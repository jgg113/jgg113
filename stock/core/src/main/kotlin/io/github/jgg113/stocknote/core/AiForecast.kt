package io.github.jgg113.stocknote.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
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
    /** 검증 구간 AUC (0.5 = 맞히는 힘 없음), 검증하지 않았으면 NaN */
    val auc: Double,
    /** AUC 의 통계 오차 (겹치지 않는 표본 수 기준). AUC − 0.5 가 이보다 2배 넘게 커야 우연이 아니라고 본다. */
    val aucSe: Double,
    /** 검증 구간 방향 적중률 */
    val accuracy: Double,
    /** 검증 구간 순위 상관 (예상 수익률과 실제 수익률) */
    val ic: Double,
    /** 앙상블 안에서 받은 가중치 (0~1) */
    val weight: Double,
    /** 이 앱에서 학습하는 모델이면 거짓, 미리 학습된 모델(Kronos)이면 참 */
    val pretrained: Boolean = false,
) {
    /** 우연보다 몇 표준오차만큼 나았는지 */
    val z: Double get() = if (auc.isNaN() || aucSe <= 0) 0.0 else (auc - 0.5) / aucSe
}

/** 앱 밖에서 미리 학습된 모델의 예측 (Kronos). 검증 성적이 있으면 앙상블에 넣는다. */
class ExternalModel(
    val id: String,
    val name: String,
    val description: String,
    val upProb: Double,
    val expectedReturn: Double,
    /** 이 시장에서 검증한 AUC (없으면 NaN → 참고용으로만 보여 준다) */
    val auc: Double,
    val aucSe: Double,
    val path: DoubleArray? = null,
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
    /** 같은 시장 대표 종목들의 가운데 수익률보다 잘할 확률 (없으면 NaN) */
    val beatMarketProb: Double = Double.NaN,
    val relAuc: Double = Double.NaN,
    val relSe: Double = Double.NaN,
) {
    val expectedPrice: Double get() = price * (1 + expectedReturn)
    fun pct(p: Double) = (p / price - 1) * 100
}

/** 학습한 앙상블 (같은 시장·기간이면 여러 종목 예측에 다시 쓴다) */
class AiEnsemble internal constructor(
    val horizon: AiHorizon,
    internal val fitted: AiForecaster.Fitted,
    val reports: List<ModelReport>,
    val baseUpProb: Double,
    val trainSamples: Int,
    val validSamples: Int,
    val pool: List<Pair<Asset, List<Candle>>>,
    val topFeatures: List<String>,
    internal val weights: AiForecaster.Weights,
    /** '시장 가운데 수익률보다 잘할지' 분류기의 검증 성적 */
    val relAuc: Double,
    val relSe: Double,
    val seconds: Double,
) {
    /** 겹치지 않게 센 검증 표본 수 */
    val effectiveSamples: Int get() = (validSamples * horizon.stride / horizon.bars).coerceAtLeast(1)

    /** 가장 나은 모델이 우연보다 몇 표준오차만큼 나았는지 */
    val bestZ: Double get() = reports.maxOfOrNull { it.z } ?: 0.0

    val relZ: Double get() = if (relAuc.isNaN() || relSe <= 0) 0.0 else (relAuc - 0.5) / relSe

    val reliability: Reliability get() = reliabilityOf(bestZ, effectiveSamples)

    companion object {
        fun reliabilityOf(z: Double, effective: Int) = when {
            z >= 3 && effective >= 100 -> Reliability.HIGH
            z >= 2 && effective >= 40 -> Reliability.MEDIUM
            else -> Reliability.LOW
        }
    }
}

object AiForecaster {
    internal class Sample(val x: DoubleArray, val seq: FloatArray, val ret: Double, val rel: Double, val time: Long, val labelTime: Long)

