package io.github.jgg113.stocknote.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class HttpResponse(val status: Int, val bytes: ByteArray) {
    val body: String by lazy { bytes.toString(Charsets.UTF_8) }
}

/** HTTP 통신. 테스트에서 가짜로 바꿔 끼운다. */
interface Http {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
}

class UrlConnectionHttp(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 45_000) : Http {
    override fun get(url: String, headers: Map<String, String>): HttpResponse = request("GET", url, headers, null)

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse = request("POST", url, headers, body)

    private fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        if (headers.keys.none { it.equals("User-Agent", true) }) conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) StockNote")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            return HttpResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }
}

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

internal fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

internal fun query(params: Map<String, String>): String = params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeUnless { it.toString() == "null" }?.content.orEmpty()
internal fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.content.num()

/** 목록 응답에서 한 건이면 객체, 여러 건이면 배열로 오는 경우를 모두 배열로 바꾼다. */
internal fun JsonElement?.asList(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> listOf(this)
    else -> emptyList()
}

internal fun parseJson(body: String): JsonElement? = runCatching { lenientJson.parseToJsonElement(body.trim()) }.getOrNull()

/**
 * 붙여넣은 키를 정리한다. 키는 모두 영문·숫자·기호뿐이라, 복사하다 섞여 들어온
 * 빈칸·줄바꿈·보이지 않는 글자·한글·따옴표를 걷어 낸다.
 */
object KeyText {
    fun clean(raw: String): String = raw.filter { it in '!'..'~' }.trim('"', '\'', '`')

    /** 화면에 보여 줄 때는 앞뒤 몇 글자와 길이만 */
    fun masked(key: String): String = when {
        key.isEmpty() -> ""
        key.length <= 8 -> "${key.take(2)}… (${key.length}자)"
        else -> "${key.take(4)}…${key.takeLast(4)} (${key.length}자)"
    }
}

/** 통신 오류를 쉬운 말로 바꾼다. */
fun networkReason(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> "서버 주소를 찾지 못했습니다. 인터넷(와이파이·모바일 데이터)이 켜져 있는지 확인하세요."
    is java.net.SocketTimeoutException -> "서버가 응답하지 않아 끊겼습니다. 와이파이가 막는 경우가 있으니 모바일 데이터로 바꿔 다시 해 보세요."
    is javax.net.ssl.SSLException -> "보안 연결에 실패했습니다. 휴대폰 날짜·시간이 맞는지, VPN 이나 회사·학교 와이파이가 막고 있지 않은지 확인하세요. (${e.message ?: e.javaClass.simpleName})"
    is java.net.ConnectException, is java.net.NoRouteToHostException -> "서버에 연결하지 못했습니다. 와이파이가 막는 경우가 있으니 모바일 데이터로 바꿔 다시 해 보세요. (${e.message ?: e.javaClass.simpleName})"
    is IOException -> "인터넷 연결을 확인하세요. (${e.message ?: e.javaClass.simpleName})"
    else -> "요청을 보내지 못했습니다. 키에 빈칸·줄바꿈·한글이 섞이지 않았는지 확인하세요. (${e.javaClass.simpleName})"
}

internal inline fun <T> network(name: String, block: () -> T): T = try {
    block()
} catch (e: DataException) {
    throw e
} catch (e: InterruptedException) {
    throw e
} catch (e: Exception) {
    // IOException 뿐 아니라 주소·헤더에 못 쓰는 글자가 있을 때 나는 오류도 쉬운 말로 바꾼다.
    throw DataException("$name: ${networkReason(e)}", "NETWORK", retryable = e is IOException)
}
