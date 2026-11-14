package io.github.jgg113.stocknote.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AI 예측에 쓰는 특징값. 머신러닝 주가 연구(트리·신경망)에서 공통으로 힘이 컸던 모멘텀·변동성·유동성·추세 계열을
 * 이 앱에서 직접 고른 32개다. 모두 그 봉까지의 값만 쓴다 (미래 정보 없음).
 */
object AiFeatures {
    const val WARMUP = 252

    val names = listOf(
        "1일 수익률", "5일 수익률", "10일 수익률", "20일 수익률", "60일 수익률", "120일 수익률", "250일 수익률", "12-1 모멘텀",
        "5일선 이격", "20일선 이격", "60일선 이격", "120일선 이격", "200일선 이격", "20/60일선 차이",
        "20일 변동성", "60일 변동성", "변동성 비", "60일 범위 위치", "1년 범위 위치", "1년 고가 대비",
        "RSI", "스토캐스틱", "볼린저 %B", "MACD 히스토그램", "ADX",
        "거래량 배수", "거래량 추세", "거래대금 규모", "몸통", "위꼬리", "아래꼬리", "ATR 비율",
    )

    val size: Int get() = names.size

    fun compute(d: PriceData): Array<DoubleArray?> {
        val c = d.close
        val n = d.size
        val sma5 = d.sma(5); val sma20 = d.sma(20); val sma60 = d.sma(60); val sma120 = d.sma(120); val sma200 = d.sma(200)
        val vol20 = d.volatility(20); val vol60 = d.volatility(60)
        val hh60 = d.highest(60); val ll60 = d.lowest(60); val hh250 = d.highest(250); val ll250 = d.lowest(250)
        val rsi = d.rsi(); val st = d.stochastic(); val bb = Indicators.percentB(c, d.bollinger()); val macd = d.macd(); val adx = d.dmi().adx
        val vr = d.volumeRatio()
        val v5 = Indicators.sma(d.volume, 5); val v60 = Indicators.sma(d.volume, 60)
        val value = DoubleArray(n) { i -> d.candles[i].value.takeIf { it > 0 } ?: (c[i] * d.volume[i]) }
        val value20 = Indicators.sma(value, 20)
        val atr = d.atr()
        return Array(n) { i ->
            if (i < WARMUP) return@Array null
            fun ret(k: Int) = c[i] / c[i - k] - 1
            val x = doubleArrayOf(
                ret(1), ret(5), ret(10), ret(20), ret(60), ret(120), ret(250), c[i - 21] / c[i - 250] - 1,
                c[i] / sma5[i] - 1, c[i] / sma20[i] - 1, c[i] / sma60[i] - 1, c[i] / sma120[i] - 1, c[i] / sma200[i] - 1, sma20[i] / sma60[i] - 1,
                vol20[i], vol60[i], vol20[i] / vol60[i],
                (c[i] - ll60[i]) / (hh60[i] - ll60[i]).takeIf { it > 0 }.orOne(), (c[i] - ll250[i]) / (hh250[i] - ll250[i]).takeIf { it > 0 }.orOne(), c[i] / hh250[i] - 1,
                rsi[i] / 100, st.k[i] / 100, bb[i], macd.hist[i] / c[i], adx[i] / 100,
                ln(vr[i].coerceIn(0.02, 50.0)), ln((v5[i] / v60[i]).coerceIn(0.02, 50.0)), ln(value20[i] + 1.0) / 30,
                (c[i] - d.open[i]) / c[i], (d.high[i] - max(d.open[i], c[i])) / c[i], (min(d.open[i], c[i]) - d.low[i]) / c[i], atr[i] / c[i],
            )
            if (x.any { it.isNaN() || it.isInfinite() }) null else x
        }
    }

    private fun Double?.orOne() = this ?: 1.0

    const val SEQ_STEPS = 20
    const val SEQ_INPUTS = 4