    private fun samples(pool: List<Pair<Asset, List<Candle>>>, h: AiHorizon): List<Sample> {
        // 날짜별로 종목들의 앞으로 수익률 가운데 값을 구해 둔다 ('시장보다 잘했나' 정답용).
        val byDate = HashMap<Long, MutableList<Double>>()
        for ((_, c) in pool) for (t in 0 until c.size - h.bars) byDate.getOrPut(c[t].time) { ArrayList() } += c[t + h.bars].close / c[t].close - 1
        val median = HashMap<Long, Double>()
        for ((time, list) in byDate) if (list.size >= 3) median[time] = Metrics.quantile(list.sorted(), 0.5)

        val out = ArrayList<Sample>()
        for ((_, candles) in pool) {
            if (candles.size < AiFeatures.WARMUP + h.bars + 20) continue
            val d = PriceData(candles)
            val f = AiFeatures.compute(d)
            var t = AiFeatures.WARMUP
            while (t + h.bars < d.size) {
                val x = f[t]
                val seq = AiFeatures.sequence(d, t)
                if (x != null && seq != null) {
                    val ret = d.close[t + h.bars] / d.close[t] - 1
                    out += Sample(x, seq, ret, median[candles[t].time]?.let { ret - it } ?: Double.NaN, candles[t].time, candles[t + h.bars].time)
                }
                t += h.stride
            }
        }
        return out
    }

    internal class Fitted(
        val scaler: Scaler, val gbdtCls: Gbdt, val gbdtReg: Gbdt, val forest: RandomForest, val logit: LinearModel, val ridge: LinearModel,
        val mlp: Mlp, val gru: Gru, val relCls: Gbdt?,
    ) {
        /** 분류기들의 오를 확률: 부스팅, 랜덤 포레스트, 신경망, 로지스틱, GRU 순 */
        fun probs(x: DoubleArray, seq: FloatArray) = doubleArrayOf(gbdtCls.predict(x), forest.predict(x), mlp.predict(x), logit.predict(x), gru.predict(seq))
        fun rets(x: DoubleArray) = doubleArrayOf(gbdtReg.predict(x), ridge.predict(x))
    }

    /** [clsEdges]: 분류기별 '우연을 뺀 실력' (부스팅, 포레스트, 신경망, 로지스틱, GRU, 패턴), [regEdges]: 회귀 (부스팅, 릿지) */
    internal class Weights(val clsEdges: DoubleArray, val regEdges: DoubleArray)

    private fun fitAll(rows: List<Sample>, random: Random, progress: (String) -> Unit, label: String): Fitted {
        progress("$label: 트리 모델")
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
        val rf = RandomForest.fit(x, up, random, trees = 60, depth = 6, minLeaf = max(15, rows.size / 300))
        val relRows = rows.indices.filter { !rows[it].rel.isNaN() }
        val rel = if (relRows.size >= 200) Gbdt.fit(relRows.map { x[it] }, DoubleArray(relRows.size) { if (rows[relRows[it]].rel > 0) 1.0 else 0.0 }, true, random, trees = trees, minLeaf = minLeaf) else null
        progress("$label: 회귀·신경망")
        val lg = LinearModel.logistic(x, up)
        val rd = LinearModel.ridge(x, ret)
        val nn = Mlp.fit(x, up, random, epochs = if (rows.size > 8000) 8 else 14)
        progress("$label: 순환신경망")
        val gru = Gru.fit(rows.map { it.seq }, up, AiFeatures.SEQ_INPUTS, AiFeatures.SEQ_STEPS, random)
        return Fitted(scaler, gc, gr, rf, lg, rd, nn, gru, rel)
    }

    private fun aucSe(n: Double, upFrac: Double): Double {
        val n1 = (n * upFrac).coerceAtLeast(1.0); val n2 = (n * (1 - upFrac)).coerceAtLeast(1.0)
        return sqrt((n1 + n2 + 1) / (12 * n1 * n2))
    }

