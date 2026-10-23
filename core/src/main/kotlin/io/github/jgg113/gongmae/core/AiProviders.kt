package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URLEncoder

/**
 * 'AI 실시간 분석' 버튼이 쓰는 곳. 설정에서 고른다.
 * - [BUILT_IN]: 앱 안의 규칙 기반 분석 (무료, 인터넷 필요 없음)
 * - [GEMINI_API]: Google AI Studio 무료 API 키로 앱 안에서 바로 답을 받는다
 * - 나머지: 질문을 해당 앱(채팅)으로 넘긴다. 그 앱의 내 계정 사용량을 쓰고, 이 앱은 비용을 쓰지 않는다.
 */
enum class AiProvider(
    val label: String,
    val short: String,
    val description: String,
    val packageName: String? = null,
    /** 앱이 없을 때 여는 웹 주소. 질문을 주소에 넣을 수 있으면 {q} 자리에 넣는다. */
    val webUrl: String? = null,
) {
    BUILT_IN("앱 내장 분석", "내장", "인터넷·계정 없이 바로 나오는 규칙 기반 분석입니다. 무료이고 결과가 항상 같습니다."),
    GEMINI_API("Gemini (무료 API 키)", "Gemini", "Google AI Studio 에서 무료 API 키를 받아 넣으면 앱 안에서 바로 AI 답변을 받습니다. 무료 한도 안에서 비용이 없습니다."),
    CHATGPT_APP("ChatGPT 앱", "ChatGPT", "질문을 ChatGPT 앱으로 넘깁니다. 무료 계정으로도 쓸 수 있습니다.", "com.openai.chatgpt", "https://chatgpt.com/?q={q}"),
    GEMINI_APP("Gemini 앱", "Gemini 앱", "질문을 Google Gemini 앱으로 넘깁니다. Google 계정만 있으면 무료입니다.", "com.google.android.apps.bard", "https://gemini.google.com/app"),
    CLAUDE_APP("Claude 앱", "Claude", "질문을 Claude 앱으로 넘깁니다. 무료·유료 요금제의 사용 한도 안에서 쓰입니다.", "com.anthropic.claude", "https://claude.ai/new?q={q}"),
    ;

    val isAppHandoff: Boolean get() = packageName != null

    /** 웹으로 열 때 주소 ({q} 자리에 줄인 질문) */
    fun webLink(prompt: String): String? = webUrl?.replace(
        "{q}",
        URLEncoder.encode(ClaudePrompt.forUrl(prompt), "UTF-8").replace("+", "%20"),
    )
}

class AiException(message: String, val retryable: Boolean = false) : Exception(message)

/**
 * Gemini API (generativelanguage.googleapis.com). 키는 aistudio.google.com 에서 무료로 받는다.
 * 모델 이름은 바뀔 수 있어 별칭(…-latest)을 먼저 쓰고, 없다고 하면 다음 후보로 넘어간다.
 */
class GeminiClient(
    apiKey: String,
    private val transport: WebTransport = UrlConnectionTransport(),
    private val models: List<String> = DEFAULT_MODELS,
) {
    private val key = apiKey.trim()

    data class Answer(val text: String, val model: String)

    fun generate(prompt: String): Answer {
        if (key.isEmpty()) throw AiException("설정에서 Gemini API 키를 먼저 넣으세요.")
        var lastError: AiException? = null
        for (model in models) {
            val response = try {
                transport.post(
                    "$BASE/$model:generateContent",
                    mapOf("Content-Type" to "application/json; charset=UTF-8", "x-goog-api-key" to key),
                    requestBody(prompt),
                )
            } catch (e: IOException) {
                throw AiException("인터넷 연결을 확인하세요. (${e.message ?: e.javaClass.simpleName})", retryable = true)
            }
            if (response.status == 404) {
                lastError = AiException("모델 $model 을 찾지 못했습니다.")
                continue
            }
            return Answer(parse(response.status, response.body), model)
        }
        throw lastError ?: AiException("사용할 수 있는 Gemini 모델이 없습니다.")
    }

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        const val KEY_PAGE = "https://aistudio.google.com/apikey"
        val DEFAULT_MODELS = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun requestBody(prompt: String): String = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
                })
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.3)
                put("maxOutputTokens", 4096)
            }
        }.toString()

        /** 답 본문을 꺼낸다. 오류면 쉬운 설명을 담은 [AiException]. */
        fun parse(status: Int, body: String): String {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val error = root?.get("error") as? JsonObject
            if (status != 200 || error != null) {
                val message = error?.str("message").orEmpty()
                val reason = error?.str("status").orEmpty()
                throw when {
                    status == 400 && (message.contains("API key", ignoreCase = true) || reason == "INVALID_ARGUMENT" && message.contains("key", ignoreCase = true)) ->
                        AiException("Gemini API 키가 올바르지 않습니다. 설정에서 키를 다시 확인하세요.")
                    status == 403 || reason == "PERMISSION_DENIED" -> AiException("이 키로 Gemini API 를 쓸 수 없습니다. AI Studio 에서 키와 API 사용 설정을 확인하세요.")
                    status == 429 || reason == "RESOURCE_EXHAUSTED" -> AiException("무료 사용 한도를 넘었습니다. 1분쯤 뒤에 다시 시도하거나 내일 다시 쓰세요.", retryable = true)
                    status >= 500 -> AiException("Gemini 서버가 잠시 응답하지 않습니다. 잠시 후 다시 시도하세요.", retryable = true)
                    else -> AiException("Gemini 오류 ($status): ${message.ifBlank { body.take(120) }}")
                }
            }
            val candidate = (root?.get("candidates") as? JsonArray)?.firstOrNull() as? JsonObject
            val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
            // 조각 사이 줄바꿈을 지키려고 trim 하지 않고 이어 붙인다.
            val text = parts.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }.joinToString("").trim()
            if (text.isEmpty()) {
                val finish = candidate?.str("finishReason").orEmpty()
                throw AiException(if (finish == "SAFETY") "답변이 안전 필터에 걸려 나오지 않았습니다. 다시 시도해 보세요." else "Gemini 가 빈 답을 보냈습니다. 다시 시도해 보세요.", retryable = true)
            }
            return text
        }
    }
}

