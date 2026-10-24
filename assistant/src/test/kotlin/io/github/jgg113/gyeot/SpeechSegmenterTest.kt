package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.audio.SpeechSegmenter
import io.github.jgg113.gyeot.audio.Wav
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechSegmenterTest {
    private val seg = SpeechSegmenter()
    private val n = seg.frameSamples

    private fun quiet() = ShortArray(n) { ((it % 7) - 3).toShort() }
    private fun voice(i: Int) = ShortArray(n) { (4000 * sin(2 * PI * 220 * (i * n + it) / 16000.0)).toInt().toShort() }

    private fun feed(frames: List<ShortArray>): List<ShortArray> = frames.mapNotNull { seg.push(it) }

    @Test
    fun silenceProducesNothing() {
        assertTrue(feed(List(500) { quiet() }).isEmpty())
    }

    @Test
    fun speechThenSilenceProducesOneSegmentWithPreRoll() {
        val frames = List(50) { quiet() } + List(50) { voice(it) } + List(80) { quiet() } // 1초 말, 1.6초 조용
        val out = feed(frames)
        assertEquals(1, out.size)
        // 말 1초 + 앞 0.3초 + 끝 침묵 1.2초 안쪽
        val ms = out[0].size * 1000 / 16000
        assertTrue(ms in 1_200..2_600, "구간 길이 $ms ms")
    }

    @Test
    fun tooShortNoiseIsDropped() {
        val out = feed(List(50) { quiet() } + List(10) { voice(it) } + List(80) { quiet() }) // 0.2초 소리
        assertTrue(out.isEmpty())
    }

    @Test
    fun longSpeechIsCutAtMax() {
        val out = feed(List(2_000) { voice(it) }) // 40초 계속 말함
        assertTrue(out.isNotEmpty())
        assertTrue(out[0].size <= 16_000 * 30 + n)
    }

    @Test
    fun flushReturnsOpenSegment() {
        feed(List(50) { voice(it) })
        assertNotNull(seg.flush())
        assertNull(seg.flush())
    }

    @Test
    fun wavHeaderAndJoin() {
        val joined = Wav.join(listOf(ShortArray(100), ShortArray(50)), gapMs = 10)
        assertEquals(100 + 160 + 50, joined.size)
        val wav = Wav.encode(ShortArray(10))
        assertEquals(44 + 20, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
    }
}
