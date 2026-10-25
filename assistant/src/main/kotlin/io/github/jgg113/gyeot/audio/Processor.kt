package io.github.jgg113.gyeot.audio

import android.content.Context
import android.util.Log
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.ai.AnalyzeException
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.ScheduleHints
import io.github.jgg113.gyeot.alarm.Notifier
import io.github.jgg113.gyeot.data.Note
import io.github.jgg113.gyeot.data.Utterance
import io.github.jgg113.gyeot.stt.Transcriber
import io.github.jgg113.gyeot.stt.TranscriptFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/**
 * 말 구간 → 받아쓰기 → 저장 → (대화가 끊기면) Claude 정리 → 일정 등록·중요 알림.
 *
 * 받아쓰기는 짧은 구간을 모아 한 번에 보낸다(요청 수를 줄여 무료 한도 안에 머물게).
 * 정리는 대화가 [IDLE_MS] 동안 멈추면 하는데, 일정 냄새가 나면 [HINT_IDLE_MS] 만에 바로 한다.
 */
class Processor(private val context: Context, private val scope: CoroutineScope) {
    private val g = Graph.get(context)
    private val segments = Channel<Pair<Long, ShortArray>>(capacity = 64)
    private val analyzeLock = Mutex()

    /** 마지막 정리 이후 새로 받아쓴 말. */
    private val pending = mutableListOf<Utterance>()
    private var lastSpeechAt = 0L
    private var hinted = false
    private var failures = 0

    fun offer(endedAt: Long, pcm: ShortArray) {
        if (segments.trySend(endedAt to pcm).isFailure) Log.w(TAG, "음성 구간 대기열이 가득 차 하나를 버렸습니다")
        ListenState.update { it.copy(queued = it.queued + 1) }
    }

    fun start() {
        scope.launch { transcribeLoop() }
        scope.launch { analyzeLoop() }
    }

    /** 화면의 '지금 정리하기'. */
    fun analyzeNow() {
        scope.launch { analyze(force = true) }
    }

    private suspend fun transcribeLoop() {
        val batch = mutableListOf<ShortArray>()
        var batchEnd = 0L
        while (scope.isActive) {
            // 첫 구간을 기다린 뒤, 이어지는 구간을 조금 더 모은다 (최대 25초 분량 또는 4초간 새 구간 없음).
            val first = segments.receive()
            batch += first.second
            batchEnd = first.first
            while (batch.sumOf { it.size } < MAX_BATCH_SAMPLES) {
                val next = withTimeoutOrNull(BATCH_WAIT_MS) { segments.receive() } ?: break
                batch += next.second
                batchEnd = next.first
            }
            val count = batch.size
            val pcm = Wav.join(batch)
            batch.clear()
            val text = transcribeWithRetry(pcm)
            ListenState.update { it.copy(queued = (it.queued - count).coerceAtLeast(0)) }
            val clean = text?.let { TranscriptFilter.clean(it) } ?: continue
            val u = Utterance(batchEnd, clean, media = noteSpeech(batchEnd, pcm.size / SpeechSegmenter.SAMPLE_RATE.toDouble()))
            g.store.appendUtterance(u)
            synchronized(pending) {
                pending += u
                if (ScheduleHints.mentionsSchedule(clean)) hinted = true
            }
            lastSpeechAt = System.currentTimeMillis()
            ListenState.update { it.copy(lastHeardAt = u.at, lastText = clean, error = "") }
        }
    }

    /** 최근 10분 동안 말소리 길이를 모아, 6분 넘게 쉬지 않고 말소리였으면 TV·영상 소리로 의심한다. */
    private val speech = ArrayDeque<Pair<Long, Double>>()
    private fun noteSpeech(at: Long, seconds: Double): Boolean {
        speech.addLast(at to seconds)
        while (speech.isNotEmpty() && speech.first().first < at - MEDIA_WINDOW_MS) speech.removeFirst()
        return speech.sumOf { it.second } >= MEDIA_SPEECH_SEC
    }

