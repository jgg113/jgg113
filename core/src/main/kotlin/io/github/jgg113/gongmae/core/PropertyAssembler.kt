package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime

/**
 * API 원본 행(1행 = 물건의 회차 1개)을 물건 단위로 묶는다.
 *
 * 현재 회차는 "입찰 종료가 아직 지나지 않은 회차 중 입찰 시작이 가장 이른 회차"다.
 * 회차 번호(pbctNsq) 최댓값은 가장 먼 미래의 회차이므로 쓰지 않는다.
 */
object PropertyAssembler {
    private val statusNames = mapOf(
        "0001" to "입찰준비중",
        "0002" to "입찰진행중",
        "0003" to "입찰마감",
        "0006" to "개찰중",
        "0009" to "수의계약가능",
        "0010" to "낙찰",
        "0011" to "유찰",
        "0012" to "취소",
    )

    fun assemble(rows: List<JsonObject>, now: LocalDateTime): List<Property> =
        rows.filter { it.str("cltrMngNo").isNotEmpty() }
            .groupBy { it.str("cltrMngNo") }
            .map { (_, group) -> assembleOne(group, now) }

    fun assembleOne(group: List<JsonObject>, now: LocalDateTime): Property {
        val current = selectCurrent(group, now)
        val rounds = group.map(::toRound)
            .distinctBy { Triple(it.conditionNo, it.round, it.bidStart) }
            .sortedBy { it.bidStart.ifEmpty { "9".repeat(12) } }

        fun pick(vararg keys: String): String =
            current.firstStr(*keys).ifEmpty { group.asSequence().map { it.firstStr(*keys) }.firstOrNull { it.isNotEmpty() }.orEmpty() }

        val statusCode = current.str("pbctStatCd")
        val rawStatusName = current.str("pbctStatNm")
        // pbctStatNm 에 코드가 그대로 들어오는 경우가 있다.
        val statusName = if (rawStatusName.isEmpty() || rawStatusName == statusCode) statusNames[statusCode].orEmpty() else rawStatusName

        val sido = pick("lctnSdnm")
        val sigungu = pick("lctnSggnm")
        val dong = pick("lctnEmdNm")
        val name = pick("onbidCltrNm", "cltrNm")
        val pnu = pick("ltnoPnu")
        // 전체 주소는 물건상세에만 있다. 목록에서는 PNU → 물건명 순으로 지번을 찾아 조립한다.
        val lot = AddressParser.lotFromPnu(pnu) ?: AddressParser.lotFromName(name, dong)
        val address = pick("zadrNm", "cltrAdr", "cltrRadr")
            .ifEmpty { if (lot != null && dong.isNotEmpty()) AddressParser.join(sido, sigungu, dong, lot) else "" }
        val road = pick("cltrRadr").takeIf { it != address }.orEmpty()
        val building = group.firstNotNullOfOrNull { it.decimal("bldSqms") }.takeIf { (it ?: 0.0) > 0.0 }
        val land = group.firstNotNullOfOrNull { it.decimal("landSqms") }.takeIf { (it ?: 0.0) > 0.0 }
        val assetCode = pick("prptDivCd")
        val thumbnail = absoluteImageUrl(pick("thnlImgUrlAdr", "thmImgUrl", "cltrImgUrl"))

        return Property(
            id = current.str("cltrMngNo"),
            conditionNo = current.str("pbctCdtnNo"),
            name = name,
            address = address,
            roadAddress = road,
            sido = sido,
            sigungu = sigungu,
            dong = dong,
            assetTypeCode = assetCode,
            assetTypeName = pick("prptDivNm").ifEmpty { AssetType.labelOf(assetCode) },
            disposalMethod = pick("dspsMthodNm"),
            bidMethod = pick("bidMthodNm"),
            usageLarge = pick("cltrUsgLclsCtgrNm"),
            usageMedium = pick("cltrUsgMclsCtgrNm"),
            usageSmall = pick("cltrUsgSclsCtgrNm"),
            buildingAreaSqm = building,
            landAreaSqm = land,
            areaSqm = building ?: land ?: group.firstNotNullOfOrNull(::areaFromList),
            appraisalPrice = group.firstNotNullOfOrNull(::appraisalOf),
            minBidPrice = current.amount("lowstBidPrcIndctCont", "lowstBidPrc"),
            failCount = parseAmount(current.str("usbdNft"))?.toInt()
                ?: group.mapNotNull { parseAmount(it.str("usbdNft"))?.toInt() }.maxOrNull() ?: 0,
            statusCode = statusCode,
            statusName = statusName,
            round = current.str("pbctNsq"),
            bidStart = normalizeDateTime(current.str("cltrBidBgngDt")),
            bidEnd = normalizeDateTime(current.str("cltrBidEndDt"), endOfDay = true),
            thumbnailUrl = thumbnail,
            photoUrls = OnbidLinks.photoUrls(thumbnail),
            organization = pick("orgNm", "exctOrgNm", "rqstOrgNm"),
            privateContract = pick("pvctTrgtYn").equals("Y", ignoreCase = true),
            rounds = rounds,
            onbidCltrNo = current.str("onbidCltrno").ifEmpty { pick("onbidCltrno") },
            onbidPbancNo = current.str("onbidPbancNo").ifEmpty { pick("onbidPbancNo") },
            pbctNo = current.str("pbctNo").ifEmpty { pick("pbctNo") },
            lotPnu = pnu,
            lawdCode = pnu.takeIf { it.length == 19 && it.all(Char::isDigit) }?.take(5).orEmpty(),
            requestOrg = pick("rqstOrgNm"),
            bidType = pick("bidDivNm"),
            distributionDeadline = pick("dtbtRqrEdtmCont").filter { it.isDigit() }.take(8),
            evictionBy = pick("evcRsbyTrgtCont"),
            jointBidAllowed = pick("collbBidPsblYn"),
            proxyBidAllowed = pick("subtBidPsblYn"),
        )
    }

