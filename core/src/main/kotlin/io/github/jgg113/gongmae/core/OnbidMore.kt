package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 온비드 물건유형 코드 (입찰결과 API 의 cltrTypeCd) */
enum class ResultType(val code: String, val label: String) {
    REAL_ESTATE("0001", "부동산"),
    VEHICLE("0002", "자동차"),
    GOODS("0003", "동산");

    companion object {
        fun of(p: Property): ResultType = when (p.movableKind) {
            MovableKind.VEHICLE -> VEHICLE
            MovableKind.GOODS -> GOODS
            null -> if (p.category == Category.VEHICLE) VEHICLE else if (p.category == Category.MOVABLE) GOODS else REAL_ESTATE
        }
    }
}

/** 낙찰(또는 유찰) 사례 1건 — 물건 입찰결과목록·공고 입찰결과상세 */
data class SoldCase(
    val id: String,
    val conditionNo: String,
    val name: String,
    val usage: String,
    val region: String,
    val openedAt: String,
    val status: String,
    val appraisal: Long?,
    val minPrice: Long?,
    val winningPrice: Long?,
    /** 감정가 대비 낙찰가율(%) */
    val ratio: Double?,
    /** 최저가 대비 낙찰가율(%) */
    val minRatio: Double?,
    val bidders: Int?,
    val invalidBidders: Int?,
    val landAreaSqm: Double?,
    val buildingAreaSqm: Double?,
    val spec: ItemSpec?,
) {
    val sold: Boolean get() = winningPrice != null && winningPrice > 0
}

/** 비슷한 물건의 낙찰 사례 묶음 */
data class SoldStats(
    val scope: String,
    val months: Int,
    val cases: List<SoldCase>,
) {
    val sold: List<SoldCase> get() = cases.filter { it.sold }

    /** 감정가 대비 낙찰가율 중간값(%) */
    val medianRatio: Double?
        get() = sold.mapNotNull { it.ratio }.filter { it in 10.0..300.0 }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /** 최저가 대비 낙찰가율 중간값(%) */
    val medianMinRatio: Double?
        get() = sold.mapNotNull { it.minRatio }.filter { it in 50.0..500.0 }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    val averageBidders: Double?
        get() = sold.mapNotNull { it.bidders?.toDouble() }.takeIf { it.isNotEmpty() }?.average()

    /** 이 감정가라면 예상 낙찰가 (사례 중간값 기준) */
    fun expectedPrice(appraisal: Long?): Long? {
        val ratio = medianRatio ?: return null
        if (appraisal == null || appraisal <= 0) return null
        return Math.round(appraisal * ratio / 100)
    }
}

/** 개찰이 끝난 공고 (공고 입찰결과목록) */
data class NoticeResult(
    val noticeNo: String,
    val name: String,
    val org: String,
    val assetType: String,
    val noticeDate: String,
    val openedAt: String,
    val round: String,
    val state: String,
)

/** 공고 상세 (공고상세 + 공고상세 입찰정보) */
data class NoticeInfo(
    val noticeNo: String,
    val name: String = "",
    val org: String = "",
    val noticeDate: String = "",
    val kind: String = "",
    val bidMethod: String = "",
    /** 입찰금액 공개 여부 */
    val bidAmountsPublic: Boolean? = null,
    val fee: String = "",
    val qualification: String = "",
    val cancelReason: String = "",
    /** 공고문 전문 */
    val fullText: String = "",
    val attachments: List<Pair<String, String>> = emptyList(),
    /** 입찰 방법·제한 (이름 → 값) */
    val rules: List<Pair<String, String>> = emptyList(),
    val schedule: List<NoticeRound> = emptyList(),
)

data class NoticeRound(val round: String, val bidStart: String, val bidEnd: String, val openedAt: String, val place: String, val decisionAt: String)

/** 물건상세 입찰정보 (회차 이력 + 입찰 방법·제출서류) */
data class BidInfo(
    val noticeNo: String,
    val noticeName: String,
    val history: List<BidHistoryRound>,
    val rules: List<Pair<String, String>>,
)