    private suspend fun transcribeWithRetry(pcm: ShortArray): String? {
        var wait = 2_000L
        repeat(4) {
            try {
                return g.transcribe(pcm)
            } catch (e: Transcriber.SttException) {
                ListenState.update { s -> s.copy(error = e.message.orEmpty()) }
                if (!e.retryable) return null
            } catch (e: Exception) {
                ListenState.update { s -> s.copy(error = "받아쓰기 실패: ${e.message}") }
            }
            delay(wait)
            wait *= 3
        }
        return null
    }

    private suspend fun analyzeLoop() {
        while (scope.isActive) {
            delay(5_000)
            val (count, chars, hint) = synchronized(pending) { Triple(pending.size, pending.sumOf { it.text.length }, hinted) }
            if (count == 0) continue
            val idle = System.currentTimeMillis() - lastSpeechAt
            val due = chars >= MAX_PENDING_CHARS ||
                (hint && idle >= HINT_IDLE_MS) ||
                (idle >= IDLE_MS && chars >= MIN_CHARS) ||
                idle >= IDLE_MS * 5 // 아주 짧은 말이라도 오래 쌓아 두지 않는다
            if (due) analyze(force = false)
        }
    }

    private suspend fun analyze(force: Boolean) = analyzeLock.withLock {
        val fresh = synchronized(pending) { pending.toList() }
        if (fresh.isEmpty()) {
            if (force) ListenState.update { it.copy(error = "정리할 새 대화가 없어요") }
            return@withLock
        }
        if (!force && fresh.sumOf { it.text.length } < 4) {
            synchronized(pending) { pending.removeAll(fresh); hinted = false }
            return@withLock
        }
        val from = fresh.first().at
        val context = g.store.utterancesSince(from - CONTEXT_MS).filter { it.at < from }.takeLast(40)
        val now = ZonedDateTime.now(EventRules.SEOUL)
        try {
            val result = g.brain.analyze(context, fresh, g.store.upcomingEvents() + g.store.suggestions(), now)
            synchronized(pending) { pending.removeAll(fresh); hinted = false }
            failures = 0
            val eventIds = g.events.handle(EventRules.attachSource(result.events, fresh), now)
            if (result.importance != "low" || eventIds.isNotEmpty() || result.todos.isNotEmpty()) {
                val note = Note(
                    id = System.currentTimeMillis(),
                    at = System.currentTimeMillis(),
                    from = from,
                    summary = result.summary,
                    importance = result.importance,
                    keyPoints = result.keyPoints,
                    todos = result.todos,
                    eventIds = eventIds,
                )
                g.store.addNote(note)
                if (result.importance == "high" && (note.keyPoints.isNotEmpty() || note.todos.isNotEmpty())) {
                    Notifier.important(this.context, note)
                }
            }
            ListenState.update { it.copy(lastAnalysisAt = System.currentTimeMillis(), error = "") }
        } catch (e: Exception) {
            val retryable = (e as? AnalyzeException)?.retryable ?: false
            failures++
            ListenState.update { it.copy(error = e.message.orEmpty()) }
            // 다시 해도 안 될 오류(키 없음 등)이거나 여러 번 실패하면 이번 묶음은 저장만 해 두고 넘어간다 (기록 탭에는 남아 있다).
            if (!retryable || failures >= 5) synchronized(pending) { pending.removeAll(fresh); hinted = false }
            else delay(30_000L * failures)
        }
    }

    companion object {
        private const val TAG = "GyeotProcessor"
        private const val MAX_BATCH_SAMPLES = SpeechSegmenter.SAMPLE_RATE * 25
        private const val BATCH_WAIT_MS = 4_000L
        private const val IDLE_MS = 90_000L
        private const val HINT_IDLE_MS = 20_000L
        private const val MIN_CHARS = 15
        private const val MAX_PENDING_CHARS = 4_000
        private const val CONTEXT_MS = 15 * 60_000L
        private const val MEDIA_WINDOW_MS = 10 * 60_000L
        private const val MEDIA_SPEECH_SEC = 6 * 60.0
    }
}
