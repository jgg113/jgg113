package io.github.jgg113.gongmae.core

import org.w3c.dom.Element
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

/** 국토교통부 실거래가 공개 API 종류 (공공데이터포털에서 따로 활용신청한다). */
enum class MarketKind(val label: String, val path: String, val nameTag: String, val areaTag: String, val apiName: String) {
    APT("아파트", "RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev", "aptNm", "excluUseAr", "국토교통부_아파트 매매 실거래가 상세 자료"),
    RH("연립·다세대", "RTMSDataSvcRHTrade/getRTMSDataSvcRHTrade", "mhouseNm", "excluUseAr", "국토교통부_연립다세대 매매 실거래가 자료"),
    OFFI("오피스텔", "RTMSDataSvcOffiTrade/getRTMSDataSvcOffiTrade", "offiNm", "excluUseAr", "국토교통부_오피스텔 매매 실거래가 자료"),
    SH("단독·다가구", "RTMSDataSvcSHTrade/getRTMSDataSvcSHTrade", "umdNm", "totalFloorAr", "국토교통부_단독/다가구 매매 실거래가 자료"),
    LAND("토지", "RTMSDataSvcLandTrade/getRTMSDataSvcLandTrade", "umdNm", "dealArea", "국토교통부_토지 매매 실거래가 자료"),
    COMMERCIAL("상업·업무용", "RTMSDataSvcNrgTrade/getRTMSDataSvcNrgTrade", "umdNm", "buildingAr", "국토교통부_상업업무용 부동산 매매 실거래가 자료");

    companion object {
        fun of(category: Category): MarketKind? = when (category) {
            Category.APARTMENT -> APT
            Category.VILLA -> RH
            Category.OFFICETEL -> OFFI
            Category.HOUSE -> SH
            Category.LAND, Category.FOREST -> LAND
            Category.COMMERCIAL, Category.FACTORY -> COMMERCIAL
            Category.ETC -> null
        }
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

    fun trades(kind: MarketKind, lawdCode: String, yearMonth: String): List<Trade> {
        val url = "https://apis.data.go.kr/1613000/${kind.path}?serviceKey=" + URLEncoder.encode(key, "UTF-8") +
            "&LAWD_CD=$lawdCode&DEAL_YMD=$yearMonth&pageNo=1&numOfRows=1000"
        val result = try {
            http.get(url)
        } catch (e: IOException) {
            throw OnbidApiException(OnbidApiException.NETWORK, "인터넷 연결을 확인하세요. (${e.message ?: e.javaClass.simpleName})")
        }
        return parse(result.body, kind, result.status)
    }

    fun summarize(p: Property, now: LocalDate = LocalDate.now(), months: Int = 6): MarketSummary? {
        val kind = MarketKind.of(p.category) ?: return null
        if (p.lawdCode.length != 5) return null
        val all = (0 until months).flatMap { back -> trades(kind, p.lawdCode, now.minusMonths(back.toLong()).format(YM)) }
        return summarize(p, kind, all, months)
    }

    companion object {
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
                )
            }
        }

        fun summarize(p: Property, kind: MarketKind, all: List<Trade>, months: Int): MarketSummary {
            val area = p.areaSqm
            fun similar(t: Trade) = area == null || area <= 0 || t.areaSqm in area * 0.8..area * 1.2
            val sameDong = all.filter { it.dong == p.dong && similar(it) }
            val chosen = if (sameDong.size >= 3) sameDong else all.filter(::similar)
            val perSqm = chosen.filter { it.areaSqm > 0 }.map { Math.round(it.price / it.areaSqm) }.sorted()
            val median = perSqm.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
            val estimate = if (median != null && area != null && area > 0) Math.round(median * area) else null
            return MarketSummary(kind, months, chosen.sortedByDescending { it.date }, sameDong.size >= 3, median, estimate)
        }
    }
}
