package io.github.jgg113.stocknote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jgg113.stocknote.core.Asset
import io.github.jgg113.stocknote.core.Market
import io.github.jgg113.stocknote.core.UsStocks
import kotlinx.coroutines.delay

/** 종목 검색. [purpose] 가 있으면 고른 종목을 그 목적(관심목록·테마·보유)에 넣고 돌아간다. */
@Composable
fun SearchScreen(vm: AppViewModel, purpose: PickPurpose?) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.back() }) { Text("←") }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; vm.search(it) },
                singleLine = true,
                placeholder = { Text("종목명·코드·티커 (삼성전자, 005930, AAPL, 비트코인)") },
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        when (purpose) {
            is PickPurpose.Watch -> Notice("관심목록에 넣을 종목을 고르세요.")
            is PickPurpose.ThemeMember -> Notice("테마에 넣을 종목을 고르세요.")
            is PickPurpose.Holding -> Notice("보유 종목에 넣을 종목을 고르세요.")
            null -> {}
        }
        if (vm.searching) Loading("찾는 중…")
        LazyColumn(Modifier.fillMaxSize()) {
            if (text.isBlank()) {
                val recent = vm.store.recentSearches
                if (recent.isNotEmpty()) item {
                    SmallLabel("최근 검색", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    ChipRow(recent, { false }, { it }) { s -> text = s; vm.search(s) }
                }
                item {
                    SmallLabel("해외 인기 종목", Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    ChipRow(UsStocks.list.take(16), { false }, { it.name }) { a -> pick(vm, purpose, a) }
                }
                if (!vm.keys.hasPublic && !vm.keys.hasKis && !vm.keys.hasDart) item {
                    Notice("국내 종목 전체를 찾으려면 [설정]에서 공공데이터포털 인증키를 넣으세요. 지금은 테마에 있는 대표 종목만 찾습니다.")
                }
            }
            items(vm.searchResults, key = { it.key }) { a ->
                Row(Modifier.fillMaxWidth().clickable { vm.rememberSearch(text); pick(vm, purpose, a) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name.ifBlank { a.code }, fontWeight = FontWeight.SemiBold)
                        SmallLabel(listOf(a.market.label, a.shortCode, if (a.market == Market.US) UsStocks.exchangeLabel(a.exchange) else a.exchange).filter { it.isNotBlank() && it != "UPBIT" }.joinToString(" · "))
                    }
                    if (purpose == null) TextButton(onClick = { vm.toggleWatch(a) }) { Text(if (vm.isWatched(a)) "★" else "☆") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (text.isNotBlank() && !vm.searching && vm.searchResults.isEmpty()) item { SmallLabel("찾는 종목이 없습니다.", Modifier.padding(16.dp)) }
        }
    }
}

private fun pick(vm: AppViewModel, purpose: PickPurpose?, a: Asset) {
    when (purpose) {
        null -> { vm.back(); vm.openAsset(a) }
        is PickPurpose.Watch -> { vm.toggleWatch(a, purpose.listId); vm.back() }
        is PickPurpose.ThemeMember -> { vm.addThemeMember(purpose.themeId, a); vm.back() }
        is PickPurpose.Holding -> { vm.back(); vm.openAsset(a); vm.message("상세 화면 아래 [보유 추가]로 수량과 평균단가를 넣으세요.") }
    }
}

@Composable
fun WatchScreen(vm: AppViewModel) {
    var selected by rememberSaveable { mutableStateOf(0) }
    val lists = vm.watchlists
    val list = lists.getOrNull(selected) ?: lists.first()
    var editing by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }

    // 화면이 보이는 동안 주기적으로 시세를 다시 받는다 (실시간 출처일 때 의미가 있다).
    LaunchedEffect(list.id, list.assets.size) {
        while (true) {
            vm.refreshWatch(list.id)
            // 실시간 출처가 있고 장이 열려 있을 때만 자주 받는다.
            val live = list.assets.any { it.market == Market.CRYPTO } || (vm.keys.hasKis && list.assets.any { io.github.jgg113.stocknote.core.MarketClock.open(it.market) })
            delay(if (live) 5_000 else 60_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("관심종목", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.open(Screen.Pick(PickPurpose.Watch(list.id))) }) { Text("+ 종목") }
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "완료" else "편집") }
        }
        ScrollableTabRow(selectedTabIndex = lists.indexOf(list).coerceAtLeast(0), edgePadding = 12.dp) {
            lists.forEachIndexed { i, w -> Tab(selected = w.id == list.id, onClick = { selected = i }, text = { Text("${w.name} (${w.assets.size})") }) }
            Tab(selected = false, onClick = { name = ""; dialog = "new" }, text = { Text("+ 목록") })
        }
        if (editing) Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { name = list.name; dialog = "rename" }) { Text("이름 바꾸기") }
            TextButton(onClick = { vm.deleteWatchlist(list.id); selected = 0 }) { Text("목록 지우기") }
        }
        if (list.assets.isEmpty()) {
            Notice("아직 종목이 없습니다. [+ 종목]으로 넣거나, 종목 화면의 ☆ 를 누르세요. 목록·종목 수에 제한이 없습니다.")
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(list.assets, key = { it.key }) { a ->
                val q = vm.watchQuotes[a.key]
                QuoteRow(a, q, note = a.market.label, trailing = if (editing) ({
                    Row {
                        IconButton(onClick = { vm.moveInWatch(list.id, a, -1) }) { Text("▲") }
                        IconButton(onClick = { vm.moveInWatch(list.id, a, 1) }) { Text("▼") }
                        IconButton(onClick = { vm.removeFromWatch(list.id, a) }) { Text("✕") }
                    }
                }) else null) { vm.openAsset(a) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (vm.priceAlerts.isNotEmpty() || vm.signalAlerts.isNotEmpty()) item {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("알림", fontWeight = FontWeight.Bold)
                    vm.priceAlerts.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallLabel("${p.asset.name}: " + listOfNotNull(p.above?.let { "${io.github.jgg113.stocknote.core.Fmt.price(it, p.asset.market.currency)} 이상" }, p.below?.let { "${io.github.jgg113.stocknote.core.Fmt.price(it, p.asset.market.currency)} 이하" }, p.movePct?.let { "±$it%" }).joinToString(", "), Modifier.weight(1f))
                            TextButton(onClick = { vm.setPriceAlert(null, p.asset) }) { Text("끄기") }
                        }
                    }
                    vm.signalAlerts.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallLabel("${s.asset.name}: ${io.github.jgg113.stocknote.core.Strategies.find(s.strategyId)?.name ?: s.strategyId} 신호", Modifier.weight(1f))
                            TextButton(onClick = { vm.removeSignalAlert(s) }) { Text("끄기") }
                        }
                    }
                    if (!vm.alertSettings.enabled) SmallLabel("알림이 꺼져 있습니다. [설정]에서 켜세요.")
                }
            }
        }
    }

    dialog?.let { mode ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (mode == "new") "새 목록" else "이름 바꾸기") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("이름") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (mode == "new") { vm.addWatchlist(name); selected = vm.watchlists.size - 1 } else vm.renameWatchlist(list.id, name)
                    dialog = null
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("취소") } },
        )
    }
}
