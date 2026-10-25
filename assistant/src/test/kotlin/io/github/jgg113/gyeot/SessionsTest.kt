package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.ai.Sessions
import io.github.jgg113.gyeot.data.Note
import io.github.jgg113.gyeot.data.Utterance
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionsTest {
    private val m = 60_000L

    @Test
    fun splitsOnSilenceAndCalls() {
        val lines = listOf(
            Utterance(0, "a"), Utterance(2 * m, "b", speaker = "me"),
            Utterance(3 * m, "c", source = "call:엄마"), Utterance(4 * m, "d", source = "call:엄마", speaker = "other"),
            Utterance(5 * m, "e"), Utterance(20 * m, "f"),
        )
        val notes = listOf(Note(1, 4 * m, 0, "첫 대화", "normal", emptyList(), emptyList()), Note(2, 6 * m, 3 * m - 20_000, "📞 엄마 통화 (3분) · 저녁 약속", "high", emptyList(), emptyList()))
        val s = Sessions.build(lines, notes)
        assertEquals(listOf(2, 2, 1, 1), s.map { it.lines.size })
        assertEquals("엄마", s[1].callWith)
        assertEquals(listOf("첫 대화"), s[0].notes.map { it.summary })
        // 통화 노트는 통화가 시작된 때(첫 말보다 20초 앞)부터라도 통화 묶음에 붙는다.
        assertEquals(listOf("📞 엄마 통화 (3분) · 저녁 약속"), s[1].notes.map { it.summary })
        assertEquals(emptyList(), s[2].notes)
        assertEquals(1, s[0].mine)
        assertEquals(1, s[1].others)
    }

    @Test
    fun ambientNoteNeverJumpsIntoCall() {
        val lines = listOf(Utterance(10 * m, "통화", source = "call:민수"), Utterance(20 * m, "곁에서"))
        val notes = listOf(Note(1, 21 * m, 20 * m, "곁 대화 정리", "normal", emptyList(), emptyList()))
        val s = Sessions.build(lines, notes)
        assertEquals(emptyList(), s[0].notes)
        assertEquals(listOf("곁 대화 정리"), s[1].notes.map { it.summary })
    }
}
