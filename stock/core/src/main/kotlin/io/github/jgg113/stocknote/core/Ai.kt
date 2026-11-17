package io.github.jgg113.stocknote.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder

/**
 * AI 해설을 받을 곳. 키를 넣는 API 는 앱 안에서 답을 받고, 앱(…_APP)은 질문을 그 앱으로 넘긴다(그 앱 계정 사용량을 쓴다).
 * 모두 무료로 쓸 수 있는 방법만 둔다.
 */
enum class AiProvider(
    val label: String,
    val description: String,
    val packageName: String? = null,
    val webUrl: String? = null,
    val keyPage: String? = null,
) {
    NONE("쓰지 않음", "통계 예측과 전략 신호만 봅니다."),
    GEMINI_API("Gemini (무료 API 키)", "Google AI Studio 무료 키. 결제 수단을 연결하지 않으면 한도를 넘어도 멈출 뿐 요금이 나가지 않습니다.", keyPage = "https://aistudio.google.com/apikey"),
    GROQ_API("Groq 공개 모델 (무료 키)", "Groq 무료 키로 공개 모델(gpt-oss·Llama)을 씁니다. 카드 등록 없이 하루 한도 안에서만 쓰입니다.", keyPage = "https://console.groq.com/keys"),
    OPENROUTER_API("OpenRouter (무료 모델만)", "이름 끝이 :free 인 무료 모델만 씁니다. 크레딧을 충전하지 않으면 요금이 나갈 수 없습니다.", keyPage = "https://openrouter.ai/keys"),
    CHATGPT_APP("ChatGPT 앱으로 넘기기", "질문을 ChatGPT 앱으로 넘깁니다 (답은 그 앱에서 봅니다).", "com.openai.chatgpt", "https://chatgpt.com/?q={q}"),
    GEMINI_APP("Gemini 앱으로 넘기기", "질문을 Gemini 앱으로 넘깁니다.", "com.google.android.apps.bard", "https://gemini.google.com/app"),
    CLAUDE_APP("Claude 앱으로 넘기기", "질문을 Claude 앱으로 넘깁니다.", "com.anthropic.claude", "https://claude.ai/new?q={q}"),
    ;

    val isApi: Boolean get() = keyPage != null
    val isAppHandoff: Boolean get() = packageName != null

    /** 다른 곳의 키를 넣은 것 같을 때 알려 줄 말. 문제없으면 null */
    fun keyHint(key: String): String? = when {
        key.isEmpty() -> null
        this == GEMINI_API && (key.startsWith("gsk_") || key.startsWith("sk-")) -> "Gemini 키가 아닌 것 같습니다. Google AI Studio 키는 보통 AIza 로 시작합니다."
        this == GROQ_API && !key.startsWith("gsk_") -> "Groq 키는 gsk_ 로 시작합니다. 다른 곳의 키가 아닌지 확인하세요."
        this == OPENROUTER_API && !key.startsWith("sk-or-") -> "OpenRouter 키는 sk-or- 로 시작합니다. 다른 곳의 키가 아닌지 확인하세요."
        key.length < 20 -> "키가 너무 짧습니다. 끝까지 복사됐는지 확인하세요."
        else -> null
    }

    fun webLink(prompt: String): String? = webUrl?.replace("{q}", URLEncoder.encode(prompt.take(1500), "UTF-8").replace("+", "%20"))
}

data class AiAnswer(val text: String, val model: String)

fun interface AiEngine {
    fun generate(prompt: String): AiAnswer
}

/** AI 는 답을 다 만든 뒤에 한꺼번에 보내므로, 긴 해설은 1분 넘게 걸릴 수 있다. */
const val AI_READ_TIMEOUT_MS = 120_000

object AiEngines {
    fun create(provider: AiProvider, key: String, http: Http = NetHttp(readTimeoutMs = AI_READ_TIMEOUT_MS)): AiEngine? {
        if (key.isBlank()) return null
        return when (provider) {
            AiProvider.GEMINI_API -> GeminiClient(key, http)
            AiProvider.GROQ_API -> OpenAiCompatClient(
                "Groq", "https://api.groq.com/openai/v1/chat/completions", key,
                listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b"), http,
            )
            AiProvider.OPENROUTER_API -> OpenAiCompatClient(
                "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", key,
                // openrouter/free 는 그때그때 쓸 수 있는 무료 모델로 알아서 보내 준다. 뒤의 둘은 그것이 막혔을 때 쓴다.
                listOf("openrouter/free", "google/gemma-4-31b-it:free", "nvidia/nemotron-3-ultra-550b-a55b:free"), http,
                mapOf("X-Title" to "Stock Note"),
                discover = { OpenRouterModels.free(http) },
            )
            else -> null
        }
    }
}

