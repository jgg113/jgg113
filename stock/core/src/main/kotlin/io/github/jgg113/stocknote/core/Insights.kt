package io.github.jgg113.stocknote.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ───────────────────────── 종합 점수 ─────────────────────────

/** 종합 점수를 이루는 축. 자료가 있는 축만 계산한다. */
enum class ScoreAxis(val label: String, val help: String, val weight: Double) {
    TREND("추세", "최근 수익률, 이동평균 배열, 52주 위치", 0.20),
    SIGNAL("기술 신호", "전략 가운데 마지막 신호가 매수인 비율", 0.20),
    AI("AI 예측", "학습 모델이 본 오를 가능성", 0.25),
    SAFETY("안정성", "변동성과 낙폭 (높을수록 덜 흔들림)", 0.15),
    VALUE("가치·재무", "PER·PBR·ROE·부채비율·성장", 0.20),
    FLOW("수급", "외국인·기관 순매수", 0.15),
}

data class Reason(val text: String, val positive: Boolean)

data class AxisScore(val axis: ScoreAxis, val score: Int, val reasons: List<Reason>)

/**
 * 여러 분석을 한 장으로 모은 요약 점수 (0~100). 규칙으로 계산한 요약일 뿐 수익을 보장하지 않는다.
 * [strengths]·[weaknesses] 는 점수를 올린 이유·내린 이유를 쉬운 말로 적은 것.
 */
data class StockScore(val total: Int, val grade: String, val axes: List<AxisScore>, val strengths: List<String>, val weaknesses: List<String>) {
    fun axis(a: ScoreAxis): AxisScore? = axes.firstOrNull { it.axis == a }
}

/** 투자자별 하루 순매수 (수량: 주) */
data class InvestorFlow(val date: Long, val close: Double, val individual: Double, val foreign: Double, val institution: Double)

object StockScorer {
    fun grade(score: Int): String = when {
        score >= 80 -> "매우 좋음"
        score >= 65 -> "좋음"
        score >= 45 -> "보통"
        score >= 30 -> "주의"
        else -> "나쁨"
    }

    private fun clamp(v: Double): Int = v.coerceIn(0.0, 100.0).roundToInt()

    private fun pct(v: Double) = Fmt.signedRate(v)

    fun trend(candles: List<Candle>, quote: Quote? = null): AxisScore? {
        if (candles.size < 60) return null
        val close = DoubleArray(candles.size) { candles[it].close }
        val n = close.size
        val last = close[n - 1]
        val rets = DoubleArray(n - 1) { close[it + 1] / close[it] - 1 }.takeLast(250)
        val sd = stdev(rets).takeIf { it > 0 } ?: return null
        val parts = ArrayList<Double>()
        val reasons = ArrayList<Reason>()
        for ((bars, name) in listOf(21 to "1개월", 63 to "3개월", 126 to "6개월", 250 to "1년")) {
            if (n <= bars) continue
            val r = last / close[n - 1 - bars] - 1
            // 그 종목의 평소 흔들림에 견줘 얼마나 큰 움직임인지로 점수를 준다.
            parts += clamp(50 + 25 * r / (sd * sqrt(bars.toDouble()))).toDouble()
            if (bars == 63 || bars == 250 || (bars == 21 && n <= 63)) reasons += Reason("최근 $name ${pct(r * 100)}", r > 0)
        }
        fun ma(k: Int) = if (n >= k) close.takeLast(k).average() else null
        val m20 = ma(20); val m60 = ma(60); val m120 = ma(120)
        val checks = listOfNotNull(m20?.let { last > it }, if (m20 != null && m60 != null) m20 > m60 else null, if (m60 != null && m120 != null) m60 > m120 else null)
        if (checks.isNotEmpty()) {
            val share = checks.count { it }.toDouble() / checks.size
            parts += share * 100
            reasons += when {
                share == 1.0 -> Reason("주가와 이동평균이 위에서부터 차례로 놓임 (정배열)", true)
                share == 0.0 -> Reason("주가와 이동평균이 거꾸로 놓임 (역배열)", false)
                else -> Reason("이동평균 배열이 섞여 있음", share >= 0.5)
            }
        }
        val year = candles.takeLast(250)
        val hi = max(quote?.high52 ?: 0.0, year.maxOf { it.high })
        val lo = min(quote?.low52?.takeIf { it > 0 } ?: Double.MAX_VALUE, year.minOf { it.low })
        if (hi > lo) {
            val pos = (last - lo) / (hi - lo)
            parts += pos * 100
            reasons += Reason("1년 가격 범위의 ${(pos * 100).roundToInt()}% 위치", pos >= 0.5)
        }
        return AxisScore(ScoreAxis.TREND, clamp(parts.average()), reasons)
    }

