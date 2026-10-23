@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.jgg113.gongmae.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.jgg113.gongmae.core.Comparison
import io.github.jgg113.gongmae.core.MapPayload

private val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

@Composable
fun MapScreen(vm: AppViewModel, onOpenDetail: (String) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val controller = rememberKakaoMapController()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // 위치를 받은 뒤 그 지역을 검색까지 할지 (아니면 내 위치로 이동만)
    var searchAfterLocate by remember { mutableStateOf(false) }

    fun locate() {
        DeviceLocation.request(context) { position ->
            if (position == null) {
                Toast.makeText(context, "현재 위치를 알 수 없습니다. 위치(GPS)가 켜져 있는지 확인하세요.", Toast.LENGTH_LONG).show()
            } else {
                controller.run("app.showMe(${position.lat}, ${position.lng}, true)")
                if (searchAfterLocate) controller.run("app.regionOf(${position.lat}, ${position.lng})")
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            locate()
        } else {
            Toast.makeText(context, "위치 권한이 있어야 내 주변 물건을 찾을 수 있습니다.", Toast.LENGTH_LONG).show()
        }
    }

    fun requestLocation(search: Boolean) {
        searchAfterLocate = search
        val granted = locationPermissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (granted) locate() else permissionLauncher.launch(locationPermissions)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("지도", fontWeight = FontWeight.Bold) },
                actions = {
                    if (vm.kakaoKey.isNotBlank()) {
                        TextButton(onClick = { requestLocation(search = true) }, enabled = vm.searchState !is SearchState.Loading) {
                            Text("내 주변 공매 찾기")
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (vm.kakaoKey.isBlank()) {
                EmptyState(
                    title = "카카오 지도 키가 필요합니다",
                    message = "지도에서 공매 물건을 보려면 설정 탭에 카카오 JavaScript 키를 넣어 주세요. 키 받는 방법도 설정 탭에 있습니다.",
                    action = { TextButton(onClick = onOpenSettings) { Text("설정으로 이동") } },
                )
            } else {
                MapContent(
                    vm = vm,
                    controller = controller,
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                    onOpenDetail = onOpenDetail,
                    onLocate = { requestLocation(search = false) },
                    onRegion = { sido, sigungu ->
                        if (searchAfterLocate) {
                            searchAfterLocate = false
                            val region = vm.searchAround(sido, sigungu)
                            Toast.makeText(context, "$region 공매 물건을 불러옵니다.", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MapContent(
    vm: AppViewModel,
    controller: KakaoMapController,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onOpenDetail: (String) -> Unit,
    onLocate: () -> Unit,
    onRegion: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val items = vm.visibleResults

    KakaoMap(
        appKey = vm.kakaoKey,
        controller = controller,
        modifier = Modifier.fillMaxSize(),
        onEvent = { event ->
            when (event) {
                is MapEvent.MarkerClick -> onSelect(event.id)
                is MapEvent.Geocoded -> vm.rememberPosition(event.key, event.position)
                is MapEvent.Region -> onRegion(event.sido, event.sigungu)
                is MapEvent.Message -> if (event.code == "REGION_FAILED") {
                    Toast.makeText(context, "현재 위치의 지역을 알아내지 못했습니다.", Toast.LENGTH_SHORT).show()
                }
                else -> Unit
            }
        },
    )

    // 지도가 준비되거나 검색 결과·필터가 바뀌면 마커를 다시 그린다.
    LaunchedEffect(controller.ready, items) {
        if (controller.ready) {
            controller.run("app.setItems(${MapPayload.items(items, vm::cachedPosition)}, true)")
        }
    }

    Surface(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(10.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
        val label = when {
            vm.searchState is SearchState.Loading -> "불러오는 중…"
            vm.results.isEmpty() -> "검색 탭에서 지역을 검색하거나 '내 주변 공매 찾기'를 누르세요"
            else -> "${items.size}건 표시 (필터 적용)"
        }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }

    val selected = selectedId?.let { id -> items.firstOrNull { it.id == id } }
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(12.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FloatingActionButton(onClick = onLocate) {
            Icon(Icons.Filled.LocationOn, contentDescription = "내 위치")
        }
        if (selected != null) {
            Box {
                PropertyCard(
                    property = selected,
                    now = vm.now,
                    isFavorite = vm.isFavorite(selected.id),
                    isComparing = vm.isComparing(selected.id),
                    onClick = { onOpenDetail(selected.id) },
                    onToggleFavorite = { vm.toggleFavorite(selected) },
                    onToggleCompare = {
                        if (!vm.toggleCompare(selected)) {
                            Toast.makeText(context, "최대 ${Comparison.MAX_ITEMS}개까지 비교할 수 있습니다.", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                IconButton(onClick = { onSelect(null) }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Filled.Close, contentDescription = "닫기", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** 카카오 키가 없을 때 상세 화면 등에서 보여주는 한 줄 안내. */
@Composable
fun MapKeyMissing(onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "카카오 JavaScript 키를 설정하면 지도·주변 시설·로드뷰를 볼 수 있습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenSettings) { Text("키 설정") }
    }
}
