@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.jgg113.gongmae.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import io.github.jgg113.gongmae.core.Analysis
import io.github.jgg113.gongmae.core.Comparison
import io.github.jgg113.gongmae.core.CourtDetail
import io.github.jgg113.gongmae.core.Factor
import io.github.jgg113.gongmae.core.Format
import io.github.jgg113.gongmae.core.MapPayload
import io.github.jgg113.gongmae.core.NearbyCategory
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.PropertyDetail
import io.github.jgg113.gongmae.core.RightsParser
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime

@Composable
fun DetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val property = vm.findProperty(id)
    val now = vm.now
    val favorite = vm.isFavorite(id)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (property?.isCourt == true) "경매 물건 상세" else "공매 물건 상세", fontWeight = FontWeight.Bold) },
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
            LaunchedEffect(property.id) {
                vm.loadCourtDetail(property)
                vm.ensurePositions(listOf(property))
            }
            val detailState = vm.details[id]
            val courtState = vm.courtDetails[id]
            val courtDetail = (courtState as? CourtDetailState.Loaded)?.detail
            val loadedDetail = (detailState as? DetailState.Loaded)?.detail
            val rounds = courtDetail?.rounds?.takeIf { it.isNotEmpty() }
                ?: loadedDetail?.property?.rounds?.takeIf { it.isNotEmpty() }
                ?: property.rounds
            val analysis = vm.analyses[property.id]
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Gallery(property, vm.courtPhotos(property.id), courtState) }
                item { Header(property, now) }
                if (analysis != null) item { AnalysisCard(property, analysis, detailState, onLoadRights = { vm.loadDetail(id) }) }
                item { PriceCard(property) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { if (property.isCourt) Links.openCourt(context, property) else Links.openOnbid(context, property) },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (property.isCourt) "법원경매정보" else "온비드 원문") }
                        val comparing = vm.isComparing(property.id)
                        FilledTonalButton(
                            onClick = {
                                if (!vm.toggleCompare(property)) {
                                    Toast.makeText(context, "최대 ${Comparison.MAX_ITEMS}개까지 비교할 수 있습니다.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (comparing) "비교 빼기" else "비교 담기") }
                    }
                }
                if (property.isCourt) item { CourtNoticeCard(property, courtDetail) }
                item { LocationCard(vm, property) }
                item { InfoCard(property, now) }
                if (rounds.isNotEmpty()) {
                    item { RoundsCard(property, rounds, now) }
                }
                if (!property.isCourt) item { RightsCard(detailState, onLoad = { vm.loadDetail(id) }) }
                item {
                    Text(
                        if (property.isCourt) {
                            "법원경매정보 공개 화면을 정리한 참고 정보입니다. 입찰 전에 매각물건명세서·현황조사서·감정평가서·등기사항증명서를 꼭 확인하세요. AI 분석은 공개 정보를 정해진 기준으로 점수화한 참고 자료입니다."
                        } else {
                            "온비드 공개 데이터를 정리한 참고 정보입니다. 입찰 전에 온비드 공고문·감정평가서·등기사항증명서를 꼭 확인하세요. AI 분석은 공개 정보를 정해진 기준으로 점수화한 참고 자료입니다."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 사진 여러 장을 옆으로 넘겨 본다. 누르면 크게 본다. */
@Composable
private fun Gallery(property: Property, courtPhotos: List<ByteArray>, courtState: CourtDetailState?) {
    val context = LocalContext.current
    var opened by remember { mutableStateOf<Any?>(null) }
    val urls = remember(property.id) { (listOf(property.thumbnailUrl).filter { it.isNotBlank() } + property.photoUrls).distinct() }
    var failed by remember(property.id) { mutableStateOf(setOf<String>()) }
    val visibleUrls = urls.filterNot { it in failed }

    when {
        courtPhotos.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(courtPhotos.size) { index ->
                val bitmap = remember(courtPhotos[index]) { decodePhoto(courtPhotos[index], 900) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "사진 ${index + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 300.dp, height = 210.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { opened = courtPhotos[index] },
                    )
                }
            }
        }
        property.isCourt && courtState is CourtDetailState.Loading -> Box(
            Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Text("사진 불러오는 중…", style = MaterialTheme.typography.bodyMedium) }
        visibleUrls.isNotEmpty() -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleUrls, key = { it }) { url ->
                AsyncImage(
                    model = remember(url) { photoRequest(context, url) },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { failed = failed + url },
                    modifier = Modifier
                        .size(width = 300.dp, height = 210.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { opened = url },
                )
            }
        }
        else -> Thumbnail(
            property,
            Modifier
                .fillMaxWidth()
                .height(160.dp),
        )
    }

    val photo = opened
    if (photo != null) {
        Dialog(onDismissRequest = { opened = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { opened = null },
                contentAlignment = Alignment.Center,
            ) {
                if (photo is ByteArray) {
                    val bitmap = remember(photo) { decodePhoto(photo, 2000) }
                    if (bitmap != null) Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                } else {
                    AsyncImage(
                        model = remember(photo) { photoRequest(context, photo) },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "화면을 누르면 닫힙니다",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(property: Property, now: LocalDateTime) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val source = sourceColors(property.source)
            Tag(property.source.label, source.container, source.content)
            WindowTag(property.bidWindow(now))
            Tag(property.usageLabel, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            if (property.assetTypeName.isNotBlank() && !property.isCourt) {
                Tag(property.assetTypeName, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val dDay = Format.dDay(property, now)
            if (dDay.isNotEmpty()) {
                Text(dDay, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            }
        }
        Text(property.name.ifBlank { property.usageLabel }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (property.isCourt) {
            Text("${property.organization} · ${property.caseNo}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(property.displayAddress, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (property.roadAddress.isNotBlank()) {
            Text(property.roadAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** AI 분석 리포트: 위험도·투자매력·종합점수와 근거. */
@Composable
private fun AnalysisCard(property: Property, analysis: Analysis, rights: DetailState?, onLoadRights: () -> Unit) {
    val risk = riskColors(analysis.riskLevel)
    SectionCard("AI 분석 리포트") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(analysis.grade, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${analysis.total}점", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ScoreBar("위험도", analysis.riskScore, "${analysis.riskLevel.label} ${analysis.riskScore}", risk.content)
                ScoreBar("투자매력", analysis.attractiveness, "${analysis.attractiveness}점", MaterialTheme.colorScheme.secondary)
            }
        }
        Text(analysis.summary, style = MaterialTheme.typography.bodyMedium)
        FactorList("위험 요인", analysis.risks, risk.content)
        FactorList("안전 신호", analysis.factors.filter { it.isRisk && it.points < 0 }, MaterialTheme.colorScheme.secondary)
        FactorList("투자 장점", analysis.merits, MaterialTheme.colorScheme.primary)
        FactorList("투자 단점", analysis.factors.filter { !it.isRisk && it.points < 0 }, MaterialTheme.colorScheme.error)
        if (!property.isCourt && !analysis.rightsChecked) {
            when (rights) {
                DetailState.Loading -> Text("권리정보 불러오는 중…", style = MaterialTheme.typography.bodySmall)
                is DetailState.Error -> Text(rights.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> OutlinedButton(onClick = onLoadRights) { Text("권리·임대차 정보로 정밀 분석") }
            }
        }
        Text(
            "공개된 가격·용도·비고·권리 요약을 경매 실무 기준으로 점수화한 자동 분석입니다. 실제 권리관계와 다를 수 있으니 참고만 하세요.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScoreBar(label: String, value: Int, text: String, color: Color) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { value / 100f },
            color = color,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun FactorList(title: String, factors: List<Factor>, color: Color) {
    if (factors.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
    factors.forEach { f ->
        Column(Modifier.padding(start = 4.dp)) {
            Row {
                Text("• " + f.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (f.points > 0) "+${f.points}" else "${f.points}", style = MaterialTheme.typography.labelMedium, color = color)
            }
            Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 법원 공고의 비고·지분·매각 정보 */
@Composable
private fun CourtNoticeCard(property: Property, detail: CourtDetail?) {
    SectionCard("법원 공고 내용") {
        InfoRow("사건번호", property.caseNo)
        InfoRow("법원·담당", property.organization)
        if (property.contact.isNotBlank()) InfoRow("전화", property.contact)
        InfoRow("매각기일", Format.dateTime(property.bidStart))
        if (property.salePlace.isNotBlank()) InfoRow("매각장소", property.salePlace)
        if (property.reauctionDate.isNotBlank()) InfoRow("재매각명령", RightsParser.formatYmd(property.reauctionDate))
        InfoRow("조회·관심", "조회 ${property.viewCount} · 관심 ${property.interestCount}")
        Text("비고 (특이사항)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(property.notes.ifBlank { "적힌 특이사항이 없습니다." }, style = MaterialTheme.typography.bodyMedium)
        if (property.shareInfo.isNotBlank()) {
            Text("지분 내용", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(property.shareInfo, style = MaterialTheme.typography.bodyMedium)
        }
        detail?.objects?.takeIf { it.isNotEmpty() }?.let { objects ->
            Text("매각 대상", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            objects.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
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
        if (property.isCourt) {
            InfoRow("사건번호", property.caseNo)
        } else {
            InfoRow("물건관리번호", property.id)
            InfoRow("재산유형", property.assetTypeName)
            InfoRow("처분방식", property.disposalMethod)
        }
        InfoRow("입찰방식", property.bidMethod)
        InfoRow("용도", listOf(property.usageLarge, property.usageMedium, property.usageSmall).filter { it.isNotBlank() }.joinToString(" > "))
        if (property.buildingAreaSqm != null) InfoRow("건물면적", Format.area(property.buildingAreaSqm))
        if (property.landAreaSqm != null) InfoRow("토지면적", Format.area(property.landAreaSqm))
        if (property.buildingAreaSqm == null && property.landAreaSqm == null) InfoRow("면적", Format.area(property.areaSqm))
        if (!property.isCourt) {
            InfoRow("현재 회차", property.round.trimStart('0').ifEmpty { "-" }.let { if (it == "-") it else "${it}회차" })
            InfoRow("입찰시작", Format.dateTime(property.bidStart))
            InfoRow("입찰마감", Format.dateTime(property.bidEnd))
        }
        InfoRow("진행상태", listOf(property.bidWindow(now).label, property.statusName).filter { it.isNotBlank() }.distinct().joinToString(" · "))
        if (property.bidType.isNotBlank()) InfoRow("입찰구분", property.bidType)
        if (!property.isCourt) InfoRow("공고기관", property.organization)
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

/** 위치 지도와 주변 시설 (OpenStreetMap, 키 필요 없음) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocationCard(vm: AppViewModel, property: Property) {
    val context = LocalContext.current
    val position = remember(vm.geoVersion, property.id) { vm.positionOf(property) }
    val query = property.geocodeQueries.firstOrNull() ?: property.displayAddress
    val controller = rememberMapController()
    var category by rememberSaveable(property.id) { mutableStateOf<NearbyCategory?>(null) }
    val nearbyState = category?.let { vm.nearby[vm.nearbyKey(property, it)] }
    val places = (nearbyState as? NearbyState.Loaded)?.places

    SectionCard("위치·주변 정보") {
        Text(property.displayAddress, style = MaterialTheme.typography.bodyMedium)
        if (position == null) {
            Text(
                if (vm.geocodeProgress != null) "위치를 찾는 중…" else "주소로 위치를 찾지 못했습니다. 아래 지도 앱으로 확인하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                MapView(controller = controller, modifier = Modifier.matchParentSize(), onEvent = {})
            }
            LaunchedEffect(controller.ready, position) {
                if (controller.ready) controller.run("app.showProperty(${MapPayload.item(property, position)})")
            }
            LaunchedEffect(controller.ready, places) {
                if (controller.ready && places != null) {
                    val json = places.take(15).joinToString(",", "[", "]") { p ->
                        "{\"name\":${JsonPrimitive(p.name)},\"lat\":${p.lat},\"lng\":${p.lng}}"
                    }
                    controller.run("app.showPois($json)")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { position?.let { Links.openRoadview(context, it) } }, enabled = position != null) { Text("로드뷰") }
            OutlinedButton(onClick = { Links.openKakaoMap(context, property.name, position, query) }) { Text("카카오맵") }
            OutlinedButton(onClick = { Links.openNaverMap(context, property.name, position, query) }) { Text("네이버지도") }
        }

        Text("주변 시설 (반경 1.5km)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NearbyCategory.entries.forEach { c ->
                FilterChip(
                    selected = category == c,
                    enabled = position != null,
                    onClick = {
                        category = if (category == c) null else c
                        val center = position
                        val selected = category
                        if (selected != null && center != null) vm.loadNearby(property, center, selected) else controller.run("app.showPois([])")
                    },
                    label = { Text(c.label) },
                )
            }
        }
        val selected = category
        when {
            selected == null -> Unit
            nearbyState is NearbyState.Loading || nearbyState == null -> Text("찾는 중…", style = MaterialTheme.typography.bodySmall)
            nearbyState is NearbyState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nearbyState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { position?.let { vm.retryNearby(property, it, selected) } }) { Text("다시") }
            }
            places.isNullOrEmpty() -> Text("반경 1.5km 안에 ${selected.label} 정보가 없습니다.", style = MaterialTheme.typography.bodySmall)
            else -> places.orEmpty().take(8).forEach { place ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(place.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(Format.distance(place.distanceM), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        Text(
            "지도·주변 시설: © OpenStreetMap 기여자. 일부 시설이 빠져 있을 수 있습니다.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
