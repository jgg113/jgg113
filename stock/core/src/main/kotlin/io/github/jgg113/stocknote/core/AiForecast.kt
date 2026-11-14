package io.github.jgg113.stocknote.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** 예측 기간 */
enum class AiHorizon(val bars: Int, val label: String, val period: String, val stride: Int, val window: Int) {
    SHORT(20, "단기", "1개월", 2, 40),
    MID(120, "중기", "6개월", 5, 80),
    LONG(250, "장기", "1년", 10, 120),
}

/** 투자의견 5단계 */
enum class AiOpinion(val label: String, val tone: Int) {
    STRONG_BUY("강력매수", 2), BUY("매수", 1), HOLD("중립", 0), SELL("매도", -1), STRONG_SELL("강력매도", -2);

    companion object {
        fun of(score: Double) = when {
            score >= 70 -> STRONG_BUY
            score >= 57 -> BUY
            score > 43 -> HOLD
            score > 30 -> SELL
            else -> STRONG_SELL
        }
    }
}

/** 모델 하나의 검증 성적과 이번 예측 */
data class ModelReport(
    val id: String,
    val name: String,
    val description: String,
    /** 오를 확률 (0~1), 확률을 내지 않는 모델이면 NaN */
    val upProb: Double,
    /** 예상 수익률 (소수), 내지 않으면 NaN */
    val expectedReturn: Double,
    /** 검증 구간 AUC (0.5 = 맞히는 힘 없음) */
    val auc: Double,
    /** AUC 의 통계 오차 (겹치지 않는 표본 수 기준). AUC − 0.5 가 이보다 2배 넘게 커야 우연이 아니라고 본다. */
    val aucSe: Double,
    /** 검증 구간 방향 적중률 */
    val accuracy: Double,
    /** 검증 구간 순위 상관 (예상 수익률과 실제 수익률) */
    val ic: Double,
    /** 앙상블 안에서 받은 가중치 (0~1) */
    val weight: Double,
)

/** AI 가 참고한 비슷한 과거 차트 */
data class PatternMatch(
    val asset: Asset,
    /** 비교한 구간 + 그 뒤 움직임 (차트용) */
    val candles: List<Candle>,
    /** [candles] 안에서 비교 구간이 끝나는 위치 */
    val splitIndex: Int,
    val similarity: Double,
    /** 비교 구간이 끝난 뒤 예측 기간 동안의 수익률 */
    val forwardReturn: Double,
)

enum class Reliability(val label: String) { HIGH("높음"), MEDIUM("보통"), LOW("낮음") }

data class AiForecastResult(
    val asset: Asset,
    val horizon: AiHorizon,
    val price: Double,
    val upProb: Double,
    val baseUpProb: Double,
    val expectedReturn: Double,
    val score: Double,
    val opinion: AiOpinion,
    val target1: Double,
    val target2: Double,
    val stop: Double,
    /** 앞으로 [horizon] 봉 동안의 예상 가격 경로 (첫 값 = 다음 봉) */
    val path: DoubleArray,
    val matches: List<PatternMatch>,
    val models: List<ModelReport>,
    val reliability: Reliability,
    val trainAssets: Int,
    val trainSamples: Int,
    val validSamples: Int,
    /** 정답 기간이 겹치지 않게 센 검증 표본 수 */
    val effectiveSamples: Int,
    val analysisYears: Double,
    val topFeatures: List<String>,
) {
    val expectedPrice: Double get() = price * (1 + expectedReturn)
    fun pct(p: Double) = (p / price - 1) * 100
}