    /**
     * 순환신경망용 입력: 위치 [t] 까지 최근 [SEQ_STEPS] 일의 (수익률, 고저 폭, 몸통, 거래량 배수).
     * 가격 변화는 그 시점의 60일 변동성으로 나눠 종목마다 크기가 비슷해지게 한다.
     */
    fun sequence(d: PriceData, t: Int): FloatArray? {
        if (t < WARMUP) return null
        val vol = d.volatility(60)[t]
        if (vol.isNaN() || vol < 1e-5) return null
        val vr = d.volumeRatio()
        val out = FloatArray(SEQ_STEPS * SEQ_INPUTS)
        for (k in 0 until SEQ_STEPS) {
            val i = t - SEQ_STEPS + 1 + k
            val c = d.close[i]
            val o = k * SEQ_INPUTS
            out[o] = ((c / d.close[i - 1] - 1) / vol).coerceIn(-5.0, 5.0).toFloat()
            out[o + 1] = ((d.high[i] - d.low[i]) / c / vol).coerceIn(0.0, 8.0).toFloat() - 1.5f
            out[o + 2] = ((c - d.open[i]) / c / vol).coerceIn(-5.0, 5.0).toFloat()
            out[o + 3] = ln(vr[i].takeIf { !it.isNaN() }?.coerceIn(0.05, 20.0) ?: 1.0).toFloat()
        }
        return out
    }
}

/** 특징값 표준화 (학습 자료의 평균·표준편차로) */
class Scaler(val mean: DoubleArray, val sd: DoubleArray) {
    fun apply(x: DoubleArray): DoubleArray = DoubleArray(x.size) { j -> ((x[j] - mean[j]) / sd[j]).coerceIn(-5.0, 5.0) }

    companion object {
        fun fit(rows: List<DoubleArray>): Scaler {
            val f = rows.first().size
            val mean = DoubleArray(f) { j -> rows.sumOf { it[j] } / rows.size }
            val sd = DoubleArray(f) { j -> sqrt(rows.sumOf { (it[j] - mean[j]).let { v -> v * v } } / rows.size).takeIf { it > 1e-12 } ?: 1.0 }
            return Scaler(mean, sd)
        }
    }
}

fun sigmoid(z: Double): Double = if (z >= 0) 1 / (1 + exp(-z)) else exp(z).let { it / (1 + it) }

/** 특징값을 분위수 구간 번호로 바꾼다 (트리 모델들이 함께 쓴다). */
class Binner(val thresholds: Array<DoubleArray>) {
    fun bin(j: Int, v: Double): Int {
        val t = thresholds[j]
        var lo = 0; var hi = t.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (t[mid] < v) lo = mid + 1 else hi = mid }
        return lo
    }

    fun binAll(x: List<DoubleArray>): Array<ByteArray> = Array(thresholds.size) { j -> ByteArray(x.size) { i -> bin(j, x[i][j]).toByte() } }

    companion object {
        fun fit(x: List<DoubleArray>, bins: Int, random: Random): Binner {
            val n = x.size
            val f = x.first().size
            val sampleIdx = if (n > 20_000) (0 until 20_000).map { random.nextInt(n) } else (0 until n).toList()
            return Binner(Array(f) { j ->
                val v = sampleIdx.map { x[it][j] }.sorted()
                (1 until bins).map { k -> v[(k * (v.size - 1)) / bins] }.distinct().toDoubleArray()
            })
        }
    }
}

/** 결정트리 하나 (구간 번호 기준으로 갈라진다) */
class Tree(val feature: IntArray, val bin: IntArray, val left: IntArray, val right: IntArray, val value: DoubleArray) {
    fun leaf(binner: Binner, x: DoubleArray): Double {
        var node = 0
        while (feature[node] >= 0) node = if (binner.bin(feature[node], x[feature[node]]) <= bin[node]) left[node] else right[node]
        return value[node]
    }

    fun leafBinned(binned: Array<ByteArray>, i: Int): Double {
        var node = 0
        while (feature[node] >= 0) node = if (binned[feature[node]][i] <= bin[node]) left[node] else right[node]
        return value[node]
    }
}

