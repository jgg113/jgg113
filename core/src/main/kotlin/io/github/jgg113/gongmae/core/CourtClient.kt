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

data class CourtSearchRequest(
    val sido: String,
    val sigungu: String = "",
    /** 오늘부터 몇 주 안의 매각기일까지 볼지 */
    val weeks: Int = 4,
    val maxPages: Int = 30,
)

/** 법원경매 물건상세: 사진(JPEG 바이트)과 기일 내역. */
data class CourtDetail(
    val photos: List<ByteArray>,
    val rounds: List<BidRound>,
    val currentMinPrice: Long?,
    val objects: List<String>,
)

/**
 * 대법원 법원경매정보(courtauction.go.kr) 화면이 쓰는 JSON 을 그대로 호출한다. 공식 오픈API 가 아니다.
 *
 * - 진입 페이지 GET 으로 JSESSIONID 를 받고, 이후 POST 에 Referer·submissionid·sc-userid 헤더를 붙인다.
 * - 한 페이지 최대 50건. 검색 조건이 모두 비면 400 이 나므로 시도와 매각기일 구간을 항상 채운다.
 * - 사이트가 접속 IP 를 지켜보므로(ipcheck) 요청 사이에 간격을 둔다.
 */
class CourtClient(
    private val transport: WebTransport = UrlConnectionTransport,
    private val delayMs: Long = 700,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val cookies = CookieJar()
    private var lastPost = 0L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun search(request: CourtSearchRequest, now: LocalDateTime = LocalDateTime.now(), progress: ProgressListener? = null): SearchOutcome {
        val codes = sidoCodes[request.sido] ?: throw OnbidApiException("COURT", "법원경매 검색을 지원하지 않는 지역입니다: ${request.sido}")
        val from = now.toLocalDate()
        val to = from.plusWeeks(request.weeks.toLong())
        var rows = emptyList<JsonObject>()
        // 강원·전북은 특별자치도 전환 전 코드로만 잡히는 물건이 있을 수 있어 옛 코드도 시도한다.
        for (code in codes) {
            rows = fetchAll(code, from, to, request.maxPages, progress)
            if (rows.isNotEmpty()) break
        }
        val filtered = rows.filter { Regions.matchesSigungu(it.str("hjguSigu"), jibunAddress(it), request.sigungu) }
        val properties = filtered.mapNotNull { toProperty(it) }.distinctBy { it.id }
        return SearchOutcome(properties, filtered.size, emptyList(), regionFilteredLocally = request.sigungu.isNotBlank())
    }

    private fun fetchAll(sidoCode: String, from: LocalDate, to: LocalDate, maxPages: Int, progress: ProgressListener?): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var page = 1
        var total: Int? = null
        while (page <= maxPages) {
            progress?.onProgress(page - 1, total?.let { (it + PAGE_SIZE - 1) / PAGE_SIZE }?.coerceAtMost(maxPages) ?: (page + 1), "법원경매 ${page}쪽")
            val data = post(SEARCH_URL, searchPayload(sidoCode, from, to, page), SEARCH_SUBMISSION, "SYSTEM")
            val rows = data["dlt_srchResult"].objects()
            total = total ?: (data["dma_pageInfo"] as? JsonObject)?.str("totalCnt")?.toIntOrNull()
            all += rows
            if (rows.size < PAGE_SIZE || (total != null && page * PAGE_SIZE >= total)) break
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

    private fun warmUp() {
        val response = try {
            transport.get(ENTRY_URL, baseHeaders())
        } catch (e: IOException) {
            throw OnbidApiException(OnbidApiException.NETWORK, "법원경매정보 사이트에 연결하지 못했습니다. (${e.message ?: e.javaClass.simpleName})")
        }
        cookies.store(response.setCookies)
        if (response.status >= 400) throw OnbidApiException("COURT${response.status}", "법원경매정보 사이트가 응답하지 않습니다 (HTTP ${response.status}).")
    }

    private fun post(url: String, payload: JsonObject, submissionId: String, userId: String): JsonObject {
        if (!cookies.has("JSESSIONID")) warmUp()
        val wait = delayMs - (clock() - lastPost)
        if (lastPost > 0 && wait > 0) sleeper(wait)
        val headers = baseHeaders() + mapOf(
            "Content-Type" to "application/json;charset=UTF-8",
            "Accept" to "application/json",
            "submissionid" to submissionId,
            "sc-userid" to userId,
            "Cookie" to cookies.header(),
        )
        val response = try {
            transport.post(url, headers, payload.toString())
        } catch (e: IOException) {
            throw OnbidApiException(OnbidApiException.NETWORK, "법원경매정보 사이트에 연결하지 못했습니다. (${e.message ?: e.javaClass.simpleName})")
        } finally {
            lastPost = clock()
        }
        cookies.store(response.setCookies)
        if (response.status >= 400) {
            throw OnbidApiException("COURT${response.status}", "법원경매정보 조회에 실패했습니다 (HTTP ${response.status}). 잠시 후 다시 시도하세요.")
        }
        val root = runCatching { json.parseToJsonElement(response.body) }.getOrNull() as? JsonObject
            ?: throw OnbidApiException("COURT_PARSE", "법원경매정보 응답을 해석하지 못했습니다.")
        val data = root["data"] as? JsonObject
            ?: throw OnbidApiException("COURT_EMPTY", root.str("message").ifEmpty { "법원경매정보 응답이 비어 있습니다." })
        if (data.str("ipcheck") == "false") {
            throw OnbidApiException("COURT_LIMIT", "법원경매정보 사이트가 잠시 접속을 제한했습니다. 몇 분 뒤 다시 시도하세요.")
        }
        return data
    }

    private fun baseHeaders() = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "ko-KR,ko;q=0.9",
        "Origin" to BASE,
        "Referer" to ENTRY_URL,
    )

    private fun searchPayload(sidoCode: String, from: LocalDate, to: LocalDate, page: Int): JsonObject {
        val condition = defaultCondition() + mapOf(
            "rdDspslPlcAdongSdCd" to JsonPrimitive(sidoCode),
            "bidBgngYmd" to JsonPrimitive(from.format(YMD)),
            "bidEndYmd" to JsonPrimitive(to.format(YMD)),
        )
        return buildJsonObject {
            put("dma_pageInfo", buildJsonObject {
                put("pageNo", page)
                put("pageSize", PAGE_SIZE)
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
        const val ENTRY_URL = "$BASE/pgj/index.on?w2xPath=/pgj/ui/pgj100/PGJ151F00.xml"
        const val SEARCH_URL = "$BASE/pgj/pgjsearch/searchControllerMain.on"
        const val DETAIL_URL = "$BASE/pgj/pgj15B/selectAuctnCsSrchRslt.on"
        private const val SEARCH_SUBMISSION = "mf_wfm_mainFrame_sbm_selectGdsDtlSrch"
        private const val DETAIL_SUBMISSION = "mf_wfm_mainFrame_sbm_selectGdsDtlSrchDtlInfo"
        const val PAGE_SIZE = 50
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
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

        /** 법원경매정보는 상세 주소가 없어 검색 화면을 열고 사건번호를 넣게 한다. */
        const val SEARCH_PAGE = ENTRY_URL
    }
}
