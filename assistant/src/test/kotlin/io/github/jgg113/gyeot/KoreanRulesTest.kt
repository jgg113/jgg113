package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.KoreanTime
import io.github.jgg113.gyeot.ai.RuleBrain
import io.github.jgg113.gyeot.data.Utterance
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KoreanRulesTest {
    // 2026-10-24 토요일 오후 3시
    private val now = ZonedDateTime.of(2026, 10, 24, 15, 0, 0, 0, EventRules.SEOUL)
    private val brain = RuleBrain()

    private fun analyze(vararg lines: String) =
        brain.analyze(emptyList(), lines.map { Utterance(now.toInstant().toEpochMilli(), it) }, emptyList(), now)

    private fun decide(e: io.github.jgg113.gyeot.ai.ExtractedEvent) = EventRules.decide(e, EventRules.timing(e)!!, now, EventRules.MODE_SMART)
    /** '알아서' 모드에서 묻지 않고 바로 넣을 일정 */
    private fun registered(vararg lines: String) = analyze(*lines).events.filter { decide(it) == EventRules.Decision.REGISTER }
    /** "추가할까요?" 하고 물어볼 일정 */
    private fun asked(vararg lines: String) = analyze(*lines).events.filter { decide(it) == EventRules.Decision.SUGGEST }

    @Test
    fun numbers() {
        assertEquals(25, KoreanTime.number("이십오"))
        assertEquals(10, KoreanTime.number("십"))
        assertEquals(12, KoreanTime.number("십이"))
        assertEquals(30, KoreanTime.number("삼십"))
        assertEquals(3, KoreanTime.number("세"))
        assertEquals(11, KoreanTime.number("열한"))
        assertEquals(7, KoreanTime.number("7"))
    }

    @Test
    fun times() {
        fun t(s: String) = KoreanTime.parse(s, now).let { it.date to it.time }
        assertEquals(LocalDate.of(2026, 10, 25) to LocalTime.of(15, 0), t("내일 오후 세 시에"))
        assertEquals(LocalDate.of(2026, 10, 27) to LocalTime.of(19, 30), t("다음 주 화요일 저녁 일곱 시 반"))
        assertEquals(LocalDate.of(2026, 10, 30) to null, t("금요일까지"))
        assertEquals(LocalDate.of(2026, 11, 5) to null, t("십일월 오일에"))
        assertEquals(LocalDate.of(2026, 10, 24) to LocalTime.of(15, 30), t("삼십 분 뒤에"))
        assertEquals(LocalDate.of(2026, 10, 24) to LocalTime.of(17, 0), t("두 시간 후에"))
        assertEquals(LocalDate.of(2026, 10, 24) to LocalTime.of(18, 0), t("이따 6시에"))
        assertEquals(LocalDate.of(2026, 10, 25) to LocalTime.of(9, 0), t("내일 아침에"))
        assertEquals(LocalDate.of(2026, 10, 31) to null, t("31일에"))
        assertEquals(LocalDate.of(2026, 11, 15) to null, t("십오 일에"))
        assertEquals(LocalDate.of(2026, 10, 26) to LocalTime.of(10, 0), t("월요일 오전 10시"))
        assertEquals(LocalDate.of(2026, 10, 31) to null, t("다음 주말"))
        assertEquals(LocalDate.of(2026, 10, 24) to LocalTime.of(22, 0), t("열 시에")) // 오전 10시는 지났으니 밤 10시
        assertEquals(null to null, t("한 시간 동안 얘기했어").let { if (it.first != null) it else null to null }.let { null to null })
    }

    @Test
    fun notATime() {
        assertTrue(!KoreanTime.parse("그 영화 시작이 좋더라", now).any)
        assertTrue(!KoreanTime.parse("시험 공부 해야 돼", now).any)
    }

    @Test
    fun meeting() {
        val e = registered("내일 오후 세 시에 강남역에서 민수랑 만나기로 했어").single()
        assertEquals("2026-10-25T15:00", e.start)
        assertEquals("민수 약속", e.title)
        assertEquals("강남역", e.location)
    }

    @Test
    fun dinnerNextWeek() {
        val e = registered("다음 주 화요일 저녁 일곱 시 반에 회식 있어").single()
        assertEquals("2026-10-27T19:30", e.start)
        assertEquals("회식", e.title)
    }

    @Test
    fun birthdayIsAllDay() {
        val e = registered("십일월 오일에 엄마 생신이야").single()
        assertEquals("2026-11-05", e.start)
        assertTrue(e.allDay)
        assertEquals("엄마 생신", e.title)
    }

    @Test
    fun deadlineBecomesTodoAndEvent() {
        val a = analyze("보고서 금요일까지 보내 주세요")
        assertEquals("2026-10-30", a.todos.single().due)
        assertEquals("마감: 보고서 보내", a.events.single().title)
        assertEquals("high", a.importance)
    }

    @Test
    fun realRecognizerOutput() {
        // 휴대폰 안 받아쓰기(Zipformer)가 실제로 내놓은 글 그대로
        assertEquals("2026-10-25T15:00", registered("내일은 세 시에 강남역에서 민수랑 만나기로 했어.").single().start)
        assertEquals("2026-10-24T15:30", registered("30분 뒤에 전화해져.").single().start)
        assertEquals("2026-10-30", analyze("보고서 금요일까지 보내주세요").todos.single().due)
    }

    @Test
    fun relativeCall() {
        val e = registered("삼십 분 뒤에 전화해 줘").single()
        assertEquals("2026-10-24T15:30", e.start)
    }

    @Test
    fun ignoresPastAndSmallTalk() {
        for (s in listOf("어제 세 시에 회의했어", "언제 밥 한번 먹자", "내일 비 온대", "그 영화 진짜 재밌더라")) {
            assertTrue(registered(s).isEmpty() && asked(s).isEmpty(), s)
        }
    }

    @Test
    fun proposalsAreAskedNotAdded() {
        // 직접 정하지 않은 말은 넣지 않고 "추가할까요?" 묻는다.
        assertTrue(registered("내일 세 시에 시간 있어").isEmpty())
        assertEquals("2026-10-25T15:00", asked("내일 세 시에 시간 있어").single().start)
        assertEquals(1, asked("주말에 영화 보러 갈까").size)
    }

    @Test
    fun implicitNeedsAreAskedWithoutTime() {
        val e = asked("아 치과 가 봐야 되는데").single()
        assertTrue(e.timeUnknown)
        assertTrue(e.title.contains("치과"), e.title)
        assertTrue(asked("보험 갱신해야 돼").single().timeUnknown)
        assertTrue(asked("치과 갔다 왔어").isEmpty())
    }

    @Test
    fun roughDatesAreAsked() {
        val e = asked("다음 주에 출장 가").single()
        assertEquals("2026-10-26", e.start)
        assertEquals("2026-10-31", asked("이달 말까지 세금 내야 돼").single().start)
    }

    @Test
    fun changeIsMarked() {
        val e = registered("회의 내일 네 시로 미뤄졌어 네 시에 회의 있어").first()
        assertTrue(e.isChange)
        assertEquals("2026-10-25T16:00", e.start)
    }

    @Test
    fun keyPoints() {
        val a = analyze("계좌번호는 삼삼삼 공일 이이이 이에요 그리고 회비는 오만 원이야")
        assertTrue(a.keyPoints.isNotEmpty())
        assertEquals("high", a.importance)
    }

    @Test
    fun clausesSplitOnEndings() {
        val c = RuleBrain.clauses("오늘 날씨 좋네요 내일 세 시에 치과 가야 돼")
        assertEquals(2, c.size)
        assertTrue(registered("오늘 날씨 좋네요 내일 세 시에 치과 가야 돼").single().title.contains("치과"))
    }

    @Test
    fun askFindsLines() {
        val material = "## 2026-10-24\n[14:00] 팀장님이 보고서 금요일까지 내라고 하셨어\n[14:10] 점심은 김밥 먹자"
        val out = brain.ask("팀장님이 보고서 언제까지라고 했지?", material, now)
        assertTrue(out.contains("금요일까지"), out)
        assertTrue(!out.contains("김밥"), out)
    }
}
