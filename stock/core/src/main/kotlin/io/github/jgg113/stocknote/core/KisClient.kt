package io.github.jgg113.stocknote.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 한국투자증권이 Open API 이용자에게 공개하는 종목 마스터 파일 (코스피·코스닥 전 종목의 코드와 이름).
 * 줄마다 길이가 고정된 CP949 글자 자료다: 단축코드(9) + 표준코드(12) + 한글 이름, 그 뒤 고정 길이 꼬리.
 */
object KisMaster {
    const val KOSPI_URL = "https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip"
    const val KOSDAQ_URL = "https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip"
    private const val KOSPI_TAIL = 228
    private const val KOSDAQ_TAIL = 222

    private val charset: java.nio.charset.Charset by lazy {
        listOf("MS949", "x-windows-949", "windows-949", "EUC-KR").firstNotNullOfOrNull { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    fun parse(bytes: ByteArray, kosdaq: Boolean): List<Asset> {
        val tail = if (kosdaq) KOSDAQ_TAIL else KOSPI_TAIL
        val out = ArrayList<Asset>()
        var start = 0
        while (start < bytes.size) {
            var end = start
            while (end < bytes.size && bytes[end] != '\n'.code.toByte()) end++
            var len = end - start
            if (len > 0 && bytes[end - 1] == '\r'.code.toByte()) len--
            if (len > tail + 21) {
                val head = len - tail
                val code = String(bytes, start, 9, Charsets.US_ASCII).trim()
                val name = String(bytes, start + 21, head - 21, charset).trim()
                val group = String(bytes, start + head + 1, 2, Charsets.US_ASCII)
                // 주권(ST)·ETF(EF)·ETN(EN)·리츠(RT)·외국주권(FS) 등 6자리 코드만 남긴다 (수익증권·워런트는 뺀다).
                if (code.length == 6 && name.isNotEmpty() && group in KEEP) out += Asset(Market.KR, code, name, if (kosdaq) "KOSDAQ" else "KOSPI")
            }
            start = end + 1
        }
        return out
    }

    private val KEEP = setOf("ST", "EF", "EN", "RT", "FS", "DR", "MF", "IF")

    fun unzip(zip: ByteArray): ByteArray {
        java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.endsWith(".mst", true)) return z.readBytes()
            }
        }
        return ByteArray(0)
    }

    fun download(http: Http): List<Asset> {
        val out = ArrayList<Asset>()
        for ((url, kosdaq) in listOf(KOSPI_URL to false, KOSDAQ_URL to true)) {
            val r = network("한국투자증권 종목 목록") { http.get(url) }
            if (r.status != 200) throw DataException("종목 목록을 받지 못했습니다 (HTTP ${r.status}).", "HTTP${r.status}")
            out += parse(unzip(r.bytes), kosdaq)
        }
        return out
    }
}

/** 접근 토큰 (하루 동안 쓰고, 1분에 한 번만 새로 받을 수 있어 기기에 저장해 둔다) */
@Serializable
data class KisToken(val token: String, val expiresAt: Long, val appKeyHash: Int)

interface KisTokenStore {
    fun load(): KisToken?
    fun save(token: KisToken)
}

class MemoryTokenStore : KisTokenStore {
    private var t: KisToken? = null
    override fun load() = t
    override fun save(token: KisToken) { t = token }
}

/**
 * 한국투자증권 Open API (KIS Developers). 계좌를 만들고 앱키·시크릿을 받으면 무료로 국내·해외 실시간 시세를 쓸 수 있다.
 * 이 앱은 시세 조회만 하고 주문은 하지 않는다.
 */
