@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.jgg113.gongmae.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.jgg113.gongmae.core.Analysis
import io.github.jgg113.gongmae.core.CourtClient
import io.github.jgg113.gongmae.core.Format
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.RiskLevel
import io.github.jgg113.gongmae.core.Source
import java.time.LocalDateTime

val FavoriteRed = Color(0xFFE53935)

data class BadgeColors(val container: Color, val content: Color)

fun riskColors(level: RiskLevel): BadgeColors = when (level) {
    RiskLevel.SAFE -> BadgeColors(Color(0xFFDFF3E3), Color(0xFF1B5E20))
    RiskLevel.CAUTION -> BadgeColors(Color(0xFFFFF0D6), Color(0xFF8A5100))
    RiskLevel.DANGER -> BadgeColors(Color(0xFFFDE0DE), Color(0xFFB71C1C))
}

fun sourceColors(source: Source): BadgeColors = when (source) {
    Source.ONBID -> BadgeColors(Color(0xFFDCE6FF), Color(0xFF1F4FA3))
    Source.COURT -> BadgeColors(Color(0xFFF0E4F7), Color(0xFF6A2C8C))
}

/** 온비드 이미지 서버는 브라우저처럼 보이는 요청만 받아 주는 경우가 있어 헤더를 붙인다. */
fun photoRequest(context: Context, model: Any): ImageRequest =
    ImageRequest.Builder(context)
        .data(model)
        .apply {
            if (model is String && model.contains("onbid.co.kr")) {
                setHeader("User-Agent", CourtClient.USER_AGENT)
                setHeader("Referer", "https://www.onbid.co.kr/")
            }
        }
        .crossfade(true)
        .build()

/** 법원경매 사진(JPEG 바이트)을 화면 크기에 맞게 줄여 읽는다. */
fun decodePhoto(bytes: ByteArray, maxSide: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/** 사진 후보를 차례로 시도한다. 하나도 안 되면 용도 이름만 보인다. */
@Composable
fun Thumbnail(property: Property, modifier: Modifier = Modifier, photo: ByteArray? = null) {
    val context = LocalContext.current
    val bitmap = remember(photo) { photo?.let { decodePhoto(it, 400) } }
    val candidates: List<String> = remember(property.id) {
        (listOf(property.thumbnailUrl) + property.photoUrls.take(1)).filter { it.isNotBlank() }
    }
    var index by remember(candidates) { mutableIntStateOf(0) }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            property.category.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val model = candidates.getOrNull(index)
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else if (model != null) {
            AsyncImage(
                model = remember(model) { photoRequest(context, model) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                onError = { index++ },
            )
        }
    }
}

@Composable
fun AnalysisBadges(analysis: Analysis, modifier: Modifier = Modifier, showHeadline: Boolean = true) {
    val risk = riskColors(analysis.riskLevel)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag("위험 ${analysis.riskLevel.label}", risk.container, risk.content)
            Tag("투자매력 ${analysis.attractiveness}", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            Tag("AI 종합 ${analysis.grade}·${analysis.total}", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        }
        if (showHeadline) {
            Text(
                analysis.headline,
                style = MaterialTheme.typography.bodySmall,
                color = if (analysis.riskLevel == RiskLevel.SAFE) MaterialTheme.colorScheme.secondary else risk.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PropertyCard(
    property: Property,
    analysis: Analysis?,
    now: LocalDateTime,
    isFavorite: Boolean,
    isComparing: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleCompare: () -> Unit,
    modifier: Modifier = Modifier,
    photo: ByteArray? = null,
    onVisible: suspend () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(property.id) { onVisible() }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Thumbnail(property, Modifier.size(88.dp), photo)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val source = sourceColors(property.source)
                        Tag(property.source.label, source.container, source.content)
                        WindowTag(property.bidWindow(now))
                        Tag(property.usageLabel, colors.surfaceVariant, colors.onSurfaceVariant)
                        val dDay = Format.dDay(property, now)
                        if (dDay.isNotEmpty()) {
                            Text(dDay, style = MaterialTheme.typography.labelMedium, color = colors.tertiary, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        property.name.ifBlank { property.usageLabel },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (property.isCourt) "${property.organization} ${property.caseNo}" else property.displayAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (analysis != null) {
                Spacer(Modifier.height(8.dp))
                AnalysisBadges(analysis)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "감정가 ${Format.wonShort(property.appraisalPrice)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("최저 ", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            Format.wonShort(property.minBidPrice),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                        )
                        property.appraisalRatio?.let {
                            Text(
                                " (${Format.percent(it)})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.tertiary,
                            )
                        }
                    }
                    Text(
                        listOfNotNull(
                            property.spec?.summary?.ifBlank { null },
                            "유찰 ${property.failCount}회",
                            analysis?.marketRatio?.let { "실거래 시세의 ${Format.percent(it)}" },
                            property.assetTypeName.ifBlank { null }.takeIf { !property.isCourt },
                            property.areaSqm?.let { Format.area(it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFavorite) "관심 해제" else "관심 등록",
                        tint = if (isFavorite) FavoriteRed else colors.onSurfaceVariant,
                    )
                }
                FilterChip(
                    selected = isComparing,
                    onClick = onToggleCompare,
                    label = { Text(if (isComparing) "비교중" else "비교") },
                    leadingIcon = if (isComparing) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
