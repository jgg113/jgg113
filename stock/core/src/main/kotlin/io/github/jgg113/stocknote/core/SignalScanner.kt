package io.github.jgg113.stocknote.core

import java.time.LocalDate

/** 매매신호 화면의 한 줄: 어떤 종목에서 어떤 전략이 신호를 냈는지 */
data class SignalHit(
    val asset: Asset,
    val strategy: Strategy,
    val signal: Signal,
    /** 신호가 난 봉 (epoch 초) */
    val time: Long,
    /** 신호 봉 종가 */
    val price: Double,
    /** 이 종목에서 이 전략의 과거 성과 */
    val backtest: BacktestResult,
    /** 몇 봉 전에 난 신호인지 (0 = 마지막 봉) */
    val barsAgo: Int,
) {
    val quality: Double get() = backtest.score - barsAgo * 2
}

/** 전략 하나를 여러 종목에 똑같이 따랐을 때의 성과 */
data class StrategyPortfolio(
    val strategy: Strategy,
    /** 날짜별 누적 자산 (처음 = 1.0) */
    val dates: List<LocalDate>,
    val equity: DoubleArray,
    /** 같은 기간 종목을 똑같이 나눠 들고만 있었을 때 */
    val benchmark: DoubleArray,
    val trades: Int,
    val winRatePct: Double,
    val totalReturnPct: Double,
    val benchmarkReturnPct: Double,
)

data class ScanResult(
    val hits: List<SignalHit>,
    val scanned: Int,
    val portfolios: List<StrategyPortfolio>,
)

object SignalScanner {
    /**
     * [universe] 의 종목마다 [strategies] 를 돌려 최근 [lookback] 봉 안에 [signal] 신호가 난 경우를 모은다.
     * 신호마다 그 종목에서 그 전략의 과거 성과를 붙여, 성과가 좋은 신호부터 보여 준다.
     */
    fun scan(
        universe: Map<Asset, List<Candle>>,
        strategies: List<Strategy>,
        config: (Asset) -> BacktestConfig,
        lookback: Int = 1,
        /** null 이면 매수·매도 신호를 모두 모은다 */
        signal: Signal? = Signal.BUY,
        minTrades: Int = 3,
        portfolioBars: Int = 250,
    ): ScanResult {
        val hits = ArrayList<SignalHit>()
        val perStrategy = HashMap<String, MutableList<Pair<PriceData, BacktestResult>>>()
        for ((asset, candles) in universe) {
            if (candles.size < 30) continue
            val data = PriceData(candles)
            val cfg = config(asset)
            for (s in strategies) {
                if (candles.size <= s.minBars + 5) continue
                val r = Backtester.run(s, data, cfg)
                perStrategy.getOrPut(s.id) { ArrayList() } += data to r
                val last = r.lastSignal
                if (last != null && (signal == null || last == signal) && r.lastSignalIndex >= data.size - lookback && r.closedTrades >= minTrades) {
                    val c = candles[r.lastSignalIndex]
                    hits += SignalHit(asset, s, last, c.time, c.close, r, data.size - 1 - r.lastSignalIndex)
                }
            }
        }
        val portfolios = strategies.mapNotNull { s -> perStrategy[s.id]?.let { portfolio(s, it, portfolioBars) } }
        return ScanResult(hits.sortedByDescending { it.quality }, universe.size, portfolios)
    }

    /**
     * 종목마다 전략대로 사고판 결과를 날짜별로 모아, 그날 보유 중인 종목들의 수익률 평균으로 누적 자산을 만든다.
     * (보유 종목이 없는 날은 현금으로 본다.)
     */
    fun portfolio(strategy: Strategy, runs: List<Pair<PriceData, BacktestResult>>, bars: Int): StrategyPortfolio? {
        if (runs.isEmpty()) return null
        val dayReturns = java.util.TreeMap<LocalDate, MutableList<Double>>()
        val benchReturns = java.util.TreeMap<LocalDate, MutableList<Double>>()
        var trades = 0
        var wins = 0
        for ((data, result) in runs) {
            val start = (data.size - bars).coerceAtLeast(1)
            val holdingAt = BooleanArray(data.size)
            for (t in result.trades) {
                // 진입 봉 시가 → 그날 종가, 그다음부터는 종가 → 종가
                for (k in t.entryIndex..t.exitIndex) if (k in holdingAt.indices) holdingAt[k] = true
                if (!t.open && t.exitIndex >= start) {
                    trades++; if (t.win) wins++
                }
            }
            for (i in start until data.size) {
                val day = data.candles[i].date
                val prev = data.close[i - 1]
                if (prev <= 0) continue
                benchReturns.getOrPut(day) { ArrayList() } += data.close[i] / prev - 1
                if (!holdingAt[i]) continue
                val t = result.trades.firstOrNull { i in it.entryIndex..it.exitIndex } ?: continue
                val r = when (i) {
                    t.entryIndex -> data.close[i] / t.entryPrice - 1
                    t.exitIndex -> if (t.open) data.close[i] / prev - 1 else t.exitPrice / prev - 1
                    else -> data.close[i] / prev - 1
                }
                dayReturns.getOrPut(day) { ArrayList() } += r
            }
        }
        if (benchReturns.isEmpty()) return null
        val dates = benchReturns.keys.toList()
        val equity = DoubleArray(dates.size)
        val bench = DoubleArray(dates.size)
        var e = 1.0
        var b = 1.0
        dates.forEachIndexed { idx, d ->
            dayReturns[d]?.takeIf { it.isNotEmpty() }?.let { e *= 1 + it.average() }
            benchReturns[d]?.takeIf { it.isNotEmpty() }?.let { b *= 1 + it.average() }
            equity[idx] = e
            bench[idx] = b
        }
        return StrategyPortfolio(
            strategy, dates, equity, bench, trades,
            if (trades == 0) 0.0 else wins * 100.0 / trades,
            (e - 1) * 100, (b - 1) * 100,
        )
    }
}
