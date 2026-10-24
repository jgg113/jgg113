package io.github.jgg113.gyeot.ai

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 일정 요약: 삼성 캘린더(휴대폰의 모든 캘린더) 일정 + 앱 알림만 맞춘 일정 + 물어보는 중인 일정을
 * 날짜별로 묶어 짧게 보여 준다. 아침 브리핑·오늘 탭·묻기 탭이 함께 쓴다.
 */
object ScheduleSummary {

    data class Item(
        val title: String,
        /** 시작 시각(한국 시간 기준 epoch ms). 종일 일정은 그날 0시. */
        val start: Long,
        val end: Long,
        val allDay: Boolean,
        val location: String = "",
        /** calendar · app(알림만) · ask(추가할지 묻는 중) */
        val source: String = "calendar",
    ) {
        val day: LocalDate get() = Instant.ofEpochMilli(start).atZone(EventRules.SEOUL).toLocalDate()
    }

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val MD = DateTimeFormatter.ofPattern("M/d")

    fun dayLabel(day: LocalDate, today: LocalDate): String {
        val name = when (day) {
            today -> "오늘"
            today.plusDays(1) -> "내일"
            today.plusDays(2) -> "모레"
            else -> null
        }
        val dow = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        return listOfNotNull(name, "${day.format(MD)}($dow)").joinToString(" ")
    }

    fun line(i: Item): String {
        val t = if (i.allDay) "종일" else Instant.ofEpochMilli(i.start).atZone(EventRules.SEOUL).format(HM)
        val mark = when (i.source) { "app" -> " 🔔"; "ask" -> " (추가할지 물어보는 중)"; else -> "" }
        return "$t ${i.title}" + (if (i.location.isNotBlank()) " · ${i.location}" else "") + mark
    }

    /** 기간 안의 일정을 날짜별로: "오늘 10/24(토)\n  · 15:00 민수 약속 · 강남역" */
    fun text(items: List<Item>, from: LocalDate, to: LocalDate, today: LocalDate): String {
        val inRange = items.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
            .sortedWith(compareBy<Item> { it.day }.thenBy { !it.allDay }.thenBy { it.start })
        if (inRange.isEmpty()) return "${rangeLabel(from, to, today)} 일정이 없어요."
        return inRange.groupBy { it.day }.entries.joinToString("\n") { (day, list) ->
            dayLabel(day, today) + "\n" + list.joinToString("\n") { "  · " + line(it) }
        }
    }

    /** 브리핑 첫 줄: "오늘 일정 3개 · 첫 일정 10:00 팀 회의" */
    fun headline(items: List<Item>, today: LocalDate): String {
        val todays = items.filter { it.day == today && it.source != "ask" }.sortedWith(compareBy<Item> { !it.allDay }.thenBy { it.start })
        if (todays.isEmpty()) return "오늘은 잡힌 일정이 없어요"
        val first = todays.firstOrNull { !it.allDay } ?: todays.first()
        return "오늘 일정 ${todays.size}개 · ${line(first)}"
    }

    fun rangeLabel(from: LocalDate, to: LocalDate, today: LocalDate): String = when {
        from == to -> dayLabel(from, today)
        else -> "${dayLabel(from, today)}~${dayLabel(to, today)}"
    }

    private val ASK_WORDS = Regex("일정|약속|스케줄|스케쥴|계획|뭐\\s*있|무슨\\s*날|바빠|한가|비어|시간\\s*있")

    fun isScheduleQuestion(q: String) = ASK_WORDS.containsMatchIn(q)

    /** 질문 속 기간: 오늘·내일·모레·이번 주·다음 주·주말·이번 달, 날짜 하나 ("목요일 일정"), 없으면 오늘부터 7일. */
    fun range(q: String, now: ZonedDateTime): Pair<LocalDate, LocalDate> {
        val today = now.toLocalDate()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when {
            q.contains("다음 주") || q.contains("다음주") || q.contains("담주") ->
                if (Regex("[월화수목금토일]요일").containsMatchIn(q)) single(q, now) else monday.plusWeeks(1) to monday.plusWeeks(1).plusDays(6)
            q.contains("주말") -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).let { it to it.plusDays(1) }
            q.contains("이번 주") || q.contains("이번주") || q.contains("이 주") -> today to monday.plusDays(6)
            q.contains("이번 달") || q.contains("이번달") || q.contains("이달") -> today to today.with(TemporalAdjusters.lastDayOfMonth())
            else -> single(q, now)
        }
    }

    private fun single(q: String, now: ZonedDateTime): Pair<LocalDate, LocalDate> {
        val f = KoreanTime.parse(q, now)
        val d = f.date
        return if (d != null && f.dateExplicit) d to d else now.toLocalDate() to now.toLocalDate().plusDays(6)
    }
}
