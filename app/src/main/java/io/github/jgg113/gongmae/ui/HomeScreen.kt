@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.jgg113.gongmae.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jgg113.gongmae.core.Analysis
import io.github.jgg113.gongmae.core.Category
import io.github.jgg113.gongmae.core.Format
import io.github.jgg113.gongmae.core.KindGroup
import io.github.jgg113.gongmae.core.OnbidExtrasClient
import io.github.jgg113.gongmae.core.OnbidMoreClient
import io.github.jgg113.gongmae.core.ResultType
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.RankDivision
import io.github.jgg113.gongmae.core.RankKind
import io.github.jgg113.gongmae.core.Regions
import io.github.jgg113.gongmae.core.Schedule
import io.github.jgg113.gongmae.core.SmallInvest
import io.github.jgg113.gongmae.core.Theme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val newsTime = DateTimeFormatter.ofPattern("M.d HH:mm")

/** 홈 바로가기 (온비드처럼 부동산·차량·동산 세부) */
private data class Shortcut(val label: String, val emoji: String, val group: KindGroup, val category: Category? = null)

private val shortcuts = listOf(
    Shortcut("아파트", "🏢", KindGroup.REAL_ESTATE, Category.APARTMENT),
    Shortcut("오피스텔", "🏬", KindGroup.REAL_ESTATE, Category.OFFICETEL),
    Shortcut("빌라", "🏘️", KindGroup.REAL_ESTATE, Category.VILLA),
    Shortcut("주택", "🏠", KindGroup.REAL_ESTATE, Category.HOUSE),
    Shortcut("상가", "🏪", KindGroup.REAL_ESTATE, Category.COMMERCIAL),
    Shortcut("토지", "🌱", KindGroup.REAL_ESTATE, Category.LAND),
    Shortcut("공장", "🏭", KindGroup.REAL_ESTATE, Category.FACTORY),
    Shortcut("자동차", "🚗", KindGroup.VEHICLE),
    Shortcut("동산", "📦", KindGroup.GOODS),
)

/** 자주 쓰는 공식 사이트 (고시·공고, 시세, 서류) */
private val usefulLinks = listOf(
    "토지이음 고시·공고" to "https://www.eum.go.kr/web/gs/gv/gvGosiList.jsp",
    "토지이음 (용도지역·규제)" to "https://www.eum.go.kr/",
    "법원경매 매각공고" to "https://www.courtauction.go.kr/",
    "온비드" to "https://www.onbid.co.kr/",
    "국토부 실거래가 공개" to "https://rt.molit.go.kr/",
    "부동산공시가격알리미" to "https://www.realtyprice.kr/",
    "정비사업 정보몽땅 (서울)" to "https://cleanup.seoul.go.kr/",
    "인터넷등기소" to "https://www.iros.go.kr/",
    "정부24 (건축물대장·토지대장)" to "https://www.gov.kr/",
)

