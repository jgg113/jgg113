package io.github.jgg113.stocknote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import io.github.jgg113.stocknote.core.Signal
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jgg113.stocknote.core.FeatureKind
import io.github.jgg113.stocknote.core.Fmt
import io.github.jgg113.stocknote.core.Market
import io.github.jgg113.stocknote.core.Quote
import io.github.jgg113.stocknote.core.ScreenerFilter
import io.github.jgg113.stocknote.core.ScreenerSort
import io.github.jgg113.stocknote.core.Strategies
import io.github.jgg113.stocknote.core.StrategyGroup
import io.github.jgg113.stocknote.core.StrategyHorizon
import io.github.jgg113.stocknote.core.Theme
import io.github.jgg113.stocknote.ui.theme.PriceColors

private enum class DiscoverTab(val label: String) { SIGNALS("매매신호"), AI("AI 순위"), FEATURED("특징종목"), THEMES("테마"), SCREENER("종목필터") }

@Composable
fun DiscoverScreen(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(DiscoverTab.SIGNALS) }
    Column(Modifier.fillMaxSize()) {
        Text("종목 발굴", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
        androidx.compose.material3.ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
            DiscoverTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
        }
        when (tab) {
            DiscoverTab.SIGNALS -> SignalsTab(vm)
            DiscoverTab.AI -> AiRankTab(vm)
            DiscoverTab.FEATURED -> FeaturedTab(vm)
            DiscoverTab.THEMES -> ThemesTab(vm)
            DiscoverTab.SCREENER -> ScreenerTab(vm)
        }
    }
}

