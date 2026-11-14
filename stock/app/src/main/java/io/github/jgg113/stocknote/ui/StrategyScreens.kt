package io.github.jgg113.stocknote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jgg113.stocknote.core.Asset
import io.github.jgg113.stocknote.core.BacktestResult
import io.github.jgg113.stocknote.core.Candle
import io.github.jgg113.stocknote.core.Fmt
import io.github.jgg113.stocknote.core.IndicatorReport
import io.github.jgg113.stocknote.core.Signal
import io.github.jgg113.stocknote.core.Strategies
import io.github.jgg113.stocknote.core.StrategyHorizon
import io.github.jgg113.stocknote.ui.theme.PriceColors

/** "4일 전 매수" 같은 글 */
fun BacktestResult.lastSignalText(): String {
    val s = lastSignal ?: return "최근 신호 없음"
    return if (barsSinceSignal == 0) "오늘 ${s.label}" else "${barsSinceSignal}일 전 ${s.label}"
}

@Composable
private fun signalColor(s: Signal?) = when (s) { Signal.BUY -> PriceColors.up; Signal.SELL -> PriceColors.down; null -> MaterialTheme.colorScheme.onSurfaceVariant }

/** 종목 화면 → 지표분석 탭 */
@Composable
fun StrategyTab(vm: AppViewModel, showChart: () -> Unit) {
    val d = vm.detail ?: return
    var horizon by rememberSaveable { mutableStateOf<StrategyHorizon?>(null) }
    var summary by rememberSaveable { mutableStateOf(true) }
    var stopDialog by remember { mutableStateOf(false) }
    if (d.analyzing) Loading("전략 ${Strategies.all.size}개를 과거 자료로 시험하는 중…")
    val full = d.report ?: return
    val report = full.of(horizon)

    ChipRow(listOf<StrategyHorizon?>(null) + StrategyHorizon.entries, { it == horizon }, { it?.let { h -> "${h.label} (${h.period})" } ?: "전체" }, Modifier.padding(top = 8.dp)) { horizon = it }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ChipRow(listOf(true, false), { it == summary }, { if (it) "분석요약" else "전체결과" }, Modifier.weight(1f)) { summary = it }
        TextButton(onClick = { stopDialog = true }) { Text("손절·익절") }
    }
    if (report.results.isEmpty()) { Notice("이 기간에 해당하는 전략 결과가 없습니다."); return }
    if (summary) SummaryPanel(vm, report, d.asset, showChart) else ResultList(vm, report, d.asset)
    SmallLabel(
        "각 전략은 종가에 신호를 보고 다음 거래일 시가에 사고팔며, 수수료·세금(왕복 ${Fmt.decimal(vm.store.fees.roundTripPct(d.asset.market))}%)을 뺐습니다. " +
            "자료 기간 ${Fmt.date(d.daily.firstOrNull()?.time ?: 0)} ~ ${Fmt.date(d.daily.lastOrNull()?.time ?: 0)}. 과거 성과가 미래 수익을 보장하지 않습니다.",
        Modifier.padding(16.dp),
    )
    if (stopDialog) StopDialog(vm) { stopDialog = false }
}

