package io.github.jgg113.stocknote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jgg113.stocknote.core.AccountType
import io.github.jgg113.stocknote.core.Asset
import io.github.jgg113.stocknote.core.BacktestResult
import io.github.jgg113.stocknote.core.Currency
import io.github.jgg113.stocknote.core.Fmt
import io.github.jgg113.stocknote.core.IndicatorConfig
import io.github.jgg113.stocknote.core.IndicatorKind
import io.github.jgg113.stocknote.core.IndicatorType
import io.github.jgg113.stocknote.core.Market
import io.github.jgg113.stocknote.core.Opinion
import io.github.jgg113.stocknote.core.PaperBroker
import io.github.jgg113.stocknote.core.StockPrompt
import io.github.jgg113.stocknote.core.StrategyGroup
import io.github.jgg113.stocknote.core.ThemeStats
import io.github.jgg113.stocknote.core.Timeframe
import io.github.jgg113.stocknote.core.UsStocks
import io.github.jgg113.stocknote.data.PriceAlert
import io.github.jgg113.stocknote.ui.theme.PriceColors
import kotlinx.coroutines.delay

private enum class DetailTab(val label: String) { CHART("차트"), FORECAST("AI 예측"), STRATEGY("지표분석"), FINANCE("재무"), ISSUES("이슈"), INFO("정보·AI") }

@Composable
fun DetailScreen(vm: AppViewModel) {
    val d = vm.detail ?: return
    val asset = d.asset
    val cur = asset.market.currency
    var tab by rememberSaveable(asset.key) { mutableStateOf(DetailTab.CHART) }
    var orderDialog by remember { mutableStateOf<Boolean?>(null) }
    var alertDialog by remember { mutableStateOf(false) }
    var indicatorDialog by remember { mutableStateOf(false) }

    // 실시간 출처가 있으면 몇 초마다 시세를 다시 받는다.
    LaunchedEffect(asset.key) {
        val live = asset.market == Market.CRYPTO || (asset.market == Market.KR && vm.keys.hasKis) || (asset.market == Market.US && vm.keys.hasKis)
        if (!live) return@LaunchedEffect
        while (true) {
            delay(if (asset.market == Market.CRYPTO) 3_000 else 5_000)
            vm.refreshQuote()
        }
    }
    LaunchedEffect(tab) {
        when (tab) {
            DetailTab.FINANCE -> vm.loadFinancials()
            DetailTab.ISSUES -> vm.loadIssues()
            else -> {}
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 머리
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.back() }) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(asset.name.ifBlank { d.quote?.asset?.name ?: asset.code }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                SmallLabel(listOf(asset.market.label, asset.shortCode, (d.quote?.asset?.exchange ?: asset.exchange).let { if (asset.market == Market.US) UsStocks.exchangeLabel(it) else it }.takeIf { it != "UPBIT" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
            }
            TextButton(onClick = { vm.toggleWatch(asset) }) { Text(if (vm.isWatched(asset)) "★" else "☆", style = MaterialTheme.typography.titleLarge) }
            TextButton(onClick = { alertDialog = true }) { Text("알림") }
        }
        d.quote?.let { q ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                Text(Fmt.priceWithUnit(q.price, cur), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = PriceColors.color(q.change))
                Spacer(Modifier.width(8.dp))
                Text("${Fmt.signedPrice(q.change, cur)} ${Fmt.signedRate(q.changeRate)}", color = PriceColors.color(q.change), modifier = Modifier.padding(bottom = 4.dp))
            }
            SmallLabel(
                (if (q.realtime) "실시간 · ${q.source}" else "기준일 ${Fmt.date(q.time)} 종가 · ${q.source}") + (d.forecast?.let { " · AI 예측 ${it.score}점 ${it.opinion.label}" } ?: ""),
                Modifier.padding(horizontal = 16.dp),
            )
        }
        // 재무 탭은 국내 종목에만 있다. 선택 위치는 보이는 탭 기준으로 센다.
        val tabs = DetailTab.entries.filter { it != DetailTab.FINANCE || asset.market == Market.KR }
        ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), edgePadding = 8.dp) {
            tabs.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (d.loading) Loading()
            d.error?.let { ErrorNotice(it) { vm.loadDetail(asset) } }
            when (tab) {
                DetailTab.CHART -> ChartTab(vm, onEditIndicators = { indicatorDialog = true })
                DetailTab.FORECAST -> ForecastTab(vm)
                DetailTab.STRATEGY -> StrategyTab(vm) { tab = DetailTab.CHART }
                DetailTab.FINANCE -> FinanceTab(vm)
                DetailTab.ISSUES -> IssuesTab(vm)
                DetailTab.INFO -> InfoTab(vm)
            }
            Spacer(Modifier.height(80.dp))
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { orderDialog = true }, modifier = Modifier.weight(1f)) { Text("모의 매수") }
            OutlinedButton(onClick = { orderDialog = false }, modifier = Modifier.weight(1f)) { Text("모의 매도 · 보유") }
        }
    }

    orderDialog?.let { buy -> OrderDialog(vm, asset, buy) { orderDialog = null } }
    if (alertDialog) AlertDialogFor(vm, asset) { alertDialog = false }
    if (indicatorDialog) IndicatorDialog(vm) { indicatorDialog = false }
}

