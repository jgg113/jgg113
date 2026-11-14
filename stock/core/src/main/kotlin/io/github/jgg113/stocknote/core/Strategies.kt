package io.github.jgg113.stocknote.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Signal(val label: String) { BUY("매수"), SELL("매도") }

enum class StrategyGroup(val label: String) {
    TREND("추세 추종"),
    MOMENTUM("모멘텀"),
    REVERSAL("과매도 반등"),
    BREAKOUT("돌파"),
    VOLUME("거래량"),
    CANDLE("캔들 패턴"),
}

/**
 * 매매 전략 하나. 봉이 끝났을 때(종가 기준) [buy]·[sell] 조건을 본다.
 * [holdBars] 가 있으면 매도 신호가 없어도 그만큼 보유한 뒤 판다.
 */
class Strategy(
    val id: String,
    val name: String,
    val group: StrategyGroup,
    val description: String,
    val minBars: Int,
    val holdBars: Int? = null,
    private val buy: (PriceData, Int) -> Boolean,
    private val sell: (PriceData, Int) -> Boolean,
) {
    fun signals(d: PriceData): Array<Signal?> = Array(d.size) { i ->
        if (i < minBars) return@Array null
        val b = runCatching { buy(d, i) }.getOrDefault(false)
        val s = runCatching { sell(d, i) }.getOrDefault(false)
        when {
            b && !s -> Signal.BUY
            s && !b -> Signal.SELL
            else -> null
        }
    }

    override fun toString(): String = name
}

/** 기본 제공 전략. 모두 널리 알려진 지표 조건을 이 앱에서 직접 정한 것이다. */
object Strategies {
    private fun DoubleArray.at(i: Int) = if (i in indices) this[i] else Double.NaN
    private fun finite(vararg v: Double) = v.none { it.isNaN() }

    private fun maCross(id: String, fast: Int, slow: Int, group: StrategyGroup = StrategyGroup.TREND) = Strategy(
        id, "이동평균 $fast/$slow 교차", group,
        "${fast}일선이 ${slow}일선을 위로 뚫으면(골든크로스) 사고, 아래로 뚫으면(데드크로스) 팝니다.",
        slow + 1,
        buy = { d, i -> Cross.up(d.sma(fast), d.sma(slow), i) },
        sell = { d, i -> Cross.down(d.sma(fast), d.sma(slow), i) },
    )

    private fun priceMa(id: String, n: Int) = Strategy(
        id, "종가 ${n}일선 돌파", StrategyGroup.TREND,
        "종가가 ${n}일 이동평균 위로 올라서면 사고, 아래로 내려가면 팝니다.",
        n + 1,
        buy = { d, i -> Cross.up(d.close, d.sma(n), i) },
        sell = { d, i -> Cross.down(d.close, d.sma(n), i) },
    )

    private fun isHammer(d: PriceData, i: Int): Boolean {
        val body = abs(d.close[i] - d.open[i])
        val range = d.high[i] - d.low[i]
        if (range <= 0) return false
        val lowerShadow = min(d.open[i], d.close[i]) - d.low[i]
        val upperShadow = d.high[i] - max(d.open[i], d.close[i])
        return lowerShadow >= body * 2 && upperShadow <= range * 0.15 && body >= range * 0.05
    }

