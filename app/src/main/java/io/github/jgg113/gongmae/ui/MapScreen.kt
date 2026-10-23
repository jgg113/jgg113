@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.jgg113.gongmae.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.jgg113.gongmae.core.Analysis
import io.github.jgg113.gongmae.core.BidWindow
import io.github.jgg113.gongmae.core.Category
import io.github.jgg113.gongmae.core.Comparison
import io.github.jgg113.gongmae.core.GeoMath
import io.github.jgg113.gongmae.core.LatLng
import io.github.jgg113.gongmae.core.MapPayload
import io.github.jgg113.gongmae.core.MovableKind
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.RiskLevel
import io.github.jgg113.gongmae.core.Source

private val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private val mapKinds = listOf(
    "아파트" to setOf(Category.APARTMENT),
    "오피스텔" to setOf(Category.OFFICETEL),
    "빌라" to setOf(Category.VILLA),
    "주택" to setOf(Category.HOUSE),
    "상가" to setOf(Category.COMMERCIAL),
    "토지" to setOf(Category.LAND, Category.FOREST),
    "공장" to setOf(Category.FACTORY),
    "자동차" to setOf(Category.VEHICLE),
    "동산" to setOf(Category.MOVABLE),
)

/** 지도에 보여 줄 층 (미스고처럼 켜고 끈다) */
private enum class MapLayer(val label: String) {
    ONBID("공매"),
    COURT("경매"),
    MOVABLE("차량·동산"),
    UPCOMING("입찰예정만"),
}

private enum class MeasureMode(val label: String) {
    OFF("재기"),
    DISTANCE("거리"),
    AREA("면적"),
}

/** 지도 마커 정보: 첫 줄 가격, 둘째 줄 종류·평수·유찰. 공매 파랑, 경매 보라, 차량·동산 초록. */
fun mapMarker(p: Property, position: LatLng, analysis: Analysis?): MapMarker = MapMarker(
    id = p.id,
    position = position,
    title = MapPayload.priceLabel(p),
    subtitle = MapPayload.detailLabel(p),
    color = when {
        p.category.isMovable -> 0xFF2E7D32.toInt()
        p.source == Source.COURT -> 0xFF6A2C8C.toInt()
        else -> 0xFF1F4FA3.toInt()
    },
    riskColor = analysis?.riskLevel?.let { riskColors(it).content.toArgb() },
)