/** 학습한 앙상블 (같은 시장·기간이면 여러 종목 예측에 다시 쓴다) */
class AiEnsemble internal constructor(
    val horizon: AiHorizon,
    internal val scaler: Scaler,
    internal val gbdtCls: Gbdt?,
    internal val gbdtReg: Gbdt?,
    internal val logit: LinearModel?,
    internal val ridge: LinearModel?,
    internal val mlp: Mlp?,
    val reports: List<ModelReport>,
    val baseUpProb: Double,
    val trainSamples: Int,
    val validSamples: Int,
    val pool: List<Pair<Asset, List<Candle>>>,
    val patternAuc: Double,
    val patternWeight: Double,
    val topFeatures: List<String>,
) {
    internal var weights: AiForecaster.Weights? = null

    /** 겹치지 않게 센 검증 표본 수 */
    val effectiveSamples: Int get() = (validSamples * horizon.stride / horizon.bars).coerceAtLeast(1)

    /** 가장 나은 모델이 우연보다 몇 표준오차만큼 나았는지 */
    val bestZ: Double get() = reports.filter { !it.auc.isNaN() && it.aucSe > 0 }.maxOfOrNull { (it.auc - 0.5) / it.aucSe } ?: 0.0

    val reliability: Reliability get() = when {
        bestZ >= 3 && effectiveSamples >= 100 -> Reliability.HIGH
        bestZ >= 2 && effectiveSamples >= 40 -> Reliability.MEDIUM
        else -> Reliability.LOW
    }
}

object AiForecaster {
    private class Sample(val x: DoubleArray, val ret: Double, val time: Long, val labelTime: Long)

    private fun samples(pool: List<Pair<Asset, List<Candle>>>, h: AiHorizon): List<Sample> {
        val out = ArrayList<Sample>()
        for ((_, candles) in pool) {
            if (candles.size < AiFeatures.WARMUP + h.bars + 20) continue
            val d = PriceData(candles)
            val f = AiFeatures.compute(d)
            var t = AiFeatures.WARMUP
            while (t + h.bars < d.size) {
                f[t]?.let { x -> out += Sample(x, d.close[t + h.bars] / d.close[t] - 1, candles[t].time, candles[t + h.bars].time) }
                t += h.stride
            }
        }
        return out
    }

