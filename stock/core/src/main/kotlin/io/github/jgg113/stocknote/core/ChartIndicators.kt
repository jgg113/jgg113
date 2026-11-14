package io.github.jgg113.stocknote.core

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

enum class IndicatorKind { OVERLAY, PANEL }

enum class SeriesStyle { LINE, HISTOGRAM, DOTS, BARS }

/** 차트에 그릴 선 하나. [colorIndex] 는 화면 쪽 색 목록의 순번이다. */
class ChartSeries(val label: String, val values: DoubleArray, val style: SeriesStyle = SeriesStyle.LINE, val colorIndex: Int = 0)

class IndicatorOutput(
    val series: List<ChartSeries>,
    /** 가로 기준선 (RSI 30·70 등) */
    val guides: List<Double> = emptyList(),
    /** 값 범위가 정해진 지표 (0~100 등) */
    val fixedRange: Pair<Double, Double>? = null,
    /** 두 선 사이를 칠할 때 (볼린저·일목 구름) */
    val fill: Pair<Int, Int>? = null,
)

/** 차트 보조지표 목록. 개수 제한 없이 여러 개를 함께 켤 수 있다. */
enum class IndicatorType(
    val label: String,
    val kind: IndicatorKind,
    val paramNames: List<String>,
    val defaults: List<Double>,
    val description: String,
) {
    MA("이동평균선", IndicatorKind.OVERLAY, listOf("기간1", "기간2", "기간3", "기간4", "기간5"), listOf(5.0, 20.0, 60.0, 120.0, 0.0), "기간을 0 으로 두면 그 선은 그리지 않습니다."),
    EMA("지수이동평균", IndicatorKind.OVERLAY, listOf("기간1", "기간2", "기간3"), listOf(12.0, 26.0, 0.0), "최근 값에 무게를 더 둔 이동평균입니다."),
    BOLLINGER("볼린저 밴드", IndicatorKind.OVERLAY, listOf("기간", "배수"), listOf(20.0, 2.0), "이동평균 ± 표준편차 배수의 띠입니다."),
    ENVELOPE("엔벨로프", IndicatorKind.OVERLAY, listOf("기간", "폭(%)"), listOf(20.0, 6.0), "이동평균 위아래로 일정 비율 떨어진 띠입니다."),
    ICHIMOKU("일목균형표", IndicatorKind.OVERLAY, listOf("전환", "기준", "선행"), listOf(9.0, 26.0, 52.0), "전환선·기준선·선행스팬(구름)·후행스팬"),
    SAR("파라볼릭 SAR", IndicatorKind.OVERLAY, listOf("가속", "최대"), listOf(0.02, 0.2), "추세가 바뀌는 자리를 점으로 보여 줍니다."),
    DONCHIAN("돈치안 채널", IndicatorKind.OVERLAY, listOf("기간"), listOf(20.0), "기간 최고가·최저가 띠입니다."),
    KELTNER("켈트너 채널", IndicatorKind.OVERLAY, listOf("기간", "ATR 배수"), listOf(20.0, 2.0), "지수이평 ± ATR 배수의 띠입니다."),
    VOLUME("거래량", IndicatorKind.PANEL, listOf("평균 기간"), listOf(20.0), "막대는 거래량, 선은 평균 거래량입니다."),
    RSI("RSI", IndicatorKind.PANEL, listOf("기간", "시그널"), listOf(14.0, 9.0), "70 위는 과열, 30 아래는 과매도로 봅니다."),
    MACD("MACD", IndicatorKind.PANEL, listOf("단기", "장기", "시그널"), listOf(12.0, 26.0, 9.0), "단기·장기 지수이평의 차이와 그 평균"),
    STOCHASTIC("스토캐스틱 슬로우", IndicatorKind.PANEL, listOf("기간", "%K", "%D"), listOf(14.0, 3.0, 3.0), "기간 가격 범위 안에서 종가 위치 (0~100)"),
    WILLIAMS("윌리엄스 %R", IndicatorKind.PANEL, listOf("기간"), listOf(14.0), "-20 위는 과열, -80 아래는 과매도"),
    CCI("CCI", IndicatorKind.PANEL, listOf("기간"), listOf(20.0), "평균 가격에서 벗어난 정도 (±100 기준)"),
    DMI("DMI·ADX", IndicatorKind.PANEL, listOf("기간"), listOf(14.0), "+DI·-DI 로 방향, ADX 로 추세 강도"),
    ATR("ATR", IndicatorKind.PANEL, listOf("기간"), listOf(14.0), "하루 평균 변동 폭"),
    OBV("OBV", IndicatorKind.PANEL, listOf("평균 기간"), listOf(20.0), "오른 날 거래량을 더하고 내린 날 빼서 누적"),
    MFI("MFI", IndicatorKind.PANEL, listOf("기간"), listOf(14.0), "거래량을 반영한 RSI (80 과열, 20 과매도)"),
    ROC("ROC", IndicatorKind.PANEL, listOf("기간"), listOf(12.0), "기간 전 대비 가격 변화율 (%)"),
    MOMENTUM("모멘텀", IndicatorKind.PANEL, listOf("기간"), listOf(10.0), "기간 전 대비 가격 차이"),
    TRIX("TRIX", IndicatorKind.PANEL, listOf("기간", "시그널"), listOf(15.0, 9.0), "세 번 평활한 지수이평의 변화율"),
    AROON("아룬", IndicatorKind.PANEL, listOf("기간"), listOf(25.0), "최고가·최저가가 나온 뒤 지난 시간으로 추세를 봅니다."),
    DISPARITY("이격도", IndicatorKind.PANEL, listOf("기간"), listOf(20.0), "종가 / 이동평균 × 100"),
    PSYCHOLOGICAL("심리도", IndicatorKind.PANEL, listOf("기간"), listOf(12.0), "기간 중 오른 날의 비율 (75 과열, 25 침체)"),
    CMF("차이킨 머니플로", IndicatorKind.PANEL, listOf("기간"), listOf(20.0), "0 위면 매수세 우위"),
    VOLATILITY("변동성(표준편차)", IndicatorKind.PANEL, listOf("기간"), listOf(20.0), "일간 수익률 표준편차를 연환산한 값 (%)"),
    ;

    fun compute(d: PriceData, params: List<Double>): IndicatorOutput {
        fun p(i: Int) = params.getOrNull(i) ?: defaults.getOrElse(i) { 0.0 }
        fun pi(i: Int) = p(i).toInt().coerceAtLeast(1)
        val c = d.close
        return when (this) {
            MA -> IndicatorOutput((0 until 5).mapNotNull { i ->
                val n = p(i).toInt()
                if (n <= 0) null else ChartSeries("MA$n", Indicators.sma(c, n), colorIndex = i)
            })
            EMA -> IndicatorOutput((0 until 3).mapNotNull { i ->
                val n = p(i).toInt()
                if (n <= 0) null else ChartSeries("EMA$n", Indicators.ema(c, n), colorIndex = i + 5)
            })
            BOLLINGER -> Indicators.bollinger(c, pi(0), p(1)).let {
                IndicatorOutput(listOf(ChartSeries("상단", it.upper, colorIndex = 8), ChartSeries("중심", it.middle, colorIndex = 9), ChartSeries("하단", it.lower, colorIndex = 8)), fill = 0 to 2)
            }
            ENVELOPE -> Indicators.envelope(c, pi(0), p(1)).let {
                IndicatorOutput(listOf(ChartSeries("상단", it.upper, colorIndex = 10), ChartSeries("중심", it.middle, colorIndex = 9), ChartSeries("하단", it.lower, colorIndex = 10)))
            }
            ICHIMOKU -> Indicators.ichimoku(d.high, d.low, c, pi(0), pi(1), pi(2)).let {
                IndicatorOutput(
                    listOf(
                        ChartSeries("전환선", it.tenkan, colorIndex = 0), ChartSeries("기준선", it.kijun, colorIndex = 1),
                        ChartSeries("선행1", it.spanA, colorIndex = 11), ChartSeries("선행2", it.spanB, colorIndex = 12),
                        ChartSeries("후행", it.chikou, colorIndex = 13),
                    ),
                    fill = 2 to 3,
                )
            }
            SAR -> IndicatorOutput(listOf(ChartSeries("SAR", Indicators.parabolicSar(d.high, d.low, p(0), p(1)).value, SeriesStyle.DOTS, 14)))
            DONCHIAN -> Indicators.donchian(d.high, d.low, pi(0)).let {
                IndicatorOutput(listOf(ChartSeries("상단", it.upper, colorIndex = 15), ChartSeries("하단", it.lower, colorIndex = 15)), fill = 0 to 1)
            }
            KELTNER -> Indicators.keltner(d.high, d.low, c, pi(0), p(1)).let {
                IndicatorOutput(listOf(ChartSeries("상단", it.upper, colorIndex = 16), ChartSeries("중심", it.middle, colorIndex = 9), ChartSeries("하단", it.lower, colorIndex = 16)))
            }
            VOLUME -> IndicatorOutput(listOf(ChartSeries("거래량", d.volume, SeriesStyle.BARS, 0), ChartSeries("평균", Indicators.sma(d.volume, pi(0)), colorIndex = 1)))
            RSI -> Indicators.rsi(c, pi(0)).let { r ->
                IndicatorOutput(listOf(ChartSeries("RSI", r, colorIndex = 0), ChartSeries("시그널", Indicators.sma(r, pi(1)), colorIndex = 1)), listOf(30.0, 70.0), 0.0 to 100.0)
            }
            MACD -> Indicators.macd(c, pi(0), pi(1), pi(2)).let {
                IndicatorOutput(listOf(ChartSeries("히스토그램", it.hist, SeriesStyle.HISTOGRAM, 2), ChartSeries("MACD", it.line, colorIndex = 0), ChartSeries("시그널", it.signal, colorIndex = 1)), listOf(0.0))
            }
            STOCHASTIC -> Indicators.stochastic(d.high, d.low, c, pi(0), pi(1), pi(2)).let {
                IndicatorOutput(listOf(ChartSeries("%K", it.k, colorIndex = 0), ChartSeries("%D", it.d, colorIndex = 1)), listOf(20.0, 80.0), 0.0 to 100.0)
            }
            WILLIAMS -> IndicatorOutput(listOf(ChartSeries("%R", Indicators.williamsR(d.high, d.low, c, pi(0)))), listOf(-80.0, -20.0), -100.0 to 0.0)
            CCI -> IndicatorOutput(listOf(ChartSeries("CCI", Indicators.cci(d.high, d.low, c, pi(0)))), listOf(-100.0, 0.0, 100.0))
            DMI -> Indicators.dmi(d.high, d.low, c, pi(0)).let {
                IndicatorOutput(listOf(ChartSeries("+DI", it.plus, colorIndex = 3), ChartSeries("-DI", it.minus, colorIndex = 4), ChartSeries("ADX", it.adx, colorIndex = 0)), listOf(20.0))
            }
            ATR -> IndicatorOutput(listOf(ChartSeries("ATR", Indicators.atr(d.high, d.low, c, pi(0)))))
            OBV -> Indicators.obv(c, d.volume).let { o ->
                IndicatorOutput(listOf(ChartSeries("OBV", o, colorIndex = 0), ChartSeries("평균", Indicators.sma(o, pi(0)), colorIndex = 1)))
            }
            MFI -> IndicatorOutput(listOf(ChartSeries("MFI", Indicators.mfi(d.high, d.low, c, d.volume, pi(0)))), listOf(20.0, 80.0), 0.0 to 100.0)
            ROC -> IndicatorOutput(listOf(ChartSeries("ROC", Indicators.roc(c, pi(0)))), listOf(0.0))
            MOMENTUM -> IndicatorOutput(listOf(ChartSeries("모멘텀", Indicators.momentum(c, pi(0)))), listOf(0.0))
            TRIX -> Indicators.trix(c, pi(0), pi(1)).let {
                IndicatorOutput(listOf(ChartSeries("TRIX", it.line, colorIndex = 0), ChartSeries("시그널", it.signal, colorIndex = 1)), listOf(0.0))
            }
            AROON -> Indicators.aroon(d.high, d.low, pi(0)).let {
                IndicatorOutput(listOf(ChartSeries("업", it.up, colorIndex = 3), ChartSeries("다운", it.down, colorIndex = 4)), listOf(30.0, 70.0), 0.0 to 100.0)
            }
            DISPARITY -> IndicatorOutput(listOf(ChartSeries("이격도", Indicators.disparity(c, pi(0)))), listOf(100.0))
            PSYCHOLOGICAL -> IndicatorOutput(listOf(ChartSeries("심리도", Indicators.psychological(c, pi(0)))), listOf(25.0, 75.0), 0.0 to 100.0)
            CMF -> IndicatorOutput(listOf(ChartSeries("CMF", Indicators.cmf(d.high, d.low, c, d.volume, pi(0)))), listOf(0.0))
            VOLATILITY -> Indicators.stdDev(Indicators.returns(c), pi(0)).let { s ->
                IndicatorOutput(listOf(ChartSeries("변동성", DoubleArray(s.size) { s[it] * kotlin.math.sqrt(250.0) * 100 })))
            }
        }
    }
}

