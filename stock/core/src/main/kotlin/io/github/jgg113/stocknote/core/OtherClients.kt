package io.github.jgg113.stocknote.core

import org.w3c.dom.Element
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Twelve Data (무료 키, 하루 800회·분당 8회). 한국투자증권 계좌가 없을 때 미국 주식 시세·일봉을 받는다.
 */
class TwelveDataClient(apiKey: String, private val http: Http = UrlConnectionHttp()) {
    private val key = KeyText.clean(apiKey)

    private fun call(path: String, params: Map<String, String>): String {
        if (key.isBlank()) throw DataException("설정에서 Twelve Data API 키를 넣거나 한국투자증권을 연결하세요.", "NOKEY")
        val r = network("Twelve Data") { http.get("$BASE/$path?" + query(params + ("apikey" to key))) }
        val o = parseJson(r.body).obj()
        if (o != null && o.str("status") == "error") {
            val code = o.str("code")
            throw DataException(
                when (code) {
                    "401" -> "Twelve Data API 키가 맞지 않습니다."
                    "429" -> "Twelve Data 무료 한도(분당 8회·하루 800회)를 넘었습니다. 잠시 뒤 다시 시도하세요."
                    "404", "400" -> "Twelve Data 에 없는 종목입니다."
                    else -> "Twelve Data: ${o.str("message").take(120)}"
                },
                "TD$code", retryable = code == "429",
            )
        }
        if (r.status != 200) throw DataException("Twelve Data 응답 오류 (HTTP ${r.status})", "HTTP${r.status}", r.status >= 500)
        return r.body
    }

    fun quote(asset: Asset): Quote? = parseQuote(call("quote", mapOf("symbol" to asset.code)), asset)

    fun daily(asset: Asset, count: Int = 500): List<Candle> =
        parseSeries(call("time_series", mapOf("symbol" to asset.code, "interval" to "1day", "outputsize" to count.coerceAtMost(5000).toString(), "order" to "ASC")))

    companion object {
        const val BASE = "https://api.twelvedata.com"
        const val KEY_PAGE = "https://twelvedata.com/pricing"

        fun parseQuote(body: String, asset: Asset): Quote? {
            val o = parseJson(body).obj() ?: return null
            val close = o.dbl("close") ?: return null
            val prev = o.dbl("previous_close") ?: 0.0
            val w = o["fifty_two_week"].obj()
            return Quote(
                asset.copy(name = asset.name.ifBlank { o.str("name") }),
                close, o.dbl("change") ?: (close - prev), o.dbl("percent_change") ?: 0.0,
                open = o.dbl("open") ?: 0.0, high = o.dbl("high") ?: 0.0, low = o.dbl("low") ?: 0.0, prevClose = prev,
                volume = o.dbl("volume") ?: 0.0, value = (o.dbl("volume") ?: 0.0) * close,
                high52 = w?.dbl("high"), low52 = w?.dbl("low"),
                time = o.dbl("timestamp")?.toLong() ?: 0, realtime = o.str("is_market_open") == "true", source = "Twelve Data",
            )
        }

        fun parseSeries(body: String): List<Candle> {
            val values = parseJson(body).obj()?.get("values").asList()
            return values.mapNotNull { v ->
                val date = runCatching { LocalDate.parse(v.str("datetime").take(10)) }.getOrNull() ?: return@mapNotNull null
                val close = v.dbl("close") ?: return@mapNotNull null
                Candle(date.epochKst(), v.dbl("open") ?: close, v.dbl("high") ?: close, v.dbl("low") ?: close, close, v.dbl("volume") ?: 0.0, (v.dbl("volume") ?: 0.0) * close)
            }.distinctBy { it.time }.sortedBy { it.time }
        }
    }
}

/** DART 고유번호 (종목코드 → 회사 고유번호) */
@kotlinx.serialization.Serializable
data class DartCorp(val corpCode: String, val name: String, val stockCode: String)

