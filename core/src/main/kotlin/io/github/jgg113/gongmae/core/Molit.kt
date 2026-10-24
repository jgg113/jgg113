package io.github.jgg113.gongmae.core

import org.w3c.dom.Element
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** 국토교통부 실거래가 공개 API 종류 (공공데이터포털에서 따로 활용신청한다). */
enum class MarketKind(val label: String, val path: String, val nameTag: String, val areaTag: String, val apiName: String, val datasetId: String) {
    APT("아파트", "RTMSDataSvcAptTrade/getRTMSDataSvcAptTrade", "aptNm", "excluUseAr", "국토교통부_아파트 매매 실거래가 자료", "15126469"),
    RH("연립·다세대", "RTMSDataSvcRHTrade/getRTMSDataSvcRHTrade", "mhouseNm", "excluUseAr", "국토교통부_연립다세대 매매 실거래가 자료", "15126467"),
    OFFI("오피스텔", "RTMSDataSvcOffiTrade/getRTMSDataSvcOffiTrade", "offiNm", "excluUseAr", "국토교통부_오피스텔 매매 신고 자료", "15126464"),
    SH("단독·다가구", "RTMSDataSvcSHTrade/getRTMSDataSvcSHTrade", "umdNm", "totalFloorAr", "국토교통부_단독/다가구 매매 실거래가 자료", "15126465"),
    LAND("토지", "RTMSDataSvcLandTrade/getRTMSDataSvcLandTrade", "umdNm", "dealArea", "국토교통부_토지 매매 신고 자료", "15126466"),
    COMMERCIAL("상업·업무용", "RTMSDataSvcNrgTrade/getRTMSDataSvcNrgTrade", "umdNm", "buildingAr", "국토교통부_상업업무용 부동산 매매 신고 자료", "15126463"),

    /** 추천 API (아직 활용신청 전일 수 있다) — 안 되면 [fallback] 으로 비교한다. */
    INDUSTRIAL("공장·창고", "RTMSDataSvcInduTrade/getRTMSDataSvcInduTrade", "umdNm", "buildingAr", "국토교통부_공장 및 창고 등 부동산 매매 실거래가 자료", "15126470");

    val pageUrl: String get() = "https://www.data.go.kr/data/$datasetId/openapi.do"

    /** 활용신청을 아직 안 했을 수 있는 추천 API */
    val recommended: Boolean get() = this == INDUSTRIAL

    /** 이 API 가 안 될 때 대신 쓸 자료 */
    val fallback: MarketKind? get() = if (this == INDUSTRIAL) COMMERCIAL else null

    companion object {
        fun of(category: Category): MarketKind? = when (category) {
            Category.APARTMENT -> APT
            Category.VILLA -> RH
            Category.OFFICETEL -> OFFI
            Category.HOUSE -> SH
            Category.LAND, Category.FOREST -> LAND
            Category.COMMERCIAL -> COMMERCIAL
            Category.FACTORY -> INDUSTRIAL
            Category.VEHICLE, Category.MOVABLE, Category.ETC -> null
        }
    }
}

/** 국토교통부 전월세 실거래 API (전세가율·월세 수익률 계산용) */
enum class RentKind(val label: String, val path: String, val nameTag: String, val areaTag: String, val apiName: String, val datasetId: String) {
    APT("아파트", "RTMSDataSvcAptRent/getRTMSDataSvcAptRent", "aptNm", "excluUseAr", "국토교통부_아파트 전월세 자료", "15126474"),
    RH("연립·다세대", "RTMSDataSvcRHRent/getRTMSDataSvcRHRent", "mhouseNm", "excluUseAr", "국토교통부_연립다세대 전월세 실거래가 자료", "15126473"),
    OFFI("오피스텔", "RTMSDataSvcOffiRent/getRTMSDataSvcOffiRent", "offiNm", "excluUseAr", "국토교통부_오피스텔 전월세 자료", "15126475"),
    SH("단독·다가구", "RTMSDataSvcSHRent/getRTMSDataSvcSHRent", "umdNm", "totalFloorAr", "국토교통부_단독/다가구 전월세 자료", "15126472");

    val pageUrl: String get() = "https://www.data.go.kr/data/$datasetId/openapi.do"

    companion object {
        fun of(category: Category): RentKind? = when (category) {
            Category.APARTMENT -> APT
            Category.VILLA -> RH
            Category.OFFICETEL -> OFFI
            Category.HOUSE -> SH
            else -> null
        }
    }
}

