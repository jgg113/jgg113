package io.github.jgg113.gyeot.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * 한국어 말 속의 날짜·시각을 찾아 실제 시각으로 바꾼다. AI 없이 규칙만 쓰므로 무료·무제한·즉시.
 *
 * 받아쓰기 결과는 숫자를 "3시"처럼 쓰기도 하고 "세 시", "삼십 분", "십일월 오일"처럼 한글로 쓰기도 해서 둘 다 읽는다.
 * 찾은 부분의 위치(spans)도 돌려줘 제목을 만들 때 빼낼 수 있게 한다.
 */
object KoreanTime {

    data class Found(
        val date: LocalDate?,
        val time: LocalTime?,
        /** 시각이 "3시"처럼 오전·오후 없이 나와 추측했는지. */
        val guessedHalf: Boolean,
        /** "저녁"처럼 대략적인 때만 있었는지. */
        val roughTime: Boolean,
        val spans: List<IntRange>,
    ) {
        val any get() = date != null || time != null
    }

    private const val NATIVE = "열한|열두|하나|다섯|여섯|일곱|여덟|아홉|한|두|세|네|열|둘|셋|넷"
    private const val SINO = "[일이삼사오육칠팔구십]{1,4}"
    private val HOUR = Regex("""(오전|오후|아침|점심|저녁|밤|새벽|낮)?\s*(\d{1,2}|$NATIVE)\s*시(?![간작장청험계키켜민설점선각])(\s*(반|(\d{1,2}|$SINO)\s*분))?""")
    private val NOON = Regex("정오|자정")
    private val PART = Regex("(오늘|내일|낼|모레)?\\s*(아침|점심|저녁|밤|새벽|오전|오후)")
    private val REL_MIN = Regex("""(\d{1,3}|$SINO|$NATIVE)\s*(시간|분)\s*(뒤|후|있다가|이따)""")
    // "오후"의 '오'를 5로 읽지 않도록 수 뒤에는 반드시 '일'·'주'가 와야 한다.
    private val REL_DAY = Regex("""(\d{1,2}|$SINO)\s*일\s*(뒤|후)|(하루|이틀|사흘|나흘|닷새|일주일)\s*(뒤|후)|(\d{1,2}|$NATIVE)\s*주\s*(뒤|후)""")
    private val DAY_WORD = Regex("오늘|금일|내일|낼|모레|글피")
    private val WEEKDAY = Regex("""(이번|다음|다다음|담|요번)?\s*(주)?\s*([월화수목금토일])요일""")
    private val WEEKEND = Regex("""(이번|다음|담)?\s*주말""")
    private val MONTH_DAY = Regex("""(\d{1,2}|$SINO)\s*월\s*(\d{1,2}|$SINO)\s*일""")
    private val NEXT_MONTH_DAY = Regex("""다음\s*달\s*(\d{1,2}|$SINO)\s*일""")
    // "15일에", "십오 일까지" — 한 글자 한자어 수("이 일에"=이 일)는 뜻이 갈려서 숫자 또는 '십'이 들어간 수만 받는다.
    private val BARE_DAY = Regex("""(\d{1,2}|[일이삼]?십[일이삼사오육칠팔구]?)\s*일\s*(에|날|까지|부터|쯤|정도)""")

