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
 * - API(키를 넣는 것): 앱 안에서 바로 답을 받는다. 여러 개를 골라 'AI 비교'로 나란히 볼 수 있다.
 * - 앱(…_APP): 질문을 해당 앱(채팅)으로 넘긴다. 그 앱의 내 계정 사용량을 쓰고, 이 앱은 비용을 쓰지 않는다.
 */
enum class AiProvider(
    val label: String,
    val short: String,
    val description: String,
    val packageName: String? = null,
    /** 앱이 없을 때 여는 웹 주소. 질문을 주소에 넣을 수 있으면 {q} 자리에 넣는다. */
    val webUrl: String? = null,
    /** API 키를 받는 곳 (API 방식만) */
    val keyPage: String? = null,
) {
    BUILT_IN("앱 내장 분석", "내장", "인터넷·계정 없이 바로 나오는 규칙 기반 분석입니다. 무료이고 결과가 항상 같습니다."),
    GEMINI_API("Gemini (무료 API 키)", "Gemini", "Google AI Studio 에서 무료 API 키를 받아 넣으면 앱 안에서 바로 AI 답변을 받습니다. 결제(빌링)를 연결하지 않은 키는 한도를 넘으면 멈출 뿐 요금이 나가지 않습니다.", keyPage = "https://aistudio.google.com/apikey"),
    GROQ_API("Groq · ChatGPT 계열 공개모델 (무료 키)", "GPT-OSS", "Groq 무료 키로 OpenAI 가 공개한 ChatGPT 계열 모델(gpt-oss-120b)과 Llama 를 앱 안에서 씁니다. 카드 등록 없이 하루 한도 안에서만 쓰이고 넘으면 멈출 뿐 요금은 나가지 않습니다.", keyPage = "https://console.groq.com/keys"),
    OPENROUTER_API("OpenRouter (무료 모델만)", "OpenRouter", "OpenRouter 키로 이름 끝이 :free 인 무료 모델만 씁니다. 크레딧을 충전하지 않으면 요금이 나갈 수 없고, 하루 횟수 제한(약 50회)이 있습니다.", keyPage = "https://openrouter.ai/keys"),
    CHATGPT_APP("ChatGPT 앱", "ChatGPT 앱", "질문을 ChatGPT 앱으로 넘깁니다. 무료 계정으로 쓸 수 있습니다 (답은 ChatGPT 앱에서 봅니다).", "com.openai.chatgpt", "https://chatgpt.com/?q={q}"),
    GEMINI_APP("Gemini 앱", "Gemini 앱", "질문을 Google Gemini 앱으로 넘깁니다. Google 계정만 있으면 무료입니다.", "com.google.android.apps.bard", "https://gemini.google.com/app"),
    CLAUDE_APP("Claude 앱", "Claude", "질문을 Claude 앱으로 넘깁니다. 무료·유료 요금제의 사용 한도 안에서 쓰입니다.", "com.anthropic.claude", "https://claude.ai/new?q={q}"),
    ;

    val isAppHandoff: Boolean get() = packageName != null

    /** 키를 넣어 앱 안에서 답을 받는 방식 */
    val isApi: Boolean get() = keyPage != null

    /** 웹으로 열 때 주소 ({q} 자리에 줄인 질문) */
    fun webLink(prompt: String): String? = webUrl?.replace(
        "{q}",
        URLEncoder.encode(ClaudePrompt.forUrl(prompt), "UTF-8").replace("+", "%20"),
    )

    companion object {
        val apis: List<AiProvider> get() = entries.filter { it.isApi }
    }
}

/** AI 답 하나 */
data class AiAnswer(val text: String, val model: String)

/** 앱 안에서 답을 받는 AI (Gemini, OpenAI 호환) */
fun interface AiEngine {
    fun generate(prompt: String): AiAnswer
}

/** 저장해 둔 키로 엔진을 만든다. 키가 없으면 null. */
object AiEngines {
    fun create(provider: AiProvider, key: String): AiEngine? {
        if (key.isBlank()) return null
        return when (provider) {
            AiProvider.GEMINI_API -> GeminiClient(key)
            AiProvider.GROQ_API -> OpenAiCompatClient(
                "Groq", "https://api.groq.com/openai/v1/chat/completions", key,
                listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b", "llama-3.1-8b-instant"),
            )
            AiProvider.OPENROUTER_API -> OpenAiCompatClient(
                "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", key,
                listOf("openai/gpt-oss-20b:free", "meta-llama/llama-3.3-70b-instruct:free", "deepseek/deepseek-chat-v3.1:free", "google/gemma-3-27b-it:free"),
                extraHeaders = mapOf("HTTP-Referer" to "https://github.com/jgg113/jgg113", "X-Title" to "Gongmae Compare"),
            )
            else -> null
        }
    }
}

/** 답의 최종 의견 (AI 비교 표에 쓴다) */
enum class AiVerdict(val label: String) {
    RECOMMEND("입찰 추천"),
    CONDITIONAL("조건부"),
    AVOID("비추천"),
    UNKNOWN("의견 없음");

