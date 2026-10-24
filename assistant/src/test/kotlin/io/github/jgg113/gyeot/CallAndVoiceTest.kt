package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.ScheduleSummary
import io.github.jgg113.gyeot.calls.MonoResampler
import io.github.jgg113.gyeot.calls.RecordingName
import io.github.jgg113.gyeot.voice.VoiceScript
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallAndVoiceTest {
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, EventRules.SEOUL).toInstant().toEpochMilli()

    @Test
    fun samsungRecordingNames() {
        val a = RecordingName.parse("통화 녹음 엄마_241024_153012.m4a")
        assertEquals("엄마", a.who)
        assertEquals(ms(2024, 10, 24, 15, 30, 12), a.startMillis)
        assertEquals("김민수", RecordingName.parse("Call recording 김민수_261024_090000.m4a").who)
        assertEquals("010-1234-5678", RecordingName.parse("010-1234-5678_261024_090000.m4a").who)
        assertEquals("홍길동 팀장", RecordingName.parse("통화 녹음 홍길동 팀장_261024_090000(1).m4a").who)
        assertNull(RecordingName.parse("메모.m4a").startMillis)
        assertTrue(RecordingName.isCallFolder("Recordings/Call/"))
        assertTrue(RecordingName.isCallFolder("Call/"))
        assertTrue(!RecordingName.isCallFolder("Music/"))
    }

    @Test
    fun resamplesStereo48kToMono16k() {
        val r = MonoResampler(48_000, 2)
        // 1초 분량을 0.1초 조각 10개로 나눠 넣는다: 왼쪽 1000, 오른쪽 3000 → 모노 2000
        var total = 0
        var last = 0
        repeat(10) {
            val chunk = ShortArray(4_800 * 2) { i -> if (i % 2 == 0) 1000 else 3000 }
            val out = r.feed(chunk)
            total += out.size
            last = out.last().toInt()
        }
        assertTrue(abs(total - 16_000) <= 2, "샘플 수 $total")
        assertEquals(2000, last)
        val up = MonoResampler(8_000, 1).feed(ShortArray(8_000) { 500 })
        assertTrue(abs(up.size - 16_000) <= 2)
    }

    @Test
    fun eventSpeech() {
        val now = ms(2026, 10, 25, 14, 30)
        val t = VoiceScript.event("민수 약속", ms(2026, 10, 25, 15, 0), false, "강남역", "내일 세 시에 강남역에서 민수랑 만나기로 했어", now)
        assertEquals("곁비서예요. 30분 뒤, 오후 3시에 강남역에서 민수 약속 일정이 있어요. 대화에서 \"내일 세 시에 강남역에서 민수랑 만나기로 했어\" 라고 들었어요. 늦지 않게 준비하세요.", t)
        assertEquals("오전 9시 5분", VoiceScript.clock(ms(2026, 10, 25, 9, 5)))
        assertEquals("낮 12시", VoiceScript.clock(ms(2026, 10, 25, 12, 0)))
    }

    @Test
    fun briefingSpeech() {
        val today = LocalDate.of(2026, 10, 24)
        val items = listOf(
            ScheduleSummary.Item("팀 회의", ms(2026, 10, 24, 10, 0), ms(2026, 10, 24, 11, 0), false),
            ScheduleSummary.Item("민수 약속", ms(2026, 10, 24, 15, 0), ms(2026, 10, 24, 16, 0), false, "강남역"),
        )
        val t = VoiceScript.briefing(items, listOf("보고서 보내기"), 1, today)
        assertEquals("좋은 아침이에요, 곁비서예요. 오늘은 일정이 2개 있어요. 오전 10시 팀 회의, 그리고 오후 3시 민수 약속, 장소는 강남역이에요. 오늘까지 할 일은 보고서 보내기예요. 그리고 캘린더에 넣을지 여쭤볼 일정이 1개 있어요. 화면에서 골라 주세요.", t)
        assertEquals("좋은 아침이에요, 곁비서예요. 오늘은 잡힌 일정이 없어요.", VoiceScript.briefing(emptyList(), emptyList(), 0, today))
    }
}