@Composable
private fun ChartTab(vm: AppViewModel, onEditIndicators: () -> Unit) {
    val d = vm.detail ?: return
    val tfs = when (d.asset.market) {
        Market.US -> listOf(Timeframe.DAY, Timeframe.WEEK, Timeframe.MONTH)
        else -> Timeframe.entries.toList()
    }
    var showMarkers by rememberSaveable { mutableStateOf(true) }
    ChipRow(tfs, { it == d.timeframe }, { it.label }, Modifier.padding(top = 8.dp)) { vm.setTimeframe(it) }
    d.chartError?.let { Notice(it) }
    if (d.candles.isNotEmpty()) {
        val markers = if (showMarkers && d.timeframe == Timeframe.DAY && d.candles.size == d.daily.size) d.selected?.markers.orEmpty() else emptyList()
        CandleChart(d.candles, vm.indicators, d.asset.market.currency, Modifier.padding(top = 6.dp), markers = markers, intraday = d.timeframe.intraday)
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEditIndicators) { Text("보조지표 ${vm.indicators.size}개 편집") }
            d.selected?.let { r ->
                FilterChip(selected = showMarkers, onClick = { showMarkers = !showMarkers }, label = { Text("매매시점: ${r.strategy.name}", maxLines = 1) })
            }
        }
        SmallLabel("두 손가락으로 확대·축소, 옆으로 밀어 과거 보기, 눌러서 그날 값 보기. ▲ 매수 ▼ 매도 시점은 [지표분석]에서 고른 전략입니다.", Modifier.padding(horizontal = 16.dp))
    } else if (!d.loading) {
        Notice("차트 자료가 없습니다.")
    }
}

@Composable
private fun ForecastTab(vm: AppViewModel) {
    val d = vm.detail ?: return
    val cur = d.asset.market.currency
    val context = LocalContext.current
    if (d.analyzing) Loading("과거 비슷한 장면을 찾는 중…")
    val f = d.forecast
    if (f == null && !d.analyzing && !d.loading) {
        Notice("예측하려면 일봉이 160개(약 8개월) 이상 있어야 합니다.")
    }
    if (f != null) {
        SectionCard("AI 예측 종합") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${f.score}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = opinionColor(f.opinion))
                Text(" / 100", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Pill(f.opinion.label, opinionColor(f.opinion))
            }
            ScoreBar(f.score.toDouble(), color = opinionColor(f.opinion))
            Spacer(Modifier.height(8.dp))
            f.items.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(76.dp))
                    Text((if (item.score > 0) "+" else "") + item.score, color = PriceColors.color(item.score.toDouble()), modifier = Modifier.width(36.dp))
                    Text(item.reason, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }
        SectionCard("예상 주가 범위") {
            f.horizons.forEach { h ->
                Text("${h.bars}거래일 뒤", fontWeight = FontWeight.Bold)
                InfoRow("가운데 값", "${Fmt.priceWithUnit(h.price(f.lastPrice, h.medianPct), cur)} (${Fmt.signedRate(h.medianPct)})", PriceColors.color(h.medianPct))
                InfoRow("절반이 든 범위", "${Fmt.price(h.price(f.lastPrice, h.p25Pct), cur)} ~ ${Fmt.price(h.price(f.lastPrice, h.p75Pct), cur)}")
                InfoRow("넓은 범위 (80%)", "${Fmt.price(h.price(f.lastPrice, h.p10Pct), cur)} ~ ${Fmt.price(h.price(f.lastPrice, h.p90Pct), cur)}")
                InfoRow("오를 확률", "${Fmt.decimal(h.upProbPct)}% (평소 ${Fmt.decimal(h.baseUpProbPct)}%)")
                InfoRow("과거 적중률", if (h.hitRatePct.isNaN()) "검증 자료 부족" else "${Fmt.decimal(h.hitRatePct)}% (${h.tested}번)")
                Spacer(Modifier.height(8.dp))
            }
            SmallLabel("지금과 지표 상태(수익률·RSI·볼린저·MACD·거래량·변동성 등)가 가장 비슷했던 과거 ${f.neighbors}번을 이 종목 기록에서 찾아, 그 뒤 실제 움직임을 모은 값입니다.")
        }
        SectionCard("읽을 때 주의") { f.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
        AiBox(vm, StockPrompt.Mode.SUMMARY, "AI 해설 받기", context)
    }
}

