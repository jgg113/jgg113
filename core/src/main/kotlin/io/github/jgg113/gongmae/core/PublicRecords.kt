package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.Element
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 필지 하나. [pnu] 는 표준 PNU 19자리(법정동코드 10 + 산 여부 1(일반)/2(산) + 본번 4 + 부번 4).
 * 온비드 PNU 는 산 여부 자리에 0/1 을 쓰므로 [fromOnbid] 로 바꿔 만든다.
 */
data class Parcel(val pnu: String) {
    val sigunguCode: String get() = pnu.substring(0, 5)
    val dongCode: String get() = pnu.substring(5, 10)
    val mountain: Boolean get() = pnu[10] == '2'
    val main: Int get() = pnu.substring(11, 15).toInt()
    val sub: Int get() = pnu.substring(15, 19).toInt()

    /** "936-31", "산12" */
    val lot: String get() = (if (mountain) "산" else "") + main + if (sub > 0) "-$sub" else ""

    companion object {
        /** 이 앱의 [Property.lotPnu] (온비드 방식: 0 일반 / 1 산) → 표준 PNU */
        fun of(p: Property): Parcel? = fromOnbid(p.lotPnu)

        fun fromOnbid(pnu: String): Parcel? {
            if (pnu.length != 19 || !pnu.all { it.isDigit() }) return null
            val flag = when (pnu[10]) {
                '0' -> '1'
                '1' -> '2'
                '2' -> '2'
                else -> return null
            }
            if (pnu.substring(11, 15).toInt() == 0) return null
            return Parcel(pnu.substring(0, 10) + flag + pnu.substring(11))
        }

        /** 법정동코드 10자리 + "936-31" / "산 12-3" 같은 지번 → 온비드 방식 PNU. 해석이 안 되면 "". */
        fun onbidStylePnu(dongCode: String, lot: String): String {
            if (dongCode.length != 10 || !dongCode.all { it.isDigit() }) return ""
            val match = Regex("^\\s*(산)?\\s*(\\d{1,4})(?:\\s*-\\s*(\\d{1,4}))?").find(lot) ?: return ""
            val main = match.groupValues[2].toInt()
            if (main == 0) return ""
            val sub = match.groupValues[3].toIntOrNull() ?: 0
            val flag = if (match.groupValues[1].isNotEmpty()) "1" else "0"
            return dongCode + flag + main.toString().padStart(4, '0') + sub.toString().padStart(4, '0')
        }
    }
}

/** 개별공시지가 한 해 (원/㎡) */
data class LandPriceYear(val year: Int, val pricePerSqm: Long, val noticeDate: String)

/** 개별주택가격(단독·다가구) 한 해 */
data class HousePriceYear(val year: Int, val price: Long, val landAreaSqm: Double?, val buildingAreaSqm: Double?)

/** 건축물대장 표제부 요약 (여러 동이면 주건축물 기준으로 합친다) */
data class BuildingInfo(
    val name: String,
    val mainPurpose: String,
    val otherPurposes: String,
    val structure: String,
    /** 사용승인일 yyyyMMdd */
    val approvalDate: String,
    val groundFloors: Int,
    val undergroundFloors: Int,
    val totalAreaSqm: Double?,
    val landAreaSqm: Double?,
    val buildingCoverage: Double?,
    val floorAreaRatio: Double?,
    val households: Int,
    val units: Int,
    val elevators: Int,
    val parking: Int,
    /** 일반 / 집합 */
    val registerKind: String,
    val buildingCount: Int,
) {
    /** 사용승인 후 지난 햇수 */
    fun age(today: LocalDate = LocalDate.now()): Int? {
        val year = approvalDate.take(4).toIntOrNull() ?: return null
        if (year < 1900) return null
        return (today.year - year).coerceAtLeast(0)
    }

    /** 1·2종 근린생활시설인데 주택으로 쓰는 '근생빌라' 의심 */
    val residentialOnCommercial: Boolean
        get() = mainPurpose.contains("근린생활") && !otherPurposes.contains("주택") && !otherPurposes.contains("공동")
}

