@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.jgg113.gongmae.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jgg113.gongmae.core.AssetType
import io.github.jgg113.gongmae.core.BidWindow
import io.github.jgg113.gongmae.core.Category
import io.github.jgg113.gongmae.core.Comparison
import io.github.jgg113.gongmae.core.Disposal
import io.github.jgg113.gongmae.core.FailBucket
import io.github.jgg113.gongmae.core.GoodsGroup
import io.github.jgg113.gongmae.core.KindGroup
import io.github.jgg113.gongmae.core.MovableKind
import io.github.jgg113.gongmae.core.PriceBand
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.PropertyFilter
import io.github.jgg113.gongmae.core.RatioBand
import io.github.jgg113.gongmae.core.Regions
import io.github.jgg113.gongmae.core.RiskLevel
import io.github.jgg113.gongmae.core.SortOrder
import io.github.jgg113.gongmae.core.Source
import io.github.jgg113.gongmae.core.SpecialRight
import io.github.jgg113.gongmae.core.TenantStatus
import io.github.jgg113.gongmae.data.SearchSettings
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

/** 부동산 세부 종류 아이콘 */
val categoryEmoji: Map<Category, String> = mapOf(
    Category.APARTMENT to "🏢", Category.OFFICETEL to "🏬", Category.VILLA to "🏘️", Category.HOUSE to "🏠",
    Category.COMMERCIAL to "🏪", Category.FACTORY to "🏭", Category.LAND to "🌱", Category.FOREST to "⛰️", Category.ETC to "📦",
    Category.VEHICLE to "🚗", Category.MOVABLE to "💎",
)

val goodsEmoji: Map<GoodsGroup, String> = mapOf(
    GoodsGroup.CAR to "🚙", GoodsGroup.TRUCK to "🚚", GoodsGroup.MOTORCYCLE to "🏍️", GoodsGroup.MACHINERY to "🚜",
    GoodsGroup.LIQUOR to "🍾", GoodsGroup.LUXURY to "👜", GoodsGroup.JEWELRY to "💍", GoodsGroup.ELECTRONICS to "💻", GoodsGroup.ETC to "📦",
)

/** 금액 선택지 (만원) */
private val priceOptions: List<Pair<Long, String>> = listOf(
    100L to "100만", 500L to "500만", 1_000L to "1천만", 3_000L to "3천만", 5_000L to "5천만", 7_000L to "7천만",
    10_000L to "1억", 20_000L to "2억", 30_000L to "3억", 50_000L to "5억", 70_000L to "7억",
    100_000L to "10억", 200_000L to "20억", 500_000L to "50억", 1_000_000L to "100억",
)

private fun priceLabel(manwon: Long?): String = priceOptions.firstOrNull { it.first == manwon }?.second ?: manwon?.let { "${it}만" } ?: "제한 없음"

/** 큰 종류를 켜거나 끈다. 차량·동산을 켜면 전국 차량·동산 공매를 불러온다. */
fun AppViewModel.toggleGroup(group: KindGroup) {
    val f = filter
    val on = group in f.effectiveGroups
    val next = if (on) {
        f.copy(
            groups = f.groups - group,
            categories = f.categories.filterNot { it.group == group }.toSet(),
            goods = f.goods.filterNot { it.group == group }.toSet(),
        )
    } else {
        f.copy(groups = f.groups + group)
    }
    updateFilter(next)
    if (!on) group.movableKind?.let { loadMovables(setOf(it)) }
}

