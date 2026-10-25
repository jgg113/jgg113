package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.ExtractedEvent
import io.github.jgg113.gyeot.ai.RuleBrain
import io.github.jgg113.gyeot.data.Utterance
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvidenceTest {
    private val now = ZonedDateTime.of(2026, 10, 25, 15, 0, 0, 0, EventRules.SEOUL)
    private val at = now.toInstant().toEpochMilli()
    private fun analyze(u: Utterance) = RuleBrain().analyze(emptyList(), listOf(u), emptyList(), now)
    private fun decide(e: ExtractedEvent) = EventRules.decide(e, EventRules.timing(e)!!, now, EventRules.MODE_SMART)

    @Test
    fun reasonsExplainTheDecision() {
        val e = analyze(Utterance(at - 60_000, "내일 오후 세 시에 강남역에서 민수랑 만나기로 했어")).events.single()
        assertEquals(at - 60_000, e.heardAt)
        assertTrue(e.reasons.any { it.contains("'내일'") && it.contains("오후 세 시") }, e.reasons.toString())
        assertTrue(e.reasons.any { it.contains("'만나'") }, e.reasons.toString())
        assertTrue(e.reasons.any { it.startsWith("정해졌다는 말") }, e.reasons.toString())
        assertEquals(EventRules.Decision.REGISTER, decide(e))
    }

    @Test
    fun tvSoundIsAskedNotAdded() {
        // 같은 말이라도 10분 동안 말소리가 쉬지 않던 때(TV)면 바로 넣지 않고 묻는다.
        val e = analyze(Utterance(at, "내일 오후 세 시에 강남역에서 민수랑 만나기로 했어", media = true)).events.single()
        assertTrue(e.mediaLikely)
        assertTrue(e.reasons.any { it.contains("TV") })
        assertEquals(EventRules.Decision.SUGGEST, decide(e))
        // 방송에서 흔한 말이 섞이면 확신도가 내려간다.
        val ad = analyze(Utterance(at, "지금 바로 전화 주세요 내일 오후 세 시까지 홈쇼핑 특가")).events
        assertTrue(ad.all { decide(it) != EventRules.Decision.REGISTER }, ad.toString())
    }

    @Test
    fun claudeResultsGetSourceFromTranscript() {
        val said = listOf(Utterance(at - 120_000, "오늘 날씨 좋다"), Utterance(at - 60_000, "다음 주 화요일에 치과 예약했어", source = "call:엄마"))
        val e = EventRules.attachSource(listOf(ExtractedEvent("치과", "2026-10-27T10:00", confidence = 0.9, evidence = "화요일에 치과 예약했어")), said).single()
        assertEquals(at - 60_000, e.heardAt)
        assertEquals("call:엄마", e.source)
        assertTrue(e.reasons.single().contains("Claude"))
    }
}