/** 무료로 자동 조회한 공적 자료 묶음. 하나라도 있으면 분석에 쓴다. */
data class PublicFacts(
    val parcel: Parcel? = null,
    val landPrices: List<LandPriceYear> = emptyList(),
    val housePrices: List<HousePriceYear> = emptyList(),
    val building: BuildingInfo? = null,
    /** 자료별로 못 가져온 이유 (화면 안내용) */
    val notes: List<String> = emptyList(),
) {
    val latestLandPrice: LandPriceYear? get() = landPrices.maxByOrNull { it.year }
    val latestHousePrice: HousePriceYear? get() = housePrices.maxByOrNull { it.year }

    /** 공시지가 전년 대비 변동률(%) */
    val landPriceChange: Double?
        get() {
            val sorted = landPrices.sortedByDescending { it.year }
            if (sorted.size < 2 || sorted[1].pricePerSqm <= 0) return null
            return (sorted[0].pricePerSqm - sorted[1].pricePerSqm) * 100.0 / sorted[1].pricePerSqm
        }

    /** 토지 면적 × 공시지가 */
    fun landValue(p: Property): Long? {
        val price = latestLandPrice?.pricePerSqm ?: return null
        val area = p.landAreaSqm ?: if (p.category == Category.LAND || p.category == Category.FOREST) p.areaSqm else null
        if (area == null || area <= 0) return null
        return Math.round(price * area)
    }

    val isEmpty: Boolean get() = landPrices.isEmpty() && housePrices.isEmpty() && building == null
}

/**
 * 돈 안 드는 공적 자료 자동 조회.
 * - 개별공시지가·개별주택가격: 부동산공시가격알리미(realtyprice.kr) 공개 조회 — 키 필요 없음
 * - 건축물대장 표제부: 공공데이터포털 '국토교통부_건축HUB_건축물대장정보 서비스' — 같은 인증키, 활용신청 필요
 * 등기부등본(유료)·전입세대열람(본인·이해관계인 방문)·토지대장(정부24 로그인)은 자동으로 받을 수 없다.
 */