    fun signal(report: IndicatorReport?): AxisScore? {
        if (report == null || report.total == 0) return null
        val withSignal = report.results.filter { it.lastSignal != null }
        if (withSignal.isEmpty()) return null
        val buy = withSignal.count { it.lastSignal == Signal.BUY }
        val share = buy * 100.0 / withSignal.size
        val reasons = mutableListOf(Reason("전략 ${withSignal.size}개 중 ${buy}개가 매수 상태", share >= 50))
        if (report.recentBuy > report.recentSell && report.recentBuy > 0) reasons += Reason("최근 5일 새 매수 신호 ${report.recentBuy}개 (매도 ${report.recentSell}개)", true)
        if (report.recentSell > report.recentBuy && report.recentSell > 0) reasons += Reason("최근 5일 새 매도 신호 ${report.recentSell}개 (매수 ${report.recentBuy}개)", false)
        return AxisScore(ScoreAxis.SIGNAL, clamp(share), reasons)
    }

    fun ai(results: List<AiForecastResult>): AxisScore? {
        if (results.isEmpty()) return null
        // 검증 신뢰도가 높은 기간에 더 무게를 둔다.
        fun w(r: AiForecastResult) = when (r.reliability) { Reliability.HIGH -> 3.0; Reliability.MEDIUM -> 2.0; Reliability.LOW -> 1.0 }
        val score = results.sumOf { it.score * w(it) } / results.sumOf { w(it) }
        val reasons = results.map { r ->
            Reason("${r.horizon.label}(${r.horizon.period}) 오를 확률 ${(r.upProb * 100).roundToInt()}% · 평소 ${(r.baseUpProb * 100).roundToInt()}% · 신뢰도 ${r.reliability.label}", r.upProb >= r.baseUpProb)
        }
        return AxisScore(ScoreAxis.AI, clamp(score), reasons)
    }

    fun safety(candles: List<Candle>, market: Market): AxisScore? {
        if (candles.size < 60) return null
        val year = candles.takeLast(251)
        val rets = DoubleArray(year.size - 1) { year[it + 1].close / year[it].close - 1 }
        val vol = stdev(rets) * sqrt(if (market == Market.CRYPTO) 365.0 else 250.0)
        var peak = year.first().close
        var mdd = 0.0
        for (c in year) { peak = max(peak, c.close); mdd = max(mdd, 1 - c.close / peak) }
        val volScore = 100 * exp(-vol / 0.6)
        val mddScore = 100 * (1 - mdd / 0.6)
        return AxisScore(
            ScoreAxis.SAFETY, clamp((volScore + mddScore) / 2),
            listOf(
                Reason("1년 변동성 연 ${(vol * 100).roundToInt()}%", vol < 0.35),
                Reason("1년 중 가장 크게 빠진 폭 -${(mdd * 100).roundToInt()}%", mdd < 0.25),
            ),
        )
    }

    private fun band(v: Double, cuts: List<Pair<Double, Int>>, otherwise: Int): Int = cuts.firstOrNull { v < it.first }?.second ?: otherwise