    fun parse(text: String, now: ZonedDateTime): Found {
        val spans = mutableListOf<IntRange>()
        val today = now.toLocalDate()
        var date: LocalDate? = null
        var time: LocalTime? = null
        var guessed = false
        var rough = false

        // 1) "삼십 분 뒤", "두 시간 후" — 지금부터 상대 시각.
        REL_MIN.find(text)?.let { m ->
            val n = number(m.groupValues[1])
            if (n != null && n > 0) {
                val t = if (m.groupValues[2] == "시간") now.plusHours(n.toLong()) else now.plusMinutes(n.toLong())
                spans += m.range
                return Found(t.toLocalDate(), t.toLocalTime().withSecond(0).withNano(0), false, false, spans)
            }
        }

        // 2) 날짜
        REL_DAY.find(text)?.let { m ->
            val days = when {
                m.groupValues[5].isNotEmpty() -> number(m.groupValues[5])?.times(7)
                m.groupValues[3].isNotEmpty() -> when (m.groupValues[3]) { "하루" -> 1; "이틀" -> 2; "사흘" -> 3; "나흘" -> 4; "닷새" -> 5; else -> 7 }
                else -> number(m.groupValues[1])
            }
            if (days != null && days in 1..365) { date = today.plusDays(days.toLong()); spans += m.range }
        }
        if (date == null) MONTH_DAY.find(text)?.let { m ->
            val mo = number(m.groupValues[1]); val d = number(m.groupValues[2])
            if (mo != null && d != null && mo in 1..12 && d in 1..31) {
                var cand = runCatching { LocalDate.of(today.year, mo, d) }.getOrNull()
                if (cand != null && cand.isBefore(today.minusDays(1))) cand = cand.plusYears(1)
                if (cand != null) { date = cand; spans += m.range }
            }
        }
        if (date == null) NEXT_MONTH_DAY.find(text)?.let { m ->
            val d = number(m.groupValues[1])
            val base = today.plusMonths(1)
            if (d != null && d in 1..base.lengthOfMonth()) { date = base.withDayOfMonth(d); spans += m.range }
        }
        if (date == null) WEEKDAY.find(text)?.let { m ->
            val dow = DOW.getValue(m.groupValues[3])
            val which = m.groupValues[1]
            date = when (which) {
                "다음", "담" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(dow))
                "다다음" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1).with(TemporalAdjusters.nextOrSame(dow))
                "이번", "요번" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(dow))
                else -> today.with(TemporalAdjusters.nextOrSame(dow))
            }
            spans += m.range
        }
        if (date == null) WEEKEND.find(text)?.let { m ->
            date = if (m.groupValues[1] == "다음" || m.groupValues[1] == "담")
                today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            else today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            spans += m.range
        }
        if (date == null) DAY_WORD.find(text)?.let { m ->
            date = when (m.value) { "내일", "낼" -> today.plusDays(1); "모레" -> today.plusDays(2); "글피" -> today.plusDays(3); else -> today }
            spans += m.range
        }
        if (date == null) BARE_DAY.find(text)?.let { m ->
            val d = number(m.groupValues[1])
            if (d != null && d in 1..31) {
                var cand = runCatching { today.withDayOfMonth(d) }.getOrNull()
                if (cand == null || cand.isBefore(today)) cand = runCatching { today.plusMonths(1).withDayOfMonth(d) }.getOrNull()
                if (cand != null) { date = cand; spans += IntRange(m.range.first, m.range.first + m.value.indexOf("일")) }
            }
        }

        // 3) 시각
        val hm = HOUR.find(text)
        if (hm != null) {
            val part = hm.groupValues[1]
            var h = number(hm.groupValues[2])
            val minWord = hm.groupValues[4]
            val min = when {
                minWord == "반" -> 30
                minWord.isNotEmpty() -> number(hm.groupValues[5]) ?: 0
                else -> 0
            }
            if (h != null && h in 0..24 && min in 0..59) {
                var hour = h
                when (part) {
                    "오전", "아침" -> if (hour == 12) hour = 0
                    "새벽" -> if (hour == 12) hour = 0
                    "오후", "저녁", "밤" -> if (hour < 12) hour += 12
                    "점심", "낮" -> if (hour in 1..5) hour += 12
                    else -> {
                        // 오전·오후를 말하지 않은 "3시": 1~6시는 오후로 본다 (약속은 대개 낮). 7~11시는 오전.
                        guessed = true
                        if (hour in 1..6) hour += 12
                    }
                }
                if (hour == 24) hour = 0
                time = LocalTime.of(hour % 24, min)
                spans += hm.range
                // 날짜 없이 시각만: 오늘, 이미 지났으면 (오전으로 추측했던 건 오후로) 아니면 내일.
                if (date == null) {
                    date = today
                    if (!today.atTime(time).atZone(now.zone).isAfter(now)) {
                        val pm = if (guessed && hour < 12) time!!.plusHours(12) else null
                        if (pm != null && today.atTime(pm).atZone(now.zone).isAfter(now)) time = pm else date = today.plusDays(1)
                    }
                }
            }
        }
        if (time == null) NOON.find(text)?.let { m ->
            time = if (m.value == "정오") LocalTime.NOON else LocalTime.MIDNIGHT
            spans += m.range
        }
        if (time == null && date != null) PART.find(text)?.let { m ->
            // 시각 없이 "내일 저녁" — 대략적인 때. 날짜가 있을 때만 쓴다.
            time = when (m.groupValues[2]) { "아침", "오전" -> LocalTime.of(9, 0); "점심" -> LocalTime.NOON; "오후" -> LocalTime.of(15, 0); "저녁" -> LocalTime.of(19, 0); "밤" -> LocalTime.of(21, 0); else -> LocalTime.of(6, 0) }
            rough = true
            spans += IntRange(m.range.first + m.value.length - m.groupValues[2].length, m.range.last)
        }
        return Found(date, time, guessed, rough, spans)
    }

    private val DOW = mapOf(
        "월" to DayOfWeek.MONDAY, "화" to DayOfWeek.TUESDAY, "수" to DayOfWeek.WEDNESDAY, "목" to DayOfWeek.THURSDAY,
        "금" to DayOfWeek.FRIDAY, "토" to DayOfWeek.SATURDAY, "일" to DayOfWeek.SUNDAY,
    )

    private val NATIVE_VALUES = mapOf(
        "한" to 1, "하나" to 1, "두" to 2, "둘" to 2, "세" to 3, "셋" to 3, "네" to 4, "넷" to 4, "다섯" to 5, "여섯" to 6,
        "일곱" to 7, "여덟" to 8, "아홉" to 9, "열" to 10, "열한" to 11, "열두" to 12,
    )
    private val SINO_DIGIT = mapOf('일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9)

    /** "15", "세", "이십오", "십" → 수. 못 읽으면 null. */
    fun number(word: String): Int? {
        val w = word.trim()
        w.toIntOrNull()?.let { return it }
        NATIVE_VALUES[w]?.let { return it }
        if (w.isEmpty() || w.any { it != '십' && it != '백' && it !in SINO_DIGIT }) return null
        var total = 0
        var cur = 0
        for (c in w) {
            when (c) {
                '백' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
                '십' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                else -> cur = SINO_DIGIT.getValue(c)
            }
        }
        return total + cur
    }
}