/** 정부재산 목록 종류 */
enum class GovPropertyKind(val label: String, val path: String, val about: String) {
    NATIONAL("국유 일반재산", "OnbidGvwsGnrPrptInfSrvc/getGvwsGnrPrptInf", "캠코가 관리하는 국유지·국유건물. '매각(입찰)검토'·'대부(입찰)검토'면 곧 공매나 대부가 나올 수 있습니다."),
    PUBLIC("공유 일반재산", "OnbidPbownGnrPrptInfSrvc/getPbownGnrPrptInf", "지자체가 온비드에 공개한 공유재산. 대부·매각 대상 여부가 표시됩니다."),
    JAPANESE("친일 귀속재산", "OnbidPjpnBlnPrptInfSrvc/getPjpnBlnPrptInf", "국가보훈부 귀속재산 (캠코 위탁 관리)"),
}

data class GovProperty(
    val kind: GovPropertyKind,
    val name: String,
    val address: String,
    val roadAddress: String,
    val pnu: String,
    val landUse: String,
    val landAreaSqm: Double?,
    val buildingAreaSqm: Double?,
    val usage: String,
    /** 관리상태 또는 대부·매각 구분 */
    val status: String,
    val org: String,
    val note: String,
    val appraisal: Long?,
    val docUrl: String,
)

/** 정부 불용품 */
data class DisusedGoods(val name: String, val assetNo: String, val condition: String, val quantity: String, val bookValue: Long?, val decidedAt: String)

/** 유가증권 상세 */
data class SecurityInfo(
    val name: String,
    val ceo: String,
    val kind: String,
    val industry: String,
    val products: String,
    val foundedAt: String,
    val shareRatio: String,
    val faceValue: Long?,
    val totalShares: String,
    val shareholders: List<Pair<String, String>>,
    /** 연도 → (항목 → 금액·비율) */
    val financials: List<Pair<String, List<Pair<String, String>>>>,
)

/** 온비드 용도 코드 */
data class UsageCode(val id: String, val name: String, val parentId: String, val parentName: String)

/**
 * 차세대 온비드 나머지 서비스 (모두 같은 인증키, 공공데이터포털에서 서비스마다 활용신청).
 * 물건·공고 입찰결과, 공고상세·공고 입찰정보, 지역별 통계, 정부재산 4종, 유가증권, 코드·주소.
 */
class OnbidMoreClient(private val onbid: OnbidClient) {
    private fun call(path: String, params: Map<String, String>, rows: Int = 100, page: Int = 1): OnbidPage =
        onbid.call(path, params + mapOf("pageNo" to page.toString(), "numOfRows" to rows.toString()))

    /** 물건 입찰결과목록. [to] 는 오늘 이전이어야 한다. */
    fun soldCases(
        type: ResultType,
        from: LocalDate,
        to: LocalDate,
        sido: String = "",
        sigungu: String = "",
        usageSmall: String = "",
        maker: String = "",
        model: String = "",
        name: String = "",
        onlySold: Boolean = true,
        rows: Int = 100,
    ): List<SoldCase> {
        val params = buildMap {
            put("cltrTypeCd", type.code)
            put("prptDivCd", ALL_DIVS)
            put("opbdDtStart", from.format(YMD))
            put("opbdDtEnd", to.format(YMD))
            if (onlySold) put("pbctStatCd", "0010")
            if (sido.isNotBlank()) put("lctnSdnm", sido)
            if (sigungu.isNotBlank()) put("lctnSggnm", sigungu)
            if (usageSmall.isNotBlank()) put("cltrUsgSclsCtgrNm", usageSmall)
            if (maker.isNotBlank()) put("cltrMkrNm", maker)
            if (model.isNotBlank()) put("carMdlNm", model)
            if (name.isNotBlank()) put("onbidCltrNm", name)
        }
        return parseSoldCases(call(RESULT_LIST_PATH, params, rows).items)
    }

