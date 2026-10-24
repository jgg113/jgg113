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
    val localTranscriber = LocalTranscriber(models)
    val analyzer = Analyzer(settings)

    /** 휴대폰 안 작은 AI 로 요약 다듬기 — 켜 두었고 모델이 있을 때만. 쓸 때마다 열고 닫는다. */
    private val polisher = object : RuleBrain.Polisher {
        private fun <T> llm(block: (LocalLlm) -> T): T? =
            if (settings.useLocalLlm && models.installed(models.llm)) LocalLlm.with(context, models.llmFile, block) else null
        override fun summarize(dialogue: String) = llm { it.summarize(dialogue) }
        override fun answer(question: String, lines: String) = llm { it.answer(question, lines) }
    }
    val ruleBrain = RuleBrain(polisher)

    /** 삼성 자동 통화 녹음 가져오기 (처음 쓸 때 만든다). */
    val calls by lazy { io.github.jgg113.gyeot.calls.CallRecordingImporter(context) }

    /** 지금 설정에 맞는 정리 엔진. */
    val brain: Brain get() = if (settings.localBrain) ruleBrain else analyzer

    /** 지금 설정에 맞는 받아쓰기. */
    fun transcribe(pcm: ShortArray): String =
        if (settings.localStt) localTranscriber.transcribe(pcm) else transcriber.transcribe(Wav.encode(pcm))

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

    /** 듣기를 시작해도 되는지, 안 되면 이유. */
    fun notReadyReason(): String? = when {
        settings.localStt && !models.installed(models.asr) -> "설정 탭에서 '한국어 음성 인식' 모델을 먼저 받아 주세요 (85MB, 한 번만)."
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
