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
        // 휴대폰에서 실제로 본 형식: "통화 이름_…", "통화 번호_…"
        assertEquals("라이더김", RecordingName.parse("통화 라이더김_261025_145100.m4a").who)
        assertEquals("010-9011-8081", RecordingName.parse("통화 01090118081_261025_150800.m4a").who)
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
        val start = ms(2026, 10, 25, 15, 0)
        assertEquals("수빈이예요! 30분 뒤 오후 3시, 민수 약속이에요.", VoiceScript.event("민수 약속", start, false, "강남역", "", now, detailed = false))
        val long = VoiceScript.event("민수 약속", start, false, "강남역", "내일 세 시에 강남역에서 민수랑 만나기로 했어", now, detailed = true)
        assertEquals("안녕하세요, 수빈이예요! 30분 뒤 오후 3시에 강남역에서 민수 약속 일정이 있어요. 전에 \"내일 세 시에 강남역에서 민수랑 만나기로 했어\" 라고 하셨거든요. 지금 준비하시면 딱 좋아요! 더 필요한 거 있으면 말씀해 주세요.", long)
        assertEquals("수빈이예요! 내일은 엄마 생신 날이에요.", VoiceScript.event("엄마 생신", ms(2026, 10, 26, 0, 0), true, "", "", now, detailed = false))
        assertEquals("오전 9시 5분", VoiceScript.clock(ms(2026, 10, 25, 9, 5)))
        assertEquals("낮 12시", VoiceScript.clock(ms(2026, 10, 25, 12, 0)))
        assertEquals("이에요", VoiceScript.ieyo("회식"))
        assertEquals("예요", VoiceScript.ieyo("치과"))
    }

    @Test
    fun briefingSpeech() {
        val today = LocalDate.of(2026, 10, 24)
        val items = listOf(
            ScheduleSummary.Item("팀 회의", ms(2026, 10, 24, 10, 0), ms(2026, 10, 24, 11, 0), false),
            ScheduleSummary.Item("민수 약속", ms(2026, 10, 24, 15, 0), ms(2026, 10, 24, 16, 0), false, "강남역"),
        )
        assertEquals("좋은 아침이에요! 수빈이예요. 오늘 일정은 2개예요. 첫 일정은 오전 10시 팀 회의예요. 할 일 1개, 여쭤볼 일정 1개가 있어요.",
            VoiceScript.briefing(items, listOf("보고서 보내기"), 1, today))
        val full = VoiceScript.briefing(items, listOf("보고서 보내기"), 1, today, detailed = true,
            tomorrow = listOf(ScheduleSummary.Item("엄마 생신", ms(2026, 10, 25, 0, 0), ms(2026, 10, 26, 0, 0), true)))
        assertEquals("좋은 아침이에요! 수빈이예요. 오늘은 일정이 2개 있어요. 오전 10시 팀 회의, 그리고 오후 3시 민수 약속, 장소는 강남역이에요. 오늘까지 할 일은 보고서 보내기예요. 참, 내일은 종일 엄마 생신 일정이 있어요. 그리고 캘린더에 넣을지 여쭤볼 일정이 1개 있어요. 오늘도 화이팅이에요!", full)
        assertEquals("좋은 아침이에요! 수빈이예요. 오늘은 잡힌 일정이 없어요.", VoiceScript.briefing(emptyList(), emptyList(), 0, today))
    }
}
