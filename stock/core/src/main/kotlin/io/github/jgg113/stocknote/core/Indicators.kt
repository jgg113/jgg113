package io.github.jgg113.stocknote.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 기술적 지표 계산. 모두 표준 공식이며, 값이 정해지지 않는 앞부분은 NaN 으로 채운다.
 * 입력 배열은 오래된 봉 → 최근 봉 순서다.
 */
object Indicators {
    private fun nan(n: Int) = DoubleArray(n) { Double.NaN }

    fun sma(x: DoubleArray, n: Int): DoubleArray {
        val out = nan(x.size)
        if (n <= 0) return out
        var sum = 0.0
        var count = 0
        for (i in x.indices) {
            val v = x[i]
            if (v.isNaN()) {
                sum = 0.0; count = 0; continue
            }
            sum += v; count++
            if (count > n) {
                sum -= x[i - n]; count = n
            }
            if (count == n) out[i] = sum / n
        }
        return out
    }

    /** 지수 이동평균. 첫 값은 단순평균으로 시작한다. */
    fun ema(x: DoubleArray, n: Int): DoubleArray = smooth(x, n, 2.0 / (n + 1))

    /** 와일더 평활 (RSI·ATR·ADX 에 쓰는 RMA) */
    fun rma(x: DoubleArray, n: Int): DoubleArray = smooth(x, n, 1.0 / n)

    private fun smooth(x: DoubleArray, n: Int, alpha: Double): DoubleArray {
        val out = nan(x.size)
        if (n <= 0) return out
        var start = 0
        while (start < x.size && x[start].isNaN()) start++
        if (start + n > x.size) return out
        var prev = (start until start + n).sumOf { x[it] } / n
        out[start + n - 1] = prev
        for (i in start + n until x.size) {
            val v = x[i]
            if (v.isNaN()) {
                out[i] = prev; continue
            }
            prev = alpha * v + (1 - alpha) * prev
            out[i] = prev
        }
        return out
    }

    fun wma(x: DoubleArray, n: Int): DoubleArray {
        val out = nan(x.size)
        val denom = n * (n + 1) / 2.0
        for (i in n - 1 until x.size) {
            var s = 0.0
            var ok = true
            for (k in 0 until n) {
                val v = x[i - k]
                if (v.isNaN()) { ok = false; break }
                s += v * (n - k)
            }
            if (ok) out[i] = s / denom
        }
        return out
    }

    fun stdDev(x: DoubleArray, n: Int): DoubleArray {
        val out = nan(x.size)
        val mean = sma(x, n)
        for (i in n - 1 until x.size) {
            val m = mean[i]
            if (m.isNaN()) continue
            var s = 0.0
            for (k in 0 until n) s += (x[i - k] - m).let { it * it }
            out[i] = sqrt(s / n)
        }
        return out
    }

    fun highest(x: DoubleArray, n: Int): DoubleArray {
        val out = nan(x.size)
        for (i in n - 1 until x.size) {
            var m = Double.NEGATIVE_INFINITY
            for (k in 0 until n) m = max(m, x[i - k])
            out[i] = m
        }
        return out
    }

    fun lowest(x: DoubleArray, n: Int): DoubleArray {
        val out = nan(x.size)
        for (i in n - 1 until x.size) {
            var m = Double.POSITIVE_INFINITY
            for (k in 0 until n) m = min(m, x[i - k])
            out[i] = m
        }
        return out
    }

    /** 배열을 [k] 칸 뒤로 민다 (값[i] = x[i-k]). */
    fun shift(x: DoubleArray, k: Int): DoubleArray = DoubleArray(x.size) { i -> if (i - k in x.indices) x[i - k] else Double.NaN }

    fun rsi(close: DoubleArray, n: Int = 14): DoubleArray {
        val gain = DoubleArray(close.size)
        val loss = DoubleArray(close.size)
        gain[0] = Double.NaN; loss[0] = Double.NaN
        for (i in 1 until close.size) {
            val d = close[i] - close[i - 1]
            gain[i] = max(d, 0.0)
            loss[i] = max(-d, 0.0)
        }
        val g = rma(gain, n)
        val l = rma(loss, n)
        return DoubleArray(close.size) { i ->
            when {
                g[i].isNaN() || l[i].isNaN() -> Double.NaN
                l[i] == 0.0 -> if (g[i] == 0.0) 50.0 else 100.0
                else -> 100 - 100 / (1 + g[i] / l[i])
            }
        }
    }