/** 전월세 한 건 (금액은 원) */
data class Rent(val name: String, val dong: String, val jibun: String, val areaSqm: Double, val deposit: Long, val monthly: Long, val date: String)

/** 주변 전월세 요약 */
data class RentSummary(
    val kind: RentKind,
    val months: Int,
    val scope: String,
    /** 비교에 쓴 전세 건수·월세 건수 */
    val jeonseCount: Int,
    val monthlyCount: Int,
    /** 이 면적으로 환산한 전세 보증금 추정 */
    val jeonseEstimate: Long?,
    /** 월세의 보증금·월세 중간값 (이 면적 환산) */
    val monthlyDeposit: Long?,
    val monthlyRent: Long?,
) {
    /** 최저가 대비 전세가율(%) */
    fun jeonseRatio(price: Long?): Double? {
        val jeonse = jeonseEstimate ?: return null
        if (price == null || price <= 0) return null
        return jeonse * 100.0 / price
    }

    /** 이 값에 사서 월세를 놓을 때 연 수익률(%) — 대출·세금 제외 */
    fun monthlyYield(price: Long?): Double? {
        val rent = monthlyRent ?: return null
        if (price == null || price <= 0) return null
        val invested = price - (monthlyDeposit ?: 0)
        return if (invested > 0) rent * 12 * 100.0 / invested else null
    }
}

data class Trade(
    val name: String,
    val dong: String,
    val jibun: String,
    val areaSqm: Double,
    val price: Long,
    val date: String,
    val floor: String,
    /** 아파트 동 (상세 자료에만 있을 때가 많다) */
    val building: String = "",
)

data class MarketSummary(
    val kind: MarketKind,
    val months: Int,
    /** 비교에 쓴 거래 (같은 동 우선, 면적 ±20%) */
    val trades: List<Trade>,
    val sameDong: Boolean,
    val medianPerSqm: Long?,
    /** 이 물건 면적으로 환산한 시세 추정 */
    val estimatedValue: Long?,
    /** 비교 범위 ("같은 단지", "같은 동", "같은 시군구") */
    val scope: String = if (sameDong) "같은 동" else "같은 시군구",
    /** 최근 3개월 ㎡당 중간값이 그 전 3개월보다 몇 % 오르내렸는지 (거래가 모자라면 null) */
    val trendPercent: Double? = null,
) {
    /** 최저입찰가가 시세 추정의 몇 % 인지 */
    fun ratioOf(price: Long?): Double? {
        val value = estimatedValue ?: return null
        if (price == null || value <= 0) return null
        return price * 100.0 / value
    }
}

/**
 * 국토교통부 실거래가 API (data.go.kr, 공매용 인증키와 같은 키). 응답은 XML 이다.
 * 시군구(LAWD_CD)·계약월(DEAL_YMD) 단위로만 조회되므로 최근 몇 달을 받아 같은 동·비슷한 면적만 고른다.
 */
class MolitClient(serviceKey: String, private val http: HttpGetter = UrlConnectionGetter) {
    private val key = ServiceKey.normalize(serviceKey)

    /** 아파트 매매 상세 자료 활용신청이 안 돼 기본 자료로 돌아갔는지 */
    @Volatile
    var aptDetailUnavailable = false
        private set

    /** 아파트는 상세 자료(동·본번/부번)를 먼저 쓰고, 그 API 승인이 안 됐으면 기본 자료를 쓴다. */
    fun trades(kind: MarketKind, lawdCode: String, yearMonth: String): List<Trade> {
        if (kind == MarketKind.APT && !aptDetailUnavailable) {
            try {
                return tradesAt(APT_DETAIL_PATH, kind, lawdCode, yearMonth)
            } catch (e: OnbidApiException) {
                if (e.code !in MovablesClient.APPROVAL_CODES) throw e
                aptDetailUnavailable = true
            }
        }
        return tradesAt(kind.path, kind, lawdCode, yearMonth)
    }

    fun tradesAt(path: String, kind: MarketKind, lawdCode: String, yearMonth: String): List<Trade> {
        val url = "https://apis.data.go.kr/1613000/$path?serviceKey=" + URLEncoder.encode(key, "UTF-8") +
            "&LAWD_CD=$lawdCode&DEAL_YMD=$yearMonth&pageNo=1&numOfRows=1000"
        val result = try {
            http.get(url)
        } catch (e: IOException) {
            throw OnbidApiException(OnbidApiException.NETWORK, "인터넷 연결을 확인하세요. (${e.message ?: e.javaClass.simpleName})")
        }
        return parse(result.body, kind, result.status)
    }

