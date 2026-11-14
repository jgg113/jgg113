package io.github.jgg113.stocknote.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** 예상 기간 하나의 결과 */
data class Horizon(
    val bars: Int,
    /** 비슷했던 과거 장면들의 이후 수익률 중앙값 (%) */
    val medianPct: Double,
    val meanPct: Double,
    /** 오른 비율 (%) */
    val upProbPct: Double,
    /** 같은 종목 전체 기간에서 그만큼 지나 오른 비율 (%) — 비교 기준 */
    val baseUpProbPct: Double,
    val p10Pct: Double,
    val p25Pct: Double,
    val p75Pct: Double,
    val p90Pct: Double,
    /** 과거에 같은 방법으로 맞혔던 비율 (%) — 검증 데이터가 모자라면 NaN */
    val hitRatePct: Double,
    val tested: Int,
) {
    fun price(base: Double, pct: Double) = base * (1 + pct / 100)
}

enum class Opinion(val label: String, val tone: Int) {
    STRONG_POSITIVE("긍정적", 2),
    POSITIVE("다소 긍정적", 1),
    NEUTRAL("중립", 0),
    NEGATIVE("다소 부정적", -1),
    STRONG_NEGATIVE("부정적", -2),
}

/** 점수 하나와 그 이유 */
data class ScoreItem(val name: String, val score: Int, val reason: String)

data class ForecastReport(
    val lastPrice: Double,
    val lastTime: Long,
    val horizons: List<Horizon>,
    val neighbors: Int,
    /** 0~100 */
    val score: Int,
    val opinion: Opinion,
    val items: List<ScoreItem>,
    val notes: List<String>,
) {
    val short: Horizon? get() = horizons.firstOrNull()
    val main: Horizon? get() = horizons.getOrNull(1) ?: horizons.firstOrNull()
}

/**
 * 'AI 예측': 지금과 지표 상태가 가장 비슷했던 과거 장면(최근접 이웃 k개)을 같은 종목 기록에서 찾아,
 * 그 뒤 실제로 어떻게 움직였는지를 모아 앞으로의 범위를 추정한다. 같은 방법을 과거 시점에 다시 적용해 적중률도 잰다.
 * 통계적 참고값일 뿐이며 미래를 보장하지 않는다.
 */
object Forecaster {
    const val MIN_BARS = 160
    private const val WARMUP = 60

