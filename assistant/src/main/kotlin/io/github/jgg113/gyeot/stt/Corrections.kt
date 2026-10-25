package io.github.jgg113.gyeot.stt

/**
 * 사용자가 정한 "틀린말=바른말" 규칙으로 받아쓴 글을 고친다.
 * 휴대폰 안 모델이 자꾸 틀리는 이름·낱말(예: "민스=민수")을 한 번 적어 두면 그 뒤로는 바로잡힌다.
 * 띄어쓰기 차이는 무시하고 찾는다 ("토닉 워터" 규칙은 "토닉워터"에도 맞는다).
 */
object Corrections {
    data class Rule(val wrong: String, val right: String)

    fun parse(text: String): List<Rule> = text.lines().mapNotNull { line ->
        val i = line.indexOf('=').takeIf { it > 0 } ?: line.indexOf('→').takeIf { it > 0 } ?: return@mapNotNull null
        val wrong = line.substring(0, i).trim()
        val right = line.substring(i + 1).trim()
        if (wrong.isEmpty() || wrong == right) null else Rule(wrong, right)
    }

    /**
     * 사용자가 고친 줄에서 바뀐 낱말을 찾아 규칙으로 만든다.
     * 낱말 수가 같으면 자리마다 비교하고, 다르면 앞뒤 같은 부분을 뺀 가운데를 하나의 규칙으로.
     */
    fun learn(before: String, after: String): List<Rule> {
        val a = before.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val b = after.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (a.isEmpty() || b.isEmpty() || a == b) return emptyList()
        if (a.size == b.size) return a.indices.filter { a[it] != b[it] }.map { Rule(a[it], b[it]) }.filter { it.wrong.length >= 2 }
        var head = 0
        while (head < minOf(a.size, b.size) && a[head] == b[head]) head++
        var tail = 0
        while (tail < minOf(a.size, b.size) - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
        val wrong = a.subList(head, a.size - tail).joinToString(" ")
        val right = b.subList(head, b.size - tail).joinToString(" ")
        return if (wrong.length >= 2 && right.isNotEmpty() && wrong.length <= 20) listOf(Rule(wrong, right)) else emptyList()
    }

    fun apply(text: String, rules: List<Rule>): String {
        var out = text
        for (r in rules.sortedByDescending { it.wrong.length }) {
            val pattern = r.wrong.filter { !it.isWhitespace() }.map { Regex.escape(it.toString()) }.joinToString("\\s*")
            out = out.replace(Regex(pattern), Regex.escapeReplacement(r.right))
        }
        return out
    }
}
