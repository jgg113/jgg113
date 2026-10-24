package io.github.jgg113.gongmae.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/**
 * 차량·동산 제원. 차량·동산 물건목록/상세 API 가 주는 값만 채운다.
 * 관심물건 저장을 위해 직렬화한다.
 */
@Serializable
data class ItemSpec(
    val maker: String = "",
    val carType: String = "",
    val model: String = "",
    val year: String = "",
    val plate: String = "",
    val mileageKm: Long? = null,
    val displacementCc: Long? = null,
    val transmission: String = "",
    val fuel: String = "",
    val color: String = "",
    val quantity: String = "",
    val goodsName: String = "",
    val storage: String = "",
    val origin: String = "",
    val size: String = "",
    val weight: String = "",
    val artist: String = "",
    val membership: String = "",
    val usePeriod: String = "",
    val madeYear: String = "",
    val stockName: String = "",
) {
    /** 연식(제조연도) 4자리 */
    val yearNumber: Int? get() = Regex("(19|20)\\d{2}").find(year.ifBlank { madeYear })?.value?.toInt()

    fun age(today: LocalDate = LocalDate.now()): Int? = yearNumber?.let { today.year - it }?.takeIf { it >= 0 }

    val isVehicle: Boolean get() = mileageKm != null || plate.isNotEmpty() || carType.isNotEmpty() || fuel.isNotEmpty()

    /** 카드 한 줄 요약 ("2019년식 · 8.2만km · 경유 · 1,995cc") */
    val summary: String
        get() = listOfNotNull(
            yearNumber?.let { "${it}년식" }.takeIf { isVehicle } ?: yearNumber?.let { "${it}년" },
            mileageKm?.let(::formatKm),
            fuel.ifBlank { null },
            displacementCc?.takeIf { it > 0 }?.let { "%,dcc".format(it) },
            transmission.ifBlank { null },
            quantity.ifBlank { null }.takeIf { !isVehicle },
        ).joinToString(" · ")

    /** 상세 표 (이름 → 값) */
    val rows: List<Pair<String, String>>
        get() = listOf(
            "제조사" to maker, "차종" to carType, "모델" to model, "연식" to year, "차량번호" to plate,
            "주행거리" to (mileageKm?.let { "%,dkm".format(it) } ?: ""),
            "배기량" to (displacementCc?.takeIf { it > 0 }?.let { "%,dcc".format(it) } ?: ""),
            "변속기" to transmission, "연료" to fuel, "색상" to color, "물품명" to goodsName, "종목" to stockName,
            "작가" to artist, "회원권" to membership, "제조년도" to madeYear, "수량" to quantity, "규격" to size,
            "무게" to weight, "사용기간" to usePeriod, "원산지" to origin, "보관장소" to storage,
        ).filter { it.second.isNotBlank() }

    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        fun formatKm(km: Long): String = when {
            km >= 10_000 -> "%.1f만km".format(km / 10_000.0).replace(".0만", "만")
            else -> "%,dkm".format(km)
        }

        /** 차량·동산 목록/상세 행 하나에서 제원을 읽는다. 아무것도 없으면 null. */
        fun from(row: JsonObject): ItemSpec? = ItemSpec(
            maker = row.str("cltrMkrNm"),
            carType = row.str("carVhknNm"),
            model = row.firstStr("carMdlNm", "mdlNm"),
            year = row.str("yrmdl"),
            plate = row.str("vhrnoCont"),
            mileageKm = row.amount("drvDstc")?.takeIf { it > 0 },
            displacementCc = row.amount("dsvlm")?.takeIf { it > 0 },
            transmission = row.str("pnsNm"),
            fuel = row.str("fuelCont"),
            color = row.str("carColrNm"),
            quantity = row.str("qntyCont"),
            goodsName = row.firstStr("mvastCmdtyNm", "cltrPrdctNm"),
            storage = row.firstStr("cltrCstdPlcNm", "cstdPlcNm"),
            origin = row.str("prdlcPlorCont"),
            size = row.str("mvastSizeCont"),
            weight = row.str("cltrWt").takeIf { it != "0" }.orEmpty(),
            artist = row.str("autrNm"),
            membership = listOf(row.str("mbsNm"), row.str("mbsSctnoCont")).filter { it.isNotBlank() }.joinToString(" "),
            usePeriod = row.str("usePerdQnty").takeIf { it.isNotBlank() && it != "0" }?.let { "${it}년" }.orEmpty(),
            madeYear = row.str("mnftYr"),
            stockName = row.str("splrItmNm"),
        ).takeIf { !it.isEmpty }
    }
}

