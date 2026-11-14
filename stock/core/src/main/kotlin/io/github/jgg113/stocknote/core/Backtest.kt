package io.github.jgg113.stocknote.core

import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.max

/** 백테스트 조건 */
@Serializable
data class BacktestConfig(
    /** 사고팔 때 드는 비용 합계 (수수료+세금, %) */
    val roundTripCostPct: Double = 0.25,
    /** 손절 (%) — 없으면 null */
    val stopLossPct: Double? = null,
    /** 익절 (%) — 없으면 null */
    val takeProfitPct: Double? = null,
) {
    companion object {
        /** 시장별 기본 비용: 국내(수수료 왕복 약 0.03% + 거래세 0.2%), 미국(수수료 왕복 약 0.5%), 코인(업비트 왕복 0.1%) */
        fun defaultCost(market: Market): Double = when (market) {
            Market.KR -> 0.23
            Market.US -> 0.5
            Market.CRYPTO -> 0.1
        }
    }
}

/** 매매 한 번. 신호가 난 봉의 다음 봉 시가에 사고판다. */
@Serializable
data class Trade(
    val entryIndex: Int,
    val entryTime: Long,
    val entryPrice: Double,
    val exitIndex: Int,
    val exitTime: Long,
    val exitPrice: Double,
    /** 비용을 뺀 수익률 (%) */
    val returnPct: Double,
    /** 아직 들고 있는 거래면 참 (마지막 종가로 평가) */
    val open: Boolean,
    val exitReason: String = "",
) {
    val bars: Int get() = exitIndex - entryIndex
    val win: Boolean get() = returnPct > 0
}

/** 차트에 찍을 매매 시점 */
data class TradeMarker(val index: Int, val signal: Signal, val price: Double)

data class BacktestResult(
    val strategy: Strategy,
    val trades: List<Trade>,
    /** 복리 누적 수익률 (%) */
    val totalReturnPct: Double,
    /** 끝난 거래 중 이긴 비율 (%) */
    val winRatePct: Double,
    val closedTrades: Int,
    val avgReturnPct: Double,
    val maxDrawdownPct: Double,
    val buyHoldReturnPct: Double,
    val profitFactor: Double,
    val avgHoldBars: Double,
    /** 봉마다 자산 (처음 = 1.0) */
    val equity: DoubleArray,
    val markers: List<TradeMarker>,
    /** 지금 들고 있는지 */
    val holding: Boolean,
    /** 마지막 신호와 그 봉 위치 */
    val lastSignal: Signal?,
    val lastSignalIndex: Int,
    val bars: Int,
    /** 매수 신호가 난 횟수 (진입 여부와 관계없이) */
    val buySignals: Int = 0,
    /** 전략이 실제로 돌아간 봉 수 (지표 준비 기간 제외) */
    val activeBars: Int = bars,
) {
    /** 마지막 신호가 몇 봉 전인지 (없으면 -1) */
    val barsSinceSignal: Int get() = if (lastSignalIndex < 0) -1 else bars - 1 - lastSignalIndex

    /** 한 달(21거래일)에 매수 신호가 평균 몇 번 나는지 */
    val signalsPerMonth: Double get() = if (activeBars <= 0) 0.0 else buySignals / (activeBars / 21.0)

    /** "월 2회", "월 1회 미만" 같은 신호 주기 글 */
    val frequencyText: String get() = signalsPerMonth.let {
        when {
            it < 1 -> if (it < 1.0 / 12) "연 1회 미만" else if (it < 0.25) "연 ${kotlin.math.round(it * 12).toInt()}회" else "월 1회 미만"
            else -> "월 ${kotlin.math.round(it).toInt()}회"
        }
    }

    /** 마지막 봉에서 신호가 났는지 (다음 거래일 시가 진입·청산 대기) */
    val signalOnLastBar: Boolean get() = lastSignalIndex == bars - 1

    /** 최근 [n] 봉 안에 매수 신호가 났는지 */
    fun recentBuy(n: Int): Boolean = lastSignal == Signal.BUY && lastSignalIndex >= bars - n

    /**
     * 순위용 점수. 수익률만 보면 거래 한두 번의 운이 크게 잡히므로, 거래 수가 적으면 깎는다.
     */
    val score: Double get() {
        if (closedTrades == 0) return -1000.0
        val confidence = ln(1.0 + closedTrades) / ln(21.0)
        return (totalReturnPct * 0.5 + (winRatePct - 50) * 0.8 + (avgReturnPct * 4)) * confidence.coerceAtMost(1.0) - maxDrawdownPct * 0.2
    }
}

