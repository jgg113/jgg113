package io.github.jgg113.stocknote.core

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalyticsTest {
    private fun candles(closes: List<Double>, start: LocalDate = LocalDate.of(2024, 1, 1)): List<Candle> =
        closes.mapIndexed { i, c ->
            val prev = if (i == 0) c else closes[i - 1]
            Candle(start.plusDays(i.toLong()).epochKst(), prev, maxOf(prev, c) * 1.01, minOf(prev, c) * 0.99, c, 1000.0 + i)
        }

    private fun wave(n: Int, seed: Int = 1): List<Double> {
        val r = Random(seed)
        var p = 100.0
        return (0 until n).map { i -> p *= 1 + 0.02 * sin(i / 9.0) + (r.nextDouble() - 0.5) * 0.01; p }
    }

    @Test
    fun smaAndEma() {
        val x = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val s = Indicators.sma(x, 3)
        assertTrue(s[1].isNaN())
        assertEquals(2.0, s[2], 1e-9)
        assertEquals(4.0, s[4], 1e-9)
        val e = Indicators.ema(x, 3)
        assertEquals(2.0, e[2], 1e-9) // 첫 값은 단순평균
        assertEquals(3.0, e[3], 1e-9) // 0.5*4 + 0.5*2
    }

    @Test
    fun rsiIsHundredWhenOnlyRising() {
        val x = DoubleArray(30) { it + 1.0 }
        assertEquals(100.0, Indicators.rsi(x, 14)[29], 1e-9)
        val y = DoubleArray(30) { 30.0 - it }
        assertEquals(0.0, Indicators.rsi(y, 14)[29], 1e-9)
    }

    @Test
    fun rsiMatchesWilderReference() {
        // 와일더 방식(첫 평균은 14개 변화의 단순평균, 이후 (이전×13 + 오늘)/14)을 따로 계산해 비교한다.
        val closes = doubleArrayOf(44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28, 46.00, 46.03, 46.41, 46.22, 45.64)
        val changes = (1 until closes.size).map { closes[it] - closes[it - 1] }
        var avgGain = changes.take(14).sumOf { maxOf(it, 0.0) } / 14
        var avgLoss = changes.take(14).sumOf { maxOf(-it, 0.0) } / 14
        val expected = mutableListOf(100 - 100 / (1 + avgGain / avgLoss))
        for (k in 14 until changes.size) {
            avgGain = (avgGain * 13 + maxOf(changes[k], 0.0)) / 14
            avgLoss = (avgLoss * 13 + maxOf(-changes[k], 0.0)) / 14
            expected += 100 - 100 / (1 + avgGain / avgLoss)
        }
        val r = Indicators.rsi(closes, 14)
        assertTrue(r[13].isNaN())
        expected.forEachIndexed { k, e -> assertEquals(e, r[14 + k], 1e-9) }
        assertTrue(r[14] in 60.0..80.0)
    }

    @Test
    fun bollingerContainsMiddle() {
        val d = PriceData(candles(wave(100)))
        val b = d.bollinger()
        for (i in 19 until 100) assertTrue(b.lower[i] <= b.middle[i] && b.middle[i] <= b.upper[i])
    }

    @Test
    fun stochasticAndWilliamsStayInRange() {
        val d = PriceData(candles(wave(200)))
        val s = d.stochastic()
        val w = d.williams()
        for (i in 20 until 200) {
            assertTrue(s.k[i] in -0.001..100.001)
            assertTrue(w[i] in -100.001..0.001)
        }
    }

    @Test
    fun crossDetectsGoldenCross() {
        val a = doubleArrayOf(1.0, 2.0, 3.0)
        val b = doubleArrayOf(2.0, 2.0, 2.0)
        assertFalse(Cross.up(a, b, 1)) // 같아진 날은 아직 아님
        assertTrue(Cross.up(a, b, 2))
    }

    @Test
    fun parabolicSarFlipsWithTrend() {
        val up = (0 until 30).map { 100.0 + it }
        val down = (0 until 30).map { 129.0 - it * 2 }
        val d = PriceData(candles(up + down))
        val sar = d.sar()
        assertTrue(sar.rising[25])
        assertFalse(sar.rising[55])
    }

    @Test
    fun allStrategiesRunWithoutErrors() {
        val d = PriceData(candles(wave(400)))
        val report = IndicatorReport.build(d, BacktestConfig())
        assertEquals(Strategies.all.size, report.total)
        assertTrue(report.results.any { it.closedTrades > 0 })
        Strategies.all.map { it.id }.let { ids -> assertEquals(ids.size, ids.toSet().size, "전략 id 가 겹칩니다") }
    }

    @Test
    fun backtestExecutesAtNextOpenAndAppliesCost() {
        // 20일선 돌파 전략이 확실히 한 번 사고 한 번 팔도록 만든 가격
        val closes = List(25) { 100.0 } + List(10) { 110.0 } + List(10) { 90.0 }
        val cs = candles(closes)
        val d = PriceData(cs)
        val s = Strategies.find("price_ma20")!!
        val r = Backtester.run(s, d, BacktestConfig(roundTripCostPct = 0.0))
        val t = r.trades.first()
        assertEquals(26, t.entryIndex) // 25번째 봉에서 신호 → 다음 봉 시가
        assertEquals(cs[26].open, t.entryPrice, 1e-9)
        assertFalse(t.open)
        val withCost = Backtester.run(s, d, BacktestConfig(roundTripCostPct = 1.0)).trades.first()
        assertTrue(withCost.returnPct < t.returnPct)
    }

    @Test
    fun stopLossCutsTheTrade() {
        val closes = List(25) { 100.0 } + listOf(110.0, 111.0, 112.0, 80.0, 79.0, 78.0) + List(10) { 78.0 }
        val d = PriceData(candles(closes))
        val r = Backtester.run(Strategies.find("price_ma20")!!, d, BacktestConfig(roundTripCostPct = 0.0, stopLossPct = 5.0))
        val t = r.trades.first()
        assertEquals("손절", t.exitReason)
        assertTrue(t.returnPct <= -4.9 && t.returnPct > -30)
    }

    @Test
    fun forecastProducesRangesAndOpinion() {
        val cs = candles(wave(600, seed = 7))
        val d = PriceData(cs)
        val f = Forecaster.forecast(cs, report = IndicatorReport.build(d, BacktestConfig()))
        assertNotNull(f)
        val h = f.main!!
        assertTrue(h.p10Pct <= h.p25Pct && h.p25Pct <= h.medianPct && h.medianPct <= h.p75Pct && h.p75Pct <= h.p90Pct)
        assertTrue(f.score in 0..100)
        assertTrue(h.upProbPct in 0.0..100.0)
    }

    @Test
    fun forecastNeedsEnoughHistory() {
        assertEquals(null, Forecaster.forecast(candles(wave(100))))
    }

    @Test
    fun scannerFindsSignalsAndBuildsPortfolio() {
        val universe = (1..5).associate { k ->
            Asset(Market.KR, "00000$k", "테스트$k", "KOSPI") to candles(wave(400, seed = k))
        }
        val result = SignalScanner.scan(universe, Strategies.all.take(10), { BacktestConfig() }, lookback = 400, minTrades = 0)
        assertEquals(5, result.scanned)
        assertTrue(result.hits.isNotEmpty())
        assertTrue(result.portfolios.isNotEmpty())
        result.portfolios.forEach { assertEquals(it.dates.size, it.equity.size) }
    }

    @Test
    fun weeklyAggregationKeepsOhlc() {
        val cs = candles((1..14).map { it.toDouble() * 10 }, LocalDate.of(2024, 1, 1)) // 2024-01-01 은 월요일
        val w = CandleAggregator.weekly(cs)
        assertEquals(2, w.size)
        assertEquals(cs[0].open, w[0].open, 1e-9)
        assertEquals(cs[6].close, w[0].close, 1e-9)
        assertEquals(cs.take(7).maxOf { it.high }, w[0].high, 1e-9)
        assertEquals(cs.take(7).sumOf { it.volume }, w[0].volume, 1e-9)
    }

    @Test
    fun mergeReplacesSameDay() {
        val a = candles(listOf(1.0, 2.0, 3.0))
        val b = listOf(a[2].copy(close = 9.0), Candle(a[2].time + 86400, 9.0, 9.0, 9.0, 10.0, 1.0))
        val m = CandleMerge.merge(a, b)
        assertEquals(4, m.size)
        assertEquals(9.0, m[2].close, 1e-9)
    }

    @Test
    fun paperBrokerBuysAndSellsWithFees() {
        val asset = Asset(Market.KR, "005930", "삼성전자", "KOSPI")
        val fees = FeeConfig(krBuyPct = 0.1, krSellPct = 0.1, krTaxPct = 0.2)
        var acc = Account.paper("p", krw = 1_000_000.0)
        assertEquals(9.0, PaperBroker.maxBuyQty(acc, asset, 100_000.0, fees))
        acc = PaperBroker.buy(acc, asset, 5.0, 100_000.0, fees, 0)
        assertEquals(1_000_000.0 - 500_000 - 500, acc.cashKrw, 1e-6)
        val h = acc.holding(asset)!!
        assertEquals(100_100.0, h.avgPrice, 1e-6) // 수수료를 평균단가에 넣는다
        acc = PaperBroker.sell(acc, asset, 5.0, 110_000.0, fees, 1)
        assertEquals(null, acc.holding(asset))
        val realized = acc.history.last().realized
        assertEquals(550_000 - 550_000 * 0.003 - 500_500, realized, 1e-6)
        val e = runCatching { PaperBroker.buy(acc, asset, 100.0, 100_000.0, fees, 2) }.exceptionOrNull()
        assertTrue(e is TradeException)
    }

    @Test
    fun cryptoQuantityKeepsDecimals() {
        assertEquals(0.12345678, PaperBroker.normalizeQty(Market.CRYPTO, 0.123456789), 1e-12)
        assertEquals(3.0, PaperBroker.normalizeQty(Market.KR, 3.9), 1e-12)
    }

    @Test
    fun valuationCombinesCurrencies() {
        var acc = Account.paper("p", krw = 1_000_000.0, usd = 1000.0)
        val us = Asset(Market.US, "AAPL", "애플", "NAS")
        acc = PaperBroker.buy(acc, us, 2.0, 100.0, FeeConfig(usPct = 0.0), 0)
        val v = PortfolioValuation.of(acc, mapOf(us.key to 150.0), usdKrw = 1000.0)
        assertEquals(300.0, v.valueUsd, 1e-9)
        assertEquals(100_000.0, v.pnlKrw, 1e-6)
        assertEquals(1_000_000.0 + (300 + 800) * 1000.0, v.totalKrw, 1e-6)
    }

    @Test
    fun chartGameTracksResult() {
        val cs = candles(wave(300))
        val g0 = ChartGame.start("x", "KR:x", cs, visible = 120, play = 40, random = Random(3))!!
        assertEquals(119, g0.cursor)
        var g = g0.buy().next(10).sell().next(5).buy().finish()
        assertTrue(g.finished)
        assertFalse(g.holding)
        assertEquals(2, g.trades)
        val bh = (g.candles.last().close / g.candles[119].close - 1) * 100
        assertEquals(bh, g.buyHoldPct, 1e-9)
        val stats = GameStats().record(g)
        assertEquals(1, stats.games)
    }

    @Test
    fun featuredAndBreadth() {
        fun q(code: String, name: String, rate: Double, value: Double, open: Double = 100.0, prev: Double = 100.0) =
            Quote(Asset(Market.KR, code, name, "KOSPI"), 100 * (1 + rate / 100), rate, rate, open = open, prevClose = prev, value = value, volume = value / 100)
        val quotes = listOf(q("1", "가", 30.0, 1e9), q("2", "나", -5.0, 5e9), q("3", "다스팩1호", 10.0, 1e10), q("4", "라우", 3.0, 1e8, open = 105.0))
        val b = MarketStats.breadth(quotes)
        assertEquals(3, b.up); assertEquals(1, b.down); assertEquals(1, b.upperLimit)
        val gainers = MarketStats.featured(FeatureKind.GAINERS, quotes)
        assertEquals("가", gainers.first().quote.asset.name)
        assertTrue(gainers.none { it.quote.asset.name.contains("스팩") })
        assertTrue(MarketStats.isSpacOrPreferred("삼성전자우"))
        assertFalse(MarketStats.isSpacOrPreferred("우리금융지주"))
        assertEquals(listOf("가"), MarketStats.featured(FeatureKind.LIMIT_UP, quotes).map { it.quote.asset.name })
    }

    @Test
    fun screenerFiltersByPriceAndHistory() {
        val a = Asset(Market.KR, "000001", "상승", "KOSPI")
        val b = Asset(Market.KR, "000002", "하락", "KOSDAQ")
        val up = candles((0 until 100).map { 100.0 + it })
        val down = candles((0 until 100).map { 200.0 - it })
        val quotes = listOf(
            Quote(a, up.last().close, 1.0, 1.0, value = 1e10, marketCap = 1e12),
            Quote(b, down.last().close, -1.0, -1.0, value = 1e10, marketCap = 1e12),
        )
        val hist = mapOf(a.key to up, b.key to down)
        assertEquals(listOf("상승"), Screener.run(ScreenerFilter(aboveMa = 20), quotes, hist).map { it.quote.asset.name })
        assertEquals(listOf("하락"), Screener.run(ScreenerFilter(exchanges = setOf("KOSDAQ")), quotes, hist).map { it.quote.asset.name })
        assertEquals(2, Screener.run(ScreenerFilter(capMin = 5000.0), quotes, hist).size)
        assertEquals(0, Screener.run(ScreenerFilter(capMin = 50000.0), quotes, hist).size)
    }

    @Test
    fun indicatorCatalogComputesEverything() {
        val d = PriceData(candles(wave(300)))
        IndicatorType.entries.forEach { t ->
            val out = t.compute(d, t.defaults)
            assertTrue(out.series.isNotEmpty(), t.label)
            out.series.forEach { assertEquals(300, it.values.size) }
            val last = out.series.first().values.last()
            assertTrue(!last.isNaN() || t == IndicatorType.ICHIMOKU, "${t.label} 마지막 값이 비었습니다")
        }
    }

    @Test
    fun themeRanking() {
        val t = Themes.builtIn.first { it.id == "kr_semi" }
        val quotes = mapOf(
            "KR:005930" to Quote(Asset(Market.KR, "005930", "삼성전자"), 100.0, 1.0, 2.0),
            "KR:000660" to Quote(Asset(Market.KR, "000660", "SK하이닉스"), 100.0, 1.0, 4.0),
        )
        val p = ThemeStats.performance(t, quotes)!!
        assertEquals(3.0, p.avgChangePct, 1e-9)
        assertEquals("SK하이닉스", p.leader!!.asset.name)
        assertTrue(Themes.builtIn.map { it.id }.toSet().size == Themes.builtIn.size)
        assertTrue(abs(UsStocks.list.size - UsStocks.list.map { it.code }.toSet().size) == 0)
    }
}
