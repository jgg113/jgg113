package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.voice.SelfSpeech
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfSpeechTest {
    @AfterTest fun reset() = SelfSpeech.clear()

    @Test
    fun audioWhileSubinSpeaksIsDropped() {
        val now = System.currentTimeMillis()
        val mark = SelfSpeech.started("오늘 오후 3시에 민수 약속이 있어요")
        assertTrue(SelfSpeech.overlaps(now - 3_000, now + 100), "말하는 중에 들어온 소리")
        SelfSpeech.finished(mark)
        assertTrue(SelfSpeech.overlaps(now + 500, now + 1_500), "말 끝난 직후 (울림)")
        assertFalse(SelfSpeech.overlaps(now - 60_000, now - 30_000), "그 전에 들은 말")
    }

    @Test
    fun echoedTextIsRecognized() {
        SelfSpeech.finished(SelfSpeech.started("안녕하세요 수빈이예요. 30분 뒤 오후 3시에 강남역에서 민수 약속 일정이 있어요."))
        // 스피커 소리를 받아쓰면 조금 틀리게 나와도 같은 말로 본다.
        assertTrue(SelfSpeech.isEcho("30분 뒤 오후 세시에 강남역에서 민수 약속 일정이 있어요"))
        assertTrue(SelfSpeech.isEcho("네 수빈이에요 무엇을 도와드릴까요"))
        assertFalse(SelfSpeech.isEcho("내일 저녁에 엄마랑 영화 보기로 했어"))
    }
}
