@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.jgg113.gongmae.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import io.github.jgg113.gongmae.core.Comparison
import io.github.jgg113.gongmae.core.Property

private val LabelWidth = 88.dp

@Composable
fun CompareScreen(vm: AppViewModel, onOpenDetail: (String) -> Unit, onOpenSearch: () -> Unit) {
    val items = vm.compareItems.toList()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("물건 비교 ${items.size}/${Comparison.MAX_ITEMS}", fontWeight = FontWeight.Bold) },
                actions = {
                    if (items.isNotEmpty()) TextButton(onClick = vm::clearCompare) { Text("비우기") }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (items.isEmpty()) {
            EmptyState(
                title = "비교할 물건을 담아 주세요",
                message = "검색 결과나 상세 화면에서 [비교]를 누르면 최대 ${Comparison.MAX_ITEMS}개까지 나란히 비교할 수 있습니다.",
                modifier = Modifier.padding(padding),
                action = { TextButton(onClick = onOpenSearch) { Text("검색하러 가기") } },
            )
        } else {
            val now = vm.now
            val analyses = vm.analyses
            val rows = remember(items, now, analyses) { Comparison.rows(items, now, analyses) }
            val horizontal = rememberScrollState()
            BoxWithConstraints(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                val cellWidth: Dp = max(136.dp, (maxWidth - LabelWidth - 16.dp) / items.size)
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Row {
                        Text(
                            "",
                            modifier = Modifier.width(LabelWidth),
                        )
                        Row(Modifier.horizontalScroll(horizontal)) {
                            items.forEach { p ->
                                CompareHeader(
                                    property = p,
                                    width = cellWidth,
                                    onOpen = { onOpenDetail(p.id) },
                                    onRemove = { vm.toggleCompare(p) },
                                )
                            }
                        }
                    }
                    rows.forEach { row ->
                        HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.label,
                                modifier = Modifier
                                    .width(LabelWidth)
                                    .padding(horizontal = 6.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(Modifier.horizontalScroll(horizontal)) {
                                row.values.forEachIndexed { index, value ->
                                    val best = index in row.best
                                    Text(
                                        (if (best) "★ " else "") + value,
                                        modifier = Modifier
                                            .width(cellWidth)
                                            .padding(horizontal = 6.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (best) FontWeight.Bold else FontWeight.Normal,
                                        color = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text(
                        "★ 는 담은 물건 중 가장 유리한 값입니다. 최저입찰가·감정가 대비·평당가는 낮을수록, 유찰 횟수는 많을수록 유리하게 봅니다. 옆으로 밀어 다른 물건을 볼 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, start = 6.dp, end = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CompareHeader(property: Property, width: Dp, onOpen: () -> Unit, onRemove: () -> Unit) {
    Column(
        Modifier
            .width(width)
            .padding(4.dp),
    ) {
        Thumbnail(
            property,
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clickable(onClick = onOpen),
        )
        Row(verticalAlignment = Alignment.Top) {
            Text(
                property.name.ifBlank { property.usageLabel },
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 6.dp)
                    .clickable(onClick = onOpen),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "비교에서 빼기", modifier = Modifier.size(18.dp))
            }
        }
    }
}