/** 감정평가서 1건 (PDF 링크는 온비드가 무료로 공개) */
data class AppraisalDoc(val org: String, val appraiser: String, val date: String, val amount: Long?, val url: String)

/** 정정 내역 1건 */
data class Correction(val date: String, val item: String, val before: String, val after: String)

/**
 * 온비드 물건상세(부동산·차량·동산 공통)의 사진·서류·유의사항.
 * 목록 API 에는 썸네일만 있으므로 상세를 불러야 실제 사진과 감정평가서 링크를 얻는다.
 */
data class OnbidDocs(
    val photos: List<String> = emptyList(),
    val photos360: List<String> = emptyList(),
    val videos: List<String> = emptyList(),
    /** 지적도 이미지 */
    val cadastralMaps: List<String> = emptyList(),
    /** 위치도 이미지 */
    val locationMaps: List<String> = emptyList(),
    val appraisals: List<AppraisalDoc> = emptyList(),
    val corrections: List<Correction> = emptyList(),
    /** 유의사항·부대조건·매수인 자격 등 (이름 → 내용) */
    val notes: List<Pair<String, String>> = emptyList(),
    /** 공매재산명세서 (압류재산) */
    val statement: List<Pair<String, String>> = emptyList(),
    val address: String = "",
    val roadAddress: String = "",
    val firstNoticeDate: String = "",
    val spec: ItemSpec? = null,
    /** 일괄입찰로 함께 파는 물건 이름 */
    val batchItems: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = photos.isEmpty() && photos360.isEmpty() && videos.isEmpty() && cadastralMaps.isEmpty() && locationMaps.isEmpty() &&
            appraisals.isEmpty() && notes.isEmpty() && statement.isEmpty() && corrections.isEmpty()

    /** 무료로 바로 열 수 있는 서류 (이름 → 링크) */
    val documents: List<Pair<String, String>>
        get() = buildList {
            appraisals.filter { it.url.isNotBlank() }.forEachIndexed { i, a ->
                val label = listOf("감정평가서", a.org, a.date.takeIf { it.length == 8 }?.let(RightsParser::formatYmd))
                    .filterNot { it.isNullOrBlank() }.joinToString(" · ")
                add((if (appraisals.size > 1) "$label (${i + 1})" else label) to a.url)
            }
            fun numbered(label: String, list: List<String>) =
                list.forEachIndexed { i, u -> add((if (list.size > 1) "$label ${i + 1}" else label) to u) }
            numbered("지적도", cadastralMaps)
            numbered("위치도", locationMaps)
            numbered("동영상", videos)
            numbered("360° 사진", photos360)
        }

    companion object {
        private val noteLabels = listOf(
            "pytnMtrsCont" to "유의사항",
            "icdlCdtnCont" to "부대조건",
            "dsplVldCont" to "매각으로 효력이 없어지지 않는 권리",
            "purrQlfcCont" to "매수인 자격",
            "evcRsbyTrgtCont" to "낙찰 후 인도·인수 책임",
            "locVntyPscdCont" to "위치 및 부근 현황",
            "utlzPscdCont" to "이용 현황",
            "cltrEtcCont" to "기타 정보",
            "etcDtlCont" to "기타 상세",
            "dlvrPlcNm" to "인도 장소",
            "cstdPlcNm" to "보관 장소",
            "cltrCstdPlcNm" to "보관 장소",
            "rentMthodNm" to "임대 방식",
            "rentPerdCont" to "임대 기간",
        )

        private val statementLabels = listOf(
            "dlgtOrgNm" to "처분청", "mngNo" to "관리번호", "pbancYmd" to "공매공고일", "dtbtRqrEdtmYmd" to "배분요구 종기",
            "pbctTdps" to "공매보증금", "zadr" to "공매재산 표시", "alcCont" to "지분", "pbctEspc" to "매각예정가격",
            "bidPerd" to "입찰기간", "opbdYmd" to "개찰일", "dodispDudt" to "매각결정기일", "ersrExclRgtCont" to "말소되지 않는 권리",
            "sttyEbrVldCont" to "법정지상권", "pytnMtrsCont" to "유의사항", "prcvYmd" to "현황 조사일", "etcSmryCont" to "기타",
            "szrPrptIndctCont" to "압류재산 표시",
        )

        /** "a.jpg|b.jpg" 나 [{urlAdr}] 모두 받는다. */
        internal fun urls(row: JsonObject, key: String): List<String> {
            val element = row[key]
            val fromObjects = element.objects().mapNotNull { it.firstStr("urlAdr", "url").ifEmpty { null } }
            val raw = if (fromObjects.isEmpty()) row.str(key).split('|') else fromObjects
            return raw.map { absolute(it.trim()) }.filter { it.startsWith("http") }.distinct()
        }

        fun absolute(url: String): String = when {
            url.isEmpty() -> ""
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "https://www.onbid.co.kr$url"
            else -> url
        }

        fun parse(rows: List<JsonObject>): OnbidDocs {
            if (rows.isEmpty()) return OnbidDocs()
            fun first(key: String) = rows.asSequence().map { it.str(key) }.firstOrNull { it.isNotEmpty() }.orEmpty()
            fun allUrls(key: String) = rows.flatMap { urls(it, key) }.distinct()
            fun list(key: String) = rows.asSequence().map { it[key].objects() }.firstOrNull { it.isNotEmpty() }.orEmpty()

            val appraisals = list("apslEvlClgList").map {
                AppraisalDoc(
                    org = it.str("apslEvlOrgNm"),
                    appraiser = it.str("apslApprNm"),
                    date = it.str("apslEvlYmd").filter(Char::isDigit).take(8),
                    amount = it.amount("apslEvlAmt")?.takeIf { a -> a > 0 },
                    url = absolute(it.str("urlAdr")),
                )
            }.distinct()
            val corrections = list("crtnLstClgList").map {
                Correction(it.str("crtnYmd").filter(Char::isDigit).take(8), it.str("crtnItemCont"), it.str("bfmdfLstCont"), it.str("afmdfLstCont"))
            }.filter { it.item.isNotBlank() || it.after.isNotBlank() }
            val notes = noteLabels.mapNotNull { (key, label) -> first(key).takeIf { it.isNotBlank() && it != "0" }?.let { label to it } }
                .distinctBy { it.first }
            val statement = list("papsInf").firstOrNull()?.let { s ->
                statementLabels.mapNotNull { (key, label) -> s.str(key).takeIf { it.isNotBlank() }?.let { label to it } }
            }.orEmpty()
            val batch = list("batcBidCltrClgList").mapNotNull { it.str("onbidCltrNm").ifEmpty { null } }.distinct()
            return OnbidDocs(
                photos = allUrls("potoUrlList"),
                photos360 = allUrls("poto360DgrUrlList"),
                videos = allUrls("vdoUrlAdrList"),
                cadastralMaps = allUrls("lrmUrlAdrList"),
                locationMaps = allUrls("lmapUrlAdrList"),
                appraisals = appraisals,
                corrections = corrections,
                notes = notes,
                statement = statement,
                address = first("zadrNm"),
                roadAddress = first("cltrRadr"),
                firstNoticeDate = first("frstPbancYmd").filter(Char::isDigit).take(8),
                spec = rows.firstNotNullOfOrNull { ItemSpec.from(it) },
                batchItems = batch,
            )
        }
    }
}
