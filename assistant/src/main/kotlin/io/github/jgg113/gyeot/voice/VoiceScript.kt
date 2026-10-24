package io.github.jgg113.gyeot.voice

import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.ScheduleSummary
import java.time.Instant
import java.time.LocalDate

/**
 * 수빈이가 전화로 읽어 줄 말 (밝고 명랑한 비서 말투). 간단히/자세히 두 가지 길이.
 * 음성 합성은 문장부호에서 쉬므로 짧은 문장으로 끊어 쓴다.
 */
object VoiceScript {
    const val NAME = "수빈이"
    const val CALLER = "클로드 수빈이"

    /** "오후 3시 30분", "오전 9시", "낮 12시" */
    fun clock(millis: Long): String {
        val t = Instant.ofEpochMilli(millis).atZone(EventRules.SEOUL)
        val h = t.hour
        val half = when { h == 12 -> "낮"; h < 12 -> "오전"; else -> "오후" }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$half ${h12}시" + if (t.minute != 0) " ${t.minute}분" else ""
    }

    fun dayWord(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "오늘"
        today.plusDays(1) -> "내일"
        today.plusDays(2) -> "모레"
        else -> "${day.monthValue}월 ${day.dayOfMonth}일 ${day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.KOREAN)}"
    }

    private fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(EventRules.SEOUL).toLocalDate()

    /** 받침이 있으면 "이에요", 없으면 "예요". */
    fun ieyo(word: String): String {
        val last = word.trimEnd().lastOrNull() ?: return "예요"
        if (last !in '가'..'힣') return if (last.isDigit()) "이에요" else "예요"
        return if ((last - '가') % 28 != 0) "이에요" else "예요"
    }

    fun event(title: String, startMillis: Long, allDay: Boolean, location: String, evidence: String, nowMillis: Long, detailed: Boolean): String {
        val today = day(nowMillis)
        val left = ((startMillis - nowMillis) / 60_000).toInt()
        val whenText = when {
            allDay -> dayWord(day(startMillis), today)
            left <= 1 -> "지금"
            left < 60 -> "${left}분 뒤 ${clock(startMillis)}"
            left < 180 -> "${left / 60}시간 ${if (left % 60 >= 5) "${left % 60}분 " else ""}뒤 ${clock(startMillis)}"
            else -> "${dayWord(day(startMillis), today)} ${clock(startMillis)}"
        }
        if (!detailed) {
            return if (allDay) "$NAME${ieyo(NAME)}! ${whenText}은 $title 날이에요."
            else "$NAME${ieyo(NAME)}! $whenText, $title${ieyo(title)}."
        }
        return buildString {
            append("안녕하세요, $NAME${ieyo(NAME)}! ")
            if (allDay) append("${whenText}은 $title 날이에요. ")
            else append("$whenText${if (whenText == "지금") "" else "에"} ${if (location.isNotBlank()) "${location}에서 " else ""}$title 일정이 있어요. ")
            if (evidence.isNotBlank() && evidence.length <= 70) append("전에 \"$evidence\" 라고 하셨거든요. ")
            if (!allDay && left in 2..90) append("지금 준비하시면 딱 좋아요! ")
            append("더 필요한 거 있으면 말씀해 주세요.")
        }.trim()
    }