object Backtester {
    fun run(strategy: Strategy, data: PriceData, config: BacktestConfig = BacktestConfig()): BacktestResult {
        val n = data.size
        val signals = strategy.signals(data)
        val equity = DoubleArray(n) { 1.0 }
        val trades = ArrayList<Trade>()
        val markers = ArrayList<TradeMarker>()
        val halfCost = config.roundTripCostPct / 200.0

        var holding = false
        var entryIndex = -1
        var entryPrice = 0.0
        var capital = 1.0
        var pendingBuy = false
        var pendingSell = false
        var lastSignal: Signal? = null
        var lastSignalIndex = -1

        fun close(i: Int, price: Double, reason: String) {
            val ret = price / entryPrice * (1 - halfCost) / (1 + halfCost) - 1
            capital *= (1 + ret)
            trades += Trade(entryIndex, data.candles[entryIndex].time, entryPrice, i, data.candles[i].time, price, ret * 100, false, reason)
            markers += TradeMarker(i, Signal.SELL, price)
            holding = false
        }

        for (i in 0 until n) {
            val c = data.candles[i]
            // 1) 전날 신호를 오늘 시가에 실행
            if (pendingBuy && !holding) {
                holding = true; entryIndex = i; entryPrice = c.open.takeIf { it > 0 } ?: c.close
                markers += TradeMarker(i, Signal.BUY, entryPrice)
            } else if (pendingSell && holding) {
                close(i, c.open.takeIf { it > 0 } ?: c.close, "매도 신호")
            }
            pendingBuy = false; pendingSell = false

            // 2) 장중 손절·익절
            if (holding && i > entryIndex) {
                val stop = config.stopLossPct?.let { entryPrice * (1 - it / 100) }
                val take = config.takeProfitPct?.let { entryPrice * (1 + it / 100) }
                when {
                    stop != null && c.low <= stop -> close(i, minOf(stop, c.open), "손절")
                    take != null && c.high >= take -> close(i, maxOf(take, c.open), "익절")
                }
            }

            // 3) 종가 기준 신호 확인
            val s = signals[i]
            if (s != null) {
                lastSignal = s; lastSignalIndex = i
            }
            if (!holding && s == Signal.BUY) pendingBuy = true
            if (holding && (s == Signal.SELL || (strategy.holdBars != null && i - entryIndex >= strategy.holdBars))) {
                pendingSell = true
            }

            equity[i] = if (holding) capital * (c.close / entryPrice) * (1 - halfCost) else capital
        }

        var openTrade: Trade? = null
        if (holding && n > 0) {
            val last = data.candles[n - 1]
            val ret = last.close / entryPrice * (1 - halfCost) / (1 + halfCost) - 1
            openTrade = Trade(entryIndex, data.candles[entryIndex].time, entryPrice, n - 1, last.time, last.close, ret * 100, true, "보유 중")
        }

        val closed = trades.toList()
        val all = closed + listOfNotNull(openTrade)
        val wins = closed.count { it.win }
        val grossWin = closed.filter { it.returnPct > 0 }.sumOf { it.returnPct }
        val grossLoss = -closed.filter { it.returnPct < 0 }.sumOf { it.returnPct }
        var peak = 1.0
        var mdd = 0.0
        for (e in equity) {
            peak = max(peak, e)
            mdd = max(mdd, (peak - e) / peak)
        }
        val firstValid = (0 until n).firstOrNull { it >= strategy.minBars } ?: 0
        val buyHold = if (n > 1 && data.close[firstValid] > 0) (data.close[n - 1] / data.close[firstValid] - 1) * 100 else 0.0
        return BacktestResult(
            strategy = strategy,
            trades = all,
            totalReturnPct = ((equity.lastOrNull() ?: 1.0) - 1) * 100,
            winRatePct = if (closed.isEmpty()) 0.0 else wins * 100.0 / closed.size,
            closedTrades = closed.size,
            avgReturnPct = if (closed.isEmpty()) 0.0 else closed.sumOf { it.returnPct } / closed.size,
            maxDrawdownPct = mdd * 100,
            buyHoldReturnPct = buyHold,
            profitFactor = if (grossLoss == 0.0) (if (grossWin > 0) 99.0 else 0.0) else grossWin / grossLoss,
            avgHoldBars = if (all.isEmpty()) 0.0 else all.sumOf { it.bars }.toDouble() / all.size,
            equity = equity,
            markers = markers,
            holding = holding,
            lastSignal = lastSignal,
            lastSignalIndex = lastSignalIndex,
            bars = n,
            buySignals = signals.count { it == Signal.BUY },
            activeBars = (n - strategy.minBars).coerceAtLeast(1),
        )
    }