    /**
     * [pool] (같은 시장 종목들의 일봉)으로 모델 다섯 개를 학습한다.
     * 시간 순서로 앞 80% 로 학습하고, 그 뒤 20% (학습 정답 기간과 겹치지 않게 비움)로 검증해 가중치를 정한 다음,
     * 전체 자료로 다시 학습해 예측에 쓴다.
     */
    fun train(pool: List<Pair<Asset, List<Candle>>>, h: AiHorizon, seed: Int = 7, progress: (String) -> Unit = {}): AiEnsemble? {
        progress("학습 자료 만드는 중")
        val all = samples(pool, h)
        if (all.size < 300) return null
        val times = all.map { it.time }.sorted()
        // 앞으로 걸어가며 두 번 검증한다: (앞 60% → 다음 20%), (앞 80% → 마지막 20%).
        // 학습 자료는 정답(미래 수익률)이 검증 시작 전에 끝난 것만 쓴다.
        val cuts = listOf(times[(times.size * 0.6).toInt()], times[(times.size * 0.8).toInt()], Long.MAX_VALUE)
        val folds = (0..1).map { k -> all.filter { it.labelTime <= cuts[k] } to all.filter { it.time > cuts[k] && it.time <= cuts[k + 1] } }
        if (folds.any { it.first.size < 150 || it.second.size < 30 }) return null
        val train = folds.last().first
        val valid = folds.flatMap { it.second }
        val random = Random(seed)

        fun fitAll(rows: List<Sample>, label: String): Fitted {
            progress("$label: 그래디언트 부스팅")
            val scaler = Scaler.fit(rows.map { it.x })
            val x = rows.map { scaler.apply(it.x) }
            val up = DoubleArray(rows.size) { if (rows[it].ret > 0) 1.0 else 0.0 }
            val sorted = rows.map { it.ret }.sorted()
            val lo = Metrics.quantile(sorted, 0.01); val hi = Metrics.quantile(sorted, 0.99)
            val ret = DoubleArray(rows.size) { rows[it].ret.coerceIn(lo, hi) }
            val trees = if (rows.size > 8000) 80 else 50
            val minLeaf = max(20, rows.size / 200)
            val gc = Gbdt.fit(x, up, true, random, trees = trees, minLeaf = minLeaf)
            val gr = Gbdt.fit(x, ret, false, random, trees = trees, minLeaf = minLeaf)
            progress("$label: 회귀·신경망")
            val lg = LinearModel.logistic(x, up)
            val rd = LinearModel.ridge(x, ret)
            val nn = Mlp.fit(x, up, random, epochs = if (rows.size > 8000) 8 else 14)
            return Fitted(scaler, gc, gr, lg, rd, nn)
        }

        val pGcL = ArrayList<Double>(); val pLgL = ArrayList<Double>(); val pNnL = ArrayList<Double>(); val rGrL = ArrayList<Double>(); val rRdL = ArrayList<Double>()
        folds.forEachIndexed { k, (tr, va) ->
            val fitted = fitAll(tr, "검증 ${k + 1}/2 학습")
            progress("검증 ${k + 1}/2")
            va.forEach { smp ->
                val x = fitted.scaler.apply(smp.x)
                pGcL += fitted.gbdtCls.predict(x); pLgL += fitted.logit.predict(x); pNnL += fitted.mlp.predict(x)
                rGrL += fitted.gbdtReg.predict(x); rRdL += fitted.ridge.predict(x)
            }
        }
        val vUp = BooleanArray(valid.size) { valid[it].ret > 0 }
        val vRet = DoubleArray(valid.size) { valid[it].ret }
        val pGc = pGcL.toDoubleArray(); val pLg = pLgL.toDoubleArray(); val pNn = pNnL.toDoubleArray()
        val rGr = rGrL.toDoubleArray(); val rRd = rRdL.toDoubleArray()
        val baseUp = train.count { it.ret > 0 }.toDouble() / train.size
        // 정답 기간이 겹치는 표본은 서로 거의 같은 정보이므로, 겹치지 않는 수로 통계 오차를 잰다.
        val effective = (valid.size.toDouble() * h.stride / h.bars).coerceAtLeast(4.0)
        val upFrac = vUp.count { it }.toDouble() / valid.size
        fun aucSe(n: Double): Double {
            val n1 = (n * upFrac).coerceAtLeast(1.0); val n2 = (n * (1 - upFrac)).coerceAtLeast(1.0)
            return sqrt((n1 + n2 + 1) / (12 * n1 * n2))
        }
        val se = aucSe(effective)

        // 패턴 매칭 검증: 검증 구간에서 일부 시점만 골라, 그 시점 이전 자료만으로 찾아 본다.
        progress("패턴 매칭 검증")
        val patternScores = ArrayList<Pair<Double, Boolean>>()
        val checkpoints = pool.filter { it.second.size > AiFeatures.WARMUP + h.bars }.flatMap { (asset, c) ->
            c.indices.filter { i -> c[i].time > cuts[0] && i + h.bars < c.size && i >= h.window }.let { idx -> idx.filterIndexed { k, _ -> k % max(1, idx.size / 6) == 0 } }.map { asset to it }
        }.shuffled(random).take(60)
        for ((asset, i) in checkpoints) {
            val c = pool.first { it.first.key == asset.key }.second
            val m = matches(c.subList(0, i + 1), asset, pool, h, before = c[i].time - (h.bars * 1.5 * 86400).toLong(), top = 15, step = h.stride * 2)
            if (m.size < 5) continue
            val p = m.count { it.forwardReturn > 0 }.toDouble() / m.size
            patternScores += p to (c[i + h.bars].close > c[i].close)
        }
        val patternAuc = if (patternScores.size >= 20) Metrics.auc(patternScores.map { it.first }.toDoubleArray(), patternScores.map { it.second }.toBooleanArray()) else Double.NaN
        val patternSe = aucSe(patternScores.size.toDouble())

        // 우연으로 나올 만한 만큼(표준오차 1배)은 빼고 남는 실력만 가중치로 쓴다.
        fun edge(auc: Double, err: Double = se) = if (auc.isNaN()) 0.0 else max(0.0, auc - 0.5 - err)
        val aucGc = Metrics.auc(pGc, vUp); val aucLg = Metrics.auc(pLg, vUp); val aucNn = Metrics.auc(pNn, vUp)
        val icGr = Metrics.rankIc(rGr, vRet); val icRd = Metrics.rankIc(rRd, vRet)
        val clsEdges = listOf(edge(aucGc), edge(aucLg), edge(aucNn), edge(patternAuc, patternSe))
        val clsSum = clsEdges.sum()
        val clsW = clsEdges.map { if (clsSum > 0) it / clsSum else 0.0 }
        val icSe = 1 / sqrt(effective)
        val regEdges = listOf(max(0.0, icGr - icSe), max(0.0, icRd - icSe))
        val regSum = regEdges.sum()
        val regW = regEdges.map { if (regSum > 0) it / regSum else 0.0 }

        val final = fitAll(all, "전체 자료로 다시 학습")
        val importance = final.gbdtCls.importance(AiFeatures.size)
        val topFeatures = importance.indices.sortedByDescending { importance[it] }.take(5).map { AiFeatures.names[it] }

        val reports = listOf(
            ModelReport("gbdt", "그래디언트 부스팅", "결정트리 수십 개를 차례로 더해 오를 확률과 수익률을 맞춥니다 (LightGBM 방식).", Double.NaN, Double.NaN, aucGc, se, Metrics.accuracy(pGc, vUp), icGr, (clsW[0] + regW[0]) / 2),
            ModelReport("mlp", "신경망", "은닉층 두 개짜리 작은 신경망으로 오를 확률을 맞춥니다.", Double.NaN, Double.NaN, aucNn, se, Metrics.accuracy(pNn, vUp), Double.NaN, clsW[2]),
            ModelReport("linear", "로지스틱·릿지 회귀", "특징값의 가중합으로 오를 확률과 수익률을 맞추는 기본 모델입니다.", Double.NaN, Double.NaN, aucLg, se, Metrics.accuracy(pLg, vUp), icRd, (clsW[1] + regW[1]) / 2),
            ModelReport("pattern", "차트 패턴 매칭", "지금과 모양이 가장 비슷했던 과거 차트들이 그 뒤 어떻게 움직였는지 봅니다.", Double.NaN, Double.NaN, patternAuc, patternSe, Double.NaN, Double.NaN, clsW[3]),
        )
        return AiEnsemble(
            h, final.scaler, final.gbdtCls, final.gbdtReg, final.logit, final.ridge, final.mlp, reports, baseUp,
            train.size, valid.size, pool, patternAuc, clsW[3], topFeatures,
        ).also { it.weights = Weights(clsW, regW) }
    }

