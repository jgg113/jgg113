package io.github.jgg113.gyeot.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.ai.Analysis
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.Sessions
import io.github.jgg113.gyeot.alarm.Notifier
import io.github.jgg113.gyeot.audio.Player
import io.github.jgg113.gyeot.data.Note
import io.github.jgg113.gyeot.data.Store
import io.github.jgg113.gyeot.data.Utterance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 하루의 받아쓴 말 → 대화 묶음 (최근 묶음이 위). */
internal fun daySessions(g: Graph, day: LocalDate): List<Sessions.Session> {
    val notes = g.store.notes().filter { Store.dayOf(it.from) == day || Store.dayOf(it.at) == day }
    return Sessions.build(g.store.utterances(day), notes).reversed()
}

/**
 * 기록 탭: 날짜를 고르면 그날 대화를 묶음별로 보여 준다.
 * 묶음마다 요약이 먼저 나오고, [전문 보기]를 누르면 받아쓴 원문 전체가 펼쳐진다 — 줄을 누르면 그때 소리를 듣는다.
 */
@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val g = remember { Graph.get(ctx) }
    val scope = rememberCoroutineScope()
    val version by g.store.version.collectAsState()
    val today = LocalDate.now(EventRules.SEOUL)
    var dayText by rememberSaveable { mutableStateOf(today.toString()) }
    val day = LocalDate.parse(dayText)
    val days = remember(version) {
        ((0L..6L).map { today.minusDays(it) } + g.store.transcriptDays()).distinct().sortedDescending()
    }
    val sessions by produceState(emptyList<Sessions.Session>(), version, day) {
        value = withContext(Dispatchers.IO) { daySessions(g, day) }
    }
    var editing by remember { mutableStateOf<Utterance?>(null) }
    editing?.let { u -> EditUtterance(u, g) { editing = null } }
    DisposableEffect(Unit) { onDispose { Player.stop() } }
    val keep = g.settings.keepAudioDays

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            ScreenHeader("기록", subtitle = longDay(day)) {
                IconButton(onClick = { scope.launch(Dispatchers.IO) { g.rebuildDay(day) } }) { Icon(Icons.Filled.Refresh, "정리 다시 하기") }
                IconButton(onClick = { share(ctx, day, sessions) }, enabled = sessions.isNotEmpty()) { Icon(Icons.Filled.Share, "이 날 기록 내보내기") }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(days, key = { it.toString() }) { d ->
                    val hasAudio = keep > 0 && !d.isBefore(today.minusDays((keep - 1).toLong()))
                    FilterChip(
                        selected = d == day,
                        onClick = { dayText = d.toString() },
                        label = { Text(shortDay(d, today) + if (hasAudio) " 🎧" else "") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        border = null,
                    )
                }
            }
        }
        item {
            val calls = sessions.count { it.callWith != null }
            val lines = sessions.sumOf { it.lines.size }
            AppCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    if (sessions.isEmpty()) "이 날 들은 대화가 없어요"
                    else "대화 ${sessions.size - calls}번" + (if (calls > 0) " · 통화 ${calls}번" else "") + " · 받아쓴 말 ${lines}줄",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    if (keep > 0) "🎧 녹음은 최근 ${keep}일치를 보관해요. [전문 보기]에서 줄을 누르면 그때 소리를 들을 수 있어요. 길게 누르면 받아쓴 글을 고쳐요."
                    else "🎧 녹음 보관이 꺼져 있어요. 설정 → 녹음 보관에서 켜면 그때 소리를 다시 들을 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        items(sessions, key = { it.key }) { s -> SessionCard(s, g) { editing = it } }
    }
}

private fun shortDay(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "오늘"
    today.minusDays(1) -> "어제"
    else -> d.format(DateTimeFormatter.ofPattern("d일 (E)", Locale.KOREAN))
}

private fun share(ctx: android.content.Context, day: LocalDate, sessions: List<Sessions.Session>) {
    val text = buildString {
        appendLine("클로드비서 기록 · ${longDay(day)}")
        sessions.reversed().forEach { s ->
            appendLine()
            appendLine("■ ${koSpan(s.from, s.to)}" + (s.callWith?.let { " · 📞 $it 님과 통화" } ?: ""))
            s.notes.forEach { n ->
                appendLine("요약: ${n.summary}")
                n.keyPoints.forEach { appendLine("  - $it") }
                n.todos.forEach { appendLine("  - 할 일: ${it.text}") }
            }
            s.lines.forEach { u ->
                val who = when (u.speaker) { "me" -> "나: "; "other" -> "상대: "; else -> "" }
                appendLine("[${clock(u.at)}] $who${u.text}")
            }
        }
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "클로드비서 기록 $day").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "기록 내보내기"))
}

