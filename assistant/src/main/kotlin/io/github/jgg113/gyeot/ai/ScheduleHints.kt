package io.github.jgg113.gyeot.ai

/**
 * 받아쓴 문장에 일정·약속 냄새가 나는지 빠르게 본다. 그렇다면 대화가 끝나기를 오래 기다리지 않고
 * 곧바로 정리(Claude 분석)를 돌려 일정을 놓치지 않게 한다. AI 를 부르지 않는 단순 규칙이다.
 */
object ScheduleHints {
    private val words = listOf(
        "내일", "모레", "글피", "오늘", "이따", "있다가", "나중에", "다음 주", "다음주", "이번 주", "이번주",
        "주말", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일",
        "약속", "회의", "미팅", "일정", "예약", "마감", "까지", "만나", "보자", "모임", "회식", "진료", "병원",
        "생일", "기념일", "결혼식", "장례", "면접", "시험", "출장", "알람", "깨워", "잊지", "기억해",
        "챙겨", "보내 줘", "보내줘", "연락", "전화해", "입금", "송금", "납부",
    )
    private val clock = Regex("""(\d{1,2}|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|열한|열두)\s*시(\s*(\d{1,2}|반))?""")
    private val date = Regex("""\d{1,2}\s*월\s*\d{1,2}\s*일|\d{1,2}\s*일(에|날|까지)""")

    fun mentionsSchedule(text: String): Boolean =
        clock.containsMatchIn(text) || date.containsMatchIn(text) || words.any { text.contains(it) }
}
