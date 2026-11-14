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

internal inline fun <T> network(name: String, block: () -> T): T = try {
    block()
} catch (e: IOException) {
    throw DataException("$name: 인터넷 연결을 확인하세요. (${e.message ?: e.javaClass.simpleName})", "NETWORK", retryable = true)
}