/** 재무 한 해(또는 한 분기) */
@kotlinx.serialization.Serializable
data class FinancialPeriod(
    val label: String,
    val revenue: Double? = null,
    val operatingIncome: Double? = null,
    val netIncome: Double? = null,
    val assets: Double? = null,
    val liabilities: Double? = null,
    val equity: Double? = null,
) {
    val operatingMargin: Double? get() = if (revenue != null && revenue != 0.0 && operatingIncome != null) operatingIncome / revenue * 100 else null
    val netMargin: Double? get() = if (revenue != null && revenue != 0.0 && netIncome != null) netIncome / revenue * 100 else null
    val debtRatio: Double? get() = if (equity != null && equity > 0 && liabilities != null) liabilities / equity * 100 else null
    val roe: Double? get() = if (equity != null && equity > 0 && netIncome != null) netIncome / equity * 100 else null
}

@kotlinx.serialization.Serializable
data class Financials(
    val corpName: String,
    /** 오래된 해 → 최근 해 */
    val annual: List<FinancialPeriod>,
    val latestQuarter: FinancialPeriod?,
    val consolidated: Boolean,
    val year: Int,
) {
    fun per(marketCap: Double): Double? = annual.lastOrNull()?.netIncome?.takeIf { it > 0 && marketCap > 0 }?.let { marketCap / it }
    fun pbr(marketCap: Double): Double? = annual.lastOrNull()?.equity?.takeIf { it > 0 && marketCap > 0 }?.let { marketCap / it }
}

/** 금융감독원 DART Open API (무료 인증키): 공시 목록·재무제표 */
class DartClient(apiKey: String, private val http: Http = UrlConnectionHttp()) {
    private val key = KeyText.clean(apiKey)

    private fun call(path: String, params: Map<String, String>): kotlinx.serialization.json.JsonObject {
        if (key.isBlank()) throw DataException("설정에서 DART 인증키를 넣으세요.", "NOKEY")
        val r = network("DART") { http.get("$BASE/$path?" + query(linkedMapOf("crtfc_key" to key) + params)) }
        val o = parseJson(r.body).obj() ?: throw DataException("DART 응답을 읽지 못했습니다 (HTTP ${r.status}).", "PARSE")
        checkStatus(o.str("status"), o.str("message"))
        return o
    }

