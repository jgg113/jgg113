package io.github.jgg113.gyeot.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.alarm.Notifier
import io.github.jgg113.gyeot.audio.Player
import io.github.jgg113.gyeot.data.SavedEvent
import io.github.jgg113.gyeot.data.Utterance
import io.github.jgg113.gyeot.stt.Corrections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/** 받아쓴 줄 한 줄: 누르면 그때 소리를 듣고(녹음이 남아 있으면), 길게 누르면 고친다. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptLine(u: Utterance, g: Graph, highlight: Boolean = false, onEdit: (Utterance) -> Unit) {
    val playing by Player.playing.collectAsState()
    val file = remember(u.audio) { g.audio.file(u.audio) }
    val now = file != null && playing == u.audio
    val bg = when {
        now -> MaterialTheme.colorScheme.primaryContainer
        highlight -> MaterialTheme.colorScheme.secondaryContainer
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg)
            .combinedClickable(
                onClick = { if (file == null) onEdit(u) else if (now) Player.stop() else Player.play(listOf(u.audio to file)) },
                onLongClick = { onEdit(u) },
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(clock(u.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            val who = when (u.speaker) {
                "me" -> "🙋 나"
                "other" -> if (u.callWith != null) "👤 ${u.callWith}" else "👤 상대"
                else -> null
            }
            if (who != null || u.media) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 2.dp)) {
                if (who != null) Tag(
                    who,
                    color = if (u.speaker == "me") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    textColor = if (u.speaker == "me") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (u.media) Tag("📺 TV?")
            }
            Text(
                u.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (file != null) Text(
            if (now) "■" else "▶",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 1.dp),
        )
    }
}

/** 여러 줄을 차례로 이어 듣기. 녹음이 하나도 없으면 false. */
fun playLines(g: Graph, lines: List<Utterance>): Boolean {
    val items = lines.mapNotNull { u -> g.audio.file(u.audio)?.let { u.audio to it } }
    if (items.isEmpty()) return false
    Player.play(items)
    return true
}

@Composable
fun EventCard(e: SavedEvent, g: Graph) {
    val scope = rememberCoroutineScope()
    AppCard(onClick = { MainActivity.openEvent.longValue = e.id }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiBadge(if (e.calendarEventId != null) "📅" else "🔔", color = MaterialTheme.colorScheme.secondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.titleMedium)
                Text(Notifier.describe(e) + if (e.location.isNotBlank()) " · ${e.location}" else "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (e.evidence.isNotBlank()) Text("“${e.evidence}”", style = TextStyles.caption, maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(whyLine(e), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch(Dispatchers.IO) { g.events.cancel(e.id) } }) { Text("취소") }
        }
    }
}

