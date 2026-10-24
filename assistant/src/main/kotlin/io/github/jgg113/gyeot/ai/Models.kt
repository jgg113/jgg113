package io.github.jgg113.gyeot.ai

import kotlinx.serialization.Serializable

/** Claude 가 대화 한 묶음을 정리해 돌려주는 결과 (구조화 출력 스키마와 1:1). */
@Serializable
data class Analysis(
    val summary: String = "",
    /** "high" 이면 바로 알림, 아니면 하루 요약에만 담는다. */
    val importance: String = "normal",
    val keyPoints: List<String> = emptyList(),
    val events: List<ExtractedEvent> = emptyList(),
    val todos: List<Todo> = emptyList(),
)

@Serializable
data class ExtractedEvent(
    val title: String,
    /** 한국 시간 "YYYY-MM-DDTHH:mm" (종일 일정이면 "YYYY-MM-DD"). */
    val start: String,
    /** 끝 시각, 모르면 빈 문자열. */
    val end: String = "",
    val allDay: Boolean = false,
    val location: String = "",
    /** 미리 알림 몇 분 전. 0 이하이면 설정의 기본값을 쓴다. */
    val reminderMinutesBefore: Int = 0,
    /** 0~1, 실제로 잡힌 약속일 가능성. */
    val confidence: Double = 0.0,
    /** 근거가 된 말 그대로. */
    val evidence: String = "",
    /** 이미 있는 일정의 시각이 바뀌었다는 말이면 true (예전 것을 지우고 새로 넣는다). */
    val isChange: Boolean = false,
    /** 할 일·약속은 들었지만 언제인지 못 들었으면 true (start 는 임시 값, 꼭 물어본다). */
    val timeUnknown: Boolean = false,
)

@Serializable
data class Todo(
    val text: String,
    /** 기한 "YYYY-MM-DDTHH:mm" 또는 "YYYY-MM-DD", 없으면 빈 문자열. */
    val due: String = "",
)