class KisClient(
    appKey: String,
    appSecret: String,
    private val virtual: Boolean = false,
    private val tokens: KisTokenStore = MemoryTokenStore(),
    private val http: Http = UrlConnectionHttp(),
) {
    private val key = KeyText.clean(appKey)
    private val secret = KeyText.clean(appSecret)
    private val base = if (virtual) VIRTUAL else REAL
    private var lastCall = 0L

    val configured: Boolean get() = key.isNotEmpty() && secret.isNotEmpty()

    /** 토큰은 앱키와 서버(실전·모의)마다 다르다. */
    private val tokenTag = "$key|$base".hashCode()
    private val serverName = if (virtual) "모의" else "실전"

    @Synchronized
    private fun token(): String {
        val saved = tokens.load()
        val now = System.currentTimeMillis() / 1000
        if (saved != null && saved.appKeyHash == tokenTag && saved.expiresAt - 600 > now) return saved.token
        val body = buildJsonObject {
            put("grant_type", "client_credentials")
            put("appkey", key)
            put("appsecret", secret)
        }.toString()
        val r = network("한국투자증권") { http.post("$base/oauth2/tokenP", mapOf("Content-Type" to "application/json; charset=UTF-8"), body) }
        val o = parseJson(r.body).obj()
        val access = o?.str("access_token").orEmpty()
        if (r.status != 200 || access.isEmpty()) {
            val code = o?.str("error_code").orEmpty()
            val desc = o?.str("error_description").orEmpty()
            val badKey = code == "EGW00103" || code == "EGW00105" || code == "EGW00002" || desc.contains("유효하지 않은") || desc.contains("AppKey", true) || desc.contains("AppSecret", true)
            throw when {
                code == "EGW00133" -> DataException("한국투자증권 접근 토큰은 1분에 한 번만 받을 수 있습니다. 1분 뒤 다시 시도하세요.", "KIS_WAIT", retryable = true)
                badKey -> DataException("한국투자증권 $serverName 서버가 이 키를 받아 주지 않습니다: ${desc.ifBlank { code }}", "KIS_KEY")
                else -> DataException("한국투자증권 토큰을 받지 못했습니다 ($serverName 서버): ${desc.ifBlank { "HTTP ${r.status}" }}", "KIS_TOKEN$code")
            }
        }
        val expiresIn = o?.dbl("expires_in")?.toLong() ?: 86_400
        tokens.save(KisToken(access, now + expiresIn, tokenTag))
        return access
    }

    private fun get(path: String, trId: String, params: Map<String, String>, retry: Boolean = true): JsonObject {
        if (!configured) throw DataException("설정에서 한국투자증권 앱키와 시크릿을 넣으세요.", "NOKEY")
        // 초당 호출 제한 (실전 20회·모의 2회) 을 넘지 않게 간격을 둔다.
        synchronized(this) {
            val gap = if (virtual) 520L else 60L
            val wait = lastCall + gap - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall = System.currentTimeMillis()
        }
        val headers = mapOf(
            "content-type" to "application/json; charset=utf-8",
            "authorization" to "Bearer ${token()}",
            "appkey" to key,
            "appsecret" to secret,
            "tr_id" to trId,
            "custtype" to "P",
        )
        val r = network("한국투자증권") { http.get("$base$path?" + query(params), headers) }
        val o = parseJson(r.body).obj() ?: throw DataException("한국투자증권 응답을 읽지 못했습니다 (HTTP ${r.status}).", "PARSE", r.status >= 500)
        val rt = o.str("rt_cd")
        val msgCd = o.str("msg_cd")
        if (msgCd == "EGW00123" || msgCd == "EGW00121") {
            // 토큰 만료 → 한 번만 새로 받는다
            if (retry) {
                tokens.save(KisToken("", 0, 0))
                return get(path, trId, params, false)
            }
        }
        if (msgCd == "EGW00201") {
            if (retry) {
                Thread.sleep(1100)
                return get(path, trId, params, false)
            }
            throw DataException("한국투자증권 초당 호출 한도를 넘었습니다. 잠시 뒤 다시 시도하세요.", msgCd, retryable = true)
        }
        if (rt.isNotEmpty() && rt != "0") throw DataException("한국투자증권: ${o.str("msg1").ifBlank { msgCd }}", msgCd)
        if (r.status != 200) throw DataException("한국투자증권 응답 오류 (HTTP ${r.status})", "HTTP${r.status}", r.status >= 500)
        return o
    }

    // ───── 국내 ─────

    fun domesticQuote(code: String, name: String = "", exchange: String = ""): Quote {
        val o = get("/uapi/domestic-stock/v1/quotations/inquire-price", "FHKST01010100", mapOf("FID_COND_MRKT_DIV_CODE" to "J", "FID_INPUT_ISCD" to code))
        return parseDomesticQuote(o["output"].obj() ?: throw DataException("한국투자증권 시세가 비어 있습니다.", "EMPTY"), Asset(Market.KR, code, name, exchange))
    }

    /** 일봉 ([from]~[to], 100개씩 나눠 받는다) */
    fun domesticDaily(code: String, from: LocalDate, to: LocalDate = LocalDate.now(KST)): List<Candle> {
        val out = ArrayList<Candle>()
        var end = to
        var guard = 0
        while (!end.isBefore(from) && guard++ < 15) {
            val o = get(
                "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice", "FHKST03010100",
                mapOf(
                    "FID_COND_MRKT_DIV_CODE" to "J", "FID_INPUT_ISCD" to code,
                    "FID_INPUT_DATE_1" to from.format(YMD), "FID_INPUT_DATE_2" to end.format(YMD),
                    "FID_PERIOD_DIV_CODE" to "D", "FID_ORG_ADJ_PRC" to "0",
                ),
            )
            val page = o["output2"].asList().mapNotNull { parseDomesticDaily(it) }
            if (page.isEmpty()) break
            out += page
            val oldest = page.minOf { it.time }
            end = java.time.Instant.ofEpochSecond(oldest).atZone(KST).toLocalDate().minusDays(1)
            if (page.size < 100) break
        }
        return out.distinctBy { it.time }.sortedBy { it.time }
    }

    /** 오늘 분봉 (1분, 30개씩 거슬러 받는다) */
    fun domesticMinutes(code: String, maxBars: Int = 390, now: LocalDateTime = LocalDateTime.now(KST)): List<Candle> {
        val out = ArrayList<Candle>()
        var hour = minOf(now.toLocalTime(), LocalTime.of(15, 30)).format(HMS)
        var guard = 0
        while (out.size < maxBars && guard++ < 15) {
            val o = get(
                "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice", "FHKST03010200",
                mapOf(
                    "FID_ETC_CLS_CODE" to "", "FID_COND_MRKT_DIV_CODE" to "J", "FID_INPUT_ISCD" to code,
                    "FID_INPUT_HOUR_1" to hour, "FID_PW_DATA_INCU_YN" to "Y",
                ),
            )
            val page = o["output2"].asList().mapNotNull { parseDomesticMinute(it) }
            if (page.isEmpty()) break
            out += page
            val oldest = page.minOf { it.time }
            val t = java.time.Instant.ofEpochSecond(oldest).atZone(KST).toLocalDateTime().minusMinutes(1)
            if (t.toLocalTime().isBefore(LocalTime.of(9, 0)) || t.toLocalDate() != now.toLocalDate()) break
            hour = t.toLocalTime().format(HMS)
            if (page.size < 30) break
        }
        return out.distinctBy { it.time }.sortedBy { it.time }
    }

    fun index(code: String, name: String): IndexQuote {
        val o = get("/uapi/domestic-stock/v1/quotations/inquire-index-price", "FHPUP02100000", mapOf("FID_COND_MRKT_DIV_CODE" to "U", "FID_INPUT_ISCD" to code))
        val out = o["output"].obj() ?: throw DataException("지수 값이 비어 있습니다.", "EMPTY")
        return IndexQuote(
            name, out.dbl("bstp_nmix_prpr") ?: 0.0, signed(out.dbl("bstp_nmix_prdy_vrss"), out.str("prdy_vrss_sign")),
            signed(out.dbl("bstp_nmix_prdy_ctrt"), out.str("prdy_vrss_sign")), System.currentTimeMillis() / 1000, "한국투자증권",
        )
    }

    /** 실시간 거래량·거래대금 순위 (실전 서버만) */
    fun volumeRank(byValue: Boolean): List<Quote> {
        val o = get(
            "/uapi/domestic-stock/v1/quotations/volume-rank", "FHPST01710000",
            mapOf(
                "FID_COND_MRKT_DIV_CODE" to "J", "FID_COND_SCR_DIV_CODE" to "20171", "FID_INPUT_ISCD" to "0000",
                "FID_DIV_CLS_CODE" to "0", "FID_BLNG_CLS_CODE" to if (byValue) "3" else "0",
                "FID_TRGT_CLS_CODE" to "111111111", "FID_TRGT_EXLS_CLS_CODE" to "0000000000",
                "FID_INPUT_PRICE_1" to "", "FID_INPUT_PRICE_2" to "", "FID_VOL_CNT" to "", "FID_INPUT_DATE_1" to "",
            ),
        )
        return o["output"].asList().mapNotNull { r ->
            val code = r.str("mksc_shrn_iscd").ifBlank { return@mapNotNull null }
            val sign = r.str("prdy_vrss_sign")
            Quote(
                Asset(Market.KR, code, r.str("hts_kor_isnm")), r.dbl("stck_prpr") ?: return@mapNotNull null,
                signed(r.dbl("prdy_vrss"), sign), signed(r.dbl("prdy_ctrt"), sign),
                volume = r.dbl("acml_vol") ?: 0.0, value = r.dbl("acml_tr_pbmn") ?: 0.0,
                time = System.currentTimeMillis() / 1000, realtime = true, source = "한국투자증권",
            )
        }
    }

    // ───── 해외 ─────

    /** 해외 현재가. 거래소가 틀리면 빈 값이 오므로 null. */
    fun overseasQuote(symbol: String, exchange: String, name: String = ""): Quote? {
        val asset = Asset(Market.US, symbol, name.ifBlank { symbol }, exchange)
        val detail = runCatching {
            get("/uapi/overseas-price/v1/quotations/price-detail", "HHDFS76200200", mapOf("AUTH" to "", "EXCD" to exchange, "SYMB" to symbol))
        }.getOrNull()?.get("output")?.obj()
        parseOverseasDetail(detail, asset)?.let { return it }
        val o = get("/uapi/overseas-price/v1/quotations/price", "HHDFS00000300", mapOf("AUTH" to "", "EXCD" to exchange, "SYMB" to symbol))
        return parseOverseasPrice(o["output"].obj(), asset)
    }

    fun overseasDaily(symbol: String, exchange: String, count: Int = 500): List<Candle> {
        val out = ArrayList<Candle>()
        var bymd = ""
        var guard = 0
        while (out.size < count && guard++ < 10) {
            val o = get(
                "/uapi/overseas-price/v1/quotations/dailyprice", "HHDFS76240000",
                mapOf("AUTH" to "", "EXCD" to exchange, "SYMB" to symbol, "GUBN" to "0", "BYMD" to bymd, "MODP" to "1"),
            )
            val page = o["output2"].asList().mapNotNull { parseOverseasDaily(it) }
            if (page.isEmpty()) break
            out += page
            val oldest = java.time.Instant.ofEpochSecond(page.minOf { it.time }).atZone(KST).toLocalDate()
            bymd = oldest.minusDays(1).format(YMD)
            if (page.size < 100) break
        }
        return out.distinctBy { it.time }.sortedBy { it.time }
    }

    companion object {
        const val REAL = "https://openapi.koreainvestment.com:9443"
        const val VIRTUAL = "https://openapivts.koreainvestment.com:29443"
        const val SIGNUP = "https://apiportal.koreainvestment.com/intro"
        val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val HMS: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmmss")

        /** 부호 코드 (1 상한·2 상승·3 보합·4 하한·5 하락) 로 부호를 맞춘다. */
        fun signed(v: Double?, sign: String): Double {
            val x = v ?: 0.0
            return when (sign) {
                "4", "5" -> -kotlin.math.abs(x)
                "1", "2" -> kotlin.math.abs(x)
                else -> x
            }
        }

        fun parseDomesticQuote(o: JsonObject, asset: Asset): Quote {
            val price = o.dbl("stck_prpr") ?: throw DataException("현재가가 비어 있습니다.", "EMPTY")
            val sign = o.str("prdy_vrss_sign")
            val change = signed(o.dbl("prdy_vrss"), sign)
            return Quote(
                asset = asset.copy(name = asset.name.ifBlank { o.str("hts_kor_isnm").ifBlank { asset.code } }, exchange = asset.exchange.ifBlank { o.str("rprs_mrkt_kor_name") }),
                price = price,
                change = change,
                changeRate = signed(o.dbl("prdy_ctrt"), sign),
                open = o.dbl("stck_oprc") ?: 0.0,
                high = o.dbl("stck_hgpr") ?: 0.0,
                low = o.dbl("stck_lwpr") ?: 0.0,
                prevClose = o.dbl("stck_sdpr") ?: (price - change),
                volume = o.dbl("acml_vol") ?: 0.0,
                value = o.dbl("acml_tr_pbmn") ?: 0.0,
                marketCap = (o.dbl("hts_avls") ?: 0.0) * 1e8,
                shares = o.dbl("lstn_stcn") ?: 0.0,
                per = o.dbl("per")?.takeIf { it != 0.0 },
                pbr = o.dbl("pbr")?.takeIf { it != 0.0 },
                eps = o.dbl("eps"),
                bps = o.dbl("bps"),
                high52 = o.dbl("w52_hgpr")?.takeIf { it > 0 },
                low52 = o.dbl("w52_lwpr")?.takeIf { it > 0 },
                time = System.currentTimeMillis() / 1000,
                realtime = true,
                source = "한국투자증권",
            )
        }

        fun parseDomesticDaily(o: JsonObject): Candle? {
            val date = runCatching { LocalDate.parse(o.str("stck_bsop_date"), YMD) }.getOrNull() ?: return null
            val close = o.dbl("stck_clpr")?.takeIf { it > 0 } ?: return null
            return Candle(date.epochKst(), o.dbl("stck_oprc") ?: close, o.dbl("stck_hgpr") ?: close, o.dbl("stck_lwpr") ?: close, close, o.dbl("acml_vol") ?: 0.0, o.dbl("acml_tr_pbmn") ?: 0.0)
        }

        fun parseDomesticMinute(o: JsonObject): Candle? {
            val date = runCatching { LocalDate.parse(o.str("stck_bsop_date"), YMD) }.getOrNull() ?: return null
            val time = runCatching { LocalTime.parse(o.str("stck_cntg_hour"), HMS) }.getOrNull() ?: return null
            val close = o.dbl("stck_prpr")?.takeIf { it > 0 } ?: return null
            return Candle(LocalDateTime.of(date, time).epochKst(), o.dbl("stck_oprc") ?: close, o.dbl("stck_hgpr") ?: close, o.dbl("stck_lwpr") ?: close, close, o.dbl("cntg_vol") ?: 0.0)
        }

        fun parseOverseasPrice(o: JsonObject?, asset: Asset): Quote? {
            if (o == null) return null
            val last = o.dbl("last")?.takeIf { it > 0 } ?: return null
            val sign = o.str("sign")
            val base = o.dbl("base") ?: 0.0
            return Quote(
                asset, last, signed(o.dbl("diff"), sign), signed(o.dbl("rate"), sign),
                prevClose = base, volume = o.dbl("tvol") ?: 0.0, value = o.dbl("tamt") ?: 0.0,
                time = System.currentTimeMillis() / 1000, realtime = true, source = "한국투자증권",
            )
        }

        fun parseOverseasDetail(o: JsonObject?, asset: Asset): Quote? {
            if (o == null) return null
            val last = o.dbl("last")?.takeIf { it > 0 } ?: return null
            val base = o.dbl("base")?.takeIf { it > 0 } ?: last
            val change = last - base
            return Quote(
                asset = asset,
                price = last,
                change = change,
                changeRate = if (base > 0) change / base * 100 else 0.0,
                open = o.dbl("open") ?: 0.0,
                high = o.dbl("high") ?: 0.0,
                low = o.dbl("low") ?: 0.0,
                prevClose = base,
                volume = o.dbl("tvol") ?: 0.0,
                value = o.dbl("tamt") ?: 0.0,
                marketCap = o.dbl("tomv") ?: 0.0,
                shares = o.dbl("shar") ?: 0.0,
                per = o.dbl("perx")?.takeIf { it != 0.0 },
                pbr = o.dbl("pbrx")?.takeIf { it != 0.0 },
                eps = o.dbl("epsx"),
                bps = o.dbl("bpsx"),
                high52 = o.dbl("h52p")?.takeIf { it > 0 },
                low52 = o.dbl("l52p")?.takeIf { it > 0 },
                time = System.currentTimeMillis() / 1000,
                realtime = true,
                source = "한국투자증권",
            )
        }

        fun parseOverseasDaily(o: JsonObject): Candle? {
            val date = runCatching { LocalDate.parse(o.str("xymd"), YMD) }.getOrNull() ?: return null
            val close = o.dbl("clos")?.takeIf { it > 0 } ?: return null
            return Candle(date.epochKst(), o.dbl("open") ?: close, o.dbl("high") ?: close, o.dbl("low") ?: close, close, o.dbl("tvol") ?: 0.0, o.dbl("tamt") ?: 0.0)
        }
    }
}