    data class Macd(val line: DoubleArray, val signal: DoubleArray, val hist: DoubleArray)

    fun macd(close: DoubleArray, fast: Int = 12, slow: Int = 26, signal: Int = 9): Macd {
        val f = ema(close, fast)
        val s = ema(close, slow)
        val line = DoubleArray(close.size) { f[it] - s[it] }
        val sig = ema(line, signal)
        return Macd(line, sig, DoubleArray(close.size) { line[it] - sig[it] })
    }

    data class Band(val middle: DoubleArray, val upper: DoubleArray, val lower: DoubleArray)

    fun bollinger(close: DoubleArray, n: Int = 20, k: Double = 2.0): Band {
        val m = sma(close, n)
        val sd = stdDev(close, n)
        return Band(m, DoubleArray(close.size) { m[it] + k * sd[it] }, DoubleArray(close.size) { m[it] - k * sd[it] })
    }

    /** 볼린저 %B (0 = 하단, 1 = 상단) */
    fun percentB(close: DoubleArray, band: Band): DoubleArray = DoubleArray(close.size) { i ->
        val w = band.upper[i] - band.lower[i]
        if (w.isNaN() || w == 0.0) Double.NaN else (close[i] - band.lower[i]) / w
    }

    /** 밴드폭 (상단-하단)/중심 */
    fun bandwidth(band: Band): DoubleArray = DoubleArray(band.middle.size) { i ->
        val m = band.middle[i]
        if (m.isNaN() || m == 0.0) Double.NaN else (band.upper[i] - band.lower[i]) / m
    }

    fun envelope(close: DoubleArray, n: Int = 20, pct: Double = 6.0): Band {
        val m = sma(close, n)
        return Band(m, DoubleArray(close.size) { m[it] * (1 + pct / 100) }, DoubleArray(close.size) { m[it] * (1 - pct / 100) })
    }

    fun donchian(high: DoubleArray, low: DoubleArray, n: Int = 20): Band {
        val u = highest(high, n)
        val l = lowest(low, n)
        return Band(DoubleArray(high.size) { (u[it] + l[it]) / 2 }, u, l)
    }