    private class Fitted(val scaler: Scaler, val gbdtCls: Gbdt, val gbdtReg: Gbdt, val logit: LinearModel, val ridge: LinearModel, val mlp: Mlp)

    internal class Weights(val cls: List<Double>, val reg: List<Double>)

    /** 지금 차트와 모양이 비슷한 과거 구간을 [pool] 에서 찾는다 ([before] 이전에 끝난 결과만). */
    fun matches(
        target: List<Candle>, targetAsset: Asset, pool: List<Pair<Asset, List<Candle>>>, h: AiHorizon,
        before: Long = Long.MAX_VALUE, top: Int = 20, step: Int = h.stride,
    ): List<PatternMatch> {
        val L = h.window
        if (target.size < L) return emptyList()
        fun shape(c: List<Candle>, end: Int): DoubleArray? {
            val v = DoubleArray(L) { k -> kotlin.math.ln(c[end - L + 1 + k].close.coerceAtLeast(1e-9)) }
            val m = v.average()
            val sd = sqrt(v.sumOf { (it - m) * (it - m) } / L)
            if (sd < 1e-6) return null
            return DoubleArray(L) { (v[it] - m) / sd }
        }
        val t = shape(target, target.size - 1) ?: return emptyList()
        val found = ArrayList<PatternMatch>()
        for ((asset, c) in pool) {
            val same = asset.key == targetAsset.key
            var end = L - 1
            val lastEnd = c.size - 1 - h.bars
            val candidates = ArrayList<Pair<Double, Int>>()
            while (end <= lastEnd) {
                if (c[end + h.bars].time < before && !(same && c[end].time > target.last().time - (L + h.bars) * 86400L * 1.4)) {
                    val s = shape(c, end)
                    if (s != null) {
                        var dot = 0.0
                        for (k in 0 until L) dot += s[k] * t[k]
                        candidates += dot / L to end
                    }
                }
                end += step
            }
            // 같은 종목에서 거의 같은 구간이 여러 번 뽑히지 않게, 구간 길이만큼 떨어진 것만 남긴다.
            val chosen = ArrayList<Pair<Double, Int>>()
            for (cand in candidates.sortedByDescending { it.first }) {
                if (chosen.size >= 3) break
                if (chosen.all { abs(it.second - cand.second) > L }) chosen += cand
            }
            for ((sim, e) in chosen) {
                val from = e - L + 1
                val to = min(c.size, e + h.bars + 1)
                found += PatternMatch(asset, c.subList(from, to), e - from, sim, c[e + h.bars].close / c[e].close - 1)
            }
        }
        return found.sortedByDescending { it.similarity }.take(top)
    }

