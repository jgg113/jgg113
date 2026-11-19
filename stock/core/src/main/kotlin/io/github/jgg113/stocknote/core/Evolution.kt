package io.github.jgg113.stocknote.core

import kotlinx.serialization.Serializable
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/*
 * AI 전략 연구소
 *
 * 기본 전략 57개의 '들고 있을지' 판단을 몇 개 골라 섞은 조합 전략을, 과거 시세로 수십만 번 모의투자하며 진화시켜 찾는다.
 * (WorldQuant '101 Formulaic Alphas'·AlphaGen 처럼 신호 공식을 자동으로 조합·탐색하는 방식을 단순하게 옮긴 것)
 *
 * 수많은 조합을 시험하면 우연히 좋아 보이는 것이 반드시 나온다. 그래서
 *  - 기간을 학습(앞 60%)·검증(다음 20%)·시험(마지막 20%)으로 나눠, 찾기는 학습, 고르기는 학습+검증으로 하고 시험 기간은 결과 확인에만 쓴다.
 *  - 아무렇게나 만든 조합 수백 개와 시험 기간 성적을 견줘 '운 테스트' 백분위를 낸다.
 *  - Bailey·Borwein·López de Prado·Zhu (2017) 의 CSCV 로 '과최적화 확률(PBO)'을 잰다.
 */

/** 조합에 더하는 시장 조건 */
@Serializable
enum class EvoFilter(val label: String, val warmup: Int) {
    NONE("조건 없음", 0),
    ABOVE_MA200("200일선 위에서만", 200),
    UPTREND("20일선이 60일선 위일 때만", 60),
}

/** 조합 전략의 설계도: [members] 중 [need]개 이상이 '보유'라고 할 때 들고 있는다 (+ 시장 조건) */
@Serializable
data class Genome(val members: List<String>, val need: Int, val filter: EvoFilter = EvoFilter.NONE) {
    val key: String get() = members.sorted().joinToString("+") + "/" + need + "/" + filter.name

    fun describe(): String {
        val names = members.mapNotNull { Strategies.find(it)?.name }
        val rule = when (need) {
            1 -> "하나라도 매수 상태면"
            members.size -> "모두 매수 상태일 때만"
            else -> "${members.size}개 중 ${need}개 이상이 매수 상태면"
        }
        return names.joinToString(" · ") + " — " + rule + " 보유" + if (filter != EvoFilter.NONE) " (${filter.label})" else ""
    }
}

/** 한 기간의 성적 (풀에 든 종목들을 각각 모의투자한 결과를 모은 것) */
@Serializable
data class PeriodStats(
    /** 연 수익률 (종목들의 가운데 값, 비용 뺀 것, %) */
    val annReturnPct: Double,
    /** 같은 기간 그냥 들고만 있었을 때 연 수익률 (가운데 값, %) */
    val buyHoldAnnPct: Double,
    val winRatePct: Double,
    val trades: Int,
    /** 최대 낙폭 (종목 평균, %) */
    val maxDrawdownPct: Double,
    /** 들고 있던 날의 비율 (%) */
    val exposurePct: Double,
    /** 돈을 번 종목의 비율 (%) */
    val positiveSharePct: Double,
    val score: Double,
    val years: Double,
)