/** 기울기(g)·곡률(h) 히스토그램으로 트리 하나를 키운다. 잎 값 = -Σg / (Σh + λ) × rate */
internal class TreeGrower(
    private val binned: Array<ByteArray>, private val nb: Int, private val depth: Int, private val minLeaf: Int,
    private val lambda: Double, private val rate: Double,
) {
    /** [featuresAt] 은 마디마다 볼 특징 목록을 준다 (랜덤 포레스트는 마디마다 다르게 뽑는다). */
    fun grow(g: DoubleArray, h: DoubleArray, rows: IntArray, featuresAt: () -> IntArray): Tree {
        val feature = ArrayList<Int>(); val bin = ArrayList<Int>(); val left = ArrayList<Int>(); val right = ArrayList<Int>(); val value = ArrayList<Double>()
        fun newNode(): Int { feature += -1; bin += 0; left += -1; right += -1; value += 0.0; return feature.size - 1 }
        fun leafValue(idx: IntArray): Double { var sg = 0.0; var sh = 0.0; for (i in idx) { sg += g[i]; sh += h[i] }; return -sg / (sh + lambda) * rate }
        val sg = DoubleArray(nb); val sh = DoubleArray(nb); val cnt = IntArray(nb)
        fun split(node: Int, idx: IntArray, level: Int) {
            if (level >= depth || idx.size < minLeaf * 2) { value[node] = leafValue(idx); return }
            var gTot = 0.0; var hTot = 0.0
            for (i in idx) { gTot += g[i]; hTot += h[i] }
            val parent = gTot * gTot / (hTot + lambda)
            var bestGain = 1e-9; var bestF = -1; var bestB = -1
            for (j in featuresAt()) {
                java.util.Arrays.fill(sg, 0.0); java.util.Arrays.fill(sh, 0.0); java.util.Arrays.fill(cnt, 0)
                val col = binned[j]
                for (i in idx) { val b = col[i].toInt(); sg[b] += g[i]; sh[b] += h[i]; cnt[b]++ }
                var gl = 0.0; var hl = 0.0; var cl = 0
                for (b in 0 until nb - 1) {
                    gl += sg[b]; hl += sh[b]; cl += cnt[b]
                    val cr = idx.size - cl
                    if (cl < minLeaf || cr < minLeaf) continue
                    val gr = gTot - gl; val hr = hTot - hl
                    val gain = gl * gl / (hl + lambda) + gr * gr / (hr + lambda) - parent
                    if (gain > bestGain) { bestGain = gain; bestF = j; bestB = b }
                }
            }
            if (bestF < 0) { value[node] = leafValue(idx); return }
            val col = binned[bestF]
            var nl = 0
            for (i in idx) if (col[i] <= bestB) nl++
            val l = IntArray(nl); val r = IntArray(idx.size - nl)
            var a = 0; var b = 0
            for (i in idx) if (col[i] <= bestB) l[a++] = i else r[b++] = i
            feature[node] = bestF; bin[node] = bestB
            val ln = newNode(); val rn = newNode()
            left[node] = ln; right[node] = rn
            split(ln, l, level + 1); split(rn, r, level + 1)
        }
        val root = newNode()
        split(root, rows, 0)
        return Tree(feature.toIntArray(), bin.toIntArray(), left.toIntArray(), right.toIntArray(), value.toDoubleArray())
    }
}

/**
 * 히스토그램 기반 그래디언트 부스팅 트리 (LightGBM 과 같은 방식을 작게 구현).
 * [logistic] 이 참이면 오를 확률, 거짓이면 수익률을 맞춘다.
 */
class Gbdt private constructor(private val binner: Binner, private val base: Double, private val trees: List<Tree>, private val logistic: Boolean) {
    fun raw(x: DoubleArray): Double {
        var s = base
        for (tree in trees) s += tree.leaf(binner, x)
        return s
    }

    fun predict(x: DoubleArray): Double = if (logistic) sigmoid(raw(x)) else raw(x)

    /** 특징별 쓰인 횟수 (어떤 값이 예측에 많이 쓰였는지) */
    fun importance(features: Int): IntArray {
        val out = IntArray(features)
        trees.forEach { t -> t.feature.forEach { f -> if (f >= 0) out[f]++ } }
        return out
    }

