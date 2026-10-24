package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime

/** 온비드 순위 물건 종류 (순위 API 의 cltrDivNm) */
enum class RankDivision(val label: String, val apiName: String, val movableKind: MovableKind?) {
    REAL_ESTATE("부동산", "부동산", null),
    VEHICLE("자동차", "자동차", MovableKind.VEHICLE),
    GOODS("동산", "동산", MovableKind.GOODS),
}

/** 온비드 순위 목록 종류 */
enum class RankKind(val label: String, val path: String, val datasetId: String) {
    VIEWS("조회수 TOP", "OnbidInqRnkClgSrvc/getInqRnkClg", "15157704"),
    INTEREST("관심물건 TOP (최근 1주)", "OnbidItrsCltrRnkClgSrvc/getItrsCltrRnkClg", "15157699"),
    DISCOUNT("저감률 TOP (많이 깎인)", "Onbid50PctDecrCltrSrvc/get50PctDecrCltr", "15157709"),
}

/** 용도별 입찰 통계 한 줄 */
data class UsageStat(
    val usage: String,
    val bidCount: Int,
    val soldCount: Int,
    /** 낙찰률(%) */
    val soldRate: Double?,
    /** 경쟁률 */
    val competition: Double?,
    /** 낙찰가율(%) — 감정가 대비 낙찰가 */
    val priceRate: Double?,
)

/** 물건 회차별 입찰 이력 (물건상세 입찰정보) */
data class BidHistoryRound(
    val round: String,
    val openedAt: String,
    val result: String,
    val minPrice: Long?,
    val winningPrice: Long?,
    /** 감정가 대비 최저가 비율(%) */
    val ratio: Double?,
)

/** 물건 입찰 결과 (낙찰·유찰·취소) */
data class BidResultInfo(
    val result: String,
    val openedAt: String,
    val winningPrice: Long?,
    val appraisalPrice: Long?,
    val minPrice: Long?,
    val bidders: Int?,
    /** 이름 → 값 (원문 그대로, 위에서 못 읽은 것까지) */
    val fields: List<Pair<String, String>>,
) {
    /** 낙찰가율(%) */
    val winningRatio: Double?
        get() {
            val win = winningPrice ?: return null
            val base = appraisalPrice ?: return null
            return if (base > 0) win * 100.0 / base else null
        }
}

/**
 * 차세대 온비드 부가 서비스 (모두 공공데이터포털에서 따로 활용신청). 같은 인증키를 쓴다.
 * - 순위물건목록 3종 (조회수·관심물건·저감률)
 * - 용도별 입찰 통계 (낙찰가율·낙찰률·경쟁률)
 * - 물건상세 입찰정보 (회차별 이력과 낙찰가)
 * - 물건 입찰결과상세
 */
class OnbidExtrasClient(private val onbid: OnbidClient) {
    fun ranking(kind: RankKind, division: RankDivision, rows: Int = 30, now: LocalDateTime = LocalDateTime.now()): List<Property> {
        val page = onbid.call(kind.path, mapOf("cltrDivNm" to division.apiName, "pageNo" to "1", "numOfRows" to rows.toString()))
        return rankingItems(page.items, division, now)
    }

    /**
     * 캠코 관리 물건의 용도별 입찰 통계. [statsType] 0041 압류 · 0044 국유 · 0045 수탁/유입 · 0046 공유.
     * [period] 는 연도(2025)·월(202503)·분기(2025-1).
     */
    fun usageStats(statsType: String, period: String): List<UsageStat> {
        val page = onbid.call(
            "OnbidUsgBidStatsSrvc/getKamcoCltrUsgStats",
            mapOf("statsTypeCd" to statsType, "inqPerd" to period, "pageNo" to "1", "numOfRows" to "100"),
        )
        return parseUsageStats(page.items)
    }

    fun bidHistory(cltrMngNo: String, conditionNo: String): List<BidHistoryRound> = bidInfo(cltrMngNo, conditionNo).history

    /** 물건상세 입찰정보: 회차 이력 + 입찰 방법·제한·제출서류 + 공고관리번호 (공고문 조회에 쓴다) */
    fun bidInfo(cltrMngNo: String, conditionNo: String): BidInfo {
        val params = buildMap {
            put("cltrMngNo", cltrMngNo)
            if (conditionNo.isNotBlank()) put("pbctCdtnNo", conditionNo)
            put("pageNo", "1")
            put("numOfRows", "10")
        }
        val rows = onbid.call("OnbidCltrBidDtlSrvc2/getCltrBidInf2", params).items
        val row = rows.firstOrNull()
        return BidInfo(
            noticeNo = row?.str("pbancMngNo").orEmpty(),
            noticeName = row?.str("onbidPbancNm").orEmpty(),
            history = parseBidHistory(rows),
            rules = row?.let(OnbidMoreClient::parseRules).orEmpty(),
        )
    }