@Serializable
data class EvolvedStrategy(
    val id: String,
    val name: String,
    val genome: Genome,
    val market: Market,
    val train: PeriodStats,
    val valid: PeriodStats,
    val test: PeriodStats,
    /** 아무렇게나 만든 조합들보다 시험 기간 성적이 몇 % 앞서는지 (높을수록 운이 아닐 가능성) */
    val luckPercentile: Double,
    /** 과최적화 확률 (0~1, 낮을수록 좋다). 이 전략을 뽑은 탐색 전체에 대한 값 */
    val pbo: Double,
    val avgHoldBars: Double,
    val poolSize: Int,
    val evaluations: Long,
    val simulations: Long,
    val periods: String,
    val createdAt: Long,
    /** 여러 조합을 다시 투표로 묶은 'AI 종합 모델'이면 그 조합들 ([partsNeed]개 이상이 보유라고 하면 보유) */
    val parts: List<Genome> = emptyList(),
    val partsNeed: Int = 0,
    /** 찾을 때 한 번도 쓰지 않은 다른 종목들의 시험 기간 성적 */
    val holdout: PeriodStats? = null,
    val holdoutSize: Int = 0,
    /** 앱에 기본으로 들어 있는 모델 (지울 수 없다) */
    val builtIn: Boolean = false,
    val note: String = "",
) {
    val isEnsemble: Boolean get() = parts.isNotEmpty()

    fun describe(): String = if (isEnsemble) "조합 전략 ${parts.size}개 가운데 ${partsNeed}개 이상이 매수 상태면 보유하는 종합 모델" else genome.describe()

    /** 시험 기간(찾을 때 쓰지 않은 최근 기간)에도 통했는지: 돈을 벌었고, 그냥 들고 있기보다 나았고, 무작위 조합의 90%보다 나았음 */
    val passedTest: Boolean get() = test.annReturnPct > 0 && test.annReturnPct > test.buyHoldAnnPct && luckPercentile >= 90 && test.trades >= 3 && test.exposurePct >= 10

    val horizon: StrategyHorizon get() = when {
        avgHoldBars < 21 -> StrategyHorizon.SHORT
        avgHoldBars < 126 -> StrategyHorizon.MID
        else -> StrategyHorizon.LONG
    }

    /** 앱의 다른 화면(지표분석·매매신호·차트)에서 쓸 수 있는 전략으로 바꾼다. */
    fun toStrategy(): Strategy {
        val gs = if (isEnsemble) parts else listOf(genome)
        val minBars = gs.maxOf { g -> max(g.members.mapNotNull { Strategies.find(it) }.maxOfOrNull { it.minBars } ?: 0, g.filter.warmup) }
        fun pos(d: PriceData): BooleanArray = d.memo("evo_${id}") {
            if (!isEnsemble) Composite.position(d, genome) else {
                val ps = parts.map { Composite.position(d, it) }
                BooleanArray(d.size) { i -> ps.count { it[i] } >= partsNeed }
            }
        }
        return Strategy(
            id, name, StrategyGroup.AI, (if (note.isNotEmpty()) "$note " else "") + describe(), minBars, null,
            buy = { d, i -> val p = pos(d); i > 0 && p[i] && !p[i - 1] },
            sell = { d, i -> val p = pos(d); i > 0 && !p[i] && p[i - 1] },
        ).also { it.horizon = horizon }
    }
}

/** 조합 전략의 보유 판단을 PriceData 위에서 계산한다 (앱 화면용, 연구소 엔진과 같은 규칙). */
object Composite {
    /** 전략 하나가 종가 기준으로 '들고 있고 싶은지' (다음 날 시가에 실행) */
    fun state(s: Strategy, d: PriceData): BooleanArray = d.memo("state_${s.id}") {
        val sig = s.signals(d)
        val out = BooleanArray(d.size)
        var want = false
        var entered = -1
        for (i in 0 until d.size) {
            if (!want && sig[i] == Signal.BUY) { want = true; entered = i + 1 }
            else if (want && (sig[i] == Signal.SELL || (s.holdBars != null && i - entered >= s.holdBars))) want = false
            out[i] = want
        }
        out
    }

    fun filter(f: EvoFilter, d: PriceData): BooleanArray = d.memo("evo_filter_${f.name}") {
        when (f) {
            EvoFilter.NONE -> BooleanArray(d.size) { true }
            EvoFilter.ABOVE_MA200 -> { val m = d.sma(200); BooleanArray(d.size) { i -> !m[i].isNaN() && d.close[i] > m[i] } }
            EvoFilter.UPTREND -> { val a = d.sma(20); val b = d.sma(60); BooleanArray(d.size) { i -> !a[i].isNaN() && !b[i].isNaN() && a[i] > b[i] } }
        }
    }

    fun position(d: PriceData, g: Genome): BooleanArray = position(d, g.members.mapNotNull { Strategies.find(it) }, g.need, g.filter)