@Composable
fun opinionColor(o: Opinion) = when (o.tone) {
    2, 1 -> PriceColors.up
    -1, -2 -> PriceColors.down
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun StrategyTab(vm: AppViewModel, showChart: () -> Unit) {
    val d = vm.detail ?: return
    val r = d.report
    var sort by rememberSaveable { mutableStateOf(0) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }
    if (d.analyzing) Loading("전략 ${io.github.jgg113.stocknote.core.Strategies.all.size}개를 과거 자료로 시험하는 중…")
    if (r == null) return
    SectionCard("지금 신호 비율") {
        val ratio = r.buyRatioPct
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("매수 쪽 ${Fmt.decimal(ratio)}%", fontWeight = FontWeight.Bold, color = if (ratio >= 50) PriceColors.up else PriceColors.down, modifier = Modifier.weight(1f))
            SmallLabel("최근 5일 매수 ${r.recentBuy} · 매도 ${r.recentSell}")
        }
        ScoreBar(ratio, color = if (ratio >= 50) PriceColors.up else PriceColors.down)
        SmallLabel("전략 ${r.total}개 중 지금 보유 상태이거나 최근 5일 안에 매수 신호를 낸 전략의 비율입니다. 각 전략은 신호 다음 날 시가에 사고팔며, 수수료·세금(왕복 ${Fmt.decimal(vm.store.fees.roundTripPct(d.asset.market))}%)을 뺐습니다.")
        TextButton(onClick = { settings = true }) {
            val bt = vm.store.backtest
            Text("손절·익절: " + (bt.stopLossPct?.let { "-$it%" } ?: "없음") + " / " + (bt.takeProfitPct?.let { "+$it%" } ?: "없음") + "  (바꾸기)")
        }
    }
    ChipRow(listOf("종합 점수", "누적 수익률", "승률", "지금 보유"), { it == listOf("종합 점수", "누적 수익률", "승률", "지금 보유")[sort] }, { it }) { sort = listOf("종합 점수", "누적 수익률", "승률", "지금 보유").indexOf(it) }
    ChipRow(listOf<String?>(null) + StrategyGroup.entries.map { it.label }, { it == group }, { it ?: "전체" }, Modifier.padding(top = 4.dp)) { group = it }
    val list = when (sort) {
        1 -> r.byReturn
        2 -> r.results.sortedWith(compareByDescending<BacktestResult> { it.closedTrades >= 3 }.thenByDescending { it.winRatePct })
        3 -> r.results.filter { it.holding || it.recentBuy(5) }.sortedByDescending { it.score }
        else -> r.byScore
    }.filter { group == null || it.strategy.group.label == group }
    SectionCard {
        if (list.isEmpty()) SmallLabel("해당하는 전략이 없습니다.")
        list.forEachIndexed { i, res ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StrategyRow(res, selected = d.selected?.strategy?.id == res.strategy.id) { vm.selectStrategy(res) }
            if (d.selected?.strategy?.id == res.strategy.id) StrategyDetail(vm, res, showChart)
        }
    }
    if (settings) StopDialog(vm) { settings = false }
}