@Composable
fun SearchScreen(
    vm: AppViewModel,
    onOpenDetail: (String) -> Unit,
    onOpenCompare: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf(vm.filter.keyword) }
    var showRegion by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val collapsed = vm.headerCollapsed

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("공매·경매 검색", fontWeight = FontWeight.Bold) },
                actions = {
                    // 위쪽(검색창·종류·탭)을 접어 목록을 넓게 본다.
                    TextButton(onClick = vm::toggleHeader) {
                        Icon(if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, contentDescription = null)
                        Text(if (collapsed) "펼치기" else "접기")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (collapsed) {
                CollapsedHeader(vm, query, tab, onTab = { tab = it }, onExpand = vm::toggleHeader)
            }
            AnimatedVisibility(visible = !collapsed) {
                Column {
                    UnifiedSearchBar(
                        query = query,
                        onChange = { query = it },
                        onSearch = {
                            focus.clearFocus()
                            vm.runQuery(query)
                            tab = 0
                        },
                        onClear = {
                            query = ""
                            vm.runQuery("")
                        },
                    )
                    RecentQueries(vm) { text ->
                        query = text
                        vm.runQuery(text)
                        tab = 0
                    }
                    QueryStatus(vm)
                    KindGroupRow(vm)
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("일반 검색") })
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text(if (vm.filter.activeCount > 0) "상세 검색 (${vm.filter.activeCount})" else "상세 검색") },
                        )
                    }
                }
            }
            if (tab == 0) {
                GeneralSearch(vm, onOpenDetail, onOpenCompare, onOpenSettings, onPickRegion = { showRegion = true })
            } else {
                DetailSearch(vm, onPickRegion = { showRegion = true }, onShowResults = { tab = 0 })
            }
        }
    }

    if (showRegion) {
        val settings = vm.settings
        RegionPickerDialog(
            initialSido = settings.sido,
            initialSigungu = Regions.splitSigungu(settings.sigungu),
            onDismiss = { showRegion = false },
            onConfirm = { sido, sigungus ->
                showRegion = false
                vm.searchRegion(sido, sigungus)
            },
        )
    }
}

/** 접었을 때 한 줄: 검색어·지역 요약, 일반/상세 전환, 펼치기 */
@Composable
private fun CollapsedHeader(vm: AppViewModel, query: String, tab: Int, onTab: (Int) -> Unit, onExpand: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onExpand)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    listOf(query.ifBlank { "검색" }, Regions.label(vm.settings.sido, vm.settings.sigungu)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilterChip(selected = tab == 0, onClick = { onTab(0) }, label = { Text("일반") }, modifier = Modifier.padding(horizontal = 4.dp))
            FilterChip(
                selected = tab == 1,
                onClick = { onTab(1) },
                label = { Text(if (vm.filter.activeCount > 0) "상세 ${vm.filter.activeCount}" else "상세") },
            )
        }
    }
}

/** 통합 검색: 지역·물건·사건번호를 한 줄로 */
@Composable
private fun UnifiedSearchBar(query: String, onChange: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("지역·물건·사건번호 (예: 논현동 아파트, 전국 토지, 양주, 자동차)") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = "지우기") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 최근 검색어 (눌러서 다시 검색, ✕ 로 지우기) */
@Composable
private fun RecentQueries(vm: AppViewModel, onPick: (String) -> Unit) {
    val recent = vm.recentQueries
    if (recent.isEmpty()) return
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("최근", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        recent.forEach { text ->
            InputChip(
                selected = false,
                onClick = { onPick(text) },
                label = { Text(text, maxLines = 1) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "지우기",
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { vm.removeRecentQuery(text) },
                    )
                },
            )
        }
    }
}

@Composable
private fun QueryStatus(vm: AppViewModel) {
    val intent = vm.filter.intent
    val note = vm.queryNote
    val movable = vm.movableState
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!intent.isEmpty && intent.description.isNotBlank()) {
            Text("검색어 해석: ${intent.description}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (note != null) {
            Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (movable) {
            is MovableState.Loading -> {
                Text("차량·동산 공매: ${movable.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is MovableState.Error -> Text(movable.message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
    }
}

/**
 * 온비드처럼 부동산·차량·동산을 먼저 고르고(여러 개 가능) 그 안의 세부 종류를 고른다.
 * 아무것도 안 고르면 전체다.
 */
@Composable
private fun KindGroupRow(vm: AppViewModel) {
    val filter = vm.filter
    val groups = filter.effectiveGroups
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KindButton("✨", "전체", groups.isEmpty(), Modifier.weight(1f)) {
            vm.updateFilter(filter.copy(groups = emptySet(), categories = emptySet(), goods = emptySet()))
        }
        KindGroup.entries.forEach { group ->
            KindButton(group.emoji, group.label, group in groups, Modifier.weight(1f)) { vm.toggleGroup(group) }
        }
    }
    if (groups.isNotEmpty()) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (KindGroup.REAL_ESTATE in groups) {
                Category.realEstate.forEach { category ->
                    FilterChip(
                        selected = category in filter.categories,
                        onClick = { vm.updateFilter(filter.copy(groups = filter.groups + KindGroup.REAL_ESTATE, categories = filter.categories.toggle(category))) },
                        label = { Text("${categoryEmoji[category].orEmpty()} ${category.label}") },
                    )
                }
            }
            listOf(KindGroup.VEHICLE, KindGroup.GOODS).filter { it in groups }.forEach { group ->
                group.goods.forEach { goods ->
                    FilterChip(
                        selected = goods in filter.goods,
                        onClick = { vm.updateFilter(filter.copy(groups = filter.groups + group, goods = filter.goods.toggle(goods))) },
                        label = { Text("${goodsEmoji[goods].orEmpty()} ${goods.label}") },
                    )
                }
            }
        }
    }
}