    /**
     * [pool] (같은 시장 종목들의 일봉)으로 모델들을 학습한다.
     * 시간 순서로 앞으로 걸어가며 두 번 검증해(앞 60% → 다음 20%, 앞 80% → 마지막 20%) 모델마다 가중치를 정한 다음,
     * 전체 자료로 다시 학습해 예측에 쓴다. 세 번의 학습은 함께(병렬로) 돌린다.
     */
    fun train(pool: List<Pair<Asset, List<Candle>>>, h: AiHorizon, seed: Int = 7, progress: (String) -> Unit = {}): AiEnsemble? {
        val t0 = System.nanoTime()
        progress("학습 자료 만드는 중")
        val all = samples(pool, h)
        if (all.size < 300) return null
        val times = all.map { it.time }.sorted()
        // 학습 자료는 정답(미래 수익률)이 검증 시작 전에 끝난 것만 쓴다.
        val cuts = listOf(times[(times.size * 0.6).toInt()], times[(times.size * 0.8).toInt()], Long.MAX_VALUE)
        val folds = (0..1).map { k -> all.filter { it.labelTime <= cuts[k] } to all.filter { it.time > cuts[k] && it.time <= cuts[k + 1] } }
        if (folds.any { it.first.size < 150 || it.second.size < 30 }) return null
        val train = folds.last().first
        val valid = folds.flatMap { it.second }

        val exec = Executors.newFixedThreadPool(3)
        val fits: List<Fitted> = try {
            val jobs = listOf(
                Callable { fitAll(folds[0].first, Random(seed + 1), progress, "검증 1/2 학습") },
                Callable { fitAll(folds[1].first, Random(seed + 2), progress, "검증 2/2 학습") },
                Callable { fitAll(all, Random(seed), progress, "전체 자료 학습") },
            )
            exec.invokeAll(jobs).map { it.get() }
        } finally {
            exec.shutdown()
        }
        progress("검증하는 중")
        val nCls = 5
        val p = Array(nCls) { DoubleArray(valid.size) }
        val r = Array(2) { DoubleArray(valid.size) }
        val relP = ArrayList<Double>(); val relUp = ArrayList<Boolean>()
        var row = 0
        folds.forEachIndexed { k, (_, va) ->
            val f = fits[k]
            for (smp in va) {
                val x = f.scaler.apply(smp.x)
                val pr = f.probs(x, smp.seq); val rr = f.rets(x)
                for (m in 0 until nCls) p[m][row] = pr[m]
                r[0][row] = rr[0]; r[1][row] = rr[1]
                if (f.relCls != null && !smp.rel.isNaN()) { relP += f.relCls.predict(x); relUp += smp.rel > 0 }
                row++
            }
        }
        val vUp = BooleanArray(valid.size) { valid[it].ret > 0 }
        val vRet = DoubleArray(valid.size) { valid[it].ret }
        val baseUp = train.count { it.ret > 0 }.toDouble() / train.size
        // 정답 기간이 겹치는 표본은 서로 거의 같은 정보이므로, 겹치지 않는 수로 통계 오차를 잰다.
        val effective = (valid.size.toDouble() * h.stride / h.bars).coerceAtLeast(4.0)
        val upFrac = vUp.count { it }.toDouble() / valid.size
        val se = aucSe(effective, upFrac)

        // 패턴 매칭 검증: 검증 구간에서 일부 시점만 골라, 그 시점 이전 자료만으로 찾아 본다.
        progress("패턴 매칭 검증")
        val random = Random(seed + 9)
        val patternScores = ArrayList<Pair<Double, Boolean>>()
        val checkpoints = pool.filter { it.second.size > AiFeatures.WARMUP + h.bars }.flatMap { (asset, c) ->
            c.indices.filter { i -> c[i].time > cuts[0] && i + h.bars < c.size && i >= h.window }.let { idx -> idx.filterIndexed { k, _ -> k % max(1, idx.size / 6) == 0 } }.map { asset to it }
        }.shuffled(random).take(60)
        for ((asset, i) in checkpoints) {
            val c = pool.first { it.first.key == asset.key }.second
            val m = matches(c.subList(0, i + 1), asset, pool, h, before = c[i].time - (h.bars * 1.5 * 86400).toLong(), top = 15, step = h.stride * 2)
            if (m.size < 5) continue
            patternScores += m.count { it.forwardReturn > 0 }.toDouble() / m.size to (c[i + h.bars].close > c[i].close)
        }
        val patternAuc = if (patternScores.size >= 20) Metrics.auc(patternScores.map { it.first }.toDoubleArray(), patternScores.map { it.second }.toBooleanArray()) else Double.NaN
        val patternSe = aucSe(patternScores.size.toDouble(), upFrac)

        // 우연으로 나올 만한 만큼(표준오차 1배)은 빼고 남는 실력만 가중치로 쓴다.
        fun edge(auc: Double, err: Double) = if (auc.isNaN()) 0.0 else max(0.0, auc - 0.5 - err)
        val aucs = DoubleArray(nCls) { Metrics.auc(p[it], vUp) }
        val ics = DoubleArray(2) { Metrics.rankIc(r[it], vRet) }
        val clsEdges = DoubleArray(nCls + 1) { if (it < nCls) edge(aucs[it], se) else edge(patternAuc, patternSe) }
        val icSe = 1 / sqrt(effective)
        val regEdges = DoubleArray(2) { max(0.0, ics[it] - icSe) }
        val clsSum = clsEdges.sum(); val regSum = regEdges.sum()
        fun cw(i: Int) = if (clsSum > 0) clsEdges[i] / clsSum else 0.0
        fun rw(i: Int) = if (regSum > 0) regEdges[i] / regSum else 0.0

        // 시장 대비 초과수익 분류기: 같은 날 종목들끼리 비교하므로 시장 전체 방향에 덜 흔들린다.
        val relAuc = if (relP.size >= 100) Metrics.auc(relP.toDoubleArray(), relUp.toBooleanArray()) else Double.NaN
        val relSe = aucSe((relP.size.toDouble() * h.stride / h.bars).coerceAtLeast(4.0), 0.5)

        val final = fits[2]
        val importance = final.gbdtCls.importance(AiFeatures.size)
        val topFeatures = importance.indices.sortedByDescending { importance[it] }.take(5).map { AiFeatures.names[it] }
        fun acc(i: Int) = Metrics.accuracy(p[i], vUp)
        val nan = Double.NaN
        val reports = listOf(
            ModelReport("gbdt", "그래디언트 부스팅", "결정트리 수십 개를 차례로 더해 오를 확률과 수익률을 맞춥니다 (LightGBM 방식).", nan, nan, aucs[0], se, acc(0), ics[0], (cw(0) + rw(0)) / 2),
            ModelReport("forest", "랜덤 포레스트", "서로 다른 표본·특징으로 키운 깊은 트리 60개의 평균입니다. 한두 특징에 쏠리지 않아 안정적입니다.", nan, nan, aucs[1], se, acc(1), nan, cw(1)),
            ModelReport("mlp", "신경망", "은닉층 두 개짜리 작은 신경망으로 오를 확률을 맞춥니다.", nan, nan, aucs[2], se, acc(2), nan, cw(2)),
            ModelReport("linear", "로지스틱·릿지 회귀", "특징값의 가중합으로 오를 확률과 수익률을 맞추는 기본 모델입니다.", nan, nan, aucs[3], se, acc(3), ics[1], (cw(3) + rw(1)) / 2),
            ModelReport("gru", "GRU 순환신경망", "최근 20일의 수익률·변동폭·거래량 흐름을 하루씩 차례로 읽는 순서 모델입니다 (LSTM 계열).", nan, nan, aucs[4], se, acc(4), nan, cw(4)),
            ModelReport("pattern", "차트 패턴 매칭", "지금과 모양이 가장 비슷했던 과거 차트들이 그 뒤 어떻게 움직였는지 봅니다.", nan, nan, patternAuc, patternSe, nan, nan, cw(5)),
        )
        return AiEnsemble(
            h, final, reports, baseUp, train.size, valid.size, pool, topFeatures, Weights(clsEdges, regEdges), relAuc, relSe,
            (System.nanoTime() - t0) / 1e9,
        )
    }

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