    companion object {
        /** '결론'·'최종 의견'이 들어간 줄을 먼저 보고, 없으면 글 전체에서 찾는다. */
        fun of(text: String): AiVerdict {
            // 질문의 "(입찰 추천 / 조건부 / 비추천)" 을 그대로 옮겨 적은 줄은 뺀다.
            val lines = text.lines().filterNot { it.contains("조건부") && Regex("비\\s*추천").containsMatchIn(it) && it.contains("/") }
            val focus = lines.filter { Regex("결론|최종\\s*의견|종합\\s*의견|판단").containsMatchIn(it) }
            for (scope in listOf(focus.joinToString(" "), lines.take(6).joinToString(" "), lines.joinToString(" "))) {
                val found = classify(scope)
                if (found != UNKNOWN) return found
            }
            return UNKNOWN
        }

        private fun classify(text: String): AiVerdict = when {
            text.isBlank() -> UNKNOWN
            Regex("비\\s*추천|추천하지\\s*않|입찰하지\\s*않|권하지\\s*않|피하는\\s*게|피하세요|보류").containsMatchIn(text) -> AVOID
            Regex("조건부|조건\\s*하에|확인\\s*후\\s*(입찰|검토)").containsMatchIn(text) -> CONDITIONAL
            Regex("추천|적극\\s*검토|입찰해\\s*볼\\s*만").containsMatchIn(text) -> RECOMMEND
            else -> UNKNOWN
        }
    }
}

/**
 * OpenAI 방식 채팅 API (OpenAI·Groq·OpenRouter 가 같은 모양을 쓴다).
 * 모델 이름이 바뀌거나 없어지면 다음 후보로 넘어간다.
 */
class OpenAiCompatClient(
    private val name: String,
    private val endpoint: String,
    apiKey: String,
    private val models: List<String>,
    private val transport: WebTransport = UrlConnectionTransport(),
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** OpenAI 추론 모델만 받는 값 (gpt-5 계열 속도를 올린다) */
    private val reasoningEffort: String? = null,
) : AiEngine {
    private val key = apiKey.trim()

    override fun generate(prompt: String): AiAnswer {
        if (key.isEmpty()) throw AiException("설정에서 $name API 키를 먼저 넣으세요.")
        var lastError: AiException? = null
        for (model in models) {
            val response = try {
                transport.post(
                    endpoint,
                    mapOf("Content-Type" to "application/json; charset=UTF-8", "Authorization" to "Bearer $key") + extraHeaders,
                    requestBody(model, prompt, reasoningEffort.takeIf { model.startsWith("gpt-5") }),
                )
            } catch (e: IOException) {
                throw AiException("인터넷 연결을 확인하세요. (${e.message ?: e.javaClass.simpleName})", retryable = true)
            }
            try {
                return AiAnswer(parse(name, response.status, response.body), model)
            } catch (e: ModelUnavailable) {
                lastError = AiException("$name 모델 $model 을 쓸 수 없습니다.")
            }
        }
        throw lastError ?: AiException("사용할 수 있는 $name 모델이 없습니다.")
    }

    /** 모델이 없거나 바뀌었을 때 (다음 후보로 넘어간다) */
    class ModelUnavailable : Exception()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun requestBody(model: String, prompt: String, reasoningEffort: String? = null): String = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            if (reasoningEffort != null) put("reasoning_effort", reasoningEffort)
        }.toString()

        fun parse(name: String, status: Int, body: String): String {
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val error = root?.get("error") as? JsonObject
            if (status != 200 || error != null) {
                val message = error?.str("message").orEmpty()
                val code = error?.str("code").orEmpty() + " " + error?.str("type").orEmpty()
                val modelProblem = status == 404 || code.contains("model_not_found") ||
                    (status == 400 && Regex("model", RegexOption.IGNORE_CASE).containsMatchIn(message) &&
                        Regex("not (found|exist|available|supported)|decommission|does not exist|invalid model|no endpoints", RegexOption.IGNORE_CASE).containsMatchIn(message))
                if (modelProblem) throw ModelUnavailable()
                throw when {
                    status == 401 -> AiException("$name API 키가 올바르지 않습니다. 설정에서 키를 다시 확인하세요.")
                    code.contains("insufficient_quota") || status == 402 -> AiException("$name 계정에 남은 크레딧(결제 수단)이 없습니다. 사이트의 결제 설정을 확인하세요.")
                    status == 429 -> AiException("$name 사용 한도를 넘었습니다. 잠시 뒤 다시 시도하세요.", retryable = true)
                    status == 403 -> AiException("$name 에서 이 키로 요청할 수 없습니다: ${message.take(120)}")
                    status >= 500 -> AiException("$name 서버가 잠시 응답하지 않습니다. 잠시 후 다시 시도하세요.", retryable = true)
                    else -> AiException("$name 오류 ($status): ${message.ifBlank { body.take(120) }}")
                }
            }
            val choice = (root?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
            val message = choice?.get("message") as? JsonObject
            val text = (message?.get("content") as? JsonPrimitive)?.content?.trim().orEmpty()
            if (text.isEmpty()) throw AiException("$name 가 빈 답을 보냈습니다. 다시 시도해 보세요.", retryable = true)
            return text
        }
    }
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
) : AiEngine {
    private val key = apiKey.trim()

    override fun generate(prompt: String): AiAnswer {
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
            return AiAnswer(parse(response.status, response.body), model)
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
