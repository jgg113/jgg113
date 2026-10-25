package io.github.jgg113.gyeot.calls

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.alarm.Notifier
import io.github.jgg113.gyeot.audio.SpeechSegmenter
import io.github.jgg113.gyeot.data.Note
import io.github.jgg113.gyeot.data.Utterance
import io.github.jgg113.gyeot.stt.TranscriptFilter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime

/**
 * 삼성 전화 앱의 자동 통화 녹음 파일을 찾아 받아쓰고 정리한다.
 * 통화 중에는 안드로이드가 다른 앱의 마이크를 막아 곁비서가 못 듣기 때문에, 녹음 파일로 대신 듣는 것이다.
 *
 * 녹음 파일은 읽기만 하고 고치거나 지우지 않는다. 처리한 파일은 기억해 두고 다시 처리하지 않는다.
 */
class CallRecordingImporter(private val context: Context) {
    private val g = Graph.get(context)
    private val prefs = context.getSharedPreferences("gyeot_calls", Context.MODE_PRIVATE)
    private val lock = Mutex()

    data class Recording(val id: Long, val name: String, val addedMillis: Long, val durationMs: Long)

    fun hasPermission(): Boolean = context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    /** 처음 켤 때 이 시각 이후 녹음만 처리한다 (예전 녹음을 한꺼번에 다 읽지 않게). */
    fun startFrom(millis: Long) = prefs.edit().putLong("since", millis).apply()

    private val since get() = prefs.getLong("since", System.currentTimeMillis() - 24L * 60 * 60 * 1000)
    private fun done(id: Long) = prefs.getStringSet("done", emptySet())!!.contains(id.toString())
    private fun markDone(id: Long) {
        val set = prefs.getStringSet("done", emptySet())!!.toMutableSet().apply { add(id.toString()) }
        // 너무 커지지 않게 최근 500개만 기억한다.
        val trimmed = if (set.size > 500) set.sortedByDescending { it.toLong() }.take(500).toSet() else set
        prefs.edit().putStringSet("done", trimmed).apply()
    }

    /** 새 통화 녹음을 모두 처리한다. @return 처리한 개수 */
    suspend fun scan(): Int = lock.withLock {
        if (!g.settings.importCalls || !hasPermission()) return@withLock 0
        var count = 0
        for (r in newRecordings()) {
            // 아직 쓰는 중인 파일(방금 끝난 통화)은 다음 번에.
            if (System.currentTimeMillis() - r.addedMillis < 5_000) continue
            runCatching { process(r) }.onFailure {
                Notifier.simple(context, "통화 녹음을 읽지 못했어요", "${r.name}: ${it.message}", Notifier.ID_ERROR)
            }
            markDone(r.id)
            count++
        }
        count
    }

    /**
     * 기록 탭의 [다시 받아쓰기]: 이 통화의 받아쓴 글·녹음·정리와, 그 통화에서 저절로 넣은(직접 승인·수정하지 않은) 일정을 지우고
     * 원래 통화 녹음 파일을 지금 모델로 다시 처리한다. 원래 녹음 파일을 못 찾으면 false (지운 것도 없다).
     */
    suspend fun redo(lines: List<Utterance>, noteIds: List<Long>): Boolean = lock.withLock {
        val who = lines.firstOrNull()?.callWith ?: return@withLock false
        val from = lines.first().at
        val to = lines.last().at
        val r = recordingsBetween(from - 24L * 60 * 60 * 1000, to + 24L * 60 * 60 * 1000).firstOrNull { rec ->
            val info = RecordingName.parse(rec.name)
            val start = info.startMillis ?: (rec.addedMillis - rec.durationMs)
            info.who == who && from >= start - 60_000 && from <= start + rec.durationMs + 60_000
        } ?: return@withLock false
        val tag = "call:$who"
        g.store.events().filter { e ->
            e.source == tag && e.heardAt in (from - 60_000)..(to + 60_000) && e.addedBy !in setOf("approved", "voice", "edited")
        }.forEach { e -> if (e.asking) g.events.dismiss(e.id) else if (e.active) g.events.cancel(e.id) }
        g.deleteRecords(lines, noteIds)
        process(r)
        markDone(r.id)
        true
    }

    private fun recordingsBetween(fromMillis: Long, toMillis: Long): List<Recording> = query(fromMillis, toMillis, skipDone = false)

    private fun newRecordings(): List<Recording> = query(since, Long.MAX_VALUE, skipDone = true)

