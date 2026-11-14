package io.github.jgg113.stocknote.core

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * 차트 게임: 실제 과거 차트의 한 구간을 이름·날짜를 숨긴 채 보여 주고, 한 봉씩 넘기며 사고팔아
 * 그냥 들고 있었을 때(바이앤홀드)보다 잘했는지 겨룬다.
 */
@Serializable
data class ChartGame(
    val assetName: String,
    val assetKey: String,
    val candles: List<Candle>,
    /** 처음에 보여 주는 봉 수 */
    val visible: Int,
    /** 지금 보이는 마지막 봉 위치 */
    val cursor: Int,
    val cash: Double = 1.0,
    val shares: Double = 0.0,
    val entryPrice: Double = 0.0,
    val trades: Int = 0,
    val wins: Int = 0,
    val costPct: Double = 0.25,
    val log: List<GameAction> = emptyList(),
) {
    val end: Int get() = candles.size - 1
    val finished: Boolean get() = cursor >= end
    val price: Double get() = candles[cursor].close
    val holding: Boolean get() = shares > 0
    val equity: Double get() = cash + shares * price
    val returnPct: Double get() = (equity - 1) * 100
    val buyHoldPct: Double get() = (candles[cursor].close / candles[visible - 1].close - 1) * 100
    val remaining: Int get() = end - cursor
    val shown: List<Candle> get() = candles.subList(0, cursor + 1)

    /** 지금 종가에 전부 산다 */
    fun buy(): ChartGame {
        if (holding || finished) return this
        val fee = cash * costPct / 200
        return copy(shares = (cash - fee) / price, cash = 0.0, entryPrice = price, log = log + GameAction(cursor, Signal.BUY, price))
    }

    /** 지금 종가에 전부 판다 */
    fun sell(): ChartGame {
        if (!holding) return this
        val proceeds = shares * price
        val fee = proceeds * costPct / 200
        val win = price > entryPrice
        return copy(cash = proceeds - fee, shares = 0.0, trades = trades + 1, wins = wins + if (win) 1 else 0, log = log + GameAction(cursor, Signal.SELL, price))
    }

    fun next(steps: Int = 1): ChartGame = copy(cursor = (cursor + steps).coerceAtMost(end))

    /** 끝까지 넘기고 들고 있는 것은 마지막 종가에 판다 */
    fun finish(): ChartGame = copy(cursor = end).sell()

    /** 결과 점수: 바이앤홀드보다 얼마나 잘했는지 (+ 이면 이김) */
    val edgePct: Double get() = returnPct - buyHoldPct

    companion object {
        /**
         * [history] 에서 무작위 구간을 고른다. 처음 [visible] 봉을 보여 주고 [play] 봉 동안 게임한다.
         */
        fun start(name: String, key: String, history: List<Candle>, visible: Int = 120, play: Int = 40, random: Random = Random.Default, costPct: Double = 0.25): ChartGame? {
            val need = visible + play
            if (history.size < need) return null
            val startMax = history.size - need
            val start = if (startMax <= 0) 0 else random.nextInt(startMax + 1)
            val window = history.subList(start, start + need)
            return ChartGame(name, key, window, visible, visible - 1, costPct = costPct)
        }
    }
}

@Serializable
data class GameAction(val index: Int, val signal: Signal, val price: Double)

/** '오를까 내릴까' 빠른 퀴즈: 차트를 보고 다음 [ahead] 봉 뒤 종가가 오를지 맞힌다. */
@Serializable
data class UpDownQuiz(
    val assetName: String,
    val assetKey: String,
    val candles: List<Candle>,
    val visible: Int,
    val ahead: Int,
) {
    val shown: List<Candle> get() = candles.subList(0, visible)
    val answerUp: Boolean get() = candles.last().close > candles[visible - 1].close
    val movePct: Double get() = (candles.last().close / candles[visible - 1].close - 1) * 100

    companion object {
        fun start(name: String, key: String, history: List<Candle>, visible: Int = 100, ahead: Int = 10, random: Random = Random.Default): UpDownQuiz? {
            val need = visible + ahead
            if (history.size < need) return null
            val start = random.nextInt(history.size - need + 1)
            return UpDownQuiz(name, key, history.subList(start, start + need), visible, ahead)
        }
    }
}

/** 게임 기록 (기기에만 저장) */
@Serializable
data class GameStats(
    val games: Int = 0,
    val beatMarket: Int = 0,
    val totalEdgePct: Double = 0.0,
    val bestEdgePct: Double = 0.0,
    val quizzes: Int = 0,
    val quizCorrect: Int = 0,
) {
    fun record(game: ChartGame): GameStats = copy(
        games = games + 1,
        beatMarket = beatMarket + if (game.edgePct > 0) 1 else 0,
        totalEdgePct = totalEdgePct + game.edgePct,
        bestEdgePct = if (games == 0) game.edgePct else maxOf(bestEdgePct, game.edgePct),
    )

    fun recordQuiz(correct: Boolean): GameStats = copy(quizzes = quizzes + 1, quizCorrect = quizCorrect + if (correct) 1 else 0)
}