    /**
     * 이 물건과 비슷한 최근 낙찰 사례. 부동산은 같은 시군구·같은 용도 → 같은 시도 순,
     * 차량은 같은 제조사·모델(전국), 동산은 같은 용도(전국).
     */
    fun comparables(p: Property, today: LocalDate = LocalDate.now(), months: Int = 12): SoldStats? {
        val type = ResultType.of(p)
        val to = today.minusDays(1)
        val from = today.minusMonths(months.toLong())
        fun fetch(sido: String = "", sigungu: String = "", usage: String = "", maker: String = "", model: String = ""): List<SoldCase> =
            soldCases(type, from, to, sido, sigungu, usage, maker, model).filter { it.id != p.id }
        val usage = p.usageSmall.ifBlank { p.usageMedium }
        return when (type) {
            ResultType.REAL_ESTATE -> {
                if (p.sido.isBlank()) return null
                val local = if (p.sigungu.isNotBlank()) fetch(p.sido, p.sigungu, usage) else emptyList()
                if (local.count { it.sold } >= 3) return SoldStats("${Regions.label(p.sido, p.sigungu)} ${usage}", months, local)
                val wide = fetch(p.sido, "", usage)
                if (wide.count { it.sold } >= 3) return SoldStats("${Regions.shortName(p.sido)} 전체 ${usage}", months, wide)
                (local + wide).distinctBy { it.id }.takeIf { it.isNotEmpty() }?.let { SoldStats("${Regions.shortName(p.sido)} $usage", months, it) }
            }
            ResultType.VEHICLE -> {
                val spec = p.spec
                val model = spec?.model.orEmpty()
                val maker = spec?.maker.orEmpty()
                val exact = if (model.isNotBlank()) fetch(maker = maker, model = model) else emptyList()
                if (exact.count { it.sold } >= 2) return SoldStats("전국 ${listOf(maker, model).filter { it.isNotBlank() }.joinToString(" ")}", months, exact)
                val byMaker = if (maker.isNotBlank()) fetch(maker = maker) else emptyList()
                val list = (exact + byMaker).distinctBy { it.id }
                list.takeIf { it.isNotEmpty() }?.let { SoldStats("전국 ${maker.ifBlank { "차량" }}", months, it) }
            }
            ResultType.GOODS -> {
                val list = fetch(usage = usage)
                list.takeIf { it.isNotEmpty() }?.let { SoldStats("전국 ${usage.ifBlank { "동산" }}", months, it) }
            }
        }
    }

    /** 개찰이 끝난 공고 목록 (공고 입찰결과목록) */
    fun noticeResults(type: ResultType, from: LocalDate, to: LocalDate, rows: Int = 50): List<NoticeResult> {
        val params = mapOf(
            "cltrTypeCd" to type.code, "prptDivCd" to ALL_DIVS,
            "opbdDtStart" to from.format(YMD), "opbdDtEnd" to to.format(YMD), "exctStatCd" to "0003",
        )
        return call(NOTICE_RESULT_LIST_PATH, params, rows).items.mapNotNull { row ->
            val no = row.str("pbancMngNo").ifEmpty { return@mapNotNull null }
            NoticeResult(
                noticeNo = no,
                name = row.str("onbidPbancNm"),
                org = row.firstStr("orgNm", "rspbSbrNm"),
                assetType = row.str("prptDivNm"),
                noticeDate = row.str("pbancYmd").filter(Char::isDigit).take(8),
                openedAt = normalizeDateTime(row.str("cltrOpbdDt")),
                round = row.str("pbctNsq").trimStart('0'),
                state = row.str("exctStatNm"),
            )
        }.distinctBy { it.noticeNo to it.round }
    }

    /** 공고에 속한 물건들의 회차별 입찰 결과 (공고 입찰결과상세) */
    fun noticeResultDetail(noticeNo: String): List<SoldCase> {
        val rows = call(NOTICE_RESULT_DETAIL_PATH, mapOf("pbancMngNo" to noticeNo), 100).items
        return parseSoldCases(rows.flatMap { row -> row["cltrBidRsltClgList"].objects().ifEmpty { listOf(row) } })
    }

    /** 공고상세 + 공고상세 입찰정보. 한쪽이 안 되면 되는 쪽만 채운다. */
    fun notice(noticeNo: String): NoticeInfo {
        var failure: OnbidApiException? = null
        val detail = try {
            call(NOTICE_DETAIL_PATH, mapOf("pbancMngNo" to noticeNo), 10).items
        } catch (e: OnbidApiException) {
            if (e.code == OnbidApiException.NETWORK || e.code == "22") throw e
            failure = e
            emptyList()
        }
        val bid = try {
            call(NOTICE_BID_PATH, mapOf("pbancMngNo" to noticeNo), 10).items
        } catch (e: OnbidApiException) {
            if (e.code == OnbidApiException.NETWORK || e.code == "22") throw e
            failure = failure ?: e
            emptyList()
        }
        if (detail.isEmpty() && bid.isEmpty() && failure != null) throw failure!!
        return parseNotice(noticeNo, detail, bid)
    }