    private fun query(fromMillis: Long, toMillis: Long, skipDone: Boolean): List<Recording> {
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val sel = "${MediaStore.Audio.Media.DATE_ADDED} >= ? AND ${MediaStore.Audio.Media.DATE_ADDED} <= ?"
        val args = arrayOf((fromMillis / 1000).toString(), (minOf(toMillis, Long.MAX_VALUE / 2) / 1000).toString())
        val out = mutableListOf<Recording>()
        context.contentResolver.query(uri, projection, sel, args, "${MediaStore.Audio.Media.DATE_ADDED} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1).orEmpty()
                val path = c.getString(4)
                if (!RecordingName.isCallFolder(path) && !name.startsWith("통화") && !name.startsWith("Call recording", ignoreCase = true)) continue
                if (skipDone && done(id)) continue
                out += Recording(id, name, c.getLong(2) * 1000, c.getLong(3))
            }
        }
        return out
    }

    private suspend fun process(r: Recording) {
        val info = RecordingName.parse(r.name)
        val start = info.startMillis ?: (r.addedMillis - r.durationMs)
        val tag = "call:${info.who}"
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, r.id)
        val segmenter = g.newSegmenter(maxSegmentSec = 20f, minSilenceSec = 0.5f)
        val frame = ShortArray(segmenter.frameSamples)
        var filled = 0
        var samples = 0L
        val segments = mutableListOf<Pair<Long, ShortArray>>()
        AudioDecoder.decode(context, uri) { pcm ->
            for (s in pcm) {
                frame[filled++] = s
                samples++
                if (filled == frame.size) {
                    filled = 0
                    segmenter.push(frame.copyOf())?.let { seg -> segments += (samples * 1000 / SpeechSegmenter.SAMPLE_RATE) to seg }
                }
            }
        }
        segmenter.flush()?.let { segments += (samples * 1000 / SpeechSegmenter.SAMPLE_RATE) to it }
        segmenter.release()
        // 두 사람 목소리로 나눈 뒤(말이 빨리 오가면 한 구간도 사람별로 자른다) 조각마다 받아쓴다.
        val parts = runCatching { io.github.jgg113.gyeot.voice.CallSpeakers(g.voiceId).split(segments.map { it.second }) }
            .getOrElse { segments.indices.map { i -> io.github.jgg113.gyeot.voice.CallSpeakers.Part(i, 0, segments[i].second.size, "", 0f) } }
        val utterances = parts.mapNotNull { part ->
            val (endMs, seg) = segments[part.seg]
            val pcm = if (part.from == 0 && part.to == seg.size) seg else seg.copyOfRange(part.from, part.to)
            if (pcm.size < SpeechSegmenter.SAMPLE_RATE * 3 / 10) return@mapNotNull null
            // 통화는 한 번만 받아쓰는 중요한 소리라, 받아 둔 모델 중 가장 정확한 것으로 받아쓴다.
            val text = runCatching { g.transcribe(pcm, preferBest = true) }.getOrNull()?.let { TranscriptFilter.clean(it) } ?: return@mapNotNull null
            val segStartMs = endMs - seg.size * 1000L / SpeechSegmenter.SAMPLE_RATE
            val at = start + segStartMs + part.to * 1000L / SpeechSegmenter.SAMPLE_RATE
            Utterance(
                at, text, source = tag,
                audio = if (g.settings.keepAudioDays > 0) g.audio.save(at, pcm) else "",
                speaker = part.speaker, voiceScore = part.score,
            )
        }
        if (utterances.isEmpty()) return
        utterances.forEach { g.store.appendUtterance(it) }
        val now = ZonedDateTime.now(EventRules.SEOUL)
        val result = g.brain.analyze(emptyList(), utterances, g.store.upcomingEvents() + g.store.suggestions(), now)
        val eventIds = g.events.handle(EventRules.attachSource(result.events, utterances), now)
        val minutes = (r.durationMs / 60_000).coerceAtLeast(1)
        val note = Note(
            id = System.currentTimeMillis(),
            at = start + r.durationMs,
            from = start,
            summary = "📞 ${info.who} 통화 (${minutes}분) · ${result.summary}",
            importance = if (result.importance == "low") "normal" else result.importance,
            keyPoints = result.keyPoints,
            todos = result.todos,
            eventIds = eventIds,
        )
        g.store.addNote(note)
        Notifier.callSummary(context, note, info.who, eventIds.size)
    }

    companion object {
        fun permission(): String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
}
