package io.github.jgg113.stocknote.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jgg113.stocknote.core.AccountType
import io.github.jgg113.stocknote.core.Currency
import io.github.jgg113.stocknote.core.Fmt
import io.github.jgg113.stocknote.core.IndicatorConfig
import io.github.jgg113.stocknote.core.IndicatorType
import io.github.jgg113.stocknote.core.PortfolioValuation
import io.github.jgg113.stocknote.core.Signal
import io.github.jgg113.stocknote.core.TradeMarker
import io.github.jgg113.stocknote.ui.theme.PriceColors
import kotlinx.coroutines.delay

private enum class InvestTab(val label: String) { ACCOUNTS("투자현황"), GAME("차트게임") }

@Composable
fun InvestScreen(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(InvestTab.ACCOUNTS) }
    Column(Modifier.fillMaxSize()) {
        Text("투자", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
        TabRow(selectedTabIndex = tab.ordinal) { InvestTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) } }
        when (tab) {
            InvestTab.ACCOUNTS -> AccountsTab(vm)
            InvestTab.GAME -> GameTab(vm)
        }
    }
}

@Composable
private fun AccountsTab(vm: AppViewModel) {
    var selectedId by rememberSaveable { mutableStateOf(vm.accounts.first().id) }
    val account = vm.account(selectedId) ?: vm.accounts.first()
    var dialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(account.id, account.holdings.size) {
        while (true) { vm.refreshAccounts(); delay(15_000) }
    }
    val fx = vm.usdKrw ?: 0.0
    val v = PortfolioValuation.of(account, vm.accountPrices, fx)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ChipRow(vm.accounts, { it.id == account.id }, { if (it.name == it.type.label) it.name else "${it.name} (${it.type.label})" }, Modifier.padding(top = 8.dp)) { selectedId = it.id }
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { dialog = "newPaper" }) { Text("+ 모의계좌") }
                TextButton(onClick = { dialog = "newManual" }) { Text("+ 보유목록") }
            }
            SectionCard(account.name) {
                Text(Fmt.priceWithUnit(v.totalKrw, Currency.KRW), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Row {
                    Text("평가손익 ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Fmt.big(v.pnlKrw)}원 (${Fmt.signedRate(v.pnlPct)})", color = PriceColors.color(v.pnlKrw), fontWeight = FontWeight.SemiBold)
                }
                if (account.type == AccountType.PAPER) {
                    InfoRow("원화 현금", Fmt.priceWithUnit(account.cashKrw, Currency.KRW))
                    InfoRow("달러 현금", Fmt.priceWithUnit(account.cashUsd, Currency.USD))
                    val totalDeposit = account.depositedKrw + account.depositedUsd * fx
                    if (totalDeposit > 0) InfoRow("총 수익률", Fmt.signedRate((v.totalKrw / totalDeposit - 1) * 100) + " (넣은 시드 기준)", PriceColors.color(v.totalKrw - totalDeposit))
                    InfoRow("실현손익", "${Fmt.big(account.realizedKrw)}원 · $${Fmt.decimal(account.realizedUsd)}")
                }
                if (fx > 0) SmallLabel("달러 자산은 1달러 ${Fmt.decimal(fx)}원으로 환산했습니다.")
                if (account.type == AccountType.MANUAL) SmallLabel("실제로 가진 종목을 적어 두는 목록입니다. 종목 화면의 [모의 매도 · 보유] → 이 목록을 골라 수량과 평균단가를 넣으세요.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (account.type == AccountType.PAPER) OutlinedButton(onClick = { dialog = "deposit" }) { Text("시드 넣기") }
                    OutlinedButton(onClick = { vm.open(Screen.Search) }) { Text("종목 찾기") }
                    TextButton(onClick = { dialog = "reset" }) { Text("초기화") }
                }
            }
            val weights = v.weights()
            if (weights.isNotEmpty()) SectionCard("비중") {
                Row(Modifier.fillMaxWidth().height(14.dp)) {
                    weights.forEachIndexed { i, (_, w) -> if (w > 0) Box(Modifier.weight(w.toFloat().coerceAtLeast(0.5f)).height(14.dp).background(PriceColors.palette[i % PriceColors.palette.size])) }
                }
                weights.take(8).forEachIndexed { i, (h, w) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Box(Modifier.width(10.dp).height(10.dp).background(PriceColors.palette[i % PriceColors.palette.size]))
                        Text(" ${h.asset.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text("${Fmt.decimal(w)}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Text("보유 종목 ${v.rows.size}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        items(v.rows, key = { it.holding.asset.key }) { r ->
            val cur = r.currency
            Column(Modifier.padding(horizontal = 16.dp)) {
                QuoteRow(r.holding.asset, null, note = "${Fmt.quantity(r.holding.quantity, r.holding.asset.market)}${if (r.holding.asset.market == io.github.jgg113.stocknote.core.Market.CRYPTO) "개" else "주"} · 평균 ${Fmt.price(r.holding.avgPrice, cur)}", trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Fmt.priceWithUnit(r.value, cur), fontWeight = FontWeight.SemiBold)
                        Text("${Fmt.big(r.pnl, cur)} (${Fmt.signedRate(r.pnlPct)})", color = PriceColors.color(r.pnl), style = MaterialTheme.typography.bodySmall)
                    }
                }) { vm.openAsset(r.holding.asset) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (account.history.isNotEmpty()) {
            item { Text("거래 기록", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            items(account.history.takeLast(50).reversed()) { t ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(Fmt.dateTime(t.time).substring(2), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
                    Text("${t.asset.name} ${t.side.label}", style = MaterialTheme.typography.bodySmall, color = if (t.side == Signal.BUY) PriceColors.up else PriceColors.down, modifier = Modifier.weight(1f))
                    Text("${Fmt.quantity(t.quantity, t.asset.market)} × ${Fmt.price(t.price, t.asset.market.currency)}", style = MaterialTheme.typography.bodySmall)
                    if (t.side == Signal.SELL) Text("  ${Fmt.big(t.realized, t.asset.market.currency)}", color = PriceColors.color(t.realized), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            if (vm.accounts.size > 1) TextButton(onClick = { dialog = "delete" }, modifier = Modifier.padding(8.dp)) { Text("이 계좌 지우기", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(40.dp))
        }
    }

    when (dialog) {
        "deposit" -> {
            var krw by remember { mutableStateOf("") }
            var usd by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("가상 시드 넣기") },
                text = { Column { NumberField("원화", krw, { krw = it }, suffix = "원"); NumberField("달러", usd, { usd = it }, suffix = "$"); SmallLabel("가상 돈이라 제한 없이 넣을 수 있습니다.") } },
                confirmButton = {
                    TextButton(onClick = {
                        krw.toDoubleOrNull()?.let { vm.deposit(account.id, Currency.KRW, it) }
                        usd.toDoubleOrNull()?.let { vm.deposit(account.id, Currency.USD, it) }
                        dialog = null
                    }) { Text("넣기") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("취소") } },
            )
        }
        "reset", "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (dialog == "reset") "처음 상태로 되돌릴까요?" else "이 계좌를 지울까요?") },
            text = { Text("보유 종목과 거래 기록이 모두 지워집니다.") },
            confirmButton = {
                TextButton(onClick = {
                    if (dialog == "reset") vm.resetAccount(account.id) else { vm.deleteAccount(account.id); selectedId = vm.accounts.first().id }
                    dialog = null
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("취소") } },
        )
        "newPaper", "newManual" -> {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(if (dialog == "newPaper") "새 모의계좌 (1억 원 + 10만 달러)" else "새 보유목록") },
                text = { OutlinedTextField(name, { name = it }, label = { Text("이름") }, singleLine = true) },
                confirmButton = { TextButton(onClick = { vm.addAccount(if (dialog == "newPaper") AccountType.PAPER else AccountType.MANUAL, name); dialog = null }) { Text("만들기") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("취소") } },
            )
        }
    }
}

@Composable
private fun GameTab(vm: AppViewModel) {
    val g = vm.game
    val stats = vm.gameStats
    val gameIndicators = remember { listOf(IndicatorConfig(IndicatorType.MA, listOf(5.0, 20.0, 60.0, 0.0, 0.0)), IndicatorConfig(IndicatorType.VOLUME)) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MarketChips(g.market, vm::setGameMarket, Modifier.padding(top = 8.dp))
            SectionCard("내 기록") {
                InfoRow("차트게임", "${stats.games}판 · 시장보다 잘한 판 ${stats.beatMarket} (${if (stats.games > 0) stats.beatMarket * 100 / stats.games else 0}%)")
                if (stats.games > 0) InfoRow("평균 초과수익", Fmt.signedRate(stats.totalEdgePct / stats.games) + " · 최고 " + Fmt.signedRate(stats.bestEdgePct))
                InfoRow("오를까 내릴까", "${stats.quizzes}문제 · 맞힘 ${stats.quizCorrect} (${if (stats.quizzes > 0) stats.quizCorrect * 100 / stats.quizzes else 0}%)")
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.newGame() }, enabled = !g.loading) { Text("차트게임 시작") }
                OutlinedButton(onClick = { vm.newQuiz() }, enabled = !g.loading) { Text("오를까 내릴까") }
            }
            SmallLabel("실제 과거 차트에서 무작위 구간을 이름·날짜 없이 보여 줍니다. 차트게임은 40봉 동안 사고팔아 그냥 들고 있었을 때보다 잘하면 이깁니다.", Modifier.padding(16.dp))
        }
        if (g.loading) item { Loading("차트를 고르는 중…") }
        g.error?.let { item { ErrorNotice(it) } }
        g.game?.let { game ->
            item {
                val markers = game.log.map { TradeMarker(it.index, it.signal, it.price) }
                CandleChart(game.shown, gameIndicators, gameCurrency(game.assetKey), markers = markers, initialBars = 100, mainHeight = 240.dp, panelHeight = 60.dp)
                SectionCard(if (game.finished) "결과: ${game.assetName}" else "남은 봉 ${game.remaining}") {
                    InfoRow("내 수익률", Fmt.signedRate(game.returnPct), PriceColors.color(game.returnPct))
                    InfoRow("그냥 들고 있었다면", Fmt.signedRate(game.buyHoldPct), PriceColors.color(game.buyHoldPct))
                    InfoRow("매매", "${game.trades}번 · 이긴 매매 ${game.wins}번" + if (game.holding) " · 보유 중" else "")
                    if (game.finished) {
                        Text(if (game.edgePct > 0) "시장보다 ${Fmt.decimal(game.edgePct)}%p 잘했습니다!" else "이번엔 들고만 있는 쪽이 ${Fmt.decimal(-game.edgePct)}%p 나았습니다.", fontWeight = FontWeight.Bold, color = PriceColors.color(game.edgePct))
                        SmallLabel("구간: ${Fmt.date(game.candles[game.visible - 1].time)} ~ ${Fmt.date(game.candles.last().time)}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.newGame() }) { Text("한 판 더") }
                            OutlinedButton(onClick = { vm.closeGame() }) { Text("닫기") }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { vm.updateGame(game.buy().next()) }, enabled = !game.holding) { Text("매수") }
                            Button(onClick = { vm.updateGame(game.sell().next()) }, enabled = game.holding) { Text("매도") }
                            OutlinedButton(onClick = { vm.updateGame(game.next()) }) { Text("다음") }
                            OutlinedButton(onClick = { vm.updateGame(game.next(5)) }) { Text("5봉") }
                        }
                        TextButton(onClick = { vm.updateGame(game.finish()) }) { Text("끝까지 넘기기") }
                    }
                }
            }
        }
        g.quiz?.let { q ->
            item {
                val shown = if (g.quizAnswered == null) q.shown else q.candles
                CandleChart(shown, gameIndicators, gameCurrency(q.assetKey), initialBars = shown.size, mainHeight = 240.dp, panelHeight = 60.dp)
                SectionCard("${q.ahead}봉 뒤 종가는 지금보다?") {
                    if (g.quizAnswered == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.answerQuiz(true) }) { Text("오른다 ▲") }
                            OutlinedButton(onClick = { vm.answerQuiz(false) }) { Text("내린다 ▼") }
                        }
                    } else {
                        Text(if (g.quizAnswered == true) "정답!" else "아쉽네요", fontWeight = FontWeight.Bold, color = if (g.quizAnswered == true) PriceColors.up else PriceColors.down)
                        Text("${q.assetName}: ${Fmt.signedRate(q.movePct)} (${Fmt.date(q.candles[q.visible - 1].time)} 이후)")
                        Button(onClick = { vm.newQuiz() }) { Text("다음 문제") }
                    }
                }
            }
        }
    }
}

private fun gameCurrency(key: String): Currency = io.github.jgg113.stocknote.core.Asset.parseKey(key)?.first?.currency ?: Currency.KRW
