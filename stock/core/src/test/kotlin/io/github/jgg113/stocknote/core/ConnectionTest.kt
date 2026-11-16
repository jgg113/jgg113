package io.github.jgg113.stocknote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 요청을 보고 답을 정하는 가짜 HTTP. 보낸 요청(주소·헤더·본문)을 모두 적어 둔다. */
class ScriptedHttp(private val answer: (url: String, headers: Map<String, String>, body: String?) -> HttpResponse) : Http {
    class Call(val url: String, val headers: Map<String, String>, val body: String?)

    val calls = mutableListOf<Call>()

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        calls += Call(url, headers, null)
        return answer(url, headers, null)
    }

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        calls += Call(url, headers, body)
        return answer(url, headers, body)
    }
}

private fun res(status: Int, body: String) = HttpResponse(status, body.toByteArray())

/** 키를 넣어도 연결되지 않던 원인들을 막는 시험 */
class ConnectionTest {
    @Test
    fun pastedKeysAreCleaned() {
        // 줄바꿈·빈칸·보이지 않는 글자·한글·따옴표가 섞여 붙어도 키만 남는다.
        assertEquals("abcDEF123+/==", KeyText.clean("  abc DEF\n123+/==​ "))
        assertEquals("AIzaSyTest", KeyText.clean("\"AIzaSyTest\""))
        assertEquals("abc", KeyText.clean("키 abc 입니다"))
        assertEquals("", KeyText.clean(" \n\t"))
        val k = DataKeys(dataGoKr = " a b\n", kisAppKey = "PS key", kisAppSecret = "sec\r\nret", dart = " d ", twelveData = "t\t").cleaned()
        assertEquals(DataKeys("ab", "PSkey", "secret", false, "d", "t"), k)
        // 화면에는 키 전체가 아니라 앞뒤 몇 글자와 길이만 보인다.
        assertEquals("abcd…wxyz (12자)", KeyText.masked("abcd1234wxyz"))
    }

    @Test
    fun keyShapeHints() {
        assertNull(DataKeys().hintKisKey())
        assertNull(DataKeys(kisAppKey = "P".repeat(36), kisAppSecret = "s".repeat(180)).let { it.hintKisKey() ?: it.hintKisSecret() })
        // 앱키와 시크릿을 서로 바꿔 넣은 경우
        val swapped = DataKeys(kisAppKey = "s".repeat(180), kisAppSecret = "P".repeat(36))
        assertTrue(swapped.hintKisKey()!!.contains("앱시크릿"))
        assertTrue(swapped.hintKisSecret()!!.contains("180"))
        assertTrue(DataKeys(dart = "abc").hintDart()!!.contains("40"))
        assertTrue(AiProvider.GEMINI_API.keyHint("gsk_" + "a".repeat(40))!!.contains("Gemini"))
        assertNull(AiProvider.GEMINI_API.keyHint("AIza" + "a".repeat(35)))
        assertNull(AiProvider.GROQ_API.keyHint("gsk_" + "a".repeat(40)))
    }

    @Test
    fun brokenRequestsBecomeReadableErrors() {
        // 헤더에 못 쓰는 글자 등으로 요청 자체가 만들어지지 않을 때도 알아볼 수 있는 말로 바뀐다.
        val http = ScriptedHttp { _, _, _ -> throw IllegalArgumentException("Illegal character(s) in message header value: secret-value") }
        val e = assertFailsWith<DataException> { DartClient("k".repeat(40), http).disclosures(null, java.time.LocalDate.of(2026, 11, 1), java.time.LocalDate.of(2026, 11, 3), 10) }
        assertEquals("NETWORK", e.code)
        assertTrue(e.message!!.contains("키에 빈칸"))
        assertFalse(e.message!!.contains("secret-value"), "오류 글에 키가 드러나면 안 된다")
        assertTrue(networkReason(java.net.UnknownHostException("x")).contains("인터넷"))
        assertTrue(networkReason(java.net.SocketTimeoutException("connect timed out")).contains("모바일 데이터"))
        assertTrue(networkReason(javax.net.ssl.SSLHandshakeException("bad")).contains("날짜"))
    }

