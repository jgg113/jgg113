@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.jgg113.gongmae.ui

import android.widget.Toast
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
import io.github.jgg113.gongmae.core.GoodsGroup
import io.github.jgg113.gongmae.core.MovableKind
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.PropertyFilter
import io.github.jgg113.gongmae.core.Regions
import io.github.jgg113.gongmae.core.RiskLevel
import io.github.jgg113.gongmae.core.SortOrder
import io.github.jgg113.gongmae.core.Source
import io.github.jgg113.gongmae.core.SpecialRight
import io.github.jgg113.gongmae.core.TenantStatus
import io.github.jgg113.gongmae.data.SearchSettings
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

/** 물건 종류 아이콘 (모두의경매처럼 한 번에 고르는 줄) */
private data class KindIcon(val label: String, val emoji: String, val categories: Set<Category>)

private val kindIcons = listOf(
    KindIcon("아파트", "🏢", setOf(Category.APARTMENT)),
    KindIcon("오피스텔", "🏬", setOf(Category.OFFICETEL)),
    KindIcon("자동차", "🚗", setOf(Category.VEHICLE)),
    KindIcon("빌라", "🏘️", setOf(Category.VILLA)),
    KindIcon("상가", "🏪", setOf(Category.COMMERCIAL)),
    KindIcon("토지", "🌱", setOf(Category.LAND, Category.FOREST)),
    KindIcon("단독", "🏠", setOf(Category.HOUSE)),
    KindIcon("공장", "🏭", setOf(Category.FACTORY)),
    KindIcon("동산", "💎", setOf(Category.MOVABLE)),
    KindIcon("기타", "📦", setOf(Category.ETC)),
)

private val goodsEmoji = mapOf(
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("공매·경매 검색", fontWeight = FontWeight.Bold) }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
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
            QueryStatus(vm)
            KindIconRow(vm)
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("일반 검색") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(if (vm.filter.activeCount > 0) "상세 검색 (${vm.filter.activeCount})" else "상세 검색") },
                )
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
            initialSigungu = settings.sigungu,
            onDismiss = { showRegion = false },
            onConfirm = { sido, sigungu ->
                vm.updateSettings(settings.copy(sido = sido, sigungu = sigungu))
                showRegion = false
                vm.search()
            },
        )
    }
}

/** 통합 검색: 지역·물건·사건번호를 한 줄로 */
@Composable
private fun UnifiedSearchBar(query: String, onChange: (String) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("지역·물건·사건번호 (예: 논현동 아파트, 양주, 자동차)") },
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

/** 물건 종류를 아이콘으로 고른다. 자동차·동산을 고르면 전국 차량·동산 공매를 불러온다. */
@Composable
private fun KindIconRow(vm: AppViewModel) {
    val filter = vm.filter
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        kindIcons.forEach { icon ->
            val selected = icon.categories.all { it in filter.categories }
            KindButton(icon.emoji, icon.label, selected) {
                val next = if (selected) filter.categories - icon.categories else filter.categories + icon.categories
                vm.updateFilter(filter.copy(categories = next, goods = if (Category.MOVABLE in next || Category.VEHICLE in next) filter.goods else emptySet()))
                if (!selected) {
                    if (Category.VEHICLE in icon.categories) vm.loadMovables(setOf(MovableKind.VEHICLE))
                    if (Category.MOVABLE in icon.categories) vm.loadMovables(setOf(MovableKind.GOODS))
                }
            }
        }
    }
    if (Category.MOVABLE in filter.categories || Category.VEHICLE in filter.categories) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GoodsGroup.entries.filter { it.category in filter.categories }.forEach { group ->
                FilterChip(
                    selected = group in filter.goods,
                    onClick = { vm.updateFilter(filter.copy(goods = filter.goods.toggle(group))) },
                    label = { Text("${goodsEmoji[group].orEmpty()} ${group.label}") },
                )
            }
        }
    }
}

