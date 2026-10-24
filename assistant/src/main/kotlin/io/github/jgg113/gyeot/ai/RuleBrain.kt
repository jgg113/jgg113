package io.github.jgg113.gyeot.ai

import io.github.jgg113.gyeot.data.SavedEvent
import io.github.jgg113.gyeot.data.Utterance
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 인터넷도 API 키도 없이 휴대폰 안에서 대화를 정리한다 (무료·무제한).
 *
 * - 일정·할 일·핵심(숫자·돈·번호·결정)은 한국어 규칙으로 뽑는다 — 빠르고 예측 가능하다.
 * - [summarizer] 가 있으면(휴대폰 안 작은 AI 모델) 요약 문장과 핵심을 더 자연스럽게 다듬는다. 없어도 동작한다.
 */
class RuleBrain(private val polisher: Polisher? = null) : Brain {

    /** 휴대폰 안 작은 AI 모델로 문장을 다듬는 쪽. 모델이 없거나 꺼져 있으면 null 을 돌려준다. */
    interface Polisher {
        fun summarize(dialogue: String): LocalLlm.Summary?
        fun answer(question: String, lines: String): String?
    }


    override fun analyze(context: List<Utterance>, fresh: List<Utterance>, known: List<SavedEvent>, now: ZonedDateTime): Analysis {
        val events = mutableListOf<ExtractedEvent>()
        val todos = mutableListOf<Todo>()
        val points = mutableListOf<String>()
        for (u in fresh) {
            // 말한 시각 기준으로 "이따 3시"를 풀어야 맞다 (정리는 몇 분 늦게 돌 수 있다).
            val spokenAt = Instant.ofEpochMilli(u.at).atZone(EventRules.SEOUL).let { if (it.isAfter(now)) now else it }
            for (clause in clauses(u.text)) {
                val found = KoreanTime.parse(clause, spokenAt)
                extractEvent(clause, found, spokenAt)?.let { e -> if (events.none { it.start == e.start && similar(it.title, e.title) }) events += e }
                extractTodo(clause, found)?.let { t -> if (todos.none { it.text == t.text }) todos += t }
                if (isKeyPoint(clause)) KoreanText.tidy(clause).takeIf { it.length >= 4 }?.let { points += it }
            }
        }
        val polished = runCatching { polisher?.summarize(fresh.joinToString("\n") { u -> "[${clock(u.at)}] ${u.text}" }) }.getOrNull()
        val keyPoints = (polished?.keyPoints.orEmpty() + points).distinct().take(8)
        val money = points.any { MONEY.containsMatchIn(it) || ACCOUNT.containsMatchIn(it) }
        val importance = when {
            events.isNotEmpty() || todos.isNotEmpty() || money -> "high"
            keyPoints.isNotEmpty() -> "normal"
            else -> "low"
        }
        val summary = polished?.summary?.takeIf { it.isNotBlank() } ?: ruleSummary(fresh, events, todos, keyPoints)
        return Analysis(summary, importance, keyPoints, events, todos)
    }