/** "추가할까요?" 카드: 캘린더 / 알림만 / 날짜·시각 바꾸기 / 아니요. */
@Composable
fun SuggestionCard(e: SavedEvent, g: Graph, activity: MainActivity) {
    val scope = rememberCoroutineScope()
    fun act(block: () -> Unit) { scope.launch(Dispatchers.IO) { block() } }
    AppCard(color = MaterialTheme.colorScheme.primaryContainer, onClick = { MainActivity.openEvent.longValue = e.id }) {
        Text("수빈이가 여쭤봐요", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(e.title, style = MaterialTheme.typography.titleMedium)
        Text(
            if (e.timeUnknown) "언제인지 못 들었어요 — [날짜 정하기]로 정해 주세요" else Notifier.describe(e) + if (e.location.isNotBlank()) " · ${e.location}" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (e.evidence.isNotBlank()) Text("들은 말: “${e.evidence}”", style = TextStyles.caption, maxLines = 3)
        if (e.mediaLikely) Tag("📺 TV·영상 소리일 수 있어요", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!e.timeUnknown) {
                Button(onClick = { act { g.events.addToCalendar(e.id) } }) { Text("캘린더에") }
                OutlinedButton(onClick = { act { g.events.remindOnly(e.id) } }) { Text("알림만") }
            }
            OutlinedButton(onClick = {
                activity.pickDateTime(e.startMillis, e.allDay && !e.timeUnknown) { start, allDay ->
                    act { g.events.reschedule(e.id, start, allDay, toCalendar = true) }
                }
            }) { Text(if (e.timeUnknown) "날짜 정하기" else "바꾸기") }
            TextButton(onClick = { act { g.events.dismiss(e.id) } }) { Text("아니요") }
        }
    }
}

/** 카드에 한 줄로: 어떻게 들어갔는지. */
internal fun whyLine(e: SavedEvent): String = when (e.addedBy) {
    "approved" -> "물어본 뒤 승인하셨어요 · 근거 보기"
    "voice" -> "통화에서 말로 부탁하셨어요 · 근거 보기"
    "edited" -> "날짜를 고쳐 넣으셨어요 · 근거 보기"
    "auto" -> "확실해 보여 바로 넣었어요 (${(e.confidence * 100).toInt()}%) · 근거 보기"
    else -> "대화에서 찾았어요 · 근거 보기"
}

/**
 * 받아쓴 한 줄: 글 고치기(바뀐 낱말은 "틀린말=바른말" 규칙으로 저장해 다음부터 자동으로 고친다),
 * 누가 말했는지 고르기(내 말이면 그때 녹음으로 내 목소리를 더 배운다), 이 줄 지우기.
 */
@Composable
fun EditUtterance(u: Utterance, g: Graph, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(u.text) }
    var learn by remember { mutableStateOf(true) }
    var speaker by remember { mutableStateOf(u.speaker) }
    var note by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val learned = remember(text) { Corrections.learn(u.text, text) }
    if (confirmDelete) {
        ConfirmDelete("이 줄을 지울까요?", "“${u.text.take(40)}” 와 그때 녹음을 지워요.", onCancel = { confirmDelete = false }) {
            Player.stop(); scope.launch(Dispatchers.IO) { g.deleteRecords(listOf(u)) }; confirmDelete = false; close()
        }
        return
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("받아쓴 말") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${koClock(u.at)}에 들은 말" + (u.callWith?.let { " · 📞 $it 통화" } ?: ""), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2)
                g.audio.file(u.audio)?.let { f ->
                    TextButton(onClick = { Player.play(listOf(u.audio to f)) }) { Text("▶ 그때 소리 다시 듣기") }
                }
                if (learned.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(learn, { learn = it })
                    Text("다음부터 자동으로 고치기: " + learned.joinToString(", ") { "${it.wrong}→${it.right}" }, style = MaterialTheme.typography.bodySmall)
                }
                Text("누가 말했나요?", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("me" to "🙋 나", "other" to "👤 ${u.callWith ?: "상대"}").forEach { (who, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = speaker == who,
                            onClick = {
                                speaker = who
                                note = if (who == "me") "내 목소리를 배우는 중…" else ""
                                scope.launch(Dispatchers.IO) {
                                    val learnedVoice = g.teachSpeaker(u, me = who == "me")
                                    if (who == "me") note = if (learnedVoice) "✓ 이 소리로 내 목소리를 더 배웠어요" else "꼬리표만 바꿨어요 (녹음이 없거나 목소리 모델이 없어요)"
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
                if (note.isNotBlank()) Hint(note)
                TextButton(onClick = { confirmDelete = true }) { Text("🗑 이 줄 지우기", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                g.store.updateUtterance(u, text.trim())
                if (learn && learned.isNotEmpty()) {
                    val rules = Corrections.parse(g.settings.corrections) + learned
                    g.settings.corrections = rules.distinctBy { it.wrong }.joinToString("\n") { "${it.wrong}=${it.right}" }
                }
                close()
            }) { Text(if (text.trim() != u.text) "고치기" else "닫기") }
        },
        dismissButton = { TextButton(onClick = close) { Text("그만두기") } },
    )
}

/**
 * 일정 근거 화면: 왜 넣었는지, 언제·어디서 들었는지, 들은 말, 판단 이유, 앞뒤 대화 원문(눌러서 그때 소리 듣기).
 * 실제 약속이 아니면 [틀렸어요 — 지우기], 시간이 틀리면 [날짜·시간 고치기].
 */
@Composable
fun EventEvidence(eventId: Long, close: () -> Unit) {
    val activity = LocalContext.current as MainActivity
    val g = remember { Graph.get(activity) }
    val scope = rememberCoroutineScope()
    val version by g.store.version.collectAsState()
    val e = remember(version, eventId) { g.store.event(eventId) }
    var askMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Utterance?>(null) }
    if (e == null) { close(); return }
    // 예전 버전이 넣은 일정은 들은 시각이 없어, 근거 문장과 같은 말을 받아쓴 기록에서 찾아 쓴다.
    val heardAt = remember(e.id) {
        if (e.heardAt > 0 || e.evidence.isBlank()) e.heardAt
        else g.store.utterancesSince(e.createdAt - 2L * 24 * 60 * 60 * 1000).filter { it.at <= e.createdAt + 60_000 }
            .lastOrNull { it.text.contains(e.evidence.take(10)) }?.at ?: 0L
    }
    val around = remember(e.id, version, heardAt) {
        if (heardAt <= 0) emptyList()
        else g.store.utterancesSince(heardAt - 4 * 60_000).filter { it.at <= heardAt + 3 * 60_000 }
    }
    val heard = if (heardAt > 0) Instant.ofEpochMilli(heardAt).atZone(EventRules.SEOUL) else null
    fun close2() { Player.stop(); close() }
    editing?.let { u -> EditUtterance(u, g) { editing = null } }
    Dialog(onDismissRequest = ::close2, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("일정 근거", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(e.title, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp))
                Text((if (e.timeUnknown) "날짜 미정" else Notifier.describe(e)) + if (e.location.isNotBlank()) " · ${e.location}" else "", style = MaterialTheme.typography.bodyLarge)
                Tag(
                    when {
                        e.wrong -> "❌ 틀린 일정으로 표시해 지웠어요"
                        e.status == SavedEvent.CANCELLED -> "취소한 일정이에요"
                        e.status == SavedEvent.DISMISSED -> "'아니요'라고 하신 일정이에요"
                        e.asking -> "❓ 아직 넣지 않고 물어보는 중"
                        e.calendarEventId != null -> "📅 캘린더에 들어가 있어요"
                        else -> "🔔 앱 알림만 맞춰 뒀어요"
                    },
                )

                AppCard {
                    Text("왜 넣었나요?", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (e.addedBy) {
                            "approved" -> "수빈이가 물어봤고, 직접 '추가'를 누르셨어요."
                            "voice" -> "수빈이와 통화하면서 말로 부탁하셨어요."
                            "edited" -> "날짜·시간을 직접 고쳐서 넣으셨어요."
                            "auto" -> "확실한 약속처럼 들려 묻지 않고 바로 넣었어요. 확신도 ${(e.confidence * 100).toInt()}%."
                            else -> if (e.asking) "약속 같은 말을 들었지만 확실하지 않아 먼저 여쭤보는 거예요. 확신도 ${(e.confidence * 100).toInt()}%." else "대화에서 찾은 일정이에요."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (e.mediaLikely) Text("📺 그때 10분 동안 말소리가 거의 쉬지 않았어요. TV·영상 소리였을 수 있어요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    if (heard != null) Text(
                        "들은 때: ${heard.monthValue}월 ${heard.dayOfMonth}일 ${koClock(heardAt)} · " +
                            if (e.source.startsWith("call:")) "📞 ${e.source.removePrefix("call:")} 통화 녹음" else "휴대폰 곁에서 들은 말",
                        style = TextStyles.caption,
                    )
                    if (e.evidence.isNotBlank()) Text("“${e.evidence}”", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    if (e.reasons.isNotEmpty()) {
                        Text("판단 근거", style = MaterialTheme.typography.labelLarge)
                        e.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    } else if (e.heardAt <= 0) {
                        Hint("이 일정은 판단 이유를 기록하기 전 버전에서 넣었어요. 아래 앞뒤 대화로 확인해 주세요.")
                    }
                }

                if (around.isNotEmpty()) AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("앞뒤 대화 (받아쓴 원문)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (around.any { it.audio.isNotBlank() && g.audio.file(it.audio) != null }) TextButton(onClick = { playLines(g, around) }) { Text("▶ 이어 듣기") }
                    }
                    Hint("줄을 누르면 그때 소리를 들어요 (녹음은 ${g.settings.keepAudioDays}일 보관) · 길게 누르면 고쳐요")
                    around.forEach { u ->
                        val key = u.at == heardAt || (e.evidence.isNotBlank() && u.text.contains(e.evidence.take(8)))
                        TranscriptLine(u, g, highlight = key) { editing = it }
                    }
                }

                if (!e.wrong && e.status != SavedEvent.CANCELLED && e.status != SavedEvent.DISMISSED) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (e.asking && !e.timeUnknown) Button(onClick = { scope.launch(Dispatchers.IO) { g.events.addToCalendar(e.id) } }) { Text("맞아요, 넣어 줘") }
                        else if (!e.asking) Button(onClick = ::close2) { Text("맞아요") }
                        OutlinedButton(onClick = {
                            activity.pickDateTime(e.startMillis, e.allDay) { start, allDay -> scope.launch(Dispatchers.IO) { g.events.reschedule(e.id, start, allDay, toCalendar = true) } }
                        }) { Text("날짜·시간 고치기") }
                    }
                    OutlinedButton(onClick = {
                        scope.launch(Dispatchers.IO) { if (e.asking) { g.events.dismiss(e.id); g.events.cancel(e.id, wrong = true) } else g.events.cancel(e.id, wrong = true) }
                        if (g.settings.addMode != EventRules.MODE_ASK) askMode = true else close2()
                    }, modifier = Modifier.fillMaxWidth()) { Text("❌ 실제 약속이 아니에요 — 지우기") }
                }
                Spacer(Modifier.height(2.dp))
                TextButton(onClick = ::close2, modifier = Modifier.align(Alignment.End)) { Text("닫기") }
            }
        }
    }
    if (askMode) AlertDialog(
        onDismissRequest = { askMode = false; close2() },
        title = { Text("지웠어요") },
        text = { Text("앞으로는 확실해 보여도 바로 넣지 않고, 모든 일정을 먼저 \"추가할까요?\" 하고 물어볼까요? (TV를 자주 켜 두신다면 추천해요)") },
        confirmButton = { TextButton(onClick = { g.settings.addMode = EventRules.MODE_ASK; askMode = false; close2() }) { Text("네, 늘 물어봐 줘") } },
        dismissButton = { TextButton(onClick = { askMode = false; close2() }) { Text("지금처럼") } },
    )
}