    companion object {
        fun fit(
            x: List<DoubleArray>, y: DoubleArray, logistic: Boolean, random: Random,
            trees: Int = 80, depth: Int = 3, rate: Double = 0.08, minLeaf: Int = 40, lambda: Double = 1.0,
            rowSample: Double = 0.7, featureSample: Double = 0.8, bins: Int = 32,
        ): Gbdt {
            val n = x.size
            val f = x.first().size
            val binner = Binner.fit(x, bins, random)
            val binned = binner.binAll(x)
            val grower = TreeGrower(binned, bins + 1, depth, minLeaf, lambda, rate)
            val base = if (logistic) {
                val p = y.average().coerceIn(0.02, 0.98); ln(p / (1 - p))
            } else y.average()
            val pred = DoubleArray(n) { base }
            val g = DoubleArray(n); val h = DoubleArray(n)
            val out = ArrayList<Tree>()
            repeat(trees) {
                for (i in 0 until n) {
                    if (logistic) { val p = sigmoid(pred[i]); g[i] = p - y[i]; h[i] = max(p * (1 - p), 1e-6) } else { g[i] = pred[i] - y[i]; h[i] = 1.0 }
                }
                val rows = (0 until n).filter { random.nextDouble() < rowSample }.toIntArray()
                val feats = (0 until f).filter { random.nextDouble() < featureSample }.ifEmpty { listOf(random.nextInt(f)) }.toIntArray()
                val tree = grower.grow(g, h, rows) { feats }
                out += tree
                for (i in 0 until n) pred[i] += tree.leafBinned(binned, i)
            }
            return Gbdt(binner, base, out, logistic)
        }
    }
}

/**
 * 랜덤 포레스트: 표본을 다시 뽑고(부트스트랩) 마디마다 특징 일부만 보며 깊은 트리를 여러 개 키워 평균한다.
 * 잎 값은 그 잎에 든 표본 중 오른 비율이다.
 */
class RandomForest private constructor(private val binner: Binner, private val trees: List<Tree>, private val prior: Double) {
    fun predict(x: DoubleArray): Double = if (trees.isEmpty()) prior else trees.sumOf { it.leaf(binner, x) } / trees.size

    companion object {
        fun fit(
            x: List<DoubleArray>, y: DoubleArray, random: Random,
            trees: Int = 60, depth: Int = 6, minLeaf: Int = 30, mtry: Int = 0, bins: Int = 32,
        ): RandomForest {
            val n = x.size
            val f = x.first().size
            val m = if (mtry > 0) mtry else sqrt(f.toDouble()).toInt().coerceAtLeast(2)
            val binner = Binner.fit(x, bins, random)
            val binned = binner.binAll(x)
            // g = -y, h = 1, λ = 0 이면 잎 값이 그 잎의 평균 y 가 된다.
            val g = DoubleArray(n) { -y[it] }
            val h = DoubleArray(n) { 1.0 }
            val grower = TreeGrower(binned, bins + 1, depth, minLeaf, 0.0, 1.0)
            val all = IntArray(f) { it }
            val out = ArrayList<Tree>()
            repeat(trees) {
                val rows = IntArray(n) { random.nextInt(n) }
                out += grower.grow(g, h, rows) {
                    // 마디마다 특징 m 개를 무작위로 고른다 (부분 섞기)
                    for (k in 0 until m) { val s = k + random.nextInt(f - k); val tmp = all[k]; all[k] = all[s]; all[s] = tmp }
                    all.copyOf(m)
                }
            }
            return RandomForest(binner, out, y.average())
        }
    }
}


/** 선형 모델: 로지스틱 회귀(오를 확률) 또는 릿지 회귀(수익률). L2 규제를 둔다. */
class LinearModel private constructor(private val w: DoubleArray, private val logistic: Boolean) {
    fun predict(x: DoubleArray): Double {
        var s = w[0]
        for (j in x.indices) s += w[j + 1] * x[j]
        return if (logistic) sigmoid(s) else s
    }