    private val kisQuote = """{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":{"stck_prpr":"98700","prdy_vrss":"500","prdy_vrss_sign":"2","prdy_ctrt":"0.51","hts_kor_isnm":"삼성전자"}}"""

    /** 모의투자용 키만 받아 주는 가짜 한국투자증권 서버 */
    private fun kisServer(acceptVirtual: Boolean, acceptReal: Boolean) = ScriptedHttp { url, headers, _ ->
        val virtual = url.startsWith(KisClient.VIRTUAL)
        val accepted = if (virtual) acceptVirtual else acceptReal
        when {
            !url.contains("koreainvestment.com") -> res(404, "")
            url.contains("/oauth2/tokenP") ->
                if (accepted) res(200, """{"access_token":"tok-${if (virtual) "v" else "r"}","expires_in":86400}""")
                else res(403, """{"error_description":"유효하지 않은 AppKey입니다.","error_code":"EGW00103"}""")
            url.contains("inquire-price") && headers["authorization"] == "Bearer tok-${if (virtual) "v" else "r"}" -> res(200, kisQuote)
            else -> res(500, """{"rt_cd":"1","msg_cd":"X","msg1":"잘못된 요청"}""")
        }
    }

    @Test
    fun checkFindsTheRightKisServer() {
        // 모의투자 키인데 '모의투자 서버 키'를 켜지 않은 경우: 반대쪽 서버로 되는지 보고 알려 준다.
        val http = kisServer(acceptVirtual = true, acceptReal = false)
        val tokens = MemoryTokenStore()
        val keys = DataKeys(kisAppKey = "P".repeat(36), kisAppSecret = "s".repeat(180), kisVirtual = false)
        val row = MarketData(keys, MemoryCache(), tokens, http).check().first { it.name == "한국투자증권 (실시간)" }
        assertTrue(row.ok, row.detail)
        assertEquals(true, row.switchKisVirtual)
        assertTrue(row.detail.contains("98,700"), row.detail)

        // 설정을 바꾼 뒤에는 저장해 둔 토큰을 다시 쓴다 (토큰은 1분에 한 번만 받을 수 있다).
        val before = http.calls.count { it.url.contains("/oauth2/tokenP") }
        val row2 = MarketData(keys.copy(kisVirtual = true), MemoryCache(), tokens, http).check().first { it.name == "한국투자증권 (실시간)" }
        assertTrue(row2.ok)
        assertNull(row2.switchKisVirtual)
        assertEquals(before, http.calls.count { it.url.contains("/oauth2/tokenP") })
    }

    @Test
    fun checkExplainsRejectedKisKey() {
        val http = kisServer(acceptVirtual = false, acceptReal = false)
        val rows = MarketData(DataKeys(kisAppKey = "P".repeat(36), kisAppSecret = "s".repeat(180)), MemoryCache(), MemoryTokenStore(), http).check()
        val row = rows.first { it.name == "한국투자증권 (실시간)" }
        assertFalse(row.ok)
        assertTrue(row.detail.contains("유효하지 않은 AppKey"), row.detail)
        assertTrue(row.help.contains("36자") && row.help.contains("보낸 키: 앱키 PPPP…PPPP (36자) · 시크릿 180자"), row.help)
        assertFalse(row.help.contains("s".repeat(20)), "시크릿이 화면에 드러나면 안 된다")
        // 두 서버 모두 물어봤다.
        assertTrue(http.calls.any { it.url.startsWith(KisClient.REAL) } && http.calls.any { it.url.startsWith(KisClient.VIRTUAL) })
        // 키를 넣지 않은 곳은 실패가 아니라 '안 씀'으로 본다.
        assertTrue(rows.first { it.name == "DART (공시·재무)" }.unused)
        // 앱키만 넣고 시크릿을 빼먹은 경우
        val half = MarketData(DataKeys(kisAppKey = "P".repeat(36)), MemoryCache(), MemoryTokenStore(), http).check().first { it.name == "한국투자증권 (실시간)" }
        assertFalse(half.ok || half.unused)
        assertTrue(half.detail.contains("둘 다"))
    }

