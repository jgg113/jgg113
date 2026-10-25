package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.audio.Gain
import io.github.jgg113.gyeot.stt.Corrections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecognitionTest {
    private fun tone(amp: Double, n: Int = 16_000) = ShortArray(n) { (amp * 32767 * sin(2 * PI * 200 * it / 16000.0)).toInt().toShort() }
    private fun rms(p: ShortArray) = sqrt(p.sumOf { it.toDouble() * it } / p.size) / 32768

    @Test
    fun quietSpeechIsBoosted() {
        // 2m 떨어진 말소리 정도 (-54dB): 키우지 않으면 휴대폰 안 모델이 아무것도 못 받아쓴다.
        val quiet = tone(0.003)
        val out = Gain.normalize(quiet)
        assertTrue(rms(out) > rms(quiet) * 10, "rms ${rms(out)}")
        assertTrue(out.maxOf { abs(it.toInt()) } <= 32767)
    }

    @Test
    fun loudSpeechIsLeftAlone() {
        val loud = tone(0.5)
        assertEquals(loud.toList(), Gain.normalize(loud).toList())
        // 최고점이 찌그러지지 않게: 짧은 큰 소리가 섞인 작은 소리는 최고점 기준으로만 키운다.
        val mixed = tone(0.002).also { it[100] = 30000 }
        assertTrue(Gain.normalize(mixed).maxOf { abs(it.toInt()) } <= 31130)
    }

    @Test
    fun correctionRules() {
        val rules = Corrections.parse("민스=민수\n토닉 워터=토닉워터\n잘못된줄\n=빈칸")
        assertEquals(2, rules.size)
        assertEquals("민수랑 토닉워터 사러 가", Corrections.apply("민스랑 토닉 워터 사러 가", rules))
        assertEquals("민수랑 토닉워터 사러 가", Corrections.apply("민스랑 토닉워터 사러 가", rules))
    }

    @Test
    fun learnsFromUserEdit() {
        assertEquals(listOf(Corrections.Rule("민스랑", "민수랑")), Corrections.learn("내일 민스랑 만나", "내일 민수랑 만나"))
        assertEquals(listOf(Corrections.Rule("강 남역", "강남역")), Corrections.learn("내일 강 남역 앞에서", "내일 강남역 앞에서"))
        assertTrue(Corrections.learn("같은 말", "같은 말").isEmpty())
    }

    @Test
    fun foreignScriptIsDropped() {
        assertEquals(null, io.github.jgg113.gyeot.stt.TranscriptFilter.clean("千九百四十年 八"))
        assertEquals("내일 회의 있어", io.github.jgg113.gyeot.stt.TranscriptFilter.clean("내일 会议 회의 있어"))
    }
}