@Composable
fun MapScreen(vm: AppViewModel, onOpenDetail: (String) -> Unit) {
    val context = LocalContext.current
    val mapState = rememberNativeMapState()
    var layers by rememberSaveable { mutableStateOf(setOf(MapLayer.ONBID, MapLayer.COURT, MapLayer.MOVABLE)) }
    val visible = vm.visibleResults
    val now = vm.now
    val items = remember(visible, layers, now) {
        visible.filter { p ->
            val layerOk = when {
                p.category.isMovable -> MapLayer.MOVABLE in layers
                p.isCourt -> MapLayer.COURT in layers
                else -> MapLayer.ONBID in layers
            }
            layerOk && (MapLayer.UPCOMING !in layers || p.bidWindow(now) == BidWindow.UPCOMING)
        }
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var style by rememberSaveable { mutableStateOf(MapStyle.STREET) }
    var cadastral by rememberSaveable { mutableStateOf(false) }
    var measure by rememberSaveable { mutableStateOf(MeasureMode.OFF) }
    var measurePoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var me by remember { mutableStateOf<LatLng?>(null) }
    // 위치를 받은 뒤 그 지역을 검색까지 할지 (아니면 내 위치로 이동만)
    var searchAfterLocate by remember { mutableStateOf(false) }
    var fittedIds by remember { mutableStateOf<List<String>?>(null) }
    val vworldKey = vm.vworldKey

    fun locate() {
        DeviceLocation.request(context) { position ->
            if (position == null) {
                Toast.makeText(context, "현재 위치를 알 수 없습니다. 위치(GPS)가 켜져 있는지 확인하세요.", Toast.LENGTH_LONG).show()
            } else {
                me = position
                mapState.moveTo(position, 14.5)
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

    // 좌표 없는 물건은 동 중심을 먼저 찾아 바로 보여 주고, 그다음 지번 위치를 찾는다.
    LaunchedEffect(items) { vm.ensurePositions(items) }

    val markers = remember(items, vm.geoVersion, vm.analyses) {
        items.mapNotNull { p -> vm.positionOf(p)?.let { mapMarker(p, it, vm.analyses[p.id]) } }
    }
    // 결과가 바뀌면 화면을 물건들에 맞춘다 (좌표를 새로 찾은 것만으로는 다시 맞추지 않는다).
    LaunchedEffect(mapState.ready, markers.isNotEmpty(), items) {
        if (!mapState.ready || markers.isEmpty()) return@LaunchedEffect
        val ids = items.map { it.id }
        if (fittedIds != ids) {
            mapState.fit(markers.map { it.position })
            fittedIds = ids
        }
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
            NativeMap(
                markers = markers,
                state = mapState,
                style = style,
                selectedId = selectedId,
                myLocation = me,
                vworldKey = vworldKey,
                cadastral = cadastral,
                measurePoints = measurePoints,
                measureArea = measure == MeasureMode.AREA,
                onMapTap = if (measure == MeasureMode.OFF) null else { point -> measurePoints = measurePoints + point },
                modifier = Modifier.fillMaxSize(),
                onSelect = { selectedId = it },
            )

            Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
                KindChips(vm)
                LayerChips(layers) { layers = it }
                MapStatus(vm, markers.size, items.size)
            }

            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                MapStyle.entries.filter { !it.needsVworldKey || vworldKey.isNotBlank() }.forEach { s ->
                    MapToggle(s.label, style == s) { style = s }
                }
                if (vworldKey.isNotBlank()) {
                    MapToggle("지적", cadastral) {
                        cadastral = !cadastral
                        if (cadastral && mapState.zoom < 15.5) {
                            Toast.makeText(context, "지적도는 동네 수준으로 크게 확대했을 때 보입니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                MapToggle(if (measure == MeasureMode.OFF) "재기" else measure.label, measure != MeasureMode.OFF) {
                    measure = when (measure) {
                        MeasureMode.OFF -> MeasureMode.DISTANCE
                        MeasureMode.DISTANCE -> MeasureMode.AREA
                        MeasureMode.AREA -> MeasureMode.OFF
                    }
                    measurePoints = emptyList()
                }
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
                if (measure != MeasureMode.OFF) {
                    MeasurePanel(
                        mode = measure,
                        points = measurePoints,
                        onUndo = { measurePoints = measurePoints.dropLast(1) },
                        onClear = { measurePoints = emptyList() },
                        onClose = {
                            measure = MeasureMode.OFF
                            measurePoints = emptyList()
                        },
                    )
                }
                FloatingActionButton(onClick = { requestLocation(search = false) }) {
                    Icon(Icons.Filled.LocationOn, contentDescription = "내 위치")
                }
                if (selected != null && measure == MeasureMode.OFF) {
                    val position = vm.positionOf(selected)
                    if (position != null) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = { Links.openStreetView(context, position) }) { Text("거리뷰") }
                                TextButton(onClick = { Links.openRoadview(context, position) }) { Text("로드뷰") }
                                TextButton(onClick = { Links.openNaverMap(context, selected.name, position, selected.displayAddress) }) { Text("네이버지도") }
                            }
                        }
                    }
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

@Composable
private fun MapToggle(label: String, on: Boolean, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

/** 지금 무엇을 하는지 한 줄로 (불러오는 중이면 진행 정도와 지금까지 표시한 수) */
@Composable
private fun MapStatus(vm: AppViewModel, shown: Int, total: Int) {
    val state = vm.searchState
    val progress = vm.geocodeProgress
    val movable = vm.movableState
    Surface(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            val label = when {
                state is SearchState.Loading -> "${state.label} 불러오는 중 (${state.done}/${state.total}) · 지금까지 ${shown}건 표시"
                movable is MovableState.Loading -> "차량·동산 ${movable.label} · ${shown}건 표시"
                vm.allResults.isEmpty() && state is SearchState.Error -> "불러오지 못했습니다: ${state.message.lineSequence().first()}"
                vm.allResults.isEmpty() -> "검색 탭이나 홈에서 지역을 검색하거나 '내 주변 찾기'를 누르세요"
                progress != null -> "위치 찾는 중 ${progress.first}/${progress.second} · ${shown}/${total}건 표시"
                total == 0 -> "조건에 맞는 물건이 없습니다 (검색 조건·위 칩을 확인하세요)"
                shown == 0 -> "${total}건의 위치를 찾지 못했습니다. 인터넷 연결을 확인하세요"
                else -> "${shown}/${total}건 표시 · 파랑 공매 · 보라 경매 · 초록 차량·동산"
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (state is SearchState.Loading || progress != null) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LayerChips(layers: Set<MapLayer>, onChange: (Set<MapLayer>) -> Unit) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MapLayer.entries.forEach { layer ->
            FilterChip(
                selected = layer in layers,
                onClick = { onChange(layers.toggle(layer)) },
                label = { Text(layer.label) },
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    }
}

@Composable
private fun MeasurePanel(mode: MeasureMode, points: List<LatLng>, onUndo: () -> Unit, onClear: () -> Unit, onClose: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            val result = when {
                points.size < 2 -> "지도를 눌러 점을 찍으세요 (${if (mode == MeasureMode.AREA) "3점 이상" else "2점 이상"})"
                mode == MeasureMode.AREA && points.size < 3 -> "한 점 더 찍으면 면적이 나옵니다 · ${GeoMath.formatLength(GeoMath.pathLength(points))}"
                mode == MeasureMode.AREA -> "면적 ${GeoMath.formatArea(GeoMath.area(points))} · 둘레 ${GeoMath.formatLength(GeoMath.pathLength(points + points.first()))}"
                else -> "거리 ${GeoMath.formatLength(GeoMath.pathLength(points))}"
            }
            Text("${mode.label} 재기 (지도를 누르면 점이 찍힙니다)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(result, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = onUndo, enabled = points.isNotEmpty()) { Text("되돌리기") }
                TextButton(onClick = onClear, enabled = points.isNotEmpty()) { Text("지우기") }
                TextButton(onClick = onClose) { Text("끝내기") }
            }
        }
    }
}

/** 지도 위 종류 칩 (모두의경매처럼 한 번 눌러 거르기) */
@Composable
private fun KindChips(vm: AppViewModel) {
    val filter = vm.filter
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = filter.maxRisk == RiskLevel.SAFE,
            onClick = { vm.updateFilter(filter.copy(maxRisk = if (filter.maxRisk == RiskLevel.SAFE) null else RiskLevel.SAFE)) },
            label = { Text("안전만") },
            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        FilterChip(
            selected = filter.excludeAllSpecial,
            onClick = { vm.updateFilter(filter.copy(excludeAllSpecial = !filter.excludeAllSpecial)) },
            label = { Text("특수 제외") },
            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        mapKinds.forEach { (label, set) ->
            val on = set.all { it in filter.categories }
            FilterChip(
                selected = on,
                onClick = {
                    vm.updateFilter(filter.copy(categories = if (on) filter.categories - set else filter.categories + set))
                    if (!on && Category.VEHICLE in set) vm.loadMovables(setOf(MovableKind.VEHICLE))
                    if (!on && Category.MOVABLE in set) vm.loadMovables(setOf(MovableKind.GOODS))
                },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    }
}
