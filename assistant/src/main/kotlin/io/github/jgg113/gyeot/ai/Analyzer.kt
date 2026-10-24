package io.github.jgg113.gyeot.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import io.github.jgg113.gyeot.data.SavedEvent
import io.github.jgg113.gyeot.data.Settings
import io.github.jgg113.gyeot.data.Utterance
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Claude 로 받아쓴 대화를 정리한다: 요약·핵심·할 일, 그리고 캘린더에 넣을 일정.
 * 응답은 구조화 출력(JSON 스키마)으로 받아 그대로 [Analysis] 로 읽는다.
 */
class Analyzer(private val settings: Settings) : Brain {
    private var cachedKey: String? = null
    private var cachedClient: AnthropicClient? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun client(): AnthropicClient {
        val key = settings.anthropicApiKey
        if (key.isBlank()) throw AnalyzeException("Claude API 키가 없습니다 (설정 탭)", retryable = false)
        cachedClient?.let { if (cachedKey == key) return it }
        return AnthropicOkHttpClient.builder().apiKey(key).build().also {
            cachedClient = it
            cachedKey = key
        }
    }

    /**
     * @param context 앞서 오간 말 (이어지는 대화를 이해하는 데만 쓰고, 여기서 다시 일정을 뽑지는 않는다)
     * @param fresh 이번에 새로 정리할 말
     * @param known 이미 등록된 앞으로의 일정 (같은 일정을 또 뽑지 않게)
     */
    override fun analyze(context: List<Utterance>, fresh: List<Utterance>, known: List<SavedEvent>, now: ZonedDateTime): Analysis {
        val user = buildString {
            appendLine("지금: ${describeNow(now)}")
            if (settings.myName.isNotBlank()) appendLine("휴대폰 주인(나): ${settings.myName}")
            appendLine()
            if (known.isNotEmpty()) {
                appendLine("<이미_등록된_일정>")
                known.forEach { appendLine("- ${it.title} · ${stamp(it.startMillis)}${if (it.allDay) " (종일)" else ""}") }
                appendLine("</이미_등록된_일정>")
                appendLine()
            }
            if (context.isNotEmpty()) {
                appendLine("<앞선_대화 참고용>")
                context.forEach { appendLine("[${clock(it.at)}] ${it.text}") }
                appendLine("</앞선_대화>")
                appendLine()
            }
            appendLine("<새_대화>")
            fresh.forEach { appendLine("[${clock(it.at)}] ${it.text}") }
            appendLine("</새_대화>")
        }
        val text = textOf(call {
            it.maxTokens(16000L)
                .system(ANALYZE_SYSTEM)
                .outputConfig(
                    OutputConfig.builder()
                        .effort(OutputConfig.Effort.MEDIUM)
                        .format(JsonOutputFormat.builder().schema(schema()).build())
                        .build()
                )
                .addUserMessage(user)
                .build()
        })
        return try {
            json.decodeFromString<Analysis>(text)
        } catch (e: Exception) {
            throw AnalyzeException("정리 결과를 읽지 못했습니다: ${e.message}", retryable = true)
        }
    }

    /** '묻기' 탭: 저장된 대화·정리 노트를 근거로 질문에 답한다. */
    override fun ask(question: String, material: String, now: ZonedDateTime): String {
        return textOf(call {
            it.maxTokens(16000L)
                .system(ASK_SYSTEM)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .addUserMessage("지금: ${describeNow(now)}\n\n<기록>\n$material\n</기록>\n\n질문: $question")
                .build()
        }).trim()
    }

    /** 이 계정·모델에서 fallbacks 를 받지 않으면(400) 다음부터는 빼고 보낸다. */
    @Volatile private var fallbackRejected = false