    companion object {
        /** 연립방정식 풀기 (가우스 소거, 부분 피벗) */
        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val n = b.size
            for (col in 0 until n) {
                var piv = col
                for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
                val t = a[col]; a[col] = a[piv]; a[piv] = t
                val tb = b[col]; b[col] = b[piv]; b[piv] = tb
                val d = a[col][col].takeIf { abs(it) > 1e-12 } ?: 1e-12
                for (r in col + 1 until n) {
                    val k = a[r][col] / d
                    if (k == 0.0) continue
                    for (c in col until n) a[r][c] -= k * a[col][c]
                    b[r] -= k * b[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                var s = b[r]
                for (c in r + 1 until n) s -= a[r][c] * x[c]
                x[r] = s / (a[r][r].takeIf { abs(it) > 1e-12 } ?: 1e-12)
            }
            return x
        }

        private fun normalEquations(x: List<DoubleArray>, weights: DoubleArray?, target: DoubleArray, lambda: Double): DoubleArray {
            val p = x.first().size + 1
            val a = Array(p) { DoubleArray(p) }
            val b = DoubleArray(p)
            x.forEachIndexed { i, row ->
                val wi = weights?.get(i) ?: 1.0
                for (r in 0 until p) {
                    val xr = if (r == 0) 1.0 else row[r - 1]
                    b[r] += wi * xr * target[i]
                    for (c in r until p) a[r][c] += wi * xr * (if (c == 0) 1.0 else row[c - 1])
                }
            }
            for (r in 0 until p) { for (c in 0 until r) a[r][c] = a[c][r]; if (r > 0) a[r][r] += lambda }
            return solve(a, b)
        }

        fun ridge(x: List<DoubleArray>, y: DoubleArray, lambda: Double = 50.0): LinearModel = LinearModel(normalEquations(x, null, y, lambda), false)

        /** 반복 재가중 최소제곱(IRLS)으로 로지스틱 회귀를 푼다. */
        fun logistic(x: List<DoubleArray>, y: DoubleArray, lambda: Double = 20.0, iterations: Int = 8): LinearModel {
            val p = x.first().size + 1
            var w = DoubleArray(p)
            repeat(iterations) {
                val weights = DoubleArray(x.size)
                val z = DoubleArray(x.size)
                x.forEachIndexed { i, row ->
                    var s = w[0]; for (j in row.indices) s += w[j + 1] * row[j]
                    val pr = sigmoid(s).coerceIn(1e-4, 1 - 1e-4)
                    weights[i] = pr * (1 - pr)
                    z[i] = s + (y[i] - pr) / weights[i]
                }
                w = normalEquations(x, weights, z, lambda)
            }
            return LinearModel(w, true)
        }
    }
}

/** 작은 신경망 (은닉층 2개, ReLU, Adam) — 오를 확률을 맞춘다. */
class Mlp private constructor(private val w1: Array<DoubleArray>, private val b1: DoubleArray, private val w2: Array<DoubleArray>, private val b2: DoubleArray, private val w3: DoubleArray, private var b3: Double) {
    fun predict(x: DoubleArray): Double {
        val h1 = DoubleArray(b1.size) { k -> var s = b1[k]; for (j in x.indices) s += w1[k][j] * x[j]; max(0.0, s) }
        val h2 = DoubleArray(b2.size) { k -> var s = b2[k]; for (j in h1.indices) s += w2[k][j] * h1[j]; max(0.0, s) }
        var s = b3; for (k in h2.indices) s += w3[k] * h2[k]
        return sigmoid(s)
    }