    /** 상장 회사 고유번호 목록 (ZIP 안의 XML, 약 3MB) */
    fun corpCodes(): List<DartCorp> {
        if (key.isBlank()) throw DataException("설정에서 DART 인증키를 넣으세요.", "NOKEY")
        val r = network("DART") { http.get("$BASE/corpCode.xml?crtfc_key=${enc(key)}") }
        if (r.bytes.size > 2 && r.bytes[0] == 'P'.code.toByte() && r.bytes[1] == 'K'.code.toByte()) return parseCorpZip(r.bytes)
        // 오류는 XML/JSON 글로 온다.
        val status = Regex("<status>(\\d+)</status>|\"status\"\\s*:\\s*\"(\\d+)\"").find(r.body)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }.orEmpty()
        val message = Regex("<message>([^<]*)</message>|\"message\"\\s*:\\s*\"([^\"]*)\"").find(r.body)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }.orEmpty()
        checkStatus(status.ifEmpty { "900" }, message)
        throw DataException("DART 회사 목록을 받지 못했습니다.", "EMPTY")
    }

    /** 공시 목록 (회사를 정하지 않으면 전체 시장 최근 공시) */
    fun disclosures(corpCode: String?, from: LocalDate, to: LocalDate = LocalDate.now(KST), count: Int = 50): List<NewsItem> {
        val params = linkedMapOf("bgn_de" to from.format(YMD), "end_de" to to.format(YMD), "page_no" to "1", "page_count" to count.coerceIn(1, 100).toString())
        if (!corpCode.isNullOrBlank()) params["corp_code"] = corpCode
        val o = try {
            call("list.json", params)
        } catch (e: DataException) {
            if (e.code == "013") return emptyList() else throw e
        }
        return o["list"].asList().map { d ->
            val date = runCatching { LocalDate.parse(d.str("rcept_dt"), YMD).epochKst() }.getOrDefault(0)
            NewsItem(
                title = d.str("report_nm").trim().replace(Regex("\\s+"), " "),
                link = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + d.str("rcept_no"),
                source = d.str("corp_name") + (d.str("flr_nm").takeIf { it.isNotBlank() && it != d.str("corp_name") }?.let { " · $it" } ?: ""),
                published = date,
                kind = NewsKind.DISCLOSURE,
            )
        }
    }

    /** 5% 넘게 가진 주주의 지분 변동 보고 (대량보유 상황보고), 최근 것부터 */
    fun majorHoldings(corpCode: String, limit: Int = 12): List<OwnershipReport> = listOrEmpty("majorstock.json", mapOf("corp_code" to corpCode))
        .mapNotNull { parseOwnership(it, major = true) }.sortedByDescending { it.date }.take(limit)

    /** 임원·주요주주가 자기 회사 주식을 사고판 보고, 최근 것부터 */
    fun insiderReports(corpCode: String, limit: Int = 12): List<OwnershipReport> = listOrEmpty("elestock.json", mapOf("corp_code" to corpCode))
        .mapNotNull { parseOwnership(it, major = false) }.sortedByDescending { it.date }.take(limit)

    /** 배당 (가장 최근 사업보고서 기준, 없으면 한 해 앞) */
    fun dividend(corpCode: String, today: LocalDate = LocalDate.now(KST)): DividendInfo? {
        for (year in listOf(today.year - 1, today.year - 2)) {
            val rows = listOrEmpty("alotMatter.json", mapOf("corp_code" to corpCode, "bsns_year" to year.toString(), "reprt_code" to "11011"))
            parseDividend(rows, year)?.let { return it }
        }
        return null
    }

    private fun listOrEmpty(path: String, params: Map<String, String>): List<kotlinx.serialization.json.JsonObject> = try {
        call(path, params)["list"].asList()
    } catch (e: DataException) {
        if (e.code == "013") emptyList() else throw e
    }

    /** 주요 계정 재무 (사업보고서 3년치 + 가장 최근 분기) */
    fun financials(corpCode: String, today: LocalDate = LocalDate.now(KST)): Financials? {
        var annual: Pair<Int, List<kotlinx.serialization.json.JsonObject>>? = null
        for (year in listOf(today.year - 1, today.year - 2)) {
            val rows = accounts(corpCode, year, "11011")
            if (rows.isNotEmpty()) { annual = year to rows; break }
        }
        var quarter: FinancialPeriod? = null
        for ((reprt, label) in listOf("11014" to "3분기", "11012" to "반기", "11013" to "1분기")) {
            val rows = accounts(corpCode, today.year, reprt)
            if (rows.isNotEmpty()) {
                quarter = period(rows, "${today.year} $label (누적)", "thstrm", cumulative = true)
                break
            }
        }
        val (year, rows) = annual ?: return quarter?.let { Financials("", emptyList(), it, true, today.year) }
        val periods = listOf("bfefrmtrm" to year - 2, "frmtrm" to year - 1, "thstrm" to year).mapNotNull { (k, y) -> period(rows, "${y}년", k) }
            .filter { it.revenue != null || it.netIncome != null || it.assets != null }
        val cfs = rows.any { it.str("fs_div") == "CFS" }
        return Financials(rows.firstOrNull()?.str("corp_name").orEmpty(), periods, quarter, cfs, year)
    }

    private fun accounts(corpCode: String, year: Int, reprt: String): List<kotlinx.serialization.json.JsonObject> = try {
        call("fnlttSinglAcnt.json", mapOf("corp_code" to corpCode, "bsns_year" to year.toString(), "reprt_code" to reprt))["list"].asList()
    } catch (e: DataException) {
        if (e.code == "013") emptyList() else throw e
    }

    companion object {
        const val BASE = "https://opendart.fss.or.kr/api"
        const val KEY_PAGE = "https://opendart.fss.or.kr/uss/umt/EgovMberInsertView.do"
        val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        private fun date(o: kotlinx.serialization.json.JsonObject): Long = runCatching { LocalDate.parse(o.str("rcept_dt").replace("-", "").replace(".", ""), YMD).epochKst() }.getOrDefault(0)

        fun parseOwnership(o: kotlinx.serialization.json.JsonObject, major: Boolean): OwnershipReport? {
            val who = o.str("repror").trim().ifEmpty { return null }
            return if (major) OwnershipReport(
                date(o), who, "5% 이상 주주", o.dbl("stkqy") ?: 0.0, o.dbl("stkqy_irds") ?: 0.0, o.dbl("stkrt"), o.dbl("stkrt_irds"),
                o.str("report_resn").trim().replace(Regex("\\s+"), " ").take(80), "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + o.str("rcept_no"),
            ) else OwnershipReport(
                date(o), who, listOf(o.str("isu_exctv_ofcps"), o.str("isu_main_shrholdr")).map { it.trim() }.filter { it.isNotEmpty() && it != "-" }.joinToString(" · ").ifEmpty { "임원" },
                o.dbl("sp_stock_lmp_cnt") ?: 0.0, o.dbl("sp_stock_lmp_irds_cnt") ?: 0.0, o.dbl("sp_stock_lmp_rate"), o.dbl("sp_stock_lmp_irds_rate"),
                "", "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + o.str("rcept_no"),
            )
        }

        fun parseDividend(rows: List<kotlinx.serialization.json.JsonObject>, year: Int): DividendInfo? {
            // 보통주 줄을 먼저 쓴다 (우선주 줄이 따로 온다).
            fun pick(name: String, field: String): Double? = rows.filter { it.str("se").replace(" ", "").contains(name) }
                .sortedBy { if (it.str("stock_knd").contains("우선")) 1 else 0 }.firstNotNullOfOrNull { it.dbl(field) }
            val dps = pick("주당현금배당금", "thstrm")
            val yieldPct = pick("현금배당수익률", "thstrm")
            val payout = pick("현금배당성향", "thstrm")
            if (dps == null && yieldPct == null && payout == null) return null
            return DividendInfo(year, dps, yieldPct, payout, pick("주당현금배당금", "frmtrm"), pick("주당현금배당금", "lwfr"))
        }

        fun checkStatus(status: String, message: String) {
            if (status.isEmpty() || status == "000") return
            throw DataException(
                when (status) {
                    "010" -> "DART 인증키가 등록되지 않았습니다. 키를 다시 확인하세요."
                    "011" -> "DART 인증키를 쓸 수 없는 상태입니다."
                    "012" -> "이 IP 에서는 DART 키를 쓸 수 없습니다."
                    "013" -> "조회된 공시·재무가 없습니다."
                    "020" -> "DART 하루 호출 한도(약 2만 회)를 넘었습니다. 내일 다시 됩니다."
                    "800" -> "DART 시스템 점검 중입니다."
                    else -> "DART 오류 ($status) $message".trim()
                },
                status, retryable = status == "020" || status == "800",
            )
        }

        private val names = mapOf(
            "revenue" to listOf("매출액", "수익(매출액)", "영업수익", "매출"),
            "operatingIncome" to listOf("영업이익", "영업이익(손실)"),
            "netIncome" to listOf("당기순이익", "당기순이익(손실)", "분기순이익", "반기순이익", "연결당기순이익"),
            "assets" to listOf("자산총계"),
            "liabilities" to listOf("부채총계"),
            "equity" to listOf("자본총계"),
        )

        /** 연결재무(CFS)가 있으면 그것을, 없으면 별도(OFS)를 쓴다. */
        fun period(rows: List<kotlinx.serialization.json.JsonObject>, label: String, prefix: String, cumulative: Boolean = false): FinancialPeriod? {
            val fs = if (rows.any { it.str("fs_div") == "CFS" }) rows.filter { it.str("fs_div") == "CFS" } else rows
            fun find(field: String): Double? {
                val wanted = names[field] ?: return null
                val row = fs.firstOrNull { r -> wanted.any { r.str("account_nm").replace(" ", "") == it } } ?: return null
                val isFlow = row.str("sj_div") == "IS" || row.str("sj_div") == "CIS"
                val amountKey = if (cumulative && isFlow && prefix == "thstrm") "thstrm_add_amount" else "${prefix}_amount"
                return row.str(amountKey).num() ?: row.str("${prefix}_amount").num()
            }
            val p = FinancialPeriod(label, find("revenue"), find("operatingIncome"), find("netIncome"), find("assets"), find("liabilities"), find("equity"))
            return if (listOf(p.revenue, p.operatingIncome, p.netIncome, p.assets, p.equity).all { it == null }) null else p
        }

        /** ZIP 안의 CORPCODE.xml 을 줄 단위로 읽어 종목코드가 있는 회사만 남긴다 (메모리를 아끼려고 DOM 을 쓰지 않는다). */
        fun parseCorpZip(bytes: ByteArray): List<DartCorp> {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.name.endsWith(".xml", true)) continue
                    return parseCorpXml(BufferedReader(InputStreamReader(zip, Charsets.UTF_8)))
                }
            }
            return emptyList()
        }

        fun parseCorpXml(reader: BufferedReader): List<DartCorp> {
            val out = ArrayList<DartCorp>()
            val block = StringBuilder()
            val tag = Regex("<(corp_code|corp_name|stock_code)>([^<]*)</\\1>")
            reader.forEachLine { line ->
                block.append(line)
                if (line.contains("</list>")) {
                    val fields = tag.findAll(block).associate { it.groupValues[1] to it.groupValues[2].trim() }
                    val stock = fields["stock_code"].orEmpty()
                    if (stock.isNotBlank()) out += DartCorp(fields["corp_code"].orEmpty(), fields["corp_name"].orEmpty(), stock)
                    block.setLength(0)
                }
            }
            return out
        }
    }
}

