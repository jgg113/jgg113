package io.github.jgg113.gyeot.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.ScheduleSummary
import io.github.jgg113.gyeot.alarm.Notifier
import io.github.jgg113.gyeot.audio.ListenState
import io.github.jgg113.gyeot.audio.ListeningService
import io.github.jgg113.gyeot.data.SavedEvent
import io.github.jgg113.gyeot.data.Utterance
import io.github.jgg113.gyeot.voice.Speaker
import io.github.jgg113.gyeot.voice.VoiceCall
import io.github.jgg113.gyeot.voice.VoiceCommands
import io.github.jgg113.gyeot.voice.VoiceCommands.Cmd
import io.github.jgg113.gyeot.voice.VoiceScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * "클로드 수빈이" 전화 화면 (삼성 One UI 수신·통화 화면을 닮게).
 * 울릴 때: 초록 버튼을 밀어서 받기, 빨간 버튼을 밀어서 거절.
 * 받으면 수빈이가 말하고, 이어서 내 말을 듣고 대답한다 ("10분 뒤에 다시 알려줘", "자세히 말해줘",
 * "응 넣어줘", "이번 주 일정 뭐야?", "내일 세 시에 치과 일정 넣어줘", "팀장님이 뭐라고 했지?", "고마워 끊어").
 */
class CallActivity : ComponentActivity() {
    private lateinit var g: Graph
    private var speaker: Speaker? = null
    private var recognizer: SpeechRecognizer? = null
    private var ringtone: Ringtone? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var kind = VoiceCall.KIND_CHAT
    private var title by mutableStateOf("")
    private var fallback = ""
    private var eventId = -1L
    private var detailed = false

    private var answered by mutableStateOf(false)
    private var answeredAt by mutableLongStateOf(0L)
    private var status by mutableStateOf("")
    private var subtitle by mutableStateOf("")
    private var heard by mutableStateOf("")
    private var lastContent = ""
    private var queue = ArrayDeque<SavedEvent>()   // 말로 물어볼 일정
    private var askingNow by mutableStateOf<SavedEvent?>(null)
    private var misses = 0
    private var pausedListening = false
    private var ending = false

