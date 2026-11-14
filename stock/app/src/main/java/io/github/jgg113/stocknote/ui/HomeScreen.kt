package io.github.jgg113.stocknote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jgg113.stocknote.core.Breadth
import io.github.jgg113.stocknote.core.FeatureKind
import io.github.jgg113.stocknote.core.Fmt
import io.github.jgg113.stocknote.core.Market
import io.github.jgg113.stocknote.ui.theme.PriceColors

@Composable
fun HomeScreen(vm: AppViewModel) {
    val h = vm.home
    val context = LocalContext.current
    LaunchedEffect(h.market) { if (h.featured.isEmpty() && !h.loading) vm.refreshHome() }
    var featureTab by remember { mutableStateOf(FeatureKind.GAINERS) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("시장 요약", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.open(Screen.Search) }) { Text("종목 검색") }
                TextButton(onClick = { vm.refreshHome() }) { Text("새로고침") }
            }
            MarketChips(h.market, vm::setHomeMarket)
            val sub = buildList {
                if (h.dataDate > 0) add((if (h.realtime) "실시간 · " else "기준일 ") + Fmt.date(h.dataDate))
                h.usdKrw?.let { add("환율 ${Fmt.decimal(it)}원/$") }
            }
            if (sub.isNotEmpty()) SmallLabel(sub.joinToString("  ·  "), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (h.loading && h.featured.isEmpty()) item { Loading() }
        h.error?.let { e ->
            item { ErrorNotice(e) { vm.refreshHome() } }
            // 키가 없어서 못 보는 경우에는 설정으로 바로 갈 수 있게 한다.
            if (h.market != Market.CRYPTO && e.contains("설정")) item { Notice("키는 모두 무료입니다. 코인은 키 없이 바로 볼 수 있습니다.", action = "설정 열기") { vm.tab = Tab.SETTINGS } }
        }
        if (h.market == Market.KR && !vm.keys.hasKis && vm.keys.hasPublic) item {
            Notice("국내 시세는 공공데이터포털 자료라 전날 종가 기준입니다. 한국투자증권 앱키를 넣으면 실시간으로 바뀝니다.")
        }

        // 지수
        if (h.indices.isNotEmpty() || h.proxies.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                h.indices.forEach { IndexCard(it.name, Fmt.decimal(it.value), it.change, it.changeRate) }
                h.proxies.forEach { q -> IndexCard(q.asset.name, "$" + Fmt.decimal(q.price), q.change, q.changeRate) { vm.openAsset(q.asset) } }
            }
        }
        h.breadth?.let { b -> item { BreadthCard(b, h.market) } }

        // 특징종목
        if (h.featured.isNotEmpty()) item {
            SectionCard("특징종목", action = { TextButton(onClick = { vm.tab = Tab.DISCOVER; vm.loadFeatured(h.market, featureTab) }) { Text("더보기") } }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    h.featured.keys.forEach { k ->
                        androidx.compose.material3.FilterChip(selected = featureTab == k, onClick = { featureTab = k }, label = { Text(k.label) })
                    }
                }
                val rows = h.featured[featureTab].orEmpty()
                if (rows.isEmpty()) SmallLabel("자료가 없습니다.")
                rows.forEach { r -> QuoteRow(r.quote.asset, r.quote, note = r.note.takeIf { featureTab == FeatureKind.VALUE }) { vm.openAsset(r.quote.asset) } }
            }
        }

        // 테마
        if (h.themes.isNotEmpty()) item {
            SectionCard("테마 흐름", action = { TextButton(onClick = { vm.tab = Tab.DISCOVER; vm.loadThemes(h.market) }) { Text("전체") } }) {
                h.themes.take(4).forEach { t -> ThemeRow(t.theme.name, t.avgChangePct, "${t.upCount}/${t.quotes.size} 상승 · 주도 ${t.leader?.asset?.name.orEmpty()}") { vm.open(Screen.ThemeDetail(t.theme)) } }
                if (h.themes.size > 4) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    SmallLabel("약한 테마")
                    h.themes.takeLast(3).reversed().forEach { t -> ThemeRow(t.theme.name, t.avgChangePct, "${t.upCount}/${t.quotes.size} 상승") { vm.open(Screen.ThemeDetail(t.theme)) } }
                }
            }
        }

        // 주요 공시 (국내)
        if (h.disclosures.isNotEmpty()) item {
            SectionCard("오늘의 주요 공시") { NewsList(h.disclosures, context, 10) }
        }
        if (h.news.isNotEmpty()) item {
            SectionCard("시장 뉴스") { NewsList(h.news, context, 10) }
        }
        item {
            SmallLabel(
                "시세: 업비트·공공데이터포털·한국투자증권·Twelve Data, 공시: DART, 뉴스: Google 뉴스. 모든 정보는 참고용이며 투자 판단의 책임은 본인에게 있습니다.",
                Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun IndexCard(name: String, value: String, change: Double, rate: Double, onClick: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(132.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = PriceColors.color(change))
            Text("${if (change > 0) "▲" else if (change < 0) "▼" else ""}${Fmt.decimal(kotlin.math.abs(change))} ${Fmt.signedRate(rate)}", style = MaterialTheme.typography.bodySmall, color = PriceColors.color(change))
        }
    }
}

@Composable
private fun BreadthCard(b: Breadth, market: Market) {
    SectionCard("상승·하락 종목") {
        val total = b.total.coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(12.dp)) {
            if (b.up > 0) Box(Modifier.weight(b.up.toFloat()).fillMaxSize().background(PriceColors.up))
            if (b.flat > 0) Box(Modifier.weight(b.flat.toFloat()).fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant))
            if (b.down > 0) Box(Modifier.weight(b.down.toFloat()).fillMaxSize().background(PriceColors.down))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("상승 ${b.up} (${b.up * 100 / total}%)", color = PriceColors.up, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("보합 ${b.flat}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("하락 ${b.down} (${b.down * 100 / total}%)", color = PriceColors.down, style = MaterialTheme.typography.bodySmall)
        }
        if (market == Market.KR && (b.upperLimit > 0 || b.lowerLimit > 0)) SmallLabel("상한가 ${b.upperLimit} · 하한가 ${b.lowerLimit}")
    }
}

@Composable
fun ThemeRow(name: String, change: Double, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            SmallLabel(sub)
        }
        ChangeText(change, bold = true)
    }
}