    fun value(quote: Quote?, fin: Financials?): AxisScore? {
        val parts = ArrayList<Int>()
        val reasons = ArrayList<Reason>()
        val cap = quote?.marketCap ?: 0.0
        val lastYear = fin?.annual?.lastOrNull()
        val per = quote?.per ?: fin?.per(cap)
        val loss = lastYear?.netIncome?.let { it <= 0 } == true || (quote?.per != null && quote.per <= 0)
        if (loss) { parts += 15; reasons += Reason("최근 연간 순이익 적자", false) }
        else if (per != null && per > 0) {
            parts += band(per, listOf(8.0 to 90, 12.0 to 75, 20.0 to 55, 35.0 to 35), 20)
            reasons += Reason("PER ${Fmt.decimal(per)}배", per < 15)
        }
        (quote?.pbr ?: fin?.pbr(cap))?.takeIf { it > 0 }?.let { pbr ->
            parts += band(pbr, listOf(0.7 to 85, 1.0 to 75, 1.5 to 60, 3.0 to 45), 25)
            reasons += Reason("PBR ${Fmt.decimal(pbr)}배", pbr < 1.5)
        }
        lastYear?.roe?.let { roe ->
            parts += if (roe >= 15) 90 else if (roe >= 10) 75 else if (roe >= 5) 55 else if (roe >= 0) 35 else 10
            reasons += Reason("ROE ${Fmt.decimal(roe)}%", roe >= 8)
        }
        lastYear?.debtRatio?.let { d ->
            parts += band(d, listOf(50.0 to 90, 100.0 to 75, 200.0 to 50, 400.0 to 25), 10)
            reasons += Reason("부채비율 ${d.roundToInt()}%", d < 150)
        }
        val prev = fin?.annual?.getOrNull(fin.annual.size - 2)
        val rev = lastYear?.revenue
        val prevRev = prev?.revenue
        if (rev != null && prevRev != null && prevRev > 0) {
            val g = (rev / prevRev - 1) * 100
            parts += if (g >= 20) 90 else if (g >= 10) 75 else if (g >= 0) 55 else if (g >= -10) 35 else 15
            reasons += Reason("매출 전년 대비 ${pct(g)}", g >= 0)
        }
        val op = lastYear?.operatingIncome
        val prevOp = prev?.operatingIncome
        if (op != null && prevOp != null && prevOp > 0) {
            val g = (op / prevOp - 1) * 100
            parts += if (g >= 20) 90 else if (g >= 0) 65 else if (g >= -20) 35 else 15
            reasons += Reason("영업이익 전년 대비 ${pct(g)}", g >= 0)
        }
        if (parts.size < 2) return null
        return AxisScore(ScoreAxis.VALUE, clamp(parts.average()), reasons)
    }

    fun flow(flows: List<InvestorFlow>): AxisScore? {
        val recent = flows.sortedBy { it.date }.takeLast(20)
        if (recent.size < 5) return null
        // 순매수가 한쪽으로 얼마나 꾸준했는지 (-1 ~ +1)
        fun lean(pick: (InvestorFlow) -> Double): Double {
            val gross = recent.sumOf { abs(pick(it)) }
            return if (gross <= 0) 0.0 else recent.sumOf(pick) / gross
        }
        fun won(pick: (InvestorFlow) -> Double) = recent.sumOf { pick(it) * it.close }
        val f = lean { it.foreign }
        val o = lean { it.institution }
        fun text(who: String, amount: Double) = "$who ${recent.size}일 ${if (amount >= 0) "순매수" else "순매도"} 약 ${Fmt.big(abs(amount), Currency.KRW)}"
        return AxisScore(
            ScoreAxis.FLOW, clamp(50 + 25 * f + 25 * o),
            listOf(Reason(text("외국인", won { it.foreign }), f >= 0), Reason(text("기관", won { it.institution }), o >= 0)),
        )
    }

    /** 자료가 있는 축만 모아 종합 점수를 낸다. 축이 둘도 안 되면 null */
    fun combine(axes: List<AxisScore?>): StockScore? {
        val have = axes.filterNotNull()
        if (have.size < 2) return null
        val total = (have.sumOf { it.score * it.axis.weight } / have.sumOf { it.axis.weight }).roundToInt()
        val all = have.flatMap { a -> a.reasons.map { a to it } }
        // 점수가 높은 축의 좋은 이유, 낮은 축의 나쁜 이유를 앞에 둔다.
        val strengths = all.filter { it.second.positive }.sortedByDescending { it.first.score }.map { "[${it.first.axis.label}] ${it.second.text}" }.take(4)
        val weaknesses = all.filter { !it.second.positive }.sortedBy { it.first.score }.map { "[${it.first.axis.label}] ${it.second.text}" }.take(4)
        return StockScore(total, grade(total), have, strengths, weaknesses)
    }

