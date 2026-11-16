package io.github.jgg113.stocknote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jgg113.stocknote.core.Candle
import io.github.jgg113.stocknote.core.ChartSeries
import io.github.jgg113.stocknote.core.Currency
import io.github.jgg113.stocknote.core.Fmt
import io.github.jgg113.stocknote.core.IndicatorConfig
import io.github.jgg113.stocknote.core.IndicatorKind
import io.github.jgg113.stocknote.core.IndicatorOutput
import io.github.jgg113.stocknote.core.PriceData
import io.github.jgg113.stocknote.core.SeriesStyle
import io.github.jgg113.stocknote.core.Signal
import io.github.jgg113.stocknote.core.TradeMarker
import io.github.jgg113.stocknote.ui.theme.PriceColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class ChartColors(val up: Color, val down: Color, val grid: Color, val text: Color, val cross: Color, val bg: Color, val future: Color, val path: Color)

/** 차트에 긋는 가로선 (목표가·손절가 등) */
data class ChartLevel(val label: String, val price: Double, val color: Color)

/**
 * 캔들 차트. 두 손가락으로 확대·축소, 한 손가락으로 옆으로 밀어 과거 보기, 눌러서 그 봉의 값 보기.
 * [indicators] 의 OVERLAY 는 가격 위에, PANEL 은 아래 칸에 그린다. 개수 제한은 없다.
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    indicators: List<IndicatorConfig>,
    currency: Currency,
    modifier: Modifier = Modifier,
    markers: List<TradeMarker> = emptyList(),
    intraday: Boolean = false,
    mainHeight: Dp = 260.dp,
    panelHeight: Dp = 86.dp,
    initialBars: Int = 90,
    /** 앞으로의 예상 가격 (마지막 봉 다음부터) — 오른쪽에 빈 칸을 두고 선으로 그린다 */
    future: DoubleArray? = null,
    levels: List<ChartLevel> = emptyList(),
    /** 이 위치부터 배경을 칠한다 (비슷한 과거 패턴의 '그 뒤' 구간 등) */
    shadeFrom: Int? = null,
    showExtremes: Boolean = true,
) {
    if (candles.isEmpty()) return
    val futureCount = future?.size ?: 0
    val slots = candles.size + futureCount
    val data = remember(candles) { PriceData(candles) }
    val outputs = remember(candles, indicators) { indicators.map { it to it.type.compute(data, it.params) } }
    val overlays = outputs.filter { it.first.type.kind == IndicatorKind.OVERLAY }
    val panels = outputs.filter { it.first.type.kind == IndicatorKind.PANEL }
    val markerAt = remember(markers) { markers.groupBy { it.index } }

    var visible by remember(candles.size, futureCount) { mutableFloatStateOf((min(initialBars, candles.size) + futureCount).toFloat().coerceAtLeast(10f)) }
    var endOffset by remember(candles.size, futureCount) { mutableFloatStateOf(0f) }
    var cross by remember(candles.size) { mutableIntStateOf(-1) }
    var width by remember { mutableStateOf(1f) }
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val colors = ChartColors(PriceColors.up, PriceColors.down, scheme.outlineVariant, scheme.onSurfaceVariant, scheme.onSurface, scheme.surface, scheme.tertiary.copy(alpha = 0.07f), scheme.tertiary)

    val total = mainHeight + panelHeight * panels.size + 18.dp
    Box(modifier.fillMaxWidth().height(total).background(scheme.surface)) {
        Canvas(
            Modifier.fillMaxWidth().height(total)
                // 가로로 밀면 과거를 보고, 세로로 밀면 화면이 스크롤되게 세로 드래그는 건드리지 않는다.
                .pointerInput(slots) {
                    detectHorizontalDragGestures { change, dragAmount ->
                        val barW = width / visible
                        endOffset = (endOffset + dragAmount / barW).coerceIn(0f, (slots - visible).coerceAtLeast(0f))
                        change.consume()
                    }
                }
                // 두 손가락으로 벌리거나 오므리면 확대·축소한다.
                .pointerInput(slots) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    val minBars = (futureCount + 10).toFloat().coerceAtMost(slots.toFloat())
                                    val lower = max(min(12f, slots.toFloat()), minBars)
                                    val upper = max(min(slots.toFloat(), 400f + futureCount), lower)
                                    visible = (visible / zoom).coerceIn(lower, upper)
                                    endOffset = endOffset.coerceIn(0f, (slots - visible).coerceAtLeast(0f))
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(slots) {
                    detectTapGestures { pos ->
                        val n = visible.roundToInt().coerceAtMost(slots)
                        val end = slots - endOffset.roundToInt()
                        val start = (end - n).coerceAtLeast(0)
                        val plotW = width - RIGHT_AXIS
                        val idx = start + (pos.x / (plotW / n)).toInt()
                        cross = if (idx == cross || idx !in start until min(end, candles.size)) -1 else idx
                    }
                },
        ) {
            width = size.width
            drawChart(candles, data, overlays.map { it.second }, panels, markerAt, visible, endOffset, cross, currency, intraday, mainHeight.toPx(), panelHeight.toPx(), colors, measurer, future, levels, shadeFrom, showExtremes)
        }
    }
}

