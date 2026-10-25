package io.github.jgg113.gyeot.ai

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** Claude 가 뽑은 일정을 실제 시각으로 바꾸고, 등록해도 되는지(미래인지·중복인지·확실한지) 가린다. */
object EventRules {
    val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    /** 이보다 낮으면 묻지도 않는다 (지나가는 말). */
    const val MIN_SUGGEST = 0.35
    /** '알아서' 모드에서 묻지 않고 바로 넣는 확신도. */
    const val AUTO_SMART = 0.75
    /** '바로 넣기' 모드의 기준. */
    const val AUTO_ALWAYS = 0.6

    enum class Decision { REGISTER, SUGGEST, DROP }

    /** 일정 추가 방식: smart(확실하면 바로, 애매하면 물어보기) · ask(늘 물어보기) · auto(웬만하면 바로). */
    fun decide(e: ExtractedEvent, timing: Timing, now: ZonedDateTime, mode: String): Decision {
        if (e.title.isBlank() || e.confidence < MIN_SUGGEST) return Decision.DROP
        val future = if (timing.allDay) !timing.start.toLocalDate().isBefore(now.toLocalDate()) else timing.start.isAfter(now)
        if (!future) return Decision.DROP
        if (e.timeUnknown) return Decision.SUGGEST
        // TV·영상 소리일 수 있으면 어떤 모드든 먼저 묻는다.
        if (e.mediaLikely) return Decision.SUGGEST
        return when (mode) {
            MODE_ASK -> Decision.SUGGEST
            MODE_AUTO -> if (e.confidence >= AUTO_ALWAYS) Decision.REGISTER else Decision.SUGGEST
            else -> if (e.confidence >= AUTO_SMART) Decision.REGISTER else Decision.SUGGEST
        }
    }

    const val MODE_SMART = "smart"
    const val MODE_ASK = "ask"
    const val MODE_AUTO = "auto"

    data class Timing(val start: ZonedDateTime, val end: ZonedDateTime, val allDay: Boolean)

    fun timing(e: ExtractedEvent): Timing? {
        val start = parse(e.start) ?: return null
        val allDay = e.allDay || e.start.trim().length == 10
        if (allDay) {
            val day = start.toLocalDate().atStartOfDay(SEOUL)
            return Timing(day, day.plusDays(1), true)
        }
        val end = parse(e.end)?.takeIf { it.isAfter(start) } ?: start.plusHours(1)
        return Timing(start, end, false)
    }

    fun parse(text: String): ZonedDateTime? {
        val t = text.trim().removeSuffix("Z")
        if (t.isEmpty()) return null
        return try {
            if (t.length == 10) LocalDate.parse(t).atStartOfDay(SEOUL)
            else LocalDateTime.parse(t.take(16)).atZone(SEOUL)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * Claude 처럼 근거 시각을 모르는 결과에, 근거 문장과 가장 비슷한 받아쓴 말을 찾아
     * 들은 시각·어디서·TV 의심 여부를 붙인다. 이미 붙어 있으면 그대로.
     */
    fun attachSource(events: List<ExtractedEvent>, said: List<io.github.jgg113.gyeot.data.Utterance>): List<ExtractedEvent> = events.map { e ->
        if (e.heardAt > 0 || said.isEmpty()) return@map e
        val u = said.maxByOrNull { overlap(it.text, e.evidence) } ?: said.last()
        e.copy(
            heardAt = u.at, source = u.source, mediaLikely = e.mediaLikely || u.media,
            reasons = e.reasons.ifEmpty { listOf("Claude 가 대화를 읽고 일정으로 판단했어요 (확신도 ${(e.confidence * 100).toInt()}%)") } +
                (if (u.media) listOf("앞뒤로 말소리가 거의 쉬지 않고 이어졌어요 — TV·영상 소리일 수 있어 바로 넣지 않고 물어봐요") else emptyList()),
        )
    }

    private fun overlap(a: String, b: String): Int {
        val ga = a.filter { !it.isWhitespace() }.windowed(2).toSet()
        return b.filter { !it.isWhitespace() }.windowed(2).count { it in ga }
    }

    /** 같은 일정을 두 번 넣지 않으려고 쓰는 키: 제목 글자만 + 시작 시각(분). */
    fun key(title: String, startMillis: Long): String =
        title.filter { it.isLetterOrDigit() }.lowercase() + "@" + startMillis / 60_000

    /**
     * 이미 등록된 일정과 같은지: 시작이 30분 안쪽이고 제목이 겹치면 같은 일정으로 본다
     * ("치과 예약" 과 "치과" 처럼 Claude 가 다르게 부를 수 있다).
     */
    fun isDuplicate(title: String, startMillis: Long, existing: List<Pair<String, Long>>): Boolean {
        val a = title.filter { it.isLetterOrDigit() }
        return existing.any { (t, s) ->
            val b = t.filter { it.isLetterOrDigit() }
            Math.abs(s - startMillis) <= Duration.ofMinutes(30).toMillis() &&
                (a == b || a.contains(b) || b.contains(a) || sharedBigrams(a, b) >= 0.5)
        }
    }

    private fun sharedBigrams(a: String, b: String): Double {
        fun grams(s: String) = s.windowed(2).toSet()
        val ga = grams(a)
        val gb = grams(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        return ga.intersect(gb).size.toDouble() / minOf(ga.size, gb.size)
    }
}
