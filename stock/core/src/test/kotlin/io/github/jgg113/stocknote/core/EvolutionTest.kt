package io.github.jgg113.stocknote.core

import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvolutionTest {
    /** 추세가 있다가 꺾이기를 되풀이하는 가짜 일봉 */
    private fun series(days: Int, seed: Int): List<Candle> {
        val rnd = Random(seed)
        var p = 10_000.0
        var drift = 0.0015
        return (0 until days).map { i ->
            if (i % 180 == 0) drift = if (rnd.nextBoolean()) 0.002 else -0.0015
            val open = p * (1 + 0.004 * (rnd.nextDouble() - 0.5))
            p = open * (1 + drift + 0.02 * (rnd.nextDouble() - 0.5))
            Candle(LocalDate.of(2019, 1, 1).plusDays(i.toLong()).epochKst(), open, maxOf(open, p) * 1.005, minOf(open, p) * 0.995, p, 1000.0 + rnd.nextInt(500), 1000.0 * p)
        }
    }

    private fun pool(k: Int) = (0 until k).map { Asset(Market.CRYPTO, "KRW-T$it", "시험$it") to series(1500, it + 1) }

    @Test
    fun evolvedStrategyMatchesTheBacktester() {
        // 연구소가 센 거래 수와, 같은 조합을 앱의 백테스트로 돌린 거래 수가 같아야 한다 (같은 규칙).
        val (asset, candles) = pool(1).first()
        val lab = EvoLab(listOf(asset to candles), Market.CRYPTO)
        val g = Genome(listOf("ma5_20", "rsi50", "macd_hist"), 2, EvoFilter.ABOVE_MA200)
        val ev = EvolvedStrategy("evo_t", "t", g, Market.CRYPTO, lab.evaluate(g, EvoLab.Period.TRAIN), lab.evaluate(g, EvoLab.Period.VALID), lab.evaluate(g, EvoLab.Period.TEST), 50.0, 0.3, 10.0, 1, 0, 0, "", 0)
        val s = ev.toStrategy()
        val data = PriceData(candles)
        val bt = Backtester.run(s, data, BacktestConfig(roundTripCostPct = 0.0))
        val pos = Composite.position(data, g.members.map { Strategies.find(it)!! }, 2, EvoFilter.ABOVE_MA200)
        val runs = pos.indices.count { i -> pos[i] && (i == 0 || !pos[i - 1]) }
        assertEquals(runs, bt.trades.size)
        assertTrue(bt.trades.isNotEmpty())
        // 앱 전체에서 찾을 수 있게 등록된다.
        Strategies.register(listOf(s))
        assertEquals("evo_t", Strategies.find("evo_t")?.id)
        Strategies.register(emptyList())
    }

    @Test
    fun searchRunsSplitsPeriodsAndReportsOverfitting() {
        val lab = EvoLab(pool(6), Market.CRYPTO, seed = 3)
        assertTrue(lab.t0 < lab.t1 && lab.t1 < lab.t2 && lab.t2 < lab.tEnd)
        var calls = 0
        val res = lab.run(maxEvaluations = 3000, population = 60, threads = 2) { calls++ }
        assertTrue(calls > 3)
        assertTrue(res.evaluations >= 3000)
        assertTrue(res.simulations >= res.evaluations * 6 / 2, "${res.simulations}")
        assertTrue(res.best.isNotEmpty())
        val b = res.best.first()
        assertTrue(b.train.trades > 0 && b.valid.trades > 0)
        assertTrue(b.luckPercentile in 0.0..100.0)
        assertTrue(res.pbo.isNaN() || res.pbo in 0.0..1.0)
        // 같은 씨앗이면 같은 결과
        val again = EvoLab(pool(6), Market.CRYPTO, seed = 3).run(maxEvaluations = 3000, population = 60, threads = 2)
        assertEquals(b.genome.key, again.best.first().genome.key)
    }

    @Test
    fun pboIsHighForPureNoise() {
        // 가격이 아무 규칙 없이 움직이면, 과거에 1등이던 조합이 다음 기간에도 잘할 이유가 없다 → 과최적화 확률이 높게 나와야 한다.
        val noise = (0 until 8).map { k ->
            val rnd = Random(100 + k)
            var p = 1000.0
            Asset(Market.CRYPTO, "KRW-N$k", "N$k") to (0 until 1500).map { i ->
                val o = p; p *= kotlin.math.exp(0.03 * (rnd.nextDouble() - 0.5)) // 로그 수익의 기대값이 0인 순수 잡음
                Candle(LocalDate.of(2019, 1, 1).plusDays(i.toLong()).epochKst(), o, maxOf(o, p), minOf(o, p), p, 1000.0, 1000.0)
            }
        }
        val lab = EvoLab(noise, Market.CRYPTO, roundTripCostPct = 0.0)
        val rnd = Random(1)
        val gs = (0 until 40).map { Genome(Strategies.all.shuffled(rnd).take(3).map { it.id }, 2, EvoFilter.NONE) }
        val pbo = lab.pbo(gs)
        assertTrue(pbo >= 0.25, "noise PBO $pbo")
    }

    @Test
    fun builtInKoreanModelsLoadAndRun() {
        // 앱에 넣은 국내 AI 모델: 불러와지고, 모든 구성 전략이 있고, 실제 백테스트로 돌아간다.
        val models = BuiltInModels.all
        assertTrue(models.size >= 2, "${models.size}")
        assertTrue(models.all { it.builtIn && it.market == Market.KR && it.holdout != null })
        assertTrue(models.first().isEnsemble && models.first().parts.size >= 3)
        assertTrue(models.first().evaluations >= 100_000, "${models.first().evaluations}")
        models.forEach { m -> (if (m.isEnsemble) m.parts else listOf(m.genome)).forEach { g -> g.members.forEach { id -> assertTrue(Strategies.find(id) != null, id) } } }
        val data = PriceData(series(1500, 9))
        models.forEach { m -> Backtester.run(m.toStrategy(), data) }
    }
}
