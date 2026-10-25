package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.RuleBrain
import io.github.jgg113.gyeot.data.Utterance
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 실제 통화(2026-10-25 일요일 15:37, "다음 주 화요일 12~1시에 보자, 회의 끝나고 연락할게")를 정리해 본다. */
class CallPlanTest {
    private val who = "call:한빛 김민준 팀장"
    private fun at(h: Int, m: Int, s: Int) = LocalDateTime.of(2026, 10, 25, h, m, s).atZone(EventRules.SEOUL).toInstant().toEpochMilli()
    private val now = LocalDateTime.of(2026, 10, 25, 15, 40).atZone(EventRules.SEOUL)

    private val call = listOf(
        Utterance(at(15, 37, 50), "어 사역이 좀 늦게 끝나가지고 오늘 이따가 내가 픽업하러 가야 돼", who, speaker = "other"),
        Utterance(at(15, 38, 30), "브로 화요일 날 브로 괜찮아? 우리 그때 화요일 날 점심쯤부터 쭉 놀기로 했는데", who, speaker = "other"),
        Utterance(at(15, 38, 40), "내가 근데 일단은 열 시에 저기 아침에 미팅 있거든", who, speaker = "me"),
        Utterance(at(15, 38, 45), "한 열한 시면 끝날 거야", who, speaker = "me"),
        Utterance(at(15, 38, 50), "아 그럼 브로도 뭐 한 한 시 조금 전에는 오겠네", who, speaker = "other"),
        Utterance(at(15, 38, 55), "뭐 아마 그 정도 되지 않을까", who, speaker = "me"),
        Utterance(at(15, 39, 0), "그래 그래 그래 나도 나도 어차피 회의 있고 빨리 가야 열두 시거든", who, speaker = "other"),
        Utterance(at(15, 39, 5), "응 나도 한 열두 시에서 한 시 사이일 것 같은데", who, speaker = "me"),
        Utterance(at(15, 39, 10), "알았어 난 화요일 날 끝나고 바로 연락할게", who, speaker = "me"),
        Utterance(at(15, 39, 40), "아 내일도 약속 있어", who, speaker = "other"),
    )

    @Test
    fun onePlanOnTuesdayWithTheCaller() {
        val a = RuleBrain().analyze(emptyList(), call, emptyList(), now)
        // 예전에는 같은 통화에서 일정 6개(오늘 밤 10시, 월요일 오후 1시 …)가 생겼다.
        val e = a.events.single()
        assertEquals("한빛 김민준 팀장 약속", e.title)
        assertEquals("2026-10-27T12:00", e.start)
        assertEquals(EventRules.Decision.REGISTER, EventRules.decide(e, EventRules.timing(e)!!, now, EventRules.MODE_SMART))
        assertTrue(e.reasons.any { it.contains("화요일") }, e.reasons.toString())
        assertTrue(e.reasons.none { it.startsWith("다른 사람 목소리예요") }, e.reasons.toString())
        // 상대의 할 일("내가 픽업하러 가야 돼")은 빼고, 내가 하기로 한 연락만 남는다.
        assertEquals(listOf("2026-10-27"), a.todos.map { it.due })
        assertTrue(a.todos.single().text.contains("연락할게"))
    }

    @Test
    fun othersOwnPlansAreNotMine() {
        val a = RuleBrain().analyze(emptyList(), listOf(Utterance(at(15, 39, 40), "아 내일도 약속 있어", who, speaker = "other")), emptyList(), now)
        assertTrue(a.events.isEmpty(), a.events.toString())
    }

    @Test
    fun spacedNativeHoursStillReadAsTwelve() {
        val f = io.github.jgg113.gyeot.ai.KoreanTime.parse("응 나도 한 열 두시에서 한 시 사이일 것 같은데", now)
        assertEquals(12, f.time?.hour)
        val g = io.github.jgg113.gyeot.ai.KoreanTime.parse("한 열 한 시면 끝날 거야", now, java.time.LocalDate.of(2026, 10, 27))
        assertEquals("2026-10-27T11:00", "${g.date}T${g.time}")
    }
}