    /**
     * 한 종목 예측. [external] 은 미리 학습된 모델(Kronos)의 예측으로, 이 시장에서 검증한 성적이 있을 때만 가중치를 받는다.
     */
    fun predict(ens: AiEnsemble, asset: Asset, candles: List<Candle>, price: Double = candles.last().close, external: ExternalModel? = null): AiForecastResult? {
        val h = ens.horizon
        if (candles.size < AiFeatures.WARMUP + 5) return null
        val d = PriceData(candles)
        val raw = AiFeatures.compute(d).last() ?: return null
        val seq = AiFeatures.sequence(d, d.size - 1) ?: return null
        val f = ens.fitted
        val x = f.scaler.apply(raw)
        val base = f.probs(x, seq)
        val rets = f.rets(x)
        val pool = if (ens.pool.any { it.first.key == asset.key }) ens.pool else ens.pool + (asset to candles)
        val m = matches(candles, asset, pool, h)
        val pPat = if (m.size >= 5) m.count { it.forwardReturn > 0 }.toDouble() / m.size else Double.NaN
        val rPat = if (m.size >= 5) Metrics.quantile(m.map { it.forwardReturn }.sorted(), 0.5) else Double.NaN

        // 확률: 검증에서 우연보다 나았던 만큼 가중한다. 모두 못했으면 평소 확률로 둔다.
        val extEdge = external?.let { if (it.auc.isNaN() || it.upProb.isNaN()) 0.0 else max(0.0, it.auc - 0.5 - it.aucSe) } ?: 0.0
        val probs = base.toList() + pPat + (external?.upProb ?: Double.NaN)
        val edges = ens.weights.clsEdges.toList() + extEdge
        val used = edges.mapIndexed { i, e -> if (probs[i].isNaN()) 0.0 else e }
        val sum = used.sum()
        val ensembleProb = if (sum > 0) probs.indices.sumOf { if (used[it] > 0) probs[it] * used[it] else 0.0 } / sum else ens.baseUpProb
        val extZ = external?.let { if (it.auc.isNaN() || it.aucSe <= 0) 0.0 else (it.auc - 0.5) / it.aucSe } ?: 0.0
        val bestZ = max(ens.bestZ, extZ)
        // 검증에서 우연보다 뚜렷이 나았던 만큼만 평소 확률에서 벗어나게 한다 (2 표준오차 미만이면 거의 평소대로).
        val trust = ((bestZ - 1) / 2).coerceIn(0.1, 1.0)
        val upProb = ens.baseUpProb + (ensembleProb - ens.baseUpProb) * trust

        // 이 종목의 과거 같은 기간 수익률 분포 (범위 계산용)
        val hist = (0 until candles.size - h.bars step max(1, h.stride / 2)).map { candles[it + h.bars].close / candles[it].close - 1 }.sorted()
        val histMed = Metrics.quantile(hist, 0.5).takeIf { !it.isNaN() } ?: 0.0
        val sigma = if (hist.size > 5) sqrt(hist.sumOf { (it - hist.average()).let { v -> v * v } } / hist.size) else 0.1
        val regSum = ens.weights.regEdges.sum()
        val modelRet = if (regSum > 0) rets.indices.sumOf { rets[it] * ens.weights.regEdges[it] } / regSum else Double.NaN
        val extRet = external?.expectedReturn?.takeIf { extEdge > 0 && !it.isNaN() }
        val expected = listOfNotNull(modelRet.takeIf { !it.isNaN() }, rPat.takeIf { !it.isNaN() }?.let { it * 0.5 + histMed * 0.5 }, extRet)
            .ifEmpty { listOf(histMed * 0.5) }.average() * trust
        fun q(p: Double) = expected + (Metrics.quantile(hist, p).takeIf { !it.isNaN() } ?: 0.0) - histMed

        val z1 = (upProb - ens.baseUpProb) / 0.1
        val z2 = if (sigma > 0) expected / sigma else 0.0
        val score = (50 + 18 * z1 + 12 * z2).coerceIn(0.0, 100.0)
        val atrPct = d.atr()[d.size - 1] / d.close[d.size - 1]
        val t1 = max(q(0.70), atrPct * 1.0)
        val t2 = max(q(0.90), t1 + atrPct)
        val stop = min(q(0.20), -atrPct * 1.5)

        // 예상 경로: 검증된 Kronos 경로가 있으면 그 모양을, 없으면 비슷했던 과거 구간들의 가운데 경로를 쓰고, 끝을 예상 수익률에 맞춘다.
        val extPath = external?.path?.takeIf { extEdge > 0 && it.size >= h.bars }
        val patEnd = if (m.size >= 5) Metrics.quantile(m.map { it.forwardReturn }.sorted(), 0.5) else 0.0
        val path = DoubleArray(h.bars) { k ->
            val (shape, end) = if (extPath != null) (extPath[k] / price - 1) to (extPath[h.bars - 1] / price - 1) else {
                val s = if (m.size >= 5) Metrics.quantile(m.mapNotNull { pm -> pm.candles.getOrNull(pm.splitIndex + k + 1)?.let { it.close / pm.candles[pm.splitIndex].close - 1 } }.sorted(), 0.5) else Double.NaN
                (if (s.isNaN()) 0.0 else s) to patEnd
            }
            price * (1 + shape + (expected - end) * (k + 1) / h.bars)
        }

        fun w(i: Int) = if (sum > 0) used[i] / sum else 0.0
        val models = ens.reports.mapIndexed { i, r ->
            when (r.id) {
                "gbdt" -> r.copy(upProb = base[0], expectedReturn = rets[0], weight = w(0))
                "forest" -> r.copy(upProb = base[1], weight = w(1))
                "mlp" -> r.copy(upProb = base[2], weight = w(2))
                "linear" -> r.copy(upProb = base[3], expectedReturn = rets[1], weight = w(3))
                "gru" -> r.copy(upProb = base[4], weight = w(4))
                else -> r.copy(upProb = pPat, expectedReturn = rPat, weight = w(i))
            }
        } + listOfNotNull(external?.let {
            ModelReport(it.id, it.name, it.description, it.upProb, it.expectedReturn, it.auc, it.aucSe, Double.NaN, Double.NaN, w(edges.size - 1), pretrained = true)
        })
        val beat = f.relCls?.predict(x) ?: Double.NaN
        val years = (candles.last().time - candles.first().time) / (365.25 * 86400)
        return AiForecastResult(
            asset, h, price, upProb, ens.baseUpProb, expected, score, AiOpinion.of(score),
            price * (1 + t1), price * (1 + t2), price * (1 + stop), path,
            // 화면에는 종목이 겹치지 않게 보여 준다 (통계는 위에서 전부 썼다).
            (m.distinctBy { it.asset.key } + m).distinct().take(5), models, AiEnsemble.reliabilityOf(bestZ, ens.effectiveSamples),
            ens.pool.size, ens.trainSamples, ens.validSamples, ens.effectiveSamples, years, ens.topFeatures,
            beat, ens.relAuc, ens.relSe,
        )
    }