class OpenAiCompatClient(
    private val name: String,
    private val endpoint: String,
    apiKey: String,
    private val models: List<String>,
    private val http: Http = NetHttp(readTimeoutMs = AI_READ_TIMEOUT_MS),
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** 정해 둔 모델이 모두 안 될 때, 지금 쓸 수 있는 모델 이름을 알아 오는 방법 (없으면 null) */
    private val discover: (() -> List<String>)? = null,
) : AiEngine {
    private val key = KeyText.clean(apiKey)

    override fun generate(prompt: String): AiAnswer {
        var last: DataException? = null
        // 무료 모델은 이름이 자주 바뀐다. 정해 둔 것이 모두 안 되면 지금 있는 모델을 찾아 이어서 해 본다.
        val more = lazy { runCatching { discover?.invoke().orEmpty() }.getOrDefault(emptyList()).filter { it !in models }.take(4) }
        val queue = sequence { yieldAll(models); yieldAll(more.value) }
        for (model in queue) {
            val body = buildJsonObject {
                put("model", model)
                putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
            }.toString()
            val r = network(name) { http.post(endpoint, mapOf("Content-Type" to "application/json; charset=UTF-8", "Authorization" to "Bearer $key") + extraHeaders, body) }
            val root = parseJson(r.body).obj()
            val error = root?.get("error").obj()
            if (r.status != 200 || error != null) {
                val message = error?.str("message").orEmpty()
                val e = when {
                    r.status == 401 -> throw DataException("$name API 키가 맞지 않습니다. 키를 처음부터 끝까지 다시 복사해 넣어 보세요.", "AI_KEY")
                    r.status == 402 -> DataException("$name 계정에 남은 크레딧이 없습니다.")
                    r.status == 404 || (r.status == 400 && message.contains("model", true)) -> DataException("$name 모델 $model 을 쓸 수 없습니다.")
                    r.status == 429 -> DataException("$name 사용 한도를 넘었습니다. 잠시 뒤 다시 시도하세요.", retryable = true)
                    else -> DataException("$name 오류 (${r.status}): ${message.ifBlank { r.body.take(120) }}", retryable = r.status >= 500)
                }
                // 한도·모델 문제는 모델마다 다르므로 다음 모델로 넘어가 본다.
                if (last == null || r.status != 404) last = e
                continue
            }
            val text = ((root?.get("choices") as? JsonArray)?.firstOrNull().obj()?.get("message").obj()?.get("content") as? JsonPrimitive)?.content?.trim().orEmpty()
            if (text.isEmpty()) { last = DataException("$name 가 빈 답을 보냈습니다. 다시 시도해 보세요.", retryable = true); continue }
            return AiAnswer(text, model)
        }
        throw last ?: DataException("사용할 수 있는 $name 모델이 없습니다.")
    }
}

class GeminiClient(apiKey: String, private val http: Http = NetHttp(readTimeoutMs = AI_READ_TIMEOUT_MS), private val models: List<String> = DEFAULT_MODELS) : AiEngine {
    private val key = KeyText.clean(apiKey)

