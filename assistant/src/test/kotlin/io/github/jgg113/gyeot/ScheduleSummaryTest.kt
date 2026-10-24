package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.ScheduleSummary
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleSummaryTest {
    private val now = ZonedDateTime.of(2026, 10, 24, 8, 0, 0, 0, EventRules.SEOUL) // 토요일
    private val today = now.toLocalDate()
    private fun at(day: LocalDate, h: Int, m: Int = 0) = day.atTime(h, m).atZone(EventRules.SEOUL).toInstant().toEpochMilli()

    private val items = listOf(
        ScheduleSummary.Item("민수 약속", at(today, 15), at(today, 16), false, "강남역"),
        ScheduleSummary.Item("헬스", at(today, 7), at(today, 8), false, source = "app"),
        ScheduleSummary.Item("엄마 생신", at(today.plusDays(1), 0), at(today.plusDays(2), 0), true),
        ScheduleSummary.Item("영화", at(today.plusDays(1), 19), at(today.plusDays(1), 21), false, source = "ask"),
    )

    @Test
    fun groupsByDay() {
        val t = ScheduleSummary.text(items, today, today.plusDays(1), today)
        assertTrue(t.startsWith("오늘 10/24(토)\n  · 07:00 헬스 🔔\n  · 15:00 민수 약속 · 강남역"), t)
        assertTrue(t.contains("내일 10/25(일)\n  · 종일 엄마 생신\n  · 19:00 영화 (추가할지 물어보는 중)"), t)
        assertEquals("오늘 10/24(토) 일정이 없어요.", ScheduleSummary.text(emptyList(), today, today, today))
    }

    @Test
    fun headline() {
        assertEquals("오늘 일정 2개 · 07:00 헬스 🔔", ScheduleSummary.headline(items, today))
    }

    @Test
    fun questionRanges() {
        assertTrue(ScheduleSummary.isScheduleQuestion("이번 주 일정 뭐 있어?"))
        assertTrue(!ScheduleSummary.isScheduleQuestion("팀장님이 보고서 언제까지라고 했지"))
        assertEquals(today to LocalDate.of(2026, 10, 25), ScheduleSummary.range("이번 주 일정", now))
        assertEquals(LocalDate.of(2026, 10, 26) to LocalDate.of(2026, 11, 1), ScheduleSummary.range("다음 주 스케줄 알려줘", now))
        assertEquals(LocalDate.of(2026, 10, 25) to LocalDate.of(2026, 10, 25), ScheduleSummary.range("내일 약속 있어?", now))
        assertEquals(LocalDate.of(2026, 10, 29) to LocalDate.of(2026, 10, 29), ScheduleSummary.range("목요일에 뭐 있어", now))
        assertEquals(today to today.plusDays(6), ScheduleSummary.range("일정 알려줘", now))
    }
}