@Composable
private fun SignalsTab(vm: AppViewModel) {
    val s = vm.signals
    var pickStrategies by remember { mutableStateOf(false) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var showPortfolio by rememberSaveable { mutableStateOf(false) }
    val r = s.result
    val hits = r?.hits.orEmpty()
        .filter { s.side == null || it.signal == s.side }
        .filter { s.horizon == null || it.strategy.horizon == s.horizon }
        .let { list ->
            when (s.sort) {
                1 -> list.sortedByDescending { it.backtest.winRatePct }
                2 -> list.sortedByDescending { it.quality }
                else -> list.sortedByDescending { it.backtest.totalReturnPct }
            }
        }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            // 국내·미국·코인·관심
            val tabs = listOf("국내" to Market.KR, "미국" to Market.US, "코인" to Market.CRYPTO, "관심" to null)
            ChipRow(tabs, { (_, m) -> if (m == null) s.watchOnly else !s.watchOnly && s.market == m }, { it.first }, Modifier.padding(top = 8.dp)) { (_, m) ->
                vm.updateSignals(if (m == null) s.copy(watchOnly = true, result = null) else s.copy(market = m, watchOnly = false, result = null))
            }
            ChipRow(listOf<StrategyHorizon?>(null) + StrategyHorizon.entries, { it == s.horizon }, { it?.let { h -> "${h.label} (${h.period})" } ?: "전체 기간" }, Modifier.padding(top = 4.dp)) {
                vm.updateSignals(s.copy(horizon = it, result = if (s.useCustom) s.result else null))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.runSignals() }, enabled = !s.running) { Text(if (s.running) "찾는 중…" else if (r == null) "신호 찾기" else "다시 찾기") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { showOptions = !showOptions }) { Text(if (showOptions) "조건 접기" else "조건") }
                Spacer(Modifier.weight(1f))
                val alertOn = vm.scanAlerts.any { it.market == s.market }
                Text("알림", style = MaterialTheme.typography.bodySmall)
                Switch(alertOn, { vm.toggleScanAlert() }, enabled = !s.watchOnly)
            }
            if (showOptions) SectionCard("조건") {
                if (!s.watchOnly) {
                    Text("대상: " + when (s.market) {
                        Market.KR -> "시가총액 상위 ${s.size}종목"
                        Market.US -> "해외 기본 목록 ${s.size}종목"
                        Market.CRYPTO -> "거래대금 상위 ${s.size}개 코인"
                    } + if (s.includeWatch) " + 관심종목" else "")
                    ChipRow(listOf(20, 50, 100, 200), { it == s.size }, { "$it" }, Modifier.padding(vertical = 4.dp)) { vm.updateSignals(s.copy(size = it)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(s.includeWatch, { vm.updateSignals(s.copy(includeWatch = it)) })
                        Text("관심종목도 함께 보기")
                    }
                } else Text("대상: 관심종목 전체 (${vm.watchlists.sumOf { it.assets.size }}종목)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(s.useCustom, { vm.updateSignals(s.copy(useCustom = it, result = null)) })
                    Text("고른 전략만 쓰기 (${s.strategyIds.size}개)", modifier = Modifier.weight(1f))
                    TextButton(onClick = { pickStrategies = true }) { Text("고르기") }
                }
                SmallLabel(if (s.useCustom) "고른 전략만 봅니다." else "위에서 고른 기간의 전략 ${io.github.jgg113.stocknote.core.Strategies.of(s.horizon).size}개를 모두 봅니다.")
                SmallLabel("최근 3봉 안에 신호가 난 종목만, 그 종목에서 그 전략이 과거 3번 넘게 거래한 경우만 보여 줍니다." + if (s.market == Market.KR && !vm.keys.hasKis && !s.watchOnly) " 국내는 전날 종가 기준입니다." else "")
            }
        }
        if (s.running) item { Loading("일봉 받는 중", s.progress) }
        s.error?.let { item { ErrorNotice(it) { vm.runSignals() } } }
        if (r == null && !s.running && s.error == null) item {
            Notice("[신호 찾기]를 누르면 대표 종목마다 전략 ${io.github.jgg113.stocknote.core.Strategies.of(s.horizon).size}개를 과거 자료로 시험하고, 최근 매수·매도 신호를 누적수익률 순으로 보여 줍니다.")
        }
        if (r != null) {
            item {
                TabRow(selectedTabIndex = listOf(null, Signal.BUY, Signal.SELL).indexOf(s.side)) {
                    listOf<Signal?>(null, Signal.BUY, Signal.SELL).forEach { side ->
                        val count = r.hits.count { (side == null || it.signal == side) && (s.horizon == null || it.strategy.horizon == s.horizon) }
                        Tab(selected = s.side == side, onClick = { vm.updateSignals(s.copy(side = side)) }, text = { Text("${side?.label ?: "전체"} $count") })
                    }
                }
                ChipRow(listOf("누적수익률순", "승률순", "신뢰도순"), { it == listOf("누적수익률순", "승률순", "신뢰도순")[s.sort] }, { it }, Modifier.padding(vertical = 6.dp)) {
                    vm.updateSignals(s.copy(sort = listOf("누적수익률순", "승률순", "신뢰도순").indexOf(it)))
                }
            }
            if (hits.isEmpty()) item { Notice("조건에 맞는 최근 신호가 없습니다. 기간이나 대상을 바꿔 보세요.") }
            itemsIndexed(hits.take(200), key = { _, h -> "${h.asset.key}|${h.strategy.id}" }) { i, h -> SignalCard(i + 1, h) { vm.openAsset(h.asset) } }
            item {
                TextButton(onClick = { showPortfolio = !showPortfolio }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (showPortfolio) "전략 성과 접기" else "전략별 성과 보기 (${r.scanned}종목에 똑같이 적용)") }
            }
            if (showPortfolio) item { PortfolioCard(vm, r) }
            item { SmallLabel("과거 성과는 미래 수익을 보장하지 않습니다. 신호는 종가 기준이며 다음 거래일 시가 매매를 가정합니다.", Modifier.padding(16.dp)) }
        }
    }
    if (pickStrategies) StrategyPicker(s.strategyIds, onDismiss = { pickStrategies = false }) { vm.updateSignals(s.copy(strategyIds = it, useCustom = it.isNotEmpty(), result = null)) }
}

