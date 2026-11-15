package io.github.jgg113.stocknote.core

import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InsightsTest {
    /** 날마다 [drift] 만큼 오르고 [noise] 만큼 흔들리는 일봉 */
    private fun series(days: Int, drift: Double, noise: Double, seed: Int, start: LocalDate = LocalDate.of(2025, 1, 1)): List<Candle> {
        val rnd = Random(seed)
        var p = 10_000.0
        return (0 until days).map { i ->
            val r = drift + noise * (rnd.nextDouble() - 0.5) * 2
            val open = p
            p *= 1 + r
            Candle(start.plusDays(i.toLong()).epochKst(), open, maxOf(open, p) * 1.002, minOf(open, p) * 0.998, p, 1000.0, 1000.0 * p)
        }
    }

    private val asset = Asset(Market.KR, "000001", "시험종목", "KOSPI")

    @Test
    fun trendAndSafetyScoresFollowThePriceHistory() {
        val up = StockScorer.trend(series(300, 0.002, 0.01, 1))!!
        val down = StockScorer.trend(series(300, -0.002, 0.01, 1))!!
        assertTrue(up.score > 70 && down.score < 30, "오르는 종목 ${up.score}, 내리는 종목 ${down.score}")
        assertTrue(up.reasons.any { it.positive && it.text.contains("정배열") })
        assertTrue(down.reasons.any { !it.positive && it.text.contains("역배열") })
        val calm = StockScorer.safety(series(300, 0.0, 0.005, 2), Market.KR)!!
        val wild = StockScorer.safety(series(300, 0.0, 0.06, 2), Market.KR)!!
        assertTrue(calm.score > wild.score + 30, "잔잔한 종목 ${calm.score}, 거친 종목 ${wild.score}")
        assertNull(StockScorer.trend(series(30, 0.001, 0.01, 1)))
    }

    @Test
    fun valueScoreUsesFinancials() {
        fun fin(net: Double, equity: Double, liabilities: Double, rev: Double, prevRev: Double) = Financials(
            "회사", listOf(FinancialPeriod("2024", prevRev, 100.0, 80.0, 1000.0, 400.0, 600.0), FinancialPeriod("2025", rev, 150.0, net, equity + liabilities, liabilities, equity)), null, true, 2025,
        )
        val q = Quote(asset, 10_000.0, 0.0, 0.0, marketCap = 1000.0)
        val good = StockScorer.value(q, fin(net = 150.0, equity = 900.0, liabilities = 300.0, rev = 1300.0, prevRev = 1000.0))!!
        val bad = StockScorer.value(q, fin(net = -50.0, equity = 100.0, liabilities = 900.0, rev = 700.0, prevRev = 1000.0))!!
        assertTrue(good.score > 70 && bad.score < 35, "좋은 회사 ${good.score}, 나쁜 회사 ${bad.score}")
        assertTrue(bad.reasons.any { it.text.contains("적자") })
        assertNull(StockScorer.value(null, null))
    }

    @Test
    fun flowScoreAndCombine() {
        val day = LocalDate.of(2026, 11, 1)
        fun flows(f: Double, o: Double) = (0 until 20).map { InvestorFlow(day.plusDays(it.toLong()).epochKst(), 50_000.0, -(f + o), f, o) }
        val buy = StockScorer.flow(flows(1000.0, 500.0))!!
        val sell = StockScorer.flow(flows(-1000.0, -500.0))!!
        assertEquals(100, buy.score)
        assertEquals(0, sell.score)
        assertTrue(buy.reasons.first().text.contains("외국인") && buy.reasons.first().text.contains("순매수"))
        val total = StockScorer.combine(listOf(StockScorer.trend(series(300, 0.002, 0.01, 1)), buy, null))!!
        assertTrue(total.total > 70 && total.strengths.isNotEmpty())
        assertEquals(2, total.axes.size)
        // 축이 하나뿐이면 종합 점수를 내지 않는다.
        assertNull(StockScorer.combine(listOf(buy)))
    }

    @Test
    fun marketMoodReadsBreadthAndIndex() {
        fun quotes(upShare: Double) = (0 until 100).map { Quote(asset.copy(code = "%06d".format(it)), 1000.0, 0.0, if (it < upShare * 100) 2.0 else -2.0) }
        val greedy = MarketMoodCalc.of(quotes(0.9), series(300, 0.002, 0.005, 3))!!
        val fearful = MarketMoodCalc.of(quotes(0.1), series(300, -0.002, 0.005, 3))!!
        assertTrue(greedy.score > 65 && fearful.score < 35, "탐욕 ${greedy.score}, 공포 ${fearful.score}")
        assertEquals("상승 90 · 하락 10", greedy.parts.first().text)
        assertNull(MarketMoodCalc.of(quotes(0.5).take(5)))
    }

    @Test
    fun treemapFillsTheBoxInProportion() {
        val w = listOf(50.0, 30.0, 10.0, 6.0, 3.0, 1.0, 0.0)
        val rects = Treemap.layout(w, 300f, 200f)
        assertEquals(6, rects.size) // 0 은 그리지 않는다
        val total = 300f * 200f
        for (r in rects) {
            assertEquals(w[r.index] / 100.0 * total, (r.w * r.h).toDouble(), total * 0.001)
            assertTrue(r.x >= -0.01f && r.y >= -0.01f && r.x + r.w <= 300.01f && r.y + r.h <= 200.01f)
        }
        // 서로 겹치지 않는다.
        for (a in rects) for (b in rects) if (a.index < b.index) {
            val overlapW = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
            val overlapH = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
            assertTrue(overlapW <= 0.01f || overlapH <= 0.01f, "$a 와 $b 가 겹친다")
        }
        // 가장 큰 칸이 지나치게 길쭉하지 않다.
        val big = rects.first { it.index == 0 }
        assertTrue(maxOf(big.w / big.h, big.h / big.w) < 3f)
    }

    @Test
    fun portfolioDiagnosisSeesConcentrationAndCorrelation() {
        val a = series(260, 0.0005, 0.02, 11)
        val b = series(260, 0.0005, 0.02, 22)
        fun item(name: String, value: Double, c: List<Candle>) = PortfolioItem(asset.copy(code = name, name = name), value, c)
        val single = PortfolioDoctor.diagnose(listOf(item("A", 100.0, a)))!!
        assertTrue(single.score <= 60 && single.findings.first().text.contains("하나뿐"))
        assertTrue(single.avgCorrelation.isNaN())
        // 똑같이 움직이는 두 종목은 나눠 담은 효과가 없다.
        val twins = PortfolioDoctor.diagnose(listOf(item("A", 50.0, a), item("A2", 50.0, a)))!!
        val mixed = PortfolioDoctor.diagnose(listOf(item("A", 50.0, a), item("B", 50.0, b)))!!
        assertEquals(1.0, twins.avgCorrelation, 1e-9)
        assertTrue(abs(mixed.avgCorrelation) < 0.3)
        assertTrue(mixed.volatilityPct < twins.volatilityPct * 0.85, "섞은 쪽 ${mixed.volatilityPct}, 쌍둥이 ${twins.volatilityPct}")
        assertTrue(mixed.score > twins.score)
        assertEquals(2.0, mixed.effectiveCount, 1e-9)
        assertEquals(100.0, mixed.rows.sumOf { it.riskSharePct }, 1e-6)
        assertEquals(100.0, mixed.rows.sumOf { it.balancedWeightPct }, 1e-6)
        // 한 종목에 90% 를 몰면 경고가 나온다.
        val skewed = PortfolioDoctor.diagnose(listOf(item("A", 90.0, a), item("B", 10.0, b)), cashKrw = 0.0)!!
        assertTrue(skewed.findings.any { it.level == 2 && it.text.contains("90%") })
        assertNull(PortfolioDoctor.diagnose(emptyList()))
    }

    @Test
    fun newsSentimentCountsWords() {
        fun n(t: String) = NewsItem(t, "", "")
        assertEquals(1, NewsSentiment.judge("삼성전자, 3분기 호실적에 신고가 돌파"))
        assertEquals(-1, NewsSentiment.judge("OO바이오 횡령 혐의로 거래정지… 주가 급락"))
        assertEquals(0, NewsSentiment.judge("OO전자 주주총회 개최"))
        val r = NewsSentiment.of(listOf(n("수주 확대 기대"), n("목표가 상향"), n("실적 부진 우려"), n("주주총회 개최")))!!
        assertEquals(2, r.positive); assertEquals(1, r.negative); assertEquals(1, r.neutral)
        assertEquals(25, r.score)
        assertEquals("다소 긍정적", r.label)
        assertNull(NewsSentiment.of(emptyList()))
    }

    @Test
    fun kimchiPremiumAndGlobalPriceParsers() {
        val okx = GlobalCoinClient.parseOkx("""{"code":"0","data":[{"instId":"BTC-USDT","last":"65567.5"},{"instId":"ETH-USDT","last":"3000"},{"instId":"BTC-EUR","last":"1"}]}""")
        assertEquals(mapOf("BTC" to 65567.5, "ETH" to 3000.0), okx)
        val bin = GlobalCoinClient.parseBinance("""[{"symbol":"BTCUSDT","price":"65500.00"},{"symbol":"ETHBTC","price":"0.04"}]""")
        assertEquals(mapOf("BTC" to 65500.0), bin)
        val upbit = listOf(
            Quote(Asset(Market.CRYPTO, "KRW-BTC", "비트코인"), 91_000_000.0, 0.0, 0.0),
            Quote(Asset(Market.CRYPTO, "KRW-ETH", "이더리움"), 4_050_000.0, 0.0, 0.0),
            Quote(Asset(Market.CRYPTO, "KRW-XYZ", "다른코인"), 100.0, 0.0, 0.0),
        )
        val p = Kimchi.premiums(upbit, okx + ("XYZ" to 5.0), 1350.0)
        assertEquals(2, p.size) // 이름만 같은 다른 코인(차이가 터무니없는 것)은 뺀다
        assertEquals((91_000_000.0 / (65567.5 * 1350) - 1) * 100, p.first { it.asset.code == "KRW-BTC" }.premiumPct, 1e-9)
        assertEquals(0.0, p.first { it.asset.code == "KRW-ETH" }.premiumPct, 1e-9)
        assertTrue(Kimchi.premiums(upbit, okx, 0.0).isEmpty())
        val fg = FearGreedClient.parse("""{"name":"Fear and Greed Index","data":[{"value":"10","value_classification":"Extreme Fear","timestamp":"1794700800"},{"value":"25","timestamp":"1794614400"}]}""")!!
        assertEquals(10, fg.value); assertEquals("극단적 공포", fg.label); assertEquals(listOf(25, 10), fg.history)
    }

    private fun obj(json: String) = parseJson(json) as JsonObject

    @Test
    fun kisFlowAndOpinionParsers() {
        val f = KisClient.parseInvestorFlow(obj("""{"stck_bsop_date":"20261113","stck_clpr":"98700","prsn_ntby_qty":"-1200000","frgn_ntby_qty":"800000","orgn_ntby_qty":"350000"}"""))!!
        assertEquals(LocalDate.of(2026, 11, 13).epochKst(), f.date)
        assertEquals(800000.0, f.foreign); assertEquals(350000.0, f.institution); assertEquals(-1200000.0, f.individual)
        // 장중에는 오늘 줄이 비어 온다.
        assertNull(KisClient.parseInvestorFlow(obj("""{"stck_bsop_date":"20261114","stck_clpr":"99000","prsn_ntby_qty":"","frgn_ntby_qty":"","orgn_ntby_qty":""}""")))
        val o = KisClient.parseAnalystOpinion(obj("""{"stck_bsop_date":"20261110","invt_opnn":"매수","rgbf_invt_opnn":"중립","hts_goal_prc":"120000","mbcr_name":"OO증권"}"""))!!
        assertEquals("매수", o.opinion); assertEquals("중립", o.previous); assertEquals(120000.0, o.target); assertEquals("OO증권", o.broker)
        // 한국투자증권 서버 주소·tr_id 로 물어본다.
        val http = ScriptedHttp { url, _, _ ->
            HttpResponse(200, (if (url.contains("tokenP")) """{"access_token":"t","expires_in":86400}""" else """{"rt_cd":"0","output":[{"stck_bsop_date":"20261113","stck_clpr":"98700","prsn_ntby_qty":"1","frgn_ntby_qty":"2","orgn_ntby_qty":"3"}]}""").toByteArray())
        }
        assertEquals(1, KisClient("k", "s", false, MemoryTokenStore(), http).investorFlows("005930").size)
        val call = http.calls.last()
        assertTrue(call.url.contains("/uapi/domestic-stock/v1/quotations/inquire-investor?") && call.url.contains("FID_INPUT_ISCD=005930"))
        assertEquals("FHKST01010900", call.headers["tr_id"])
    }

    @Test
    fun dartOwnershipAndDividendParsers() {
        val major = DartClient.parseOwnership(obj("""{"rcept_no":"20261101000123","rcept_dt":"2026-11-01","repror":"국민연금공단","stkqy":"12,345,678","stkqy_irds":"-234,567","stkrt":"7.12","stkrt_irds":"-0.14","report_resn":"단순  추가취득/처분"}"""), major = true)!!
        assertEquals("국민연금공단", major.reporter); assertEquals(-234567.0, major.change); assertEquals(7.12, major.ratePct)
        assertEquals(LocalDate.of(2026, 11, 1).epochKst(), major.date)
        assertEquals("단순 추가취득/처분", major.reason)
        val insider = DartClient.parseOwnership(obj("""{"rcept_no":"1","rcept_dt":"20261020","repror":"홍길동","isu_exctv_ofcps":"대표이사","isu_main_shrholdr":"-","sp_stock_lmp_cnt":"50,000","sp_stock_lmp_irds_cnt":"10,000","sp_stock_lmp_rate":"0.05"}"""), major = false)!!
        assertEquals("대표이사", insider.role); assertEquals(10000.0, insider.change)
        val rows = listOf(
            obj("""{"se":"주당 현금배당금(원)","stock_knd":"우선주","thstrm":"1,500","frmtrm":"1,400","lwfr":"1,300"}"""),
            obj("""{"se":"주당 현금배당금(원)","stock_knd":"보통주","thstrm":"1,444","frmtrm":"1,416","lwfr":"1,354"}"""),
            obj("""{"se":"현금배당수익률(%)","stock_knd":"보통주","thstrm":"2.70","frmtrm":"1.90"}"""),
            obj("""{"se":"(연결)현금배당성향(%)","thstrm":"29.20","frmtrm":"67.80"}"""),
        )
        val d = DartClient.parseDividend(rows, 2025)!!
        assertEquals(1444.0, d.perShare); assertEquals(2.7, d.yieldPct); assertEquals(29.2, d.payoutPct); assertEquals(1416.0, d.prevPerShare); assertEquals(1354.0, d.prev2PerShare)
        assertNull(DartClient.parseDividend(listOf(obj("""{"se":"주당 현금배당금(원)","thstrm":"-"}""")), 2025))
    }

    @Test
    fun briefPromptsCarryOnlyComputedNumbers() {
        val mood = MarketMood(72, "탐욕", listOf(MoodPart("오른 종목 비율", 80, "상승 80 · 하락 20")))
        val text = BriefPrompt.market(Market.CRYPTO, mood, Breadth(80, 20, 0, 0, 0), emptyList(), emptyList(), emptyList(), listOf(NewsItem("비트코인 반등", "", "")), FearGreed(60, "탐욕", 0, emptyList()), 1.25)
        assertTrue(text.contains("72/100") && text.contains("+1.25%") && text.contains("비트코인 반등") && text.contains("60/100"))
        val a = series(260, 0.0005, 0.02, 11)
        val d = PortfolioDoctor.diagnose(listOf(PortfolioItem(asset, 100.0, a), PortfolioItem(asset.copy(code = "B", name = "둘째"), 50.0, series(260, 0.0, 0.03, 5))), 10.0)!!
        val p = BriefPrompt.portfolio("모의계좌", d, 160.0, 3.2)
        assertTrue(p.contains("시험종목") && p.contains("둘째") && p.contains("${d.score}/100"))
        assertNotNull(sin(1.0)) // (쓰지 않는 import 경고 방지)
    }
}
