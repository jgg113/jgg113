package io.github.jgg113.gyeot.stt

/**
 * Whisper 계열 인식기는 잡음·무음 구간에서 방송 자막 같은 문장을 지어내는 일이 잦다
 * (특히 한국어: "시청해 주셔서 감사합니다", "구독과 좋아요" 등). 그런 줄은 버린다.
 */
object TranscriptFilter {
    private val hallucinations = listOf(
        "시청해 주셔서 감사합니다", "시청해주셔서 감사합니다", "시청 해주셔서 감사합니다",
        "구독과 좋아요", "구독 좋아요", "좋아요와 구독", "알림 설정",
        "자막 제공", "자막 by", "한글자막", "자막은 설정에서",
        "MBC 뉴스", "KBS 뉴스", "SBS 뉴스", "YTN", "뉴스였습니다",
        "다음 영상에서 만나요", "오늘도 시청", "감사합니다.감사합니다",
    )

    fun clean(raw: String): String? {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.length < 2) return null
        val compact = text.replace(" ", "")
        if (hallucinations.any { compact.contains(it.replace(" ", ""), ignoreCase = true) } && text.length < 40) return null
        // 같은 짧은 말이 끝없이 되풀이되는 것도 지어낸 결과다 ("네 네 네 네 네 ...").
        val words = text.split(" ")
        if (words.size >= 6 && words.toSet().size <= 2) return null
        if (!text.any { it in '가'..'힣' || it.isLetterOrDigit() }) return null
        return text
    }
}
