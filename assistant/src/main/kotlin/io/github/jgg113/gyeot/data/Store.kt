package io.github.jgg113.gyeot.data

import android.content.Context
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.Todo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate

@Serializable
data class Utterance(
    val at: Long,
    val text: String,
    /** 어디서 들은 말인지: 빈 값 = 곁에서 들은 말, "call:엄마" = 엄마와의 통화 녹음. */
    val source: String = "",
    /** 앞뒤 10분 동안 말소리가 거의 쉬지 않았는지 (TV·영상 소리일 가능성). */
    val media: Boolean = false,
) {
    val callWith: String? get() = source.removePrefix("call:").takeIf { source.startsWith("call:") }
}

@Serializable
data class Note(
    val id: Long,
    val at: Long,
    /** 정리한 대화가 시작된 시각. */
    val from: Long,
    val summary: String,
    val importance: String,
    val keyPoints: List<String>,
    val todos: List<Todo>,
    val eventIds: List<Long> = emptyList(),
)

@Serializable
data class SavedEvent(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String = "",
    val reminderMinutes: Int,
    val evidence: String = "",
    /** 캘린더(CalendarContract) 이벤트 id, 아직 안 넣었으면 null. */
    val calendarEventId: Long? = null,
    /**
     * registered(캘린더에 넣음) · reminder(캘린더 없이 앱 알림만) · suggested(추가할지 묻는 중)
     * · dismissed(아니요) · cancelled(넣었다가 취소). 옛 버전의 pending 은 suggested 로 본다.
     */
    val status: String = REGISTERED,
    val createdAt: Long = System.currentTimeMillis(),
    /** 언제인지 못 들어 시각이 임시 값인지. */
    val timeUnknown: Boolean = false,
    /** 근거: 들은 시각, 어디서(곁에서/통화), 판단 이유, 확신도, 누가 넣었는지. */
    val heardAt: Long = 0,
    val source: String = "",
    val reasons: List<String> = emptyList(),
    val confidence: Double = 0.0,
    /** auto(확실해 보여 바로) · approved(물어본 뒤 내가 승인) · voice(통화에서 말로 부탁) · edited(날짜 고쳐서) */
    val addedBy: String = "",
    val mediaLikely: Boolean = false,
    /** "틀렸어요"로 지운 일정. */
    val wrong: Boolean = false,
) {
    val active get() = status == REGISTERED || status == REMINDER
    val asking get() = status == SUGGESTED || status == "pending"

    companion object {
        const val REGISTERED = "registered"
        const val REMINDER = "reminder"
        const val SUGGESTED = "suggested"
        const val DISMISSED = "dismissed"
        const val CANCELLED = "cancelled"
    }
}

/**
 * 휴대폰 안에만 저장한다: 받아쓴 글(날짜별), 정리 노트, 등록한 일정. 원본 음성은 저장하지 않는다.
 * 파일이 작고 쓰는 곳이 한 군데라 DB 없이 JSON 파일로 둔다.
 */
