package io.github.jgg113.gyeot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 테두리 없는 둥근 흰 카드. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = color,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** 화면 맨 위 큰 제목 (원 UI 처럼 넉넉하게). */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        trailing()
    }
}

@Composable
fun SectionHeader(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** 작은 둥근 꼬리표: "🙋 나", "📺 TV 의심", "중요" */
@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.surfaceVariant, textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, fontSize = 12.sp, color = textColor, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 동그란 바탕 위 이모지 */
@Composable
fun EmojiBadge(emoji: String, color: Color = MaterialTheme.colorScheme.primaryContainer, size: Int = 42) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size * 0.45).sp)
    }
}

/** 듣는 중 표시 점 (말소리가 들리면 숨 쉬듯 커졌다 작아짐). */
@Composable
fun StatusDot(color: Color, pulsing: Boolean) {
    val t = rememberInfiniteTransition(label = "dot")
    val s by t.animateFloat(1f, if (pulsing) 1.8f else 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(12.dp).scale(s).alpha(0.3f).clip(CircleShape).background(color))
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

/** 오늘 탭의 바로가기 칸 */
@Composable
fun QuickAction(emoji: String, label: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = color, modifier = modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 6.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** 설정 묶음: 머리글을 누르면 펼치고 접는다. */
@Composable
fun SettingGroup(emoji: String, title: String, subtitle: String, startOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    AppCard {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            EmojiBadge(emoji, size = 38)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = TextStyles.caption, maxLines = 2)
            }
            Text(if (open) "⌃" else "⌄", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
fun ToggleRow(title: String, desc: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(desc, style = TextStyles.caption)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange)
    }
}

@Composable
fun Hint(text: String) = Text(text, style = TextStyles.caption)

/** 소리 크기 막대 (-70dB ~ -10dB) */
@Composable
fun LevelBar(db: Int) {
    val v = ((db + 70) / 60f).coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { v },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
        color = if (db < -55) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}