private const val RIGHT_AXIS = 140f

private fun DrawScope.drawChart(
    candles: List<Candle>,
    data: PriceData,
    overlays: List<IndicatorOutput>,
    panels: List<Pair<IndicatorConfig, IndicatorOutput>>,
    markers: Map<Int, List<TradeMarker>>,
    visibleF: Float,
    endOffsetF: Float,
    cross: Int,
    currency: Currency,
    intraday: Boolean,
    mainH: Float,
    panelH: Float,
    c: ChartColors,
    tm: TextMeasurer,
    future: DoubleArray?,
    levels: List<ChartLevel>,
    shadeFrom: Int?,
    showExtremes: Boolean,
) {
    val futureCount = future?.size ?: 0
    val slots = candles.size + futureCount
    val n = visibleF.roundToInt().coerceIn(1, slots)
    val end = (slots - endOffsetF.roundToInt()).coerceIn(n, slots)
    val start = min(end - n, candles.size - 1).coerceAtLeast(0)
    // 실제 봉이 있는 끝 (그 뒤는 예상 구간)
    val cEnd = min(end, candles.size)
    val plotW = size.width - RIGHT_AXIS
    val barW = plotW / n
    fun x(i: Int) = (i - start) * barW + barW / 2
    val label = TextStyle(fontFamily = io.github.jgg113.stocknote.ui.theme.Pretendard, fontSize = 9.sp, color = c.text)

    // ── 가격 영역 범위
    var hi = Double.NEGATIVE_INFINITY
    var lo = Double.POSITIVE_INFINITY
    for (i in start until cEnd) { hi = max(hi, candles[i].high); lo = min(lo, candles[i].low) }
    overlays.forEach { o -> o.series.forEach { s -> for (i in start until cEnd) { val v = s.values[i]; if (!v.isNaN()) { hi = max(hi, v); lo = min(lo, v) } } } }
    if (future != null) for (k in future.indices) if (candles.size + k in start until end) { hi = max(hi, future[k]); lo = min(lo, future[k]) }
    levels.forEach { hi = max(hi, it.price); lo = min(lo, it.price) }
    if (hi <= lo) { hi += 1; lo -= 1 }
    val pad = (hi - lo) * 0.06
    hi += pad; lo -= pad
    val top = 6f
    val mainBottom = mainH - 4f
    fun y(v: Double) = (top + (hi - v) / (hi - lo) * (mainBottom - top)).toFloat()

    // 격자와 가격 눈금
    for (k in 0..4) {
        val v = lo + (hi - lo) * k / 4
        val yy = y(v)
        drawLine(c.grid, Offset(0f, yy), Offset(plotW, yy), 1f)
        drawText(tm, Fmt.price(v, currency), Offset(plotW + 6f, yy - 14f), label)
    }

    // 보조지표 띠 칠하기 (볼린저·구름)
    overlays.forEach { o ->
        val f = o.fill ?: return@forEach
        val a = o.series.getOrNull(f.first) ?: return@forEach
        val b = o.series.getOrNull(f.second) ?: return@forEach
        val col = io.github.jgg113.stocknote.ui.theme.PriceColors.palette[a.colorIndex % io.github.jgg113.stocknote.ui.theme.PriceColors.palette.size].copy(alpha = 0.08f)
        for (i in start until cEnd) {
            val va = a.values[i]; val vb = b.values[i]
            if (va.isNaN() || vb.isNaN()) continue
            val y1 = y(max(va, vb)); val y2 = y(min(va, vb))
            drawRect(col, Offset(x(i) - barW / 2, y1), Size(barW, y2 - y1))
        }
    }

    // 예상 구간·비교 이후 구간 배경
    if (futureCount > 0 && end > candles.size) {
        val fx = x(candles.size) - barW / 2
        drawRect(c.future, Offset(fx, 0f), Size(plotW - fx, mainH))
    }
    if (shadeFrom != null && shadeFrom < end) {
        val sx = (x(max(shadeFrom, start)) - barW / 2).coerceAtLeast(0f)
        drawRect(c.future, Offset(sx, 0f), Size((x(cEnd - 1) + barW / 2 - sx).coerceAtLeast(0f), mainH))
    }

    // 캔들
    val bodyW = (barW * 0.7f).coerceAtLeast(1f)
    for (i in start until cEnd) {
        val cd = candles[i]
        val col = if (cd.close >= cd.open) c.up else c.down
        val cx = x(i)
        drawLine(col, Offset(cx, y(cd.high)), Offset(cx, y(cd.low)), 1.2f)
        val yo = y(cd.open); val yc = y(cd.close)
        val t = min(yo, yc); val h = max(abs(yo - yc), 1.2f)
        drawRect(col, Offset(cx - bodyW / 2, t), Size(bodyW, h))
    }

    // 가격 위 보조지표 선
    overlays.forEach { o -> o.series.forEach { s -> drawSeries(s, start, cEnd, ::x, ::y, barW, false) } }

    // 목표가·손절가 선
    levels.forEach { lv ->
        val yy = y(lv.price)
        drawLine(lv.color, Offset(0f, yy), Offset(plotW, yy), 2.5f)
        drawText(tm, "${lv.label} ${Fmt.price(lv.price, currency)}", Offset(6f, (yy - 30f).coerceAtLeast(0f)), TextStyle(fontFamily = io.github.jgg113.stocknote.ui.theme.Pretendard, fontSize = 10.sp, color = lv.color))
    }

    // 예상 경로
    if (future != null && end > candles.size) {
        val path = Path()
        path.moveTo(x(candles.size - 1), y(candles.last().close))
        for (k in future.indices) { val i = candles.size + k; if (i >= end) break; path.lineTo(x(i), y(future[k])) }
        drawPath(path, c.path, style = Stroke(3.5f))
    }

    // 매매 시점
    for (i in start until cEnd) {
        markers[i]?.forEach { m ->
            val cx = x(i)
            val buy = m.signal == Signal.BUY
            val yy = if (buy) y(candles[i].low) + 10f else y(candles[i].high) - 10f
            val s = (barW * 0.9f).coerceIn(9f, 22f)
            val path = Path().apply {
                if (buy) { moveTo(cx, yy); lineTo(cx - s / 2, yy + s); lineTo(cx + s / 2, yy + s) }
                else { moveTo(cx, yy); lineTo(cx - s / 2, yy - s); lineTo(cx + s / 2, yy - s) }
                close()
            }
            drawPath(path, if (buy) c.up else c.down)
        }
    }

    // 보이는 구간의 최고·최저
    if (showExtremes && cEnd - start > 5) {
        var hiI = start; var loI = start
        for (i in start until cEnd) { if (candles[i].high > candles[hiI].high) hiI = i; if (candles[i].low < candles[loI].low) loI = i }
        val lastClose = candles.last().close
        fun mark(i: Int, value: Double, isHigh: Boolean) {
            val pct = (lastClose / value - 1) * 100
            val text = "${if (isHigh) "최고" else "최저"} ${Fmt.price(value, currency)} (${Fmt.signedRate(pct)}) ${Fmt.date(candles[i].time)}"
            val layout = tm.measure(text, TextStyle(fontFamily = io.github.jgg113.stocknote.ui.theme.Pretendard, fontSize = 9.sp, color = if (isHigh) c.up else c.down))
            val px = x(i)
            val leftSide = px > plotW / 2
            val tx = if (leftSide) px - layout.size.width - 8f else px + 8f
            val ty = if (isHigh) y(value) - layout.size.height - 2f else y(value) + 2f
            drawText(layout, topLeft = Offset(tx.coerceIn(0f, (plotW - layout.size.width).coerceAtLeast(0f)), ty.coerceIn(0f, mainH - layout.size.height)))
        }
        mark(hiI, candles[hiI].high, true)
        mark(loI, candles[loI].low, false)
    }

    // 마지막 가격 표시
    val last = candles[cEnd - 1]
    val ly = y(last.close).coerceIn(top, mainBottom)
    drawLine((if (last.close >= last.open) c.up else c.down).copy(alpha = 0.5f), Offset(0f, ly), Offset(plotW, ly), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    drawText(tm, Fmt.price(last.close, currency), Offset(plotW + 6f, ly - 14f), label.copy(color = if (last.close >= last.open) c.up else c.down))

    // ── 아래 칸 보조지표
    var panelTop = mainH
    panels.forEach { (cfg, out) ->
        val ptop = panelTop + 14f
        val pbot = panelTop + panelH - 4f
        drawLine(c.grid, Offset(0f, panelTop), Offset(size.width, panelTop), 1f)
        var pHi = Double.NEGATIVE_INFINITY
        var pLo = Double.POSITIVE_INFINITY
        val fixed = out.fixedRange
        if (fixed != null) { pLo = fixed.first; pHi = fixed.second } else {
            out.series.forEach { s -> for (i in start until cEnd) { val v = s.values[i]; if (!v.isNaN()) { pHi = max(pHi, v); pLo = min(pLo, v) } } }
            if (out.series.any { it.style == SeriesStyle.BARS || it.style == SeriesStyle.HISTOGRAM }) pLo = min(pLo, 0.0)
        }
        if (pHi == Double.NEGATIVE_INFINITY) { pHi = 1.0; pLo = 0.0 }
        if (pHi <= pLo) { pHi += 1; pLo -= 1 }
        fun py(v: Double) = (ptop + (pHi - v) / (pHi - pLo) * (pbot - ptop)).toFloat()
        out.guides.forEach { g ->
            if (g in pLo..pHi) drawLine(c.grid, Offset(0f, py(g)), Offset(plotW, py(g)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
        }
        out.series.forEach { s ->
            when (s.style) {
                SeriesStyle.BARS -> for (i in start until cEnd) {
                    val v = s.values[i]; if (v.isNaN()) continue
                    val col = (if (candles[i].close >= candles[i].open) c.up else c.down).copy(alpha = 0.55f)
                    drawRect(col, Offset(x(i) - bodyW / 2, py(v)), Size(bodyW, py(max(pLo, 0.0)) - py(v)))
                }
                SeriesStyle.HISTOGRAM -> for (i in start until cEnd) {
                    val v = s.values[i]; if (v.isNaN()) continue
                    val z = py(0.0); val yy = py(v)
                    drawRect((if (v >= 0) c.up else c.down).copy(alpha = 0.5f), Offset(x(i) - bodyW / 2, min(z, yy)), Size(bodyW, abs(z - yy).coerceAtLeast(1f)))
                }
                else -> drawSeries(s, start, cEnd, ::x, ::py, barW, false)
            }
        }
        val valueText = out.series.joinToString("  ") { s ->
            val idx = if (cross in start until cEnd) cross else cEnd - 1
            val v = s.values.getOrNull(idx) ?: Double.NaN
            "${s.label} ${if (abs(v) >= 1e5) Fmt.volume(v) else Fmt.decimal(v)}"
        }
        drawText(tm, cfg.title + "  " + valueText, Offset(4f, panelTop + 1f), label)
        drawText(tm, if (abs(pHi) >= 1e5) Fmt.volume(pHi) else Fmt.decimal(pHi), Offset(plotW + 6f, ptop - 4f), label)
        panelTop += panelH
    }

    // 날짜 눈금
    val step = (n / 4).coerceAtLeast(1)
    var i = start + step / 2
    while (i < cEnd) {
        val t = candles[i].time
        val text = if (intraday) Fmt.dateTime(t).substring(11) else Fmt.date(t).substring(2)
        drawText(tm, text, Offset((x(i) - 30f).coerceIn(0f, plotW - 60f), panelTop + 2f), label)
        i += step
    }

    // 십자선과 값 상자
    if (cross in start until cEnd) {
        val cd = candles[cross]
        val cx = x(cross)
        drawLine(c.cross.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, panelTop), 1f)
        val chg = if (cross > 0) (cd.close / candles[cross - 1].close - 1) * 100 else 0.0
        val lines = buildString {
            append(if (intraday) Fmt.dateTime(cd.time) else Fmt.date(cd.time))
            append("\n시 ").append(Fmt.price(cd.open, currency)).append("  고 ").append(Fmt.price(cd.high, currency))
            append("\n저 ").append(Fmt.price(cd.low, currency)).append("  종 ").append(Fmt.price(cd.close, currency))
            append("\n").append(Fmt.signedRate(chg)).append("  거래량 ").append(Fmt.volume(cd.volume))
            overlays.flatMap { it.series }.take(6).forEach { s -> val v = s.values[cross]; if (!v.isNaN()) append("\n").append(s.label).append(' ').append(Fmt.price(v, currency)) }
            markers[cross]?.forEach { m -> append("\n").append(m.signal.label).append(" ").append(Fmt.price(m.price, currency)) }
        }
        val layout = tm.measure(lines, TextStyle(fontFamily = io.github.jgg113.stocknote.ui.theme.Pretendard, fontSize = 10.sp, color = c.cross))
        val boxX = if (cx > plotW / 2) 6f else plotW - layout.size.width - 14f
        drawRect(c.bg.copy(alpha = 0.92f), Offset(boxX, 8f), Size(layout.size.width + 10f, layout.size.height + 8f))
        drawRect(c.grid, Offset(boxX, 8f), Size(layout.size.width + 10f, layout.size.height + 8f), style = Stroke(1f))
        drawText(layout, topLeft = Offset(boxX + 5f, 12f))
    }
}

private fun DrawScope.drawSeries(s: ChartSeries, start: Int, end: Int, x: (Int) -> Float, y: (Double) -> Float, barW: Float, dashed: Boolean) {
    val col = PriceColors.palette[s.colorIndex % PriceColors.palette.size]
    if (s.style == SeriesStyle.DOTS) {
        for (i in start until end) { val v = s.values[i]; if (!v.isNaN()) drawCircle(col, (barW * 0.18f).coerceIn(1.5f, 3.5f), Offset(x(i), y(v))) }
        return
    }
    val path = Path()
    var started = false
    for (i in start until end) {
        val v = s.values[i]
        if (v.isNaN()) { started = false; continue }
        if (!started) { path.moveTo(x(i), y(v)); started = true } else path.lineTo(x(i), y(v))
    }
    drawPath(path, col, style = Stroke(1.6f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null))
}

/** 누적 수익률 같은 선 그래프 (여러 줄) */
@Composable
fun LineChart(
    series: List<Pair<DoubleArray, Color>>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    baseline: Double? = null,
    format: (Double) -> String = { Fmt.decimal(it) },
) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        val all = series.flatMap { it.first.filter { v -> !v.isNaN() } }
        if (all.isEmpty()) return@Canvas
        var hi = all.max(); var lo = all.min()
        baseline?.let { hi = max(hi, it); lo = min(lo, it) }
        if (hi <= lo) { hi += 1; lo -= 1 }
        val axis = 110f
        val w = size.width - axis
        fun y(v: Double) = ((hi - v) / (hi - lo) * (size.height - 8f) + 4f).toFloat()
        for (k in 0..3) {
            val v = lo + (hi - lo) * k / 3
            drawLine(grid, Offset(0f, y(v)), Offset(w, y(v)), 1f)
            drawText(measurer, format(v), Offset(w + 6f, y(v) - 14f), TextStyle(fontFamily = io.github.jgg113.stocknote.ui.theme.Pretendard, fontSize = 9.sp, color = text))
        }
        baseline?.let { drawLine(text.copy(alpha = 0.6f), Offset(0f, y(it)), Offset(w, y(it)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))) }
        series.forEach { (values, color) ->
            if (values.size < 2) return@forEach
            val path = Path()
            var started = false
            values.forEachIndexed { i, v ->
                if (v.isNaN()) return@forEachIndexed
                val px = i * w / (values.size - 1)
                if (!started) { path.moveTo(px, y(v)); started = true } else path.lineTo(px, y(v))
            }
            drawPath(path, color, style = Stroke(2.2f))
        }
    }
}

