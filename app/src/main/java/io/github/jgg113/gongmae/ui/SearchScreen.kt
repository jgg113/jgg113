@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.jgg113.gongmae.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jgg113.gongmae.core.AssetType
import io.github.jgg113.gongmae.core.BidWindow
import io.github.jgg113.gongmae.core.Category
import io.github.jgg113.gongmae.core.Comparison
import io.github.jgg113.gongmae.core.Disposal
import io.github.jgg113.gongmae.core.PropertyFilter
import io.github.jgg113.gongmae.core.Regions
import io.github.jgg113.gongmae.core.SortOrder
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

@Composable
fun SearchScreen(
    vm: AppViewModel,
    onOpenDetail: (String) -> Unit,
    onOpenCompare: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var showRegion by rememberSaveable { mutableStateOf(false) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    val settings = vm.settings
    val state = vm.searchState
    val visible = vm.visibleResults

    Scaffold(
        topBar = { TopAppBar(title = { Text("공매 검색", fontWeight = FontWeight.Bold) }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showRegion = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${Regions.shortName(settings.sido)} ${settings.sigungu.ifBlank { "전체" }}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = vm::search, enabled = state !is SearchState.Loading) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("검색")
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssetType.entries.forEach { type ->
                        FilterChip(
                            selected = type in settings.assetTypes,
                            onClick = { vm.updateSettings(settings.copy(assetTypes = settings.assetTypes.toggle(type))) },
                            label = { Text(type.label) },
                        )
                    }
                    FilterChip(
                        selected = settings.includePrivateContract,
                        onClick = { vm.updateSettings(settings.copy(includePrivateContract = !settings.includePrivateContract)) },
                        label = { Text("수의계약 포함") },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val active = vm.filter.activeCount
                    AssistChip(
                        onClick = { showFilter = true },
                        label = { Text(if (active > 0) "필터 $active" else "필터") },
                        leadingIcon = { Icon(Icons.Filled.Menu, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    Spacer(Modifier.weight(1f))
                    SortMenu(vm.sort, vm::updateSort)
                }
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
                when {
                    state is SearchState.Error && vm.results.isEmpty() -> EmptyState(
                        title = "조회하지 못했습니다",
                        message = state.message,
                        action = if (vm.serviceKey.isBlank() || state.message.contains("인증키")) {
                            { Button(onClick = onOpenSettings) { Text("설정으로 이동") } }
                        } else {
                            { OutlinedButton(onClick = vm::search) { Text("다시 시도") } }
                        },
                    )
                    vm.results.isEmpty() && state is SearchState.Done -> EmptyState(
                        title = "공매 물건이 없습니다",
                        message = "선택한 지역·재산유형에 지금 진행 중인 공매 물건이 없습니다. 지역을 넓히거나 재산유형을 더 골라 보세요.",
                    )
                    vm.results.isEmpty() && state !is SearchState.Loading -> EmptyState(
                        title = "공매 물건을 찾아보세요",
                        message = if (vm.serviceKey.isBlank()) {
                            "처음이라면 설정 탭에서 공공데이터포털 인증키를 입력하세요.\n그다음 지역을 고르고 [검색]을 누르면 온비드 공매 물건을 불러옵니다."
                        } else {
                            "지역과 재산유형을 고르고 [검색]을 누르세요.\n불러온 뒤에는 필터와 정렬로 바로 좁혀 볼 수 있습니다."
                        },
                        action = if (vm.serviceKey.isBlank()) {
                            { Button(onClick = onOpenSettings) { Text("인증키 설정하기") } }
                        } else {
                            null
                        },
                    )
                    vm.results.isNotEmpty() -> LazyColumn(
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 8.dp,
                            bottom = if (vm.compareItems.isEmpty()) 12.dp else 88.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "summary") {
                            ResultSummary(vm, visible.size, onResetFilter = { vm.updateFilter(PropertyFilter()) })
                        }
                        items(visible, key = { it.id }) { p ->
                            PropertyCard(
                                property = p,
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
    }

    if (showRegion) {
        RegionPickerDialog(
            initialSido = settings.sido,
            initialSigungu = settings.sigungu,
            onDismiss = { showRegion = false },
            onConfirm = { sido, sigungu ->
                vm.updateSettings(settings.copy(sido = sido, sigungu = sigungu))
                showRegion = false
            },
        )
    }
    if (showFilter) {
        FilterSheet(
            initial = vm.filter,
            onDismiss = { showFilter = false },
            onApply = {
                vm.updateFilter(it)
                showFilter = false
            },
        )
    }
}

@Composable
private fun ResultSummary(vm: AppViewModel, visibleCount: Int, onResetFilter: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val fetched = (vm.searchState as? SearchState.Done)?.fetchedAt ?: vm.now
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${visibleCount}건",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                " / 전체 ${vm.results.size}건 · ${fetched.format(timeFormat)} 기준",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (vm.filter.activeCount > 0) {
                TextButton(onClick = onResetFilter) { Text("필터 해제") }
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
                "필터 조건에 맞는 물건이 없습니다. 필터를 바꾸거나 해제해 보세요.",
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
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun SortMenu(current: SortOrder, onSelect: (SortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(current.label)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    onClick = {
                        onSelect(order)
                        expanded = false
                    },
                    leadingIcon = if (order == current) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
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
        confirmButton = { TextButton(onClick = { onConfirm(sido, sigungu) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun FilterSheet(initial: PropertyFilter, onDismiss: () -> Unit, onApply: (PropertyFilter) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var minPrice by remember { mutableStateOf(initial.minPriceManwon?.toString().orEmpty()) }
    var maxPrice by remember { mutableStateOf(initial.maxPriceManwon?.toString().orEmpty()) }
    var minArea by remember { mutableStateOf(initial.minAreaSqm?.let(::plainNumber).orEmpty()) }
    var maxArea by remember { mutableStateOf(initial.maxAreaSqm?.let(::plainNumber).orEmpty()) }
    var keyword by remember { mutableStateOf(initial.keyword) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("필터", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            FilterSection("용도") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Category.entries.forEach { c ->
                        FilterChip(
                            selected = c in draft.categories,
                            onClick = { draft = draft.copy(categories = draft.categories.toggle(c)) },
                            label = { Text(c.label) },
                        )
                    }
                }
            }

            FilterSection("최저입찰가 (만원)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(minPrice, { minPrice = it }, "최소", Modifier.weight(1f))
                    Text("  ~  ")
                    NumberField(maxPrice, { maxPrice = it }, "최대", Modifier.weight(1f))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5_000L to "5천만", 10_000L to "1억", 30_000L to "3억", 50_000L to "5억", 100_000L to "10억").forEach { (value, label) ->
                        AssistChip(
                            onClick = {
                                minPrice = ""
                                maxPrice = value.toString()
                            },
                            label = { Text("$label 이하") },
                        )
                    }
                }
            }

            FilterSection("감정가 대비 최저입찰가") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf<Int?>(null, 50, 60, 70, 80).forEach { value ->
                        FilterChip(
                            selected = draft.maxRatioPercent == value,
                            onClick = { draft = draft.copy(maxRatioPercent = value) },
                            label = { Text(if (value == null) "전체" else "$value% 이하") },
                        )
                    }
                }
            }

            FilterSection("유찰 횟수") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 1, 2, 3, 5).forEach { value ->
                        FilterChip(
                            selected = draft.minFailCount == value,
                            onClick = { draft = draft.copy(minFailCount = value) },
                            label = { Text(if (value == 0) "전체" else "${value}회 이상") },
                        )
                    }
                }
            }

            FilterSection("면적 (㎡)") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(minArea, { minArea = it }, "최소", Modifier.weight(1f))
                    Text("  ~  ")
                    NumberField(maxArea, { maxArea = it }, "최대", Modifier.weight(1f))
                }
                Text(
                    "84㎡ ≈ 25평, 59㎡ ≈ 18평",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FilterSection("입찰 상태") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(BidWindow.OPEN, BidWindow.UPCOMING, BidWindow.ENDED).forEach { w ->
                        FilterChip(
                            selected = w in draft.windows,
                            onClick = { draft = draft.copy(windows = draft.windows.toggle(w)) },
                            label = { Text(w.label) },
                        )
                    }
                }
            }

            FilterSection("처분방식") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Disposal.entries.forEach { d ->
                        FilterChip(
                            selected = draft.disposal == d,
                            onClick = { draft = draft.copy(disposal = d) },
                            label = { Text(d.label) },
                        )
                    }
                }
            }

            FilterSection("키워드") {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text("물건명·주소 (예: 래미안, 역삼동)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(Modifier.padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = {
                        draft = PropertyFilter()
                        minPrice = ""
                        maxPrice = ""
                        minArea = ""
                        maxArea = ""
                        keyword = ""
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("초기화") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onApply(
                            draft.copy(
                                minPriceManwon = minPrice.toLongOrNull(),
                                maxPriceManwon = maxPrice.toLongOrNull(),
                                minAreaSqm = minArea.toDoubleOrNull(),
                                maxAreaSqm = maxArea.toDoubleOrNull(),
                                keyword = keyword.trim(),
                            ),
                        )
                    },
                    modifier = Modifier.weight(2f),
                ) { Text("적용") }
            }
        }
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
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

private fun plainNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