    val all: List<Strategy> = listOf(
        // ── 추세 추종 ──
        maCross("ma5_20", 5, 20),
        maCross("ma20_60", 20, 60),
        maCross("ma60_120", 60, 120),
        Strategy(
            "ema12_26", "지수이평 12/26 교차", StrategyGroup.TREND,
            "12일 지수이동평균이 26일 지수이동평균을 위로 뚫으면 사고, 아래로 뚫으면 팝니다.",
            27,
            buy = { d, i -> Cross.up(d.ema(12), d.ema(26), i) },
            sell = { d, i -> Cross.down(d.ema(12), d.ema(26), i) },
        ),
        priceMa("price_ma20", 20),
        priceMa("price_ma60", 60),
        Strategy(
            "ma_align", "이평선 정배열 시작", StrategyGroup.TREND,
            "5·20·60·120일선이 위에서부터 차례로 늘어서는(정배열) 첫날 사고, 5일선이 20일선 아래로 내려가면 팝니다.",
            121,
            buy = { d, i ->
                fun aligned(k: Int) = d.sma(5).at(k) > d.sma(20).at(k) && d.sma(20).at(k) > d.sma(60).at(k) && d.sma(60).at(k) > d.sma(120).at(k)
                aligned(i) && !aligned(i - 1)
            },
            sell = { d, i -> Cross.down(d.sma(5), d.sma(20), i) },
        ),
        Strategy(
            "ma20_pullback", "상승 추세 20일선 눌림목", StrategyGroup.TREND,
            "20일선이 60일선 위에 있는 상승 추세에서 주가가 20일선 근처까지 내려왔다가 양봉으로 버티면 사고, 60일선 아래로 떨어지면 팝니다.",
            61,
            buy = { d, i ->
                val m20 = d.sma(20)[i]; val m60 = d.sma(60)[i]
                finite(m20, m60) && m20 > m60 && d.close[i] > m20 && d.low[i] <= m20 * 1.01 && d.close[i] > d.open[i] &&
                    d.sma(20)[i] > d.sma(20).at(i - 5)
            },
            sell = { d, i -> Cross.down(d.close, d.sma(60), i) },
        ),
        Strategy(
            "dmi_cross", "DMI 방향 전환 (ADX 20↑)", StrategyGroup.TREND,
            "+DI 가 -DI 를 위로 넘고 추세 강도(ADX)가 20 이상이면 사고, +DI 가 -DI 아래로 내려가면 팝니다.",
            30,
            buy = { d, i -> val m = d.dmi(); Cross.up(m.plus, m.minus, i) && m.adx[i] >= 20 },
            sell = { d, i -> val m = d.dmi(); Cross.down(m.plus, m.minus, i) },
        ),
        Strategy(
            "ichimoku_tk", "일목 전환선·기준선 교차", StrategyGroup.TREND,
            "구름대 위에서 전환선이 기준선을 위로 넘으면 사고, 아래로 내려가면 팝니다.",
            80,
            buy = { d, i ->
                val k = d.ichimoku()
                Cross.up(k.tenkan, k.kijun, i) && d.close[i] > max(k.spanA[i], k.spanB[i])
            },
            sell = { d, i -> val k = d.ichimoku(); Cross.down(k.tenkan, k.kijun, i) },
        ),
        Strategy(
            "ichimoku_cloud", "일목 구름대 돌파", StrategyGroup.BREAKOUT,
            "종가가 구름대 위로 올라서면 사고, 구름대 아래로 내려가면 팝니다.",
            80,
            buy = { d, i ->
                val k = d.ichimoku()
                val top = max(k.spanA[i], k.spanB[i]); val prevTop = max(k.spanA.at(i - 1), k.spanB.at(i - 1))
                finite(top, prevTop) && d.close[i] > top && d.close[i - 1] <= prevTop
            },
            sell = { d, i ->
                val k = d.ichimoku()
                val bottom = min(k.spanA[i], k.spanB[i]); val prevBottom = min(k.spanA.at(i - 1), k.spanB.at(i - 1))
                finite(bottom, prevBottom) && d.close[i] < bottom && d.close[i - 1] >= prevBottom
            },
        ),
        Strategy(
            "sar", "파라볼릭 SAR 전환", StrategyGroup.TREND,
            "SAR 점이 주가 아래로 바뀌면(상승 전환) 사고, 주가 위로 바뀌면(하락 전환) 팝니다.",
            10,
            buy = { d, i -> val s = d.sar(); s.rising[i] && !s.rising[i - 1] && !s.value[i - 1].isNaN() },
            sell = { d, i -> val s = d.sar(); !s.rising[i] && s.rising[i - 1] },
        ),
        Strategy(
            "aroon", "아룬 교차", StrategyGroup.TREND,
            "아룬 업이 아룬 다운을 위로 넘고 70 이상이면 사고, 아래로 내려가면 팝니다.",
            30,
            buy = { d, i -> val a = d.aroon(); Cross.up(a.up, a.down, i) && a.up[i] >= 70 },
            sell = { d, i -> val a = d.aroon(); Cross.down(a.up, a.down, i) },
        ),

        // ── 모멘텀 ──
        Strategy(
            "macd_signal", "MACD 시그널 교차", StrategyGroup.MOMENTUM,
            "MACD 선이 시그널 선을 위로 넘으면 사고, 아래로 내려가면 팝니다.",
            35,
            buy = { d, i -> val m = d.macd(); Cross.up(m.line, m.signal, i) },
            sell = { d, i -> val m = d.macd(); Cross.down(m.line, m.signal, i) },
        ),
        Strategy(
            "macd_zero", "MACD 0선 돌파", StrategyGroup.MOMENTUM,
            "MACD 가 0 위로 올라서면 사고, 0 아래로 내려가면 팝니다.",
            35,
            buy = { d, i -> Cross.upLevel(d.macd().line, 0.0, i) },
            sell = { d, i -> Cross.downLevel(d.macd().line, 0.0, i) },
        ),
        Strategy(
            "macd_hist", "MACD 바닥권 반전", StrategyGroup.REVERSAL,
            "MACD 가 0 아래에 있을 때 히스토그램이 음수에서 양수로 바뀌면 사고, 양수에서 음수로 바뀌면 팝니다.",
            35,
            buy = { d, i -> val m = d.macd(); Cross.upLevel(m.hist, 0.0, i) && m.line[i] < 0 },
            sell = { d, i -> Cross.downLevel(d.macd().hist, 0.0, i) },
        ),
        Strategy(
            "rsi50", "RSI 50 돌파", StrategyGroup.MOMENTUM,
            "RSI(14)가 50 위로 올라서면 사고, 50 아래로 내려가면 팝니다.",
            20,
            buy = { d, i -> Cross.upLevel(d.rsi(), 50.0, i) },
            sell = { d, i -> Cross.downLevel(d.rsi(), 50.0, i) },
        ),
        Strategy(
            "roc_zero", "가격변화율(ROC) 0선 돌파", StrategyGroup.MOMENTUM,
            "12일 가격변화율이 0 위로 올라서면 사고, 0 아래로 내려가면 팝니다.",
            15,
            buy = { d, i -> Cross.upLevel(d.roc(), 0.0, i) },
            sell = { d, i -> Cross.downLevel(d.roc(), 0.0, i) },
        ),
        Strategy(
            "trix", "TRIX 시그널 교차", StrategyGroup.MOMENTUM,
            "TRIX 선이 시그널 선을 위로 넘으면 사고, 아래로 내려가면 팝니다.",
            60,
            buy = { d, i -> val t = d.trix(); Cross.up(t.line, t.signal, i) },
            sell = { d, i -> val t = d.trix(); Cross.down(t.line, t.signal, i) },
        ),
        Strategy(
            "cci_zero", "CCI 0선 돌파", StrategyGroup.MOMENTUM,
            "CCI(20)가 0 위로 올라서면 사고, 0 아래로 내려가면 팝니다.",
            25,
            buy = { d, i -> Cross.upLevel(d.cci(), 0.0, i) },
            sell = { d, i -> Cross.downLevel(d.cci(), 0.0, i) },
        ),

        // ── 과매도 반등 ──
        Strategy(
            "rsi30", "RSI 과매도 탈출", StrategyGroup.REVERSAL,
            "RSI(14)가 30 아래에서 위로 올라오면 사고, 70 위에서 아래로 내려오면 팝니다.",
            20,
            buy = { d, i -> Cross.upLevel(d.rsi(), 30.0, i) },
            sell = { d, i -> Cross.downLevel(d.rsi(), 70.0, i) },
        ),
        Strategy(
            "rsi2", "RSI(2) 단기 급락 매수", StrategyGroup.REVERSAL,
            "200일선 위 상승 종목이 이틀 RSI 10 아래로 급락하면 사고, 종가가 5일선을 넘으면 팝니다.",
            201,
            buy = { d, i -> val r = d.rsi(2)[i]; finite(r, d.sma(200)[i]) && d.close[i] > d.sma(200)[i] && r < 10 },
            sell = { d, i -> finite(d.sma(5)[i]) && d.close[i] > d.sma(5)[i] },
        ),
        Strategy(
            "bb_lower", "볼린저 하단 복귀", StrategyGroup.REVERSAL,
            "주가가 볼린저 하단 밖으로 나갔다가 다시 안으로 들어오면 사고, 중심선에 닿으면 팝니다.",
            21,
            buy = { d, i -> Cross.up(d.close, d.bollinger().lower, i) },
            sell = { d, i -> Cross.up(d.close, d.bollinger().middle, i) },
        ),
        Strategy(
            "stoch20", "스토캐스틱 과매도 탈출", StrategyGroup.REVERSAL,
            "슬로우 %K 가 20 위로 올라오면 사고, 80 아래로 내려오면 팝니다.",
            20,
            buy = { d, i -> Cross.upLevel(d.stochastic().k, 20.0, i) },
            sell = { d, i -> Cross.downLevel(d.stochastic().k, 80.0, i) },
        ),
        Strategy(
            "stoch_cross", "스토캐스틱 K·D 교차", StrategyGroup.REVERSAL,
            "%K 가 30 아래에서 %D 를 위로 넘으면 사고, 70 위에서 아래로 넘으면 팝니다.",
            20,
            buy = { d, i -> val s = d.stochastic(); Cross.up(s.k, s.d, i) && s.k[i] < 30 },
            sell = { d, i -> val s = d.stochastic(); Cross.down(s.k, s.d, i) && s.k[i] > 70 },
        ),
        Strategy(
            "williams", "윌리엄스 %R 반등", StrategyGroup.REVERSAL,
            "%R 이 -80 위로 올라오면 사고, -20 아래로 내려오면 팝니다.",
            15,
            buy = { d, i -> Cross.upLevel(d.williams(), -80.0, i) },
            sell = { d, i -> Cross.downLevel(d.williams(), -20.0, i) },
        ),
        Strategy(
            "cci100", "CCI -100 반등", StrategyGroup.REVERSAL,
            "CCI(20)가 -100 위로 올라오면 사고, +100 아래로 내려오면 팝니다.",
            25,
            buy = { d, i -> Cross.upLevel(d.cci(), -100.0, i) },
            sell = { d, i -> Cross.downLevel(d.cci(), 100.0, i) },
        ),
        Strategy(
            "mfi20", "MFI 자금 유입 반등", StrategyGroup.REVERSAL,
            "자금흐름지수(MFI)가 20 위로 올라오면 사고, 80 아래로 내려오면 팝니다.",
            20,
            buy = { d, i -> Cross.upLevel(d.mfi(), 20.0, i) },
            sell = { d, i -> Cross.downLevel(d.mfi(), 80.0, i) },
        ),
        Strategy(
            "disparity", "이격도 과매도 반등", StrategyGroup.REVERSAL,
            "20일 이격도가 92 아래인 상태에서 오르는 날 사고, 이격도가 105 를 넘으면 팝니다.",
            21,
            buy = { d, i -> val x = d.disparity(); finite(x[i], x[i - 1]) && x[i - 1] < 92 && d.close[i] > d.close[i - 1] },
            sell = { d, i -> Cross.upLevel(d.disparity(), 105.0, i) },
        ),
        Strategy(
            "envelope", "엔벨로프 하단 반등", StrategyGroup.REVERSAL,
            "20일선 -10% 엔벨로프 하단에 닿은 뒤 양봉이 나오면 사고, 20일선에 닿으면 팝니다.",
            21,
            buy = { d, i -> val e = d.envelope(); finite(e.lower[i]) && d.low[i] <= e.lower[i] && d.close[i] > d.open[i] },
            sell = { d, i -> Cross.up(d.close, d.sma(20), i) },
        ),
        Strategy(
            "psych", "심리도 바닥 반등", StrategyGroup.REVERSAL,
            "심리도(12일)가 25 이하일 때 오르는 날 사고, 75 이상이 되면 팝니다.",
            15,
            buy = { d, i -> val p = d.psychological(); finite(p[i - 1]) && p[i - 1] <= 25 && d.close[i] > d.close[i - 1] },
            sell = { d, i -> Cross.upLevel(d.psychological(), 74.9, i) },
        ),
        Strategy(
            "rebound3", "3일 하락 후 반등", StrategyGroup.REVERSAL,
            "사흘 연속 떨어진 뒤 전날 고가를 넘는 양봉이 나오면 사고, 5일 보유하거나 5일선 아래로 마감하면 팝니다.",
            10, holdBars = 5,
            buy = { d, i ->
                i >= 4 && d.close[i - 1] < d.close[i - 2] && d.close[i - 2] < d.close[i - 3] && d.close[i - 3] < d.close[i - 4] &&
                    d.close[i] > d.high[i - 1] && d.close[i] > d.open[i]
            },
            sell = { d, i -> Cross.down(d.close, d.sma(5), i) },
        ),

        // ── 돌파 ──
        Strategy(
            "bb_upper", "볼린저 상단 돌파", StrategyGroup.BREAKOUT,
            "종가가 볼린저 상단을 뚫으면 사고, 중심선 아래로 내려가면 팝니다.",
            21,
            buy = { d, i -> Cross.up(d.close, d.bollinger().upper, i) },
            sell = { d, i -> Cross.down(d.close, d.bollinger().middle, i) },
        ),
        Strategy(
            "bb_squeeze", "볼린저 수축 후 돌파", StrategyGroup.BREAKOUT,
            "밴드 폭이 최근 120일 중 가장 좁은 편(하위 15%)일 때 상단을 뚫으면 사고, 중심선 아래로 내려가면 팝니다.",
            140,
            buy = { d, i ->
                val bw = d.memo("bbw") { Indicators.bandwidth(d.bollinger()) }
                val window = (i - 120 until i).map { bw.at(it) }.filterNot { it.isNaN() }.sorted()
                window.size >= 100 && bw[i - 1] <= window[(window.size * 0.15).toInt()] && Cross.up(d.close, d.bollinger().upper, i)
            },
            sell = { d, i -> Cross.down(d.close, d.bollinger().middle, i) },
        ),
        Strategy(
            "donchian20", "20일 신고가 돌파 (채널)", StrategyGroup.BREAKOUT,
            "종가가 지난 20일 최고가를 넘으면 사고, 지난 10일 최저가 아래로 내려가면 팝니다.",
            21,
            buy = { d, i -> val h = d.highest(20).at(i - 1); finite(h) && d.close[i] > h && d.close[i - 1] <= h },
            sell = { d, i -> val l = d.lowest(10).at(i - 1); finite(l) && d.close[i] < l },
        ),
        Strategy(
            "high52w", "52주 신고가 돌파", StrategyGroup.BREAKOUT,
            "종가가 지난 1년(250일) 최고가를 넘으면 사고, 20일선 아래로 내려가면 팝니다.",
            251,
            buy = { d, i -> val h = d.highest(250).at(i - 1); finite(h) && d.close[i] > h && d.close[i - 1] <= h },
            sell = { d, i -> Cross.down(d.close, d.sma(20), i) },
        ),
        Strategy(
            "keltner", "켈트너 채널 돌파", StrategyGroup.BREAKOUT,
            "종가가 켈트너 채널 상단을 뚫으면 사고, 중심선 아래로 내려가면 팝니다.",
            25,
            buy = { d, i -> Cross.up(d.close, d.keltner().upper, i) },
            sell = { d, i -> Cross.down(d.close, d.keltner().middle, i) },
        ),
        Strategy(
            "gap_up", "갭 상승 + 거래량", StrategyGroup.BREAKOUT,
            "시가가 전날 고가보다 2% 넘게 높게 시작하고 거래량이 평소의 2배 이상인 양봉이면 사고, 갭이 메워지거나 10일이 지나면 팝니다.",
            21, holdBars = 10,
            buy = { d, i ->
                val vr = d.volumeRatio()[i]
                finite(vr) && d.open[i] > d.high[i - 1] * 1.02 && vr >= 2 && d.close[i] > d.open[i]
            },
            sell = { d, i ->
                // 매수 봉의 전날 고가를 정확히 알 수 없으므로 최근 5일 안의 갭 아래로 내려가면 판다.
                (1..5).any { k -> i - k - 1 >= 0 && d.open[i - k] > d.high[i - k - 1] * 1.02 && d.close[i] < d.high[i - k - 1] }
            },
        ),

        // ── 거래량 ──
        Strategy(
            "volume_spike", "거래량 급증 장대양봉", StrategyGroup.VOLUME,
            "거래량이 20일 평균의 3배 이상이면서 4% 넘게 오른 양봉이면 사고, 5일선 아래로 마감하면 팝니다.",
            21,
            buy = { d, i ->
                val vr = d.volumeRatio()[i]
                finite(vr) && vr >= 3 && d.close[i] > d.open[i] && d.close[i] >= d.close[i - 1] * 1.04
            },
            sell = { d, i -> Cross.down(d.close, d.sma(5), i) },
        ),
        Strategy(
            "obv", "OBV 상승 전환", StrategyGroup.VOLUME,
            "OBV 가 20일 평균을 위로 넘고 주가가 20일선 위에 있으면 사고, OBV 가 20일 평균 아래로 내려가면 팝니다.",
            30,
            buy = { d, i ->
                val o = d.obv(); val m = d.memo("obvma20") { Indicators.sma(o, 20) }
                Cross.up(o, m, i) && d.close[i] > d.sma(20)[i]
            },
            sell = { d, i -> val o = d.obv(); Cross.down(o, d.memo("obvma20") { Indicators.sma(o, 20) }, i) },
        ),

        // ── 캔들 ──
        Strategy(
            "hammer", "하락 중 망치형", StrategyGroup.CANDLE,
            "20일선 아래 하락 구간에서 아래꼬리가 긴 망치형 캔들이 나오면 사고, 20일선을 넘거나 10일이 지나면 팝니다.",
            21, holdBars = 10,
            buy = { d, i -> finite(d.sma(20)[i]) && d.close[i] < d.sma(20)[i] && d.close[i - 1] < d.close[i - 3] && isHammer(d, i) },
            sell = { d, i -> Cross.up(d.close, d.sma(20), i) },
        ),
        Strategy(
            "engulfing", "상승·하락 장악형", StrategyGroup.CANDLE,
            "하락 구간에서 양봉이 전날 음봉 몸통을 완전히 감싸면 사고, 상승 구간에서 반대 모양(하락 장악형)이 나오면 팝니다.",
            21,
            buy = { d, i ->
                d.close[i - 1] < d.open[i - 1] && d.close[i] > d.open[i] &&
                    d.open[i] <= d.close[i - 1] && d.close[i] >= d.open[i - 1] && d.close[i] < d.sma(20)[i]
            },
            sell = { d, i ->
                d.close[i - 1] > d.open[i - 1] && d.close[i] < d.open[i] &&
                    d.open[i] >= d.close[i - 1] && d.close[i] <= d.open[i - 1] && d.close[i] > d.sma(20)[i]
            },
        ),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String): Strategy? = byId[id]
}