/** 작은 추세선 (목록용) */
@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val hi = values.max(); val lo = values.min()
        val r = (hi - lo).takeIf { it > 0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val px = i * size.width / (values.size - 1)
            val py = ((hi - v) / r * size.height).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(2f))
    }
}

/** 막대 그래프 (재무) */
@Composable
fun BarChart(values: List<Pair<String, Double?>>, color: Color, negative: Color, modifier: Modifier = Modifier, height: Dp = 110.dp) {
    val measurer = rememberTextMeasurer()
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.fillMaxWidth().height(height)) {
        val nums = values.mapNotNull { it.second }
        if (nums.isEmpty()) return@Canvas
        val hi = max(nums.max(), 0.0); val lo = min(nums.min(), 0.0)
        val r = (hi - lo).takeIf { it > 0 } ?: 1.0
        val labelH = 26f
        fun y(v: Double) = ((hi - v) / r * (size.height - labelH * 2) + labelH).toFloat()
        val slot = size.width / values.size
        values.forEachIndexed { i, (name, v) ->
            val cx = slot * i + slot / 2
            drawText(measurer, name, Offset(cx - 30f, size.height - labelH + 4f), TextStyle(fontFamily = io.github.jgg113.stocknote.ui.theme.Pretendard, fontSize = 9.sp, color = text))
            if (v == null) return@forEachIndexed
            val top = min(y(v), y(0.0)); val bottom = max(y(v), y(0.0))
            drawRect(if (v >= 0) color else negative, Offset(cx - slot * 0.25f, top), Size(slot * 0.5f, (bottom - top).coerceAtLeast(1f)))
            drawText(measurer, Fmt.big(v), Offset(cx - 34f, (top - 24f).coerceAtLeast(0f)), TextStyle(fontFamily = io.github.jgg113.stocknote.ui.theme.Pretendard, fontSize = 9.sp, color = text))
        }
    }
}