    fun selectCurrent(group: List<JsonObject>, now: LocalDateTime): JsonObject {
        require(group.isNotEmpty())
        val nowKey = now.format(Property.KEY_FORMAT)
        fun start(o: JsonObject) = normalizeDateTime(o.str("cltrBidBgngDt")).ifEmpty { "9".repeat(12) }
        fun end(o: JsonObject) = normalizeDateTime(o.str("cltrBidEndDt"), endOfDay = true)
        val live = group.filter { end(it).let { e -> e.isEmpty() || e >= nowKey } }
        return if (live.isNotEmpty()) live.minBy(::start) else group.maxBy(::end)
    }

    private fun toRound(o: JsonObject) = BidRound(
        round = o.str("pbctNsq"),
        conditionNo = o.str("pbctCdtnNo"),
        bidStart = normalizeDateTime(o.str("cltrBidBgngDt")),
        bidEnd = normalizeDateTime(o.str("cltrBidEndDt"), endOfDay = true),
        minBidPrice = o.amount("lowstBidPrcIndctCont", "lowstBidPrc"),
        statusName = o.str("pbctStatNm").let { name -> statusNames[name] ?: name },
    )

    /** 감정평가금액: 직접 필드 우선, 없으면 감정평가 목록의 평균. */
    private fun appraisalOf(o: JsonObject): Long? {
        o.amount("apslEvlAmt")?.takeIf { it > 0 }?.let { return it }
        val amounts = o["apslEvlClgList"].objects("apslEvlClg").mapNotNull { it.amount("apslEvlAmt") }.filter { it > 0 }
        return if (amounts.isEmpty()) null else amounts.sum() / amounts.size
    }

    private fun areaFromList(o: JsonObject): Double? =
        o["sqmsList"].objects("sqms").firstNotNullOfOrNull { parseDecimal(it.str("sqmsCont").substringBefore("㎡").substringBefore("m")) }
            ?.takeIf { it > 0.0 }

    private fun absoluteImageUrl(url: String): String = when {
        url.isEmpty() -> ""
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://www.onbid.co.kr$url"
        else -> url
    }
}