@Composable
private fun SummaryPanel(vm: AppViewModel, report: IndicatorReport, asset: Asset, showChart: () -> Unit) {
    val buy = report.lastBuySharePct
    SectionCard("결과요약") {
        SmallLabel("${report.total}개의 전략으로 분석해본 결과")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${asset.name}은(는) 최근 ", style = MaterialTheme.typography.titleMedium)
            Text(if (buy >= 50) "매수" else "매도", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (buy >= 50) PriceColors.up else PriceColors.down)
        }
        Text("신호 비율이 높았어요", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        ShareBar("매수신호", buy, PriceColors.up)
        ShareBar("매도신호", 100 - buy, PriceColors.down)
        SmallLabel("전략마다 가장 최근에 낸 신호가 매수인지 매도인지 센 비율입니다. 지금 보유 중인 전략 ${report.holdingCount}개, 최근 5일 매수 신호 ${report.recentBuy}개 · 매도 신호 ${report.recentSell}개.")
    }
    val ranked = report.byReturn
    var rank by rememberSaveable(report.total) { mutableIntStateOf(0) }
    val r = ranked.getOrNull(rank) ?: return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("수익률 ${rank + 1}위", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { rank = (rank - 1).coerceAtLeast(0) }, enabled = rank > 0) { Text("◀") }
            TextButton(onClick = { rank = (rank + 1).coerceAtMost(ranked.size - 1) }, enabled = rank < ranked.size - 1) { Text("▶") }
        }
        Text(r.strategy.name, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
        SmallLabel("${r.strategy.horizon.label} · ${r.strategy.group.label} 매매전략은")
        Spacer(Modifier.height(8.dp))
        Text("${asset.name}을(를)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (r.holding) "보유" else "관망", if (r.holding) PriceColors.up else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(" 중이에요", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Text(r.lastSignalText() + if (r.lastSignal != null) " 신호 발생" else "", color = signalColor(r.lastSignal), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(14.dp)) {
            StatRow("수익률", Fmt.signedRate(r.totalReturnPct), PriceColors.color(r.totalReturnPct))
            StatRow("승률", "${Fmt.decimal(r.winRatePct)}%")
            StatRow("신호주기", r.frequencyText)
            StatRow("거래횟수", "${r.closedTrades}회 (보유만 하면 ${Fmt.signedRate(r.buyHoldReturnPct)})")
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.selectStrategy(r); showChart() }, modifier = Modifier.weight(1f)) { Text("매매시점 표기") }
            val on = vm.hasSignalAlert(asset, r.strategy.id)
            Button(onClick = { vm.toggleSignalAlert(asset, r.strategy.id) }, modifier = Modifier.weight(1f)) { Text(if (on) "신호 알림 끄기" else "🔔 다음 신호받기") }
        }
        TextButton(onClick = { vm.open(Screen.StrategyDetail(asset, r.strategy.id)) }) { Text("누적수익률·매매내역 보기 ›") }
    }
}

