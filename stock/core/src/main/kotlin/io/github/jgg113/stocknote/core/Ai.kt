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

    fun webLink(prompt: String): String? = webUrl?.replace("{q}", URLEncoder.encode(prompt.take(1500), "UTF-8").replace("+", "%20"))
}

data class AiAnswer(val text: String, val model: String)

fun interface AiEngine {
    fun generate(prompt: String): AiAnswer
}

object AiEngines {
    fun create(provider: AiProvider, key: String, http: Http = UrlConnectionHttp()): AiEngine? {
        if (key.isBlank()) return null
        return when (provider) {
            AiProvider.GEMINI_API -> GeminiClient(key, http)
            AiProvider.GROQ_API -> OpenAiCompatClient(
                "Groq", "https://api.groq.com/openai/v1/chat/completions", key,
                listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b"), http,
            )
            AiProvider.OPENROUTER_API -> OpenAiCompatClient(
                "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", key,
                listOf("openai/gpt-oss-20b:free", "meta-llama/llama-3.3-70b-instruct:free", "google/gemma-3-27b-it:free"), http,
                mapOf("X-Title" to "Stock Note"),
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
    private val http: Http = UrlConnectionHttp(),
    private val extraHeaders: Map<String, String> = emptyMap(),
) : AiEngine {
    private val key = apiKey.trim()

    override fun generate(prompt: String): AiAnswer {
        var last: DataException? = null
        for (model in models) {
            val body = buildJsonObject {
                put("model", model)
                putJsonArray("messages") { add(buildJsonObject { put("role", "user"); put("content", prompt) }) }
            }.toString()
            val r = network(name) { http.post(endpoint, mapOf("Content-Type" to "application/json; charset=UTF-8", "Authorization" to "Bearer $key") + extraHeaders, body) }
            val root = parseJson(r.body).obj()
            val error = root?.get("error").obj()
            if (r.status != 200 || error != null) {
                val message = error?.str("message").orEmpty()
                val modelProblem = r.status == 404 || (r.status == 400 && message.contains("model", true))
                if (modelProblem) { last = DataException("$name 모델 $model 을 쓸 수 없습니다."); continue }
                throw when (r.status) {
                    401 -> DataException("$name API 키가 맞지 않습니다.")
                    402 -> DataException("$name 계정에 남은 크레딧이 없습니다.")
                    429 -> DataException("$name 사용 한도를 넘었습니다. 잠시 뒤 다시 시도하세요.", retryable = true)
                    else -> DataException("$name 오류 (${r.status}): ${message.ifBlank { r.body.take(120) }}", retryable = r.status >= 500)
                }
            }
            val text = ((root?.get("choices") as? JsonArray)?.firstOrNull().obj()?.get("message").obj()?.get("content") as? JsonPrimitive)?.content?.trim().orEmpty()
            if (text.isEmpty()) throw DataException("$name 가 빈 답을 보냈습니다. 다시 시도해 보세요.", retryable = true)
            return AiAnswer(text, model)
        }
        throw last ?: DataException("사용할 수 있는 $name 모델이 없습니다.")
    }
}

class GeminiClient(apiKey: String, private val http: Http = UrlConnectionHttp(), private val models: List<String> = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest")) : AiEngine {
    private val key = apiKey.trim()

    override fun generate(prompt: String): AiAnswer {
        var last: DataException? = null
        for (model in models) {
            val body = buildJsonObject {
                putJsonArray("contents") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) }
                    })
                }
                putJsonObject("generationConfig") { put("temperature", 0.3); put("maxOutputTokens", 4096) }
            }.toString()
            val r = network("Gemini") { http.post("$BASE/$model:generateContent", mapOf("Content-Type" to "application/json; charset=UTF-8", "x-goog-api-key" to key), body) }
            if (r.status == 404) { last = DataException("Gemini 모델 $model 을 찾지 못했습니다."); continue }
            return AiAnswer(parse(r.status, r.body), model)
        }
        throw last ?: DataException("사용할 수 있는 Gemini 모델이 없습니다.")
    }

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"

        fun parse(status: Int, body: String): String {
            val root = parseJson(body).obj()
            val error = root?.get("error").obj()
            if (status != 200 || error != null) {
                val message = error?.str("message").orEmpty()
                val reason = error?.str("status").orEmpty()
                throw when {
                    status == 400 && message.contains("key", true) -> DataException("Gemini API 키가 맞지 않습니다.")
                    status == 403 || reason == "PERMISSION_DENIED" -> DataException("이 키로 Gemini API 를 쓸 수 없습니다.")
                    status == 429 || reason == "RESOURCE_EXHAUSTED" -> DataException("Gemini 무료 한도를 넘었습니다. 1분쯤 뒤나 내일 다시 쓰세요.", retryable = true)
                    status >= 500 -> DataException("Gemini 서버가 잠시 응답하지 않습니다.", retryable = true)
                    else -> DataException("Gemini 오류 ($status): ${message.ifBlank { body.take(120) }}")
                }
            }
            val candidate = (root?.get("candidates") as? JsonArray)?.firstOrNull().obj()
            val parts = (candidate?.get("content").obj()?.get("parts") as? JsonArray).orEmpty()
            val text = parts.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }.joinToString("").trim()
            if (text.isEmpty()) throw DataException("Gemini 가 빈 답을 보냈습니다. 다시 시도해 보세요.", retryable = true)
            return text
        }
    }
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
