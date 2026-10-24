@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.jgg113.gongmae.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jgg113.gongmae.core.DisusedGoods
import io.github.jgg113.gongmae.core.Format
import io.github.jgg113.gongmae.core.GovProperty
import io.github.jgg113.gongmae.core.GovPropertyKind
import io.github.jgg113.gongmae.core.NoticeResult
import io.github.jgg113.gongmae.core.Regions
import io.github.jgg113.gongmae.core.ResultType
import io.github.jgg113.gongmae.core.RightsParser

private enum class ExploreTab(val label: String) {
    GOV("정부재산"),
    DISUSED("불용품"),
    RESULTS("개찰 결과"),
}

/** 온비드 정부재산(국유·공유·귀속)·불용품·개찰 끝난 공고 */
@Composable
fun ExploreScreen(vm: AppViewModel, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(ExploreTab.GOV) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("온비드 데이터 더보기", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab.ordinal) {
                ExploreTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
            }
            when (tab) {
                ExploreTab.GOV -> GovTab(vm)
                ExploreTab.DISUSED -> DisusedTab(vm)
                ExploreTab.RESULTS -> ResultsTab(vm)
            }
        }
    }
}

@Composable
private fun GovTab(vm: AppViewModel) {
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf(GovPropertyKind.NATIONAL) }
    var sido by rememberSaveable { mutableStateOf(vm.settings.sido.ifBlank { "서울특별시" }) }
    var sigungu by rememberSaveable { mutableStateOf(Regions.splitSigungu(vm.settings.sigungu).firstOrNull().orEmpty()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var statusFilter by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(kind, sido, sigungu) { vm.loadGovProperties(kind, sido, sigungu) }
    val state = vm.govProperties[vm.govKey(kind, sido, sigungu)]
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GovPropertyKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k; statusFilter = "" }, label = { Text(k.label) }) }
                }
                Text(kind.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("지역: ${Regions.label(sido, sigungu)}") }
            }
        }
        item {
            LoadView(state, onRetry = { vm.loadGovProperties(kind, sido, sigungu, force = true) }) { (list, total) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val statuses = list.map { it.status }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                    Text("${total ?: list.size}건 중 ${list.size}건 표시", style = MaterialTheme.typography.labelLarge)
                    if (statuses.size > 1) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = statusFilter.isEmpty(), onClick = { statusFilter = "" }, label = { Text("전체") })
                            statuses.forEach { (name, n) ->
                                FilterChip(selected = statusFilter == name, onClick = { statusFilter = name }, label = { Text("$name $n") })
                            }
                        }
                    }
                }
            }
        }
        val list = (state as? Load.Done<Pair<List<GovProperty>, Int?>>)?.value?.first.orEmpty().filter { statusFilter.isEmpty() || it.status == statusFilter }
        items(list, key = { it.kind.name + it.pnu + it.name + it.address }) { item -> GovRow(item, onMap = { Links.openMap(context, item.address) }, onDoc = { Links.openUrl(context, item.docUrl) }) }
    }
    if (picking) {
        RegionPickerDialog(
            initialSido = sido,
            initialSigungu = listOf(sigungu).filter { it.isNotBlank() },
            onDismiss = { picking = false },
            onConfirm = { newSido, sigungus ->
                picking = false
                sido = newSido.ifBlank { "서울특별시" }
                sigungu = sigungus.firstOrNull().orEmpty()
            },
        )
    }
}

@Composable
private fun GovRow(item: GovProperty, onMap: () -> Unit, onDoc: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.address.isNotBlank() && item.address != item.name) Text(item.address, style = MaterialTheme.typography.bodySmall)
            Text(
                listOfNotNull(
                    item.landUse.ifBlank { null },
                    item.usage.ifBlank { null },
                    item.landAreaSqm?.let { "토지 ${Format.area(it)}" },
                    item.buildingAreaSqm?.let { "건물 ${Format.area(it)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(item.status.ifBlank { null }, item.org.ifBlank { null }, item.appraisal?.let { "감정 ${Format.wonShort(it)}" }).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.address.isNotBlank()) TextButton(onClick = onMap) { Text("지도") }
                if (item.docUrl.isNotBlank()) TextButton(onClick = onDoc) { Text("감정평가서·사진") }
            }
        }
    }
}

@Composable
private fun DisusedTab(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadDisusedGoods() }
    val state = vm.disusedGoods
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(
                "공공기관이 더 쓰지 않기로 한 물품(불용품) 목록입니다. 매각 공고는 온비드 동산 검색에 따로 올라옵니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { LoadView(state, onRetry = { vm.loadDisusedGoods(force = true) }) { (list, total) -> Text("${total ?: list.size}건 중 ${list.size}건 표시", style = MaterialTheme.typography.labelLarge) } }
        val list = (state as? Load.Done<Pair<List<DisusedGoods>, Int?>>)?.value?.first.orEmpty()
        items(list.size) { i ->
            val g = list[i]
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(g.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        g.condition.ifBlank { null },
                        g.quantity.ifBlank { null }?.let { "수량 $it" },
                        g.bookValue?.let { "대장가액 ${Format.won(it)}" },
                        g.decidedAt.takeIf { it.length == 8 }?.let { "불용결정 ${RightsParser.formatYmd(it)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResultsTab(vm: AppViewModel) {
    var type by rememberSaveable { mutableStateOf(ResultType.REAL_ESTATE) }
    var opened by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(type) { vm.loadClosedNotices(type) }
    val state = vm.closedNotices[type.name]
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ResultType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t; opened = "" }, label = { Text(t.label) }) }
                }
                Text("최근 2주 개찰이 끝난 공고입니다. 누르면 물건별 낙찰가·입찰자 수를 봅니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LoadView(state, onRetry = { vm.loadClosedNotices(type, force = true) }) { list -> Text("${list.size}건", style = MaterialTheme.typography.labelLarge) }
            }
        }
        val list = (state as? Load.Done<List<NoticeResult>>)?.value.orEmpty()
        items(list, key = { it.noticeNo + "|" + it.round }) { notice ->
            NoticeResultRow(vm, notice, open = opened == notice.noticeNo, onToggle = {
                opened = if (opened == notice.noticeNo) "" else notice.noticeNo
                if (opened.isNotEmpty()) vm.loadNoticeResults(notice.noticeNo)
            })
        }
    }
}

@Composable
private fun NoticeResultRow(vm: AppViewModel, notice: NoticeResult, open: Boolean, onToggle: () -> Unit) {
    Card(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(notice.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    notice.org.ifBlank { null },
                    notice.assetType.ifBlank { null },
                    notice.round.ifBlank { null }?.let { "${it}회" },
                    notice.openedAt.takeIf { it.length == 12 }?.let { "개찰 ${Format.dateTime(it)}" },
                    notice.state.ifBlank { null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (open) {
                LoadView(vm.noticeResults[notice.noticeNo], onRetry = { vm.loadNoticeResults(notice.noticeNo, force = true) }) { cases ->
                    Column {
                        if (cases.isEmpty()) Text("물건별 결과가 없습니다.", style = MaterialTheme.typography.bodySmall)
                        cases.take(30).forEach { SoldRow(it) }
                    }
                }
            }
        }
    }
}