    fun trueRange(high: DoubleArray, low: DoubleArray, close: DoubleArray): DoubleArray = DoubleArray(close.size) { i ->
        if (i == 0) high[0] - low[0]
        else max(high[i] - low[i], max(abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1])))
    }

    fun atr(high: DoubleArray, low: DoubleArray, close: DoubleArray, n: Int = 14): DoubleArray = rma(trueRange(high, low, close), n)

    fun keltner(high: DoubleArray, low: DoubleArray, close: DoubleArray, n: Int = 20, mult: Double = 2.0, atrN: Int = 10): Band {
        val m = ema(close, n)
        val a = atr(high, low, close, atrN)
        return Band(m, DoubleArray(close.size) { m[it] + mult * a[it] }, DoubleArray(close.size) { m[it] - mult * a[it] })
    }

    data class Stoch(val k: DoubleArray, val d: DoubleArray)

    /** 스토캐스틱 슬로우: fast %K(n) 를 smooth 로 평활한 값이 %K, 그 평균이 %D */
    fun stochastic(high: DoubleArray, low: DoubleArray, close: DoubleArray, n: Int = 14, smooth: Int = 3, d: Int = 3): Stoch {
        val hh = highest(high, n)
        val ll = lowest(low, n)
        val fast = DoubleArray(close.size) { i ->
            val w = hh[i] - ll[i]
            if (w.isNaN()) Double.NaN else if (w == 0.0) 50.0 else (close[i] - ll[i]) / w * 100
        }
        val k = if (smooth > 1) sma(fast, smooth) else fast
        return Stoch(k, sma(k, d))
    }

    fun williamsR(high: DoubleArray, low: DoubleArray, close: DoubleArray, n: Int = 14): DoubleArray {
        val hh = highest(high, n)
        val ll = lowest(low, n)
        return DoubleArray(close.size) { i ->
            val w = hh[i] - ll[i]
            if (w.isNaN()) Double.NaN else if (w == 0.0) -50.0 else (hh[i] - close[i]) / w * -100
        }
    }

    fun cci(high: DoubleArray, low: DoubleArray, close: DoubleArray, n: Int = 20): DoubleArray {
        val tp = DoubleArray(close.size) { (high[it] + low[it] + close[it]) / 3 }
        val m = sma(tp, n)
        return DoubleArray(close.size) { i ->
            if (m[i].isNaN()) return@DoubleArray Double.NaN
            var md = 0.0
            for (k in 0 until n) md += abs(tp[i - k] - m[i])
            md /= n
            if (md == 0.0) 0.0 else (tp[i] - m[i]) / (0.015 * md)
        }
    }

    data class Dmi(val plus: DoubleArray, val minus: DoubleArray, val adx: DoubleArray)

    fun dmi(high: DoubleArray, low: DoubleArray, close: DoubleArray, n: Int = 14): Dmi {
        val size = close.size
        val pdm = DoubleArray(size)
        val mdm = DoubleArray(size)
        pdm[0] = Double.NaN; mdm[0] = Double.NaN
        for (i in 1 until size) {
            val up = high[i] - high[i - 1]
            val down = low[i - 1] - low[i]
            pdm[i] = if (up > down && up > 0) up else 0.0
            mdm[i] = if (down > up && down > 0) down else 0.0
        }
        val tr = trueRange(high, low, close).also { it[0] = Double.NaN }
        val atr = rma(tr, n)
        val p = rma(pdm, n)
        val m = rma(mdm, n)
        val plus = DoubleArray(size) { if (atr[it].isNaN() || atr[it] == 0.0) Double.NaN else p[it] / atr[it] * 100 }
        val minus = DoubleArray(size) { if (atr[it].isNaN() || atr[it] == 0.0) Double.NaN else m[it] / atr[it] * 100 }
        val dx = DoubleArray(size) {
            val s = plus[it] + minus[it]
            if (s.isNaN() || s == 0.0) Double.NaN else abs(plus[it] - minus[it]) / s * 100
        }
        return Dmi(plus, minus, rma(dx, n))
    }

    fun obv(close: DoubleArray, volume: DoubleArray): DoubleArray {
        val out = DoubleArray(close.size)
        for (i in 1 until close.size) {
            out[i] = out[i - 1] + when {
                close[i] > close[i - 1] -> volume[i]
                close[i] < close[i - 1] -> -volume[i]
                else -> 0.0
            }
        }
        return out
    }

    fun mfi(high: DoubleArray, low: DoubleArray, close: DoubleArray, volume: DoubleArray, n: Int = 14): DoubleArray {
        val tp = DoubleArray(close.size) { (high[it] + low[it] + close[it]) / 3 }
        val out = nan(close.size)
        for (i in n until close.size) {
            var pos = 0.0
            var neg = 0.0
            for (k in i - n + 1..i) {
                val flow = tp[k] * volume[k]
                if (tp[k] > tp[k - 1]) pos += flow else if (tp[k] < tp[k - 1]) neg += flow
            }
            out[i] = if (neg == 0.0) (if (pos == 0.0) 50.0 else 100.0) else 100 - 100 / (1 + pos / neg)
        }
        return out
    }

    fun roc(close: DoubleArray, n: Int = 12): DoubleArray = DoubleArray(close.size) { i ->
        if (i < n || close[i - n] == 0.0) Double.NaN else (close[i] / close[i - n] - 1) * 100
    }

    fun momentum(close: DoubleArray, n: Int = 10): DoubleArray = DoubleArray(close.size) { i ->
        if (i < n) Double.NaN else close[i] - close[i - n]
    }

    data class Trix(val line: DoubleArray, val signal: DoubleArray)

    fun trix(close: DoubleArray, n: Int = 15, signal: Int = 9): Trix {
        val e3 = ema(ema(ema(close, n), n), n)
        val line = DoubleArray(close.size) { i ->
            if (i == 0 || e3[i - 1].isNaN() || e3[i - 1] == 0.0) Double.NaN else (e3[i] / e3[i - 1] - 1) * 100
        }
        return Trix(line, ema(line, signal))
    }

    data class Aroon(val up: DoubleArray, val down: DoubleArray)

    fun aroon(high: DoubleArray, low: DoubleArray, n: Int = 25): Aroon {
        val up = nan(high.size)
        val down = nan(high.size)
        for (i in n until high.size) {
            var hi = i; var lo = i
            for (k in i - n..i) {
                if (high[k] >= high[hi]) hi = k
                if (low[k] <= low[lo]) lo = k
            }
            up[i] = (n - (i - hi)).toDouble() / n * 100
            down[i] = (n - (i - lo)).toDouble() / n * 100
        }
        return Aroon(up, down)
    }

    /** 이격도: 종가 / n일 이동평균 × 100 */
    fun disparity(close: DoubleArray, n: Int = 20): DoubleArray {
        val m = sma(close, n)
        return DoubleArray(close.size) { if (m[it].isNaN() || m[it] == 0.0) Double.NaN else close[it] / m[it] * 100 }
    }

    /** 심리도: 최근 n일 중 오른 날의 비율 (%) */
    fun psychological(close: DoubleArray, n: Int = 12): DoubleArray = DoubleArray(close.size) { i ->
        if (i < n) Double.NaN else (i - n + 1..i).count { close[it] > close[it - 1] }.toDouble() / n * 100
    }

    /** 차이킨 머니 플로 */
    fun cmf(high: DoubleArray, low: DoubleArray, close: DoubleArray, volume: DoubleArray, n: Int = 20): DoubleArray {
        val mfv = DoubleArray(close.size) { i ->
            val w = high[i] - low[i]
            if (w == 0.0) 0.0 else ((close[i] - low[i]) - (high[i] - close[i])) / w * volume[i]
        }
        val out = nan(close.size)
        for (i in n - 1 until close.size) {
            var a = 0.0; var v = 0.0
            for (k in i - n + 1..i) { a += mfv[k]; v += volume[k] }
            out[i] = if (v == 0.0) 0.0 else a / v
        }
        return out
    }

    data class Ichimoku(
        val tenkan: DoubleArray,
        val kijun: DoubleArray,
        /** 선행스팬 1·2: 이 봉 자리에 그려지는 값 (n2 봉 전에 계산된 값) */
        val spanA: DoubleArray,
        val spanB: DoubleArray,
        /** 후행스팬: 이 봉 자리에 그려지는 값 (n2 봉 뒤의 종가) */
        val chikou: DoubleArray,
    )

    fun ichimoku(high: DoubleArray, low: DoubleArray, close: DoubleArray, n1: Int = 9, n2: Int = 26, n3: Int = 52): Ichimoku {
        fun mid(n: Int): DoubleArray {
            val h = highest(high, n); val l = lowest(low, n)
            return DoubleArray(close.size) { (h[it] + l[it]) / 2 }
        }
        val tenkan = mid(n1)
        val kijun = mid(n2)
        val a = DoubleArray(close.size) { (tenkan[it] + kijun[it]) / 2 }
        val b = mid(n3)
        return Ichimoku(
            tenkan, kijun, shift(a, n2), shift(b, n2),
            DoubleArray(close.size) { i -> if (i + n2 < close.size) close[i + n2] else Double.NaN },
        )
    }

    data class Sar(val value: DoubleArray, val rising: BooleanArray)

    /** 파라볼릭 SAR */
    fun parabolicSar(high: DoubleArray, low: DoubleArray, step: Double = 0.02, maxStep: Double = 0.2): Sar {
        val size = high.size
        val sar = nan(size)
        val rising = BooleanArray(size)
        if (size < 2) return Sar(sar, rising)
        var up = high[1] >= high[0]
        var ep = if (up) max(high[0], high[1]) else min(low[0], low[1])
        var s = if (up) min(low[0], low[1]) else max(high[0], high[1])
        var af = step
        sar[1] = s; rising[1] = up
        for (i in 2 until size) {
            s += af * (ep - s)
            if (up) {
                s = min(s, min(low[i - 1], low[i - 2]))
                if (low[i] < s) {
                    up = false; s = ep; ep = low[i]; af = step
                } else if (high[i] > ep) {
                    ep = high[i]; af = min(af + step, maxStep)
                }
            } else {
                s = max(s, max(high[i - 1], high[i - 2]))
                if (high[i] > s) {
                    up = true; s = ep; ep = high[i]; af = step
                } else if (low[i] < ep) {
                    ep = low[i]; af = min(af + step, maxStep)
                }
            }
            sar[i] = s; rising[i] = up
        }
        return Sar(sar, rising)
    }

    /** 거래량 / n일 평균 거래량 (오늘 제외) */
    fun volumeRatio(volume: DoubleArray, n: Int = 20): DoubleArray {
        val avg = shift(sma(volume, n), 1)
        return DoubleArray(volume.size) { if (avg[it].isNaN() || avg[it] == 0.0) Double.NaN else volume[it] / avg[it] }
    }

    /** 일간 수익률 */
    fun returns(close: DoubleArray): DoubleArray = DoubleArray(close.size) { i ->
        if (i == 0 || close[i - 1] == 0.0) Double.NaN else close[i] / close[i - 1] - 1
    }
}