/** 화면에 켜 둔 보조지표 하나 */
@Serializable
data class IndicatorConfig(val type: IndicatorType, val params: List<Double> = type.defaults) {
    val title: String get() = type.label + if (params.isEmpty()) "" else "(" + params.filter { it != 0.0 }.joinToString(",") { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } + ")"

    companion object {
        val defaults = listOf(IndicatorConfig(IndicatorType.MA), IndicatorConfig(IndicatorType.VOLUME), IndicatorConfig(IndicatorType.RSI), IndicatorConfig(IndicatorType.MACD))
    }
}

/** 일봉을 주봉·월봉으로 묶고, 분봉을 더 긴 분봉으로 묶는다. */
object CandleAggregator {
    fun weekly(daily: List<Candle>): List<Candle> = group(daily) { c -> c.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).epochKst() }

    fun monthly(daily: List<Candle>): List<Candle> = group(daily) { c -> c.date.withDayOfMonth(1).epochKst() }

    fun minutes(source: List<Candle>, minutes: Int): List<Candle> = group(source) { c -> c.time - Math.floorMod(c.time, minutes * 60L) }

    fun group(source: List<Candle>, keyOf: (Candle) -> Long): List<Candle> {
        val out = ArrayList<Candle>()
        var key = Long.MIN_VALUE
        var cur: Candle? = null
        for (c in source) {
            val k = keyOf(c)
            val prev = cur
            if (prev == null || k != key) {
                if (prev != null) out += prev
                key = k
                cur = c.copy(time = k)
            } else {
                cur = prev.copy(high = maxOf(prev.high, c.high), low = minOf(prev.low, c.low), close = c.close, volume = prev.volume + c.volume, value = prev.value + c.value)
            }
        }
        cur?.let { out += it }
        return out
    }

    fun forTimeframe(daily: List<Candle>, tf: Timeframe): List<Candle> = when (tf) {
        Timeframe.WEEK -> weekly(daily)
        Timeframe.MONTH -> monthly(daily)
        else -> daily
    }
}

/** 시세 목록에 일봉을 이어 붙인다 (같은 날은 새 값으로 바꾼다). */
object CandleMerge {
    fun merge(old: List<Candle>, new: List<Candle>): List<Candle> {
        if (old.isEmpty()) return new.sortedBy { it.time }
        if (new.isEmpty()) return old
        val map = java.util.TreeMap<Long, Candle>()
        old.forEach { map[it.time] = it }
        new.forEach { map[it.time] = it }
        return map.values.toList()
    }

    /** 오늘 시세로 마지막 일봉을 만들거나 고친다 (장중 실시간 신호 계산용). */
    fun withQuote(daily: List<Candle>, quote: Quote, today: Long): List<Candle> {
        if (quote.price <= 0 || quote.open <= 0) return daily
        val bar = Candle(today, quote.open, maxOf(quote.high, quote.price), minOf(quote.low.takeIf { it > 0 } ?: quote.price, quote.price), quote.price, quote.volume, quote.value)
        if (daily.isEmpty()) return listOf(bar)
        val last = daily.last()
        return when {
            last.time == today -> daily.dropLast(1) + bar
            last.time < today -> daily + bar
            else -> daily
        }
    }
}