    companion object {
        fun fit(x: List<DoubleArray>, y: DoubleArray, random: Random, hidden1: Int = 16, hidden2: Int = 8, epochs: Int = 12, batch: Int = 128, lr: Double = 0.003, l2: Double = 1e-4): Mlp {
            val f = x.first().size
            fun init(fanIn: Int) = random.nextDouble(-1.0, 1.0) * sqrt(2.0 / fanIn)
            val w1 = Array(hidden1) { DoubleArray(f) { init(f) } }; val b1 = DoubleArray(hidden1)
            val w2 = Array(hidden2) { DoubleArray(hidden1) { init(hidden1) } }; val b2 = DoubleArray(hidden2)
            val w3 = DoubleArray(hidden2) { init(hidden2) }; var b3 = 0.0
            // Adam 상태
            val m1 = Array(hidden1) { DoubleArray(f) }; val v1 = Array(hidden1) { DoubleArray(f) }
            val m2 = Array(hidden2) { DoubleArray(hidden1) }; val v2 = Array(hidden2) { DoubleArray(hidden1) }
            val mb1 = DoubleArray(hidden1); val vb1 = DoubleArray(hidden1); val mb2 = DoubleArray(hidden2); val vb2 = DoubleArray(hidden2)
            val m3 = DoubleArray(hidden2); val v3 = DoubleArray(hidden2); var mb3 = 0.0; var vb3 = 0.0
            val beta1 = 0.9; val beta2 = 0.999; val eps = 1e-8
            var step = 0
            val order = (x.indices).toMutableList()
            val gw1 = Array(hidden1) { DoubleArray(f) }; val gb1 = DoubleArray(hidden1)
            val gw2 = Array(hidden2) { DoubleArray(hidden1) }; val gb2 = DoubleArray(hidden2)
            val gw3 = DoubleArray(hidden2)
            fun adam(p: Double, g: Double, m: Double, v: Double, set: (Double, Double, Double) -> Unit) {
                val mm = beta1 * m + (1 - beta1) * g
                val vv = beta2 * v + (1 - beta2) * g * g
                val mh = mm / (1 - Math.pow(beta1, step.toDouble())); val vh = vv / (1 - Math.pow(beta2, step.toDouble()))
                set(p - lr * mh / (sqrt(vh) + eps), mm, vv)
            }
            repeat(epochs) {
                order.shuffle(random)
                order.chunked(batch).forEach { chunk ->
                    gw1.forEach { java.util.Arrays.fill(it, 0.0) }; java.util.Arrays.fill(gb1, 0.0)
                    gw2.forEach { java.util.Arrays.fill(it, 0.0) }; java.util.Arrays.fill(gb2, 0.0)
                    java.util.Arrays.fill(gw3, 0.0); var gb3 = 0.0
                    for (i in chunk) {
                        val xi = x[i]
                        val z1 = DoubleArray(hidden1) { k -> var s = b1[k]; for (j in 0 until f) s += w1[k][j] * xi[j]; s }
                        val h1 = DoubleArray(hidden1) { max(0.0, z1[it]) }
                        val z2 = DoubleArray(hidden2) { k -> var s = b2[k]; for (j in 0 until hidden1) s += w2[k][j] * h1[j]; s }
                        val h2 = DoubleArray(hidden2) { max(0.0, z2[it]) }
                        var s3 = b3; for (k in 0 until hidden2) s3 += w3[k] * h2[k]
                        val d3 = sigmoid(s3) - y[i]
                        gb3 += d3
                        val d2 = DoubleArray(hidden2) { k -> gw3[k] += d3 * h2[k]; if (z2[k] > 0) d3 * w3[k] else 0.0 }
                        val d1 = DoubleArray(hidden1)
                        for (k in 0 until hidden2) {
                            if (d2[k] == 0.0) continue
                            gb2[k] += d2[k]
                            for (j in 0 until hidden1) { gw2[k][j] += d2[k] * h1[j]; d1[j] += d2[k] * w2[k][j] }
                        }
                        for (k in 0 until hidden1) {
                            if (z1[k] <= 0) continue
                            gb1[k] += d1[k]
                            for (j in 0 until f) gw1[k][j] += d1[k] * xi[j]
                        }
                    }
                    val sz = chunk.size.toDouble()
                    step++
                    for (k in 0 until hidden1) {
                        for (j in 0 until f) adam(w1[k][j], gw1[k][j] / sz + l2 * w1[k][j], m1[k][j], v1[k][j]) { p, m, v -> w1[k][j] = p; m1[k][j] = m; v1[k][j] = v }
                        adam(b1[k], gb1[k] / sz, mb1[k], vb1[k]) { p, m, v -> b1[k] = p; mb1[k] = m; vb1[k] = v }
                    }
                    for (k in 0 until hidden2) {
                        for (j in 0 until hidden1) adam(w2[k][j], gw2[k][j] / sz + l2 * w2[k][j], m2[k][j], v2[k][j]) { p, m, v -> w2[k][j] = p; m2[k][j] = m; v2[k][j] = v }
                        adam(b2[k], gb2[k] / sz, mb2[k], vb2[k]) { p, m, v -> b2[k] = p; mb2[k] = m; vb2[k] = v }
                        adam(w3[k], gw3[k] / sz + l2 * w3[k], m3[k], v3[k]) { p, m, v -> w3[k] = p; m3[k] = m; v3[k] = v }
                    }
                    adam(b3, gb3 / sz, mb3, vb3) { p, m, v -> b3 = p; mb3 = m; vb3 = v }
                }
            }
            return Mlp(w1, b1, w2, b2, w3, b3)
        }
    }
}

/**
 * GRU 순환신경망: 최근 며칠의 수익률·변동폭·거래량 흐름을 차례로 읽어 오를 확률을 낸다.
 * (LSTM 계열이 일간 수익률 순서 자료에서 트리·일반 신경망보다 나았다는 연구를 따라 넣은 작은 버전.)
 */
class Gru internal constructor(val inputs: Int, val hidden: Int, val steps: Int, internal val p: DoubleArray) {
    // 매개변수 배치: Wi[3H×I] (r,z,n 순), Wh[3H×H], bi[3H], bhn[H], wo[H], bo
    private val oWi = 0
    private val oWh = oWi + 3 * hidden * inputs
    private val oBi = oWh + 3 * hidden * hidden
    private val oBhn = oBi + 3 * hidden
    private val oWo = oBhn + hidden
    private val oBo = oWo + hidden

