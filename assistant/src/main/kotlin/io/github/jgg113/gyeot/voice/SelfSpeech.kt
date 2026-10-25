package io.github.jgg113.gyeot.voice

/**
 * 수빈이(앱)가 스피커로 말한 내용을 기억해, 듣기 기능이 그 소리를 다시 받아써 일정으로 넣지 않게 한다.
 *
 * 두 겹으로 막는다:
 * 1. 시각: 수빈이가 말하던 동안(앞뒤 여유 포함) 들어온 말 구간은 버린다.
 * 2. 내용: 그래도 들어온 글이 최근 10분 안에 수빈이가 한 말과 거의 같으면 버린다 (스피커 소리가 늦게 잡힌 경우).
 */
object SelfSpeech {
    private class Spoken(val text: String, val start: Long, @Volatile var end: Long)

    private val recent = ArrayDeque<Spoken>()
    private const val KEEP_MS = 10 * 60_000L
    private const val BEFORE_MS = 500L
    private const val AFTER_MS = 2_000L

    /** 수빈이 이름을 대는 말투 — 사람이 이렇게 말할 일은 거의 없다. */
    private val SIGNATURE = Regex("수빈이(에|예)요|클로드\\s*수빈|곁비서(에|예)요|캘린더에 넣었어요|알림 맞춰 뒀어요|여쭤볼 일정")

    @Synchronized
    fun started(text: String): Any {
        val now = System.currentTimeMillis()
        while (recent.isNotEmpty() && recent.first().start < now - KEEP_MS - 120_000) recent.removeFirst()
        return Spoken(text, now, 0).also { recent.addLast(it) }
    }

    fun finished(handle: Any) {
        (handle as? Spoken)?.end = System.currentTimeMillis()
    }

    /** [start]~[end] 동안 들어온 소리가 수빈이가 말하던 때와 겹치는지. */
    @Synchronized
    fun overlaps(start: Long, end: Long): Boolean {
        val now = System.currentTimeMillis()
        return recent.any { s ->
            // 아직 말하는 중이면 지금까지로 본다. 끝 표시를 못 받았어도 2분 넘게 말할 일은 없다.
            val sEnd = if (s.end == 0L) minOf(now, s.start + 120_000) else s.end
            start <= sEnd + AFTER_MS && end >= s.start - BEFORE_MS
        }
    }

    /** 받아쓴 글이 수빈이가 한 말을 다시 들은 것인지. */
    @Synchronized
    fun isEcho(text: String, now: Long = System.currentTimeMillis()): Boolean {
        if (SIGNATURE.containsMatchIn(text)) return true
        val heard = grams(text)
        if (heard.size < 3) return false
        return recent.any { s ->
            (s.end == 0L || s.end >= now - KEEP_MS) && grams(s.text).let { spoken -> heard.count { it in spoken } >= heard.size * 0.5 }
        }
    }

    private fun grams(t: String) = t.filter { it in '가'..'힣' || it.isDigit() }.windowed(2).toSet()

    /** 시험용 */
    @Synchronized
    fun clear() = recent.clear()
}