@Composable
private fun ShareBar(label: String, pct: Double, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))) {
            Box(Modifier.fillMaxWidth((pct / 100).toFloat().coerceIn(0.02f, 1f)).height(40.dp).background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp)))
            Text(label, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
        Text("${Fmt.decimal(pct)}%", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun StatRow(label: String, value: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun ResultList(vm: AppViewModel, report: IndicatorReport, asset: Asset) {
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val sorts = listOf("수익률 순", "승률 순", "최근 신호 순")
    ChipRow(sorts.indices.toList(), { it == sort }, { sorts[it] }) { sort = it }
    val list = when (sort) {
        1 -> report.results.sortedWith(compareByDescending<BacktestResult> { it.closedTrades >= 3 }.thenByDescending { it.winRatePct })
        2 -> report.results.filter { it.lastSignal != null }.sortedBy { it.barsSinceSignal }
        else -> report.byReturn
    }
    SectionCard("전체결과 (${list.size})") {
        list.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().clickable { vm.open(Screen.StrategyDetail(asset, r.strategy.id)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.strategy.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Pill(if (r.holding) "보유" else "관망", if (r.holding) PriceColors.up else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row {
                        Text("수익률 ", style = MaterialTheme.typography.bodySmall)
                        Text(Fmt.signedRate(r.totalReturnPct), color = PriceColors.color(r.totalReturnPct), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text("   승률 ${Fmt.decimal(r.winRatePct)}%   ${r.strategy.horizon.label}", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.lastSignalText(), color = signalColor(r.lastSignal), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text("  거래횟수 ${r.closedTrades}회 · ${r.frequencyText}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                val on = vm.hasSignalAlert(asset, r.strategy.id)
                TextButton(onClick = { vm.toggleSignalAlert(asset, r.strategy.id) }) { Text(if (on) "🔔" else "🔕", fontSize = 18.sp) }
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 전략 하나의 누적수익률·매매내역 */
@Composable
fun StrategyDetailScreen(vm: AppViewModel, asset: Asset, strategyId: String) {
    val d = vm.detail
    val strategy = Strategies.find(strategyId) ?: return
    val candles = d?.daily.orEmpty()
    val r = remember(candles, strategyId, d?.report) { d?.report?.results?.firstOrNull { it.strategy.id == strategyId } ?: vm.singleBacktest(asset, candles, strategyId) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.back() }) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(strategy.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                SmallLabel("${asset.name} · ${strategy.horizon.label}(${strategy.horizon.period}) · ${strategy.group.label}")
            }
        }
        if (r == null || candles.isEmpty()) { Loading(); return }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(strategy.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
            CandleChart(candles, vm.indicators.filter { it.type.kind == io.github.jgg113.stocknote.core.IndicatorKind.OVERLAY }, asset.market.currency, Modifier.padding(top = 8.dp), markers = r.markers, mainHeight = 240.dp, initialBars = 150)
            SectionCard("누적수익률", action = { SmallLabel("${shortDate(candles.first().time)} – ${shortDate(candles.last().time)} · 일봉") }) {
                EquityChart(r.equity, candles)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) { SmallLabel("전략"); Text(Fmt.signedRate(r.totalReturnPct), color = PriceColors.color(r.totalReturnPct), fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f)) { SmallLabel("보유만"); Text(Fmt.signedRate(r.buyHoldReturnPct), color = PriceColors.color(r.buyHoldReturnPct), fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f)) { SmallLabel("승률"); Text("${Fmt.decimal(r.winRatePct)}%", fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f)) { SmallLabel("최대낙폭"); Text("-${Fmt.decimal(r.maxDrawdownPct)}%", fontWeight = FontWeight.Bold) }
                }
                SmallLabel("거래 ${r.closedTrades}회 · ${r.frequencyText} · 평균 보유 ${Fmt.decimal(r.avgHoldBars)}일 · 손익비 ${Fmt.decimal(r.profitFactor)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { vm.selectStrategy(r); vm.back() }) { Text("차트에 매매시점 표기") }
                    val on = vm.hasSignalAlert(asset, strategyId)
                    Button(onClick = { vm.toggleSignalAlert(asset, strategyId) }) { Text(if (on) "신호 알림 끄기" else "🔔 다음 신호받기") }
                }
            }
            SectionCard("매매내역") { TradeTable(r, candles, asset) }
            Spacer(Modifier.height(40.dp))
        }
    }
}

private fun shortDate(t: Long) = Fmt.date(t).substring(2).replace('-', '.')

/** 누적수익률 그래프. 눌러서 그날 값을 본다. */
@Composable
private fun EquityChart(equity: DoubleArray, candles: List<Candle>) {
    val measurer = rememberTextMeasurer()
    var sel by remember(equity) { mutableIntStateOf(-1) }
    val up = PriceColors.up
    val down = PriceColors.down
    val grid = MaterialTheme.colorScheme.outlineVariant
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val pct = remember(equity) { DoubleArray(equity.size) { (equity[it] - 1) * 100 } }
    Canvas(
        Modifier.fillMaxWidth().height(200.dp).pointerInput(equity) {
            detectTapGestures { pos -> sel = ((pos.x - 90f) / (size.width - 90f) * (pct.size - 1)).toInt().coerceIn(0, pct.size - 1) }
        },
    ) {
        if (pct.size < 2) return@Canvas
        val left = 90f
        val w = size.width - left
        var hi = pct.max(); var lo = pct.min()
        hi = maxOf(hi, 0.0); lo = minOf(lo, 0.0)
        if (hi == lo) hi += 1
        fun y(v: Double) = ((hi - v) / (hi - lo) * (size.height - 40f) + 8f).toFloat()
        fun x(i: Int) = left + i * w / (pct.size - 1)
        for (k in 0..3) {
            val v = lo + (hi - lo) * k / 3
            drawLine(grid, Offset(left, y(v)), Offset(size.width, y(v)), 1f)
            drawText(measurer, "${Fmt.decimal(v).substringBefore('.')}%", Offset(0f, y(v) - 14f), TextStyle(fontSize = 9.sp, color = text))
        }
        drawLine(text.copy(alpha = 0.5f), Offset(left, y(0.0)), Offset(size.width, y(0.0)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        val path = Path()
        pct.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, if (pct.last() >= 0) up else down, style = Stroke(3f))
        // 연도 눈금
        var lastYear = -1
        candles.forEachIndexed { i, c ->
            val yr = c.date.year
            if (yr != lastYear && c.date.monthValue == 1 && i > 0) {
                drawText(measurer, "$yr.01", Offset(x(i) - 24f, size.height - 26f), TextStyle(fontSize = 9.sp, color = text))
            }
            lastYear = yr
        }
        if (sel in pct.indices) {
            val px = x(sel); val py = y(pct[sel])
            drawLine(text.copy(alpha = 0.4f), Offset(px, 0f), Offset(px, size.height - 30f), 1f)
            drawCircle(if (pct[sel] >= 0) up else down, 8f, Offset(px, py))
            val label = "${Fmt.date(candles[sel].time)}\n${Fmt.signedRate(pct[sel])}"
            val layout = measurer.measure(label, TextStyle(fontSize = 11.sp, color = if (pct[sel] >= 0) up else down, fontWeight = FontWeight.Bold))
            val bx = (px - layout.size.width / 2).coerceIn(left, size.width - layout.size.width - 12f)
            val by = (py - layout.size.height - 24f).coerceAtLeast(0f)
            drawRect(surface, Offset(bx - 6f, by - 4f), Size(layout.size.width + 12f, layout.size.height + 8f))
            drawRect(if (pct[sel] >= 0) up else down, Offset(bx - 6f, by - 4f), Size(layout.size.width + 12f, layout.size.height + 8f), style = Stroke(2f))
            drawText(layout, topLeft = Offset(bx, by))
        }
    }
    SmallLabel("그래프를 누르면 그날의 누적수익률이 보입니다.")
}

@Composable
private fun TradeTable(r: BacktestResult, candles: List<Candle>, asset: Asset) {
    data class Row5(val time: Long, val signal: Signal?, val state: String, val price: Double, val equityPct: Double)
    val rows = remember(r) {
        val out = ArrayList<Row5>()
        r.trades.forEach { t ->
            out += Row5(t.entryTime, Signal.BUY, "진입", t.entryPrice, (r.equity[t.entryIndex] - 1) * 100)
            if (!t.open) out += Row5(t.exitTime, Signal.SELL, if (t.exitReason == "매도 신호") "관망" else t.exitReason, t.exitPrice, (r.equity[t.exitIndex] - 1) * 100)
        }
        if (r.holding) out += Row5(candles.last().time, null, "보유", candles.last().close, (r.equity.last() - 1) * 100)
        out.sortedByDescending { it.time }
    }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 8.dp, horizontal = 6.dp)) {
        listOf("날짜" to 1.3f, "신호" to 0.8f, "상태" to 0.8f, "일봉 종가" to 1.3f, "누적수익률" to 1.3f).forEach { (t, w) -> SmallLabel(t, Modifier.weight(w)) }
    }
    if (rows.isEmpty()) SmallLabel("자료 기간에 거래가 없었습니다.", Modifier.padding(8.dp))
    rows.take(80).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(shortDate(row.time), modifier = Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
            Text(row.signal?.label ?: "-", color = signalColor(row.signal), modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(row.state, modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
            Text(Fmt.price(row.price, asset.market.currency), modifier = Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
            Text(Fmt.signedRate(row.equityPct), color = PriceColors.color(row.equityPct), modifier = Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun StopDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val bt = vm.store.backtest
    var sl by remember { mutableStateOf(bt.stopLossPct?.toString().orEmpty()) }
    var tp by remember { mutableStateOf(bt.takeProfitPct?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("손절·익절 (모든 전략)") },
        text = {
            Column {
                NumberField("손절 (%)", sl, { sl = it }, suffix = "%")
                NumberField("익절 (%)", tp, { tp = it }, suffix = "%")
                SmallLabel("비워 두면 쓰지 않습니다. 장중에 그 가격에 닿으면 판 것으로 계산합니다.")
            }
        },
        confirmButton = { TextButton(onClick = { vm.rerunBacktest(sl.toDoubleOrNull()?.takeIf { it > 0 }, tp.toDoubleOrNull()?.takeIf { it > 0 }); onDismiss() }) { Text("다시 계산") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