    /** 캠코 물건 지역별 입찰 통계. [statsType] 0021 압류 · 0024 국유 · 0025 수탁/유입 · 0026 공유, "ORG" 면 이용기관 물건. */
    fun regionStats(statsType: String, period: String): List<UsageStat> {
        val (path, params) = if (statsType == ORG) {
            "OnbidClarBidStatsSrvc/getOrgCltrClarStats" to mapOf("inqPerd" to period)
        } else {
            "OnbidClarBidStatsSrvc/getKamcoCltrClarStats" to mapOf("statsTypeCd" to statsType, "inqPerd" to period)
        }
        return OnbidExtrasClient.parseUsageStats(call(path, params, 100).items)
    }

    /** 이용기관(지자체·공공기관) 물건 용도별 통계 */
    fun orgUsageStats(period: String): List<UsageStat> =
        OnbidExtrasClient.parseUsageStats(call("OnbidUsgBidStatsSrvc/getOrgCltrUsgStats", mapOf("inqPerd" to period), 100).items)

    /** 정부재산 목록 (시도 필수) */
    fun govProperties(kind: GovPropertyKind, sido: String, sigungu: String = "", page: Int = 1, rows: Int = 100): Pair<List<GovProperty>, Int?> {
        val params = buildMap {
            put("sdnm", sido)
            if (sigungu.isNotBlank()) put("sggnm", sigungu)
        }
        val result = call(kind.path, params, rows, page)
        return parseGovProperties(kind, result.items, sido, sigungu) to result.totalCount
    }

    fun disusedGoods(page: Int = 1, rows: Int = 100): Pair<List<DisusedGoods>, Int?> {
        val result = call("OnbidDisuseCmdtyInfSrvc/getDisuseCmdtyInf", emptyMap(), rows, page)
        return parseDisused(result.items) to result.totalCount
    }

    fun security(cltrMngNo: String): SecurityInfo? =
        parseSecurity(call("OnbidSctDtlInfSrvc/getSctDtlInf", mapOf("cltrMngNo" to cltrMngNo), 10).items)

    fun usageCodes(parentId: String = ""): List<UsageCode> {
        val params = if (parentId.isBlank()) emptyMap() else mapOf("upCtgrId" to parentId)
        return call("OnbidCodeSrvc/getOnbidUsgCodeInfo", params, 500).items.mapNotNull { row ->
            val id = row.str("ctgrId").ifEmpty { return@mapNotNull null }
            UsageCode(id, row.str("ctgrNm"), row.str("upCtgrId"), row.str("upCtgrNm"))
        }
    }

    /** 온비드에 등록된 주소 (시도 → 시군구 → 읍면동). 시군구 표기가 우리와 다를 때 찾아 쓴다. */
    fun addresses(sido: String = "", sigungu: String = "", rows: Int = 1000): List<Triple<String, String, String>> {
        val params = buildMap {
            if (sido.isNotBlank()) put("sdnm", sido)
            if (sigungu.isNotBlank()) put("sggnm", sigungu)
        }
        return call("OnbidCodeSrvc/getOnbidDtlAddrInfo", params, rows).items.map { row ->
            Triple(row.str("sdnm"), row.str("sggnm"), row.str("emdNm"))
        }.filter { it.first.isNotBlank() }.distinct()
    }