/** 두 선의 교차 판정 */
object Cross {
    fun up(a: DoubleArray, b: DoubleArray, i: Int): Boolean =
        i > 0 && ok(a[i], b[i], a[i - 1], b[i - 1]) && a[i - 1] <= b[i - 1] && a[i] > b[i]

    fun down(a: DoubleArray, b: DoubleArray, i: Int): Boolean =
        i > 0 && ok(a[i], b[i], a[i - 1], b[i - 1]) && a[i - 1] >= b[i - 1] && a[i] < b[i]

    fun upLevel(a: DoubleArray, level: Double, i: Int): Boolean =
        i > 0 && !a[i].isNaN() && !a[i - 1].isNaN() && a[i - 1] <= level && a[i] > level

    fun downLevel(a: DoubleArray, level: Double, i: Int): Boolean =
        i > 0 && !a[i].isNaN() && !a[i - 1].isNaN() && a[i - 1] >= level && a[i] < level

    private fun ok(vararg v: Double) = v.none { it.isNaN() }
}

/** 봉 목록에서 가격 배열을 꺼내고, 같은 지표를 여러 전략이 다시 계산하지 않도록 기억해 둔다. */
class PriceData(val candles: List<Candle>) {
    val size: Int = candles.size
    val open = DoubleArray(size) { candles[it].open }
    val high = DoubleArray(size) { candles[it].high }
    val low = DoubleArray(size) { candles[it].low }
    val close = DoubleArray(size) { candles[it].close }
    val volume = DoubleArray(size) { candles[it].volume }