    private fun body(prompt: String, thinking: Boolean) = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
            })
        }
        putJsonObject("generationConfig") {
            put("temperature", 0.3)
            put("maxOutputTokens", 8192)
            // Gemini 3 계열은 답하기 전에 '생각'을 하는데 기본값(중간)이면 오래 걸린다. 숫자를 풀어 쓰는 일이라 짧게 생각해도 충분하다.
            if (thinking) putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
        }
    }.toString()

    private val headers get() = mapOf("Content-Type" to "application/json; charset=UTF-8", "x-goog-api-key" to key)

    override fun generate(prompt: String): AiAnswer {
        var last: DataException? = null
        fun attempt(model: String): AiAnswer? {
            var r = network("Gemini") { http.post("$BASE/$model:generateContent", headers, body(prompt, thinking = true)) }
            // 생각 설정을 모르는 모델이면 빼고 다시 보낸다.
            if (r.status == 400 && r.body.contains("thinking", ignoreCase = true)) r = network("Gemini") { http.post("$BASE/$model:generateContent", headers, body(prompt, thinking = false)) }
            if (r.status == 404) { if (last == null) last = DataException("Gemini 모델 $model 을 찾지 못했습니다."); return null }
            return try {
                AiAnswer(parse(r.status, r.body), model)
            } catch (e: DataException) {
                // 키가 틀린 것은 어느 모델이든 같으니 바로 알린다. 한도·권한·서버 문제는 모델마다 달라 다음 모델로 넘어간다.
                if (e.code == "AI_KEY") throw e
                last = e
                null
            }
        }
        for (model in models) attempt(model)?.let { return it }
        // 정해 둔 이름이 모두 안 되면, 이 키로 쓸 수 있는 모델을 물어 다시 해 본다.
        for (model in runCatching { discover() }.getOrDefault(emptyList()).filter { it !in models }.take(3)) attempt(model)?.let { return it }
        throw last ?: DataException("사용할 수 있는 Gemini 모델이 없습니다.")
    }

    /** 이 키로 글을 만들 수 있는 flash 계열 모델 이름 (정식판·새 버전 먼저) */
    fun discover(): List<String> {
        val r = network("Gemini") { http.get("$BASE?pageSize=200", mapOf("x-goog-api-key" to key)) }
        return parseModels(r.body)
    }

    /**
     * 연결 테스트 1단계: 답을 만들지 않고 모델 목록만 받아, 서버에 닿는지와 키가 맞는지를 몇 초 안에 가린다.
     * 쓸 수 있는 모델 수를 돌려준다.
     */
    fun checkKey(): Int {
        val r = network("Gemini") { http.get("$BASE?pageSize=200", mapOf("x-goog-api-key" to key)) }
        if (r.status != 200) parse(r.status, r.body)
        return parseModels(r.body).size
    }

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        // 2.5 계열은 새 키로는 쓸 수 없게 되었다(2026-11 확인). 이름이 또 바뀌어도 아래 목록이 모두 안 되면 쓸 수 있는 모델을 물어 찾는다.
        val DEFAULT_MODELS = listOf("gemini-flash-latest", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite")
        private val SKIP = listOf("image", "tts", "audio", "live", "embedding", "robotics", "computer")

        fun parseModels(body: String): List<String> =
            (parseJson(body).obj()?.get("models") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { m -> (m["supportedGenerationMethods"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.content == "generateContent" } }
                .map { it.str("name").removePrefix("models/") }
                .filter { n -> n.startsWith("gemini") && "flash" in n && SKIP.none { it in n } }
                .sortedWith(compareBy<String> { "preview" in it || "exp" in it }.thenBy { "lite" in it }.thenByDescending { it })

        fun parse(status: Int, body: String): String {
            val root = parseJson(body).obj()
            val error = root?.get("error").obj()
            if (status != 200 || error != null) {
                val message = error?.str("message").orEmpty()
                val reason = error?.str("status").orEmpty()
                throw when {
                    // 2026-05 부터 AI Studio 는 'AQ.' 로 시작하는 새 형식 키만 만든다. 일부 계정에서 이 키가 거절되는 알려진 문제가 있다.
                    status == 401 && body.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED") ->
                        DataException("Google 이 이 키 형식을 받아 주지 않았습니다 (ACCESS_TOKEN_TYPE_UNSUPPORTED). AI Studio 에서 키를 새로 만들어 넣거나, 키의 프로젝트에서 'Generative Language API' 가 켜져 있는지 확인하세요.", "AI_KEY")
                    status == 401 || ((status == 400 || status == 403) && (message.contains("API key", true) || message.contains("API_KEY", true))) ->
                        DataException("Gemini API 키가 맞지 않습니다. Google AI Studio 의 [API 키 세부정보]에서 복사 버튼으로 다시 복사해 넣어 보세요. (${message.take(100)})", "AI_KEY")
                    status == 403 || reason == "PERMISSION_DENIED" -> DataException("이 키로 Gemini API 를 쓸 수 없습니다: ${message.take(160).ifBlank { "권한 없음" }}", "AI_DENIED")
                    status == 429 || reason == "RESOURCE_EXHAUSTED" -> DataException("Gemini 무료 한도를 넘었습니다. 1분쯤 뒤나 내일 다시 쓰세요.", "AI_LIMIT", retryable = true)
                    status >= 500 -> DataException("Gemini 서버가 잠시 응답하지 않습니다.", retryable = true)
                    reason == "FAILED_PRECONDITION" -> DataException("이 지역·계정에서는 Gemini 무료 API 를 쓸 수 없습니다: ${message.take(160)}", "AI_DENIED")
                    else -> DataException("Gemini 오류 ($status): ${message.ifBlank { body.take(120) }}")
                }
            }
            val candidate = (root?.get("candidates") as? JsonArray)?.firstOrNull().obj()
            val parts = (candidate?.get("content").obj()?.get("parts") as? JsonArray).orEmpty()
            val text = parts.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }.joinToString("").trim()
            if (text.isEmpty()) {
                val why = candidate?.str("finishReason").orEmpty().ifBlank { root?.get("promptFeedback").obj()?.str("blockReason").orEmpty() }
                throw DataException("Gemini 가 빈 답을 보냈습니다${if (why.isNotBlank()) " ($why)" else ""}. 다시 시도해 보세요.", retryable = true)
            }
            return text
        }
    }
}

