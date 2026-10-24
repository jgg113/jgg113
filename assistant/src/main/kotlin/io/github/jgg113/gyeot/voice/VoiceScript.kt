package io.github.jgg113.gyeot.voice

import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.ScheduleSummary
import java.time.Instant
import java.time.LocalDate

/** 전화처럼 걸려 와서 읽어 줄 말을 만든다 (비서 말투, 한국어 음성 합성용). */
object VoiceScript {

    /** "오후 3시 30분", "오전 9시", "낮 12시" */
    fun clock(millis: Long): String {
        val t = Instant.ofEpochMilli(millis).atZone(EventRules.SEOUL)
        val h = t.hour
        val half = when { h == 12 -> "낮"; h < 12 -> "오전"; else -> "오후" }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$half ${h12}시" + if (t.minute != 0) " ${t.minute}분" else ""
    }

    private fun dayWord(millis: Long, today: LocalDate): String {
        val d = Instant.ofEpochMilli(millis).atZone(EventRules.SEOUL).toLocalDate()
        return when (d) {
            today -> "오늘"
            today.plusDays(1) -> "내일"
            today.plusDays(2) -> "모레"
            else -> "${d.monthValue}월 ${d.dayOfMonth}일"
        }
    }

    fun event(title: String, startMillis: Long, allDay: Boolean, location: String, evidence: String, nowMillis: Long): String {
        val today = Instant.ofEpochMilli(nowMillis).atZone(EventRules.SEOUL).toLocalDate()
        val left = ((startMillis - nowMillis) / 60_000).toInt()
        val whenText = when {
            allDay -> "${dayWord(startMillis, today)}은"
            left <= 1 -> "지금"
            left < 60 -> "${left}분 뒤, ${clock(startMillis)}에"
            left < 180 -> "${left / 60}시간 ${if (left % 60 >= 5) "${left % 60}분 " else ""}뒤, ${clock(startMillis)}에"
            else -> "${dayWord(startMillis, today)} ${clock(startMillis)}에"
        }
        val place = if (location.isNotBlank()) "${location}에서 " else ""
        return buildString {
            append("곁비서예요. ")
            if (allDay) append("$whenText ${title}이 있는 날이에요. ") else append("$whenText ${place}${title} 일정이 있어요. ")
            if (evidence.isNotBlank() && evidence.length <= 60) append("대화에서 \"$evidence\" 라고 들었어요. ")
            if (!allDay && left in 2..90) append("늦지 않게 준비하세요.")
        }.trim()
    }

    fun briefing(items: List<ScheduleSummary.Item>, todos: List<String>, asking: Int, today: LocalDate, morning: Boolean = true): String {
        val todays = items.filter { it.day == today && it.source != "ask" }.sortedWith(compareBy<ScheduleSummary.Item> { !it.allDay }.thenBy { it.start })
        return buildString {
            append(if (morning) "좋은 아침이에요, 곁비서예요. " else "곁비서예요. ")
            if (todays.isEmpty()) append("오늘은 잡힌 일정이 없어요. ")
            else {
                append("오늘은 일정이 ${todays.size}개 있어요. ")
                val parts = todays.take(5).map { if (it.allDay) "종일 ${it.title}" else "${clock(it.start)} ${it.title}" + if (it.location.isNotBlank()) ", 장소는 ${it.location}" else "" }
                append(parts.dropLast(1).joinToString(", ")).append(if (parts.size > 1) ", 그리고 " else "").append(parts.last()).append("이에요. ")
                if (todays.size > 5) append("나머지 ${todays.size - 5}개는 화면에서 확인하세요. ")
            }
            if (todos.isNotEmpty()) append("오늘까지 할 일은 ${todos.take(3).joinToString(", ")}${if (todos.size > 3) " 외 ${todos.size - 3}개" else ""}예요. ")
            if (asking > 0) append("그리고 캘린더에 넣을지 여쭤볼 일정이 ${asking}개 있어요. 화면에서 골라 주세요.")
        }.trim()
    }
}
