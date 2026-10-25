package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.audio.Mic
import io.github.jgg113.gyeot.ui.koClock
import io.github.jgg113.gyeot.ui.koDuration
import io.github.jgg113.gyeot.ui.koSpan
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryViewTest {
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 25, h, m).atZone(EventRules.SEOUL).toInstant().toEpochMilli()

    @Test
    fun koreanTimes() {
        assertEquals("오후 2:05", koClock(at(14, 5)))
        assertEquals("오전 12:30", koClock(at(0, 30)))
        assertEquals("오후 2:05 ~ 2:40", koSpan(at(14, 5), at(14, 40)))
        assertEquals("오전 11:50 ~ 오후 12:10", koSpan(at(11, 50), at(12, 10)))
        assertEquals("25분", koDuration(25 * 60_000L))
        assertEquals("1시간 10분", koDuration(70 * 60_000L))
    }

    @Test
    fun trimsSilenceAroundVoice() {
        val rate = 16_000
        val pcm = ShortArray(rate * 4)
        // 1.5초~2.5초에만 소리
        for (i in (rate * 3 / 2) until (rate * 5 / 2)) pcm[i] = if (i % 2 == 0) 3000 else -3000
        val trimmed = Mic.trimSilence(pcm)
        // 말한 1초 + 앞뒤 여유(각 0.2초 안팎)만 남는다.
        assertTrue(trimmed.size in (rate..(rate * 3 / 2)), "남은 길이 ${trimmed.size}")
    }
}
