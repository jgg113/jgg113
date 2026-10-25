package io.github.jgg113.gyeot

import android.content.Context
import io.github.jgg113.gyeot.ai.Analyzer
import io.github.jgg113.gyeot.ai.Brain
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.ScheduleSummary
import io.github.jgg113.gyeot.data.SavedEvent
import io.github.jgg113.gyeot.ai.LocalLlm
import io.github.jgg113.gyeot.ai.RuleBrain
import io.github.jgg113.gyeot.audio.Wav
import io.github.jgg113.gyeot.data.ModelManager
import io.github.jgg113.gyeot.stt.LocalTranscriber
import io.github.jgg113.gyeot.alarm.AlarmScheduler
import io.github.jgg113.gyeot.calendar.CalendarWriter
import io.github.jgg113.gyeot.calendar.EventRegistrar
import io.github.jgg113.gyeot.data.Settings
import io.github.jgg113.gyeot.data.Store
import io.github.jgg113.gyeot.stt.Transcriber

/** 앱 전체에서 하나씩만 쓰는 것들. */
class Graph private constructor(private val context: Context) {
    val settings = Settings(context)
    val store = Store(context)
    val calendar = CalendarWriter(context)
    val alarms = AlarmScheduler(context)
    val events = EventRegistrar(context, store, settings, calendar, alarms)
    val models = ModelManager(context)
    val transcriber = Transcriber(settings)
    val localTranscriber = LocalTranscriber(models, settings)
    val analyzer = Analyzer(settings)

    /** 휴대폰 안 작은 AI 로 요약 다듬기 — 켜 두었고 모델이 있을 때만. 쓸 때마다 열고 닫는다. */
    private val polisher = object : RuleBrain.Polisher {
        private fun <T> llm(block: (LocalLlm) -> T): T? =
            if (settings.useLocalLlm && models.installed(models.llm)) LocalLlm.with(context, models.llmFile, block) else null
        override fun summarize(dialogue: String) = llm { it.summarize(dialogue) }
        override fun answer(question: String, lines: String) = llm { it.answer(question, lines) }?.takeIf { it.isNotBlank() }
    }
    val ruleBrain = RuleBrain(polisher)

    /** 삼성 자동 통화 녹음 가져오기 (처음 쓸 때 만든다). */
    val calls by lazy { io.github.jgg113.gyeot.calls.CallRecordingImporter(context) }

    /**
     * 말소리 구간 자르는 쪽: 사람 목소리 감지 모델(Silero)이 있으면 그것을, 없으면 소리 크기 기준을 쓴다.
     * 모델이 없으면 뒤에서 조용히 받아 둔다 (0.6MB).
     */
    fun newSegmenter(maxSegmentSec: Float = 20f, minSilenceSec: Float = 0.6f): io.github.jgg113.gyeot.audio.Segmenter {
        if (models.installed(models.vad)) {
            runCatching { return io.github.jgg113.gyeot.audio.SileroSegmenter(models.file(models.vad, "silero_vad.onnx").path, maxSegmentSec, minSilenceSec) }
        } else if (localTranscriber.available) {
            models.download(models.vad)
        }
        return io.github.jgg113.gyeot.audio.SpeechSegmenter(endSilenceMs = (minSilenceSec * 1000).toInt() + 600, maxSegmentMs = (maxSegmentSec * 1000).toInt())
    }

    /** 지금 설정에 맞는 정리 엔진. */
    val brain: Brain get() = if (settings.localBrain) ruleBrain else analyzer

