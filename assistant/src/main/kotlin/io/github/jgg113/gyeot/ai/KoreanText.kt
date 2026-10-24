package io.github.jgg113.gyeot.ai

/** 작은 AI·받아쓰기 결과를 읽을 수 있는 한국어로 다듬는다. */
object KoreanText {

    // GPT-2 계열(byte-level BPE) 토크나이저가 바이트를 대신 쓰는 글자 → 원래 바이트.
    private val BYTE_OF: Map<Char, Int> = run {
        val printable = (33..126) + (161..172) + (174..255)
        val map = HashMap<Char, Int>()
        printable.forEach { map[it.toChar()] = it }
        var n = 0
        for (b in 0..255) if (b !in printable) { map[(256 + n).toChar()] = b; n++ }
        map
    }

    /**
     * Qwen 같은 모델이 한국어를 "Ġë¹ł르게" 처럼 바이트 조각 글자로 내보낼 때 원래 글("빠르게")로 되돌린다.
     * 이미 제대로 나온 한글·영문은 그대로 둔다.
     */
    fun fixByteLevel(s: String): String {
        if (s.none { it.code in 0x80..0x143 }) return s
        val out = java.io.ByteArrayOutputStream()
        for (ch in s) {
            val b = if (ch.code >= 0x80) BYTE_OF[ch] else null
            if (b != null) out.write(b) else out.write(ch.toString().toByteArray(Charsets.UTF_8))
        }
        return out.toString("UTF-8")
    }

    /** 한자·깨진 글자가 섞였거나 한글이 거의 없으면 쓰지 않는다. */
    fun isCleanKorean(s: String): Boolean {
        if (s.isBlank()) return false
        if (s.any { it == '�' || it.code in 0x80..0x24F || it in '一'..'鿿' || it in '぀'..'ヿ' }) return false
        val letters = s.count { it.isLetter() }
        val hangul = s.count { it in '가'..'힣' }
        return letters == 0 || hangul * 2 >= letters
    }

    private val FILLERS = setOf(
        "어", "아", "음", "응", "그", "저", "이제", "막", "좀", "뭐", "그냥", "아니", "아니야", "진짜", "근데", "그럼", "그러면", "그래서",
        "네", "예", "어어", "아아", "에", "야", "뭐지", "있잖아", "약간", "되게", "완전", "혹시", "일단",
    )

    /**
     * 말버릇·더듬은 말을 뺀다: "어 토 아니 토닉워터 사와야 돼 그럼" → "토닉워터 사와야 돼".
     * 다음 낱말의 앞부분만 말하다 만 것("토" → "토닉워터")도 뺀다.
     */
    fun tidy(s: String): String {
        val words = s.replace(Regex("[,.]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() && it !in FILLERS }
        val kept = words.filterIndexed { i, w ->
            val next = words.getOrNull(i + 1)
            !(next != null && w.length < next.length && next.startsWith(w))
        }
        return kept.joinToString(" ").trim()
    }

    /** 01090118081 → 010-9011-8081 */
    fun phone(s: String): String {
        val d = s.filter { it.isDigit() }
        if (d.length != s.length) return s
        return when {
            d.length == 11 && d.startsWith("01") -> "${d.substring(0, 3)}-${d.substring(3, 7)}-${d.substring(7)}"
            d.length == 10 && d.startsWith("02") -> "${d.substring(0, 2)}-${d.substring(2, 6)}-${d.substring(6)}"
            d.length == 10 -> "${d.substring(0, 3)}-${d.substring(3, 6)}-${d.substring(6)}"
            else -> s
        }
    }
}