class Store(context: Context) {
    private val dir = File(context.filesDir, "gyeot").apply { mkdirs() }
    private val transcriptDir = File(dir, "transcripts").apply { mkdirs() }
    private val notesFile = File(dir, "notes.json")
    private val eventsFile = File(dir, "events.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _version = MutableStateFlow(0)
    /** 저장 내용이 바뀔 때마다 오른다. 화면이 이걸 보고 다시 읽는다. */
    val version: StateFlow<Int> = _version

    private fun changed() { _version.value++ }

    // ---- 받아쓴 글 ----

    fun appendUtterance(u: Utterance) {
        synchronized(lock) {
            fileFor(dayOf(u.at)).appendText(json.encodeToString(u) + "\n")
        }
        changed()
    }

    fun utterances(day: LocalDate): List<Utterance> = synchronized(lock) {
        val f = fileFor(day)
        if (!f.exists()) emptyList()
        else f.readLines().mapNotNull { line -> runCatching { json.decodeFromString<Utterance>(line) }.getOrNull() }
    }

    fun utterancesSince(since: Long): List<Utterance> {
        val days = generateSequence(dayOf(since)) { it.plusDays(1) }.takeWhile { !it.isAfter(LocalDate.now(EventRules.SEOUL)) }
        return days.flatMap { utterances(it) }.filter { it.at >= since }.toList()
    }

    fun transcriptDays(): List<LocalDate> = synchronized(lock) {
        transcriptDir.listFiles().orEmpty().mapNotNull { runCatching { LocalDate.parse(it.nameWithoutExtension) }.getOrNull() }.sortedDescending()
    }

    /** 보관 기간이 지난 받아쓰기 파일을 지운다. */
    fun purgeOlderThan(days: Int) {
        val cutoff = LocalDate.now(EventRules.SEOUL).minusDays(days.toLong())
        synchronized(lock) {
            transcriptDir.listFiles().orEmpty().forEach { f ->
                val d = runCatching { LocalDate.parse(f.nameWithoutExtension) }.getOrNull()
                if (d != null && d.isBefore(cutoff)) f.delete()
            }
            val keepFrom = cutoff.atStartOfDay(EventRules.SEOUL).toInstant().toEpochMilli()
            writeNotes(readNotes().filter { it.at >= keepFrom })
        }
        changed()
    }

    // ---- 정리 노트 ----

    fun notes(): List<Note> = synchronized(lock) { readNotes() }

    fun addNote(note: Note) {
        synchronized(lock) { writeNotes(readNotes() + note) }
        changed()
    }

    /** 저장된 노트를 한 번에 고친다 (예: 예전 버전이 남긴 깨진 글자 고치기). */
    fun rewriteNotes(transform: (Note) -> Note?) {
        synchronized(lock) { writeNotes(readNotes().mapNotNull(transform)) }
        changed()
    }

    private fun readNotes(): List<Note> =
        if (!notesFile.exists()) emptyList()
        else runCatching { json.decodeFromString<List<Note>>(notesFile.readText()) }.getOrDefault(emptyList())

    private fun writeNotes(list: List<Note>) = atomicWrite(notesFile, json.encodeToString(list))

    // ---- 일정 ----

    fun events(): List<SavedEvent> = synchronized(lock) {
        if (!eventsFile.exists()) emptyList()
        else runCatching { json.decodeFromString<List<SavedEvent>>(eventsFile.readText()) }.getOrDefault(emptyList())
    }

    fun event(id: Long): SavedEvent? = events().firstOrNull { it.id == id }

    fun upsertEvent(e: SavedEvent) {
        synchronized(lock) {
            val list = events().filter { it.id != e.id } + e
            atomicWrite(eventsFile, json.encodeToString(list.sortedBy { it.startMillis }))
        }
        changed()
    }

    /** 캘린더에 넣었거나 알림을 맞춘, 아직 안 끝난 일정. */
    fun upcomingEvents(now: Long = System.currentTimeMillis()): List<SavedEvent> =
        events().filter { it.active && it.endMillis >= now }

    /** "추가할까요?" 하고 대답을 기다리는 일정 (지난 것은 빼고). */
    fun suggestions(now: Long = System.currentTimeMillis()): List<SavedEvent> =
        events().filter { it.asking && (it.timeUnknown || it.endMillis >= now) }

    /** 같은 걸 또 묻거나 또 넣지 않으려고 보는 목록: 상태와 상관없이 최근·앞으로의 일정. */
    fun recentEvents(now: Long = System.currentTimeMillis()): List<SavedEvent> =
        events().filter { it.endMillis >= now - 24L * 60 * 60 * 1000 || it.asking }

    fun clearAll() {
        synchronized(lock) {
            transcriptDir.listFiles().orEmpty().forEach { it.delete() }
            notesFile.delete()
        }
        changed()
    }

    private fun fileFor(day: LocalDate) = File(transcriptDir, "$day.jsonl")

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(EventRules.SEOUL).toLocalDate()
    }
}