    /**
     * 지금 설정에 맞는 받아쓰기 + 내가 정한 고쳐 쓸 말 적용.
     * '중요한 말만 클라우드로 한 번 더'를 켜 두면, 휴대폰 안에서 받아쓴 글에 일정·약속 냄새가 날 때만
     * 그 구간을 클라우드(Groq Whisper 무료 한도)로 다시 받아써 더 정확한 쪽을 쓴다.
     */
    fun transcribe(pcm: ShortArray, preferBest: Boolean = false): String {
        val best = preferBest && settings.localStt && localTranscriber.canBoost
        var text = if (best) localTranscriber.transcribe(pcm, io.github.jgg113.gyeot.data.Settings.MODEL_QWEN3)
        else if (settings.localStt) localTranscriber.transcribe(pcm) else transcriber.transcribe(Wav.encode(io.github.jgg113.gyeot.audio.Gain.normalize(pcm)))
        val important = io.github.jgg113.gyeot.ai.ScheduleHints.mentionsSchedule(text)
        // 휴대폰 안에서: 기본 모델 글에 일정 냄새가 나면 그 구간만 고정밀 모델로 다시 (무료).
        if (!best && settings.localStt && important && settings.localBoost && localTranscriber.canBoost &&
            localTranscriber.engine() != io.github.jgg113.gyeot.data.Settings.MODEL_QWEN3
        ) {
            runCatching { localTranscriber.transcribe(pcm, io.github.jgg113.gyeot.data.Settings.MODEL_QWEN3) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { text = it }
        } else if (settings.localStt && settings.cloudBoost && settings.sttApiKey.isNotBlank() && important) {
            runCatching { transcriber.transcribe(Wav.encode(io.github.jgg113.gyeot.audio.Gain.normalize(pcm))) }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { text = it }
        }
        val rules = io.github.jgg113.gyeot.stt.Corrections.parse(settings.corrections)
        return if (rules.isEmpty()) text else io.github.jgg113.gyeot.stt.Corrections.apply(text, rules)
    }

    /** 기간 안의 일정: 휴대폰 캘린더 전체 + 앱 알림만 맞춘 것 + 추가할지 묻는 중인 것. */
    fun scheduleItems(from: java.time.LocalDate, to: java.time.LocalDate): List<ScheduleSummary.Item> {
        val zone = EventRules.SEOUL
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val cal = calendar.instances(fromMs, toMs)
        val app = store.events().filter { e ->
            e.startMillis in fromMs until toMs && !e.timeUnknown &&
                (e.status == SavedEvent.REMINDER || e.asking || (e.status == SavedEvent.REGISTERED && (e.calendarEventId == null || !calendar.hasPermission())))
        }.map { e ->
            ScheduleSummary.Item(e.title, e.startMillis, e.endMillis, e.allDay, e.location, if (e.asking) "ask" else "app")
        }
        return (cal + app).sortedBy { it.start }
    }

    /** 묻기 탭: "이번 주 일정 뭐야?" 같은 질문은 캘린더로 바로 답하고, 나머지는 대화 기록에서 찾는다. */
    fun ask(question: String, material: String): String {
        val now = java.time.ZonedDateTime.now(EventRules.SEOUL)
        val schedule = if (ScheduleSummary.isScheduleQuestion(question)) {
            val (from, to) = ScheduleSummary.range(question, now)
            ScheduleSummary.text(scheduleItems(from, to), from, to, now.toLocalDate())
        } else null
        if (schedule != null && settings.localBrain) return schedule
        val week = ScheduleSummary.text(scheduleItems(now.toLocalDate().minusDays(1), now.toLocalDate().plusDays(13)), now.toLocalDate().minusDays(1), now.toLocalDate().plusDays(13), now.toLocalDate())
        return brain.ask(question, "## 캘린더 일정 (어제~2주 뒤)\n$week\n\n$material", now)
    }

    /**
     * 정리 노트를 받아쓴 원문에서 규칙으로 다시 만든다 (작은 AI 를 쓰지 않음).
     * 예전 버전의 요약 AI 가 남긴 깨진 글자·지어낸 문장을 없애는 데 쓴다. 쓸모없는 노트면 null.
     */
    fun rebuildNote(n: io.github.jgg113.gyeot.data.Note): io.github.jgg113.gyeot.data.Note? {
        val said = store.utterancesSince(n.from - 1_000).filter { it.at <= n.at + 1_000 }
        if (said.isEmpty()) return null
        val at = java.time.Instant.ofEpochMilli(n.at).atZone(EventRules.SEOUL)
        val r = plainRules.analyze(emptyList(), said, emptyList(), at)
        if (r.importance == "low" && n.eventIds.isEmpty() && r.todos.isEmpty() && r.keyPoints.isEmpty()) return null
        val who = said.firstNotNullOfOrNull { it.callWith }
        val prefix = if (who != null) "📞 $who 통화 (${((n.at - n.from) / 60_000).coerceAtLeast(1)}분) · " else ""
        return n.copy(summary = prefix + r.summary, importance = r.importance, keyPoints = r.keyPoints, todos = r.todos)
    }
    private val plainRules = RuleBrain()

    /** 하루치 정리를 다시 한다 (기록 탭의 [정리 다시 하기]). */
    fun rebuildDay(day: java.time.LocalDate) =
        store.rewriteNotes { n -> if (io.github.jgg113.gyeot.data.Store.dayOf(n.at) == day) rebuildNote(n) else n }

    /** 최근 며칠의 정리 노트 + 받아쓴 말 원문 (날짜·시각 붙임) — 묻기 탭·수빈이 통화가 근거로 쓴다. */
    fun recentMaterial(days: Int): String {
        val today = java.time.LocalDate.now(EventRules.SEOUL)
        val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        fun clock(at: Long) = java.time.Instant.ofEpochMilli(at).atZone(EventRules.SEOUL).format(hm)
        return buildString {
            generateSequence(today.minusDays((days - 1).toLong())) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.forEach { d ->
                val notes = store.notes().filter { io.github.jgg113.gyeot.data.Store.dayOf(it.at) == d }
                val lines = store.utterances(d)
                if (notes.isEmpty() && lines.isEmpty()) return@forEach
                appendLine("## $d")
                notes.forEach { n ->
                    appendLine("[정리 ${clock(n.from)}~${clock(n.at)}] ${n.summary}")
                    n.keyPoints.forEach { appendLine("  - $it") }
                    n.todos.forEach { appendLine("  - 할 일: ${it.text} ${it.due}") }
                }
                lines.forEach { u -> appendLine("[${clock(u.at)}] ${u.callWith?.let { "(📞 $it) " }.orEmpty()}${u.text}") }
            }
        }.ifBlank { "(기록 없음)" }
    }

    /** 듣기를 시작해도 되는지, 안 되면 이유. */
    fun notReadyReason(): String? = when {
        settings.localStt && !localTranscriber.available -> "설정 탭에서 '한국어 음성 인식' 모델을 먼저 받아 주세요 (105MB, 한 번만)."
        !settings.localStt && settings.sttApiKey.isBlank() -> "설정 탭에서 받아쓰기 API 키를 넣어 주세요."
        !settings.localBrain && settings.anthropicApiKey.isBlank() -> "설정 탭에서 Claude API 키를 넣어 주세요."
        else -> null
    }

    companion object {
        @Volatile private var instance: Graph? = null
        fun get(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context.applicationContext).also { instance = it } }
    }
}