    fun rents(kind: RentKind, lawdCode: String, yearMonth: String): List<Rent> {
        val url = "https://apis.data.go.kr/1613000/${kind.path}?serviceKey=" + URLEncoder.encode(key, "UTF-8") +
            "&LAWD_CD=$lawdCode&DEAL_YMD=$yearMonth&pageNo=1&numOfRows=1000"
        val result = try {
            http.get(url)
        } catch (e: IOException) {
            throw OnbidApiException(OnbidApiException.NETWORK, "인터넷 연결을 확인하세요. (${e.message ?: e.javaClass.simpleName})")
        }
        return parseRents(result.body, kind, result.status)
    }

    fun summarize(p: Property, now: LocalDate = LocalDate.now(), months: Int = 6): MarketSummary? {
        val kind = MarketKind.of(p.category) ?: return null
        if (p.lawdCode.length != 5) return null
        val all = (0 until months).flatMap { back -> trades(kind, p.lawdCode, now.minusMonths(back.toLong()).format(YM)) }
        return summarize(p, kind, all, months)
    }

    companion object {
        /** 국토교통부_아파트 매매 실거래가 상세 자료 (15126468) */
        const val APT_DETAIL_PATH = "RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev"
        const val APT_DETAIL_PAGE = "https://www.data.go.kr/data/15126468/openapi.do"
        private val YM: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")

        fun parse(body: String, kind: MarketKind, status: Int = 200): List<Trade> {
            val text = body.trim()
            if (!text.startsWith("<")) {
                // 게이트웨이 오류는 JSON/문자열로 오기도 한다.
                val coded = runCatching { OnbidResponseParser.parse(text) }.exceptionOrNull() as? OnbidApiException
                if (coded != null && coded.code != "EMPTY" && coded.code != "PARSE") throw coded
                throw OnbidApiException(
                    "HTTP$status",
                    if (status != 200) OnbidResponseParser.describeHttp(status) else "실거래가 응답을 해석하지 못했습니다.",
                )
            }
            val doc = runCatching {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream(Charsets.UTF_8))
            }.getOrElse { throw OnbidApiException("PARSE", "실거래가 응답을 해석하지 못했습니다.") }
            fun tag(parent: Element, name: String): String =
                parent.getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()
            val root = doc.documentElement
            val code = tag(root, "resultCode").ifEmpty { tag(root, "returnReasonCode") }
            val message = tag(root, "resultMsg").ifEmpty { tag(root, "returnAuthMsg") }
            if (code.isNotEmpty() && code != "00" && code != "000") {
                if (code == "03") return emptyList()
                throw OnbidApiException(code, OnbidResponseParser.describe(code, message))
            }
            val items = root.getElementsByTagName("item")
            return (0 until items.length).mapNotNull { i ->
                val item = items.item(i) as? Element ?: return@mapNotNull null
                if (tag(item, "cdealType").equals("O", ignoreCase = true)) return@mapNotNull null // 해제된 거래
                val price = parseAmount(tag(item, "dealAmount"))?.times(10_000) ?: return@mapNotNull null
                val area = parseDecimal(tag(item, kind.areaTag)) ?: return@mapNotNull null
                val y = tag(item, "dealYear")
                val m = tag(item, "dealMonth").padStart(2, '0')
                val d = tag(item, "dealDay").padStart(2, '0')
                Trade(
                    name = tag(item, kind.nameTag),
                    dong = tag(item, "umdNm"),
                    jibun = tag(item, "jibun"),
                    areaSqm = area,
                    price = price,
                    date = "$y.$m.$d",
                    floor = tag(item, "floor"),
                    building = tag(item, "aptDong"),
                )
            }
        }

        fun summarize(p: Property, kind: MarketKind, all: List<Trade>, months: Int, today: LocalDate = LocalDate.now()): MarketSummary {
            val area = p.areaSqm
            fun similar(t: Trade) = area == null || area <= 0 || t.areaSqm in area * 0.8..area * 1.2
            // 같은 단지(같은 동·같은 지번)가 2건 이상이면 그것만, 아니면 같은 동 3건 이상, 아니면 시군구 전체.
            val lot = sameLot(p)
            val sameComplex = if (lot == null) emptyList() else all.filter { it.dong == p.dong && lotKey(it.jibun) == lot && similar(it) }
            val sameDong = all.filter { it.dong == p.dong && similar(it) }
            val (chosen, scope) = when {
                sameComplex.size >= 2 && kind in setOf(MarketKind.APT, MarketKind.OFFI, MarketKind.RH) -> sameComplex to "같은 단지"
                sameDong.size >= 3 -> sameDong to "같은 동"
                else -> all.filter(::similar) to "같은 시군구"
            }
            val median = medianPerSqm(chosen)
            val estimate = if (median != null && area != null && area > 0) Math.round(median * area) else null
            val cut = today.minusMonths(3).format(DOTTED)
            val recent = medianPerSqm(chosen.filter { it.date >= cut })
            val older = medianPerSqm(chosen.filter { it.date < cut })
            val enough = chosen.count { it.date >= cut } >= 2 && chosen.count { it.date < cut } >= 2
            val trend = if (enough && recent != null && older != null && older > 0) (recent - older) * 100.0 / older else null
            return MarketSummary(kind, months, chosen.sortedByDescending { it.date }, scope != "같은 시군구", median, estimate, scope, trend)
        }

