package io.github.jgg113.stocknote.core

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** 실제 인터넷으로 키 없는 출처를 확인한다. LIVE=1 일 때만 돈다 (CI 에서는 건너뛴다). */
class LiveTest {
    private fun live() = assumeTrue(System.getenv("LIVE") == "1")

    @Test
    fun upbitEndToEnd() {
        live()
        val md = MarketData(DataKeys(), MemoryCache())
        val btc = md.search("비트코인", setOf(Market.CRYPTO)).first()
        val candles = md.daily(btc, 500)
        println("BTC 일봉 ${candles.size}개, 마지막 ${Fmt.date(candles.last().time)} ${Fmt.number(candles.last().close)}")
        assertTrue(candles.size >= 400)
        val report = IndicatorReport.build(PriceData(candles), BacktestConfig(roundTripCostPct = 0.1))
        report.byScore.take(5).forEach { println("  ${it.strategy.name}: ${Fmt.signedRate(it.totalReturnPct)} 승률 ${Fmt.decimal(it.winRatePct)}% ${it.closedTrades}회") }
        val f = Forecaster.forecast(candles, report = report)!!
        println("예측 ${f.score}점 ${f.opinion.label}, 20일 중앙값 ${Fmt.signedRate(f.main!!.medianPct)} 적중률 ${Fmt.decimal(f.main!!.hitRatePct)}%")
        val minutes = md.intraday(btc, Timeframe.MIN15)
        println("15분봉 ${minutes.size}개")
        assertTrue(minutes.size > 100)
        val uni = md.universe(Market.CRYPTO, 8)
        val scan = SignalScanner.scan(md.dailyMany(uni, 300), Strategies.all, { BacktestConfig(roundTripCostPct = 0.1) }, lookback = 3)
        println("신호 ${scan.hits.size}건: " + scan.hits.take(5).joinToString { "${it.asset.name}/${it.strategy.name}" })
        val news = md.news("비트코인")
        println("뉴스 ${news.size}건: ${news.firstOrNull()?.title}")
        println("환율 ${md.usdKrw()}")
        val featured = MarketStats.featured(FeatureKind.GAINERS, md.marketQuotes(Market.CRYPTO), limit = 3)
        println("급등 코인: " + featured.joinToString { "${it.quote.asset.name} ${it.note}" })
    }
}