class PublicRecordsClient(
    private val serviceKey: String = "",
    private val transport: WebTransport = UrlConnectionTransport(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun landPrices(parcel: Parcel): List<LandPriceYear> {
        val url = "$REALTY/search/gsiSearchListApi.search?gbn=1&reg=${parcel.sigunguCode}&eub=${parcel.dongCode}" +
            "&san=${if (parcel.mountain) 2 else 1}&bun1=${parcel.main.toString().padStart(4, '0')}&bun2=${parcel.sub.toString().padStart(4, '0')}" +
            "&tabGbn=Text&page_no=1&year="
        return parseLandPrices(realtyGet(url, LAND_REFERER))
    }

    fun housePrices(parcel: Parcel): List<HousePriceYear> {
        val url = "$REALTY/search/hpiSearchListApi.search?page_no=1&gbn=1&year=&reg=${parcel.sigunguCode}&eub=${parcel.dongCode}" +
            "&san=${if (parcel.mountain) 2 else 1}&bun1=${parcel.main.toString().padStart(4, '0')}&bun2=${parcel.sub.toString().padStart(4, '0')}" +
            "&road_code=&p_initialword=&build_bun1=&build_bun2=&from_year=&to_year=&dong_gbn=&tabGbn=Text"
        return parseHousePrices(realtyGet(url, HOUSE_REFERER))
    }

    fun building(parcel: Parcel): BuildingInfo? {
        if (serviceKey.isBlank()) throw OnbidApiException("KEY", "공공데이터포털 인증키가 없습니다.")
        val url = "https://apis.data.go.kr/1613000/BldRgstHubService/getBrTitleInfo?serviceKey=" +
            URLEncoder.encode(ServiceKey.normalize(serviceKey), "UTF-8") +
            "&sigunguCd=${parcel.sigunguCode}&bjdongCd=${parcel.dongCode}&platGbCd=${if (parcel.mountain) 1 else 0}" +
            "&bun=${parcel.main.toString().padStart(4, '0')}&ji=${parcel.sub.toString().padStart(4, '0')}&numOfRows=50&pageNo=1"
        val response = try {
            transport.get(url, mapOf("Accept" to "application/xml"))
        } catch (e: IOException) {
            throw OnbidApiException(OnbidApiException.NETWORK, "인터넷 연결을 확인하세요. (${e.message ?: e.javaClass.simpleName})")
        }
        return parseBuilding(DataGoXml.items(response.body, response.status))
    }

    /**
     * 이 물건에 맞는 자료를 모두 받아 본다. 하나가 실패해도 나머지는 계속한다.
     * [withBuilding] 이 거짓이면 건축물대장(인증키 필요)은 건너뛴다.
     */
    fun collect(p: Property, withBuilding: Boolean): PublicFacts {
        val parcel = Parcel.of(p) ?: return PublicFacts(notes = listOf("지번(PNU)을 알 수 없어 공시가격·건축물대장을 조회하지 못했습니다."))
        val notes = mutableListOf<String>()
        val land = runCatching { landPrices(parcel) }.getOrElse { notes += "공시지가: ${it.message ?: "조회 실패"}"; emptyList() }
        val house = if (p.category == Category.HOUSE) {
            runCatching { housePrices(parcel) }.getOrElse { notes += "개별주택가격: ${it.message ?: "조회 실패"}"; emptyList() }
        } else {
            emptyList()
        }
        val building = if (withBuilding && p.category != Category.LAND && p.category != Category.FOREST) {
            runCatching { building(parcel) }.getOrElse { e ->
                val approval = e is OnbidApiException && e.code in setOf("12", "20", "22", "30", "31", "32", "HTTP401", "HTTP403")
                notes += if (approval) "건축물대장: 공공데이터포털에서 '국토교통부_건축HUB_건축물대장정보 서비스' 활용신청이 필요합니다." else "건축물대장: ${e.message ?: "조회 실패"}"
                null
            }
        } else {
            null
        }
        if (land.isEmpty() && notes.none { it.startsWith("공시지가") }) notes += "공시지가: 등록된 값이 없습니다 (도로·하천이거나 지번이 다를 수 있음)."
        return PublicFacts(parcel, land, house, building, notes)
    }

    private fun realtyGet(url: String, referer: String): String {
        val response = try {
            transport.get(url, mapOf("Referer" to referer, "X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json"))
        } catch (e: IOException) {
            throw IOException("부동산공시가격알리미 연결 실패 (${e.message ?: e.javaClass.simpleName})")
        }
        if (response.status != 200) throw IOException("부동산공시가격알리미 응답 오류 (HTTP ${response.status})")
        return response.body
    }

    private fun modelList(body: String): List<JsonObject> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: throw IOException("공시가격 응답을 해석하지 못했습니다.")
        val model = root["model"] as? JsonObject ?: return emptyList()
        return (model["list"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
    }

    fun parseLandPrices(body: String): List<LandPriceYear> = modelList(body).mapNotNull { row ->
        val year = row.str("base_year").take(4).toIntOrNull() ?: return@mapNotNull null
        val price = parseAmount(row.firstStr("gakuka_w", "gakuka"))?.takeIf { it > 0 } ?: return@mapNotNull null
        LandPriceYear(year, price, row.str("notice_ymd"))
    }.distinctBy { it.year }.sortedByDescending { it.year }

    fun parseHousePrices(body: String): List<HousePriceYear> = modelList(body).mapNotNull { row ->
        val year = row.firstStr("base_ymd", "base_date", "notice_ymd").filter { it.isDigit() }.take(4).toIntOrNull() ?: return@mapNotNull null
        val price = parseAmount(row.str("hprice_w"))?.takeIf { it > 0 } ?: return@mapNotNull null
        HousePriceYear(year, price, row.decimal("tbook_area"), row.decimal("bldg_garea"))
    }.distinctBy { it.year }.sortedByDescending { it.year }

    companion object {
        private const val REALTY = "https://www.realtyprice.kr/notice"
        private const val LAND_REFERER = "$REALTY/gsindividual/search.htm"
        private const val HOUSE_REFERER = "$REALTY/hpindividual/search.htm"

        /** 표제부 여러 행(동별)을 하나로 합친다. 주건축물을 먼저 본다. */
        fun parseBuilding(rows: List<Map<String, String>>): BuildingInfo? {
            if (rows.isEmpty()) return null
            val main = rows.filter { it["mainAtchGbCdNm"].orEmpty().contains("주") }.ifEmpty { rows }
            fun int(row: Map<String, String>, key: String) = parseDecimal(row[key])?.toInt() ?: 0
            fun dec(row: Map<String, String>, key: String) = parseDecimal(row[key])?.takeIf { it > 0 }
            val first = main.maxByOrNull { dec(it, "totArea") ?: 0.0 } ?: main.first()
            val approvals = main.mapNotNull { it["useAprDay"]?.filter(Char::isDigit)?.takeIf { d -> d.length == 8 } }
            return BuildingInfo(
                name = first["bldNm"].orEmpty(),
                mainPurpose = first["mainPurpsCdNm"].orEmpty(),
                otherPurposes = first["etcPurps"].orEmpty(),
                structure = first["strctCdNm"].orEmpty(),
                approvalDate = approvals.minOrNull().orEmpty(),
                groundFloors = main.maxOf { int(it, "grndFlrCnt") },
                undergroundFloors = main.maxOf { int(it, "ugrndFlrCnt") },
                totalAreaSqm = main.mapNotNull { dec(it, "totArea") }.takeIf { it.isNotEmpty() }?.sum(),
                landAreaSqm = dec(first, "platArea"),
                buildingCoverage = dec(first, "bcRat"),
                floorAreaRatio = dec(first, "vlRat"),
                households = main.sumOf { int(it, "hhldCnt") },
                units = main.sumOf { int(it, "hoCnt") },
                elevators = main.sumOf { int(it, "rideUseElvtCnt") + int(it, "emgenUseElvtCnt") },
                parking = main.sumOf { int(it, "indrMechUtcnt") + int(it, "oudrMechUtcnt") + int(it, "indrAutoUtcnt") + int(it, "oudrAutoUtcnt") },
                registerKind = first["regstrKindCdNm"].orEmpty(),
                buildingCount = main.size,
            )
        }
    }
}

/** 공공데이터포털 XML 응답 → item 목록 (태그 이름 → 값). 오류 코드는 [OnbidApiException] 으로 던진다. */
object DataGoXml {
    fun items(body: String, status: Int = 200): List<Map<String, String>> {
        val text = body.trim().removePrefix("\uFEFF")
        if (text.startsWith("{")) {
            // 서비스에 따라 JSON 으로 올 때가 있다 (Accept 헤더·_type).
            val page = OnbidResponseParser.parse(text)
            return page.items.map { row -> row.keys.associateWith { row.str(it) } }
        }
        if (!text.startsWith("<")) {
            val coded = runCatching { OnbidResponseParser.parse(text) }.exceptionOrNull() as? OnbidApiException
            if (coded != null && coded.code != "EMPTY" && coded.code != "PARSE") throw coded
            throw OnbidApiException("HTTP$status", if (status != 200) OnbidResponseParser.describeHttp(status) else "응답을 해석하지 못했습니다.")
        }
        val doc = runCatching {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream(Charsets.UTF_8))
        }.getOrElse { throw OnbidApiException("PARSE", "응답을 해석하지 못했습니다.") }
        fun tag(parent: Element, name: String): String = parent.getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()
        val root = doc.documentElement
        val code = tag(root, "resultCode").ifEmpty { tag(root, "returnReasonCode") }
        val message = tag(root, "resultMsg").ifEmpty { tag(root, "returnAuthMsg") }
        if (code.isNotEmpty() && code != "00" && code != "000" && code != "0") {
            if (code == "03") return emptyList()
            throw OnbidApiException(code, OnbidResponseParser.describe(code, message))
        }
        if (status != 200) throw OnbidApiException("HTTP$status", OnbidResponseParser.describeHttp(status))
        val items = root.getElementsByTagName("item")
        return (0 until items.length).mapNotNull { i ->
            val item = items.item(i) as? Element ?: return@mapNotNull null
            val children = item.childNodes
            (0 until children.length).mapNotNull { j -> (children.item(j) as? Element)?.let { it.tagName to it.textContent.trim() } }.toMap()
        }
    }
}