        private val DOTTED: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

        fun parseRents(body: String, kind: RentKind, status: Int = 200): List<Rent> =
            DataGoXml.items(body, status).mapNotNull { item ->
                if (item["cdealType"].equals("O", ignoreCase = true)) return@mapNotNull null
                val deposit = parseAmount(item["deposit"])?.times(10_000) ?: return@mapNotNull null
                val area = parseDecimal(item[kind.areaTag]) ?: return@mapNotNull null
                Rent(
                    name = item[kind.nameTag].orEmpty(),
                    dong = item["umdNm"].orEmpty(),
                    jibun = item["jibun"].orEmpty(),
                    areaSqm = area,
                    deposit = deposit,
                    monthly = (parseAmount(item["monthlyRent"]) ?: 0) * 10_000,
                    date = "${item["dealYear"].orEmpty()}.${item["dealMonth"].orEmpty().padStart(2, '0')}.${item["dealDay"].orEmpty().padStart(2, '0')}",
                )
            }

        /** 같은 단지 → 같은 동 → 시군구 순으로 비슷한 면적 전세·월세를 골라 이 면적으로 환산한다. */
        fun summarizeRents(p: Property, kind: RentKind, all: List<Rent>, months: Int): RentSummary {
            val area = p.areaSqm
            fun similar(r: Rent) = area == null || area <= 0 || r.areaSqm in area * 0.8..area * 1.2
            val lot = sameLot(p)
            val complex = if (lot == null) emptyList() else all.filter { it.dong == p.dong && lotKey(it.jibun) == lot && similar(it) }
            val dong = all.filter { it.dong == p.dong && similar(it) }
            val (chosen, scope) = when {
                complex.size >= 2 && kind != RentKind.SH -> complex to "같은 단지"
                dong.size >= 3 -> dong to "같은 동"
                else -> all.filter(::similar) to "같은 시군구"
            }
            fun perSqmMedian(values: List<Pair<Long, Double>>): Long? {
                val list = values.filter { it.second > 0 }.map { Math.round(it.first / it.second) }.sorted()
                return list.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
            }
            fun scale(perSqm: Long?) = if (perSqm != null && area != null && area > 0) Math.round(perSqm * area) else null
            val jeonse = chosen.filter { it.monthly == 0L }
            val monthly = chosen.filter { it.monthly > 0 }
            return RentSummary(
                kind = kind,
                months = months,
                scope = scope,
                jeonseCount = jeonse.size,
                monthlyCount = monthly.size,
                jeonseEstimate = scale(perSqmMedian(jeonse.map { it.deposit to it.areaSqm })),
                monthlyDeposit = scale(perSqmMedian(monthly.map { it.deposit to it.areaSqm })),
                monthlyRent = scale(perSqmMedian(monthly.map { it.monthly to it.areaSqm })),
            )
        }

        private fun medianPerSqm(trades: List<Trade>): Long? {
            val perSqm = trades.filter { it.areaSqm > 0 }.map { Math.round(it.price / it.areaSqm) }.sorted()
            return perSqm.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
        }

        /** "936-31" → "936-31", "0936-0031" → "936-31", "936" → "936" */
        private fun lotKey(jibun: String): String? {
            val match = Regex("(\\d+)(?:-(\\d+))?").find(jibun.replace("산", "")) ?: return null
            val main = match.groupValues[1].toIntOrNull() ?: return null
            val sub = match.groupValues[2].toIntOrNull() ?: 0
            return if (sub > 0) "$main-$sub" else "$main"
        }

        private fun sameLot(p: Property): String? {
            val lot = AddressParser.lotFromPnu(p.lotPnu) ?: AddressParser.lotFromName(p.name, p.dong) ?: return null
            return lotKey(lot)
        }
    }
}