    private val cache = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> memo(key: String, compute: () -> T): T = cache.getOrPut(key, compute) as T

    fun sma(n: Int) = memo("sma$n") { Indicators.sma(close, n) }
    fun ema(n: Int) = memo("ema$n") { Indicators.ema(close, n) }
    fun rsi(n: Int = 14) = memo("rsi$n") { Indicators.rsi(close, n) }
    fun macd(f: Int = 12, s: Int = 26, sig: Int = 9) = memo("macd$f/$s/$sig") { Indicators.macd(close, f, s, sig) }
    fun bollinger(n: Int = 20, k: Double = 2.0) = memo("bb$n/$k") { Indicators.bollinger(close, n, k) }
    fun stochastic(n: Int = 14, s: Int = 3, d: Int = 3) = memo("st$n/$s/$d") { Indicators.stochastic(high, low, close, n, s, d) }
    fun williams(n: Int = 14) = memo("wr$n") { Indicators.williamsR(high, low, close, n) }
    fun cci(n: Int = 20) = memo("cci$n") { Indicators.cci(high, low, close, n) }
    fun dmi(n: Int = 14) = memo("dmi$n") { Indicators.dmi(high, low, close, n) }
    fun atr(n: Int = 14) = memo("atr$n") { Indicators.atr(high, low, close, n) }
    fun obv() = memo("obv") { Indicators.obv(close, volume) }
    fun mfi(n: Int = 14) = memo("mfi$n") { Indicators.mfi(high, low, close, volume, n) }
    fun roc(n: Int = 12) = memo("roc$n") { Indicators.roc(close, n) }
    fun trix(n: Int = 15, s: Int = 9) = memo("trix$n/$s") { Indicators.trix(close, n, s) }
    fun aroon(n: Int = 25) = memo("aroon$n") { Indicators.aroon(high, low, n) }
    fun disparity(n: Int = 20) = memo("disp$n") { Indicators.disparity(close, n) }
    fun psychological(n: Int = 12) = memo("psy$n") { Indicators.psychological(close, n) }
    fun ichimoku() = memo("ichi") { Indicators.ichimoku(high, low, close) }
    fun sar() = memo("sar") { Indicators.parabolicSar(high, low) }
    fun donchian(n: Int) = memo("don$n") { Indicators.donchian(high, low, n) }
    fun keltner(n: Int = 20, m: Double = 2.0) = memo("kc$n/$m") { Indicators.keltner(high, low, close, n, m) }
    fun envelope(n: Int = 20, pct: Double = 10.0) = memo("env$n/$pct") { Indicators.envelope(close, n, pct) }
    fun volumeRatio(n: Int = 20) = memo("vr$n") { Indicators.volumeRatio(volume, n) }
    fun highest(n: Int) = memo("hh$n") { Indicators.highest(high, n) }
    fun lowest(n: Int) = memo("ll$n") { Indicators.lowest(low, n) }
}