/** OpenRouter 가 공개하는 모델 목록(키 없이 볼 수 있다)에서 지금 무료인 글 모델을 고른다. */
object OpenRouterModels {
    const val URL = "https://openrouter.ai/api/v1/models"
    private val PREFER = listOf("gemma", "llama", "nemotron-3-ultra", "nemotron", "gpt-oss", "qwen", "deepseek", "mistral")
    private val SKIP = listOf("code", "nano", "guard", "embed", "tts", "-vl", "preview")

    fun free(http: Http): List<String> = parse(network("OpenRouter") { http.get(URL) }.body)

    fun parse(body: String): List<String> =
        (parseJson(body).obj()?.get("data") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { m ->
                val out = (m["architecture"].obj()?.get("output_modalities") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                m.str("id").endsWith(":free") && (out.isEmpty() || "text" in out)
            }
            .map { it.str("id") to (it.dbl("context_length") ?: 0.0) }
            .filter { (id, _) -> SKIP.none { it in id } }
            .sortedWith(compareBy<Pair<String, Double>> { (id, _) -> PREFER.indexOfFirst { it in id }.let { if (it < 0) PREFER.size else it } }.thenByDescending { it.second }.thenByDescending { it.first })
            .map { it.first }
}

/** 설정 화면의 [연결 테스트]: 짧은 질문 하나로 키가 되는지 본다. */
object AiTest {
    const val PROMPT = "한 문장으로 답해 줘: 주가수익비율(PER)이란?"
}

object AiText {
    fun plain(markdown: String): String = markdown
        .replace(Regex("```[a-zA-Z]*\\n?"), "")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace("**", "")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

/** AI 에게 보낼 질문을 만든다. 앱이 계산한 숫자를 그대로 넣어, AI 가 숫자를 지어내지 않게 한다. */
object StockPrompt {
    enum class Mode { SUMMARY, ISSUES, QUESTION }

    fun build(
        asset: Asset,
        quote: Quote?,
        candles: List<Candle>,
        forecast: ForecastReport?,
        report: IndicatorReport?,
        financials: Financials?,
        news: List<NewsItem>,
        mode: Mode,
        question: String = "",
        ai: AiForecastResult? = null,
    ): String = buildString {
        val cur = asset.market.currency
        appendLine("너는 개인 투자자를 돕는 차분한 한국어 주식 분석 도우미야. 아래 자료만 근거로 답하고, 자료에 없는 숫자는 지어내지 마.")
        appendLine("매수·매도를 단정하지 말고, 근거와 위험을 함께 말해. 마지막 줄에 '참고용이며 투자 판단은 본인 책임' 이라고 덧붙여.")
        appendLine()
        appendLine("[종목] ${asset.name} (${asset.shortCode}, ${asset.market.label}${asset.exchange.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""})")
        if (quote != null) {
            appendLine("[시세] ${Fmt.priceWithUnit(quote.price, cur)} (${Fmt.signedRate(quote.changeRate)}), 거래대금 ${Fmt.big(quote.value, cur)}" +
                (if (quote.marketCap > 0) ", 시가총액 ${Fmt.big(quote.marketCap, cur)}" else "") +
                (quote.per?.let { ", PER ${Fmt.decimal(it)}" } ?: "") + (quote.pbr?.let { ", PBR ${Fmt.decimal(it)}" } ?: "") +
                (if (quote.realtime) "" else " (전일 종가 기준)"))
        }
        if (candles.size > 20) {
            val c = candles.last().close
            fun ago(n: Int) = candles.getOrNull(candles.size - 1 - n)?.close?.let { Fmt.signedRate((c / it - 1) * 100) } ?: "-"
            appendLine("[수익률] 1주 ${ago(5)}, 1개월 ${ago(21)}, 3개월 ${ago(63)}, 1년 ${ago(250)}")
            val year = candles.takeLast(250)
            appendLine("[1년 범위] 최고 ${Fmt.price(year.maxOf { it.high }, cur)} / 최저 ${Fmt.price(year.minOf { it.low }, cur)}")
        }
        if (forecast != null) {
            appendLine("[통계 예측] 점수 ${forecast.score}/100 (${forecast.opinion.label})")
            forecast.horizons.forEach { h ->
                appendLine("- ${h.bars}일 뒤: 중앙값 ${Fmt.signedRate(h.medianPct)}, 오를 확률 ${Fmt.decimal(h.upProbPct)}% (평소 ${Fmt.decimal(h.baseUpProbPct)}%), 범위(25~75%) ${Fmt.signedRate(h.p25Pct)} ~ ${Fmt.signedRate(h.p75Pct)}" +
                    if (!h.hitRatePct.isNaN()) ", 과거 적중률 ${Fmt.decimal(h.hitRatePct)}%" else "")
            }
            forecast.items.forEach { appendLine("- ${it.name} (${if (it.score >= 0) "+" else ""}${it.score}): ${it.reason}") }
        }
        if (ai != null) {
            appendLine("[AI 앙상블 예측 (${ai.horizon.period})] 의견 ${ai.opinion.label} (${"%.0f".format(ai.score)}점), 상승확률 ${Fmt.decimal(ai.upProb * 100)}% (평소 ${Fmt.decimal(ai.baseUpProb * 100)}%), 예상수익률 ${Fmt.signedRate(ai.expectedReturn * 100)}, 목표 ${Fmt.price(ai.target1, cur)}/${Fmt.price(ai.target2, cur)}, 손절 ${Fmt.price(ai.stop, cur)}, 검증 신뢰도 ${ai.reliability.label}")
            ai.models.forEach { m -> appendLine("- ${m.name}: 검증 AUC ${Fmt.decimal(m.auc)} (±${Fmt.decimal(m.aucSe)}), 가중치 ${Fmt.decimal(m.weight)}") }
        }
        if (report != null) {
            appendLine("[전략 신호] ${report.total}개 전략 중 매수 쪽 ${Fmt.decimal(report.buyRatioPct)}%, 최근 5일 매수 신호 ${report.recentBuy}개·매도 신호 ${report.recentSell}개")
            report.byScore.take(3).forEach { r ->
                appendLine("- ${r.strategy.name}: 누적 ${Fmt.signedRate(r.totalReturnPct)}, 승률 ${Fmt.decimal(r.winRatePct)}% (${r.closedTrades}회), 지금 ${if (r.holding) "보유" else "관망"}")
            }
        }
        if (financials != null && financials.annual.isNotEmpty()) {
            appendLine("[재무 (${if (financials.consolidated) "연결" else "별도"})]")
            financials.annual.forEach { p ->
                appendLine("- ${p.label}: 매출 ${p.revenue?.let { Fmt.big(it) } ?: "-"}, 영업이익 ${p.operatingIncome?.let { Fmt.big(it) } ?: "-"}, 순이익 ${p.netIncome?.let { Fmt.big(it) } ?: "-"}, 부채비율 ${p.debtRatio?.let { Fmt.decimal(it) + "%" } ?: "-"}, ROE ${p.roe?.let { Fmt.decimal(it) + "%" } ?: "-"}")
            }
            financials.latestQuarter?.let { q -> appendLine("- ${q.label}: 매출 ${q.revenue?.let { Fmt.big(it) } ?: "-"}, 영업이익 ${q.operatingIncome?.let { Fmt.big(it) } ?: "-"}") }
        }
        if (news.isNotEmpty()) {
            appendLine("[최근 뉴스·공시 제목]")
            news.take(15).forEach { appendLine("- ${Fmt.date(it.published)} ${it.kind.label}: ${it.title}") }
        }
        appendLine()
        when (mode) {
            Mode.SUMMARY -> appendLine("요청: 이 종목을 1) 한 줄 결론 2) 좋은 점 3) 걱정되는 점 4) 앞으로 볼 지표·일정 순서로 10줄 안팎으로 정리해 줘.")
            Mode.ISSUES -> appendLine("요청: 뉴스·공시 제목을 바탕으로 이 종목의 최근 이슈를 3~5개로 묶어 쉬운 말로 요약하고, 주가에 줄 수 있는 영향을 짧게 말해 줘.")
            Mode.QUESTION -> appendLine("질문: $question")
        }
    }
}