    fun predict(seq: FloatArray): Double = forward(seq, null)

    private class Tape(steps: Int, hidden: Int) {
        val h = Array(steps + 1) { DoubleArray(hidden) }
        val r = Array(steps) { DoubleArray(hidden) }; val z = Array(steps) { DoubleArray(hidden) }
        val n = Array(steps) { DoubleArray(hidden) }; val an = Array(steps) { DoubleArray(hidden) }
    }

    private fun forward(seq: FloatArray, tape: Tape?): Double {
        val H = hidden; val I = inputs
        var h = DoubleArray(H)
        val hNew = DoubleArray(H)
        for (t in 0 until steps) {
            val xo = t * I
            for (j in 0 until H) {
                var ar = p[oBi + j]; var az = p[oBi + H + j]; var ai = p[oBi + 2 * H + j]; var ah = p[oBhn + j]
                for (i in 0 until I) {
                    val x = seq[xo + i]
                    ar += p[oWi + j * I + i] * x; az += p[oWi + (H + j) * I + i] * x; ai += p[oWi + (2 * H + j) * I + i] * x
                }
                for (k in 0 until H) {
                    val hk = h[k]
                    ar += p[oWh + j * H + k] * hk; az += p[oWh + (H + j) * H + k] * hk; ah += p[oWh + (2 * H + j) * H + k] * hk
                }
                val r = sigmoid(ar); val z = sigmoid(az)
                val n = kotlin.math.tanh(ai + r * ah)
                hNew[j] = (1 - z) * n + z * h[j]
                if (tape != null) { tape.r[t][j] = r; tape.z[t][j] = z; tape.n[t][j] = n; tape.an[t][j] = ah }
            }
            if (tape != null) { System.arraycopy(h, 0, tape.h[t], 0, H); System.arraycopy(hNew, 0, tape.h[t + 1], 0, H) }
            h = hNew.copyOf()
        }
        var out = p[oBo]
        for (j in 0 until H) out += p[oWo + j] * h[j]
        return sigmoid(out)
    }

    /** 표본 하나의 손실(교차 엔트로피)을 돌려주고 기울기를 [grad] 에 더한다. */
    internal fun lossAndGrad(seq: FloatArray, y: Double, grad: DoubleArray): Double {
        val H = hidden; val I = inputs
        val tape = Tape(steps, H)
        val prob = forward(seq, tape).coerceIn(1e-9, 1 - 1e-9)
        val dOut = prob - y
        grad[oBo] += dOut
        var dh = DoubleArray(H) { j -> grad[oWo + j] += dOut * tape.h[steps][j]; dOut * p[oWo + j] }
        for (t in steps - 1 downTo 0) {
            val hPrev = tape.h[t]
            val xo = t * I
            val dPrev = DoubleArray(H)
            for (j in 0 until H) {
                val r = tape.r[t][j]; val z = tape.z[t][j]; val n = tape.n[t][j]
                val dnPre = dh[j] * (1 - z) * (1 - n * n)
                val dzPre = dh[j] * (hPrev[j] - n) * z * (1 - z)
                val drPre = dnPre * tape.an[t][j] * r * (1 - r)
                val dnh = dnPre * r
                dPrev[j] += dh[j] * z
                grad[oBi + j] += drPre; grad[oBi + H + j] += dzPre; grad[oBi + 2 * H + j] += dnPre; grad[oBhn + j] += dnh
                for (i in 0 until I) {
                    val x = seq[xo + i]
                    grad[oWi + j * I + i] += drPre * x; grad[oWi + (H + j) * I + i] += dzPre * x; grad[oWi + (2 * H + j) * I + i] += dnPre * x
                }
                for (k in 0 until H) {
                    val hk = hPrev[k]
                    grad[oWh + j * H + k] += drPre * hk; grad[oWh + (H + j) * H + k] += dzPre * hk; grad[oWh + (2 * H + j) * H + k] += dnh * hk
                    dPrev[k] += p[oWh + j * H + k] * drPre + p[oWh + (H + j) * H + k] * dzPre + p[oWh + (2 * H + j) * H + k] * dnh
                }
            }
            dh = dPrev
        }
        return -(y * ln(prob) + (1 - y) * ln(1 - prob))
    }