private data class Digest(val summary: String, val keyPoints: List<String>, val todos: List<String>, val eventIds: List<Long>, val important: Boolean)

private fun digestOf(notes: List<Note>): Digest = Digest(
    notes.map { it.summary }.filter { it.isNotBlank() }.joinToString(" "),
    notes.flatMap { it.keyPoints }.distinct(),
    notes.flatMap { it.todos }.map { it.text + if (it.due.isNotBlank()) " (${it.due.replace('T', ' ')})" else "" }.distinct(),
    notes.flatMap { it.eventIds }.distinct(),
    notes.any { it.importance == "high" },
)

private fun digestOf(a: Analysis): Digest = Digest(a.summary, a.keyPoints, a.todos.map { it.text }, emptyList(), a.importance == "high")

/**
 * 대화 묶음 카드: 언제·누구와, 요약·핵심·할 일·일정, [전문 보기]로 원문 전체, [전체 듣기]로 녹음 이어 듣기.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SessionCard(s: Sessions.Session, g: Graph, onEdit: (Utterance) -> Unit) {
    var open by rememberSaveable(s.key) { mutableStateOf(false) }
    val playing by Player.playing.collectAsState()
    // 정리 노트가 아직 없는 묶음(정리 전이거나 중요하지 않다고 본 대화)은 규칙으로 바로 요약한다.
    val d by produceState<Digest?>(if (s.notes.isNotEmpty()) digestOf(s.notes) else null, s.key, s.lines.size, s.notes.size) {
        value = if (s.notes.isNotEmpty()) digestOf(s.notes) else withContext(Dispatchers.Default) { digestOf(g.quickSummary(s.lines)) }
    }
    val audioLines = remember(s.key, s.lines.size) { s.lines.filter { g.audio.file(it.audio) != null } }
    val thisPlaying = playing != null && s.lines.any { it.audio == playing }
    val call = s.callWith != null

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiBadge(
                if (call) "📞" else if (s.tvLikely) "📺" else "💬",
                color = if (call) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(koSpan(s.from, s.to), style = MaterialTheme.typography.titleMedium)
                Text(
                    (if (call) "${s.callWith} 님과 통화" else "곁에서 들은 대화") + " · " + koDuration(s.to - s.from + 30_000),
                    style = TextStyles.caption,
                )
            }
        }
        val tags = buildList {
            if (d?.important == true) add("⭐ 중요")
            if (s.tvLikely && !call) add("📺 TV 소리 같아요")
            if (s.mine > 0) add("🙋 내 말 ${s.mine}")
            if (audioLines.isNotEmpty()) add("🎧 녹음 ${audioLines.size}")
        }
        if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tags.forEach { t ->
                if (t.startsWith("⭐")) Tag(t, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) else Tag(t)
            }
        }

        val digest = d
        val summary = digest?.summary.orEmpty().ifBlank { s.lines.firstOrNull()?.text?.let { "“${it.take(90)}${if (it.length > 90) "…" else ""}”" }.orEmpty() }
        Text(summary, style = MaterialTheme.typography.bodyLarge)
        if (digest != null) {
            val points = if (open) digest.keyPoints else digest.keyPoints.take(3)
            points.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            digest.todos.forEach { Text("☑ $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
            digest.eventIds.mapNotNull { g.store.event(it) }.forEach { e ->
                Row(
                    Modifier.fillMaxWidth().clickable { MainActivity.openEvent.longValue = e.id }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📅 ${e.title} · ${if (e.timeUnknown) "날짜 미정" else Notifier.describe(e)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                    Text("근거 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { open = !open }) { Text(if (open) "전문 접기 ⌃" else "전문 보기 ⌄ (${s.lines.size}줄)") }
            Spacer(Modifier.weight(1f))
            if (audioLines.isNotEmpty()) TextButton(onClick = { if (thisPlaying) Player.stop() else playLines(g, audioLines) }) {
                Text(if (thisPlaying) "■ 멈춤" else "▶ 전체 듣기")
            }
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (audioLines.isNotEmpty()) Hint("줄을 누르면 그때 소리를 들어요 · 길게 누르면 받아쓴 글을 고쳐요")
                else Hint("녹음 보관 기간이 지났거나 녹음을 남기지 않은 대화예요 · 줄을 누르면 받아쓴 글을 고쳐요")
                s.lines.forEach { u -> TranscriptLine(u, g) { onEdit(it) } }
            }
        }
    }
}