@Composable
private fun StrategyRow(r: BacktestResult, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.strategy.name, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                val last = r.lastSignal
                when {
                    r.signalOnLastBar && last != null -> Pill("오늘 ${last.label}", if (last == io.github.jgg113.stocknote.core.Signal.BUY) PriceColors.up else PriceColors.down)
                    r.holding -> Pill("보유 중", PriceColors.up)
                }
            }
            SmallLabel("${r.strategy.group.label} · 거래 ${r.closedTrades}회 · 승률 ${Fmt.decimal(r.winRatePct)}% · 최대낙폭 -${Fmt.decimal(r.maxDrawdownPct)}%")
        }
        Column(horizontalAlignment = Alignment.End) {
            ChangeText(r.totalReturnPct, bold = true)
            SmallLabel("보유만 ${Fmt.signedRate(r.buyHoldReturnPct)}")
        }
    }
}

@Composable
private fun StrategyDetail(vm: AppViewModel, r: BacktestResult, showChart: () -> Unit) {
    val d = vm.detail ?: return
    val cur = d.asset.market.currency
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(r.strategy.description, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        LineChart(listOf(r.equity.map { (it - 1) * 100 }.toDoubleArray() to MaterialTheme.colorScheme.primary), height = 110.dp, baseline = 0.0, format = { Fmt.decimal(it) + "%" })
        InfoRow("평균 수익", "${Fmt.signedRate(r.avgReturnPct)} / 거래")
        InfoRow("손익비", Fmt.decimal(r.profitFactor))
        InfoRow("평균 보유", "${Fmt.decimal(r.avgHoldBars)}봉")
        InfoRow("마지막 신호", r.lastSignal?.let { "${it.label} · ${Fmt.date(d.daily.getOrNull(r.lastSignalIndex)?.time ?: 0)}" } ?: "-")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = showChart) { Text("차트에서 보기") }
            val on = vm.hasSignalAlert(d.asset, r.strategy.id)
            OutlinedButton(onClick = { vm.toggleSignalAlert(d.asset, r.strategy.id) }) { Text(if (on) "신호 알림 끄기" else "다음 신호 알림") }
        }
        if (r.trades.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            SmallLabel("최근 거래")
            r.trades.takeLast(8).reversed().forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${Fmt.shortDate(t.entryTime)} → ${if (t.open) "보유 중" else Fmt.shortDate(t.exitTime)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text("${Fmt.price(t.entryPrice, cur)} → ${Fmt.price(t.exitPrice, cur)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.4f))
                    Text(Fmt.signedRate(t.returnPct), color = PriceColors.color(t.returnPct), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StopDialog(vm: AppViewModel, onDismiss: () -> Unit) {
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

@Composable
private fun FinanceTab(vm: AppViewModel) {
    val d = vm.detail ?: return
    d.financialsError?.let { Notice(it); return }
    val f = d.financials ?: run { Loading("DART 재무제표를 받는 중…"); return }
    val cap = d.quote?.marketCap ?: 0.0
    SectionCard("주요 재무 (${if (f.consolidated) "연결" else "별도"}, ${f.year}년 사업보고서)") {
        Text("매출액", fontWeight = FontWeight.SemiBold)
        BarChart(f.annual.map { it.label to it.revenue }, MaterialTheme.colorScheme.primary, PriceColors.down)
        Text("영업이익", fontWeight = FontWeight.SemiBold)
        BarChart(f.annual.map { it.label to it.operatingIncome }, PriceColors.up, PriceColors.down)
        Text("순이익", fontWeight = FontWeight.SemiBold)
        BarChart(f.annual.map { it.label to it.netIncome }, PriceColors.up, PriceColors.down)
    }
    SectionCard("지표") {
        Row(Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.weight(1.2f))
            f.annual.forEach { Text(it.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
        }
        listOf<Pair<String, (io.github.jgg113.stocknote.core.FinancialPeriod) -> String>>(
            "영업이익률" to { p -> p.operatingMargin.orDash { Fmt.decimal(it) + "%" } },
            "순이익률" to { p -> p.netMargin.orDash { Fmt.decimal(it) + "%" } },
            "ROE" to { p -> p.roe.orDash { Fmt.decimal(it) + "%" } },
            "부채비율" to { p -> p.debtRatio.orDash { Fmt.decimal(it) + "%" } },
            "자산총계" to { p -> p.assets.orDash { Fmt.big(it) } },
            "자본총계" to { p -> p.equity.orDash { Fmt.big(it) } },
        ).forEach { (name, fn) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                f.annual.forEach { Text(fn(it), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (cap > 0) {
            Spacer(Modifier.height(6.dp))
            InfoRow("시가총액", Fmt.big(cap))
            InfoRow("PER (추정)", f.per(cap).orDash { Fmt.decimal(it) + "배" })
            InfoRow("PBR (추정)", f.pbr(cap).orDash { Fmt.decimal(it) + "배" })
            SmallLabel("최근 사업보고서 순이익·자본으로 계산한 값이라 증권사 숫자와 다를 수 있습니다.")
        }
    }
    f.latestQuarter?.let { q ->
        SectionCard(q.label) {
            InfoRow("매출액", q.revenue.orDash { Fmt.big(it) })
            InfoRow("영업이익", q.operatingIncome.orDash { Fmt.big(it) })
            InfoRow("순이익", q.netIncome.orDash { Fmt.big(it) })
            InfoRow("부채비율", q.debtRatio.orDash { Fmt.decimal(it) + "%" })
        }
    }
}

@Composable
private fun IssuesTab(vm: AppViewModel) {
    val d = vm.detail ?: return
    val context = LocalContext.current
    if (d.issuesLoading) Loading("공시·뉴스를 받는 중…")
    AiBox(vm, StockPrompt.Mode.ISSUES, "AI 이슈 요약", context)
    if (d.asset.market == Market.KR) SectionCard("공시 (DART, 최근 3개월)") {
        if (!vm.keys.hasDart) SmallLabel("[설정]에서 DART 인증키(무료)를 넣으면 공시를 볼 수 있습니다.") else NewsList(d.disclosures, context, 30)
    }
    SectionCard("뉴스") { NewsList(d.news, context, 30) }
}

@Composable
private fun InfoTab(vm: AppViewModel) {
    val d = vm.detail ?: return
    val context = LocalContext.current
    val cur = d.asset.market.currency
    d.quote?.let { q ->
        SectionCard("시세 정보") {
            InfoRow("시가 / 고가 / 저가", "${Fmt.price(q.open, cur)} / ${Fmt.price(q.high, cur)} / ${Fmt.price(q.low, cur)}")
            InfoRow("전일 종가", Fmt.price(q.prevClose, cur))
            InfoRow("거래량", Fmt.volume(q.volume))
            InfoRow("거래대금", Fmt.big(q.value, cur))
            if (q.marketCap > 0) InfoRow("시가총액", Fmt.big(q.marketCap, cur))
            q.per?.let { InfoRow("PER / PBR", "${Fmt.decimal(it)} / ${q.pbr.orDash { v -> Fmt.decimal(v) }}") }
            val year = d.daily.takeLast(250)
            val hi52 = q.high52 ?: year.maxOfOrNull { it.high }
            val lo52 = q.low52 ?: year.minOfOrNull { it.low }
            if (hi52 != null && lo52 != null) InfoRow("52주 최고 / 최저", "${Fmt.price(hi52, cur)} / ${Fmt.price(lo52, cur)}")
            if (d.daily.size > 60) {
                fun ago(n: Int) = d.daily.getOrNull(d.daily.size - 1 - n)?.close?.let { (q.price / it - 1) * 100 }
                InfoRow("1주·1달·3달·1년", listOf(5, 21, 63, 250).joinToString(" · ") { n -> ago(n).orDash { Fmt.signedRate(it) } })
            }
        }
    }
    val themes = ThemeStats.themesOf(d.asset.key, vm.allThemes)
    if (themes.isNotEmpty()) SectionCard("속한 테마") {
        themes.forEach { t -> Text(t.name, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().clickable { vm.open(Screen.ThemeDetail(t)) }.padding(vertical = 6.dp)) }
    }
    SectionCard("AI 에게 묻기") {
        var question by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(question, { question = it }, label = { Text("예) 지금 분할 매수해도 될까? 위험 요소는?") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
        Spacer(Modifier.height(6.dp))
        Button(onClick = { if (question.isNotBlank()) vm.askAi(StockPrompt.Mode.QUESTION, question) { p, text -> handoffToAi(context, p, text) } }) {
            val p = vm.store.aiProvider
            Text(if (p == io.github.jgg113.stocknote.core.AiProvider.NONE) "질문하기" else "질문하기 (${p.label})")
        }
        SmallLabel("앱이 계산한 시세·예측·전략 신호·재무·뉴스 제목을 함께 보냅니다.")
        AiResult(vm)
    }
    SectionCard("바로가기") {
        val links = when (d.asset.market) {
            Market.KR -> listOf("네이버 증권" to "https://finance.naver.com/item/main.naver?code=${d.asset.code}", "DART 공시" to "https://dart.fss.or.kr/dsab007/main.do?textCrpNm=${java.net.URLEncoder.encode(d.asset.name, "UTF-8")}")
            Market.US -> listOf("Yahoo Finance" to "https://finance.yahoo.com/quote/${d.asset.code}", "SEC 공시" to "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=${d.asset.code}")
            Market.CRYPTO -> listOf("업비트" to "https://upbit.com/exchange?code=CRIX.UPBIT.${d.asset.code}")
        }
        links.forEach { (n, u) -> TextButton(onClick = { openUrl(context, u) }) { Text(n) } }
    }
}

@Composable
private fun AiBox(vm: AppViewModel, mode: StockPrompt.Mode, label: String, context: android.content.Context) {
    SectionCard("AI 해설") {
        val provider = vm.store.aiProvider
        Button(onClick = { vm.askAi(mode) { p, text -> handoffToAi(context, p, text) } }) { Text(if (provider == io.github.jgg113.stocknote.core.AiProvider.NONE) label else "$label (${provider.label})") }
        if (provider.isAppHandoff) SmallLabel("질문이 ${provider.label.removeSuffix("으로 넘기기")}으로 넘어가고 클립보드에도 복사됩니다.")
        AiResult(vm)
    }
}

@Composable
private fun AiResult(vm: AppViewModel) {
    val d = vm.detail ?: return
    if (d.aiLoading) Loading("AI 가 답을 쓰는 중…")
    d.aiError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    d.aiText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
private fun OrderDialog(vm: AppViewModel, asset: Asset, buy: Boolean, onDismiss: () -> Unit) {
    val d = vm.detail
    val price0 = d?.quote?.price ?: d?.daily?.lastOrNull()?.close ?: 0.0
    var accountId by remember { mutableStateOf(vm.accounts.firstOrNull { it.type == AccountType.PAPER }?.id ?: vm.accounts.first().id) }
    val account = vm.account(accountId) ?: return
    var isBuy by remember { mutableStateOf(buy) }
    var qty by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(if (price0 > 0) price0.let { if (asset.market.currency == Currency.USD) "%.2f".format(it) else it.toLong().toString() } else "") }
    val holding = account.holding(asset)
    val p = price.toDoubleOrNull() ?: 0.0
    val maxQty = if (account.type == AccountType.PAPER) PaperBroker.maxBuyQty(account, asset, p, vm.store.fees) else 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(asset.name) },
        text = {
            Column {
                ChipRow(vm.accounts, { it.id == accountId }, { it.name }) { accountId = it.id }
                Row { FilterChip(isBuy, { isBuy = true }, { Text(if (account.type == AccountType.MANUAL) "추가" else "매수") }); Spacer(Modifier.width(6.dp)); FilterChip(!isBuy, { isBuy = false }, { Text(if (account.type == AccountType.MANUAL) "줄이기" else "매도") }) }
                NumberField(if (account.type == AccountType.MANUAL && isBuy) "평균 단가" else "가격", price, { price = it })
                NumberField("수량", qty, { qty = it })
                if (account.type == AccountType.PAPER) {
                    SmallLabel("현금 ${Fmt.priceWithUnit(account.cash(asset.market.currency), asset.market.currency)}" + if (isBuy) " · 최대 ${Fmt.quantity(maxQty, asset.market)}" else "")
                    if (isBuy) TextButton(onClick = { qty = if (asset.market == Market.CRYPTO) "%.8f".format(maxQty) else maxQty.toLong().toString() }) { Text("최대") }
                }
                holding?.let { h ->
                    SmallLabel("보유 ${Fmt.quantity(h.quantity, asset.market)} · 평균 ${Fmt.price(h.avgPrice, asset.market.currency)}")
                    if (!isBuy) TextButton(onClick = { qty = if (asset.market == Market.CRYPTO) h.quantity.toString() else h.quantity.toLong().toString() }) { Text("전부") }
                }
                if (account.type == AccountType.PAPER) SmallLabel("모의투자는 가상 돈으로 지금 시세에 바로 체결됩니다. 실제 주문은 나가지 않습니다.")
            }
        },
        confirmButton = { TextButton(onClick = { if (vm.trade(accountId, asset, isBuy, qty.toDoubleOrNull() ?: 0.0, p)) onDismiss() }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun AlertDialogFor(vm: AppViewModel, asset: Asset, onDismiss: () -> Unit) {
    val old = vm.priceAlerts.firstOrNull { it.asset.key == asset.key }
    var above by remember { mutableStateOf(old?.above?.toString().orEmpty()) }
    var below by remember { mutableStateOf(old?.below?.toString().orEmpty()) }
    var move by remember { mutableStateOf(old?.movePct?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("가격 알림") },
        text = {
            Column {
                NumberField("이 가격 이상이면", above, { above = it })
                NumberField("이 가격 이하이면", below, { below = it })
                NumberField("하루 등락률이 넘으면 (%)", move, { move = it }, suffix = "%")
                SmallLabel("[설정]의 확인 간격(최소 15분)마다 봅니다. 같은 알림은 6시간에 한 번만 보냅니다.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = PriceAlert(asset, above.toDoubleOrNull(), below.toDoubleOrNull(), move.toDoubleOrNull())
                vm.setPriceAlert(if (a.above == null && a.below == null && a.movePct == null) null else a, asset)
                onDismiss()
            }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (old != null) TextButton(onClick = { vm.setPriceAlert(null, asset); onDismiss() }) { Text("끄기") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}

@Composable
private fun IndicatorDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf(vm.indicators) }
    var editing by remember { mutableStateOf<IndicatorType?>(null) }
    AlertDialog(
        onDismissRequest = { vm.saveIndicators(list); onDismiss() },
        title = { Text("보조지표 (제한 없음)") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                IndicatorKind.entries.forEach { kind ->
                    item { SmallLabel(if (kind == IndicatorKind.OVERLAY) "가격 위에 그리기" else "아래 칸에 그리기", Modifier.padding(top = 8.dp)) }
                    IndicatorType.entries.filter { it.kind == kind }.forEach { t ->
                        item {
                            val cfg = list.firstOrNull { it.type == t }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Checkbox(cfg != null, { on -> list = if (on) list + IndicatorConfig(t) else list.filterNot { it.type == t } })
                                Column(Modifier.weight(1f).clickable { if (cfg != null) editing = t }) {
                                    Text(cfg?.title ?: t.label)
                                    SmallLabel(t.description)
                                }
                                if (cfg != null && t.paramNames.isNotEmpty()) TextButton(onClick = { editing = t }) { Text("설정") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveIndicators(list); onDismiss() }) { Text("완료") } },
        dismissButton = { TextButton(onClick = { list = IndicatorConfig.defaults }) { Text("기본값") } },
    )
    editing?.let { t ->
        val cfg = list.firstOrNull { it.type == t } ?: IndicatorConfig(t)
        val values = remember(t) {
            androidx.compose.runtime.mutableStateListOf<String>().apply {
                t.paramNames.indices.forEach { i -> val v = cfg.params.getOrElse(i) { t.defaults.getOrElse(i) { 0.0 } }; add(if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) }
            }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(t.label) },
            text = {
                Column {
                    t.paramNames.forEachIndexed { i, n -> NumberField(n, values[i], { v -> values[i] = v }) }
                    SmallLabel(t.description)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val params = t.paramNames.indices.map { i -> values.getOrNull(i)?.toDoubleOrNull() ?: t.defaults.getOrElse(i) { 0.0 } }
                    list = list.map { if (it.type == t) it.copy(params = params) else it }
                    editing = null
                }) { Text("적용") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("취소") } },
        )
    }
}