    fun briefing(
        items: List<ScheduleSummary.Item>, todos: List<String>, asking: Int, today: LocalDate,
        morning: Boolean = true, detailed: Boolean = false, tomorrow: List<ScheduleSummary.Item> = emptyList(),
    ): String {
        val todays = items.filter { it.day == today && it.source != "ask" }.sortedWith(compareBy<ScheduleSummary.Item> { !it.allDay }.thenBy { it.start })
        val hello = if (morning) "좋은 아침이에요! $NAME${ieyo(NAME)}. " else "$NAME${ieyo(NAME)}! "
        fun item(it: ScheduleSummary.Item, withPlace: Boolean) =
            (if (it.allDay) "종일 ${it.title}" else "${clock(it.start)} ${it.title}") + if (withPlace && it.location.isNotBlank()) ", 장소는 ${it.location}" else ""
        if (!detailed) return buildString {
            append(hello)
            if (todays.isEmpty()) append("오늘은 잡힌 일정이 없어요. ")
            else {
                val first = todays.firstOrNull { !it.allDay } ?: todays.first()
                append("오늘 일정은 ${todays.size}개예요. ")
                append("${if (todays.size > 1) "첫 일정은 " else ""}${item(first, false)}${ieyo(first.title)}. ")
            }
            val extra = listOfNotNull(todos.size.takeIf { it > 0 }?.let { "할 일 ${it}개" }, asking.takeIf { it > 0 }?.let { "여쭤볼 일정 ${it}개" })
            if (extra.isNotEmpty()) append(extra.joinToString(", ") + "가 있어요.")
        }.trim()
        return buildString {
            append(hello)
            if (todays.isEmpty()) append("오늘은 잡힌 일정이 없어요. 여유로운 하루 보내세요! ")
            else {
                append("오늘은 일정이 ${todays.size}개 있어요. ")
                val parts = todays.take(6).map { item(it, true) }
                append(parts.dropLast(1).joinToString(", ")).append(if (parts.size > 1) ", 그리고 " else "").append(parts.last()).append(ieyo(parts.last())).append(". ")
                if (todays.size > 6) append("나머지 ${todays.size - 6}개는 화면에서 확인해 주세요. ")
            }
            if (todos.isNotEmpty()) append("오늘까지 할 일은 ${todos.take(3).joinToString(", ")}${if (todos.size > 3) " 외 ${todos.size - 3}개" else ""}${ieyo(todos.take(3).last())}. ")
            val tmr = tomorrow.filter { it.source != "ask" }
            if (tmr.isNotEmpty()) append("참, 내일은 ${tmr.take(2).joinToString(", ") { item(it, false) }}${if (tmr.size > 2) " 등 ${tmr.size}개" else ""} 일정이 있어요. ")
            if (asking > 0) append("그리고 캘린더에 넣을지 여쭤볼 일정이 ${asking}개 있어요. ")
            append(if (morning) "오늘도 화이팅이에요!" else "도움이 필요하면 불러 주세요!")
        }.trim()
    }

    /** "이번 주 일정 뭐 있어?" 에 대한 말 대답. */
    fun schedule(items: List<ScheduleSummary.Item>, from: LocalDate, to: LocalDate, today: LocalDate): String {
        val list = items.filter { it.source != "ask" && !it.day.isBefore(from) && !it.day.isAfter(to) }
            .sortedWith(compareBy<ScheduleSummary.Item> { it.day }.thenBy { !it.allDay }.thenBy { it.start })
        val range = if (from == to) dayWord(from, today) else "${dayWord(from, today)}부터 ${dayWord(to, today)}까지"
        if (list.isEmpty()) return "${range}는 잡힌 일정이 없어요."
        val parts = list.take(8).map { (if (from == to) "" else dayWord(it.day, today) + " ") + (if (it.allDay) "종일 " else clock(it.start) + " ") + it.title }
        return "$range 일정은 ${list.size}개예요. " + parts.joinToString(", ") + (if (list.size > 8) " 등이 있어요." else "${ieyo(parts.last())}.")
    }

    fun askToAdd(title: String, whenText: String?): String =
        if (whenText == null) "그리고 '$title', 언제 할지 정하면 챙겨 드릴까요? 내일 아침에 알려 드릴까요?"
        else "그리고 $whenText '$title', 캘린더에 넣어 드릴까요?"

    fun greeting() = "네, $NAME${ieyo(NAME)}! 무엇을 도와드릴까요?"
    fun farewell() = "네, 좋은 하루 보내세요! 필요하면 언제든 불러 주세요."
    fun notHeard() = "잘 못 들었어요. 한 번만 더 말씀해 주시겠어요?"
}
