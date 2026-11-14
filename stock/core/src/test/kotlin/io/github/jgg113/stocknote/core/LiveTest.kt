package io.github.jgg113.stocknote.core

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** 실제 인터넷으로 키 없는 출처를 확인한다. LIVE=1 일 때만 돈다 (CI 에서는 건너뛴다). */
class LiveTest {
    private fun live() = assumeTrue(System.getenv("LIVE") == "1")

    @Test
    fun aiOnRealCoins() {
        live()
        val md = MarketData(DataKeys(), MemoryCache())
        val uni = md.universe(Market.CRYPTO, 15)
        val pool = md.dailyMany(uni, 1500).map { it.key to it.value }
        println("학습 종목 ${pool.size}개, 평균 ${pool.map { it.second.size }.average().toInt()}봉")
        for (h in AiHorizon.entries) {
            val t0 = System.currentTimeMillis()
            val ens = AiForecaster.train(pool, h)
            val t1 = System.currentTimeMillis()
            if (ens == null) { println("${h.label}: 자료 부족"); continue }
            println("${h.label} (${h.period}) 학습 ${(t1 - t0) / 1000.0}초, 학습표본 ${ens.trainSamples}, 검증 ${ens.validSamples} (겹침 제외 ${ens.effectiveSamples}), 신뢰도 ${ens.reliability.label} z=${"%.2f".format(ens.bestZ)}")
            ens.reports.forEach { println("   ${it.name}: AUC ${"%.3f".format(it.auc)} ±${"%.3f".format(it.aucSe)} 적중 ${"%.3f".format(it.accuracy)} IC ${"%.3f".format(it.ic)} 가중 ${"%.2f".format(it.weight)}") }
            val btc = pool.first { it.first.code == "KRW-BTC" }
            val f = AiForecaster.predict(ens, btc.first, btc.second)!!
            println("   BTC: ${f.opinion.label} ${"%.1f".format(f.score)}점, 상승확률 ${"%.1f".format(f.upProb * 100)}% (평소 ${"%.1f".format(f.baseUpProb * 100)}%), 예상 ${Fmt.signedRate(f.expectedReturn * 100)}, 목표 ${Fmt.signedRate(f.pct(f.target1))}/${Fmt.signedRate(f.pct(f.target2))}, 손절 ${Fmt.signedRate(f.pct(f.stop))}, 주요 특징 ${f.topFeatures}")
            f.matches.take(3).forEach { println("     패턴 ${it.asset.name} ${Fmt.date(it.candles.first().time)}~ 비슷함 ${"%.0f".format(it.similarity * 100)}% 이후 ${Fmt.signedRate(it.forwardReturn * 100)}") }
            println("   예측 ${(System.currentTimeMillis() - t1) / 1000.0}초, 순위 1위 ${AiForecaster.rank(ens, pool).firstOrNull()?.asset?.name}")
        }
    }

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