    internal fun stdev(x: DoubleArray): Double {
        if (x.size < 2) return 0.0
        val m = x.average()
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    private fun DoubleArray.takeLast(n: Int): DoubleArray = if (size <= n) this else copyOfRange(size - n, size)
}

// ───────────────────────── 시장 심리 ─────────────────────────

data class MoodPart(val name: String, val score: Int, val text: String)

/** 시장 전체 분위기 (0 = 극단적 공포, 100 = 극단적 탐욕). 그날 시세로 계산한 참고값이다. */
data class MarketMood(val score: Int, val label: String, val parts: List<MoodPart>)

object MarketMoodCalc {
    fun label(score: Int): String = when {
        score < 25 -> "극단적 공포"
        score < 45 -> "공포"
        score <= 55 -> "중립"
        score <= 75 -> "탐욕"
        else -> "극단적 탐욕"
    }

    private fun clamp(v: Double): Int = v.coerceIn(0.0, 100.0).roundToInt()

    /** [quotes] 는 그 시장 종목들의 오늘 시세, [index] 는 대표 지수(코인은 비트코인) 일봉 */
    fun of(quotes: List<Quote>, index: List<Candle>? = null): MarketMood? {
        val q = quotes.filter { it.price > 0 }
        if (q.size < 10) return null
        val parts = ArrayList<MoodPart>()
        val up = q.count { it.changeRate > 0 }
        val down = q.count { it.changeRate < 0 }
        if (up + down > 0) parts += MoodPart("오른 종목 비율", clamp(up * 100.0 / (up + down)), "상승 $up · 하락 $down")
        val sorted = q.map { it.changeRate }.sorted()
        val median = sorted[sorted.size / 2]
        parts += MoodPart("가운데 종목 등락률", clamp(50 + median * 15), Fmt.signedRate(median))
        val ranged = q.filter { (it.high52 ?: 0.0) > (it.low52 ?: 0.0) && (it.low52 ?: 0.0) > 0 }
        if (ranged.size >= 10) {
            val pos = ranged.map { (it.price - it.low52!!) / (it.high52!! - it.low52) }
            val nearHigh = pos.count { it >= 0.9 }
            val nearLow = pos.count { it <= 0.1 }
            parts += MoodPart("52주 최고 근처 − 최저 근처", clamp(50 + (nearHigh - nearLow) * 150.0 / ranged.size), "최고 근처 $nearHigh · 최저 근처 $nearLow")
        }
        if (index != null && index.size >= 130) {
            val close = index.map { it.close }
            val last = close.last()
            val ma = close.takeLast(125).average()
            val gap = (last / ma - 1) * 100
            parts += MoodPart("지수의 125일 평균 대비", clamp(50 + gap * 4), Fmt.signedRate(gap))
            val rets = DoubleArray(close.size - 1) { close[it + 1] / close[it] - 1 }
            val v20 = StockScorer.stdev(rets.copyOfRange(rets.size - 20, rets.size))
            val v250 = StockScorer.stdev(rets.copyOfRange(max(0, rets.size - 250), rets.size))
            if (v250 > 0) parts += MoodPart("최근 흔들림 (평소 대비)", clamp(50 - (v20 / v250 - 1) * 50), "평소의 ${Fmt.decimal(v20 / v250)}배")
        }
        val score = parts.map { it.score }.average().roundToInt()
        return MarketMood(score, label(score), parts)
    }
}

// ───────────────────────── 히트맵 ─────────────────────────

data class TreeRect(val index: Int, val x: Float, val y: Float, val w: Float, val h: Float)

/** 넓이가 값에 비례하고 되도록 정사각형에 가깝게 칸을 나누는 배치 (squarified treemap) */
object Treemap {
    fun layout(weights: List<Double>, width: Float, height: Float): List<TreeRect> {
        val items = weights.withIndex().filter { it.value > 0 }.sortedByDescending { it.value }
        val total = items.sumOf { it.value }
        if (items.isEmpty() || total <= 0 || width <= 0 || height <= 0) return emptyList()
        val scale = width.toDouble() * height / total
        val areas = items.map { it.value * scale }
        val out = ArrayList<TreeRect>(items.size)
        var x = 0.0; var y = 0.0; var w = width.toDouble(); var h = height.toDouble()
        var start = 0
        while (start < areas.size) {
            val side = min(w, h)
            // 줄에 칸을 하나씩 더하며, 가장 찌그러진 칸의 비율이 나빠지기 직전까지 담는다.
            var end = start + 1
            var sum = areas[start]
            var worst = worst(areas, start, end, sum, side)
            while (end < areas.size) {
                val s2 = sum + areas[end]
                val w2 = worst(areas, start, end + 1, s2, side)
                if (w2 > worst) break
                sum = s2; worst = w2; end++
            }
            val thick = if (side > 0) sum / side else 0.0
            var offset = 0.0
            for (i in start until end) {
                val len = if (thick > 0) areas[i] / thick else 0.0
                out += if (w >= h) TreeRect(items[i].index, x.toFloat(), (y + offset).toFloat(), thick.toFloat(), len.toFloat())
                else TreeRect(items[i].index, (x + offset).toFloat(), y.toFloat(), len.toFloat(), thick.toFloat())
                offset += len
            }
            if (w >= h) { x += thick; w -= thick } else { y += thick; h -= thick }
            start = end
        }
        return out
    }