    companion object {
        const val RESULT_LIST_PATH = "OnbidCltrBidRsltListSrvc2/getCltrBidRsltList2"
        const val RESULT_DETAIL_PATH = "OnbidCltrBidRsltDtlSrvc2/getCltrBidRsltDtl2"
        const val NOTICE_RESULT_LIST_PATH = "OnbidPbancBidRsltListSrvc2/getPbancBidRsltList2"
        const val NOTICE_RESULT_DETAIL_PATH = "OnbidPbancBidRsltDtlSrvc2/getPbancBidRsltDtl2"
        const val NOTICE_DETAIL_PATH = "OnbidPbancDtlnfSrvc2/getPbancDtlInf2"
        const val NOTICE_BID_PATH = "OnbidPbancBidDtlSrvc2/getPbancBidInf2"
        const val ORG = "ORG"
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /** 압류·불용품·기타일반·공유·국유·수탁·파산·금융권담보·유입·공공개발 */
        val ALL_DIVS = listOf("0007", "0004", "0005", "0002", "0010", "0008", "0013", "0003", "0006", "0011").joinToString(",")

        /** 지역별 통계 종류 (statsTypeCd) — 용도별(0041…)과 코드가 다르다. */
        val REGION_STATS_TYPES: List<Pair<String, String>> =
            listOf("0021" to "압류재산", "0024" to "국유재산", "0025" to "수탁·유입", "0026" to "공유재산", ORG to "이용기관")

        /** 용도별 통계 코드 → 지역별 통계 코드 */
        fun regionTypeOf(usageType: String): String = mapOf("0041" to "0021", "0044" to "0024", "0045" to "0025", "0046" to "0026")[usageType] ?: "0021"

        fun parseSoldCases(rows: List<JsonObject>): List<SoldCase> = rows.mapNotNull { row ->
            val id = row.str("cltrMngNo").ifEmpty { return@mapNotNull null }
            val appraisal = row.amount("apslEvlAmt")?.takeIf { it > 0 }
            val win = row.amount("scfbAmt")?.takeIf { it > 0 }
            val min = row.amount("lowstBidPrcIndctCont")?.takeIf { it > 0 }
            SoldCase(
                id = id,
                conditionNo = row.str("pbctCdtnNo"),
                name = row.str("onbidCltrNm"),
                usage = row.str("cltrUsgSclsCtgrNm").ifEmpty { row.str("cltrUsgMclsCtgrNm") },
                region = listOf(row.str("lctnSdnm"), row.str("lctnSggnm"), row.str("lctnEmdNm")).filter { it.isNotBlank() }.joinToString(" "),
                openedAt = normalizeDateTime(row.str("cltrOpbdDt")),
                status = row.str("pbctStatNm").ifEmpty { OnbidExtrasClient.STATUS[row.str("pbctStatCd")].orEmpty() },
                appraisal = appraisal,
                minPrice = min,
                winningPrice = win,
                ratio = parseDecimal(row.str("apslPrcCtrsScfbPrcRto"))?.takeIf { it > 0 }
                    ?: if (win != null && appraisal != null) win * 100.0 / appraisal else null,
                minRatio = parseDecimal(row.str("lowstBidCtrsScfbPrcRto"))?.takeIf { it > 0 }
                    ?: if (win != null && min != null) win * 100.0 / min else null,
                bidders = row.str("vldBddrNope").toIntOrNull(),
                invalidBidders = row.str("nfctBddrNope").toIntOrNull(),
                landAreaSqm = row.decimal("landSqms")?.takeIf { it > 0 },
                buildingAreaSqm = row.decimal("bldSqms")?.takeIf { it > 0 },
                spec = ItemSpec.from(row),
            )
        }.sortedByDescending { it.openedAt }

        private fun yn(value: String): String = when (value.uppercase()) {
            "Y" -> "가능"
            "N" -> "불가"
            else -> value
        }

        private val ruleLabels = listOf(
            "pbctTdpsCont" to "입찰보증금", "pcmtPayMtdCont" to "잔대금 납부방법", "pcmtPayTermCont" to "잔대금 납부기한",
            "collbBidPsblYn" to "공동입찰", "subtBidPsblYn" to "대리입찰", "eltrGrprUseYn" to "전자보증서",
            "tdpsSbtnDcmtYn" to "보증금 대체서류", "nrnkAplyPsblYn" to "차순위 매수신청", "bidPtctAplyYn" to "입찰참가신청 필요",
            "bidVldCrtrCont" to "입찰 성립 기준", "twtmGthrBidPsblYn" to "같은 물건 2회 이상 입찰", "smnsIpDpcnBidBlcktYn" to "같은 IP 중복입찰",
            "qlfcLmtCdtnCont" to "자격 제한", "rgnLmtCdtnCont" to "지역 제한", "etcLmtCdtnCont" to "기타 제한",
            "bidPtctAplyDcmtCont" to "참가신청 서류", "bidRltnDcmtCont" to "입찰 관련 서류", "subtBidDcmtCont" to "대리입찰 서류",
        )
        private val ynKeys = setOf("collbBidPsblYn", "subtBidPsblYn", "eltrGrprUseYn", "tdpsSbtnDcmtYn", "nrnkAplyPsblYn", "twtmGthrBidPsblYn", "smnsIpDpcnBidBlcktYn")

        fun parseRules(row: JsonObject): List<Pair<String, String>> = ruleLabels.mapNotNull { (key, label) ->
            val value = row.str(key).ifEmpty { return@mapNotNull null }
            label to when {
                key == "bidPtctAplyYn" -> if (value.equals("Y", true)) "필요" else if (value.equals("N", true)) "불필요" else value
                key in ynKeys -> yn(value)
                else -> value
            }
        }

        fun parseNotice(noticeNo: String, detail: List<JsonObject>, bid: List<JsonObject>): NoticeInfo {
            val d = detail.firstOrNull()
            val b = bid.firstOrNull()
            val attachments = detail.flatMap { it["atchFileList"].objects() }.mapNotNull { f ->
                val url = OnbidDocs.absolute(f.str("urlAdr")).ifEmpty { return@mapNotNull null }
                f.str("btbdAtchFileNm").ifEmpty { "첨부파일" } to url
            }.distinctBy { it.second }
            val schedule = bid.flatMap { it["cseqBidInfClgList"].objects() }.map { r ->
                NoticeRound(
                    round = r.str("pbctNsq").trimStart('0').ifEmpty { r.str("pbctNsq") },
                    bidStart = normalizeDateTime(r.str("cltrBidBgngDt")),
                    bidEnd = normalizeDateTime(r.str("cltrBidEndDt")),
                    openedAt = normalizeDateTime(r.str("cltrOpbdDt")),
                    place = r.str("pbctOpbdPlcCont"),
                    decisionAt = normalizeDateTime(r.str("cltrDodispDt")),
                )
            }.distinct().sortedBy { it.bidStart }
            return NoticeInfo(
                noticeNo = noticeNo,
                name = d?.str("onbidPbancNm").orEmpty().ifEmpty { b?.str("onbidPbancNm").orEmpty() },
                org = d?.firstStr("pbancOrgNm", "orgNm").orEmpty(),
                noticeDate = d?.str("pbancYmd").orEmpty().filter(Char::isDigit).take(8),
                kind = listOfNotNull(d?.str("pbancKindNm"), d?.str("prptDivNm"), d?.str("dspsMthodNm")).filter { it.isNotBlank() }.joinToString(" · "),
                bidMethod = listOfNotNull(d?.str("cptnMthodNm"), d?.str("bidMthodNm"), d?.str("bidDivNm")).filter { it.isNotBlank() }.joinToString(" · "),
                bidAmountsPublic = d?.str("bidAmtRlsYn")?.let { if (it.equals("Y", true)) true else if (it.equals("N", true)) false else null },
                fee = d?.str("ptctCmsn").orEmpty(),
                qualification = d?.str("pbancPtctQlfcCont").orEmpty(),
                cancelReason = d?.str("pbancRtrcnRsnCont").orEmpty(),
                fullText = d?.str("anncmAlCont").orEmpty().let(::plainText),
                attachments = attachments,
                rules = b?.let(::parseRules).orEmpty(),
                schedule = schedule,
            )
        }

        /** 공고문 전문의 HTML 태그를 걷어 낸다. */
        fun plainText(html: String): String = html
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

        fun parseGovProperties(kind: GovPropertyKind, rows: List<JsonObject>, sido: String, sigungu: String): List<GovProperty> = rows.mapNotNull { row ->
            val name = row.firstStr("gvwsPrptCltrNm", "onbidCltrNm")
            val address = row.str("zadrNm")
            if (name.isEmpty() && address.isEmpty()) return@mapNotNull null
            val appraisals = row["apslEvlClgList"].objects()
            GovProperty(
                kind = kind,
                name = name.ifEmpty { address },
                address = address.ifEmpty { listOf(sido, sigungu).filter { it.isNotBlank() }.joinToString(" ") },
                roadAddress = row.str("cltrRadr"),
                pnu = row.str("ltnoPnu"),
                landUse = row.firstStr("clandCont", "pbownPrptLandClandNm", "rlClandCont"),
                landAreaSqm = row.decimal("landSqms", "pblbSqms", "rlSqms")?.takeIf { it > 0 },
                buildingAreaSqm = row.decimal("bldSqms")?.takeIf { it > 0 },
                usage = row.firstStr("cltrUsgSclsCtgrNm", "pbownPrptBldUsgNm", "cltrUsgMclsCtgrNm", "bldDivCont"),
                status = row.firstStr("mngStatNm", "lngDsplTypeCont"),
                org = row.firstStr("dlgtOrgNm", "orgNm", "pbownPrptOrgNm"),
                note = listOf(
                    row.str("locVntyPscdCont"),
                    row.str("bldGrndFlnoCont").takeIf { it.isNotBlank() }?.let { "지상 ${it}층" }.orEmpty(),
                    row.str("cstnYmdCont").takeIf { it.isNotBlank() }?.let { "건축 $it" }.orEmpty(),
                ).filter { it.isNotBlank() }.joinToString(" · "),
                appraisal = appraisals.firstNotNullOfOrNull { it.amount("apslEvlAmt")?.takeIf { a -> a > 0 } }
                    ?: row.amount("apslEvlAmt")?.takeIf { it > 0 },
                docUrl = OnbidDocs.absolute(appraisals.firstNotNullOfOrNull { it.str("urlAdr").ifEmpty { null } } ?: row["imgFileList"].objects().firstOrNull()?.str("urlAdr").orEmpty()),
            )
        }

        fun parseDisused(rows: List<JsonObject>): List<DisusedGoods> = rows.mapNotNull { row ->
            val name = row.str("astNm").ifEmpty { return@mapNotNull null }
            DisusedGoods(
                name = name,
                assetNo = row.str("astNo"),
                condition = row.str("cmdtyStatCont"),
                quantity = row.str("ldgrQnty"),
                bookValue = row.amount("ldgrAmt")?.takeIf { it > 0 },
                decidedAt = row.str("disuseDcsnYmd").filter(Char::isDigit).take(8),
            )
        }

        private val financeLabels = linkedMapOf(
            "astTotalamt" to "자산총계", "lbtTotalamt" to "부채총계", "sumCptlAmt" to "자본총계", "cptlmAmt" to "자본금",
            "slsAmt" to "매출액", "dbizGainAmt" to "영업이익", "tstrNprfAmt" to "당기순이익", "dvrt" to "배당률(%)",
            "lbtRto" to "부채비율(%)", "slsAmtIncrt" to "매출 증가율(%)", "nprfIncrt" to "순이익 증가율(%)", "selfCptlNprfRto" to "ROE(%)",
        )
        private val ratioKeys = setOf("dvrt", "lbtRto", "slsAmtIncrt", "nprfIncrt", "selfCptlNprfRto")

        fun parseSecurity(rows: List<JsonObject>): SecurityInfo? {
            val row = rows.firstOrNull() ?: return null
            val holders = row["sthlPscdClgList"].objects().mapNotNull { h ->
                val name = h.str("sthlNm").ifEmpty { return@mapNotNull null }
                name to listOf(h.str("onrt").takeIf { it.isNotBlank() }?.let { "$it%" }, h.str("scrtStno").takeIf { it.isNotBlank() }?.let { "${it}주" })
                    .filterNotNull().joinToString(" · ")
            }
            val byYear = linkedMapOf<String, MutableList<Pair<String, String>>>()
            listOf("fnclStatClgList", "plClgList", "fnclRtoClgList").forEach { key ->
                row[key].objects().forEach { item ->
                    val year = item.str("fnclYr").ifEmpty { "-" }
                    financeLabels.forEach { (field, label) ->
                        val raw = item.str(field)
                        if (raw.isNotBlank()) {
                            val value = if (field in ratioKeys) raw else parseAmount(raw)?.let(Format::won) ?: raw
                            byYear.getOrPut(year) { mutableListOf() } += label to value
                        }
                    }
                }
            }
            return SecurityInfo(
                name = row.str("splrItmNm"),
                ceo = row.str("rprsvNm"),
                kind = row.str("sctKindCont"),
                industry = row.str("tfbNm"),
                products = row.str("sctPrmrProdNm"),
                foundedAt = row.str("fndnYmd").filter(Char::isDigit).take(8),
                shareRatio = row.str("onrt"),
                faceValue = row.amount("sctPrshDnmtAmt")?.takeIf { it > 0 },
                totalShares = row.str("pblcnStkTtlNum"),
                shareholders = holders,
                financials = byYear.entries.sortedByDescending { it.key }.map { it.key to it.value.toList() },
            )
        }
    }
}
