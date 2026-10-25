package io.github.jgg113.gyeot.ui

import io.github.jgg113.gyeot.ai.EventRules
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

/** "14:05" */
internal fun clock(at: Long): String = Instant.ofEpochMilli(at).atZone(EventRules.SEOUL).format(HM)

/** "오후 2:05" */
internal fun koClock(at: Long): String {
    val t = Instant.ofEpochMilli(at).atZone(EventRules.SEOUL)
    val h = t.hour % 12
    return "${if (t.hour < 12) "오전" else "오후"} ${if (h == 0) 12 else h}:${"%02d".format(t.minute)}"
}

/** "오후 2:05 ~ 2:40" (오전·오후가 같으면 뒤쪽은 생략) */
internal fun koSpan(from: Long, to: Long): String {
    if (to - from < 60_000) return koClock(from)
    val a = koClock(from)
    val b = koClock(to)
    return if (a.take(2) == b.take(2)) "$a ~ ${b.drop(3)}" else "$a ~ $b"
}

/** "25분", "1시간 10분" */
internal fun koDuration(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return if (m < 60) "${m}분" else "${m / 60}시간" + if (m % 60 > 0) " ${m % 60}분" else ""
}

/** "오늘" · "어제" · "10월 23일 (목)" */
internal fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now(EventRules.SEOUL)): String = when (day) {
    today -> "오늘"
    today.minusDays(1) -> "어제"
    else -> day.format(DAY)
}

internal fun longDay(day: LocalDate): String = day.format(DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN))