@Composable
private fun KindButton(emoji: String, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** 일반 검색: 지역·공매/경매 고르고 빠른 조건 칩으로 바로 거른다. */
@Composable
private fun ColumnScope.GeneralSearch(
    vm: AppViewModel,
    onOpenDetail: (String) -> Unit,
    onOpenCompare: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickRegion: () -> Unit,
) {
    val context = LocalContext.current
    val settings = vm.settings
    val state = vm.searchState
    val filter = vm.filter
    val visible = vm.visibleResults

    // 보이는 물건의 실거래가·전월세·공시지가·건축물대장을 자동으로 불러와 정밀 분석에 넣는다.
    LaunchedEffect(visible) { vm.autoLookups(visible) }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPickRegion, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(Regions.label(settings.sido, settings.sigungu), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            Source.entries.forEach { source ->
                FilterChip(
                    selected = source in settings.sources,
                    onClick = { vm.updateSettings(settings.copy(sources = settings.sources.toggle(source))) },
                    label = { Text(source.label) },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            Button(onClick = vm::search, enabled = state !is SearchState.Loading) { Text("검색") }
        }
        QuickChips(vm)
    }
    HorizontalDivider()
    if (state is SearchState.Loading) {
        val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(
            "${state.label} 조회 중… (${state.done}/${state.total}) · 받은 물건부터 보여 줍니다",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    Box(Modifier.weight(1f)) {
        val all = vm.allResults
        when {
            state is SearchState.Error && all.isEmpty() -> EmptyState(
                title = "조회하지 못했습니다",
                message = state.message,
                action = if (vm.serviceKey.isBlank() || state.message.contains("인증키")) {
                    { Button(onClick = onOpenSettings) { Text("설정으로 이동") } }
                } else {
                    { OutlinedButton(onClick = vm::search) { Text("다시 시도") } }
                },
            )
            all.isEmpty() && state is SearchState.Done -> EmptyState(
                title = "물건이 없습니다",
                message = "선택한 지역에 지금 진행 중인 물건이 없습니다. 지역을 넓히거나(전국) 경매 기간·재산유형을 더 골라 보세요." +
                    vm.warnings.joinToString("") { "\n\n$it" },
            )
            all.isEmpty() && state !is SearchState.Loading -> EmptyState(
                title = "공매·경매 물건을 찾아보세요",
                message = "위 검색창에 '논현동 아파트', '전국 토지 5천만 이하', '양주', '자동차'처럼 쓰거나, 지역을 고르고 [검색]을 누르세요.\n" +
                    "차량·동산을 누르면 전국 차량·동산 공매를 불러옵니다.",
                action = if (vm.serviceKey.isBlank() && Source.ONBID in settings.sources) {
                    { OutlinedButton(onClick = onOpenSettings) { Text("공매 인증키 설정하기") } }
                } else {
                    null
                },
            )
            all.isNotEmpty() -> LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = if (vm.compareItems.isEmpty()) 12.dp else 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "summary") {
                    ResultSummary(vm, visible.size, onResetFilter = { vm.updateFilter(PropertyFilter(keyword = filter.keyword)) })
                }
                items(visible, key = { it.id }) { p ->
                    PropertyCard(
                        property = p,
                        analysis = vm.analyses[p.id],
                        photo = vm.courtPhotos(p.id).firstOrNull(),
                        onVisible = { vm.loadCourtDetail(p) },
                        now = vm.now,
                        isFavorite = vm.isFavorite(p.id),
                        isComparing = vm.isComparing(p.id),
                        onClick = { onOpenDetail(p.id) },
                        onToggleFavorite = { vm.toggleFavorite(p) },
                        onToggleCompare = {
                            if (!vm.toggleCompare(p)) {
                                Toast.makeText(context, "최대 ${Comparison.MAX_ITEMS}개까지 비교할 수 있습니다.", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }
        if (vm.compareItems.isNotEmpty()) {
            CompareBar(
                count = vm.compareItems.size,
                onClear = vm::clearCompare,
                onOpen = onOpenCompare,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 빠른 조건: 자주 쓰는 것만 한 줄로 (유찰·할인율은 여러 개 고를 수 있다) */
@Composable
private fun QuickChips(vm: AppViewModel) {
    val f = vm.filter
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        DropdownChip(
            label = vm.sort.label,
            active = vm.sort != SortOrder.SCORE_DESC,
            options = SortOrder.entries.map { it.label to { vm.updateSort(it) } },
        )
        FilterChip(selected = f.excludeAllSpecial, onClick = { vm.updateFilter(f.copy(excludeAllSpecial = !f.excludeAllSpecial)) }, label = { Text("특수 제외") })
        FilterChip(
            selected = RiskLevel.SAFE in f.riskLevels || f.maxRisk == RiskLevel.SAFE,
            onClick = {
                val on = RiskLevel.SAFE in f.riskLevels || f.maxRisk == RiskLevel.SAFE
                vm.updateFilter(f.copy(maxRisk = null, riskLevels = if (on) f.riskLevels - RiskLevel.SAFE else f.riskLevels + RiskLevel.SAFE))
            },
            label = { Text("안전") },
        )
        FailBucket.all.forEach { bucket ->
            FilterChip(
                selected = bucket in f.failBuckets,
                onClick = { vm.updateFilter(f.copy(failBuckets = f.failBuckets.toggle(bucket), minFailCount = 0, maxFailCount = null)) },
                label = { Text(if (bucket == 0) "신건" else "유찰 ${FailBucket.label(bucket)}") },
            )
        }
        FilterChip(
            selected = RatioBand.UNDER_50 in f.ratioBands,
            onClick = { vm.updateFilter(f.copy(ratioBands = f.ratioBands.toggle(RatioBand.UNDER_50))) },
            label = { Text("반값 이하") },
        )
        DropdownChip(
            label = f.maxPriceManwon?.let { "${priceLabel(it)} 이하" } ?: "금액",
            active = f.maxPriceManwon != null || f.minPriceManwon != null,
            options = listOf<Long?>(null, 1_000, 5_000, 10_000, 20_000, 30_000, 50_000, 100_000).map { v ->
                (if (v == null) "전체" else "${priceLabel(v)} 이하") to { vm.updateFilter(f.copy(maxPriceManwon = v, minPriceManwon = null)) }
            },
        )
        FilterChip(selected = f.withinDays == 7, onClick = { vm.updateFilter(f.copy(withinDays = if (f.withinDays == 7) null else 7)) }, label = { Text("마감 7일") })
        if (f.activeCount > 0) {
            AssistChip(onClick = { vm.updateFilter(PropertyFilter()) }, label = { Text("초기화") })
        }
    }
}

@Composable
private fun DropdownChip(label: String, active: Boolean, options: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            onClick = { expanded = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, action) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    action()
                    expanded = false
                })
            }
        }
    }
}

/**
 * 상세 검색: 경매 사이트처럼 항목별로 세세하게 고른다 (칩은 여러 개 고를 수 있다).
 * 아래 버튼에 지금 결과 중 몇 건이 맞는지 바로 보여 준다. 지역·공매/경매 범위를 바꾸면 버튼이 '새로 검색'이 된다.
 */
@Composable
private fun ColumnScope.DetailSearch(vm: AppViewModel, onPickRegion: () -> Unit, onShowResults: () -> Unit) {
    var draft by remember { mutableStateOf(vm.filter) }
    var scope by remember { mutableStateOf(vm.settings) }
    var minArea by remember { mutableStateOf(draft.minAreaSqm?.let { pyeong(it) }.orEmpty()) }
    var maxArea by remember { mutableStateOf(draft.maxAreaSqm?.let { pyeong(it) }.orEmpty()) }

    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Hint("모든 칩은 여러 개 고를 수 있습니다. 아무것도 안 고르면 전체입니다.")
        Heading("물건과 지역")
        Section("구분") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Source.entries.forEach { source ->
                    FilterChip(
                        selected = source in draft.sources,
                        onClick = {
                            draft = draft.copy(sources = draft.sources.toggle(source))
                            scope = scope.copy(sources = draft.sources.ifEmpty { Source.entries.toSet() })
                        },
                        label = { Text(if (source == Source.COURT) "경매 (법원)" else "공매 (온비드)") },
                    )
                }
            }
        }
        Section("소재지") {
            OutlinedButton(onClick = onPickRegion, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("${Regions.label(vm.settings.sido, vm.settings.sigungu)}  (바꾸면 바로 새로 검색)")
            }
            Hint("전국·여러 구를 함께 고를 수 있습니다. 동 이름은 위 검색창에 쓰세요 (예: 논현동). 차량·동산은 전국을 불러옵니다.")
        }
        Section("물건 종류") {
            val groups = draft.effectiveGroups
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KindGroup.entries.forEach { group ->
                    FilterChip(
                        selected = group in groups,
                        onClick = {
                            draft = if (group in groups) {
                                draft.copy(
                                    groups = draft.groups - group,
                                    categories = draft.categories.filterNot { it.group == group }.toSet(),
                                    goods = draft.goods.filterNot { it.group == group }.toSet(),
                                )
                            } else {
                                draft.copy(groups = draft.groups + group)
                            }
                        },
                        label = { Text("${group.emoji} ${group.label}") },
                    )
                }
            }
            if (KindGroup.REAL_ESTATE in groups) {
                SubLabel("부동산 세부")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Category.realEstate.forEach { category ->
                        FilterChip(
                            selected = category in draft.categories,
                            onClick = { draft = draft.copy(categories = draft.categories.toggle(category)) },
                            label = { Text("${categoryEmoji[category].orEmpty()} ${category.label}") },
                        )
                    }
                }
            }
            listOf(KindGroup.VEHICLE, KindGroup.GOODS).filter { it in groups }.forEach { group ->
                SubLabel("${group.label} 세부")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.goods.forEach { goods ->
                        FilterChip(
                            selected = goods in draft.goods,
                            onClick = { draft = draft.copy(goods = draft.goods.toggle(goods)) },
                            label = { Text("${goodsEmoji[goods].orEmpty()} ${goods.label}") },
                        )
                    }
                }
            }
        }

        Heading("가격")
        Section("최저입찰가") {
            BandChips(PriceBand.entries, draft.priceBands, { it.label }) { draft = draft.copy(priceBands = it) }
            Hint("직접 지정")
            RangePicker(draft.minPriceManwon, draft.maxPriceManwon) { min, max -> draft = draft.copy(minPriceManwon = min, maxPriceManwon = max) }
        }
        Section("감정가") {
            BandChips(PriceBand.entries, draft.appraisalBands, { it.label }) { draft = draft.copy(appraisalBands = it) }
        }
        Section("감정가 대비 최저가 (할인율)") {
            BandChips(RatioBand.entries, draft.ratioBands, { it.label }) { draft = draft.copy(ratioBands = it, maxRatioPercent = null) }
        }
        Section("면적 (평)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberField(minArea, { minArea = it }, "최소", Modifier.weight(1f))
                Text("  ~  ")
                NumberField(maxArea, { maxArea = it }, "최대", Modifier.weight(1f))
                Text("  평")
            }
            Hint("25평 ≈ 84㎡, 18평 ≈ 59㎡")
        }

        Heading("진행 상황")
        Section("입찰 상태") {
            BandChips(listOf(BidWindow.OPEN, BidWindow.UPCOMING, BidWindow.ENDED), draft.windows, { it.label }) { draft = draft.copy(windows = it) }
        }
        val statusNames = vm.statusNames
        if (statusNames.isNotEmpty()) {
            Section("진행상태 (불러온 물건 기준)") {
                BandChips(statusNames, draft.statuses, { it }) { draft = draft.copy(statuses = it) }
            }
        }
        Section("매각기일·입찰마감") {
            ChipRow(listOf<Int?>(null, 3, 7, 14, 30), draft.withinDays, { if (it == null) "전체" else "${it}일 안" }) { draft = draft.copy(withinDays = it) }
        }
        Section("유찰 횟수") {
            BandChips(FailBucket.all, draft.failBuckets, { FailBucket.label(it) }) { draft = draft.copy(failBuckets = it, minFailCount = 0, maxFailCount = null) }
        }
        Section("처분방식") {
            BandChips(listOf(Disposal.SALE, Disposal.LEASE), draft.disposals, { it.label }) { draft = draft.copy(disposals = it, disposal = Disposal.ALL) }
        }

        Heading("권리·점유·인수 조건")
        Section("특수권리") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SpecialRight.entries.forEach { right ->
                    FilterChip(selected = right in draft.specials, onClick = { draft = draft.copy(specials = draft.specials.toggle(right)) }, label = { Text(right.label) })
                }
            }
            SwitchRow("고른 권리가 있는 물건 빼기", "예: 지분매각 선택 + 켜기 = 지분매각 없는 물건만", draft.excludeSpecials) { draft = draft.copy(excludeSpecials = it) }
            SwitchRow("특수물건 모두 빼기", "유치권·법정지상권·지분 등 하나라도 있으면 뺍니다", draft.excludeAllSpecial) { draft = draft.copy(excludeAllSpecial = it) }
        }
        Section("대항력 있는 임차인") {
            BandChips(TenantStatus.entries, draft.tenants, { it.label }) { draft = draft.copy(tenants = it) }
            Hint("공고 문구·권리 상세·법원 이해관계인으로 판단합니다. 모르면 '미상'입니다.")
        }
        Section("AI 위험도") {
            BandChips(RiskLevel.entries, draft.riskLevels, { it.label }) { draft = draft.copy(riskLevels = it, maxRisk = null) }
        }
        Section("AI 종합점수") {
            ChipRow(listOf<Int?>(null, 50, 60, 70, 80), draft.minScore, { if (it == null) "전체" else "${it}점 이상" }) { draft = draft.copy(minScore = it) }
        }

        Heading("조회 범위")
        Section("공매 재산유형") {
            BandChips(AssetType.entries, scope.assetTypes, { it.label }) { scope = scope.copy(assetTypes = it) }
            SwitchRow("수의계약 물건 포함", null, scope.includePrivateContract) { scope = scope.copy(includePrivateContract = it) }
        }
        Section("경매 매각기일 범위") {
            ChipRow(listOf(2, 4, 8), scope.courtWeeks, { "${it}주 안" }) { scope = scope.copy(courtWeeks = it) }
        }
        Spacer(Modifier.size(8.dp))
    }

    val applied = draft.copy(
        minAreaSqm = minArea.toDoubleOrNull()?.let { it * Property.SQM_PER_PYEONG },
        maxAreaSqm = maxArea.toDoubleOrNull()?.let { it * Property.SQM_PER_PYEONG },
    )
    val scopeChanged = scope.differsForFetch(vm.settings)
    val count = vm.countMatching(applied)
    Surface(shadowElevation = 6.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                draft = PropertyFilter(keyword = vm.filter.keyword)
                minArea = ""
                maxArea = ""
            }) { Text("초기화") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    vm.updateFilter(applied)
                    if (scopeChanged) {
                        vm.updateSettings(scope)
                        vm.search()
                    }
                    val kinds = applied.effectiveGroups.mapNotNull { it.movableKind }.toSet()
                    if (kinds.isNotEmpty()) vm.loadMovables(kinds)
                    onShowResults()
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (scopeChanged) "새로 검색하기" else "${count}건 보기")
            }
        }
    }
}

