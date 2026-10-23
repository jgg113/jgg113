package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.random.Random

data class CourtSearchRequest(
    val sido: String,
    val sigungu: String = "",
    /** 오늘부터 몇 주 안의 매각기일까지 볼지 */
    val weeks: Int = 4,
    /** 검색 한 번에 사이트를 부르는 최대 횟수 (IP 차단 방지) */
    val maxCalls: Int = 8,
)

/** 법원경매 물건상세: 사진(JPEG 바이트)과 기일 내역. */
data class CourtDetail(
    val photos: List<ByteArray>,
    val rounds: List<BidRound>,
    val currentMinPrice: Long?,
    val objects: List<String>,
)

/**
 * 법원 경매사건 정보 (경매사건검색). 이해관계인 목록으로 임차인·유치권자 등이 권리신고를 했는지 본다.
 * [stakeholders] 는 (구분, 이름) 쌍이다. 예: ("임차인", "김OO").
 */
data class CourtCase(
    val caseName: String,
    val claimAmount: Long?,
    val receiptDate: String,
    val stakeholders: List<Pair<String, String>>,
    val appeals: Int,
    val relatedCases: Int,
    val claimDeadline: String,
) {
    data class RiskItem(val points: Int, val title: String, val detail: String)

    fun count(keyword: String): Int = stakeholders.count { it.first.contains(keyword) }

    /** 이해관계인 구성에서 읽히는 위험 신호 */
    fun riskFactors(): List<RiskItem> = buildList {
        val tenants = count("임차")
        if (tenants > 0) add(RiskItem(15, "임차인 ${tenants}명이 이해관계인", "임차인이 권리신고를 했습니다. 매각물건명세서에서 전입일·확정일자를 보고 대항력(보증금 인수) 여부를 꼭 확인하세요."))
        if (count("유치권") > 0) add(RiskItem(25, "유치권자 신고", "이해관계인에 유치권자가 있습니다. 점유를 풀려면 공사대금을 물어줘야 할 수 있습니다."))
        if (count("전세권") > 0) add(RiskItem(10, "전세권자", "전세권이 말소기준권리보다 앞서면 낙찰자가 떠안을 수 있습니다."))
        if (count("가처분") > 0) add(RiskItem(20, "가처분권자", "소유권 분쟁이 걸려 있을 수 있습니다."))
        if (count("가등기") > 0) add(RiskItem(15, "가등기권자", "선순위 소유권이전청구권 가등기면 소유권을 잃을 수 있습니다."))
        if (appeals > 0) add(RiskItem(5, "항고 ${appeals}건", "매각 절차에 이의가 제기돼 잔금·소유권 이전이 늦어질 수 있습니다."))
    }
}

/**
 * 대법원 법원경매정보(courtauction.go.kr) 화면이 쓰는 JSON 을 그대로 호출한다. 공식 오픈API 가 아니다.
 *
 * - 진입 페이지 GET 으로 세션 쿠키를 받는다. 이때 HTTP 500 이 와도 쿠키는 내려오므로 상태코드는 따지지 않는다.
 * - 이후 POST 에 Referer·X-Requested-With·submissionid·sc-userid 헤더를 붙인다.
 * - 30초에 16번쯤 부르면 IP 가 1시간 막힌다. 요청 사이에 2~3초를 두고, 검색 한 번에 부르는 횟수를 제한한다.
 * - 검색 조건이 모두 비면 400 이 나므로 시도(+시군구)와 매각기일 구간을 항상 채운다.
 */