    /** 한 종목 예측 */
    fun predict(ens: AiEnsemble, asset: Asset, candles: List<Candle>, price: Double = candles.last().close): AiForecastResult? {
        val h = ens.horizon
        if (candles.size < AiFeatures.WARMUP + 5) return null
        val d = PriceData(candles)
        val raw = AiFeatures.compute(d).last() ?: return null
        val x = ens.scaler.apply(raw)
        val w = ens.weights ?: return null
        val pGc = ens.gbdtCls!!.predict(x); val pLg = ens.logit!!.predict(x); val pNn = ens.mlp!!.predict(x)
        val rGr = ens.gbdtReg!!.predict(x); val rRd = ens.ridge!!.predict(x)
        val pool = if (ens.pool.any { it.first.key == asset.key }) ens.pool else ens.pool + (asset to candles)
        val m = matches(candles, asset, pool, h)
        val pPat = if (m.size >= 5) m.count { it.forwardReturn > 0 }.toDouble() / m.size else Double.NaN
        val rPat = if (m.size >= 5) Metrics.quantile(m.map { it.forwardReturn }.sorted(), 0.5) else Double.NaN

        // 확률: 검증에서 기준(0.5)보다 나았던 만큼 가중한다. 모두 못했으면 평소 확률로 둔다.
        val probs = listOf(pGc, pLg, pNn, pPat)
        val clsW = w.cls.mapIndexed { i, wi -> if (probs[i].isNaN()) 0.0 else wi }
        val clsSum = clsW.sum()
        val ensembleProb = if (clsSum > 0) probs.indices.sumOf { if (clsW[it] > 0) probs[it] * clsW[it] else 0.0 } / clsSum else ens.baseUpProb
        // 검증에서 우연보다 뚜렷이 나았던 만큼만 평소 확률에서 벗어나게 한다 (2 표준오차 미만이면 거의 평소대로).
        val trust = ((ens.bestZ - 1) / 2).coerceIn(0.1, 1.0)
        val upProb = ens.baseUpProb + (ensembleProb - ens.baseUpProb) * trust

        // 이 종목의 과거 같은 기간 수익률 분포 (범위 계산용)
        val hist = (0 until candles.size - h.bars step max(1, h.stride / 2)).map { candles[it + h.bars].close / candles[it].close - 1 }.sorted()
        val histMed = Metrics.quantile(hist, 0.5).takeIf { !it.isNaN() } ?: 0.0
        val sigma = if (hist.size > 5) sqrt(hist.sumOf { (it - hist.average()).let { v -> v * v } } / hist.size) else 0.1
        val regs = listOf(rGr, rRd)
        val regSum = w.reg.sum()
        val modelRet = if (regSum > 0) regs.indices.sumOf { regs[it] * w.reg[it] } / regSum else Double.NaN
        val expected = listOfNotNull(modelRet.takeIf { !it.isNaN() }, rPat.takeIf { !it.isNaN() }?.let { it * 0.5 + histMed * 0.5 })
            .ifEmpty { listOf(histMed * 0.5) }.average() * trust
        fun q(p: Double) = expected + (Metrics.quantile(hist, p).takeIf { !it.isNaN() } ?: 0.0) - histMed

        val z1 = (upProb - ens.baseUpProb) / 0.1
        val z2 = if (sigma > 0) expected / sigma else 0.0
        val score = (50 + 18 * z1 + 12 * z2).coerceIn(0.0, 100.0)
        val opinion = AiOpinion.of(score)
        val atrPct = d.atr()[d.size - 1] / d.close[d.size - 1]
        val t1 = max(q(0.70), atrPct * 1.0)
        val t2 = max(q(0.90), t1 + atrPct)
        val stop = min(q(0.20), -atrPct * 1.5)

        // 예상 경로: 비슷했던 과거 구간들의 경로 가운데 값에 모양을 맞추고, 끝을 예상 수익률에 맞춘다.
        val path = DoubleArray(h.bars) { k ->
            val shape = if (m.size >= 5) Metrics.quantile(m.mapNotNull { pm -> pm.candles.getOrNull(pm.splitIndex + k + 1)?.let { it.close / pm.candles[pm.splitIndex].close - 1 } }.sorted(), 0.5) else Double.NaN
            val end = if (m.size >= 5) Metrics.quantile(m.map { it.forwardReturn }.sorted(), 0.5) else 0.0
            val base = if (shape.isNaN()) 0.0 else shape
            val adj = base + (expected - end) * (k + 1) / h.bars
            price * (1 + adj)
        }

        val models = ens.reports.map { r ->
            when (r.id) {
                "gbdt" -> r.copy(upProb = pGc, expectedReturn = rGr)
                "mlp" -> r.copy(upProb = pNn)
                "linear" -> r.copy(upProb = pLg, expectedReturn = rRd)
                else -> r.copy(upProb = pPat, expectedReturn = rPat)
            }
        }
        val years = (candles.last().time - candles.first().time) / (365.25 * 86400)
        return AiForecastResult(
            asset, h, price, upProb, ens.baseUpProb, expected, score, opinion,
            price * (1 + t1), price * (1 + t2), price * (1 + stop), path,
            // 화면에는 종목이 겹치지 않게 보여 준다 (통계는 위에서 전부 썼다).
            (m.distinctBy { it.asset.key } + m).distinct().take(5), models, ens.reliability,
            ens.pool.size, ens.trainSamples, ens.validSamples, ens.effectiveSamples, years, ens.topFeatures,
        )
    }

