package io.github.jgg113.stocknote.core

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 주소별로 정해 둔 응답을 돌려주는 가짜 HTTP */
class FakeHttp(private val routes: List<Pair<String, String>>) : Http {
    val calls = mutableListOf<String>()

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        calls += url
        val body = routes.firstOrNull { url.contains(it.first) }?.second ?: return HttpResponse(404, ByteArray(0))
        return HttpResponse(200, body.toByteArray())
    }

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse = get(url, headers)
}

class DataParsersTest {
    @Test
    fun upbitTickerAndCandles() {
        val ticker = """[{"market":"KRW-BTC","trade_price":92121000.0,"prev_closing_price":93226000.0,"signed_change_price":-1105000.0,"signed_change_rate":-0.0118529166,"opening_price":93292000.0,"high_price":93499000.0,"low_price":91111000.0,"acc_trade_price_24h":153864804069.2,"acc_trade_volume_24h":1650.1,"highest_52_week_price":144888000.0,"lowest_52_week_price":88342000.0,"trade_timestamp":1794637247588}]"""
        val q = UpbitClient.parseTickers(ticker, mapOf("KRW-BTC" to "비트코인")).single()
        assertEquals("비트코인", q.asset.name)
        assertEquals(-1.18529166, q.changeRate, 1e-6)
        assertEquals(144888000.0, q.high52)
        assertEquals(1794637247, q.time)

        val candles = """[{"market":"KRW-BTC","candle_date_time_utc":"2026-11-14T00:00:00","candle_date_time_kst":"2026-11-14T09:00:00","opening_price":1.0,"high_price":3.0,"low_price":0.5,"trade_price":2.0,"candle_acc_trade_volume":10.0,"candle_acc_trade_price":20.0},
            {"market":"KRW-BTC","candle_date_time_utc":"2026-11-13T00:00:00","candle_date_time_kst":"2026-11-13T09:00:00","opening_price":1.0,"high_price":3.0,"low_price":0.5,"trade_price":1.5,"candle_acc_trade_volume":10.0,"candle_acc_trade_price":20.0}]"""
        val page = UpbitClient.parseCandles(candles, daily = true)
        assertEquals(LocalDate.of(2026, 11, 14).epochKst(), page.candles.first().time)
        assertEquals("2026-11-13T00:00:00", page.oldestUtc)
        val minute = UpbitClient.parseCandles(candles, daily = false)
        assertEquals(LocalDateTime.of(2026, 11, 14, 9, 0).epochKst(), minute.candles.first().time)
    }

    @Test
    fun publicDataParsesItemsAndErrors() {
        val ok = """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"numOfRows":2,"pageNo":1,"totalCount":2,"items":{"item":[
            {"basDt":"20261113","srtnCd":"005930","isinCd":"KR7005930003","itmsNm":"삼성전자","mrktCtg":"KOSPI","clpr":"72400","vs":"-600","fltRt":"-.82","mkp":"73000","hipr":"73200","lopr":"72100","trqu":"12345678","trPrc":"893000000000","lstgStCnt":"5969782550","mrktTotAmt":"432212256620000"},
            {"basDt":"20261113","srtnCd":"000660","itmsNm":"SK하이닉스","mrktCtg":"KOSPI","clpr":"200000","vs":"5000","fltRt":"2.56","mkp":"195000","hipr":"201000","lopr":"194000","trqu":"100","trPrc":"20000000","mrktTotAmt":"1"}]}}}}"""
        val page = PublicDataClient.parse(200, ok)
        assertEquals(2, page.totalCount)
        val q = PublicDataClient.toQuote(page.items[0])!!
        assertEquals("삼성전자", q.asset.name)
        assertEquals("KOSPI", q.asset.exchange)
        assertEquals(73000.0, q.prevClose, 1e-9)
        assertEquals(-0.82, q.changeRate, 1e-9)
        assertEquals(LocalDate.of(2026, 11, 13).epochKst(), q.time)
        val c = PublicDataClient.toCandle(page.items[1])!!
        assertEquals(195000.0, c.open, 1e-9)

        val single = """{"response":{"header":{"resultCode":"00"},"body":{"totalCount":1,"items":{"item":{"basDt":"20261113","srtnCd":"005930","clpr":"1"}}}}}"""
        assertEquals(1, PublicDataClient.parse(200, single).items.size)

        val err = """{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE ERROR","returnAuthMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR","returnReasonCode":"30"}}}"""
        val e = assertFailsWith<DataException> { PublicDataClient.parse(200, err) }
        assertEquals("SERVICE_KEY_IS_NOT_REGISTERED_ERROR", e.code)
        val xml = "<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg><returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg></cmmMsgHeader></OpenAPI_ServiceResponse>"
        assertTrue(assertFailsWith<DataException> { PublicDataClient.parse(200, xml) }.message!!.contains("한도"))
        assertEquals("AB+C=", ServiceKey.normalize(" AB%2BC%3D "))
        assertEquals("AB+C=", ServiceKey.normalize("AB+C="))
    }

