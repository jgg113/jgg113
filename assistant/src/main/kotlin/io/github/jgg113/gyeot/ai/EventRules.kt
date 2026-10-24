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
    const val MIN_CONFIDENCE = 0.6

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

    fun shouldRegister(e: ExtractedEvent, timing: Timing, now: ZonedDateTime): Boolean {
        if (e.title.isBlank() || e.confidence < MIN_CONFIDENCE) return false
        // 종일 일정은 오늘 것까지, 시각이 있는 일정은 아직 안 지난 것만.
        return if (timing.allDay) !timing.start.toLocalDate().isBefore(now.toLocalDate())
        else timing.start.isAfter(now)
    }

    private fun sharedBigrams(a: String, b: String): Double {
        fun grams(s: String) = s.windowed(2).toSet()
        val ga = grams(a)
        val gb = grams(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        return ga.intersect(gb).size.toDouble() / minOf(ga.size, gb.size)
    }
}
