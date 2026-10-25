package io.github.jgg113.gyeot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.ScheduleSummary
import io.github.jgg113.gyeot.ai.Sessions
import io.github.jgg113.gyeot.audio.ListenState
import io.github.jgg113.gyeot.audio.ListeningService
import io.github.jgg113.gyeot.data.Settings as AppSettings
import io.github.jgg113.gyeot.data.Utterance
import io.github.jgg113.gyeot.voice.VoiceCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

@Composable
fun TodayScreen(onTab: (Int) -> Unit) {
    val activity = LocalContext.current as MainActivity
    val g = remember { Graph.get(activity) }
    val state by ListenState.state.collectAsState()
    val version by g.store.version.collectAsState()
    val events = remember(version) { g.store.upcomingEvents() }
    val asking = remember(version) { g.store.suggestions().sortedBy { it.startMillis } }
    val today = LocalDate.now(EventRules.SEOUL)
    var range by remember { mutableIntStateOf(0) } // 0 오늘·내일, 1 이번 주, 2 다음 주
    val summary by produceState("", version, range) {
        value = withContext(Dispatchers.IO) {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val (from, to) = when (range) {
                1 -> today to monday.plusDays(6)
                2 -> monday.plusWeeks(1) to monday.plusWeeks(1).plusDays(6)
                else -> today to today.plusDays(1)
            }
            ScheduleSummary.text(g.scheduleItems(from, to), from, to, today)
        }
    }
    val sessions by produceState(emptyList<Sessions.Session>(), version) {
        value = withContext(Dispatchers.IO) { daySessions(g, today) }
    }
    var editing by remember { mutableStateOf<Utterance?>(null) }
    // 목소리 등록 창이 닫히면 다시 읽어 '등록됨' 표시를 새로 고친다.
    val enrolled = remember(MainActivity.openTool.value) { g.voiceId.enrolled }
    editing?.let { u -> EditUtterance(u, g) { editing = null } }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val hour = LocalTime.now(EventRules.SEOUL).hour
            val hello = when (hour) {
                in 5..10 -> "좋은 아침이에요"
                in 11..16 -> "좋은 오후예요"
                in 17..21 -> "좋은 저녁이에요"
                else -> "편안한 밤 되세요"
            }
            val name = g.settings.myName.trim().split(' ', ',').firstOrNull().orEmpty()
            ScreenHeader(if (name.isNotBlank()) "$name 님, $hello" else hello, subtitle = longDay(today))
        }
        item { ListenCard(g, activity, state, version) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction("📞", "수빈이와\n통화", Modifier.weight(1f)) { VoiceCall.chat(activity) }
                QuickAction("🎤", "받아쓰기\n시험", Modifier.weight(1f)) { MainActivity.openTool.value = MainActivity.TOOL_DICTATION }
                QuickAction("🙋", if (enrolled) "내 목소리\n등록됨 ✓" else "내 목소리\n등록", Modifier.weight(1f)) { MainActivity.openTool.value = MainActivity.TOOL_VOICE }
                QuickAction("🗒", "기록\n다시 듣기", Modifier.weight(1f)) { onTab(1) }
            }
        }
        if (asking.isNotEmpty()) {
            item { SectionHeader("추가할까요? ${asking.size}") }
            items(asking, key = { it.id }) { e -> SuggestionCard(e, g, activity) }
        }
        item { SectionHeader("일정 요약") }
        item {
            AppCard {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("오늘·내일", "이번 주", "다음 주").forEachIndexed { i, t ->
                        FilterChip(
                            selected = range == i, onClick = { range = i }, label = { Text(t) }, border = null,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                Text(summary.ifBlank { "불러오는 중…" }, style = MaterialTheme.typography.bodyLarge)
                if (!g.calendar.hasPermission()) Hint("캘린더 권한을 허용하면 삼성 캘린더의 다른 일정도 함께 보여요.")
            }
        }
        item { SectionHeader("대화에서 넣은 일정 ${events.size}") }
        if (events.isEmpty()) item {
            AppCard { Hint("대화에서 찾은 일정이 여기에 쌓여요. 예) \"내일 3시에 치과 가야 돼\"") }
        }
        items(events, key = { it.id }) { e -> EventCard(e, g) }
        item {
            SectionHeader("오늘의 대화 ${sessions.size}") {
                if (sessions.size > 3) TextButton(onClick = { onTab(1) }) { Text("모두 보기 ›") }
            }
        }
        if (sessions.isEmpty()) item {
            AppCard { Hint("들은 대화가 묶음별로 요약돼 여기에 보여요. [전문 보기]를 누르면 받아쓴 원문과 그때 녹음을 들을 수 있어요.") }
        }
        items(sessions.take(3), key = { it.key }) { s -> SessionCard(s, g) { editing = it } }
    }
}

@Composable
private fun ListenCard(g: Graph, activity: MainActivity, state: ListenState.State, version: Int) {
    val on = state.running && !state.paused
    AppCard(color = if (on) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when {
                    !state.running -> MaterialTheme.colorScheme.outline
                    state.paused -> Brand.Amber
                    else -> MaterialTheme.colorScheme.tertiary
                },
                pulsing = on && state.hearing,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !state.running -> "듣기가 꺼져 있어요"
                        state.paused -> "잠시 멈췄어요"
                        state.hearing -> "말소리를 듣고 있어요…"
                        else -> "수빈이가 듣고 있어요"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    (if (g.settings.localStt) "휴대폰 안 받아쓰기 · 무료·무제한" else "클라우드 받아쓰기") +
                        (if (g.voiceId.enrolled) " · 내 목소리 알아봄" else "") +
                        (if (g.settings.keepAudioDays > 0) " · 녹음 ${g.settings.keepAudioDays}일 보관" else ""),
                    style = TextStyles.caption,
                )
            }
        }
        if (state.lastHeardAt > 0) Text("${koClock(state.lastHeardAt)} “${state.lastText}”", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        if (state.queued > 0) Hint("받아쓰는 중 ${state.queued}개")
        if (state.error.isNotBlank()) Text("⚠ ${state.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        val why = remember(state.running, version) { g.notReadyReason() }
        if (why != null) Text(why, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (g.settings.localStt && !g.models.installed(g.models.kozip) && g.settings.localModel == AppSettings.MODEL_KOZIP) {
            val p by g.models.progress(g.models.kozip).collectAsState()
            AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
                Text("✨ 한국어 대화 전용 모델을 받아 주세요", style = MaterialTheme.typography.titleMedium)
                Hint("한국어 대화 6,500시간으로 학습한 무료 모델이에요. 일상 대화·통화에서 지금 모델보다 훨씬 덜 틀려요. 160MB, 와이파이에서 한 번만.")
                if (p.running) LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth())
                else Button(onClick = { g.models.download(g.models.kozip); g.models.download(g.models.vad) }) { Text(if (p.error.isNotBlank()) "다시 받기" else "지금 받기") }
                if (p.error.isNotBlank()) Text(p.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!state.running) Button(onClick = { activity.startListening() }, modifier = Modifier.weight(1f)) { Text("듣기 시작") }
            else {
                Button(onClick = { ListeningService.pause(activity, !state.paused) }, modifier = Modifier.weight(1f)) { Text(if (state.paused) "다시 듣기" else "잠시 멈춤") }
                OutlinedButton(onClick = { ListeningService.analyzeNow(activity) }) { Text("지금 정리") }
                TextButton(onClick = { ListeningService.stop(activity) }) { Text("끄기") }
            }
        }
    }
}
