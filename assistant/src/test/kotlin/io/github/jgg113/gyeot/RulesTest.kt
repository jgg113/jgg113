package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.ExtractedEvent
import io.github.jgg113.gyeot.ai.ScheduleHints
import io.github.jgg113.gyeot.stt.TranscriptFilter
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesTest {
    private val now = ZonedDateTime.of(2026, 10, 24, 15, 0, 0, 0, EventRules.SEOUL)

    @Test
    fun timedEventDefaultsToOneHour() {
        val t = assertNotNull(EventRules.timing(ExtractedEvent("치과", "2026-10-25T15:00", confidence = 0.9)))
        assertFalse(t.allDay)
        assertEquals(16, t.end.hour)
    }

    @Test
    fun dateOnlyIsAllDay() {
        val t = assertNotNull(EventRules.timing(ExtractedEvent("엄마 생신", "2026-10-30", confidence = 0.9)))
        assertTrue(t.allDay)
        assertEquals(0, t.start.hour)
    }

    @Test
    fun badDateIsIgnored() {
        assertNull(EventRules.timing(ExtractedEvent("x", "다음주")))
    }

    @Test
    fun pastOrUnsureEventsAreNotRegistered() {
        val past = ExtractedEvent("회의", "2026-10-24T10:00", confidence = 0.9)
        fun d(e: ExtractedEvent, mode: String = EventRules.MODE_SMART) = EventRules.decide(e, EventRules.timing(e)!!, now, mode)
        assertEquals(EventRules.Decision.DROP, d(past))
        val vague = ExtractedEvent("밥 한번", "2026-10-30T12:00", confidence = 0.3)
        assertEquals(EventRules.Decision.DROP, d(vague))
        val today = ExtractedEvent("택배", "2026-10-24", confidence = 0.8)
        assertEquals(EventRules.Decision.REGISTER, d(today))
        // 애매하면 묻고, '늘 묻기'면 확실해도 묻는다. 시각을 모르면 어느 모드든 묻는다.
        val maybe = ExtractedEvent("영화", "2026-10-31T19:00", confidence = 0.55)
        assertEquals(EventRules.Decision.SUGGEST, d(maybe))
        assertEquals(EventRules.Decision.SUGGEST, d(today, EventRules.MODE_ASK))
        assertEquals(EventRules.Decision.SUGGEST, d(ExtractedEvent("치과", "2026-10-25T09:00", confidence = 0.9, timeUnknown = true), EventRules.MODE_AUTO))
        assertEquals(EventRules.Decision.REGISTER, d(maybe.copy(confidence = 0.65), EventRules.MODE_AUTO))
    }

    @Test
    fun duplicatesByTitleAndTime() {
        val start = EventRules.timing(ExtractedEvent("치과 예약", "2026-10-25T15:00"))!!.start.toInstant().toEpochMilli()
        val existing = listOf("치과" to start + 10 * 60_000)
        assertTrue(EventRules.isDuplicate("치과 예약", start, existing))
        assertFalse(EventRules.isDuplicate("팀 회식", start, existing))
        assertFalse(EventRules.isDuplicate("치과", start + 5 * 60 * 60_000, existing))
    }

    @Test
    fun hallucinationsAreFiltered() {
        assertNull(TranscriptFilter.clean("시청해주셔서 감사합니다."))
        assertNull(TranscriptFilter.clean("네 네 네 네 네 네 네"))
        assertNull(TranscriptFilter.clean(" . "))
        assertEquals("내일 3시에 보자", TranscriptFilter.clean("  내일   3시에 보자 "))
    }

    @Test
    fun scheduleHints() {
        assertTrue(ScheduleHints.mentionsSchedule("다섯 시 반에 만나"))
        assertTrue(ScheduleHints.mentionsSchedule("11월 3일 결혼식"))
        assertTrue(ScheduleHints.mentionsSchedule("금요일까지 보내 주세요"))
        assertFalse(ScheduleHints.mentionsSchedule("그 영화 진짜 재밌더라"))
    }
}
