package io.github.jgg113.stocknote.core

import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiTest {
    private fun series(n: Int, seed: Int, drift: (Int, Double) -> Double): List<Candle> {
        val r = Random(seed)
        var p = 100.0
        val start = LocalDate.of(2020, 1, 1)
        var prevRet = 0.0
        return (0 until n).map { i ->
            val open = p
            val ret = drift(i, prevRet) + r.nextDouble(-1.0, 1.0) * 0.02
            prevRet = ret
            p *= exp(ret)
            Candle(start.plusDays(i.toLong()).epochKst(), open, maxOf(open, p) * 1.005, minOf(open, p) * 0.995, p, 1000.0 + r.nextInt(500))
        }
    }

    @Test
    fun strategiesCoverEveryHorizon() {
        StrategyHorizon.entries.forEach { h -> assertTrue(Strategies.of(h).size >= 10, "${h.label} 전략이 10개보다 적습니다: ${Strategies.of(h).size}") }
        assertEquals(Strategies.all.size, Strategies.all.map { it.id }.toSet().size)
        val d = PriceData(series(1500, 1) { i, _ -> 0.002 * sin(i / 60.0) })
        val report = IndicatorReport.build(d, BacktestConfig())
        val long = report.of(StrategyHorizon.LONG)
        assertTrue(long.results.any { it.closedTrades > 0 }, "장기 전략이 6년 자료에서 한 번도 거래하지 않았습니다")
        assertTrue(report.results.all { it.signalsPerMonth >= 0 })
        assertTrue(report.lastBuySharePct in 0.0..100.0)
    }

    @Test
    fun gbdtLearnsNonlinearRule() {
        val r = Random(3)
        val x = List(4000) { DoubleArray(5) { r.nextDouble(-2.0, 2.0) } }
        // 두 특징이 같은 부호일 때만 오른다 (선형으로는 못 푸는 규칙)
        val y = DoubleArray(x.size) { if (x[it][0] * x[it][1] > 0) 1.0 else 0.0 }
        val model = Gbdt.fit(x.take(3000), y.copyOfRange(0, 3000), true, Random(1), trees = 60)
        val test = x.drop(3000)
        val p = DoubleArray(test.size) { model.predict(test[it]) }
        val auc = Metrics.auc(p, BooleanArray(test.size) { y[3000 + it] > 0.5 })
        assertTrue(auc > 0.9, "AUC $auc")
        val linear = LinearModel.logistic(x.take(3000), y.copyOfRange(0, 3000))
        val pl = DoubleArray(test.size) { linear.predict(test[it]) }
        assertTrue(Metrics.auc(pl, BooleanArray(test.size) { y[3000 + it] > 0.5 }) < 0.65, "선형 모델은 이 규칙을 풀지 못해야 합니다")
    }

    @Test
    fun linearAndNeuralLearnLinearRule() {
        val r = Random(5)
        val x = List(3000) { DoubleArray(4) { r.nextDouble(-1.0, 1.0) } }
        val y = DoubleArray(x.size) { if (x[it][0] - 0.5 * x[it][2] + r.nextDouble(-0.3, 0.3) > 0) 1.0 else 0.0 }
        val up = BooleanArray(1000) { y[2000 + it] > 0.5 }
        val lg = LinearModel.logistic(x.take(2000), y.copyOfRange(0, 2000))
        val nn = Mlp.fit(x.take(2000), y.copyOfRange(0, 2000), Random(2), epochs = 20)
        val test = x.drop(2000)
        assertTrue(Metrics.auc(DoubleArray(1000) { lg.predict(test[it]) }, up) > 0.9)
        assertTrue(Metrics.auc(DoubleArray(1000) { nn.predict(test[it]) }, up) > 0.85)
        val ridge = LinearModel.ridge(x.take(2000), DoubleArray(2000) { x[it][0] * 2 + 1 }, lambda = 0.001)
        assertEquals(1.0 + 2 * 0.3, ridge.predict(doubleArrayOf(0.3, 0.0, 0.0, 0.0)), 1e-3)
    }

    @Test
    fun randomForestLearnsNonlinearRule() {
        val r = Random(11)
        val x = List(4000) { DoubleArray(6) { r.nextDouble(-2.0, 2.0) } }
        val y = DoubleArray(x.size) { if (x[it][0] * x[it][1] > 0) 1.0 else 0.0 }
        val model = RandomForest.fit(x.take(3000), y.copyOfRange(0, 3000), Random(1), mtry = 3)
        val test = x.drop(3000)
        val auc = Metrics.auc(DoubleArray(test.size) { model.predict(test[it]) }, BooleanArray(test.size) { y[3000 + it] > 0.5 })
        assertTrue(auc > 0.9, "랜덤 포레스트 AUC $auc")
    }

    @Test
    fun gruGradientMatchesNumericalDerivative() {
        val r = Random(4)
        val model = Gru.init(3, 4, 5, r)
        val seq = FloatArray(15) { r.nextDouble(-1.0, 1.0).toFloat() }
        val grad = DoubleArray(model.p.size)
        model.lossAndGrad(seq, 1.0, grad)
        val eps = 1e-6
        for (k in model.p.indices) {
            val old = model.p[k]
            model.p[k] = old + eps; val up = model.lossAndGrad(seq, 1.0, DoubleArray(model.p.size))
            model.p[k] = old - eps; val down = model.lossAndGrad(seq, 1.0, DoubleArray(model.p.size))
            model.p[k] = old
            val numeric = (up - down) / (2 * eps)
            assertEquals(numeric, grad[k], 1e-5, "매개변수 $k 의 기울기가 다릅니다")
        }
    }

    @Test
    fun gruLearnsSequenceRule() {
        val r = Random(8)
        // 뒤쪽 5칸의 첫 값 합이 양수면 오른다 → 순서를 읽어야 맞힐 수 있다.
        val seqs = List(3000) { FloatArray(20 * 2) { r.nextDouble(-1.0, 1.0).toFloat() } }
        val y = DoubleArray(seqs.size) { i -> if ((15 until 20).sumOf { seqs[i][it * 2].toDouble() } > 0) 1.0 else 0.0 }
        val model = Gru.fit(seqs.take(2400), y.copyOfRange(0, 2400), 2, 20, Random(1), hidden = 8, epochs = 8)
        val test = seqs.drop(2400)
        val auc = Metrics.auc(DoubleArray(test.size) { model.predict(test[it]) }, BooleanArray(test.size) { y[2400 + it] > 0.5 })
        assertTrue(auc > 0.9, "GRU AUC $auc")
    }

    @Test
    fun externalModelJoinsOnlyWhenValidated() {
        val pool = (1..6).map { k -> Asset(Market.KR, "20000$k", "무작위$k") to series(1300, 300 + k) { _, _ -> 0.0 } }
        val ens = AiForecaster.train(pool, AiHorizon.SHORT)!!
        val (asset, candles) = pool[0]
        val unvalidated = ExternalModel("kronos", "Kronos", "", 0.95, 0.3, Double.NaN, Double.NaN)
        val a = AiForecaster.predict(ens, asset, candles, external = unvalidated)!!
        assertEquals(0.0, a.models.last().weight, 1e-12)
        assertTrue(a.models.last().pretrained)
        val base = AiForecaster.predict(ens, asset, candles)!!
        assertEquals(base.upProb, a.upProb, 1e-12, "검증 안 된 모델이 의견을 바꿨습니다")
        val validated = ExternalModel("kronos", "Kronos", "", 0.95, 0.3, 0.70, 0.03)
        val b = AiForecaster.predict(ens, asset, candles, external = validated)!!
        assertTrue(b.models.last().weight > 0.5 && b.upProb > base.upProb + 0.1, "검증된 모델이 반영되지 않았습니다: ${b.upProb} vs ${base.upProb}")
        assertEquals(7, b.models.size)
    }

    @Test
    fun metricsBehave() {
        val up = booleanArrayOf(false, false, true, true)
        assertEquals(1.0, Metrics.auc(doubleArrayOf(0.1, 0.2, 0.8, 0.9), up), 1e-9)
        assertEquals(0.5, Metrics.auc(doubleArrayOf(0.5, 0.5, 0.5, 0.5), up), 1e-9)
        assertEquals(1.0, Metrics.rankIc(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(10.0, 20.0, 30.0)), 1e-9)
    }

    @Test
    fun patternMatcherFindsTheSameShape() {
        val base = series(900, 9) { i, _ -> 0.003 * sin(i / 15.0) }
        val asset = Asset(Market.KR, "000001", "기준")
        val other = Asset(Market.KR, "000002", "복사본")
        // 다른 종목에 같은 모양을 2배 가격으로 넣는다 → 비슷함 1.0 이어야 한다.
        val target = base.subList(0, 400)
        val copy = base.map { it.copy(open = it.open * 2, high = it.high * 2, low = it.low * 2, close = it.close * 2) }
        val m = AiForecaster.matches(target, asset, listOf(other to copy), AiHorizon.SHORT, step = 1)
        assertTrue(m.isNotEmpty())
        assertTrue(m.first().similarity > 0.999, "비슷함 ${m.first().similarity}")
        assertEquals(other.key, m.first().asset.key)
    }

    @Test
    fun ensembleFindsPersistentMomentumAndStaysHumbleOnRandomWalk() {
        // 1) 수익률에 추세가 오래 이어지는 시장 (6개 종목)
        val momentum = (1..6).map { k -> Asset(Market.KR, "00000$k", "추세$k") to series(1300, k) { i, _ -> 0.004 * sin(i / 40.0 + k) } }
        val ens = AiForecaster.train(momentum, AiHorizon.SHORT)
        assertNotNull(ens)
        val best = ens.reports.maxOf { if (it.auc.isNaN()) 0.0 else it.auc }
        println("추세 시장 AUC: " + ens.reports.joinToString { "${it.name} ${"%.3f".format(it.auc)}" })
        assertTrue(best > 0.6, "추세가 있는 자료에서는 맞혀야 합니다: $best")
        val f = AiForecaster.predict(ens, momentum[0].first, momentum[0].second)
        assertNotNull(f)
        assertEquals(AiHorizon.SHORT.bars, f.path.size)
        assertTrue(f.target2 >= f.target1 && f.target1 > f.price && f.stop < f.price)
        assertTrue(f.score in 0.0..100.0)

        // 2) 아무 규칙이 없는 시장 → 모델들이 기준보다 뚜렷이 낫지 않아야 하고, 의견이 중립 쪽이어야 한다.
        val random = (1..6).map { k -> Asset(Market.KR, "10000$k", "무작위$k") to series(1300, 100 + k) { _, _ -> 0.0 } }
        val ens2 = AiForecaster.train(random, AiHorizon.SHORT)
        assertNotNull(ens2)
        println("무작위 시장 AUC: " + ens2.reports.joinToString { "${it.name} ${"%.3f".format(it.auc)}" } + " 신뢰도 ${ens2.reliability.label} z=${"%.2f".format(ens2.bestZ)} 유효표본 ${ens2.effectiveSamples}")
        val f2 = AiForecaster.predict(ens2, random[0].first, random[0].second)!!
        assertTrue(f2.score in 25.0..75.0, "무작위 자료에서 강한 의견이 나왔습니다: ${f2.score}")
        assertTrue(ens2.reliability != Reliability.HIGH, "무작위 자료인데 신뢰도가 높음으로 나왔습니다 (z=${ens2.bestZ})")
        assertTrue(ens.reliability != Reliability.LOW, "추세 자료인데 신뢰도가 낮음으로 나왔습니다 (z=${ens.bestZ})")
        val ranks = AiForecaster.rank(ens2, random)
        assertEquals(6, ranks.size)
    }
}
