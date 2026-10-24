package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.KoreanText
import io.github.jgg113.gyeot.ai.RuleBrain
import io.github.jgg113.gyeot.data.Utterance
import io.github.jgg113.gyeot.voice.VoiceCommands
import io.github.jgg113.gyeot.voice.VoiceCommands.Cmd
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceAndTextTest {
    private val now = ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, EventRules.SEOUL)

    @Test
    fun brokenModelTextIsRepaired() {
        // 휴대폰 화면에 나왔던 깨진 글자: 바이트 조각으로 나온 한국어
        assertEquals("집으로 빠르게", KoreanText.fixByteLevel("집으로Ġë¹ł르게"))
        assertEquals("그대로 둠", KoreanText.fixByteLevel("그대로 둠"))
        assertFalse(KoreanText.isCleanKorean("나에게 집으로 오라고 吩咐했다"))
        assertFalse(KoreanText.isCleanKorean("오타가 안부상을Gë 었습니다"))
        assertTrue(KoreanText.isCleanKorean("민수랑 내일 3시에 만나기로 했어요"))
    }

    @Test
    fun tidyRemovesFillersAndFalseStarts() {
        assertEquals("토닉워터 사와야 돼", KoreanText.tidy("어 토 아니 토닉워터 사와야 돼"))
        assertEquals("010-9011-8081", KoreanText.phone("01090118081"))
    }

    @Test
    fun callTodosAreShortAndClean() {
        val brain = RuleBrain()
        fun todos(t: String) = brain.analyze(emptyList(), listOf(Utterance(now.toInstant().toEpochMilli(), t)), emptyList(), now).todos.map { it.text }
        assertEquals(listOf("토닉워터 사와야 돼"), todos("왜 또 거기까지 갔어 어 토 아니 토닉워터 사와야 돼 그럼"))
        assertTrue(todos("와야 돼 어 너 어디야").isEmpty())
        assertEquals(listOf("보고서 보내 주세요"), todos("보고서 금요일까지 보내 주세요"))
    }

    @Test
    fun voiceCommands() {
        fun p(t: String, asking: Boolean = false) = VoiceCommands.parse(t, asking, now)
        assertEquals(Cmd.Snooze(10), p("10분 뒤에 다시 알려줘"))
        assertEquals(Cmd.Snooze(30), p("삼십 분 있다가"))
        assertEquals(Cmd.Snooze(10), p("나중에 다시 해 줘"))
        assertTrue(p("30분 뒤에 빨래 꺼내라고 알려 줘") is Cmd.NewEvent)
        assertEquals(Cmd.Repeat, p("뭐라고?"))
        assertEquals(Cmd.Detailed, p("자세히 말해 줘"))
        assertEquals(Cmd.Brief, p("짧게 해 줘"))
        assertEquals(Cmd.Yes, p("응 넣어줘", asking = true))
        assertEquals(Cmd.No, p("아니 필요 없어", asking = true))
        assertEquals(Cmd.RemindOnly, p("알림만 해 줘", asking = true))
        assertTrue(p("내일 오후 세 시에 치과 일정 넣어 줘") is Cmd.NewEvent)
        assertTrue(p("이번 주 일정 뭐 있어") is Cmd.Schedule)
        assertTrue(p("내일 일정 알려줘") is Cmd.Schedule)
        assertEquals(Cmd.End, p("고마워"))
        assertEquals(Cmd.End, p("응 알겠어"))
        assertTrue(p("팀장님이 보고서 언제까지라고 했지") is Cmd.Ask)
    }
}