    override fun ask(question: String, material: String, now: ZonedDateTime): String {
        if (material.lines().none { it.isNotBlank() && !it.startsWith("##") }) return "기록이 없어요."
        val words = keywords(question)
        if (words.isEmpty()) return "질문에서 찾을 낱말을 못 골랐어요. 사람 이름이나 물건·장소 이름을 넣어 물어봐 주세요."
        // 날짜 머리글을 줄마다 붙여 둔다.
        var day = ""
        val dated = material.lines().mapNotNull { l ->
            if (l.startsWith("## ")) { day = l.removePrefix("## "); null } else if (l.isBlank()) null else "$day $l"
        }
        val hits = dated.map { it to words.count { w -> it.contains(w) } }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }).take(30).map { it.first }
        if (hits.isEmpty()) return "기록에서 '${words.joinToString(", ")}' 이(가) 나온 말을 찾지 못했어요."
        val answer = runCatching { polisher?.answer(question, hits.joinToString("\n")) }.getOrNull()
        return buildString {
            if (!answer.isNullOrBlank()) append(answer.trim()).append("\n\n— 찾은 말 —\n")
            else append("찾은 말 (많이 겹치는 순):\n")
            hits.take(12).forEach { append("• ").append(it.trim()).append('\n') }
        }.trim()
    }

    // ---------------- 규칙 ----------------

    private fun extractEvent(clause: String, f: KoreanTime.Found, now: ZonedDateTime): ExtractedEvent? {
        if (PAST_DAY.containsMatchIn(clause)) return null
        val cue = EVENT_CUES.firstOrNull { clause.contains(it) }
        val date = f.date ?: return implicitEvent(clause, now)
        val deadline = clause.contains("까지")
        if (cue == null && f.time == null && !deadline) return null // 날짜만 있고 약속 말이 없으면 ("내일 비 온대") 넘긴다
        val pastVerb = PAST_VERB.containsMatchIn(clause) && !COMMIT.containsMatchIn(clause)
        var confidence = 0.45
        if (f.time != null) confidence += 0.2
        if (f.dateExplicit && !f.roughDate) confidence += 0.1
        if (deadline) confidence += 0.2
        if (cue != null) confidence += 0.25
        if (COMMIT.containsMatchIn(clause)) confidence += 0.1
        if (f.guessedHalf || f.roughTime) confidence -= 0.1
        if (f.roughDate) confidence -= 0.15
        if (pastVerb) confidence -= 0.5
        if (VAGUE.containsMatchIn(clause)) confidence -= 0.35
        val start = if (f.time == null) date.toString() else date.atTime(f.time).format(MINUTE)
        val title = (if (deadline && cue == null) "마감: " else "") + title(clause, f)
        return ExtractedEvent(
            title = title.take(30),
            start = start,
            allDay = f.time == null,
            location = location(clause),
            confidence = confidence.coerceIn(0.0, 1.0),
            evidence = clause,
            isChange = CHANGE.containsMatchIn(clause),
        )
    }

    /**
     * 날짜·시각은 없지만 챙겨야 할 일로 들리는 말 ("치과 가 봐야 되는데", "보험 갱신해야 돼", "엄마한테 전화해야지").
     * 언제인지 모르니 내일 아침 9시로 임시로 잡고, 반드시 "추가할까요?" 하고 묻는다.
     */
    private fun implicitEvent(clause: String, now: ZonedDateTime): ExtractedEvent? {
        if (!NEED.containsMatchIn(clause) || PAST_VERB.containsMatchIn(clause) && !NEED_END.containsMatchIn(clause)) return null
        val rest = strip(clause, emptyList())
        val noun = TITLE_NOUNS.firstOrNull { rest.contains(it) }
        val tokens = rest.split(' ').filter { it.isNotBlank() }
        if (noun == null && tokens.size < 2) return null
        val title = tokens.dropLastWhile { ENDING_WORD.matches(it) || NEED_WORD.matches(it) }.take(5).joinToString(" ").ifBlank { noun ?: return null }
        val at = now.toLocalDate().plusDays(1).atTime(9, 0)
        return ExtractedEvent(
            title = title.take(30),
            start = at.format(MINUTE),
            location = location(clause),
            confidence = if (noun != null) 0.5 else 0.4,
            evidence = clause,
            timeUnknown = true,
        )
    }

    private fun extractTodo(clause: String, f: KoreanTime.Found): Todo? {
        if (TODO_CUES.none { clause.contains(it) }) return null
        if (PAST_DAY.containsMatchIn(clause)) return null
        val due = when {
            f.date == null -> ""
            f.time == null -> f.date.toString()
            else -> f.date.atTime(f.time).format(MINUTE)
        }
        val text = focus(strip(clause, f.spans).ifBlank { clause }) ?: return null
        return Todo(text, due)
    }

    /**
     * 긴 말에서 할 일 부분만 골라낸다: "왜 또 거기까지 갔어 어 토 아니 토닉워터 사와야 돼 그럼" → "토닉워터 사와야 돼".
     * 할 일 말(…야 돼, …해 줘, …할게)이 든 낱말 앞 세 낱말 + 뒤 한 낱말만 남기고 말버릇을 뺀다.
     */
    private fun focus(clause: String): String? {
        val words = clause.split(Regex("\\s+")).filter { it.isNotBlank() }
        val i = words.indexOfFirst { STRONG_TODO.containsMatchIn(it) }.takeIf { it >= 0 }
            ?: words.indexOfFirst { w -> TODO_CUES.any { w.contains(it.trim()) } }.takeIf { it >= 0 } ?: return null
        val end = if (i + 1 < words.size && AUX.matches(words[i + 1])) i + 1 else i
        val picked = KoreanText.tidy(words.subList((i - 3).coerceAtLeast(0), end + 1).joinToString(" "))
        val meaningful = picked.split(' ').count { !AUX.matches(it) && !STRONG_TODO.containsMatchIn(it) }
        return picked.takeIf { meaningful >= 1 && it.length >= 4 }
    }

    private fun isKeyPoint(clause: String) =
        MONEY.containsMatchIn(clause) || ACCOUNT.containsMatchIn(clause) || DECISION.containsMatchIn(clause) || LONG_NUMBER.containsMatchIn(clause)

    /** 말에서 날짜·시각을 빼고, 누구와(…랑) + 무엇(치과·회의…)으로 짧은 제목을 만든다. */
    private fun title(clause: String, f: KoreanTime.Found): String {
        val rest = strip(clause, f.spans)
        val tokens = rest.split(' ').filter { it.isNotBlank() }
        val nounWord = TITLE_NOUNS.firstOrNull { rest.contains(it) }
        // "엄마 생신", "팀 회의" 처럼 바로 앞 낱말을 붙인다.
        val noun = nounWord?.let { n ->
            val i = tokens.indexOfFirst { it.contains(n) }
            val prev = tokens.getOrNull(i - 1)?.takeIf { it.length in 1..4 && !it.endsWith("에") && !it.endsWith("에서") && !WITH_END.containsMatchIn(it) }
            if (prev != null) "$prev $n" else n
        }
        val who = WITH.find(rest)?.groupValues?.get(1)?.takeIf { it.length in 1..6 && it !in NOT_NAMES }
        return when {
            who != null && noun != null -> "$who $noun"
            who != null -> "$who 약속"
            noun != null -> listOfNotNull(location(clause).takeIf { it.isNotBlank() && noun != it }, noun).joinToString(" ")
            else -> tokens.dropLastWhile { ENDING_WORD.matches(it) }.take(5).joinToString(" ")
        }.ifBlank { "약속" }
    }

    private fun location(clause: String): String =
        PLACE.find(clause)?.groupValues?.get(1)?.takeIf { it.length >= 2 && KoreanTime.parse(it, ZonedDateTime.now(EventRules.SEOUL)).any.not() }.orEmpty()

    private fun ruleSummary(fresh: List<Utterance>, events: List<ExtractedEvent>, todos: List<Todo>, points: List<String>): String {
        val parts = mutableListOf<String>()
        if (events.isNotEmpty()) parts += "일정: " + events.joinToString(", ") { it.title }
        if (todos.isNotEmpty()) parts += "할 일: " + todos.joinToString(", ") { it.text.take(24) }
        if (points.isNotEmpty()) parts += "기억할 말: " + points.first().take(40)
        if (parts.isEmpty()) {
            val gist = fresh.map { KoreanText.tidy(it.text) }.maxByOrNull { it.length }.orEmpty().take(40)
            parts += if (gist.isBlank()) "짧은 대화" else "일상 대화 — \"$gist\""
        }
        return parts.joinToString(" · ")
    }

    companion object {
        private val MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        private val HM = DateTimeFormatter.ofPattern("HH:mm")
        private fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(EventRules.SEOUL).format(HM)

        private val EVENT_CUES = listOf(
            "약속", "만나", "만날", "보자", "봐요", "봅시다", "뵙", "회의", "미팅", "회식", "모임", "예약", "진료", "병원", "치과",
            "면접", "시험", "수업", "학원", "출장", "출발", "도착", "비행기", "기차", "결혼식", "장례", "돌잔치", "생일", "생신", "기념일",
            "가야", "가기로", "오기로", "와야", "하기로", "있어", "있대", "있어요", "있습니다", "있으니까", "마감", "알람", "깨워", "픽업",
            "데리러", "데려다", "전화", "통화", "택배", "납부", "제출", "발표", "공연", "영화", "경기",
        )
        private val TITLE_NOUNS = listOf(
            "치과", "병원", "진료", "회의", "미팅", "회식", "모임", "면접", "시험", "수업", "학원", "출장", "결혼식", "장례식", "돌잔치",
            "생일", "생신", "기념일", "예약", "점심", "저녁", "식사", "술", "커피", "통화", "전화", "발표", "공연", "영화", "경기", "택배", "마감", "납부", "제출", "픽업",
        )
        private val TODO_CUES = listOf(
            "해 줘", "해줘", "해 주세요", "해주세요", "주세요", "줄래", "주실", "해야 돼", "해야돼", "해야 해", "해야해", "해야 된다", "해야겠",
            "해야 되", "야 돼", "야 해", "야겠", "할게", "할께", "드릴게", "보낼게", "잊지 마", "잊지마", "챙겨", "꼭 ", "까지",
        )
        private val COMMIT = Regex("기로\\s*했|기로\\s*해|자$|자\\s|할게|할께|합시다|봐요|보자|만나요|갈게|올게|예약했|잡았|잡혔")
        private val PAST_VERB = Regex("(았|었|였|했|갔|왔|봤|났)(어|다|지|네|요|었|습니다)")
        private val PAST_DAY = Regex("어제|그저께|그제|지난\\s*주|저번\\s*주|작년|지난번")
        private val VAGUE = Regex("언제\\s*한\\s*번|나중에\\s*한\\s*번|언제가|언제 되|몇\\s*시|시간\\s*있|시간\\s*돼|시간\\s*되|어때|괜찮아|[가-힣]까(?=\\s|$|\\?)|[가-힣]래(?=\\s*$|\\?)")
        private val CHANGE = Regex("미뤄|미루|당겨|당기|바뀌|바꿔|변경|옮겨|연기")
        private val MONEY = Regex("\\d[\\d,]*\\s*(원|만\\s*원|천\\s*원|만원)|[일이삼사오육칠팔구십백천만]+\\s*원|\\d+\\s*만")
        private val ACCOUNT = Regex("계좌|번호|주소|비밀번호|비번|전화번호|이메일|아이디|코드")
        private val DECISION = Regex("결정|확정|정했|하기로|바뀌|변경|취소|미뤄|당겨|합의|계약")
        private val LONG_NUMBER = Regex("\\d{4,}|\\d+-\\d+")
        private val STRONG_TODO = Regex("(야|야지|줘|줘요|주세요|줄래|할게|할께|드릴게|보낼게|챙겨|잊지)$")
        private val AUX = Regex("돼|돼요|해|해요|된다|되지|합니다|돼야|겠다|겠어|마|마세요|말고")
        private val NEED = Regex("(가야|가 봐야|가봐야|해야|예약해야|챙겨야|사야|내야|보내야|받아야|신청해야|갱신해야|들러야|전화해야|연락해야|알아봐야|준비해야|잊지 말아야|잊으면 안)")
        private val NEED_END = Regex("(야|야지|야겠|야 돼|야 되|야 해|안 돼|안돼)")
        private val NEED_WORD = Regex("(가야|가 봐야|해야|봐야|돼|되는데|하는데|해|겠다|겠네|지|돼요|되는데요|할|것|같아)")
        private val ENDING_WORD = Regex("주세요|줘|줘요|줄래|돼|돼요|해|해요|있어|있어요|있대|했어|할게|하자|하래|야|이야|입니다|합니다")
        private val WITH_END = Regex("(이랑|랑|하고|와|과)$")
        private val WITH = Regex("(\\S+?)(이랑|랑|하고|와|과)\\s")
        private val NOT_NAMES = setOf("나", "너", "우리", "저", "그", "이", "같이", "그리고", "회사", "사람")
        private val PLACE = Regex("(\\S+?)(?:앞에서|에서)(?=\\s|$)")
        private val SPLIT = Regex("(?<=(요|다|죠|자))\\s+(?=\\S)")

        /**
         * 받아쓰기는 문장부호가 없어서, 끝맺는 말(…요, …다, …죠, …자) 뒤에서만 나눈다.
         * "…어/…지/…네" 는 "까지", "네 시" 처럼 낱말 안에도 흔해서 나누지 않는다. 너무 짧은 마디는 앞과 합친다.
         */
        fun clauses(text: String): List<String> {
            val raw = text.replace(Regex("[.?!]+"), "$0 ").split(SPLIT).flatMap { it.split(Regex("(?<=[.?!])\\s+")) }
                .map { it.trim().trim('.', '?', '!', ',') }.filter { it.length >= 2 }
            val out = mutableListOf<String>()
            for (c in raw) {
                val prev = out.lastOrNull()
                if (prev != null && (prev.length < 6 || c.length < 4)) out[out.size - 1] = "$prev $c" else out += c
            }
            return out
        }

        fun strip(clause: String, spans: List<IntRange>): String {
            val sb = StringBuilder(clause)
            spans.sortedByDescending { it.first }.forEach { r ->
                if (r.first in 0..sb.length && r.last < sb.length) sb.replace(r.first, r.last + 1, " ")
            }
            return sb.toString().replace(Regex("(^|\\s)(에|에는|은|는|이|가|부터|까지|쯤|정도)(?=\\s|$)"), " ")
                .replace(Regex("\\s+"), " ").trim()
        }

        private val PARTICLE = Regex("(은|는|이|가|을|를|에|에서|랑|이랑|하고|도|만|한테|께|의|로|으로|요|했지|했어|였지|지|야|냐|니|까|어)$")
        private val STOP = setOf("아까", "어제", "오늘", "내일", "뭐", "뭐라고", "언제", "어디", "누가", "몇", "그거", "이거", "저거", "했지", "말했", "알려", "알려줘", "들은", "얘기")

        fun keywords(q: String): List<String> =
            q.split(Regex("[\\s,.?!]+")).map { w -> var x = w; repeat(2) { x = x.replace(PARTICLE, "") }; x }
                .filter { it.length >= 2 && it !in STOP }.distinct()

        private fun similar(a: String, b: String) = a == b || a.contains(b) || b.contains(a)
    }
}