class CourtClient(
    private val transport: WebTransport = UrlConnectionTransport(followRedirects = false),
    private val minDelayMs: Long = 2_000,
    private val jitterMs: Long = 1_000,
    private val random: (Long) -> Long = { bound -> if (bound <= 0) 0 else Random.nextLong(bound) },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val cookies = CookieJar()
    private var warmedUp = false
    private var lastPost = 0L
    private var blockedUntil = 0L
    private var pageSize = 100
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun search(request: CourtSearchRequest, now: LocalDateTime = LocalDateTime.now(), progress: ProgressListener? = null): SearchOutcome {
        val sidoCodes = sidoCodes[request.sido] ?: throw OnbidApiException("COURT", "법원경매 검색을 지원하지 않는 지역입니다: ${request.sido}")
        val from = now.toLocalDate()
        val to = from.plusWeeks(request.weeks.toLong())
        val budget = Budget(request.maxCalls)
        val sigunguCodes = if (request.sigungu.isBlank()) emptyList() else RegionCodes.sigungu(request.sido, request.sigungu)

        var rows = emptyList<JsonObject>()
        var localFilter = request.sigungu.isNotBlank()
        if (sigunguCodes.isNotEmpty()) {
            // 시군구 코드로 서버에서 바로 좁힌다. 구가 여럿인 시는 구마다 부른다.
            rows = sigunguCodes.flatMap { code -> fetchAll(sidoCodes.first(), code, from, to, budget, progress) }
            localFilter = false
        }
        if (rows.isEmpty()) {
            // 시군구 코드를 모르거나 결과가 없으면 시도 전체를 받아 주소로 거른다.
            for (code in sidoCodes) {
                rows = fetchAll(code, "", from, to, budget, progress)
                if (rows.isNotEmpty()) break
            }
        }
        val filtered = if (localFilter || request.sigungu.isBlank()) {
            rows.filter { Regions.matchesSigungu(it.str("hjguSigu"), jibunAddress(it), request.sigungu) }
        } else {
            rows
        }
        val notes = buildList {
            if (budget.exhausted) add("경매: 결과가 많아 일부(${filtered.size}건)만 불러왔습니다. 시군구를 고르거나 기간을 줄이면 전부 볼 수 있습니다.")
        }
        val properties = filtered.mapNotNull { toProperty(it) }.distinctBy { it.id }
        return SearchOutcome(properties, filtered.size, notes, regionFilteredLocally = false)
    }

    /** 검색 한 번에 사이트를 부르는 횟수 상한 (IP 차단 방지). */
    private class Budget(var remaining: Int) {
        var exhausted = false
    }

    private fun fetchAll(
        sidoCode: String,
        sigunguCode: String,
        from: LocalDate,
        to: LocalDate,
        budget: Budget,
        progress: ProgressListener?,
    ): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var page = 1
        var total: Int? = null
        while (true) {
            if (budget.remaining <= 0) {
                budget.exhausted = true
                break
            }
            budget.remaining--
            val pages = total?.let { (it + pageSize - 1) / pageSize } ?: (page + 1)
            progress?.onProgress(page - 1, pages, "법원경매 ${page}쪽")
            val data = try {
                post(SEARCH_URL, searchPayload(sidoCode, sigunguCode, from, to, page), SEARCH_SUBMISSION, "SYSTEM")
            } catch (e: OnbidApiException) {
                // 한 페이지 100건을 거절하면 50건으로 다시 시도한다.
                if (e.code == "COURT400" && pageSize > 50 && page == 1) {
                    pageSize = 50
                    continue
                }
                throw e
            }
            val rows = data["dlt_srchResult"].objects()
            total = total ?: (data["dma_pageInfo"] as? JsonObject)?.str("totalCnt")?.toIntOrNull()
            all += rows
            if (rows.size < pageSize || (total != null && page * pageSize >= total)) break
            page++
        }
        return all
    }

    fun fetchDetail(p: Property): CourtDetail {
        require(p.isCourt) { "법원경매 물건이 아닙니다" }
        val payload = buildJsonObject {
            put("dma_srchGdsDtlSrch", buildJsonObject {
                put("csNo", p.caseNo)
                put("cortOfcCd", p.courtCode)
                put("dspslGdsSeq", p.itemSeq.ifBlank { "1" })
                put("pgmId", "PGJ151F01")
                put("srchInfo", JsonObject(defaultCondition() + mapOf("csNo" to JsonPrimitive(p.caseNo), "cortOfcCd" to JsonPrimitive(p.courtCode), "sideDvsCd" to JsonPrimitive("2"))))
            })
        }
        val data = post(DETAIL_URL, payload, DETAIL_SUBMISSION, "NONUSER")
        return parseDetail(data["dma_result"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    /** 사건 단건 조회 (이해관계인·청구금액·항고). 상세 화면에서 한 번만 부른다. */
    fun fetchCase(p: Property): CourtCase {
        require(p.isCourt) { "법원경매 물건이 아닙니다" }
        val payload = buildJsonObject {
            put("dma_srchCsDtlInf", buildJsonObject {
                put("cortOfcCd", p.courtCode)
                put("csNo", p.caseNo)
            })
        }
        return parseCase(post(CASE_URL, payload, submissionId = null, userId = null, referer = CASE_ENTRY_URL))
    }

    private fun warmUp() {
        val response = try {
            transport.get(
                ENTRY_URL,
                mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.8",
                ),
            )
        } catch (e: IOException) {
            throw OnbidApiException(OnbidApiException.NETWORK, "법원경매정보 사이트에 연결하지 못했습니다. (${e.message ?: e.javaClass.simpleName})")
        }
        cookies.store(response.setCookies)
        warmedUp = true
    }

    private fun post(url: String, payload: JsonObject, submissionId: String?, userId: String?, referer: String = ENTRY_URL): JsonObject {
        val now = clock()
        if (now < blockedUntil) {
            val minutes = ((blockedUntil - now) / 60_000).coerceAtLeast(1)
            throw OnbidApiException("COURT_LIMIT", "법원경매정보 사이트가 접속을 막아 둔 상태입니다. 약 ${minutes}분 뒤에 다시 시도하세요.")
        }
        if (!warmedUp) warmUp()
        if (lastPost > 0) {
            val wait = minDelayMs + random(jitterMs) - (clock() - lastPost)
            if (wait > 0) sleeper(wait)
        }
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.8",
            "Origin" to BASE,
            "Referer" to referer,
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/json; charset=UTF-8",
        ) + listOfNotNull(submissionId?.let { "submissionid" to it }, userId?.let { "sc-userid" to it }) +
            (if (cookies.isEmpty()) emptyMap() else mapOf("Cookie" to cookies.header()))
        val response = try {
            transport.post(url, headers, payload.toString())
        } catch (e: IOException) {
            throw OnbidApiException(OnbidApiException.NETWORK, "법원경매정보 사이트에 연결하지 못했습니다. (${e.message ?: e.javaClass.simpleName})")
        } finally {
            lastPost = clock()
        }
        cookies.store(response.setCookies)
        val root = runCatching { json.parseToJsonElement(response.body) }.getOrNull() as? JsonObject
        val data = root?.get("data") as? JsonObject
        if (data?.str("ipcheck") == "false") {
            blockedUntil = clock() + 60 * 60_000
            throw OnbidApiException("COURT_LIMIT", "법원경매정보 사이트가 너무 잦은 조회로 이 휴대폰의 접속을 약 1시간 막았습니다. 1시간 뒤에 다시 시도하세요.")
        }
        if (response.status >= 400 || root == null) {
            val reason = (root?.get("errors") as? JsonObject)?.str("errorMessage").orEmpty().ifEmpty { root?.str("message").orEmpty() }
            val step = when (url) {
                SEARCH_URL -> "검색"
                CASE_URL -> "사건"
                else -> "상세"
            }
            throw OnbidApiException(
                "COURT${response.status}",
                "법원경매정보 $step 조회에 실패했습니다 (HTTP ${response.status}${if (reason.isNotEmpty()) ", $reason" else ""}). 잠시 후 다시 시도하세요.",
            )
        }
        val errorMessage = (root["errors"] as? JsonObject)?.str("errorMessage").orEmpty()
        if (errorMessage.isNotEmpty()) throw OnbidApiException("COURT_ERROR", "법원경매정보: $errorMessage")
        return data ?: throw OnbidApiException("COURT_EMPTY", root.str("message").ifEmpty { "법원경매정보 응답이 비어 있습니다." })
    }

    private fun searchPayload(sidoCode: String, sigunguCode: String, from: LocalDate, to: LocalDate, page: Int): JsonObject {
        // 지역을 주면 지번주소 검색(cortStDvs=2)으로 조회한다.
        val condition = defaultCondition() + mapOf(
            "rprsAdongSdCd" to JsonPrimitive(sidoCode),
            "rprsAdongSggCd" to JsonPrimitive(sigunguCode),
            "cortStDvs" to JsonPrimitive("2"),
            "bidBgngYmd" to JsonPrimitive(from.format(YMD)),
            "bidEndYmd" to JsonPrimitive(to.format(YMD)),
        )
        return buildJsonObject {
            put("dma_pageInfo", buildJsonObject {
                put("pageNo", page)
                put("pageSize", pageSize)
                put("bfPageNo", "")
                put("startRowNo", "")
                put("totalCnt", "")
                put("totalYn", "Y")
                put("groupTotalCount", "")
            })
            put("dma_srchGdsDtlSrchInfo", JsonObject(condition))
        }
    }

    companion object {
        const val BASE = "https://www.courtauction.go.kr"
        const val ENTRY_URL = "$BASE/pgj/index.on?w2xPath=/pgj/ui/pgj100/PGJ151F00.xml&pgjId=151F00"
        const val SEARCH_URL = "$BASE/pgj/pgjsearch/searchControllerMain.on"
        const val DETAIL_URL = "$BASE/pgj/pgj15B/selectAuctnCsSrchRslt.on"
        const val CASE_URL = "$BASE/pgj/pgj15A/selectAuctnCsSrchRslt.on"
        const val CASE_ENTRY_URL = "$BASE/pgj/index.on?w2xPath=/pgj/ui/pgj100/PGJ159M00.xml&pgjId=159M00"
        private const val SEARCH_SUBMISSION = "mf_wfm_mainFrame_sbm_selectGdsDtlSrch"
        private const val DETAIL_SUBMISSION = "mf_wfm_mainFrame_sbm_selectGdsDtlSrchDtlInfo"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /** 법원경매정보의 시도 코드. 특별자치도는 새 코드 뒤에 옛 코드를 둔다. */
        val sidoCodes: Map<String, List<String>> = mapOf(
            "서울특별시" to listOf("11"), "부산광역시" to listOf("26"), "대구광역시" to listOf("27"),
            "인천광역시" to listOf("28"), "광주광역시" to listOf("29"), "대전광역시" to listOf("30"),
            "울산광역시" to listOf("31"), "세종특별자치시" to listOf("36"), "경기도" to listOf("41"),
            "강원특별자치도" to listOf("51", "42"), "충청북도" to listOf("43"), "충청남도" to listOf("44"),
            "전북특별자치도" to listOf("52", "45"), "전라남도" to listOf("46"), "경상북도" to listOf("47"),
            "경상남도" to listOf("48"), "제주특별자치도" to listOf("50"),
        )

        /** 부동산 물건상세검색 화면이 보내는 조건 필드. 빈 값도 모두 보내야 서버가 받아준다. */
        private val conditionFields = listOf(
            "rletDspslSpcCondCd", "rprsAdongSdCd", "rprsAdongSggCd", "rprsAdongEmdCd", "rdnmSdCd", "rdnmSggCd", "rdnmNo",
            "mvprpDspslPlcAdongSdCd", "mvprpDspslPlcAdongSggCd", "mvprpDspslPlcAdongEmdCd", "rdDspslPlcAdongSdCd",
            "rdDspslPlcAdongSggCd", "rdDspslPlcAdongEmdCd", "cortOfcCd", "jdbnCd", "execrOfcDvsCd", "lclDspslGdsLstUsgCd",
            "mclDspslGdsLstUsgCd", "sclDspslGdsLstUsgCd", "cortAuctnMbrsId", "aeeEvlAmtMin", "aeeEvlAmtMax",
            "lwsDspslPrcRateMin", "lwsDspslPrcRateMax", "flbdNcntMin", "flbdNcntMax", "objctArDtsMin", "objctArDtsMax",
            "mvprpArtclKndCd", "mvprpArtclNm", "mvprpAtchmPlcTypCd", "lafjOrderBy", "csNo", "bidBgngYmd", "bidEndYmd",
            "dspslDxdyYmd", "fstDspslHm", "scndDspslHm", "thrdDspslHm", "fothDspslHm", "dspslPlcNm", "lwsDspslPrcMin",
            "lwsDspslPrcMax", "grbxTypCd", "gdsVendNm", "fuelKndCd", "carMdyrMax", "carMdyrMin", "carMdlNm", "sideDvsCd",
        )

        private fun defaultCondition(): Map<String, JsonPrimitive> = buildMap {
            conditionFields.forEach { put(it, JsonPrimitive("")) }
            put("bidDvsCd", JsonPrimitive("000331"))
            put("mvprpRletDvsCd", JsonPrimitive("00031R"))
            put("cortAuctnSrchCondCd", JsonPrimitive("0004601"))
            put("notifyLoc", JsonPrimitive("off"))
            put("pgmId", JsonPrimitive("PGJ151F01"))
            put("cortStDvs", JsonPrimitive("1"))
            put("statNum", JsonPrimitive(1))
        }

        private val areaPattern = Regex("(\\d+(?:\\.\\d+)?)\\s*㎡")

        private fun areas(text: String): List<Double> = areaPattern.findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()

        fun jibunAddress(row: JsonObject): String =
            AddressParser.join(row.str("hjguSido"), row.str("hjguSigu"), row.str("hjguDong"), row.str("hjguRd"), row.str("daepyoLotno"))

        /** 검색 결과 1행 → 물건. 차량·기타(대분류 30000·40000)는 버린다. */
        fun toProperty(row: JsonObject): Property? {
            val large = row.str("lclsUtilCd")
            if (large.isNotEmpty() && large != "10000" && large != "20000") return null
            val caseNo = row.str("srnSaNo")
            val id = row.str("docid").ifEmpty { listOf(row.str("boCd"), row.str("saNo"), row.str("maemulSer"), row.str("mokmulSer")).joinToString("-") }
            if (caseNo.isEmpty() && id.isBlank()) return null

            val address = jibunAddress(row)
            val road = AddressParser.join(row.str("rd1Nm"), row.str("rd2Nm"), row.str("rdEubMyun"), row.str("rdNm"), row.str("buldNo").trimStart('0'))
                .takeIf { row.str("rdNm").isNotEmpty() }.orEmpty()
            val building = areas(row.str("pjbBuldList")).takeIf { it.isNotEmpty() }?.sum()
            val representative = parseDecimal(row.str("minArea"))?.takeIf { it > 0 }
            val land = if (large == "10000") areas(row.str("areaList")).firstOrNull() ?: representative else null
            val date = row.str("maeGiil").filter { it.isDigit() }.take(8)
            val time = row.str("maeHh1").filter { it.isDigit() }.take(4).ifEmpty { "1000" }.padEnd(4, '0')
            val periodFrom = row.str("ipgiganFday").filter { it.isDigit() }.take(8)
            val periodTo = row.str("ipgiganTday").filter { it.isDigit() }.take(8)
            val (start, end) = when {
                periodFrom.length == 8 && periodTo.length == 8 -> periodFrom + "0900" to periodTo + "1800"
                date.length == 8 -> date + time to date + "2359"
                else -> "" to ""
            }
            val stopped = row.str("stopsaGbncd").let { it.isNotEmpty() && it != "00" }
            val position = Katec.toWgs84OrNull(parseDecimal(row.str("xCordi")), parseDecimal(row.str("yCordi")))

            return Property(
                id = id,
                source = Source.COURT,
                caseNo = caseNo,
                courtCode = row.str("boCd").ifEmpty { row.str("cortOfcCd") },
                itemSeq = row.str("maemulSer").ifEmpty { "1" },
                name = AddressParser.join(address, row.str("buldNm"), row.str("buldList")).ifEmpty { caseNo },
                address = address,
                roadAddress = road,
                sido = row.str("hjguSido"),
                sigungu = row.str("hjguSigu"),
                dong = row.str("hjguDong"),
                assetTypeCode = "COURT",
                assetTypeName = "법원경매",
                disposalMethod = "매각",
                bidMethod = if (periodFrom.isNotEmpty()) "기간입찰" else "기일입찰",
                usageLarge = when (large) { "10000" -> "토지"; "20000" -> "건물"; else -> "" },
                usageSmall = row.str("dspslUsgNm"),
                buildingAreaSqm = building,
                landAreaSqm = land,
                areaSqm = building ?: land ?: representative,
                appraisalPrice = parseAmount(row.str("gamevalAmt"))?.takeIf { it > 0 },
                minBidPrice = parseAmount(row.str("minmaePrice"))?.takeIf { it > 0 },
                failCount = parseAmount(row.str("yuchalCnt"))?.toInt() ?: 0,
                statusName = if (stopped) "정지" else if (row.str("mulJinYn") == "Y") "진행" else "",
                bidStart = start,
                bidEnd = end,
                organization = AddressParser.join(row.str("jiwonNm"), row.str("jpDeptNm")),
                notes = row.str("mulBigo"),
                shareInfo = row.str("maejibun"),
                salePlace = row.str("maePlace"),
                contact = row.str("tel"),
                viewCount = parseAmount(row.str("inqCnt"))?.toInt() ?: 0,
                interestCount = parseAmount(row.str("gwansMulRegCnt"))?.toInt() ?: 0,
                reauctionDate = row.str("remaeordDay").filter { it.isDigit() }.take(8),
                latitude = position?.lat,
                longitude = position?.lng,
                lawdCode = row.str("srchHjguSiguCd").takeIf { it.length == 5 }
                    ?: (row.str("daepyoSidoCd") + row.str("daepyoSiguCd")).takeIf { it.length == 5 }.orEmpty(),
            )
        }

        fun parseDetail(result: JsonObject): CourtDetail {
            val decoder = Base64.getMimeDecoder()
            val photoOrder = listOf("000241", "000244", "000245", "000247")
            val photos = result["csPicLst"].objects()
                .sortedWith(compareBy({ photoOrder.indexOf(it.str("cortAuctnPicDvsCd")).let { i -> if (i < 0) photoOrder.size else i } }, { it.str("cortAuctnPicSeq").toIntOrNull() ?: 0 }))
                .mapNotNull { pic -> pic.str("picFile").takeIf { it.isNotEmpty() }?.let { runCatching { decoder.decode(it) }.getOrNull() } }
                .filter { it.size > 100 }

            val days = result["gdsDspslDxdyLst"].objects()
                .filter { it.str("auctnDxdyKndCd").let { kind -> kind.isEmpty() || kind == "01" } }
                .sortedBy { it.str("dxdyYmd") }
            val rounds = days.mapIndexed { index, day ->
                val ymd = day.str("dxdyYmd").filter { it.isDigit() }.take(8)
                val hm = day.str("dxdyHm").filter { it.isDigit() }.take(4).ifEmpty { "1000" }
                BidRound(
                    round = (index + 1).toString(),
                    bidStart = if (ymd.length == 8) ymd + hm else "",
                    bidEnd = if (ymd.length == 8) ymd + "2359" else "",
                    minBidPrice = parseAmount(day.str("tsLwsDspslPrc"))?.takeIf { it > 0 },
                    statusName = day.firstStr("auctnDxdyRsltNm", "dxdyRsltNm").ifEmpty { if (day.str("auctnDxdyRsltCd").isEmpty()) "예정" else "" },
                )
            }
            // 결과가 아직 없는 가장 이른 매각기일의 최저가가 지금 가격이다.
            val current = days.firstOrNull { it.str("auctnDxdyRsltCd").isEmpty() && (parseAmount(it.str("tsLwsDspslPrc")) ?: 0) > 0 }
                ?.let { parseAmount(it.str("tsLwsDspslPrc")) }
            val objects = result["gdsDspslObjctLst"].objects().map { obj ->
                listOf(obj.str("objctArDts"), obj.str("pjbBuldList"), obj.str("dspslStkCtt")).filter { it.isNotBlank() }.joinToString(" ")
            }.filter { it.isNotBlank() }
            return CourtDetail(photos, rounds, current, objects)
        }

        fun parseCase(data: JsonObject): CourtCase {
            val basis = data["dma_csBasInf"] as? JsonObject
                ?: throw OnbidApiException("COURT_EMPTY", "법원경매정보에서 사건을 찾지 못했습니다 (비공개이거나 종결된 사건일 수 있습니다).")
            val stakeholders = data["dlt_rletCsIntrpsLst"].objects().mapNotNull { row ->
                val kind = row.firstStr("auctnIntrpsDvsNm1", "auctnIntrpsDvsNm2")
                if (kind.isEmpty()) null else kind to row.firstStr("intrpsNm1", "intrpsNm2")
            }
            return CourtCase(
                caseName = basis.str("csNm"),
                claimAmount = parseAmount(basis.str("clmAmt"))?.takeIf { it > 0 },
                receiptDate = basis.str("csRcptYmd").filter { it.isDigit() }.take(8),
                stakeholders = stakeholders,
                appeals = data["dlt_csApalRaplDts"].objects().size,
                relatedCases = data["dlt_rletReltCsLst"].objects().size,
                claimDeadline = data["dlt_dstrtDemnLstprdDts"].objects().firstOrNull()?.str("dstrtDemnLstprdYmd")?.filter { it.isDigit() }?.take(8).orEmpty(),
            )
        }

        /** 법원경매정보는 상세 주소가 없어 검색 화면을 열고 사건번호를 넣게 한다. */
        const val SEARCH_PAGE = ENTRY_URL
    }
}