    private fun features(d: PriceData): Array<DoubleArray?> {
        val c = d.close
        val rsi = d.rsi()
        val bb = Indicators.percentB(c, d.bollinger())
        val macd = d.macd()
        val vr = d.volumeRatio()
        val hh = d.highest(60)
        val ret = Indicators.returns(c)
        val vol = Indicators.stdDev(ret, 20)
        val m20 = d.sma(20)
        val m60 = d.sma(60)
        return Array(d.size) { i ->
            if (i < WARMUP) return@Array null
            val f = doubleArrayOf(
                c[i] / c[i - 5] - 1,
                c[i] / c[i - 20] - 1,
                c[i] / c[i - 60] - 1,
                rsi[i] / 100,
                bb[i],
                macd.hist[i] / c[i],
                ln(vr[i].coerceIn(0.05, 50.0)),
                c[i] / hh[i] - 1,
                vol[i],
                c[i] / m20[i] - 1,
                m20[i] / m60[i] - 1,
            )
            if (f.any { it.isNaN() || it.isInfinite() }) null else f
        }
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val pos = (sorted.size - 1) * q
        val lo = pos.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    /** [target] 시점의 이웃 [k] 개 이후 수익률. 이웃은 target - bars 이전에서만 고른다 (미래를 엿보지 않게). */
    private fun neighborsReturns(
        feats: Array<DoubleArray?>, mean: DoubleArray, sd: DoubleArray, close: DoubleArray, target: Int, bars: Int, k: Int,
    ): List<Double> {
        val ft = feats[target] ?: return emptyList()
        val dist = ArrayList<Pair<Double, Int>>()
        for (t in WARMUP until target - bars) {
            val f = feats[t] ?: continue
            var s = 0.0
            for (j in f.indices) {
                val z = (f[j] - ft[j]) / sd[j]
                s += z * z
            }
            dist += s to t
        }
        if (dist.size < k) return emptyList()
        dist.sortBy { it.first }
        return dist.take(k).map { (_, t) -> close[t + bars] / close[t] - 1 }
    }

    fun forecast(candles: List<Candle>, horizons: List<Int> = listOf(5, 20), k: Int = 30, report: IndicatorReport? = null, quote: Quote? = null): ForecastReport? {
        if (candles.size < MIN_BARS) return null
        val d = PriceData(candles)
        val feats = features(d)
        val valid = feats.filterNotNull()
        if (valid.size < k * 2) return null
        val dims = valid.first().size
        val mean = DoubleArray(dims) { j -> valid.sumOf { it[j] } / valid.size }
        val sd = DoubleArray(dims) { j -> sqrt(valid.sumOf { (it[j] - mean[j]).let { x -> x * x } } / valid.size).takeIf { it > 1e-9 } ?: 1.0 }
        val last = d.size - 1
        val close = d.close

        val results = horizons.mapNotNull { h ->
            val rets = neighborsReturns(feats, mean, sd, close, last, h, k).map { it * 100 }
            if (rets.isEmpty()) return@mapNotNull null
            val sorted = rets.sorted()
            val base = (WARMUP until d.size - h).map { close[it + h] > close[it] }
            // 검증: 과거 시점마다 같은 방법으로 방향을 맞혔는지 (최근 기록일수록 실전과 비슷하다)
            var hit = 0
            var tested = 0
            val step = (h / 2).coerceAtLeast(1)
            var t = d.size - 1 - h
            while (t > WARMUP + k * 3 && tested < 60) {
                val r = neighborsReturns(feats, mean, sd, close, t, h, k)
                if (r.isNotEmpty()) {
                    val predictedUp = r.sorted()[r.size / 2] > 0
                    val actualUp = close[t + h] > close[t]
                    if (predictedUp == actualUp) hit++
                    tested++
                }
                t -= step
            }
            Horizon(
                bars = h,
                medianPct = quantile(sorted, 0.5),
                meanPct = rets.average(),
                upProbPct = rets.count { it > 0 } * 100.0 / rets.size,
                baseUpProbPct = if (base.isEmpty()) 50.0 else base.count { it } * 100.0 / base.size,
                p10Pct = quantile(sorted, 0.1),
                p25Pct = quantile(sorted, 0.25),
                p75Pct = quantile(sorted, 0.75),
                p90Pct = quantile(sorted, 0.9),
                hitRatePct = if (tested >= 10) hit * 100.0 / tested else Double.NaN,
                tested = tested,
            )
        }
        if (results.isEmpty()) return null

        val items = ArrayList<ScoreItem>()
        val main = results.getOrNull(1) ?: results.first()
        // 1) 통계 예측
        val edge = main.upProbPct - main.baseUpProbPct
        items += ScoreItem(
            "통계 예측", (edge * 0.6 + main.medianPct.coerceIn(-15.0, 15.0) * 1.5).toInt().coerceIn(-20, 20),
            "비슷했던 과거 ${k}번 중 ${main.bars}일 뒤 오른 비율 ${Fmt.decimal(main.upProbPct)}% (평소 ${Fmt.decimal(main.baseUpProbPct)}%), 중앙값 ${Fmt.signedRate(main.medianPct)}",
        )
        // 2) 추세
        val c = close[last]
        val m20 = d.sma(20)[last]; val m60 = d.sma(60)[last]; val m120 = d.sma(120).getOrNull(last) ?: Double.NaN
        var trend = 0
        val trendWhy = ArrayList<String>()
        if (!m20.isNaN()) { if (c > m20) { trend += 4; trendWhy += "20일선 위" } else { trend -= 4; trendWhy += "20일선 아래" } }
        if (!m60.isNaN()) { if (c > m60) { trend += 4; trendWhy += "60일선 위" } else { trend -= 4; trendWhy += "60일선 아래" } }
        if (!m120.isNaN()) { if (c > m120) { trend += 3; trendWhy += "120일선 위" } else { trend -= 3; trendWhy += "120일선 아래" } }
        if (!m20.isNaN() && !m60.isNaN()) { if (m20 > m60) { trend += 4; trendWhy += "단기선이 중기선 위" } else trend -= 4 }
        items += ScoreItem("추세", trend, trendWhy.joinToString(", ").ifEmpty { "기간이 짧아 판단하기 어렵습니다" })
        // 3) 모멘텀·과열
        val rsi = d.rsi()[last]
        val momentum = when {
            rsi.isNaN() -> 0
            rsi > 80 -> -6
            rsi > 70 -> -2
            rsi >= 50 -> 5
            rsi >= 30 -> -2
            else -> 3
        }
        items += ScoreItem("모멘텀", momentum, "RSI ${Fmt.decimal(rsi)}" + when {
            rsi > 80 -> " — 단기 과열이라 쉬어 갈 수 있습니다"
            rsi > 70 -> " — 강하지만 과열권에 가깝습니다"
            rsi >= 50 -> " — 상승 힘이 우세합니다"
            rsi >= 30 -> " — 하락 힘이 우세합니다"
            else -> " — 과매도라 반등 여지가 있습니다"
        })
        // 4) 전략 신호
        if (report != null) {
            val ratio = report.buyRatioPct
            items += ScoreItem(
                "전략 신호", ((ratio - 50) / 3.5).toInt().coerceIn(-14, 14),
                "전략 ${report.total}개 중 매수 상태 ${report.results.count { it.holding || it.recentBuy(5) }}개 (${Fmt.decimal(ratio)}%), 최근 5일 매수 신호 ${report.recentBuy}개 · 매도 신호 ${report.recentSell}개",
            )
        }
        // 5) 변동성
        val ret = Indicators.returns(close)
        val vol20 = Indicators.stdDev(ret, 20)[last] * sqrt(250.0) * 100
        if (!vol20.isNaN()) {
            items += ScoreItem(
                "변동성", when { vol20 > 80 -> -6; vol20 > 50 -> -3; vol20 < 20 -> 2; else -> 0 },
                "연환산 변동성 ${Fmt.decimal(vol20)}%" + if (vol20 > 50) " — 크게 흔들려 손실 폭도 클 수 있습니다" else "",
            )
        }
        // 6) 가치 (있을 때만)
        quote?.per?.takeIf { it > 0 }?.let { per ->
            items += ScoreItem(
                "가치", when { per < 8 -> 4; per < 15 -> 2; per > 60 -> -4; per > 30 -> -2; else -> 0 },
                "PER ${Fmt.decimal(per)}배" + (quote.pbr?.let { " · PBR ${Fmt.decimal(it)}배" } ?: ""),
            )
        }

        val raw = 50 + items.sumOf { it.score }
        val score = raw.coerceIn(0, 100)
        val opinion = when {
            score >= 72 -> Opinion.STRONG_POSITIVE
            score >= 58 -> Opinion.POSITIVE
            score > 42 -> Opinion.NEUTRAL
            score > 28 -> Opinion.NEGATIVE
            else -> Opinion.STRONG_NEGATIVE
        }
        val notes = buildList {
            if (!main.hitRatePct.isNaN()) {
                add("이 종목에서 같은 방법의 과거 방향 적중률은 ${Fmt.decimal(main.hitRatePct)}% (${main.tested}번 검증)입니다." +
                    if (main.hitRatePct < 55) " 동전 던지기와 큰 차이가 없으니 가볍게 참고하세요." else "")
            }
            if (abs(edge) < 5) add("비슷했던 과거 장면의 결과가 평소와 비슷해, 통계상 뚜렷한 방향성은 없습니다.")
            add("과거 가격 움직임만으로 계산한 통계 참고값입니다. 실적·공시·시장 상황은 반영하지 않으며 투자 판단의 책임은 본인에게 있습니다.")
        }
        return ForecastReport(c, candles[last].time, results, k, score, opinion, items, notes)
    }
}