    @Test
    fun kisTokenIsNotSharedBetweenServers() {
        // 실전 서버에서 받은 토큰을 모의 서버에 쓰지 않는다.
        val http = kisServer(acceptVirtual = true, acceptReal = true)
        val tokens = MemoryTokenStore()
        KisClient("key", "secret", false, tokens, http).domesticQuote("005930")
        KisClient("key", "secret", true, tokens, http).domesticQuote("005930")
        assertEquals(2, http.calls.count { it.url.contains("/oauth2/tokenP") })
    }

    @Test
    fun publicDataCheckTellsToApplyPerApi() {
        // 다른 API 에서 쓰던 키: 서버가 거절하면 활용신청을 따로 해야 한다고 알려 준다.
        val http = ScriptedHttp { url, _, _ ->
            if (url.contains("apis.data.go.kr")) res(200, """<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg><returnAuthMsg>SERVICE_ACCESS_DENIED_ERROR</returnAuthMsg><returnReasonCode>20</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>""")
            else res(404, "")
        }
        val rows = MarketData(DataKeys(dataGoKr = "  abcd%2Bef \n"), MemoryCache(), MemoryTokenStore(), http).check()
        val stock = rows.first { it.name == "공공데이터포털 주식시세" }
        assertFalse(stock.ok)
        assertTrue(stock.help.contains("금융위원회_주식시세정보") && stock.help.contains("활용신청"), stock.help)
        assertEquals(PublicDataClient.STOCK_PAGE, stock.page)
        assertTrue(rows.first { it.name == "공공데이터포털 지수시세" }.help.contains("금융위원회_지수시세정보"))
        // 붙여넣다 섞인 빈칸·줄바꿈 없이, 한 번만 인코딩해서 보낸다.
        val sent = http.calls.first { it.url.contains("apis.data.go.kr") }.url
        assertTrue(sent.contains("serviceKey=abcd%2Bef&"), sent)
    }

    private fun geminiOk(text: String) = """{"candidates":[{"content":{"parts":[{"text":"$text"}]},"finishReason":"STOP"}]}"""

    @Test
    fun geminiTriesNextModelWhenOneIsOverLimit() {
        // 첫 모델의 무료 한도가 0 이어도 다음 모델로 넘어가 답을 받는다.
        val http = ScriptedHttp { url, _, _ ->
            if (url.contains("gemini-flash-latest")) res(429, """{"error":{"code":429,"message":"Quota exceeded, limit: 0","status":"RESOURCE_EXHAUSTED"}}""")
            else res(200, geminiOk("PER 은 주가를 주당순이익으로 나눈 값"))
        }
        val a = GeminiClient(" AIza test\n", http).generate("q")
        assertEquals("gemini-3.8-flash", a.model)
        assertEquals("AIzatest", http.calls.first().headers["x-goog-api-key"])
    }

    @Test
    fun geminiFindsModelsWhenNamesAreRetired() {
        // 정해 둔 모델 이름이 모두 없어져도, 이 키로 쓸 수 있는 모델을 물어 찾아 쓴다.
        val list = """{"models":[
            {"name":"models/gemini-9.0-flash-image","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-9.0-flash-preview","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-8.5-flash-lite","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-8.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/gemini-9.0-pro","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-9","supportedGenerationMethods":["embedContent"]}]}"""
        assertEquals(listOf("gemini-8.5-flash", "gemini-8.5-flash-lite", "gemini-9.0-flash-preview"), GeminiClient.parseModels(list))
        val http = ScriptedHttp { url, _, _ ->
            when {
                url.endsWith("/models?pageSize=200") -> res(200, list)
                url.contains("gemini-8.5-flash:generateContent") -> res(200, geminiOk("답"))
                else -> res(404, """{"error":{"code":404,"message":"model not found","status":"NOT_FOUND"}}""")
            }
        }
        assertEquals("gemini-8.5-flash", GeminiClient("k", http).generate("q").model)
    }

