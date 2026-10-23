@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.jgg113.gongmae.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import io.github.jgg113.gongmae.core.Comparison
import io.github.jgg113.gongmae.core.Format
import io.github.jgg113.gongmae.core.MapPayload
import io.github.jgg113.gongmae.core.NearbyCategory
import io.github.jgg113.gongmae.core.NearbyPlace
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.PropertyDetail
import io.github.jgg113.gongmae.core.RightsParser
import java.time.LocalDateTime

@Composable
fun DetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val property = vm.findProperty(id)
    val now = vm.now
    val favorite = vm.isFavorite(id)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("물건 상세", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
                actions = {
                    if (property != null) {
                        IconButton(onClick = { Links.share(context, property, now) }) {
                            Icon(Icons.Filled.Share, contentDescription = "공유")
                        }
                        IconButton(onClick = { vm.toggleFavorite(property) }) {
                            Icon(
                                if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (favorite) "관심 해제" else "관심 등록",
                                tint = if (favorite) FavoriteRed else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (property == null) {
            EmptyState("물건 정보를 찾을 수 없습니다", "검색 결과가 바뀌었을 수 있습니다. 다시 검색해 주세요.", Modifier.padding(padding))
        } else {
            val detailState = vm.details[id]
            val loadedDetail = (detailState as? DetailState.Loaded)?.detail
            val rounds = loadedDetail?.property?.rounds?.takeIf { it.isNotEmpty() } ?: property.rounds
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (property.thumbnailUrl.isNotBlank()) {
                    item {
                        Thumbnail(
                            property,
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                        )
                    }
                }
                item { Header(property, now) }
                item { PriceCard(property) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { Links.openMap(context, property.displayAddress) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("지도")
                        }
                        OutlinedButton(onClick = { Links.openOnbid(context, property) }, modifier = Modifier.weight(1f)) {
                            Text("온비드")
                        }
                        val comparing = vm.isComparing(property.id)
                        FilledTonalButton(
                            onClick = {
                                if (!vm.toggleCompare(property)) {
                                    Toast.makeText(context, "최대 ${Comparison.MAX_ITEMS}개까지 비교할 수 있습니다.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1.2f),
                        ) { Text(if (comparing) "비교 빼기" else "비교 담기") }
                    }
                }
                item { LocationCard(vm, property, onOpenSettings) }
                item { InfoCard(property, now) }
                if (rounds.isNotEmpty()) {
                    item { RoundsCard(property, rounds, now) }
                }
                item { RightsCard(detailState, onLoad = { vm.loadDetail(id) }) }
                item {
                    Text(
                        "온비드 공개 데이터를 정리한 참고 정보입니다. 입찰 전에 온비드 공고문·감정평가서·등기사항증명서를 꼭 확인하세요. 입찰은 온비드에서 직접 진행합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(property: Property, now: LocalDateTime) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            WindowTag(property.bidWindow(now))
            Tag(property.usageLabel, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            if (property.assetTypeName.isNotBlank()) {
                Tag(property.assetTypeName, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val dDay = Format.dDay(property, now)
            if (dDay.isNotEmpty()) {
                Text(dDay, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            }
        }
        Text(property.name.ifBlank { property.usageLabel }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(property.displayAddress, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (property.roadAddress.isNotBlank()) {
            Text(property.roadAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PriceCard(property: Property) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row {
                PriceItem("감정가", Format.won(property.appraisalPrice), Modifier.weight(1f))
                PriceItem("최저입찰가", Format.won(property.minBidPrice), Modifier.weight(1f), emphasized = true)
            }
            Row {
                PriceItem("감정가 대비", Format.percent(property.appraisalRatio), Modifier.weight(1f), emphasized = true)
                PriceItem("유찰", "${property.failCount}회", Modifier.weight(1f))
                PriceItem("평당 최저가", Format.wonShort(property.pricePerPyeong), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PriceItem(label: String, value: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            value,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun InfoCard(property: Property, now: LocalDateTime) {
    SectionCard("물건 정보") {
        InfoRow("물건관리번호", property.id)
        InfoRow("재산유형", property.assetTypeName)
        InfoRow("처분방식", property.disposalMethod)
        InfoRow("입찰방식", property.bidMethod)
        InfoRow("용도", listOf(property.usageLarge, property.usageMedium, property.usageSmall).filter { it.isNotBlank() }.joinToString(" > "))
        if (property.buildingAreaSqm != null) InfoRow("건물면적", Format.area(property.buildingAreaSqm))
        if (property.landAreaSqm != null) InfoRow("토지면적", Format.area(property.landAreaSqm))
        if (property.buildingAreaSqm == null && property.landAreaSqm == null) InfoRow("면적", Format.area(property.areaSqm))
        InfoRow("현재 회차", property.round.trimStart('0').ifEmpty { "-" }.let { if (it == "-") it else "${it}회차" })
        InfoRow("입찰시작", Format.dateTime(property.bidStart))
        InfoRow("입찰마감", Format.dateTime(property.bidEnd))
        InfoRow("진행상태", listOf(property.bidWindow(now).label, property.statusName).filter { it.isNotBlank() }.distinct().joinToString(" · "))
        if (property.bidType.isNotBlank()) InfoRow("입찰구분", property.bidType)
        InfoRow("공고기관", property.organization)
        if (property.requestOrg.isNotBlank() && property.requestOrg != property.organization) InfoRow("위임기관", property.requestOrg)
        if (property.distributionDeadline.isNotBlank()) InfoRow("배분요구종기", RightsParser.formatYmd(property.distributionDeadline))
        if (property.evictionBy.isNotBlank()) InfoRow("명도책임", property.evictionBy)
        if (property.jointBidAllowed.isNotBlank()) InfoRow("공동입찰", yesNo(property.jointBidAllowed))
        if (property.proxyBidAllowed.isNotBlank()) InfoRow("대리입찰", yesNo(property.proxyBidAllowed))
        if (property.privateContract) InfoRow("수의계약", "가능")
    }
}

private fun yesNo(flag: String): String = when (flag.uppercase()) {
    "Y" -> "가능"
    "N" -> "불가"
    else -> flag
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocationCard(vm: AppViewModel, property: Property, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val geoKey = MapPayload.geoKey(property)
    var position by remember(property.id) { mutableStateOf(vm.cachedPosition(geoKey)) }
    val query = property.geocodeQueries.firstOrNull() ?: property.displayAddress

    SectionCard("위치·주변 정보") {
        Text(property.displayAddress, style = MaterialTheme.typography.bodyMedium)
        if (vm.kakaoKey.isBlank()) {
            MapKeyMissing(onOpenSettings)
            OutlinedButton(onClick = { Links.openKakaoMap(context, property.name, position, query) }) { Text("카카오맵에서 보기") }
        } else {
            val controller = rememberKakaoMapController()
            var category by rememberSaveable(property.id) { mutableStateOf<NearbyCategory?>(null) }
            var places by remember(property.id) { mutableStateOf<List<NearbyPlace>?>(null) }
            var roadviewOpen by remember { mutableStateOf(false) }
            var note by remember { mutableStateOf<String?>(null) }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                KakaoMap(
                    appKey = vm.kakaoKey,
                    controller = controller,
                    modifier = Modifier.matchParentSize(),
                    onEvent = { event ->
                        when (event) {
                            is MapEvent.Located -> {
                                position = event.position
                                category?.let { controller.run("app.nearby('${it.code}')") }
                            }
                            is MapEvent.Geocoded -> vm.rememberPosition(event.key, event.position)
                            is MapEvent.Nearby -> places = NearbyPlace.parse(event.json)
                            is MapEvent.Message -> when (event.code) {
                                "ROADVIEW_OPEN" -> roadviewOpen = true
                                "NO_ROADVIEW" -> Toast.makeText(context, "이 근처에는 로드뷰가 없습니다.", Toast.LENGTH_SHORT).show()
                                "GEOCODE_FAILED" -> note = "주소로 정확한 위치를 찾지 못했습니다."
                                else -> Unit
                            }
                            else -> Unit
                        }
                    },
                )
                if (roadviewOpen) {
                    FilledTonalButton(
                        onClick = {
                            controller.run("app.closeRoadview()")
                            roadviewOpen = false
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                    ) { Text("지도로") }
                }
            }
            LaunchedEffect(controller.ready) {
                if (controller.ready) controller.run("app.showProperty(${MapPayload.item(property, position)})")
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { controller.run("app.openRoadview()") }, enabled = position != null) { Text("로드뷰") }
                OutlinedButton(onClick = { Links.openKakaoMap(context, property.name, position, query) }) { Text("카카오맵 앱") }
            }

            Text("주변 시설 (반경 2km)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NearbyCategory.entries.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        enabled = position != null,
                        onClick = {
                            category = if (category == c) null else c
                            places = null
                            controller.run("app.nearby('${category?.code.orEmpty()}')")
                        },
                        label = { Text(c.label) },
                    )
                }
            }
            val list = places
            when {
                category == null -> Unit
                list == null -> Text("찾는 중…", style = MaterialTheme.typography.bodySmall)
                list.isEmpty() -> Text("반경 2km 안에 ${category?.label} 이(가) 없습니다.", style = MaterialTheme.typography.bodySmall)
                else -> list.take(8).forEach { place ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(place.name, style = MaterialTheme.typography.bodyMedium)
                            if (place.category.isNotBlank()) {
                                Text(place.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(Format.distance(place.distanceM), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundsCard(property: Property, rounds: List<io.github.jgg113.gongmae.core.BidRound>, now: LocalDateTime) {
    val nowKey = now.format(Property.KEY_FORMAT)
    SectionCard("회차별 최저입찰가") {
        Text(
            "유찰되면 다음 회차로 넘어가며 최저입찰가가 낮아집니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rounds.forEachIndexed { index, round ->
            if (index > 0) HorizontalDivider()
            val isCurrent = round.round == property.round && round.bidStart == property.bidStart
            val ended = round.bidEnd.isNotEmpty() && round.bidEnd < nowKey
            val ratio = property.appraisalPrice?.takeIf { it > 0 }?.let { a -> round.minBidPrice?.let { it * 100.0 / a } }
            val color = when {
                isCurrent -> MaterialTheme.colorScheme.primary
                ended -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    round.round.trimStart('0').ifEmpty { "-" } + "회",
                    modifier = Modifier.width(44.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = color,
                )
                Text(
                    Format.period(round.bidStart, round.bidEnd),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Format.wonShort(round.minBidPrice),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = color,
                    )
                    Text(Format.percent(ratio), style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }
    }
}

@Composable
private fun RightsCard(state: DetailState?, onLoad: () -> Unit) {
    SectionCard("권리·임대차 정보") {
        when (state) {
            null -> {
                Text(
                    "등기 권리, 임차인, 점유 관계, 배분요구 내역을 온비드에서 불러옵니다. 공공데이터포털에서 '차세대 온비드 부동산 물건상세 조회서비스'를 활용신청해야 합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onLoad) { Text("불러오기") }
            }
            DetailState.Loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is DetailState.Error -> {
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onLoad) { Text("다시 시도") }
            }
            is DetailState.Loaded -> RightsContent(state.detail)
        }
    }
}

@Composable
private fun RightsContent(detail: PropertyDetail) {
    if (detail.cautions.isEmpty()) {
        Text("자동 점검에서 눈에 띄는 위험 신호는 없었습니다. (참고용)", style = MaterialTheme.typography.bodyMedium)
    } else {
        detail.cautions.forEach { caution ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.padding(10.dp)) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(caution, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
    InfoRow("말소기준권리", RightsParser.formatYmd(detail.baseRightDate) + if (detail.baseRightDate.isEmpty()) "" else " (추정)")
    InfoRow("인수 가능 보증금", if (detail.seniorLeaseDeposit > 0) Format.won(detail.seniorLeaseDeposit) else "없음 (추정)")

    SubTitle("등기 권리 ${detail.registry.size}건")
    if (detail.registry.isEmpty()) EmptyLine()
    detail.registry.forEach {
        EntryLine(it.kind, "${it.holder.ifBlank { "-" }} · ${RightsParser.formatYmd(it.date)}", it.amount?.let(Format::won))
    }

    SubTitle("임대차 ${detail.leases.size}건")
    if (detail.leases.isEmpty()) EmptyLine()
    detail.leases.forEach {
        val rent = it.monthlyRent?.takeIf { m -> m > 0 }?.let { m -> " / 월 ${Format.won(m)}" }.orEmpty()
        EntryLine(
            it.kind.ifBlank { "임차인" },
            "${it.holder.ifBlank { "-" }} · 전입 ${RightsParser.formatYmd(it.moveInDate)} · 확정 ${RightsParser.formatYmd(it.confirmDate)}",
            it.deposit?.let { d -> "보증금 ${Format.won(d)}$rent" },
        )
    }

    SubTitle("점유 관계 ${detail.occupancy.size}건")
    if (detail.occupancy.isEmpty()) EmptyLine()
    detail.occupancy.forEach {
        EntryLine(
            it.relation.ifBlank { "점유" },
            listOf(it.name, it.part, "전입 ${RightsParser.formatYmd(it.moveInDate)}").filter { s -> s.isNotBlank() }.joinToString(" · "),
            it.deposit?.let { d -> "보증금 ${Format.won(d)}" },
        )
    }

    SubTitle("배분요구 ${detail.claims.size}건")
    if (detail.claims.isEmpty()) EmptyLine()
    detail.claims.forEach {
        EntryLine(
            it.kind.ifBlank { "배분요구" },
            listOf(it.name, RightsParser.formatYmd(it.claimDate), it.note).filter { s -> s.isNotBlank() && s != "-" }.joinToString(" · "),
            it.claimAmount?.let(Format::won),
        )
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun EmptyLine() {
    Text("내역 없음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EntryLine(title: String, subtitle: String, amount: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (amount != null) {
            Text(amount, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