    @Test
    fun kisParsers() {
        val o = parseJson("""{"stck_prpr":"72400","prdy_vrss":"600","prdy_vrss_sign":"5","prdy_ctrt":"0.82","acml_vol":"100","acml_tr_pbmn":"7240000","stck_oprc":"73000","stck_hgpr":"73500","stck_lwpr":"72000","stck_sdpr":"73000","per":"12.5","pbr":"1.20","hts_avls":"4322000","w52_hgpr":"88000","w52_lwpr":"50000","hts_kor_isnm":"삼성전자"}""").obj()!!
        val q = KisClient.parseDomesticQuote(o, Asset(Market.KR, "005930", ""))
        assertEquals(-600.0, q.change, 1e-9)
        assertEquals(-0.82, q.changeRate, 1e-9)
        assertEquals("삼성전자", q.asset.name)
        assertEquals(4322000 * 1e8, q.marketCap, 1.0)
        assertEquals(12.5, q.per)
        val d = KisClient.parseDomesticDaily(parseJson("""{"stck_bsop_date":"20261113","stck_clpr":"100","stck_oprc":"90","stck_hgpr":"110","stck_lwpr":"80","acml_vol":"5"}""").obj()!!)!!
        assertEquals(LocalDate.of(2026, 11, 13).epochKst(), d.time)
        val m = KisClient.parseDomesticMinute(parseJson("""{"stck_bsop_date":"20261113","stck_cntg_hour":"093000","stck_prpr":"100","stck_oprc":"99","stck_hgpr":"101","stck_lwpr":"98","cntg_vol":"7"}""").obj()!!)!!
        assertEquals(LocalDateTime.of(2026, 11, 13, 9, 30).epochKst(), m.time)
        assertNull(KisClient.parseOverseasPrice(parseJson("""{"last":"","base":""}""").obj(), Asset(Market.US, "X", "X")))
        val us = KisClient.parseOverseasDetail(parseJson("""{"last":"110","base":"100","open":"101","h52p":"120","perx":"30"}""").obj(), Asset(Market.US, "AAPL", "애플"))!!
        assertEquals(10.0, us.changeRate, 1e-9)
        assertEquals(120.0, us.high52)
    }

    @Test
    fun twelveDataParsers() {
        val q = TwelveDataClient.parseQuote("""{"symbol":"AAPL","name":"Apple Inc.","close":"356.4","previous_close":"354.88","change":"1.52","percent_change":"0.43","volume":"100","is_market_open":false,"fifty_two_week":{"low":"243.42","high":"360.83"}}""", Asset(Market.US, "AAPL", ""))!!
        assertEquals("Apple Inc.", q.asset.name)
        assertEquals(360.83, q.high52)
        val s = TwelveDataClient.parseSeries("""{"values":[{"datetime":"2026-11-13","open":"1","high":"2","low":"0.5","close":"1.5","volume":"10"},{"datetime":"2026-11-12","open":"1","high":"2","low":"0.5","close":"1.2","volume":"10"}],"status":"ok"}""")
        assertEquals(2, s.size)
        assertTrue(s[0].time < s[1].time)
    }

