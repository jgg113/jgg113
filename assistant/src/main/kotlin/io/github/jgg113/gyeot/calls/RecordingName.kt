package io.github.jgg113.gyeot.calls

import io.github.jgg113.gyeot.ai.EventRules
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 삼성 전화 앱 자동 통화 녹음 파일 이름에서 상대와 시각을 읽는다.
 * 예) "통화 녹음 엄마_241024_153012.m4a", "Call recording 김민수_241024_153012.m4a", "010-1234-5678_241024_153012.m4a"
 */
object RecordingName {
    data class Info(val who: String, val startMillis: Long?)

    private val PATTERN = Regex("""^(?:통화\s*녹음|Call\s*recording)?\s*(.*?)[_ ](\d{6})[_ ](\d{6})(?:\(\d+\))?$""", RegexOption.IGNORE_CASE)
    private val STAMP = DateTimeFormatter.ofPattern("yyMMddHHmmss")

    fun parse(fileName: String): Info {
        val base = fileName.substringBeforeLast('.').trim()
        val m = PATTERN.find(base)
            ?: return Info(base.removePrefix("통화 녹음").removePrefix("Call recording").trim().ifBlank { "알 수 없음" }, null)
        val who = m.groupValues[1].trim().ifBlank { "알 수 없음" }
        val at = runCatching { LocalDateTime.parse(m.groupValues[2] + m.groupValues[3], STAMP).atZone(EventRules.SEOUL).toInstant().toEpochMilli() }.getOrNull()
        return Info(who, at)
    }

    /** 통화 녹음 폴더인지 (삼성: Recordings/Call, 옛 버전: Call, Sounds/Call). */
    fun isCallFolder(relativePath: String?): Boolean {
        val p = relativePath.orEmpty().lowercase()
        return p.contains("recordings/call") || p.startsWith("call/") || p.contains("/call/") || p.contains("통화")
    }
}