    private fun worst(a: List<Double>, from: Int, to: Int, sum: Double, side: Double): Double {
        var mx = 0.0; var mn = Double.MAX_VALUE
        for (i in from until to) { mx = max(mx, a[i]); mn = min(mn, a[i]) }
        val s2 = side * side
        return max(s2 * mx / (sum * sum), sum * sum / (s2 * mn))
    }
}

// ───────────────────────── 포트폴리오 진단 ─────────────────────────

/** [level] 0 = 좋음, 1 = 참고, 2 = 주의 */
data class PortfolioFinding(val text: String, val level: Int)

data class PortfolioItem(val asset: Asset, val valueKrw: Double, val candles: List<Candle>)

data class PortfolioRow(val asset: Asset, val weightPct: Double, val volPct: Double, val riskSharePct: Double, val balancedWeightPct: Double)

/**
 * 보유 종목 묶음의 위험 진단. 지난 1년 일봉으로 지금 비중을 그대로 들고 있었다면 어땠는지를 본다.
 * 과거 기준 참고값이며 앞으로도 같다는 뜻은 아니다.
 */
data class PortfolioDiagnosis(
    val score: Int,
    val grade: String,
    /** 연 변동성 (%) */
    val volatilityPct: Double,
    /** 가장 크게 빠진 폭 (%) */
    val maxDrawdownPct: Double,
    /** 같은 비중으로 들고 있었을 때의 기간 수익률 (%) */
    val returnPct: Double,
    val days: Int,
    val topWeightPct: Double,
    /** 비중을 고르게 나눈 종목 몇 개와 같은지 */
    val effectiveCount: Double,
    /** 종목끼리 함께 움직인 정도의 평균 (-1 ~ 1, 종목이 하나면 NaN) */
    val avgCorrelation: Double,
    val cashPct: Double,
    val rows: List<PortfolioRow>,
    val findings: List<PortfolioFinding>,
)

object PortfolioDoctor {
    fun diagnose(items: List<PortfolioItem>, cashKrw: Double = 0.0): PortfolioDiagnosis? {
        val held = items.filter { it.valueKrw > 0 && it.candles.size >= 30 }
        if (held.isEmpty()) return null
        val invested = held.sumOf { it.valueKrw }
        val w = DoubleArray(held.size) { held[it].valueKrw / invested }
        // 모든 종목에 시세가 있는 날만 쓴다 (주식과 코인이 섞이면 주식이 쉬는 날은 빠진다).
        val maps = held.map { it.candles.takeLast(260).associate { c -> c.date to c.close } }
        val dates = maps.map { it.keys }.reduce { a, b -> a intersect b }.sorted()
        if (dates.size < 25) return null
        val n = dates.size - 1
        val rets = Array(held.size) { i -> DoubleArray(n) { t -> maps[i][dates[t + 1]]!! / maps[i][dates[t]]!! - 1 } }
        val perYear = if (held.all { it.asset.market == Market.CRYPTO }) 365.0 else 250.0
        val k = held.size
        val mean = DoubleArray(k) { rets[it].average() }
        val cov = Array(k) { i -> DoubleArray(k) { j -> (0 until n).sumOf { t -> (rets[i][t] - mean[i]) * (rets[j][t] - mean[j]) } / (n - 1) } }
        val sd = DoubleArray(k) { sqrt(cov[it][it]) }
        val variance = (0 until k).sumOf { i -> (0 until k).sumOf { j -> w[i] * w[j] * cov[i][j] } }
        val vol = sqrt(max(variance, 0.0)) * sqrt(perYear)
        // 날마다 같은 비중을 유지했다고 보고 누적 수익과 최대 낙폭을 구한다.
        var value = 1.0; var peak = 1.0; var mdd = 0.0
        for (t in 0 until n) {
            value *= 1 + (0 until k).sumOf { w[it] * rets[it][t] }
            peak = max(peak, value); mdd = max(mdd, 1 - value / peak)
        }
        val corrs = ArrayList<Double>()
        for (i in 0 until k) for (j in i + 1 until k) if (sd[i] > 0 && sd[j] > 0) corrs += cov[i][j] / (sd[i] * sd[j])
        val avgCorr = if (corrs.isEmpty()) Double.NaN else corrs.average()
        val inv = DoubleArray(k) { if (sd[it] > 0) 1 / sd[it] else 0.0 }
        val invSum = inv.sum()
        val rows = (0 until k).map { i ->
            val contribution = if (variance > 0) w[i] * (0 until k).sumOf { j -> w[j] * cov[i][j] } / variance else w[i]
            PortfolioRow(held[i].asset, w[i] * 100, sd[i] * sqrt(perYear) * 100, contribution * 100, if (invSum > 0) inv[i] / invSum * 100 else w[i] * 100)
        }.sortedByDescending { it.weightPct }
        val top = w.max() * 100
        val effective = 1 / w.sumOf { it * it }
        val total = invested + max(cashKrw, 0.0)
        val cashPct = if (total > 0) max(cashKrw, 0.0) / total * 100 else 0.0

        var score = 100
        val findings = ArrayList<PortfolioFinding>()
        val topRow = rows.first()
        if (k == 1) { score -= 45; findings += PortfolioFinding("종목이 하나뿐입니다. 이 종목이 빠지면 전체가 그대로 빠집니다.", 2) }
        else if (top > 50) { score -= 25; findings += PortfolioFinding("${topRow.asset.name} 한 종목이 ${top.roundToInt()}%를 차지합니다. 한 종목이 절반을 넘으면 그 종목 하나에 성과가 좌우됩니다.", 2) }
        else if (top > 35) { score -= 12; findings += PortfolioFinding("${topRow.asset.name} 비중이 ${top.roundToInt()}%로 높은 편입니다.", 1) }
        else findings += PortfolioFinding("가장 큰 종목도 ${top.roundToInt()}%로, 한 종목에 쏠리지 않았습니다.", 0)
        if (k > 1) {
            if (effective < 3) { score -= 12; findings += PortfolioFinding("종목은 ${k}개지만 비중으로 보면 고르게 나눈 ${Fmt.decimal(effective)}종목과 같습니다.", 1) }
            if (!avgCorr.isNaN()) {
                if (avgCorr > 0.7) { score -= 15; findings += PortfolioFinding("종목들이 거의 같이 움직였습니다 (평균 상관 ${Fmt.decimal(avgCorr)}). 여러 종목이어도 나눠 담은 효과가 작습니다.", 2) }
                else if (avgCorr > 0.5) { score -= 7; findings += PortfolioFinding("종목들이 꽤 같이 움직였습니다 (평균 상관 ${Fmt.decimal(avgCorr)}).", 1) }
                else findings += PortfolioFinding("종목들이 서로 다르게 움직여 (평균 상관 ${Fmt.decimal(avgCorr)}) 나눠 담은 효과가 있습니다.", 0)
            }
            val heavy = rows.maxBy { it.riskSharePct - it.weightPct }
            if (heavy.riskSharePct - heavy.weightPct > 15) findings += PortfolioFinding("${heavy.asset.name}은 비중 ${heavy.weightPct.roundToInt()}%인데 전체 흔들림의 ${heavy.riskSharePct.roundToInt()}%를 만듭니다. 위험이 이 종목에 몰려 있습니다.", 1)
        }
        if (vol > 0.6) { score -= 20; findings += PortfolioFinding("지난 기간 연 변동성이 ${(vol * 100).roundToInt()}%로 매우 큽니다. 하루에도 크게 오르내릴 수 있습니다.", 2) }
        else if (vol > 0.35) { score -= 10; findings += PortfolioFinding("연 변동성 ${(vol * 100).roundToInt()}%로 주식 시장 평균보다 흔들림이 큽니다.", 1) }
        else findings += PortfolioFinding("연 변동성 ${(vol * 100).roundToInt()}%로 흔들림이 크지 않습니다.", 0)
        if (mdd > 0.4) { score -= 10; findings += PortfolioFinding("지금 비중으로 들고 있었다면 한때 -${(mdd * 100).roundToInt()}%까지 빠졌습니다.", 2) }
        if (cashPct < 3 && total > 0) findings += PortfolioFinding("현금이 거의 없습니다. 급락 때 더 살 여유가 없습니다.", 1)
        score = score.coerceIn(0, 100)
        return PortfolioDiagnosis(score, StockScorer.grade(score), vol * 100, mdd * 100, (value - 1) * 100, n, top, effective, avgCorr, cashPct, rows, findings.sortedByDescending { it.level })
    }
}

// ───────────────────────── 뉴스 분위기 ─────────────────────────

data class SentimentResult(
    /** -100(모두 부정) ~ +100(모두 긍정) */
    val score: Int,
    val label: String,
    val positive: Int,
    val negative: Int,
    val neutral: Int,
    /** 제목별 판정 (+1, 0, -1) */
    val items: List<Pair<NewsItem, Int>>,
)

/** 기사 제목에 나온 낱말로 분위기를 가늠한다. 낱말 세기일 뿐이라 반어·맥락은 읽지 못한다. */
object NewsSentiment {
    private val POSITIVE = listOf(
        "급등", "상승", "강세", "신고가", "최고가", "상한가", "호실적", "흑자", "상향", "수주", "최대", "돌파", "반등", "호조", "성장", "수혜", "기대",
        "개선", "증가", "확대", "승인", "계약", "자사주", "러브콜", "서프라이즈", "훈풍", "회복", "순매수", "낙관", "청신호", "잭팟", "랠리",
        "surge", "beat", "upgrade", "record", "rally", "gain", "soar", "jump",
    )
    private val NEGATIVE = listOf(
        "급락", "하락", "약세", "신저가", "최저가", "하한가", "적자", "하향", "부진", "쇼크", "우려", "리스크", "소송", "횡령", "배임", "감자",
        "유상증자", "상장폐지", "거래정지", "손실", "감소", "축소", "리콜", "제재", "과징금", "압수수색", "불확실", "폭락", "경고", "파산", "연체",
        "순매도", "비관", "적신호", "먹구름", "추락", "논란", "plunge", "miss", "downgrade", "lawsuit", "loss", "crash", "slump", "fall",
    )