@Composable
private fun SignalCard(rank: Int, h: io.github.jgg113.stocknote.core.SignalHit, onClick: () -> Unit) {
    val buy = h.signal == Signal.BUY
    val color = if (buy) PriceColors.up else PriceColors.down
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable(onClick = onClick),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.06f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text("$rank", fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.asset.name.ifBlank { h.asset.code }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    SmallLabel("${h.asset.shortCode} · ${if (h.barsAgo == 0) "오늘" else "${h.barsAgo}봉 전"} 신호 · 종가 ${Fmt.priceWithUnit(h.price, h.asset.market.currency)}")
                }
                Pill(h.signal.label, color)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallLabel("전략명 ")
                        Text(h.strategy.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Pill("승률 ${Fmt.decimal(h.backtest.winRatePct)}%", MaterialTheme.colorScheme.onSurfaceVariant)
                        Pill("주기 ${h.backtest.frequencyText}", MaterialTheme.colorScheme.onSurfaceVariant)
                        Pill(h.strategy.horizon.label, MaterialTheme.colorScheme.primary)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    SmallLabel("누적수익률")
                    Text(Fmt.signedRate(h.backtest.totalReturnPct), color = PriceColors.color(h.backtest.totalReturnPct), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun PortfolioCard(vm: AppViewModel, r: io.github.jgg113.stocknote.core.ScanResult) {
    val s = vm.signals
    SectionCard("전략 성과 (최근 1년, ${r.scanned}종목에 똑같이 적용)") {
        val sorted = r.portfolios.filter { s.horizon == null || it.strategy.horizon == s.horizon }.sortedByDescending { it.totalReturnPct }
        val sel = sorted.firstOrNull { it.strategy.id == s.selectedPortfolio } ?: sorted.firstOrNull()
        if (sel != null) {
            Text(sel.strategy.name, fontWeight = FontWeight.Bold)
            LineChart(
                listOf(sel.equity.map { (it - 1) * 100 }.toDoubleArray() to MaterialTheme.colorScheme.primary, sel.benchmark.map { (it - 1) * 100 }.toDoubleArray() to MaterialTheme.colorScheme.outline),
                height = 130.dp, baseline = 0.0, format = { Fmt.decimal(it) + "%" },
            )
            SmallLabel("파랑: 전략대로 사고판 누적 수익률 · 회색: 같은 종목을 나눠 들고만 있었을 때")
        }
        sorted.take(30).forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { vm.updateSignals(s.copy(selectedPortfolio = p.strategy.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.strategy.name, fontWeight = if (p.strategy.id == sel?.strategy?.id) FontWeight.Bold else FontWeight.Normal)
                    SmallLabel("${p.strategy.horizon.label} · 거래 ${p.trades}회 · 승률 ${Fmt.decimal(p.winRatePct)}% · 보유만 ${Fmt.signedRate(p.benchmarkReturnPct)}")
                }
                ChangeText(p.totalReturnPct, bold = true)
            }
        }
    }
}

@Composable
fun StrategyPicker(selected: Set<String>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    val chosen = remember { mutableStateListOf(*selected.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("전략 ${chosen.size}/${Strategies.all.size}") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                item {
                    Row {
                        TextButton(onClick = { chosen.clear(); chosen.addAll(Strategies.all.map { it.id }) }) { Text("모두") }
                        TextButton(onClick = { chosen.clear() }) { Text("비우기") }
                    }
                }
                StrategyGroup.entries.forEach { g ->
                    item { SmallLabel(g.label, Modifier.padding(top = 8.dp)) }
                    Strategies.all.filter { it.group == g }.forEach { st ->
                        item {
                            Row(Modifier.fillMaxWidth().clickable { if (st.id in chosen) chosen.remove(st.id) else chosen.add(st.id) }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(st.id in chosen, { if (it) chosen.add(st.id) else chosen.remove(st.id) })
                                Column(Modifier.weight(1f)) {
                                    Text(st.name)
                                    SmallLabel(st.description)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen.toSet()); onDismiss() }) { Text("완료") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun FeaturedTab(vm: AppViewModel) {
    val f = vm.featured
    LaunchedEffect(Unit) { if (f.rows.isEmpty() && !f.loading) vm.loadFeatured() }
    Column(Modifier.fillMaxSize()) {
        MarketChips(f.market, { vm.loadFeatured(market = it) }, Modifier.padding(top = 8.dp))
        ChipRow(FeatureKind.entries, { it == f.kind }, { it.label }, Modifier.padding(top = 4.dp)) { vm.loadFeatured(kind = it) }
        SmallLabel(f.kind.description + if (f.live) " · 실시간 순위" else "", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (f.kind == FeatureKind.VOLUME_SURGE || f.kind == FeatureKind.NEW_HIGH || f.kind == FeatureKind.NEW_LOW) {
            if (f.market == Market.KR) SmallLabel("국내 거래량 급증·신고가는 저장해 둔 일봉(매매신호·종목필터·상세 화면에서 받은 종목)으로 계산합니다.", Modifier.padding(horizontal = 16.dp))
        }
        if (f.loading) Loading()
        f.error?.let { ErrorNotice(it) { vm.loadFeatured() } }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(f.rows, key = { it.quote.asset.key }) { r ->
                QuoteRow(r.quote.asset, r.quote, note = r.note.takeUnless { f.kind == FeatureKind.GAINERS || f.kind == FeatureKind.LOSERS || f.kind == FeatureKind.LIMIT_UP }) { vm.openAsset(r.quote.asset) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (!f.loading && f.rows.isEmpty() && f.error == null) item { SmallLabel("해당 종목이 없습니다.", Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun ThemesTab(vm: AppViewModel) {
    val t = vm.themes
    var newTheme by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (t.ranking.isEmpty() && !t.loading) vm.loadThemes() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MarketChips(t.market, { vm.loadThemes(it) }, Modifier.padding(top = 8.dp))
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { newTheme = true }) { Text("+ 내 테마 만들기") }
                TextButton(onClick = { vm.loadThemes() }) { Text("새로고침") }
            }
        }
        if (t.loading) item { Loading() }
        t.error?.let { item { ErrorNotice(it) { vm.loadThemes() } } }
        items(t.ranking, key = { it.theme.id }) { p ->
            Column(Modifier.padding(horizontal = 16.dp)) {
                ThemeRow(p.theme.name + if (p.theme.custom) " ✎" else "", p.avgChangePct, "${p.upCount}/${p.quotes.size} 상승 · " + p.quotes.take(3).joinToString(", ") { "${it.asset.name} ${Fmt.signedRate(it.changeRate)}" }) { vm.open(Screen.ThemeDetail(p.theme)) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        val empty = vm.customThemes.filter { it.members.isEmpty() }
        if (empty.isNotEmpty()) item {
            SectionCard("종목이 없는 내 테마") { empty.forEach { th -> Text(th.name, Modifier.fillMaxWidth().clickable { vm.open(Screen.ThemeDetail(th)) }.padding(vertical = 6.dp)) } }
        }
        item { SmallLabel("기본 테마는 업종·사업 내용으로 직접 묶은 대표 종목입니다. 테마를 열어 [내 것으로 고치기]를 누르면 종목을 넣고 뺄 수 있습니다.", Modifier.padding(16.dp)) }
    }
    if (newTheme) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newTheme = false },
            title = { Text("내 테마") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("이름") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { val th = vm.createTheme(name); newTheme = false; vm.open(Screen.ThemeDetail(th)) }) { Text("만들기") } },
            dismissButton = { TextButton(onClick = { newTheme = false }) { Text("취소") } },
        )
    }
}

@Composable
fun ThemeDetailScreen(vm: AppViewModel, theme0: Theme) {
    val theme = vm.themeById(theme0.id) ?: theme0
    var quotes by remember(theme.id, theme.members.size) { mutableStateOf<List<Quote>?>(null) }
    LaunchedEffect(theme.id, theme.members.size) { vm.themeQuotes(theme) { quotes = it } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.back() }) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(theme.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (theme.description.isNotBlank()) SmallLabel(theme.description)
            }
            if (theme.custom) {
                TextButton(onClick = { vm.open(Screen.Pick(PickPurpose.ThemeMember(theme.id))) }) { Text("+ 종목") }
            } else {
                TextButton(onClick = { val copy = vm.editableCopy(theme); vm.back(); vm.open(Screen.ThemeDetail(copy)) }) { Text("내 것으로 고치기") }
            }
        }
        val qs = quotes
        if (qs == null) Loading()
        qs?.takeIf { it.isNotEmpty() }?.let { list ->
            Row(Modifier.padding(horizontal = 16.dp)) {
                Text("평균 ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChangeText(list.map { it.changeRate }.average(), bold = true)
                Spacer(Modifier.width(12.dp))
                Text("상승 ${list.count { it.changeRate > 0 }}/${list.size}", color = PriceColors.up)
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            val byKey = qs.orEmpty().associateBy { it.asset.key }
            val ordered = qs.orEmpty().map { it.asset.key } + theme.members.filter { it !in byKey }
            items(ordered, key = { it }) { k ->
                val q = byKey[k]
                val asset = q?.asset ?: io.github.jgg113.stocknote.core.Asset.parseKey(k)!!.let { (m, c) -> io.github.jgg113.stocknote.core.Asset(m, c, c) }
                QuoteRow(asset, q, trailing = if (theme.custom) ({ TextButton(onClick = { vm.removeThemeMember(theme.id, k) }) { Text("빼기") } }) else null) { vm.openAsset(asset) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (theme.custom) item {
                TextButton(onClick = { vm.deleteTheme(theme.id); vm.back() }) { Text("이 테마 지우기", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ScreenerTab(vm: AppViewModel) {
    val s = vm.screener
    val f = s.filter
    fun set(nf: ScreenerFilter) = vm.updateScreener(s.copy(filter = nf))
    fun num(v: Double?) = v?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()
    val unit = if (s.market == Market.US) "백만$" else "억원"
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MarketChips(s.market, { vm.updateScreener(s.copy(market = it, rows = null, filter = f.copy(exchanges = emptySet()))) }, Modifier.padding(top = 8.dp))
            SectionCard("시세 조건") {
                if (s.market == Market.KR) {
                    ChipRow(listOf("KOSPI", "KOSDAQ", "KONEX"), { it in f.exchanges }, { it }) { e -> set(f.copy(exchanges = if (e in f.exchanges) f.exchanges - e else f.exchanges + e)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(f.excludeSpacPref, { set(f.copy(excludeSpacPref = it)) }); Text("스팩·우선주 빼기")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("가격 이상", num(f.priceMin), { set(f.copy(priceMin = it.toDoubleOrNull())) }, Modifier.weight(1f))
                    NumberField("가격 이하", num(f.priceMax), { set(f.copy(priceMax = it.toDoubleOrNull())) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("등락률 이상", num(f.changeMin), { set(f.copy(changeMin = it.toDoubleOrNull())) }, Modifier.weight(1f), "%")
                    NumberField("등락률 이하", num(f.changeMax), { set(f.copy(changeMax = it.toDoubleOrNull())) }, Modifier.weight(1f), "%")
                }
                if (s.market != Market.CRYPTO) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("시총 이상", num(f.capMin), { set(f.copy(capMin = it.toDoubleOrNull())) }, Modifier.weight(1f), unit)
                    NumberField("시총 이하", num(f.capMax), { set(f.copy(capMax = it.toDoubleOrNull())) }, Modifier.weight(1f), unit)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("거래대금 이상", num(f.valueMin), { set(f.copy(valueMin = it.toDoubleOrNull())) }, Modifier.weight(1f), unit)
                    if (s.market != Market.CRYPTO) NumberField("PER 이하", num(f.perMax), { set(f.copy(perMax = it.toDoubleOrNull())) }, Modifier.weight(1f))
                }
                if (f.perMax != null && !(s.market == Market.US && vm.keys.hasKis)) SmallLabel("PER 은 한국투자증권 해외 시세에만 있어 다른 경우에는 걸러지지 않습니다.")
            }
            SectionCard("차트 조건 (일봉을 받아 계산)") {
                ChipRow(listOf(null, 20, 60, 120), { it == f.aboveMa }, { it?.let { n -> "${n}일선 위" } ?: "이평 무관" }) { set(f.copy(aboveMa = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    NumberField("RSI 이상", num(f.rsiMin), { set(f.copy(rsiMin = it.toDoubleOrNull())) }, Modifier.weight(1f))
                    NumberField("RSI 이하", num(f.rsiMax), { set(f.copy(rsiMax = it.toDoubleOrNull())) }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("거래량 배수 이상", num(f.volumeRatioMin), { set(f.copy(volumeRatioMin = it.toDoubleOrNull())) }, Modifier.weight(1f), "배")
                    NumberField("1년 고가 근처", num(f.nearHighPct), { set(f.copy(nearHighPct = it.toDoubleOrNull())) }, Modifier.weight(1f), "% 안")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.goldenCrossDays != null, { set(f.copy(goldenCrossDays = if (it) 3 else null)) }); Text("최근 3일 안 5/20 골든크로스")
                }
                var pick by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("전략 매수 신호: " + (f.strategyId?.let { Strategies.find(it)?.name } ?: "없음"), modifier = Modifier.weight(1f))
                    TextButton(onClick = { pick = true }) { Text("고르기") }
                    if (f.strategyId != null) TextButton(onClick = { set(f.copy(strategyId = null)) }) { Text("빼기") }
                }
                if (pick) StrategyPicker(setOfNotNull(f.strategyId), { pick = false }) { set(f.copy(strategyId = it.firstOrNull())) }
                if (f.needsHistory) SmallLabel("차트 조건은 시세 조건에 맞는 종목 중 거래대금 상위 150개만 일봉을 받아 계산합니다.")
            }
            ChipRow(ScreenerSort.entries, { it == f.sort }, { it.label + "순" }) { set(f.copy(sort = it)) }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.runScreener() }, enabled = !s.running) { Text(if (s.running) "찾는 중…" else "찾기 (조건 ${f.activeCount}개)") }
                OutlinedButton(onClick = { vm.updateScreener(s.copy(filter = ScreenerFilter(), rows = null)) }) { Text("초기화") }
            }
        }
        if (s.running) item { Loading("일봉 받는 중", s.progress) }
        s.error?.let { item { ErrorNotice(it) { vm.runScreener() } } }
        s.rows?.let { rows ->
            item { Text("${rows.size}종목", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp)) }
            items(rows.take(300), key = { it.quote.asset.key }) { r ->
                Column(Modifier.padding(horizontal = 12.dp)) {
                    QuoteRow(r.quote.asset, r.quote, note = (listOf(Fmt.big(r.quote.value, r.quote.asset.market.currency)) + r.notes).joinToString(" · ")) { vm.openAsset(r.quote.asset) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}