    @Test
    fun geminiBadKeyStopsAtOnce() {
        val http = ScriptedHttp { _, _, _ -> res(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""") }
        val e = assertFailsWith<DataException> { GeminiClient("wrong", http).generate("q") }
        assertEquals("AI_KEY", e.code)
        assertEquals(1, http.calls.size)
        // 빈 답은 이유를 함께 알려 준다.
        val empty = assertFailsWith<DataException> { GeminiClient.parse(200, """{"candidates":[{"content":{},"finishReason":"MAX_TOKENS"}]}""") }
        assertTrue(empty.message!!.contains("MAX_TOKENS"))
    }

    @Test
    fun openAiCompatTriesNextModelOnLimit() {
        val http = ScriptedHttp { _, _, body ->
            if (body!!.contains("gpt-oss-120b")) res(429, """{"error":{"message":"Rate limit reached"}}""")
            else res(200, """{"choices":[{"message":{"content":"답"}}]}""")
        }
        val a = AiEngines.create(AiProvider.GROQ_API, "gsk_x\n", http)!!.generate("q")
        assertEquals("llama-3.3-70b-versatile", a.model)
        assertEquals("Bearer gsk_x", http.calls.first().headers["Authorization"])
        val bad = ScriptedHttp { _, _, _ -> res(401, """{"error":{"message":"Invalid API Key"}}""") }
        assertEquals("AI_KEY", assertFailsWith<DataException> { AiEngines.create(AiProvider.GROQ_API, "gsk_x", bad)!!.generate("q") }.code)
        assertEquals(1, bad.calls.size)
    }

    @Test
    fun openRouterFindsFreeModelsWhenNamesChange() {
        // 무료 모델 이름은 자주 바뀐다. 정해 둔 이름이 모두 없어져도 공개 목록에서 지금 무료인 모델을 찾아 쓴다.
        val list = """{"data":[
            {"id":"vendor/paid-model","context_length":100000,"architecture":{"output_modalities":["text"]}},
            {"id":"cohere/north-mini-code:free","context_length":256000,"architecture":{"output_modalities":["text"]}},
            {"id":"some/image-maker:free","context_length":1000,"architecture":{"output_modalities":["image"]}},
            {"id":"inclusionai/ling-9-flash:free","context_length":1048576,"architecture":{"output_modalities":["text"]}},
            {"id":"google/gemma-9-31b-it:free","context_length":262144,"architecture":{"output_modalities":["text"]}}]}"""
        assertEquals(listOf("google/gemma-9-31b-it:free", "inclusionai/ling-9-flash:free"), OpenRouterModels.parse(list))
        val http = ScriptedHttp { url, _, body ->
            when {
                url == OpenRouterModels.URL -> res(200, list)
                body!!.contains("google/gemma-9-31b-it:free") -> res(200, """{"choices":[{"message":{"content":"답"}}]}""")
                else -> res(404, """{"error":{"message":"No endpoints found","code":404}}""")
            }
        }
        val a = AiEngines.create(AiProvider.OPENROUTER_API, "sk-or-x", http)!!.generate("q")
        assertEquals("google/gemma-9-31b-it:free", a.model)
        // 첫 시도는 무료 모델로 알아서 보내 주는 주소다.
        assertTrue(http.calls.first().body!!.contains("openrouter/free"))
    }

    @Test
    fun checkFixesSwappedKisKeys() {
        // 앱키 칸에 시크릿을, 시크릿 칸에 앱키를 넣은 경우: 바꾼 조합으로 되면 바로잡으라고 알려 준다.
        val appKey = "P".repeat(36)
        val secret = "s".repeat(180)
        val http = ScriptedHttp { url, headers, body ->
            when {
                !url.contains("koreainvestment.com") -> res(404, "")
                url.contains("/oauth2/tokenP") ->
                    if (body!!.contains("\"appkey\":\"$appKey\"") && body.contains("\"appsecret\":\"$secret\"") && url.startsWith(KisClient.REAL)) res(200, """{"access_token":"tok","expires_in":86400}""")
                    else res(403, """{"error_description":"유효하지 않은 AppKey입니다.","error_code":"EGW00103"}""")
                headers["authorization"] == "Bearer tok" -> res(200, kisQuote)
                else -> res(500, """{"rt_cd":"1","msg1":"잘못된 요청"}""")
            }
        }
        val row = MarketData(DataKeys(kisAppKey = secret, kisAppSecret = appKey), MemoryCache(), MemoryTokenStore(), http).check().first { it.name == "한국투자증권 (실시간)" }
        assertTrue(row.ok, row.detail)
        assertTrue(row.swapKisKeys)
        assertEquals(false, row.switchKisVirtual)
        assertTrue(row.detail.contains("바뀌어"), row.detail)
        // 토큰 요청 헤더는 한국투자증권 공식 예제와 같다.
        val token = http.calls.first { it.url.contains("tokenP") }
        assertEquals("application/json", token.headers["Content-Type"])
    }

    @Test
    fun publicDataCheckTellsWrongKeyFromUnappliedApi() {
        // 포털은 '키가 틀림'과 '이 API 를 신청 안 함'을 같은 오류로 돌려준다. 다른 공공 API 에서 통하는지로 가린다.
        val unregistered = """{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR","returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}"""
        // ① 금융위원회 API 만 거절하고 온비드는 통하는 키: 키는 정상, 이 API 신청이 안 된 것.
        val applied = ScriptedHttp { url, _, _ ->
            when {
                url.contains("/1160100/") -> res(403, unregistered)
                url.contains("/B010003/") -> res(200, """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE"},"body":{"totalCount":0}}}""")
                url.contains("apis.data.go.kr") -> res(403, unregistered)
                else -> res(404, "")
            }
        }
        val key = "a".repeat(60) + "+/=="
        val good = MarketData(DataKeys(dataGoKr = key), MemoryCache(), MemoryTokenStore(), applied).check().first { it.name == "공공데이터포털 주식시세" }
        assertFalse(good.ok)
        assertTrue(good.help.contains("키는 정상") && good.help.contains("금융위원회_주식시세정보"), good.help)
        assertTrue(good.help.contains("보낸 키: aaaa…+/== (64자)"), good.help)
        // ② 어디서도 모르는 키: 키가 잘못 복사됐거나 아직 아무것도 신청하지 않은 것.
        val nowhere = ScriptedHttp { url, _, _ -> if (url.contains("apis.data.go.kr")) res(403, unregistered) else res(404, "") }
        val bad = MarketData(DataKeys(dataGoKr = key), MemoryCache(), MemoryTokenStore(), nowhere).check().first { it.name == "공공데이터포털 주식시세" }
        assertTrue(bad.help.contains("다른 공공 API") && bad.help.contains("잘못 복사"), bad.help)
        // 다른 API 에도 같은 키를 한 번만 인코딩해서 보낸다.
        assertTrue(nowhere.calls.any { it.url.contains("/B010003/") && it.url.endsWith("serviceKey=" + "a".repeat(60) + "%2B%2F%3D%3D") })
        assertEquals(PublicDataClient.KeyStanding.KNOWN, PublicDataClient.standing(403, "<errMsg>SERVICE_ACCESS_DENIED_ERROR</errMsg>"))
        assertEquals(PublicDataClient.KeyStanding.UNSURE, PublicDataClient.standing(500, "gateway error"))
    }
}
