@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.jgg113.gongmae.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jgg113.gongmae.core.Comparison

@Composable
fun FavoritesScreen(vm: AppViewModel, onOpenDetail: (String) -> Unit) {
    val context = LocalContext.current
    val favorites = vm.favorites.toList()
    Scaffold(
        topBar = { TopAppBar(title = { Text("관심 물건 ${favorites.size}", fontWeight = FontWeight.Bold) }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (favorites.isEmpty()) {
            EmptyState(
                title = "관심 물건이 없습니다",
                message = "검색 결과에서 하트를 누르면 여기에 모아 둘 수 있습니다. 관심 물건은 이 휴대폰에만 저장됩니다.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "note") {
                    Text(
                        "저장할 때의 정보입니다. 최신 입찰 일정과 가격은 다시 검색해서 확인하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(favorites, key = { it.id }) { p ->
                    PropertyCard(
                        property = p,
                        analysis = vm.analyses[p.id],
                        photo = vm.courtPhotos(p.id).firstOrNull(),
                        now = vm.now,
                        isFavorite = true,
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
    }
}