/** 답변의 마크다운 기호(**, #, ```)를 걷어 앱 화면에서 읽기 좋게 만든다. */
object AiText {
    fun plain(markdown: String): String = markdown
        .replace(Regex("```[a-zA-Z]*\\n?"), "")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace("**", "")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

/** '앱 내장 분석'을 골랐을 때 보여 주는 글 (규칙 기반 점수를 문장으로 풀어 쓴다). */
object LocalReport {
    fun build(p: Property, a: Analysis, range: Pair<Long, Long>?, checklist: List<CheckItem>, mode: ClaudePrompt.Mode): String = buildString {
        val verdict = when {
            a.riskLevel == RiskLevel.DANGER -> "비추천 — 위험 요소를 서류로 해결할 수 있을 때만 검토하세요."
            a.riskLevel == RiskLevel.CAUTION -> "조건부 — 아래 확인 사항을 해결하면 입찰을 검토할 만합니다."
            a.total >= 60 -> "입찰 검토 추천 — 큰 위험 신호가 없고 가격 매력이 있습니다."
            else -> "보통 — 위험은 낮지만 가격 매력이 크지 않습니다."
        }
        appendLine("결론: $verdict")
        appendLine("종합 ${a.total}점(${a.grade}) · 위험도 ${a.riskLevel.label} ${a.riskScore} · 투자매력 ${a.attractiveness}")
        appendLine()
        val risks = a.risks.take(if (mode == ClaudePrompt.Mode.DETAIL) 8 else 3)
        appendLine("위험 요소")
        if (risks.isEmpty()) appendLine("• 공개 정보에서 뚜렷한 위험 신호를 찾지 못했습니다.")
        risks.forEach { appendLine(if (mode == ClaudePrompt.Mode.DETAIL) "• ${it.title}: ${it.detail}" else "• ${it.title}") }
        appendLine()
        val merits = a.merits.take(if (mode == ClaudePrompt.Mode.DETAIL) 6 else 3)
        appendLine("장점")
        if (merits.isEmpty()) appendLine("• 뚜렷한 가격 장점은 없습니다.")
        merits.forEach { appendLine(if (mode == ClaudePrompt.Mode.DETAIL) "• ${it.title}: ${it.detail}" else "• ${it.title}") }
        if (range != null) {
            appendLine()
            appendLine("적정 입찰가 (참고): ${Format.won(range.first)} ~ ${Format.won(range.second)}")
        }
        val important = checklist.filter { it.important }.ifEmpty { checklist.take(3) }
        if (important.isNotEmpty()) {
            appendLine()
            appendLine("입찰 전 꼭 확인")
            important.take(if (mode == ClaudePrompt.Mode.DETAIL) 8 else 4).forEach { appendLine("• ${it.title}") }
        }
        if (p.category.isMovable) {
            appendLine()
            appendLine("차량·동산은 사진·현장 확인과 중고 시세 비교가 가장 중요합니다.")
        }
    }.trim()
}
