@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.jgg113.gongmae.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
fun MapScreen(vm: AppViewModel, onOpenDetail: (String) -> Unit) {
    val context = LocalContext.current
    val controller = rememberMapController()
    val items = vm.visibleResults
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // 위치를 받은 뒤 그 지역을 검색까지 할지 (아니면 내 위치로 이동만)
    var searchAfterLocate by remember { mutableStateOf(false) }
    var fittedIds by remember { mutableStateOf<List<String>?>(null) }
    // 지도 페이지가 알려 온 문제 (타일 서버 실패·스크립트 오류 등)
    var mapProblem by remember { mutableStateOf<String?>(null) }

    fun locate() {
        DeviceLocation.request(context) { position ->
            if (position == null) {
                Toast.makeText(context, "현재 위치를 알 수 없습니다. 위치(GPS)가 켜져 있는지 확인하세요.", Toast.LENGTH_LONG).show()
            } else {
                controller.run("app.showMe(${position.lat}, ${position.lng}, true)")
                if (searchAfterLocate) {
                    searchAfterLocate = false
                    vm.searchAt(position) { region ->
                        val text = if (region == null) "현재 위치의 지역을 알아내지 못했습니다." else "$region 물건을 불러옵니다."
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                    }
                }
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

    // 좌표 없는 물건은 휴대폰 지오코더로 찾는다.
    LaunchedEffect(items) { vm.ensurePositions(items) }

    // 지도가 준비되거나, 결과·필터가 바뀌거나, 좌표가 새로 생기면 다시 그린다. 화면 맞춤은 결과가 바뀔 때만.
    LaunchedEffect(controller.ready, items, vm.geoVersion) {
        if (!controller.ready) return@LaunchedEffect
        val ids = items.map { it.id }
        val fit = fittedIds != ids
        controller.run("app.setItems(${MapPayload.items(items, vm::positionOf)}, $fit)")
        if (fit) fittedIds = ids
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("지도", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = { requestLocation(search = true) }, enabled = vm.searchState !is SearchState.Loading) {
                        Text("내 주변 찾기")
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
            MapView(
                controller = controller,
                modifier = Modifier.fillMaxSize(),
                onEvent = { event ->
                    when (event) {
                        is MapEvent.MarkerClick -> {
                            selectedId = event.id
                        }
                        is MapEvent.Message -> {
                            mapProblem = when {
                                event.code.startsWith("TILES_OK") -> null
                                event.code.startsWith("TILES_FAILED") -> "지도 그림 서버 연결 실패(${event.code.substringAfter(':')}) — 다른 지도로 바꾸는 중"
                                event.code.startsWith("JS_ERROR") || event.code.startsWith("PAGE_ERROR") -> "지도 오류: " + event.code.substringAfter(':')
                                else -> mapProblem
                            }
                        }
                        else -> Unit
                    }
                },
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shadowElevation = 2.dp,
            ) {
                val progress = vm.geocodeProgress
                val label = when {
                    mapProblem != null -> mapProblem.orEmpty()
                    vm.searchState is SearchState.Loading -> "불러오는 중…"
                    vm.results.isEmpty() -> "검색 탭에서 지역을 검색하거나 '내 주변 찾기'를 누르세요"
                    progress != null -> "위치 찾는 중 ${progress.first}/${progress.second}"
                    else -> {
                        val located = items.count { vm.positionOf(it) != null }
                        "${located}/${items.size}건 표시 · 파랑 공매 / 보라 경매"
                    }
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
                FloatingActionButton(onClick = { requestLocation(search = false) }) {
                    Icon(Icons.Filled.LocationOn, contentDescription = "내 위치")
                }
                if (selected != null) {
                    Box {
                        PropertyCard(
                            property = selected,
                            analysis = vm.analyses[selected.id],
                            now = vm.now,
                            photo = vm.courtPhotos(selected.id).firstOrNull(),
                            isFavorite = vm.isFavorite(selected.id),
                            isComparing = vm.isComparing(selected.id),
                            onClick = { onOpenDetail(selected.id) },
                            onToggleFavorite = { vm.toggleFavorite(selected) },
                            onToggleCompare = {
                                if (!vm.toggleCompare(selected)) {
                                    Toast.makeText(context, "최대 ${Comparison.MAX_ITEMS}개까지 비교할 수 있습니다.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onVisible = { vm.loadCourtDetail(selected) },
                        )
                        IconButton(onClick = { selectedId = null }, modifier = Modifier.align(Alignment.TopEnd)) {
                            Icon(Icons.Filled.Close, contentDescription = "닫기", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
