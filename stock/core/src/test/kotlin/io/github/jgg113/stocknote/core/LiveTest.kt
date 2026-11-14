package io.github.jgg113.stocknote.core

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** 실제 인터넷으로 키 없는 출처를 확인한다. LIVE=1 일 때만 돈다 (CI 에서는 건너뛴다). */
class LiveTest {
    private fun live() = assumeTrue(System.getenv("LIVE") == "1")

    /** 실제 Kronos 가중치로 구현이 맞는지 점검한다: KRONOS_DIR 에 가중치 두 파일이 있어야 한다. */
    @Test
    fun kronosSanity() {
        live()
        val dir = java.io.File(System.getenv("KRONOS_DIR") ?: return)
        assumeTrue(KronosModel.installed(dir))
        val model = KronosModel.load(dir)
        println("Kronos 층 ${model.layers}, 차원 ${model.dModel}")
        val md = MarketData(DataKeys(), MemoryCache())
        for (code in listOf("KRW-BTC", "KRW-ETH", "KRW-XRP")) {
            val asset = Asset(Market.CRYPTO, code, code, "UPBIT")
            val candles = md.daily(asset, 600, live = false)
            val window = candles.takeLast(276).dropLast(20)
            val (rmse, nll1, nll2) = model.diagnose(window)
            println("$code 복원 RMSE(정규화) ${"%.3f".format(rmse)}, 다음 토큰 NLL s1 ${"%.2f".format(nll1)} s2 ${"%.2f".format(nll2)} (무작위면 ${"%.2f".format(kotlin.math.ln(1024.0))})")
            assertTrue(rmse < 0.5, "토크나이저 복원 오차가 큽니다: $rmse")
            assertTrue(nll1 < 6.0, "다음 토큰 예측이 무작위 수준입니다: $nll1")
            val r = model.forecast(window, 20, KronosModel.futureDays(window.last().time, 20, Market.CRYPTO), context = 256, samples = 16)
            val actual = candles.last().close / window.last().close - 1
            println("   20일 예측 ${"%.1f".format(r.seconds)}초: 상승확률 ${"%.0f".format(r.upProb * 100)}%, 예상 ${Fmt.signedRate(r.expectedReturn * 100)}, 80% 범위 ${Fmt.signedRate((r.closeQuantile(19, 0.1) / r.lastClose - 1) * 100)} ~ ${Fmt.signedRate((r.closeQuantile(19, 0.9) / r.lastClose - 1) * 100)}, 실제 ${Fmt.signedRate(actual * 100)}")
            println("   첫 예상 봉 ${r.candles.first().let { "시 ${Fmt.number(it.open)} 고 ${Fmt.number(it.high)} 저 ${Fmt.number(it.low)} 종 ${Fmt.number(it.close)}" }} (직전 종가 ${Fmt.number(window.last().close)})")
            assertTrue(kotlin.math.abs(r.candles.first().close / window.last().close - 1) < 0.25, "첫 예상 봉이 직전 종가에서 너무 멉니다")
        }
    }