    /** 여러 종목을 같은 모델로 점수 매겨 순위를 낸다 (머신러닝이 가장 힘을 내는 쓰임새: 종목 간 비교). */
    fun rank(ens: AiEnsemble, universe: List<Pair<Asset, List<Candle>>>): List<AiRankRow> {
        val w = ens.weights ?: return emptyList()
        return universe.mapNotNull { (asset, candles) ->
            val raw = AiFeatures.compute(PriceData(candles)).lastOrNull() ?: return@mapNotNull null
            val x = ens.scaler.apply(raw)
            val probs = listOf(ens.gbdtCls!!.predict(x), ens.logit!!.predict(x), ens.mlp!!.predict(x))
            val cw = w.cls.take(3)
            val p = if (cw.sum() > 0) probs.indices.sumOf { probs[it] * cw[it] } / cw.sum() else probs.average()
            val regs = listOf(ens.gbdtReg!!.predict(x), ens.ridge!!.predict(x))
            val r = if (w.reg.sum() > 0) regs.indices.sumOf { regs[it] * w.reg[it] } / w.reg.sum() else regs.average()
            AiRankRow(asset, candles.last().close, p, r)
        }.sortedByDescending { it.upProb + it.expectedReturn }
    }
}

data class AiRankRow(val asset: Asset, val price: Double, val upProb: Double, val expectedReturn: Double)