    fun runAll(data: PriceData, config: BacktestConfig, strategies: List<Strategy> = Strategies.all): List<BacktestResult> =
        strategies.map { run(it, data, config) }
}

/** 한 종목에 모든 전략을 돌린 결과 요약 (지표분석 화면) */
data class IndicatorReport(
    val results: List<BacktestResult>,
    /** 지금 보유(매수 상태)인 전략 수 */
    val holdingCount: Int,
    /** 최근 5봉 안에 매수 신호를 낸 전략 수 / 매도 신호를 낸 전략 수 */
    val recentBuy: Int,
    val recentSell: Int,
) {
    val total: Int get() = results.size

    /** 마지막 신호가 매수인 전략 / 매도인 전략의 비율 (%) — 신호가 없던 전략은 빼고 센다 */
    val lastBuySharePct: Double get() {
        val withSignal = results.filter { it.lastSignal != null }
        return if (withSignal.isEmpty()) 50.0 else withSignal.count { it.lastSignal == Signal.BUY } * 100.0 / withSignal.size
    }

    fun of(horizon: StrategyHorizon?): IndicatorReport = if (horizon == null) this else {
        val r = results.filter { it.strategy.horizon == horizon }
        val last = r.firstOrNull()?.bars ?: 0
        IndicatorReport(r, r.count { it.holding }, r.count { it.lastSignal == Signal.BUY && it.lastSignalIndex >= last - 5 }, r.count { it.lastSignal == Signal.SELL && it.lastSignalIndex >= last - 5 })
    }

    /** 매수 쪽 신호 비율 (%) — 보유 중이거나 최근 매수 신호를 낸 전략의 비율 */
    val buyRatioPct: Double get() = if (total == 0) 0.0 else results.count { it.holding || it.recentBuy(5) } * 100.0 / total

    val byReturn: List<BacktestResult> get() = results.sortedByDescending { it.totalReturnPct }
    val byWinRate: List<BacktestResult> get() = results.filter { it.closedTrades >= 3 }.sortedByDescending { it.winRatePct }
    val byScore: List<BacktestResult> get() = results.sortedByDescending { it.score }

    companion object {
        fun build(data: PriceData, config: BacktestConfig, strategies: List<Strategy> = Strategies.all): IndicatorReport {
            val results = Backtester.runAll(data, config, strategies)
            return IndicatorReport(
                results = results,
                holdingCount = results.count { it.holding },
                recentBuy = results.count { it.lastSignal == Signal.BUY && it.lastSignalIndex >= data.size - 5 },
                recentSell = results.count { it.lastSignal == Signal.SELL && it.lastSignalIndex >= data.size - 5 },
            )
        }
    }
}