    companion object {
        fun paramCount(inputs: Int, hidden: Int) = 3 * hidden * inputs + 3 * hidden * hidden + 3 * hidden + hidden + hidden + 1

        internal fun init(inputs: Int, hidden: Int, steps: Int, random: Random): Gru {
            val scale = 1.0 / sqrt(hidden.toDouble())
            return Gru(inputs, hidden, steps, DoubleArray(paramCount(inputs, hidden)) { random.nextDouble(-scale, scale) })
        }

        fun fit(
            seqs: List<FloatArray>, y: DoubleArray, inputs: Int, steps: Int, random: Random,
            hidden: Int = 10, epochs: Int = 6, batch: Int = 64, lr: Double = 0.01, maxSamples: Int = 5000,
        ): Gru {
            val model = init(inputs, hidden, steps, random)
            val n = model.p.size
            val m = DoubleArray(n); val v = DoubleArray(n); val grad = DoubleArray(n)
            // 표본이 많으면 최근 것 위주로 줄인다 (휴대폰에서 시간이 오래 걸리지 않게).
            val order = (seqs.indices).toMutableList().let { if (it.size > maxSamples) it.takeLast(maxSamples).toMutableList() else it }
            var step = 0
            repeat(epochs) {
                order.shuffle(random)
                for (chunk in order.chunked(batch)) {
                    java.util.Arrays.fill(grad, 0.0)
                    for (i in chunk) model.lossAndGrad(seqs[i], y[i], grad)
                    step++
                    val c1 = 1 - Math.pow(0.9, step.toDouble()); val c2 = 1 - Math.pow(0.999, step.toDouble())
                    for (k in 0 until n) {
                        val gk = (grad[k] / chunk.size).coerceIn(-1.0, 1.0)
                        m[k] = 0.9 * m[k] + 0.1 * gk
                        v[k] = 0.999 * v[k] + 0.001 * gk * gk
                        model.p[k] -= lr * (m[k] / c1) / (sqrt(v[k] / c2) + 1e-8)
                    }
                }
            }
            return model
        }
    }
}

/** 검증 지표 계산 */
object Metrics {
    /** AUC: 오른 사례의 점수가 내린 사례보다 높을 확률 (0.5 = 맞히는 힘 없음) */
    fun auc(score: DoubleArray, up: BooleanArray): Double {
        val idx = score.indices.sortedBy { score[it] }
        var rankSum = 0.0
        var pos = 0
        var i = 0
        while (i < idx.size) {
            var j = i
            while (j + 1 < idx.size && score[idx[j + 1]] == score[idx[i]]) j++
            val avgRank = (i + j) / 2.0 + 1
            for (k in i..j) if (up[idx[k]]) { rankSum += avgRank; pos++ }
            i = j + 1
        }
        val neg = idx.size - pos
        if (pos == 0 || neg == 0) return 0.5
        return (rankSum - pos * (pos + 1) / 2.0) / (pos.toDouble() * neg)
    }

    /** 순위 상관 (Spearman) */
    fun rankIc(a: DoubleArray, b: DoubleArray): Double {
        if (a.size < 3) return 0.0
        fun ranks(v: DoubleArray): DoubleArray {
            val idx = v.indices.sortedBy { v[it] }
            val r = DoubleArray(v.size)
            idx.forEachIndexed { rank, i -> r[i] = rank.toDouble() }
            return r
        }
        val ra = ranks(a); val rb = ranks(b)
        val ma = ra.average(); val mb = rb.average()
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in ra.indices) { num += (ra[i] - ma) * (rb[i] - mb); da += (ra[i] - ma).let { it * it }; db += (rb[i] - mb).let { it * it } }
        return if (da == 0.0 || db == 0.0) 0.0 else num / sqrt(da * db)
    }

    fun accuracy(prob: DoubleArray, up: BooleanArray, threshold: Double = 0.5): Double =
        if (prob.isEmpty()) 0.0 else prob.indices.count { (prob[it] > threshold) == up[it] }.toDouble() / prob.size

    fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val pos = (sorted.size - 1) * q
        val lo = pos.toInt(); val hi = min(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