@Composable
private fun KindButton(emoji: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .width(56.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
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

    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPickRegion, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("${Regions.shortName(settings.sido)} ${settings.sigungu.ifBlank { "전체" }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            "${state.label} 조회 중… (${state.done}/${state.total})",
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
                message = "선택한 지역에 지금 진행 중인 물건이 없습니다. 지역을 넓히거나 경매 기간·재산유형을 더 골라 보세요." +
                    vm.warnings.joinToString("") { "\n\n$it" },
            )
            all.isEmpty() && state !is SearchState.Loading -> EmptyState(
                title = "공매·경매 물건을 찾아보세요",
                message = "위 검색창에 '논현동 아파트', '경기 광주 5억 이하', '양주', '자동차'처럼 쓰거나, 지역을 고르고 [검색]을 누르세요.\n" +
                    "자동차·동산 아이콘을 누르면 전국 차량·동산 공매를 불러옵니다.",
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

/** 빠른 조건: 자주 쓰는 것만 한 줄로 */
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
            selected = f.maxFailCount == 0,
            onClick = { vm.updateFilter(if (f.maxFailCount == 0) f.copy(maxFailCount = null) else f.copy(maxFailCount = 0, minFailCount = 0)) },
            label = { Text("신건") },
        )
        DropdownChip(
            label = if (f.minFailCount > 0) "유찰 ${f.minFailCount}회+" else "유찰",
            active = f.minFailCount > 0,
            options = listOf(0, 1, 2, 3, 5).map { n -> (if (n == 0) "전체" else "${n}회 이상") to { vm.updateFilter(f.copy(minFailCount = n, maxFailCount = if (n > 0) null else f.maxFailCount)) } },
        )
        DropdownChip(
            label = f.maxPriceManwon?.let { "${priceLabel(it)} 이하" } ?: "금액",
            active = f.maxPriceManwon != null || f.minPriceManwon != null,
            options = listOf<Long?>(null, 1_000, 5_000, 10_000, 20_000, 30_000, 50_000, 100_000).map { v ->
                (if (v == null) "전체" else "${priceLabel(v)} 이하") to { vm.updateFilter(f.copy(maxPriceManwon = v, minPriceManwon = null)) }
            },
        )
        DropdownChip(
            label = f.maxRatioPercent?.let { "감정가 ${it}%↓" } ?: "할인율",
            active = f.maxRatioPercent != null,
            options = listOf<Int?>(null, 80, 70, 60, 50).map { v ->
                (if (v == null) "전체" else "감정가의 ${v}% 이하 (${100 - v}%↑ 할인)") to { vm.updateFilter(f.copy(maxRatioPercent = v)) }
            },
        )
        FilterChip(selected = f.withinDays == 7, onClick = { vm.updateFilter(f.copy(withinDays = if (f.withinDays == 7) null else 7)) }, label = { Text("마감 7일") })
        FilterChip(selected = f.maxRisk == RiskLevel.SAFE, onClick = { vm.updateFilter(f.copy(maxRisk = if (f.maxRisk == RiskLevel.SAFE) null else RiskLevel.SAFE)) }, label = { Text("안전만") })
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
 * 상세 검색: 경매 사이트처럼 항목별로 세세하게 고른다. 아래 버튼에 지금 결과 중 몇 건이 맞는지 바로 보여 준다.
 * 지역·공매/경매 범위를 바꾸면 버튼이 '새로 검색'이 된다.
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
        Heading("물건과 지역")
        Section("구분") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val options = listOf("전체" to emptySet(), "경매" to setOf(Source.COURT), "공매" to setOf(Source.ONBID))
                options.forEach { (label, value) ->
                    FilterChip(
                        selected = draft.sources == value,
                        onClick = {
                            draft = draft.copy(sources = value)
                            scope = scope.copy(sources = value.ifEmpty { Source.entries.toSet() })
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        Section("소재지") {
            OutlinedButton(onClick = onPickRegion, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("${Regions.shortName(vm.settings.sido)} ${vm.settings.sigungu.ifBlank { "전체" }}  (바꾸면 바로 새로 검색)")
            }
            Hint("동 이름은 위 검색창에 쓰세요 (예: 논현동). 차량·동산은 지역과 상관없이 전국을 불러옵니다.")
        }
        Section("물건종류") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = draft.categories.isEmpty() && draft.goods.isEmpty(), onClick = { draft = draft.copy(categories = emptySet(), goods = emptySet()) }, label = { Text("전체") })
                (kindIcons.map { it.label to it.categories } + listOf("임야" to setOf(Category.FOREST))).forEach { (label, set) ->
                    val icon = kindIcons.firstOrNull { it.label == label }?.emoji ?: "⛰️"
                    FilterChip(
                        selected = set.all { it in draft.categories },
                        onClick = {
                            val on = set.all { it in draft.categories }
                            draft = draft.copy(categories = if (on) draft.categories - set else draft.categories + set)
                        },
                        label = { Text("$icon $label") },
                    )
                }
            }
        }
        if (Category.MOVABLE in draft.categories || Category.VEHICLE in draft.categories) {
            Section("차량·동산 세부") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GoodsGroup.entries.filter { it.category in draft.categories }.forEach { group ->
                        FilterChip(
                            selected = group in draft.goods,
                            onClick = { draft = draft.copy(goods = draft.goods.toggle(group)) },
                            label = { Text("${goodsEmoji[group].orEmpty()} ${group.label}") },
                        )
                    }
                }
            }
        }

        Heading("가격")
        Section("최저입찰가") {
            RangePicker(draft.minPriceManwon, draft.maxPriceManwon) { min, max -> draft = draft.copy(minPriceManwon = min, maxPriceManwon = max) }
        }
        Section("감정가") {
            RangePicker(draft.minAppraisalManwon, draft.maxAppraisalManwon) { min, max -> draft = draft.copy(minAppraisalManwon = min, maxAppraisalManwon = max) }
        }
        Section("할인율 (감정가 대비 최저가)") {
            ChipRow(listOf<Int?>(null, 90, 80, 70, 60, 50, 40), draft.maxRatioPercent, { if (it == null) "전체" else "${it}% 이하" }) {
                draft = draft.copy(maxRatioPercent = it)
            }
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(BidWindow.OPEN, BidWindow.UPCOMING, BidWindow.ENDED).forEach { w ->
                    FilterChip(selected = w in draft.windows, onClick = { draft = draft.copy(windows = draft.windows.toggle(w)) }, label = { Text(w.label) })
                }
            }
        }
        Section("매각기일·입찰마감") {
            ChipRow(listOf<Int?>(null, 3, 7, 14, 30), draft.withinDays, { if (it == null) "전체" else "${it}일 안" }) { draft = draft.copy(withinDays = it) }
        }
        Section("유찰") {
            val current = when {
                draft.maxFailCount == 0 -> -1
                else -> draft.minFailCount
            }
            ChipRow(listOf(0, -1, 1, 2, 3, 5), current, {
                when (it) {
                    0 -> "전체"
                    -1 -> "신건"
                    else -> "${it}회 이상"
                }
            }) {
                draft = if (it == -1) draft.copy(maxFailCount = 0, minFailCount = 0) else draft.copy(minFailCount = it, maxFailCount = null)
            }
        }
        Section("처분방식") {
            ChipRow(Disposal.entries, draft.disposal, { it.label }) { draft = draft.copy(disposal = it) }
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TenantStatus.entries.forEach { t ->
                    FilterChip(selected = t in draft.tenants, onClick = { draft = draft.copy(tenants = draft.tenants.toggle(t)) }, label = { Text(t.label) })
                }
            }
            Hint("공고 문구·권리 상세·법원 이해관계인으로 판단합니다. 모르면 '미상'입니다.")
        }
        Section("AI 위험도") {
            ChipRow(listOf<RiskLevel?>(null, RiskLevel.SAFE, RiskLevel.CAUTION), draft.maxRisk, {
                when (it) {
                    null -> "전체"
                    RiskLevel.SAFE -> "안전만"
                    else -> "주의까지 (위험 빼기)"
                }
            }) { draft = draft.copy(maxRisk = it) }
        }
        Section("AI 종합점수") {
            ChipRow(listOf<Int?>(null, 50, 60, 70, 80), draft.minScore, { if (it == null) "전체" else "${it}점 이상" }) { draft = draft.copy(minScore = it) }
        }

        Heading("조회 범위")
        Section("공매 재산유형") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssetType.entries.forEach { type ->
                    FilterChip(selected = type in scope.assetTypes, onClick = { scope = scope.copy(assetTypes = scope.assetTypes.toggle(type)) }, label = { Text(type.label) })
                }
            }
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
                    val kinds = buildSet {
                        if (Category.VEHICLE in applied.categories) add(MovableKind.VEHICLE)
                        if (Category.MOVABLE in applied.categories) add(MovableKind.GOODS)
                    }
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
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
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
private fun NoticeCard(text: String) {
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

@Composable
private fun RegionPickerDialog(
    initialSido: String,
    initialSigungu: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var sido by remember { mutableStateOf(initialSido) }
    var sigungu by remember { mutableStateOf(initialSigungu) }
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
                    Regions.sidoList.forEach { name ->
                        FilterChip(
                            selected = name == sido,
                            onClick = {
                                if (name != sido) {
                                    sido = name
                                    sigungu = ""
                                }
                            },
                            label = { Text(Regions.shortName(name)) },
                        )
                    }
                }
                val sigunguList = Regions.sigunguOf(sido)
                if (sigunguList.isNotEmpty()) {
                    Text("시/군/구", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = sigungu.isEmpty(), onClick = { sigungu = "" }, label = { Text("전체") })
                        sigunguList.forEach { name ->
                            FilterChip(selected = name == sigungu, onClick = { sigungu = name }, label = { Text(name) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(sido, sigungu) }) { Text("이 지역 검색") } },
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