    @Test
    fun dartParsers() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<result>
<list>
<corp_code>00126380</corp_code>
<corp_name>삼성전자</corp_name>
<stock_code>005930</stock_code>
<modify_date>20250101</modify_date>
</list>
<list>
<corp_code>00999999</corp_code>
<corp_name>비상장회사</corp_name>
<stock_code> </stock_code>
</list>
</result>"""
        val corps = DartClient.parseCorpXml(BufferedReader(StringReader(xml)))
        assertEquals(listOf(DartCorp("00126380", "삼성전자", "005930")), corps)

        val zipped = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("CORPCODE.xml")); z.write(xml.toByteArray()); z.closeEntry() }
        }.toByteArray()
        assertEquals(1, DartClient.parseCorpZip(zipped).size)

        val rows = parseJson("""{"list":[
            {"fs_div":"CFS","sj_div":"IS","account_nm":"매출액","thstrm_amount":"300,000","frmtrm_amount":"200,000","bfefrmtrm_amount":"100,000","thstrm_add_amount":"900,000"},
            {"fs_div":"CFS","sj_div":"IS","account_nm":"영업이익","thstrm_amount":"30,000","frmtrm_amount":"-10,000"},
            {"fs_div":"CFS","sj_div":"IS","account_nm":"당기순이익","thstrm_amount":"20,000"},
            {"fs_div":"CFS","sj_div":"BS","account_nm":"부채총계","thstrm_amount":"50,000"},
            {"fs_div":"CFS","sj_div":"BS","account_nm":"자본총계","thstrm_amount":"100,000"},
            {"fs_div":"OFS","sj_div":"IS","account_nm":"매출액","thstrm_amount":"1"}
        ]}""").obj()!!["list"].asList()
        val p = DartClient.period(rows, "2025년", "thstrm")!!
        assertEquals(300000.0, p.revenue)
        assertEquals(10.0, p.operatingMargin!!, 1e-9)
        assertEquals(50.0, p.debtRatio!!, 1e-9)
        assertEquals(20.0, p.roe!!, 1e-9)
        assertEquals(-10000.0, DartClient.period(rows, "2024년", "frmtrm")!!.operatingIncome)
        assertEquals(900000.0, DartClient.period(rows, "분기", "thstrm", cumulative = true)!!.revenue)
        assertFailsWith<DataException> { DartClient.checkStatus("010", "") }
        DartClient.checkStatus("000", "정상")
    }

    @Test
    fun newsParser() {
        val xml = """<?xml version="1.0"?><rss><channel><item><title>코스피 상승 - 한국경제</title><link>https://example.com/a</link><pubDate>Sat, 14 Nov 2026 01:00:00 GMT</pubDate><source url="x">한국경제</source></item></channel></rss>"""
        val n = NewsClient.parse(xml).single()
        assertEquals("코스피 상승", n.title)
        assertTrue(n.published > 0)
    }

    @Test
    fun marketDataCachesCryptoCandles() {
        val day = { d: Int -> """{"market":"KRW-BTC","candle_date_time_utc":"2026-11-${"%02d".format(d)}T00:00:00","candle_date_time_kst":"2026-11-${"%02d".format(d)}T09:00:00","opening_price":1.0,"high_price":2.0,"low_price":0.5,"trade_price":${d}.0,"candle_acc_trade_volume":1.0}""" }
        val candles = "[" + (14 downTo 1).joinToString(",") { day(it) } + "]"
        val http = FakeHttp(
            listOf(
                "/v1/candles/days" to candles,
                "/v1/market/all" to """[{"market":"KRW-BTC","korean_name":"비트코인"}]""",
                "/v1/ticker" to """[{"market":"KRW-BTC","trade_price":15.0,"opening_price":14.0,"high_price":15.0,"low_price":13.0,"prev_closing_price":14.0,"signed_change_rate":0.07,"acc_trade_price_24h":1.0,"acc_trade_volume_24h":1.0,"trade_timestamp":1794637247588}]""",
            ),
        )
        val cache = MemoryCache()
        val md = MarketData(DataKeys(), cache, http = http, now = { LocalDateTime.of(2026, 11, 14, 15, 0) })
        val btc = Asset(Market.CRYPTO, "KRW-BTC", "비트코인", "UPBIT")
        val first = md.daily(btc, bars = 20)
        assertEquals(14, first.size)
        assertEquals(15.0, first.last().close, 1e-9) // 오늘 봉에 지금 시세를 반영
        val callsAfterFirst = http.calls.count { it.contains("/candles/") }
        md.daily(btc, bars = 20)
        assertEquals(callsAfterFirst, http.calls.count { it.contains("/candles/") }, "1분 안에는 다시 받지 않는다")
        assertNotNull(cache.list("candles_").singleOrNull())
    }

    @Test
    fun marketDataNeedsKeysForStocks() {
        val md = MarketData(DataKeys(), MemoryCache(), http = FakeHttp(emptyList()))
        val e = assertFailsWith<DataException> { md.quote(Asset(Market.KR, "005930", "삼성전자")) }
        assertEquals("NOKEY", e.code)
        assertFailsWith<DataException> { md.quote(Asset(Market.US, "AAPL", "애플")) }
    }

    @Test
    fun candleCodecRoundTrip() {
        val c = listOf(Candle(1, 1.5, 2.0, 1.0, 1.75, 100.0, 175.0), Candle(2, 1.0, 1.0, 1.0, 1.0, 0.0))
        assertEquals(c, CandleCodec.decode(CandleCodec.encode(c)))
    }

    @Test
    fun clockKnowsWeekends() {
        assertTrue(!MarketClock.isKrTradingDay(LocalDate.of(2026, 11, 14))) // 토요일
        assertEquals(LocalDate.of(2026, 11, 13), MarketClock.lastKrTradingDay(LocalDate.of(2026, 11, 15)))
        assertTrue(MarketClock.krOpen(LocalDateTime.of(2026, 11, 13, 10, 0)))
        assertTrue(!MarketClock.krOpen(LocalDateTime.of(2026, 11, 13, 16, 0)))
    }

    @Test
    fun promptContainsNumbers() {
        val asset = Asset(Market.KR, "005930", "삼성전자", "KOSPI")
        val p = StockPrompt.build(asset, Quote(asset, 72400.0, -600.0, -0.82), emptyList(), null, null, null, emptyList(), StockPrompt.Mode.SUMMARY)
        assertTrue(p.contains("72,400원"))
        assertTrue(p.contains("삼성전자"))
    }
}