    fun position(d: PriceData, members: List<Strategy>, need: Int, f: EvoFilter): BooleanArray {
        val states = members.map { state(it, d) }
        val fl = filter(f, d)
        return BooleanArray(d.size) { i -> fl[i] && states.count { it[i] } >= need }
    }
}

/** 연구소 진행 상황 */
data class EvoProgress(
    val generation: Int,
    /** 시험한 조합 전략 수 */
    val evaluations: Long,
    /** 종목별 모의투자 횟수 (= 조합 × 종목 × 기간) */
    val simulations: Long,
    val best: List<EvolvedStrategy>,
    val pbo: Double,
    val message: String,
)

/**
 * 진화 탐색 엔진. [pool] 은 같은 시장 종목들의 일봉(5년 이상 권장).
 * 보유 여부를 비트(Long 한 개에 64일)로 들고 있어, 조합 하나를 수십 종목에 모의투자하는 데 1ms 안팎이 든다.
 */
class EvoLab(
    pool: List<Pair<Asset, List<Candle>>>,
    private val market: Market,
    private val roundTripCostPct: Double = BacktestConfig.defaultCost(market),
    private val seed: Int = 7,
    private val bases: List<Strategy> = Strategies.all,
    /** 기간 경계를 다른 연구와 맞추고 싶을 때 (t0, t1, t2, tEnd) */
    cuts: LongArray? = null,
) {
    private val perYear = if (market == Market.CRYPTO) 365.0 else 250.0
    private val cost = roundTripCostPct / 100

    private class Stock(
        val asset: Asset,
        val n: Int,
        val dates: LongArray,
        val states: Array<LongArray>,
        val filters: Array<LongArray>,
        /** i 날 종가에 들고 있기로 하면 얻는 하루 수익 (다음 날 시가 → 그다음 날 시가, 로그) */
        val ret: DoubleArray,
        val close: DoubleArray,
    )

    private val stocks: List<Stock>
    private val idIndex: Map<String, Int> = bases.withIndex().associate { it.value.id to it.index }

    /** 기간 경계 (epoch 초): 학습 [t0, t1), 검증 [t1, t2), 시험 [t2, 끝] */
    val t0: Long
    val t1: Long
    val t2: Long
    val tEnd: Long

    init {
        stocks = pool.filter { it.second.size >= 400 }.map { (asset, candles) ->
            val d = PriceData(candles)
            val n = d.size
            val words = (n + 63) / 64
            fun bits(b: BooleanArray): LongArray = LongArray(words).also { w -> for (i in 0 until n) if (b[i]) w[i shr 6] = w[i shr 6] or (1L shl (i and 63)) }
            val states = Array(bases.size) { k -> bits(Composite.state(bases[k], d)) }
            val filters = Array(EvoFilter.entries.size) { k -> bits(Composite.filter(EvoFilter.entries[k], d)) }
            val ret = DoubleArray(n) { i ->
                when {
                    i + 2 < n && d.open[i + 1] > 0 && d.open[i + 2] > 0 -> ln(d.open[i + 2] / d.open[i + 1])
                    i + 1 < n && d.open[i + 1] > 0 && d.close[i + 1] > 0 -> ln(d.close[i + 1] / d.open[i + 1])
                    else -> 0.0
                }
            }
            Stock(asset, n, LongArray(n) { candles[it].time }, states, filters, ret, d.close)
        }
        require(stocks.isNotEmpty()) { "연구에 쓸 종목 일봉이 부족합니다 (종목마다 400일 이상 필요)." }
        // 전략들이 자리 잡는 데 1년쯤 걸리므로, 종목들의 1년째 날짜의 가운데부터 쓴다.
        if (cuts != null) {
            t0 = cuts[0]; t1 = cuts[1]; t2 = cuts[2]; tEnd = cuts[3]
        } else {
            val warm = stocks.map { it.dates[min(260, it.n - 1)] }.sorted()
            t0 = warm[warm.size / 2]
            tEnd = stocks.maxOf { it.dates.last() }
            val span = tEnd - t0
            t1 = t0 + (span * 0.6).toLong()
            t2 = t0 + (span * 0.8).toLong()
        }
    }

    val poolSize: Int get() = stocks.size
    val cutDates: LongArray get() = longArrayOf(t0, t1, t2, tEnd)
    val evaluationsSoFar: Long get() = evalCount.get()
    val simulationsSoFar: Long get() = simCount.get()

    fun periodText(): String {
        fun d(t: Long) = Fmt.date(t)
        return "학습 ${d(t0)}~${d(t1)} · 검증 ~${d(t2)} · 시험 ~${d(tEnd)}"
    }

    private fun range(s: Stock, from: Long, to: Long): IntRange {
        var a = s.dates.binarySearch(from).let { if (it < 0) -it - 1 else it }
        var b = s.dates.binarySearch(to).let { if (it < 0) -it - 1 else it } // 끝 제외
        a = a.coerceIn(0, s.n); b = b.coerceIn(a, s.n)
        return a until b
    }

    private val evalCount = AtomicLong(0)
    private val simCount = AtomicLong(0)

    // ───────── 평가 ─────────

    private class Raw(val logRet: Double, val bh: Double, val trades: Int, val wins: Int, val held: Int, val bars: Int, val mdd: Double, val holdBars: Int)

    private val subsetsCache = HashMap<Pair<Int, Int>, List<IntArray>>()

    private fun subsets(m: Int, k: Int): List<IntArray> = synchronized(subsetsCache) {
        subsetsCache.getOrPut(m to k) {
            val out = ArrayList<IntArray>()
            fun go(start: Int, acc: IntArray, depth: Int) {
                if (depth == k) { out += acc.copyOf(); return }
                for (i in start until m) { acc[depth] = i; go(i + 1, acc, depth + 1) }
            }
            go(0, IntArray(k), 0)
            out
        }
    }

    private fun simulate(s: Stock, g: Genome, r: IntRange): Raw {
        if (r.isEmpty()) return Raw(0.0, 0.0, 0, 0, 0, 0, 0.0, 0)
        return simulateBits(s, bits(s, g), r)
    }

    /** 조합 하나가 이 종목에서 들고 있는 날 (비트) */
    private fun bits(s: Stock, g: Genome): LongArray {
        val idx = g.members.map { idIndex.getValue(it) }
        val subs = subsets(idx.size, g.need.coerceIn(1, idx.size))
        val f = s.filters[g.filter.ordinal]
        val words = s.states[0].size
        val p = LongArray(words)
        for (w in 0 until words) {
            var acc = 0L
            for (sub in subs) {
                var x = -1L
                for (j in sub) x = x and s.states[idx[j]][w]
                acc = acc or x
            }
            p[w] = acc and f[w]
        }
        return p
    }

    /** 여러 조합 중 [need]개 이상이 들고 있는 날 (비트) */
    private fun ensembleBits(s: Stock, parts: List<Genome>, need: Int): LongArray {
        val ps = parts.map { bits(s, it) }
        val out = LongArray(ps[0].size)
        for (w in out.indices) {
            var word = 0L
            for (b in 0 until 64) {
                var c = 0
                for (p in ps) if ((p[w] ushr b) and 1L != 0L) c++
                if (c >= need) word = word or (1L shl b)
            }
            out[w] = word
        }
        return out
    }

    private fun simulateBits(s: Stock, p: LongArray, r: IntRange): Raw {
        if (r.isEmpty()) return Raw(0.0, 0.0, 0, 0, 0, 0, 0.0, 0)
        // 기간 안의 보유한 날을 차례로 훑어 거래·수익·낙폭을 센다.
        var logRet = 0.0
        var trades = 0; var wins = 0; var held = 0; var holdBars = 0
        var cum = 0.0; var peak = 0.0; var mdd = 0.0
        var prev = -2
        var tradeRet = 0.0
        fun closeTrade() {
            trades++
            if (tradeRet - cost > 0) wins++
            logRet -= cost
            cum -= cost
            peak = max(peak, cum); mdd = max(mdd, peak - cum)
        }
        var w = r.first shr 6
        val lastWord = (r.last) shr 6
        while (w <= lastWord) {
            var bitsW = p[w]
            if (w == r.first shr 6) bitsW = bitsW and (-1L shl (r.first and 63))
            if (w == lastWord && (r.last and 63) != 63) bitsW = bitsW and ((1L shl ((r.last and 63) + 1)) - 1)
            while (bitsW != 0L) {
                val t = (w shl 6) + java.lang.Long.numberOfTrailingZeros(bitsW)
                bitsW = bitsW and (bitsW - 1)
                if (t != prev + 1) {
                    if (prev >= 0) closeTrade()
                    tradeRet = 0.0
                }
                val x = s.ret[t]
                logRet += x; tradeRet += x; cum += x; held++; holdBars++
                peak = max(peak, cum); mdd = max(mdd, peak - cum)
                prev = t
            }
            w++
        }
        if (prev >= 0) closeTrade()
        val a = r.first; val b = r.last
        val bh = if (s.close[a] > 0 && s.close[b] > 0) ln(s.close[b] / s.close[a]) else 0.0
        simCount.incrementAndGet()
        return Raw(logRet, bh, trades, wins, held, r.last - r.first + 1, 1 - exp(-mdd), holdBars)
    }

    private fun stats(raws: List<Raw>): PeriodStats {
        val years = (raws.map { it.bars }.average() / perYear).coerceAtLeast(1.0 / perYear)
        val ann = raws.map { it.logRet / years }.sorted()
        val bh = raws.map { it.bh / years }.sorted()
        val med = ann[ann.size / 2]
        val trades = raws.sumOf { it.trades }
        val mdd = raws.map { it.mdd }.average()
        val positive = raws.count { it.logRet > 0 }.toDouble() / raws.size
        val exposure = raws.sumOf { it.held }.toDouble() / raws.sumOf { it.bars }.coerceAtLeast(1)
        val bhMed = bh[bh.size / 2]
        // 실제로 투자하는 전략만 인정한다: 들고 있는 날이 15% 이상, 종목마다 1년에 한 번 이상 거래.
        // (이 조건이 없으면 '거의 아무것도 안 하는' 전략이 낙폭이 없어 1등이 된다)
        val active = exposure >= MIN_EXPOSURE && trades >= max(3.0, raws.size * years * 1.0)
        // 점수: 가운데 종목의 연 수익(로그) − 낙폭 벌점 + 그냥 들고 있기보다 나은 만큼 가산, 여러 종목에서 고르게 벌수록 가산.
        val score = if (!active) -9.0 else med * (0.6 + 0.4 * positive) - 0.35 * mdd + 0.25 * (med - bhMed)
        return PeriodStats(
            annReturnPct = (exp(med) - 1) * 100,
            buyHoldAnnPct = (exp(bhMed) - 1) * 100,
            winRatePct = if (trades == 0) 0.0 else raws.sumOf { it.wins } * 100.0 / trades,
            trades = trades,
            maxDrawdownPct = mdd * 100,
            exposurePct = exposure * 100,
            positiveSharePct = positive * 100,
            score = score,
            years = years,
        )
    }

    enum class Period { TRAIN, VALID, TEST, TRAIN_VALID }

    fun evaluate(g: Genome, period: Period): PeriodStats {
        evalCount.incrementAndGet()
        val (from, to) = when (period) {
            Period.TRAIN -> t0 to t1
            Period.VALID -> t1 to t2
            Period.TEST -> t2 to tEnd + 1
            Period.TRAIN_VALID -> t0 to t2
        }
        return stats(stocks.map { simulate(it, g, range(it, from, to)) })
    }

    private fun periodRange(period: Period): Pair<Long, Long> = when (period) {
        Period.TRAIN -> t0 to t1
        Period.VALID -> t1 to t2
        Period.TEST -> t2 to tEnd + 1
        Period.TRAIN_VALID -> t0 to t2
    }

    /** 'AI 종합 모델' (여러 조합의 투표) 성적 */
    fun evaluateEnsemble(parts: List<Genome>, need: Int, period: Period): PeriodStats {
        evalCount.incrementAndGet()
        val (from, to) = periodRange(period)
        return stats(stocks.map { s -> simulateBits(s, ensembleBits(s, parts, need), range(s, from, to)) })
    }

    fun ensembleAvgHold(parts: List<Genome>, need: Int): Double {
        val raws = stocks.map { s -> simulateBits(s, ensembleBits(s, parts, need), range(s, t0, t2)) }
        val t = raws.sumOf { it.trades }
        return if (t == 0) 0.0 else raws.sumOf { it.holdBars }.toDouble() / t
    }

    /** 아무렇게나 만든 조합 [n]개의 시험 기간 점수 (운 테스트 기준) */
    /** 아무렇게나 만든 조합 가운데 '실제로 투자하는' 것 [n]개의 시험 기간 점수 (운 테스트 기준) */
    fun randomTestScores(n: Int = 300): List<Double> {
        val out = ArrayList<Double>()
        var k = 0
        while (out.size < n && k < n * 20) {
            val g = randomGenome(Random(seed * 31 + k++))
            // 고르는 조건(학습 기간에 실제로 투자했는지)을 똑같이 걸어, 같은 조건을 통과한 것끼리 견준다.
            if (evaluate(g, Period.TRAIN).score <= -9.0) continue
            out += evaluate(g, Period.TEST).score
        }
        return out.sorted()
    }

    fun avgHoldOf(g: Genome): Double = avgHold(g)

    private fun avgHold(g: Genome): Double {
        val raws = stocks.map { simulate(it, g, range(it, t0, t2)) }
        val t = raws.sumOf { it.trades }
        return if (t == 0) 0.0 else raws.sumOf { it.holdBars }.toDouble() / t
    }

    // ───────── 과최적화 확률 (CSCV) ─────────

    /**
     * 학습+검증 기간을 8토막 내어, 그중 4토막으로 1등을 뽑았을 때 나머지 4토막에서 그 1등이 가운데보다 못한 경우의 비율.
     * 70가지 나눔을 모두 해 본다. 0.5 에 가까우면 '뽑기'가 운과 다를 바 없다는 뜻.
     */
    fun pbo(candidates: List<Genome>, blocks: Int = 8): Double {
        if (candidates.size < 4) return Double.NaN
        val edges = LongArray(blocks + 1) { t0 + (t2 - t0) * it / blocks }
        // perf[c][b] = 후보 c 의 토막 b 성적 (종목 평균 로그 수익)
        val perf = Array(candidates.size) { c ->
            DoubleArray(blocks) { b -> stocks.map { simulate(it, candidates[c], range(it, edges[b], edges[b + 1])).logRet }.average() }
        }
        val half = blocks / 2
        val splits = ArrayList<IntArray>()
        fun go(start: Int, acc: IntArray, depth: Int) {
            if (depth == half) { splits += acc.copyOf(); return }
            for (i in start until blocks) { acc[depth] = i; go(i + 1, acc, depth + 1) }
        }
        go(0, IntArray(half), 0)
        var below = 0
        for (inSet in splits) {
            val inMask = BooleanArray(blocks).also { m -> inSet.forEach { m[it] = true } }
            val isPerf = perf.map { row -> (0 until blocks).filter { inMask[it] }.sumOf { row[it] } }
            val oosPerf = perf.map { row -> (0 until blocks).filter { !inMask[it] }.sumOf { row[it] } }
            val best = isPerf.indices.maxBy { isPerf[it] }
            val rank = oosPerf.count { it < oosPerf[best] } + 0.5 * (oosPerf.count { it == oosPerf[best] } - 1) + 1
            val omega = rank / (candidates.size + 1)
            if (ln(omega / (1 - omega)) <= 0) below++
        }
        return below.toDouble() / splits.size
    }

    // ───────── 진화 탐색 ─────────

    private fun randomGenome(rnd: Random): Genome {
        val m = 2 + rnd.nextInt(3) // 2~4개
        val members = bases.shuffled(rnd).take(m).map { it.id }
        return Genome(members, 1 + rnd.nextInt(m), EvoFilter.entries[rnd.nextInt(EvoFilter.entries.size)])
    }

    private fun mutate(g: Genome, rnd: Random): Genome {
        var members = g.members.toMutableList()
        when (rnd.nextInt(5)) {
            0 -> members[rnd.nextInt(members.size)] = bases[rnd.nextInt(bases.size)].id
            1 -> if (members.size < 4) members += bases[rnd.nextInt(bases.size)].id
            2 -> if (members.size > 2) members.removeAt(rnd.nextInt(members.size))
            3 -> return g.copy(need = 1 + rnd.nextInt(members.size))
            else -> return g.copy(filter = EvoFilter.entries[rnd.nextInt(EvoFilter.entries.size)])
        }
        members = members.distinct().toMutableList()
        if (members.size < 2) members += bases.first { it.id !in members }.id
        return Genome(members, g.need.coerceIn(1, members.size), g.filter)
    }

    private fun crossover(a: Genome, b: Genome, rnd: Random): Genome {
        val pool = (a.members + b.members).distinct().shuffled(rnd)
        val m = (2 + rnd.nextInt(3)).coerceAtMost(pool.size)
        val members = pool.take(m)
        return Genome(members, (if (rnd.nextBoolean()) a.need else b.need).coerceIn(1, members.size), if (rnd.nextBoolean()) a.filter else b.filter)
    }

    private class Scored(val g: Genome, val train: PeriodStats, var valid: PeriodStats? = null) {
        val both: Double get() = min(train.score, valid?.score ?: -9.0)
    }

    /**
     * [maxEvaluations] 개의 조합을 시험할 때까지(또는 [stop] 이 참이 될 때까지) 진화시킨다.
     * 세대마다 [progress] 로 지금까지 찾은 상위 전략을 알려 준다.
     */
    fun run(
        maxEvaluations: Long = 100_000,
        population: Int = 160,
        keep: Int = 12,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
        stop: () -> Boolean = { false },
        progress: (EvoProgress) -> Unit = {},
    ): EvoProgress {
        val rnd = Random(seed)
        val exec = Executors.newFixedThreadPool(threads)
        try {
            fun scoreAll(gs: List<Genome>): List<Scored> = exec.invokeAll(gs.map { g -> Callable { Scored(g, evaluate(g, Period.TRAIN)) } }).map { it.get() }

            // 운 테스트 기준: 아무렇게나 만든 조합들의 시험 기간 점수
            val randomTest = randomTestScores()

            val seen = HashSet<String>()
            var pop = scoreAll(List(population) { randomGenome(rnd) }.filter { seen.add(it.key) })
            val hall = LinkedHashMap<String, Scored>()
            var generation = 0
            var lastPbo = Double.NaN
            var bestSoFar = Double.NEGATIVE_INFINITY
            var stale = 0
            var last: EvoProgress? = null
            while (evalCount.get() < maxEvaluations && !stop()) {
                generation++
                // 학습 점수 상위만 검증 기간으로 다시 본다 (검증 기간은 고르는 데만 쓴다).
                val top = pop.sortedByDescending { it.train.score }.take(population / 5)
                exec.invokeAll(top.filter { it.valid == null }.map { s -> Callable { s.valid = evaluate(s.g, Period.VALID) } }).forEach { it.get() }
                top.forEach { s -> if (s.valid != null && s.both > -1) hall[s.g.key] = s }
                val ranked = hall.values.sortedByDescending { it.both }
                hall.keys.retainAll(ranked.take(60).map { it.g.key }.toSet())

                // 다음 세대: 상위 유지 + 토너먼트로 고른 부모의 교배·돌연변이
                val parents = pop.sortedByDescending { it.train.score }
                val elite = parents.take(population / 10)
                val children = ArrayList<Genome>()
                var guard = 0
                while (children.size < population - elite.size && guard++ < population * 20) {
                    fun pick(): Genome = (0 until 3).map { parents[rnd.nextInt(min(parents.size, population / 2).coerceAtLeast(1))] }.maxBy { it.train.score }.g
                    val child = if (rnd.nextDouble() < 0.6) mutate(crossover(pick(), pick(), rnd), rnd) else if (rnd.nextDouble() < 0.85) mutate(pick(), rnd) else randomGenome(rnd)
                    if (seen.add(child.key)) children += child
                }
                // 한동안 나아지지 않으면 한곳에 갇힌 것이므로, 절반을 새 무작위 조합으로 바꿔 다른 곳을 찾아본다.
                val topBoth = ranked.firstOrNull()?.both ?: Double.NEGATIVE_INFINITY
                if (topBoth > bestSoFar + 1e-9) { bestSoFar = topBoth; stale = 0 } else stale++
                if (stale >= 25) {
                    stale = 0
                    val fresh = List(population / 2) { randomGenome(rnd) }.filter { seen.add(it.key) }
                    children.subList(0, min(fresh.size, children.size)).clear()
                    children.addAll(0, fresh)
                }
                pop = elite + scoreAll(children)

                if (generation % 5 == 1 || evalCount.get() >= maxEvaluations || stop()) {
                    lastPbo = runCatching { pbo(ranked.take(40).map { it.g }) }.getOrDefault(Double.NaN)
                }
                val best = ranked.take(keep).map { s -> toEvolved(s, randomTest, lastPbo) }
                last = EvoProgress(generation, evalCount.get(), simCount.get(), best, lastPbo, "세대 $generation")
                progress(last)
            }
            return last ?: EvoProgress(0, evalCount.get(), simCount.get(), emptyList(), Double.NaN, "")
        } finally {
            exec.shutdownNow()
        }
    }

    private fun toEvolved(s: Scored, randomTest: List<Double>, pbo: Double): EvolvedStrategy {
        val test = evaluate(s.g, Period.TEST)
        val below = randomTest.count { it < test.score }
        val names = s.g.members.mapNotNull { Strategies.find(it)?.name }
        val id = "evo_" + market.name.lowercase() + "_" + Integer.toHexString(s.g.key.hashCode())
        val name = "AI 조합: " + names.joinToString(" + ") { it.take(10) }
        return EvolvedStrategy(
            id, name, s.g, market, s.train, s.valid!!, test, below * 100.0 / randomTest.size.coerceAtLeast(1), pbo, avgHold(s.g),
            stocks.size, evalCount.get(), simCount.get(), periodText(), System.currentTimeMillis() / 1000,
        )
    }

    companion object {
        /** 이보다 적게 들고 있으면 '투자하는 전략'으로 치지 않는다 */
        const val MIN_EXPOSURE = 0.15

        /** 화면에 보일 '운 테스트' 해석 */
        fun luckText(p: Double): String = when {
            p >= 90 -> "무작위 조합의 ${p.toInt()}%보다 나음 — 운만은 아닐 가능성"
            p >= 70 -> "무작위 조합의 ${p.toInt()}%보다 나음 — 약간 나은 정도"
            else -> "무작위 조합과 비슷함 — 운일 가능성이 큼"
        }

        fun pboText(p: Double): String = when {
            p.isNaN() -> "계산 중"
            p < 0.2 -> "${(p * 100).toInt()}% (낮음 — 고르는 방법이 믿을 만함)"
            p < 0.5 -> "${(p * 100).toInt()}% (보통)"
            else -> "${(p * 100).toInt()}% (높음 — 찾은 전략들이 운에 기댄 것일 가능성이 큼)"
        }

        @Suppress("unused")
        private fun keepImports() = abs(0.0)
    }
}

/**
 * 앱에 기본으로 들어 있는 AI 모델. 개발 중에 국내 161종목(찾기 113 + 따로 남긴 48)의 6~7년 일봉으로
 * 조합 12만 개(모의투자 약 1,950만 번)를 시험해 만든 것이다 (core/src/test/.../KrResearch.kt).
 */
object BuiltInModels {
    val all: List<EvolvedStrategy> by lazy {
        runCatching {
            val text = BuiltInModels::class.java.getResourceAsStream("/models/kr_ai_models.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@runCatching emptyList()
            lenientJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(EvolvedStrategy.serializer()), text)
        }.getOrDefault(emptyList())
    }
}