private fun SearchSettings.differsForFetch(other: SearchSettings): Boolean =
    sources != other.sources || assetTypes != other.assetTypes || includePrivateContract != other.includePrivateContract || courtWeeks != other.courtWeeks

private fun pyeong(sqm: Double): String = Math.round(sqm / Property.SQM_PER_PYEONG).toString()

@Composable
private fun Heading(text: String) {
    Column {
        HorizontalDivider()
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun SubLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 하나만 고르는 칩 */
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

/** 여러 개 고르는 칩 (맨 앞 '전체'는 모두 해제) */
@Composable
private fun <T> BandChips(options: List<T>, selected: Set<T>, label: (T) -> String, onChange: (Set<T>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = selected.isEmpty(), onClick = { onChange(emptySet()) }, label = { Text("전체") })
        options.forEach { option ->
            FilterChip(selected = option in selected, onClick = { onChange(selected.toggle(option)) }, label = { Text(label(option)) })
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Hint(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 최소·최대 금액을 목록에서 고른다 (만원). */
@Composable
private fun RangePicker(min: Long?, max: Long?, onChange: (Long?, Long?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PriceDropdown("최소", min, Modifier.weight(1f)) { onChange(it, max?.takeIf { m -> it == null || m >= it }) }
        Text("  ~  ")
        PriceDropdown("최대", max, Modifier.weight(1f)) { onChange(min?.takeIf { m -> it == null || m <= it }, it) }
    }
}

@Composable
private fun PriceDropdown(title: String, value: Long?, modifier: Modifier, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (value == null) "$title 없음" else priceLabel(value), modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("$title 없음") }, onClick = {
                onSelect(null)
                expanded = false
            })
            priceOptions.forEach { (manwon, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onSelect(manwon)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun ResultSummary(vm: AppViewModel, visibleCount: Int, onResetFilter: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val fetched = (vm.searchState as? SearchState.Done)?.fetchedAt ?: vm.now
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${visibleCount}건", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                " / 전체 ${vm.allResults.size}건 · ${fetched.format(timeFormat)} 기준",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (vm.filter.activeCount > 0) {
                TextButton(onClick = onResetFilter) { Text("조건 해제") }
            }
        }
        val state = vm.searchState
        if (state is SearchState.Error) {
            NoticeCard("새로 조회하지 못해 이전 결과를 보여줍니다: ${state.message}")
        }
        if (vm.regionFilteredLocally) {
            NoticeCard("시군구 이름이 온비드 표기와 달라 시도 전체를 받아 주소로 걸렀습니다.")
        }
        vm.warnings.forEach { NoticeCard(it) }
        if (visibleCount == 0) {
            Text(
                "조건에 맞는 물건이 없습니다. 검색어나 조건을 바꾸거나 해제해 보세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}

@Composable
fun NoticeCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun CompareBar(count: Int, onClear: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .padding(12.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "비교함 $count/${Comparison.MAX_ITEMS}",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TextButton(onClick = onClear) { Text("비우기") }
            Button(onClick = onOpen) { Text("비교하기") }
        }
    }
}

/** 지역 선택: 전국 또는 시도 하나 + 시군구 여러 개 */
@Composable
fun RegionPickerDialog(
    initialSido: String,
    initialSigungu: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>) -> Unit,
) {
    var sido by remember { mutableStateOf(initialSido) }
    var sigungus by remember { mutableStateOf(initialSigungu.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("지역 선택") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("시/도", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = sido.isBlank(),
                        onClick = {
                            sido = Regions.NATIONWIDE
                            sigungus = emptySet()
                        },
                        label = { Text("🇰🇷 전국") },
                    )
                    Regions.sidoList.forEach { name ->
                        FilterChip(
                            selected = name == sido,
                            onClick = {
                                if (name != sido) {
                                    sido = name
                                    sigungus = emptySet()
                                }
                            },
                            label = { Text(Regions.shortName(name)) },
                        )
                    }
                }
                if (sido.isBlank()) {
                    Text(
                        "전국 공매는 모두, 법원경매는 시도마다 매각기일이 가까운 100건씩 불러옵니다. 1~2분 걸릴 수 있고, 받은 만큼 먼저 보여 줍니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val sigunguList = Regions.sigunguOf(sido)
                if (sigunguList.isNotEmpty()) {
                    Text("시/군/구 (여러 개 고를 수 있음)", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = sigungus.isEmpty(), onClick = { sigungus = emptySet() }, label = { Text("전체") })
                        sigunguList.forEach { name ->
                            FilterChip(selected = name in sigungus, onClick = { sigungus = sigungus.toggle(name) }, label = { Text(name) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(sido, sigungus.toList()) }) {
                Text(if (sido.isBlank()) "전국 검색" else "이 지역 검색")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onChange(input.filter { it.isDigit() || it == '.' }) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