    fun bidResult(cltrMngNo: String, conditionNo: String = ""): List<BidResultInfo> {
        val params = mutableMapOf("cltrMngNo" to cltrMngNo, "pageNo" to "1", "numOfRows" to "20")
        if (conditionNo.isNotBlank()) params["pbctCdtnNo"] = conditionNo
        val page = onbid.call("OnbidCltrBidRsltDtlSrvc2/getCltrBidRsltDtl2", params)
        return parseBidResults(page.items)
    }

    companion object {
        /** 통계 종류 (statsTypeCd) */
        val STATS_TYPES: List<Pair<String, String>> = listOf("0041" to "압류재산", "0044" to "국유재산", "0045" to "수탁·유입", "0046" to "공유재산")

        /** 순위 응답은 물건목록과 필드가 거의 같다. 없는 필드는 비워 둔다. */
        fun rankingItems(rows: List<JsonObject>, division: RankDivision, now: LocalDateTime): List<Property> {
            val nowKey = now.format(Property.KEY_FORMAT)
            return rows.mapNotNull { row ->
                val id = row.str("cltrMngNo").ifEmpty { return@mapNotNull null }
                val address = row.firstStr("cltrAdr", "zadrNm", "cltrRadr", "ldnmAdr")
                val region = Regions.fromAddress(listOf(row.str("lctnSdnm"), row.str("lctnSggnm"), address).joinToString(" "))
                val start = normalizeDateTime(row.firstStr("cltrBidBgngDt", "bidBgngDt"))
                val end = normalizeDateTime(row.firstStr("cltrBidEndDt", "bidEndDt"), endOfDay = true)
                Property(
                    id = id,
                    conditionNo = row.firstStr("pbctCdtnNo", "pbctsn"),
                    name = row.firstStr("onbidCltrNm", "cltrNm"),
                    address = address,
                    sido = row.str("lctnSdnm").ifEmpty { region?.first.orEmpty() },
                    sigungu = row.str("lctnSggnm").ifEmpty { region?.second.orEmpty() },
                    assetTypeCode = row.str("prptDivCd"),
                    assetTypeName = row.str("prptDivNm"),
                    disposalMethod = row.str("dspsMthodNm"),
                    bidMethod = row.str("bidMthodNm"),
                    usageLarge = row.str("cltrUsgLclsCtgrNm").ifEmpty { division.label },
                    usageMedium = row.str("cltrUsgMclsCtgrNm"),
                    usageSmall = row.str("cltrUsgSclsCtgrNm"),
                    appraisalPrice = row.amount("apslEvlAmt")?.takeIf { it > 0 },
                    minBidPrice = row.amount("lowstBidPrcIndctCont", "lowstBidPrc")?.takeIf { it > 0 },
                    failCount = row.str("usbdNft").toIntOrNull() ?: 0,
                    statusCode = row.str("pbctStatCd"),
                    statusName = row.str("pbctStatNm").ifEmpty { STATUS[row.str("pbctStatCd")].orEmpty() },
                    round = row.str("pbctNsq"),
                    bidStart = start,
                    bidEnd = end,
                    thumbnailUrl = row.firstStr("thmImgUrl", "thnlImgUrlAdr"),
                    onbidCltrNo = row.str("onbidCltrNo"),
                    onbidPbancNo = row.str("onbidPbancNo"),
                    pbctNo = row.str("pbctNo"),
                    lotPnu = row.str("ltnoPnu"),
                    lawdCode = row.str("ltnoPnu").takeIf { it.length == 19 }?.take(5).orEmpty(),
                    viewCount = row.str("inqCnt").toIntOrNull() ?: 0,
                    interestCount = row.firstStr("itrsCltrCnt", "intrstCnt").toIntOrNull() ?: 0,
                    source = Source.ONBID,
                    movableKind = division.movableKind,
                ).let { p -> if (p.bidEnd.isNotEmpty() && p.bidEnd < nowKey && p.statusName.isEmpty()) p.copy(statusName = "입찰마감") else p }
            }.distinctBy { it.id }
        }

        fun parseUsageStats(rows: List<JsonObject>): List<UsageStat> = rows.mapNotNull { row ->
            val usage = row.firstStr("clsCdNm", "usgNm").ifEmpty { return@mapNotNull null }
            UsageStat(
                usage = usage,
                bidCount = parseDecimal(row.str("pbctCltrCnt"))?.toInt() ?: 0,
                soldCount = parseDecimal(row.str("mtSumCnt"))?.toInt() ?: 0,
                soldRate = parseDecimal(row.str("scbrt")),
                competition = parseDecimal(row.str("compRate")),
                priceRate = parseDecimal(row.firstStr("scfbAmtRto1", "scfbAmtRto")),
            )
        }

        fun parseBidHistory(rows: List<JsonObject>): List<BidHistoryRound> {
            val history = rows.flatMap { row ->
                when (val list = row["prcnBidClgList"]) {
                    is JsonArray -> list.filterIsInstance<JsonObject>()
                    else -> list.objects("item")
                }
            }
            return history.map { h ->
                BidHistoryRound(
                    round = h.str("pbctNsq").trimStart('0').ifEmpty { h.str("pbctNsq") },
                    openedAt = normalizeDateTime(h.firstStr("cltrOpbdDt", "opbdDt")),
                    result = h.str("pbctStatNm"),
                    minPrice = h.amount("lowstBidPrcIndctCont")?.takeIf { it > 0 },
                    winningPrice = h.amount("scfbAmt")?.takeIf { it > 0 },
                    ratio = parseDecimal(h.str("apslPrcCtrsLowstBidRto")),
                )
            }.sortedBy { it.openedAt }
        }

        /** 물건 입찰결과상세 필드 이름 (보여 줄 것만, 순서대로) */
        private val resultLabels = linkedMapOf(
            "pbctNsq" to "회차", "pbctStatNm" to "결과", "cltrOpbdDt" to "개찰일시", "opbdCmptnDt" to "개찰완료",
            "apslEvlAmt" to "감정가", "lowstBidPrcIndctCont" to "최저입찰가", "scfbAmt" to "낙찰가",
            "apslPrcCtrsScfbPrcRto" to "감정가 대비 낙찰가율(%)", "lowstBidCtrsScfbPrcRto" to "최저가 대비 낙찰가율(%)",
            "vldBddrNope" to "유효 입찰자 수", "nfctBddrNope" to "무효 입찰자 수", "bidAmtClgCont" to "입찰금액 목록",
            "pbctTdpsCont" to "입찰보증금", "usbdNft" to "유찰 횟수", "cptnMthodNm" to "입찰방식", "bidMthodNm" to "세부 입찰방식",
            "bidDivNm" to "입찰구분", "onbidPbancNm" to "공고명", "orgNm" to "기관", "rtrcnRsnCont" to "취소 사유",
            "afsbRtrcnRsnCont" to "매각결정 취소 사유",
        )

        fun parseBidResults(rows: List<JsonObject>): List<BidResultInfo> = rows.map { row ->
            val fields = resultLabels.mapNotNull { (key, label) ->
                val raw = row.str(key)
                if (raw.isEmpty()) return@mapNotNull null
                val value = when {
                    key == "cltrOpbdDt" || key == "opbdCmptnDt" -> normalizeDateTime(raw).let { if (it.isEmpty()) raw else Format.dateTime(it) }
                    key in setOf("apslEvlAmt", "lowstBidPrcIndctCont", "scfbAmt") -> parseAmount(raw)?.let(Format::won) ?: raw
                    key == "bidAmtClgCont" -> raw.split('|').mapNotNull { parseAmount(it)?.let(Format::won) ?: it.trim().ifEmpty { null } }.joinToString(", ")
                    else -> raw
                }
                label to value
            }
            BidResultInfo(
                result = row.firstStr("pbctStatNm").ifEmpty { STATUS[row.str("pbctStatCd")].orEmpty() },
                openedAt = normalizeDateTime(row.firstStr("cltrOpbdDt", "opbdCmptnDt")),
                winningPrice = row.amount("scfbAmt")?.takeIf { it > 0 },
                appraisalPrice = row.amount("apslEvlAmt")?.takeIf { it > 0 },
                minPrice = row.amount("lowstBidPrcIndctCont")?.takeIf { it > 0 },
                bidders = row.str("vldBddrNope").toIntOrNull(),
                fields = fields,
            )
        }

        /** 물건목록의 상태코드 (pbctStatCd) */
        val STATUS: Map<String, String> = mapOf(
            "0001" to "입찰준비중", "0002" to "입찰진행중", "0003" to "입찰마감", "0006" to "개찰중",
            "0009" to "수의계약가능", "0010" to "낙찰", "0011" to "유찰", "0012" to "취소",
        )

        /** 이 물건 용도와 가장 잘 맞는 통계 줄 ("아파트" → "아파트", "다세대주택" → "다세대·연립"…) */
        fun statFor(p: Property, stats: List<UsageStat>): UsageStat? {
            if (stats.isEmpty()) return null
            val words = listOf(p.usageSmall, p.usageMedium, p.category.label).filter { it.isNotBlank() }
            for (word in words) {
                stats.firstOrNull { it.usage == word }?.let { return it }
                stats.firstOrNull { it.usage.contains(word) || word.contains(it.usage) }?.let { return it }
                val head = word.take(2)
                stats.firstOrNull { head.length == 2 && it.usage.contains(head) }?.let { return it }
            }
            return null
        }

        /** 통계 기본 기간: 작년 한 해 (올해는 아직 표본이 적다) */
        fun defaultPeriod(today: LocalDate = LocalDate.now()): String = (today.year - 1).toString()
    }
}