    /**
     * 여러 종목을 같은 모델로 점수 매겨 순위를 낸다 (머신러닝이 가장 힘을 내는 쓰임새: 종목 간 비교).
     * '시장 가운데보다 잘할 확률' 분류기가 검증에서 통했으면 그것으로, 아니면 오를 확률로 줄을 세운다.
     */
    fun rank(ens: AiEnsemble, universe: List<Pair<Asset, List<Candle>>>): List<AiRankRow> {
        val f = ens.fitted
        val useRel = f.relCls != null && ens.relZ >= 1
        val edges = ens.weights.clsEdges
        val clsSum = (0 until 5).sumOf { edges[it] }
        val regSum = ens.weights.regEdges.sum()
        return universe.mapNotNull { (asset, candles) ->
            val d = PriceData(candles)
            val raw = AiFeatures.compute(d).lastOrNull() ?: return@mapNotNull null
            val seq = AiFeatures.sequence(d, d.size - 1) ?: return@mapNotNull null
            val x = f.scaler.apply(raw)
            val probs = f.probs(x, seq)
            val p = if (clsSum > 0) probs.indices.sumOf { probs[it] * edges[it] } / clsSum else probs.average()
            val rets = f.rets(x)
            val r = if (regSum > 0) rets.indices.sumOf { rets[it] * ens.weights.regEdges[it] } / regSum else rets.average()
            AiRankRow(asset, candles.last().close, p, r, f.relCls?.predict(x) ?: Double.NaN)
        }.sortedByDescending { if (useRel) it.beatProb else it.upProb + it.expectedReturn }
    }
}

data class AiRankRow(val asset: Asset, val price: Double, val upProb: Double, val expectedReturn: Double, val beatProb: Double = Double.NaN)