/** Google 뉴스 RSS (키 필요 없음, 개인용 읽기) */
class NewsClient(private val http: Http = UrlConnectionHttp()) {
    fun search(query: String, limit: Int = 30): List<NewsItem> {
        val url = "https://news.google.com/rss/search?q=" + enc(query) + "&hl=ko&gl=KR&ceid=KR:ko"
        val r = network("뉴스") { http.get(url, mapOf("Accept" to "application/rss+xml")) }
        if (r.status != 200) throw DataException("뉴스를 불러오지 못했습니다 (HTTP ${r.status})", "HTTP${r.status}")
        return parse(r.body).take(limit)
    }

    companion object {
        private val rfc: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

        private fun date(text: String): Long =
            (runCatching { ZonedDateTime.parse(text, rfc) }.getOrNull()
                ?: runCatching { ZonedDateTime.parse(text.substringAfter(", ").trim(), DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss z", Locale.US)) }.getOrNull())
                ?.toEpochSecond() ?: 0

        fun parse(xml: String): List<NewsItem> {
            val factory = DocumentBuilderFactory.newInstance()
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            val doc = runCatching { factory.newDocumentBuilder().parse(xml.trim().byteInputStream(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
            val items = doc.getElementsByTagName("item")
            return (0 until items.length).mapNotNull { i ->
                val item = items.item(i) as? Element ?: return@mapNotNull null
                fun tag(name: String) = item.getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()
                val source = tag("source")
                val title = tag("title").let { if (source.isNotEmpty()) it.removeSuffix(" - $source") else it }
                val link = tag("link")
                if (title.isEmpty() || link.isEmpty()) null else NewsItem(title, link, source, date(tag("pubDate")))
            }.sortedByDescending { it.published }
        }
    }
}

/** 환율 (open.er-api.com, 키 필요 없음, 하루 한 번 갱신) */
class FxClient(private val http: Http = UrlConnectionHttp()) {
    /** 1달러 = 몇 원 */
    fun usdKrw(): Pair<Double, Long> {
        val r = network("환율") { http.get("https://open.er-api.com/v6/latest/USD") }
        val o = parseJson(r.body).obj() ?: throw DataException("환율을 읽지 못했습니다.", "PARSE")
        if (o.str("result") != "success") throw DataException("환율을 받지 못했습니다.", "FX")
        val krw = o["rates"].obj()?.dbl("KRW") ?: throw DataException("원화 환율이 없습니다.", "FX")
        return krw to (o.dbl("time_last_update_unix")?.toLong() ?: 0)
    }
}