    private val missed = Runnable {
        if (!answered) {
            stopRinging()
            Notifier.simple(this, "부재중 · ${VoiceScript.CALLER}", fallback.ifBlank { title }, VoiceCall.ID_CALL + 100)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        g = Graph.get(this)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        detailed = g.settings.detailed
        speaker = Speaker(this, g.settings) { ok -> if (!ok) runOnUiThread { status = "한국어 음성이 없어 글로 보여 드려요" } }
        load(intent)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { if (answered) InCall() else Ringing() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        load(intent)
    }

    private fun load(i: Intent) {
        kind = i.getStringExtra(VoiceCall.EXTRA_KIND) ?: VoiceCall.KIND_CHAT
        title = i.getStringExtra(VoiceCall.EXTRA_TITLE).orEmpty()
        fallback = i.getStringExtra(VoiceCall.EXTRA_SPEECH).orEmpty()
        eventId = i.getLongExtra(VoiceCall.EXTRA_EVENT, -1)
        if (i.getBooleanExtra(EXTRA_ANSWER, false)) answer() else if (!answered) startRinging()
    }

    // ---------------- 벨 ----------------

    private fun startRinging() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        handler.postDelayed(missed, 40_000)
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        getSystemService(Vibrator::class.java)?.cancel()
        handler.removeCallbacks(missed)
    }

    // ---------------- 통화 ----------------

    private fun answer() {
        if (answered) return
        answered = true
        answeredAt = System.currentTimeMillis()
        stopRinging()
        VoiceCall.cancel(this)
        // 통화하는 동안은 곁에서 듣기를 잠시 멈춘다 (수빈이 목소리·내 대답이 기록에 섞이지 않게).
        val s = ListenState.state.value
        if (s.running && !s.paused) { ListeningService.pause(this, true); pausedListening = true }
        lifecycleScope.launch {
            val (content, asks) = withContext(Dispatchers.IO) { content() to askable() }
            queue = ArrayDeque(asks)
            lastContent = content
            say(content) { askNextOrListen() }
        }
    }

    /** 이번 전화에서 할 말 (간단히/자세히). */
    private fun content(): String {
        val now = System.currentTimeMillis()
        val today = LocalDate.now(EventRules.SEOUL)
        return when (kind) {
            VoiceCall.KIND_EVENT -> g.store.event(eventId)?.let {
                VoiceScript.event(it.title, it.startMillis, it.allDay, it.location, it.evidence, now, detailed)
            } ?: fallback
            VoiceCall.KIND_BRIEFING -> {
                val todos = g.store.notes().flatMap { it.todos }.filter { it.due.startsWith(today.toString()) }.map { it.text }.distinct()
                VoiceScript.briefing(
                    g.scheduleItems(today, today), todos, 0, today,
                    morning = ZonedDateTime.now(EventRules.SEOUL).hour < 12, detailed = detailed,
                    tomorrow = if (detailed) g.scheduleItems(today.plusDays(1), today.plusDays(1)) else emptyList(),
                )
            }
            else -> VoiceScript.greeting()
        }
    }

    /** 말로 물어볼 일정 ("추가할까요?" 기다리는 것). 일정 전화는 1개, 브리핑·대화는 3개까지. */
    private fun askable(): List<SavedEvent> = g.store.suggestions().sortedBy { it.startMillis }.take(if (kind == VoiceCall.KIND_EVENT) 1 else 3)

    private fun askNextOrListen() {
        val next = queue.removeFirstOrNull()
        if (next != null) {
            askingNow = next
            val whenText = if (next.timeUnknown) null else Notifier.describe(next)
            say(VoiceScript.askToAdd(next.title, whenText)) { listen() }
        } else {
            askingNow = null
            listen()
        }
    }

    private fun say(text: String, then: () -> Unit) {
        subtitle = text
        status = "수빈이가 말하는 중"
        val sp = speaker
        if (sp == null || !sp.ready) {
            // 목소리를 못 쓰면 글을 읽을 시간을 준다.
            handler.postDelayed({ then() }, (2_000L + text.length * 60L).coerceAtMost(12_000))
            return
        }
        sp.speak(text) { runOnUiThread { if (!ending) then() } }
    }

    private fun listen() {
        if (ending) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status = "음성 인식을 쓸 수 없어 버튼으로 골라 주세요"; return }
        status = "듣고 있어요 🎤"
        heard = ""
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status = "알아듣는 중…" }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard = it }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                heard = text
                if (text.isBlank()) onMiss() else { misses = 0; handle(text) }
            }
            override fun onError(error: Int) { onMiss() }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        runCatching { r.startListening(intent) }.onFailure { status = "음성 인식을 시작하지 못했어요" }
    }

    private fun onMiss() {
        if (ending) return
        misses++
        when {
            askingNow != null && misses == 1 -> say("대답이 없으시면 일단 넣지 않고 그대로 둘게요. 나중에 화면에서 골라 주세요.") { askingNow = null; askNextOrListen() }
            kind == VoiceCall.KIND_CHAT && misses == 1 -> say("듣고 있어요. 편하게 말씀해 주세요.") { listen() }
            else -> { status = "말이 없어 통화를 마칠게요"; handler.postDelayed({ hangUp() }, 1_500) }
        }
    }

    private fun handle(text: String) {
        val now = ZonedDateTime.now(EventRules.SEOUL)
        val asking = askingNow
        when (val cmd = VoiceCommands.parse(text, asking != null, now)) {
            Cmd.End -> { ending = true; say(VoiceScript.farewell()) { hangUp() } }
            Cmd.Repeat -> say(lastContent) { askNextOrListen() }
            is Cmd.Snooze -> {
                if (eventId >= 0) g.alarms.snooze(eventId, cmd.minutes) else g.alarms.callBriefingAt(System.currentTimeMillis() + cmd.minutes * 60_000L)
                ending = true
                say("네, ${cmd.minutes}분 뒤에 다시 전화드릴게요!") { hangUp() }
            }
            Cmd.Detailed, Cmd.Brief -> {
                detailed = cmd == Cmd.Detailed
                background({ content() }) { c -> lastContent = c; say(c) { askNextOrListen() } }
            }
            Cmd.Yes, Cmd.RemindOnly, Cmd.No -> {
                val e = asking ?: return listen()
                background({
                    when (cmd) {
                        Cmd.Yes -> if (e.timeUnknown) { g.events.remindOnly(e.id); "내일 아침 9시에 알려 드릴게요!" } else { g.events.addToCalendar(e.id); "네! ${e.title}, 캘린더에 넣었어요." }
                        Cmd.RemindOnly -> { g.events.remindOnly(e.id); "알림만 맞춰 둘게요!" }
                        else -> { g.events.dismiss(e.id); "알겠어요, 넣지 않을게요." }
                    }
                }) { reply -> askingNow = null; say(reply) { askNextOrListen() } }
            }
            is Cmd.NewEvent -> background({ addByVoice(cmd.text, now) }) { reply -> say(reply) { askNextOrListen() } }
            is Cmd.Schedule -> background({
                val (from, to) = ScheduleSummary.range(cmd.text, now)
                VoiceScript.schedule(g.scheduleItems(from, to), from, to, now.toLocalDate())
            }) { reply -> say(reply) { askNextOrListen() } }
            is Cmd.Ask -> {
                status = "찾아보는 중…"
                background({ spoken(g.ask(cmd.text, g.recentMaterial(3))) }) { reply -> say(reply) { askNextOrListen() } }
            }
            Cmd.Nothing -> listen()
        }
    }

    /** "내일 세 시에 치과 일정 넣어 줘" — 말로 부탁한 일정은 묻지 않고 바로 넣는다. */
    private fun addByVoice(text: String, now: ZonedDateTime): String {
        val found = g.ruleBrain.analyze(emptyList(), listOf(Utterance(now.toInstant().toEpochMilli(), text)), emptyList(), now)
            .events.firstOrNull()?.let { it.copy(mediaLikely = false, reasons = listOf("수빈이와 통화 중에 직접 말로 부탁하셨어요")) } ?: return "언제인지 잘 못 알아들었어요. 날짜랑 시간을 한 번 더 말씀해 주시겠어요?"
        val ids = g.events.handle(listOf(found.copy(confidence = maxOf(found.confidence, 0.9), timeUnknown = false)), now, EventRules.MODE_AUTO, addedBy = "voice")
        val e = ids.firstOrNull()?.let { g.store.event(it) } ?: return "이미 같은 일정이 있어서 따로 넣지 않았어요."
        return "네! ${Notifier.describe(e)}에 ${e.title}, ${if (e.calendarEventId != null) "캘린더에 넣었어요" else "알림 맞춰 뒀어요"}."
    }

    /** 묻기 답을 말로 읽기 좋게: 글머리표·시각 표시를 다듬고 앞부분만. */
    private fun spoken(answer: String): String {
        val lines = answer.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("—") && !it.startsWith("찾은 말") }
        val text = lines.take(3).joinToString(" ") { it.removePrefix("•").trim() }
            .replace(Regex("""\[(\d{2}):(\d{2})]""")) { m -> "${m.groupValues[1].toInt()}시 ${m.groupValues[2].toInt()}분쯤 " }
            .replace(Regex("""\d{4}-(\d{2})-(\d{2})""")) { m -> "${m.groupValues[1].toInt()}월 ${m.groupValues[2].toInt()}일" }
        return if (text.isBlank()) "기록에서 찾지 못했어요." else "찾아봤어요. ${text.take(260)}"
    }

    private fun <T> background(work: () -> T, done: (T) -> Unit) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(work) }
            r.onSuccess(done).onFailure { say("죄송해요, 잠깐 문제가 생겼어요. 다시 말씀해 주시겠어요?") { listen() } }
        }
    }

    private fun hangUp() {
        ending = true
        stopRinging()
        speaker?.stop()
        recognizer?.cancel()
        VoiceCall.cancel(this)
        finish()
    }

    override fun onResume() { super.onResume(); VoiceCall.foregroundActivities++ }
    override fun onPause() { super.onPause(); VoiceCall.foregroundActivities-- }

    override fun onDestroy() {
        stopRinging()
        speaker?.shutdown()
        recognizer?.destroy()
        if (pausedListening) ListeningService.pause(this, false)
        super.onDestroy()
    }

    // ---------------- 화면 (One UI 를 닮게) ----------------

    private val bg = Brush.verticalGradient(listOf(Color(0xFF2C3E50), Color(0xFF151C24), Color(0xFF0B0F14)))

    @Composable
    private fun Caller(sub: String) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(72.dp))
            Text(VoiceScript.CALLER, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Normal)
            Spacer(Modifier.height(6.dp))
            Text(sub, color = Color(0xFFB0BAC5), fontSize = 16.sp)
            Spacer(Modifier.height(28.dp))
            Box(Modifier.size(112.dp).background(Color(0xFF7BA7D9), CircleShape), contentAlignment = Alignment.Center) {
                Text("수", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    @Composable
    private fun Ringing() {
        Box(Modifier.fillMaxSize().background(bg)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Caller("휴대전화 · AI 비서")
                Spacer(Modifier.height(24.dp))
                Text(title, color = Color(0xFFE6ECF2), fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SwipeButton(Color(0xFF2EB85C), 0f) { answer() }
                    SwipeButton(Color(0xFFE5483D), 135f) { hangUp() }
                }
                Text("버튼을 밀어서 받거나 거절하세요", color = Color(0xFF8C97A3), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(20.dp))
                if (eventId >= 0) {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp).background(Color(0x22FFFFFF), RoundedCornerShape(28.dp))
                            .clickable { g.alarms.snooze(eventId, 10); hangUp() }.padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("10분 뒤 다시 알려 줘", color = Color.White, fontSize = 15.sp) }
                }
                Spacer(Modifier.height(36.dp))
            }
        }
    }

    /** One UI 처럼 원을 끌어서 받기/거절. 원 밖으로 충분히 밀면 동작한다. */
    @Composable
    private fun SwipeButton(color: Color, iconRotation: Float, onTrigger: () -> Unit) {
        var drag by remember { mutableStateOf(Offset.Zero) }
        val limit = with(LocalDensity.current) { 90.dp.toPx() }
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(76.dp).scale(pulse).alpha(0.25f).background(color, CircleShape))
            Box(
                Modifier.offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }.size(76.dp).background(color, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { if (drag.getDistance() > limit) onTrigger(); drag = Offset.Zero },
                            onDragCancel = { drag = Offset.Zero },
                        ) { change, amount -> change.consume(); drag += amount }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp).rotate(iconRotation)) }
        }
    }

    @Composable
    private fun InCall() {
        var elapsed by remember { mutableLongStateOf(0L) }
        LaunchedEffect(answeredAt) { while (true) { elapsed = (System.currentTimeMillis() - answeredAt) / 1000; delay(1_000) } }
        Box(Modifier.fillMaxSize().background(bg)) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Caller("%02d:%02d".format(elapsed / 60, elapsed % 60))
                Spacer(Modifier.height(12.dp))
                Text(status, color = Color(0xFF9FD3FF), fontSize = 14.sp)
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                        .background(Color(0x1AFFFFFF), RoundedCornerShape(20.dp)).padding(16.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(subtitle, color = Color.White, fontSize = 17.sp, lineHeight = 25.sp)
                    if (heard.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text("나: $heard", color = Color(0xFFFFD27A), fontSize = 15.sp)
                    }
                }
                val ask = askingNow
                if (ask != null) Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("네, 넣어 줘") { recognizer?.cancel(); handle("응 넣어줘") }
                    Pill("알림만") { recognizer?.cancel(); handle("알림만") }
                    Pill("아니요") { recognizer?.cancel(); handle("아니 필요 없어") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundAction("다시 듣기", "↻") { recognizer?.cancel(); handle("다시 말해줘") }
                    RoundAction(if (detailed) "간단히" else "자세히", if (detailed) "≡" else "☰") { recognizer?.cancel(); handle(if (detailed) "짧게" else "자세히") }
                    RoundAction("말하기", "🎤") { speaker?.stop(); recognizer?.cancel(); misses = 0; listen() }
                    if (eventId >= 0) RoundAction("10분 뒤", "⏰") { recognizer?.cancel(); handle("10분 뒤에 다시 알려줘") }
                }
                Spacer(Modifier.height(24.dp))
                Box(Modifier.size(76.dp).background(Color(0xFFE5483D), CircleShape).clickable { hangUp() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Call, contentDescription = "끊기", tint = Color.White, modifier = Modifier.size(34.dp).rotate(135f))
                }
                Spacer(Modifier.height(36.dp))
            }
        }
    }

    @Composable
    private fun RoundAction(label: String, glyph: String, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
            Box(Modifier.size(58.dp).background(Color(0x26FFFFFF), CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
                if (glyph == "↻") Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White) else Text(glyph, color = Color.White, fontSize = 22.sp)
            }
            Text(label, color = Color(0xFFD0D7DE), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }

    @Composable
    private fun Pill(label: String, onClick: () -> Unit) {
        Box(Modifier.background(Color(0x332EB85C), RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(label, color = Color.White, fontSize = 14.sp)
        }
    }

    companion object {
        const val EXTRA_ANSWER = "answer"
    }
}