    /**
     * Kronos 를 모델 공개(2025-09) 이후 구간에서만 검증한다 (학습에 쓰였을 수 있는 과거는 뺀다).
     * 결과를 KRONOS_OUT 파일(CSV)로 남긴다.
     */
    @Test
    fun kronosValidation() {
        live()
        val dir = java.io.File(System.getenv("KRONOS_DIR") ?: return)
        assumeTrue(KronosModel.installed(dir))
        val model = KronosModel.load(dir)
        val md = MarketData(DataKeys(), MemoryCache())
        val pool = md.dailyMany(md.universe(Market.CRYPTO, 24), 1500).filter { it.value.size >= 500 }
        val from = java.time.LocalDate.of(2025, 9, 1).epochKst()
        val horizon = 20; val stride = 10
        data class Job(val asset: Asset, val candles: List<Candle>, val end: Int)
        val jobs = pool.flatMap { (asset, c) ->
            (300 until c.size - horizon).filter { c[it].time >= from && (c.size - 1 - horizon - it) % stride == 0 }.map { Job(asset, c, it) }
        }
        println("검증 대상 ${pool.size}종목, ${jobs.size}건")
        val t0 = System.currentTimeMillis()
        val rows = jobs.parallelStream().map { j ->
            val window = j.candles.subList(0, j.end + 1)
            val r = model.forecast(window, horizon, KronosModel.futureDays(window.last().time, horizon, Market.CRYPTO), context = 256, samples = 16, threads = 1, seed = j.end.toLong())
            val actual = j.candles[j.end + horizon].close / j.candles[j.end].close - 1
            listOf(j.asset.code, Fmt.date(j.candles[j.end].time), r.upProb, r.expectedReturn, r.medianReturn, actual)
        }.toList()
        println("걸린 시간 ${(System.currentTimeMillis() - t0) / 1000}초")
        System.getenv("KRONOS_OUT")?.let { out -> java.io.File(out).writeText("code,date,upProb,expected,median,actual\n" + rows.joinToString("\n") { it.joinToString(",") }) }
        val prob = rows.map { it[2] as Double }.toDoubleArray()
        val exp = rows.map { it[3] as Double }.toDoubleArray()
        val act = rows.map { it[5] as Double }.toDoubleArray()
        val up = BooleanArray(act.size) { act[it] > 0 }
        val eff = rows.size.toDouble() * stride / horizon
        val upFrac = up.count { it }.toDouble() / up.size
        val n1 = eff * upFrac; val n2 = eff * (1 - upFrac)
        val se = kotlin.math.sqrt((n1 + n2 + 1) / (12 * n1 * n2))
        println("Kronos 검증: ${rows.size}건 (겹침 제외 ${eff.toInt()}건), 실제 상승 비율 ${"%.1f".format(upFrac * 100)}%, 평균 예측 상승확률 ${"%.1f".format(prob.average() * 100)}%")
        println("  상승확률 AUC ${"%.3f".format(Metrics.auc(prob, up))} ±${"%.3f".format(se)}, 예상수익률 AUC ${"%.3f".format(Metrics.auc(exp, up))}, 순위상관 ${"%.3f".format(Metrics.rankIc(exp, act))}, 방향 적중 ${"%.3f".format(Metrics.accuracy(prob, up))}")
        // 날짜별 종목 간 순위 상관 (같은 날 어떤 코인이 더 오를지 맞히는 힘)
        val byDate = rows.groupBy { it[1] as String }.values.filter { it.size >= 8 }
        val ics = byDate.map { g -> Metrics.rankIc(g.map { it[3] as Double }.toDoubleArray(), g.map { it[5] as Double }.toDoubleArray()) }
        println("  날짜별 종목 순위상관 평균 ${"%.3f".format(ics.average())} (${ics.size}일, 표준오차 ${"%.3f".format(kotlin.math.sqrt(ics.map { (it - ics.average()).let { v -> v * v } }.average() / ics.size))})")
    }

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
            println("${h.label} (${h.period}) 학습 ${(t1 - t0) / 1000.0}초, 학습표본 ${ens.trainSamples}, 검증 ${ens.validSamples} (겹치지 않는 구간 ${ens.effectiveSamples}개), 신뢰도 ${ens.reliability.label} z=${"%.2f".format(ens.bestZ)}")
            ens.reports.forEach { println("   ${it.name}: AUC ${"%.3f".format(it.auc)} ±${"%.3f".format(it.aucSe)} 적중 ${"%.3f".format(it.accuracy)} IC ${"%.3f".format(it.ic)} 가중 ${"%.2f".format(it.weight)}") }
            println("   시장 대비 초과수익 분류기: AUC ${"%.3f".format(ens.relAuc)} ±${"%.3f".format(ens.relSe)} (z=${"%.2f".format(ens.relZ)})")
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