    private fun base(withFallback: Boolean): MessageCreateParams.Builder {
        val model = settings.claudeModel
        val b = MessageCreateParams.builder().model(model)
        // 안전 분류기가 요청을 거절하면(정상 대화도 드물게 걸린다) 서버가 알맞은 모델로 다시 돌린다.
        if (withFallback && FALLBACK_MODELS.any { model.startsWith(it) }) {
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return b
    }

    private fun call(build: (MessageCreateParams.Builder) -> MessageCreateParams): Message = try {
        try {
            client().messages().create(build(base(!fallbackRejected)))
        } catch (e: BadRequestException) {
            if (fallbackRejected || e.message?.contains("fallback", ignoreCase = true) != true) throw e
            fallbackRejected = true
            client().messages().create(build(base(false)))
        }
    } catch (e: UnauthorizedException) {
        throw AnalyzeException("Claude API 키가 맞지 않습니다", retryable = false)
    } catch (e: RateLimitException) {
        throw AnalyzeException("Claude 사용 한도에 걸렸습니다. 잠시 뒤 다시 합니다", retryable = true)
    } catch (e: AnthropicServiceException) {
        throw AnalyzeException("Claude 오류 ${e.statusCode()}: ${e.message}", retryable = e.statusCode() >= 500)
    } catch (e: AnalyzeException) {
        throw e
    } catch (e: Exception) {
        throw AnalyzeException("Claude 연결 실패: ${e.message}", retryable = true)
    }

    private fun textOf(message: Message): String {
        val reason = message.stopReason().orElse(null)
        if (reason == StopReason.REFUSAL) throw AnalyzeException("Claude 가 이 대화 정리를 거절했습니다", retryable = false)
        if (reason == StopReason.MAX_TOKENS) throw AnalyzeException("정리 결과가 너무 길어 잘렸습니다", retryable = false)
        return message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
    }

    private fun schema(): JsonOutputFormat.Schema {
        fun str(desc: String) = mapOf("type" to "string", "description" to desc)
        val event = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("title", "start", "end", "allDay", "location", "reminderMinutesBefore", "confidence", "evidence", "isChange"),
            "properties" to mapOf(
                "title" to str("캘린더 제목. 짧게, 누구와·무엇 (예: '민수와 점심', '치과 예약')"),
                "start" to str("한국 시간 YYYY-MM-DDTHH:mm, 종일이면 YYYY-MM-DD"),
                "end" to str("끝 시각 YYYY-MM-DDTHH:mm, 모르면 빈 문자열"),
                "allDay" to mapOf("type" to "boolean"),
                "location" to str("장소, 없으면 빈 문자열"),
                "reminderMinutesBefore" to mapOf("type" to "integer", "description" to "미리 알림(분 전). 특별히 말한 게 없으면 0"),
                "confidence" to mapOf("type" to "number", "description" to "0~1, 실제로 정해진 약속일 가능성"),
                "evidence" to str("근거가 된 말 그대로 한 줄"),
                "isChange" to mapOf("type" to "boolean", "description" to "이미 등록된 일정의 시각·날짜가 바뀌었다는 말이면 true"),
            ),
        )
        val todo = mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "required" to listOf("text", "due"),
            "properties" to mapOf(
                "text" to str("할 일 한 줄 (누가 무엇을)"),
                "due" to str("기한 YYYY-MM-DDTHH:mm 또는 YYYY-MM-DD, 없으면 빈 문자열"),
            ),
        )
        val props = mapOf(
            "summary" to str("이번 대화 요약 1~3문장"),
            "importance" to mapOf("type" to "string", "enum" to listOf("low", "normal", "high")),
            "keyPoints" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            "events" to mapOf("type" to "array", "items" to event),
            "todos" to mapOf("type" to "array", "items" to todo),
        )
        return JsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .putAdditionalProperty("required", JsonValue.from(listOf("summary", "importance", "keyPoints", "events", "todos")))
            .putAdditionalProperty("properties", JsonValue.from(props))
            .build()
    }

    companion object {
        private val FALLBACK_MODELS = listOf("claude-opus-5", "claude-fable-5", "claude-sonnet-5-5")

        private val ANALYZE_SYSTEM = """
            너는 휴대폰 주인 곁에서 하루 종일 대화를 들어 주는 한국어 비서다. 주인이 흘려들었거나 잊어버릴 만한 것을 대신 챙기는 게 목적이다.
            입력은 휴대폰 마이크로 받아쓴 글이라 오타·잘린 말·잘못 들은 단어가 섞여 있고, 누가 말했는지 표시가 없다. 앞뒤 맥락으로 뜻을 바로잡아 읽어라.
            TV·유튜브·라디오 소리로 보이는 말(광고, 뉴스 낭독, 드라마 대사)은 주인의 대화가 아니니 일정·할 일로 뽑지 말고 요약에서도 빼라.

            events — 주인이 가야 하거나 챙겨야 하는 약속·예약·회의·마감·기념일만 넣는다.
            - "다음 주 화요일", "모레 저녁", "이따 3시" 같은 말은 '지금' 시각을 기준으로 실제 날짜·시각으로 바꾼다. 한국 시간 기준이다.
            - 시각 없이 "저녁"은 19:00, "점심"은 12:00, "아침"은 09:00, "오후"만 있으면 15:00 으로 잡되 confidence 를 낮춘다. 날짜만 있고 시각이 없으면 종일(allDay) 일정이다.
            - "3시"처럼 오전·오후가 없으면 상식(업무·약속은 대개 낮)으로 정한다.
            - 주인이 직접 "일정 넣어"라고 하지 않아도, 들은 말로 보아 주인이 챙겨야 할 것 같은 일(곧 있을 약속, 다녀와야 할 곳, 해야 할 연락·납부·예약, 다가오는 기념일)은 넣는다.
              앱이 확신도에 따라 바로 넣거나 "추가할까요?" 하고 주인에게 묻는다. 확정된 약속은 0.8 이상, 아직 정하지 않은 제안·추측은 0.4~0.7 로 준다.
              날짜를 전혀 알 수 없으면 내일 09:00 으로 두고 confidence 0.4~0.5 로 준다.
            - 지나가는 말("언제 밥 한번 먹자"), 남의 일정(주인과 상관없는 제3자 이야기), 이미 지난 일은 넣지 않는다.
            - <이미_등록된_일정> 에 있는 것과 같은 일정은 다시 넣지 않는다. 시각이 바뀌었다는 말이 있으면 같은 제목으로 새 시각을 넣고 isChange 를 true 로 한다.
            - <앞선_대화> 는 맥락 참고용이다. 일정·할 일·핵심은 <새_대화> 에서 나온 것만 뽑는다.

            todos — 주인이 하기로 한 일, 남이 주인에게 부탁한 일 (예: "자료 금요일까지 보내 주세요").
            keyPoints — 나중에 다시 알아야 할 사실: 숫자, 금액, 계좌, 주소, 이름, 결정된 내용, 부탁받은 내용. 짧은 한 줄씩. 잡담뿐이면 비운다.
            importance — 놓치면 곤란한 약속·부탁·돈·건강 이야기가 있으면 high, 평범하면 normal, 잡담·소음뿐이면 low.
            모든 글은 자연스러운 한국어로 쓴다.
        """.trimIndent()

        private val ASK_SYSTEM = """
            너는 휴대폰 주인의 대화 기록을 들고 있는 한국어 비서다. <기록> 에 있는 받아쓴 대화와 정리 노트만 근거로 질문에 답한다.
            기록에 없는 내용은 지어내지 말고 "기록에서 찾지 못했어요"라고 말한다. 언제 나온 말인지(날짜·시각)를 함께 알려 준다.
            받아쓰기라 오타가 있을 수 있으니 확실하지 않으면 그렇다고 말한다. 짧고 바로 쓸 수 있게 답한다.
        """.trimIndent()

        private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val HM = DateTimeFormatter.ofPattern("HH:mm")

        fun describeNow(now: ZonedDateTime): String =
            "${now.format(DAY)} (${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}) ${now.format(HM)} 한국 시간"

        private fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(EventRules.SEOUL).format(HM)

        private fun stamp(at: Long): String {
            val t = Instant.ofEpochMilli(at).atZone(EventRules.SEOUL)
            return "${t.format(DAY)}(${t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)}) ${t.format(HM)}"
        }
    }
}