    fun judge(title: String): Int {
        val t = title.lowercase()
        val p = POSITIVE.count { it in t }
        val n = NEGATIVE.count { it in t }
        return if (p > n) 1 else if (n > p) -1 else 0
    }

    fun of(items: List<NewsItem>): SentimentResult? {
        if (items.isEmpty()) return null
        val judged = items.map { it to judge(it.title) }
        val p = judged.count { it.second > 0 }
        val n = judged.count { it.second < 0 }
        val score = ((p - n) * 100.0 / judged.size).roundToInt()
        val label = when {
            p + n == 0 -> "뚜렷한 분위기 없음"
            score >= 30 -> "긍정적"
            score >= 10 -> "다소 긍정적"
            score > -10 -> "엇갈림"
            score > -30 -> "다소 부정적"
            else -> "부정적"
        }
        return SentimentResult(score, label, p, n, judged.size - p - n, judged)
    }
}

// ───────────────────────── 김치 프리미엄 ─────────────────────────

data class Premium(val asset: Asset, val krwPrice: Double, val usdPrice: Double, val premiumPct: Double)

object Kimchi {
    /** 업비트 원화 가격이 해외 달러 가격(환율 적용)보다 얼마나 비싼지 (%) */
    fun premiums(upbit: List<Quote>, usd: Map<String, Double>, usdKrw: Double): List<Premium> {
        if (usdKrw <= 0) return emptyList()
        return upbit.mapNotNull { q ->
            val symbol = q.asset.code.substringAfter("KRW-")
            val u = usd[symbol]?.takeIf { it > 0 } ?: return@mapNotNull null
            if (q.price <= 0) return@mapNotNull null
            val pct = (q.price / (u * usdKrw) - 1) * 100
            // 이름만 같고 다른 코인인 경우를 걸러 낸다.
            if (abs(pct) > 30) null else Premium(q.asset, q.price, u, pct)
        }
    }

    /** 거래가 많은 코인들의 가운데 값 */
    fun median(list: List<Premium>): Double? = list.map { it.premiumPct }.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
}