@Composable
fun HomeScreen(vm: AppViewModel, onOpenDetail: (String) -> Unit, onOpenSearch: () -> Unit, onOpenExplore: () -> Unit = {}) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var showRegion by rememberSaveable { mutableStateOf(false) }
    val list = vm.allResults
    val analyses = vm.analyses
    val now = vm.now

    LaunchedEffect(Unit) { vm.loadNews() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("공매·경매 한눈에", fontWeight = FontWeight.Bold) }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "search") {
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("어디·무엇을 찾으세요? (예: 전국 아파트, 논현동, 양주)") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            vm.runQuery(query)
                            onOpenSearch()
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { showRegion = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(Regions.label(vm.settings.sido, vm.settings.sigungu), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            vm.search()
                            onOpenSearch()
                        }, enabled = vm.searchState !is SearchState.Loading) { Text("이 지역 검색") }
                    }
                    val state = vm.searchState
                    if (state is SearchState.Loading) {
                        Text("${state.label} 불러오는 중… (${state.done}/${state.total})", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else if (list.isNotEmpty()) {
                        Text(
                            "불러온 물건 ${list.size}건 (${Regions.label(vm.settings.sido, vm.settings.sigungu)}) 기준으로 아래를 골랐습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "shortcuts") {
                FlowRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 5,
                ) {
                    shortcuts.forEach { s ->
                        ShortcutButton(s.emoji, s.label) {
                            val base = vm.filter
                            vm.updateFilter(
                                base.copy(
                                    groups = setOf(s.group),
                                    categories = listOfNotNull(s.category).toSet() + if (s.category == Category.LAND) setOf(Category.FOREST) else emptySet(),
                                    goods = emptySet(),
                                ),
                            )
                            s.group.movableKind?.let { vm.loadMovables(setOf(it)) }
                            if (s.group == KindGroup.REAL_ESTATE && vm.results.isEmpty() && vm.searchState !is SearchState.Loading) vm.search()
                            onOpenSearch()
                        }
                    }
                }
            }

            val changes = vm.favoriteChanges.filterKeys { id -> vm.favorites.any { it.id == id } }
            if (changes.isNotEmpty()) {
                item(key = "changes") {
                    HomeSection("🔔 관심 물건 변동", action = "모두 확인" to { vm.clearFavoriteChanges() }) {
                        changes.entries.take(5).forEach { (id, lines) ->
                            val p = vm.favorites.firstOrNull { it.id == id } ?: return@forEach
                            Card(
                                onClick = {
                                    vm.clearFavoriteChanges(id)
                                    onOpenDetail(id)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    lines.take(2).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                                }
                            }
                        }
                    }
                }
            }

            if (list.isNotEmpty()) {
                item(key = "calendar") { ScheduleSection(list, onOpenDetail) }
                Theme.entries.filter { it != Theme.SMALL }.forEach { theme ->
                    val picked = theme.pick(list, analyses, now).take(12)
                    if (picked.isNotEmpty()) {
                        item(key = "theme-${theme.name}") {
                            HomeSection("${theme.emoji} ${theme.title}", subtitle = theme.subtitle) {
                                PropertyCarousel(picked, analyses, onOpenDetail)
                            }
                        }
                    }
                }
                item(key = "small") { SmallInvestSection(vm, onOpenDetail) }
            } else {
                item(key = "empty") {
                    Text(
                        "지역을 검색하면 여기에 '권리 깨끗 + 저평가', '시세보다 싼', '이번에 팔릴 만한', '많이 깎인', '신건', '마감 임박', 소액투자, 매각기일 달력이 나타납니다.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "ranking") { RankingSection(vm, onOpenDetail) }
            item(key = "sold") { RecentSoldSection(vm) }
            item(key = "stats") { StatsSection(vm) }
            item(key = "explore") {
                HomeSection("🗂 온비드 데이터 더보기", subtitle = "정부재산(국유·공유·귀속) · 불용품 · 개찰 끝난 공고 결과") {
                    OutlinedButton(onClick = onOpenExplore, modifier = Modifier.fillMaxWidth()) { Text("열기") }
                }
            }
            item(key = "news") { NewsSection(vm) }
            item(key = "links") {
                HomeSection("🔗 고시·공고와 공식 사이트") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        usefulLinks.forEach { (label, url) ->
                            OutlinedButton(onClick = { Links.openUrl(context, url) }) { Text(label, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }
        }
    }

    if (showRegion) {
        RegionPickerDialog(
            initialSido = vm.settings.sido,
            initialSigungu = Regions.splitSigungu(vm.settings.sigungu),
            onDismiss = { showRegion = false },
            onConfirm = { sido, sigungus ->
                showRegion = false
                vm.searchRegion(sido, sigungus)
            },
        )
    }
}

@Composable
private fun ShortcutButton(emoji: String, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp)
            .width(60.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun HomeSection(
    title: String,
    subtitle: String? = null,
    action: Pair<String, () -> Unit>? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (action != null) TextButton(onClick = action.second) { Text(action.first) }
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun PropertyCarousel(list: List<Property>, analyses: Map<String, Analysis>, onOpenDetail: (String) -> Unit, note: (Property) -> String? = { null }) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.id }) { p -> MiniCard(p, analyses[p.id], note(p)) { onOpenDetail(p.id) } }
    }
}

/** 가로로 넘겨 보는 작은 물건 카드 */
@Composable
fun MiniCard(p: Property, analysis: Analysis?, note: String? = null, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(190.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column {
            Box {
                Thumbnail(
                    p,
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                )
                val source = sourceColors(p.source)
                Tag(if (p.category.isMovable) p.category.label else p.source.label, source.container, source.content, Modifier.padding(6.dp))
            }
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.name.ifBlank { p.usageLabel }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Format.wonShort(p.minBidPrice)} · ${p.appraisalRatio?.let { Format.percent(it) } ?: "-"}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    listOfNotNull(
                        p.sigungu.ifBlank { null },
                        if (p.failCount > 0) "유찰 ${p.failCount}" else "신건",
                        analysis?.let { "위험 ${it.riskLevel.label}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, maxLines = 1)
            }
        }
    }
}

/** 매각기일·입찰마감 달력 (2주) */
@Composable
private fun ScheduleSection(list: List<Property>, onOpenDetail: (String) -> Unit) {
    val today = LocalDate.now()
    val byDate = remember(list) { Schedule.byDate(list, today, days = 14) }
    var selected by rememberSaveable { mutableStateOf(today.toString()) }
    HomeSection("📅 매각기일·입찰마감 일정", subtitle = "경매는 매각기일, 공매는 입찰 마감일 기준") {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (0 until 14).forEach { offset ->
                val day = today.plusDays(offset.toLong())
                val count = byDate[day]?.size ?: 0
                val on = selected == day.toString()
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (on) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable { selected = day.toString() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(if (offset == 0) "오늘" else day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN), style = MaterialTheme.typography.labelSmall)
                    Text("${day.monthValue}.${day.dayOfMonth}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(if (count > 0) "${count}건" else "-", style = MaterialTheme.typography.labelSmall, color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val day = runCatching { LocalDate.parse(selected) }.getOrDefault(today)
        val items = byDate[day].orEmpty()
        Spacer(Modifier.height(6.dp))
        if (items.isEmpty()) {
            Text("이 날 일정이 있는 물건이 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            items.take(6).forEach { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenDetail(p.id) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.source.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(p.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(Format.wonShort(p.minBidPrice), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
            if (items.size > 6) Text("외 ${items.size - 6}건", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 소액투자: 가진 돈으로 살 수 있는 물건 (대출 가정) */
@Composable
private fun SmallInvestSection(vm: AppViewModel, onOpenDetail: (String) -> Unit) {
    var budgetManwon by rememberSaveable { mutableStateOf("5000") }
    val budget = (budgetManwon.toLongOrNull() ?: 0) * 10_000
    val markets = vm.markets.mapNotNull { (id, state) -> (state as? MarketState.Loaded)?.let { id to it.summary } }.toMap()
    val picked = remember(vm.allResults, vm.analyses, budget) { SmallInvest.within(budget, vm.allResults, vm.analyses, markets).take(12) }
    HomeSection("🪙 소액 투자", subtitle = "내 돈(현금)으로 살 수 있는 물건. 주거용 대출 60%·그 밖 50%, 취득세·등기·명도비 포함 추정") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = budgetManwon,
                onValueChange = { budgetManwon = it.filter(Char::isDigit).take(7) },
                label = { Text("투자금 (만원)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(150.dp),
            )
            Spacer(Modifier.width(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1_000, 3_000, 5_000, 10_000, 30_000).forEach { v ->
                    FilterChip(selected = budgetManwon == v.toString(), onClick = { budgetManwon = v.toString() }, label = { Text(Format.wonShort(v * 10_000L)) })
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (picked.isEmpty()) {
            Text("이 금액으로 살 수 있는 물건이 불러온 결과에 없습니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val cash = picked.associate { it.first.id to it.second }
            PropertyCarousel(picked.map { it.first }, vm.analyses, onOpenDetail) { p -> cash[p.id]?.let { "필요 현금 약 ${Format.wonShort(it)}" } }
        }
    }
}

/** 온비드 순위 (조회수·관심·저감률) — 지역과 상관없이 전국 */
@Composable
private fun RankingSection(vm: AppViewModel, onOpenDetail: (String) -> Unit) {
    var kind by rememberSaveable { mutableStateOf(RankKind.VIEWS) }
    var division by rememberSaveable { mutableStateOf(RankDivision.REAL_ESTATE) }
    LaunchedEffect(kind, division) { vm.loadRanking(kind, division) }
    HomeSection("🏆 온비드 인기·저감 TOP (전국)", subtitle = "온비드 순위물건목록 API") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RankKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RankDivision.entries.forEach { d -> FilterChip(selected = division == d, onClick = { division = d }, label = { Text(d.label) }) }
        }
        when (val state = vm.rankings[vm.rankingKey(kind, division)]) {
            null, Load.Loading -> CircularProgressIndicator(Modifier.padding(8.dp))
            is Load.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.loadRanking(kind, division, force = true) }) { Icon(Icons.Filled.Refresh, contentDescription = "다시") }
            }
            is Load.Done -> if (state.value.isEmpty()) {
                Text("순위 물건이 없습니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                PropertyCarousel(state.value, vm.analyses, onOpenDetail) { p ->
                    when (kind) {
                        RankKind.VIEWS -> if (p.viewCount > 0) "조회 ${p.viewCount}" else null
                        RankKind.INTEREST -> if (p.interestCount > 0) "관심 ${p.interestCount}" else null
                        RankKind.DISCOUNT -> p.appraisalRatio?.let { "감정가의 ${Format.percent(it)}" }
                    }
                }
            }
        }
    }
}

/** 지역 최근 30일 공매 낙찰 (온비드 물건 입찰결과목록) */
@Composable
private fun RecentSoldSection(vm: AppViewModel) {
    var type by rememberSaveable { mutableStateOf(ResultType.REAL_ESTATE) }
    val sido = if (type == ResultType.REAL_ESTATE) vm.settings.sido else ""
    val sigungu = if (type == ResultType.REAL_ESTATE) vm.settings.sigungu else ""
    LaunchedEffect(type, sido, sigungu) { vm.loadRecentSold(type, sido, sigungu) }
    val where = if (sido.isBlank()) "전국" else Regions.label(sido, sigungu)
    HomeSection("🔨 최근 30일 공매 낙찰 ($where)", subtitle = "얼마에 팔렸나 — 감정가 대비 낙찰가율", action = "새로고침" to { vm.loadRecentSold(type, sido, sigungu, force = true) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ResultType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }) }
        }
        LoadView(vm.recentSold[vm.recentSoldKey(type, sido, sigungu)]) { cases ->
            val sold = cases.filter { it.sold }
            if (sold.isEmpty()) {
                Text("최근 30일 낙찰 결과가 없습니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                val ratios = sold.mapNotNull { it.ratio }.sorted()
                if (ratios.isNotEmpty()) {
                    Text(
                        "낙찰 ${sold.size}건 · 낙찰가율 중간값 ${Format.percent(ratios[ratios.size / 2])}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                sold.take(8).forEach { SoldRow(it, showRegion = type != ResultType.REAL_ESTATE || Regions.splitSigungu(sigungu).size != 1) }
            }
        }
    }
}

/** 온비드 용도별·지역별 낙찰 통계 */
@Composable
private fun StatsSection(vm: AppViewModel) {
    var byRegion by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf(OnbidExtrasClient.STATS_TYPES.first().first) }
    var regionType by rememberSaveable { mutableStateOf(OnbidMoreClient.REGION_STATS_TYPES.first().first) }
    LaunchedEffect(type, regionType, byRegion) { if (byRegion) vm.loadRegionStats(regionType) else vm.loadUsageStats(type) }
    HomeSection(
        "📊 공매 낙찰가율 통계 (${OnbidExtrasClient.defaultPeriod()}년)",
        subtitle = "온비드 ${if (byRegion) "지역별" else "용도별"} 입찰 통계 API · 낙찰가율 = 낙찰가 ÷ 감정가",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !byRegion, onClick = { byRegion = false }, label = { Text("용도별") })
            FilterChip(selected = byRegion, onClick = { byRegion = true }, label = { Text("지역별") })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (byRegion) {
                OnbidMoreClient.REGION_STATS_TYPES.forEach { (code, label) -> FilterChip(selected = regionType == code, onClick = { regionType = code }, label = { Text(label) }) }
            } else {
                OnbidExtrasClient.STATS_TYPES.forEach { (code, label) -> FilterChip(selected = type == code, onClick = { type = code }, label = { Text(label) }) }
            }
        }
        when (val state = if (byRegion) vm.regionStats[regionType] else vm.usageStats[type]) {
            null, Load.Loading -> CircularProgressIndicator(Modifier.padding(8.dp))
            is Load.Error -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            is Load.Done -> {
                val rows = state.value.filter { it.bidCount > 0 }.sortedByDescending { it.bidCount }.take(12)
                if (rows.isEmpty()) {
                    Text("통계가 없습니다.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(Modifier.padding(vertical = 4.dp)) {
                        listOf((if (byRegion) "지역" else "용도") to 2f, "낙찰가율" to 1f, "낙찰률" to 1f, "경쟁률" to 1f).forEach { (h, w) ->
                            Text(h, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(w))
                        }
                    }
                    HorizontalDivider()
                    rows.forEach { r ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(r.usage, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f))
                            Text(Format.percent(r.priceRate), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Text(Format.percent(r.soldRate), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(r.competition?.let { "$it:1" } ?: "-", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsSection(vm: AppViewModel) {
    val context = LocalContext.current
    HomeSection("📰 부동산·경매 뉴스", action = "새로고침" to { vm.loadNews(force = true) }) {
        when (val state = vm.news) {
            null, Load.Loading -> CircularProgressIndicator(Modifier.padding(8.dp))
            is Load.Error -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            is Load.Done -> state.value.take(8).forEach { item ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { Links.openUrl(context, item.link) }
                        .padding(vertical = 6.dp),
                ) {
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(item.source.ifBlank { null }, item.published?.withZoneSameInstant(java.time.ZoneId.systemDefault())?.format(newsTime)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
