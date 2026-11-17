package io.github.jgg113.stocknote.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
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

/**
 * 실제 통신 (OkHttp).
 * - 서버 주소(IP)가 여러 개면 붙지 않는 주소를 건너뛰고 다음 주소로 붙는다. IPv4 주소를 먼저 쓴다
 *   (IPv6 경로가 끊긴 망에서 연결이 멈춰 버리는 일을 막는다).
 * - 휴대폰 DNS 가 주소를 못 찾으면 공개 DNS(Google·Cloudflare, 보안 연결)로 한 번 더 찾는다.
 * - 아직 아무것도 보내지 않은 단계(주소 찾기·연결)에서 실패하면 잠깐 쉬었다가 두 번 더 해 본다.
 */
class NetHttp(private val connectTimeoutMs: Int = 10_000, private val readTimeoutMs: Int = 45_000) : Http {
    private val client: okhttp3.OkHttpClient by lazy {
        shared.newBuilder()
            .connectTimeout(connectTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    /** 다시 하기 전에 쉬는 시간 (시험에서는 0 으로 둔다) */
    var retryPauseMs: Long = 800

    /** 조회는 연결이 중간에 끊기면(Connection reset 등) 한 번 더 해 본다. 시간 초과는 다시 하지 않는다. */
    override fun get(url: String, headers: Map<String, String>): HttpResponse = try {
        beforeSend { execute(build(url, headers, null)) }
    } catch (e: java.net.SocketTimeoutException) {
        throw e
    } catch (e: java.net.UnknownHostException) {
        throw e
    } catch (e: IOException) {
        execute(build(url, headers, null))
    }

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse = beforeSend { execute(build(url, headers, body)) }

    private fun build(url: String, headers: Map<String, String>, body: String?): okhttp3.Request {
        val b = okhttp3.Request.Builder().url(url)
        if (headers.keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", "Mozilla/5.0 (Android) StockNote")
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
        headers.forEach { (k, v) -> if (!k.equals("Content-Type", true)) b.header(k, v) }
        if (body != null) {
            val type = (contentType ?: "application/json; charset=UTF-8").toMediaTypeOrNull()
            b.post(body.toByteArray(Charsets.UTF_8).toRequestBody(type))
        }
        return b.build()
    }

    private fun execute(request: okhttp3.Request): HttpResponse = try {
        client.newCall(request).execute().use { r -> HttpResponse(r.code, r.body?.bytes() ?: ByteArray(0)) }
    } catch (e: javax.net.ssl.SSLHandshakeException) {
        // 오래된 암호 방식만 받는 서버(정부·공공기관에 많다)와 보안 연결이 안 되면, 휴대폰 기본 통신으로 한 번 더 해 본다.
        // 보안 연결 전 단계라 아직 아무것도 보내지 않았으므로 다시 해도 안전하다.
        runCatching { legacy(request) }.getOrElse { throw e }
    }

    /** 휴대폰(자바) 기본 통신. 예전에 쓰던 방식 그대로다. */
    private fun legacy(request: okhttp3.Request): HttpResponse {
        val conn = java.net.URI(request.url.toString()).toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = request.method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        for (i in 0 until request.headers.size) conn.setRequestProperty(request.headers.name(i), request.headers.value(i))
        try {
            val body = request.body
            if (body != null) {
                body.contentType()?.let { conn.setRequestProperty("Content-Type", it.toString()) }
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                conn.doOutput = true
                conn.outputStream.use { it.write(buffer.readByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            return HttpResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            conn.disconnect()
        }
    }

    /** 주소를 못 찾았거나 연결이 거절된 경우는 아직 아무것도 보내지 않은 것이라 다시 해도 안전하다. */
    private fun <T> beforeSend(block: () -> T): T {
        var last: IOException? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) Thread.sleep(retryPauseMs * attempt)
            try {
                return block()
            } catch (e: java.net.UnknownHostException) {
                last = e
            } catch (e: java.net.ConnectException) {
                last = e
            } catch (e: java.net.SocketTimeoutException) {
                // 연결 단계의 시간 초과만 다시 한다 (답을 기다리다 끊긴 것은 서버가 이미 받았을 수 있다).
                if (!isConnectTimeout(e)) throw e
                last = e
            }
        }
        throw last!!
    }

    companion object {
        private val dohBootstrap by lazy { okhttp3.OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).readTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build() }

        private fun doh(url: String, vararg ips: String): okhttp3.Dns = okhttp3.dnsoverhttps.DnsOverHttps.Builder()
            .client(dohBootstrap)
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(ips.map { java.net.InetAddress.getByName(it) }) // 숫자 주소라 DNS 를 쓰지 않는다
            .includeIPv6(true)
            .build()

        /** 휴대폰 DNS → 실패하면 공개 DNS 두 곳. IPv4 주소를 앞에 둔다. */
        val dns = FallbackDns(okhttp3.Dns.SYSTEM, lazy { listOf(doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"), doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1")) })

        val shared: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder().dns(dns).retryOnConnectionFailure(true).followRedirects(true).build()
        }

        fun isConnectTimeout(e: java.net.SocketTimeoutException): Boolean = e.message.orEmpty().contains("connect", ignoreCase = true)
    }
}

/** 휴대폰 DNS 로 먼저 찾고, 못 찾으면 [fallbacks] 로 찾는다. 찾은 주소는 IPv4 를 앞에 둔다. */
class FallbackDns(private val primary: okhttp3.Dns, private val fallbacks: Lazy<List<okhttp3.Dns>>) : okhttp3.Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> {
        val found = try {
            primary.lookup(hostname)
        } catch (e: java.net.UnknownHostException) {
            // 숫자 주소이거나 대체 DNS 도 못 찾으면 원래 오류를 그대로 낸다.
            fallbacks.value.firstNotNullOfOrNull { d -> runCatching { d.lookup(hostname) }.getOrNull()?.takeIf { it.isNotEmpty() } } ?: throw e
        }
        return found.sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
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
    is java.net.UnknownHostException -> unknownHostReason(e)
    is java.net.SocketTimeoutException ->
        if (NetHttp.isConnectTimeout(e)) "서버에 연결하지 못했습니다 (세 번 시도, 매번 10초 동안 응답 없음). 이 망이 이 서버로 가는 길을 막고 있을 수 있습니다. 와이파이를 끄고 모바일 데이터로 다시 해 보세요."
        else "서버에 연결은 됐지만 답이 제때 오지 않았습니다. 서버가 붐비거나 답이 길어 오래 걸린 것일 수 있으니 잠시 뒤 다시 해 보세요."
    is javax.net.ssl.SSLException -> "보안 연결에 실패했습니다. 휴대폰 날짜·시간이 맞는지, VPN 이나 회사·학교 와이파이가 막고 있지 않은지 확인하세요. (${e.message ?: e.javaClass.simpleName})"
    is java.net.ConnectException, is java.net.NoRouteToHostException -> "서버에 연결하지 못했습니다. 와이파이가 막는 경우가 있으니 모바일 데이터로 바꿔 다시 해 보세요. (${e.message ?: e.javaClass.simpleName})"
    is IOException -> "인터넷 연결을 확인하세요. (${e.message ?: e.javaClass.simpleName})"
    else -> "요청을 보내지 못했습니다. 키에 빈칸·줄바꿈·한글이 섞이지 않았는지 확인하세요. (${e.javaClass.simpleName})"
}

/** 주소를 찾을 수 있는지 (시험에서 바꿔 끼운다) */
var hostResolves: (String) -> Boolean = { host -> runCatching { java.net.InetAddress.getByName(host) }.isSuccess }

/**
 * 서버 주소를 못 찾은 까닭을 가린다: 인터넷 자체가 끊긴 것인지, 이 주소만 못 찾는 것인지.
 * 이 주소만 못 찾는다면 휴대폰·공유기의 DNS(비공개 DNS, 광고 차단, VPN, 회사·학교 망)가 막고 있는 경우가 대부분이다.
 */
fun unknownHostReason(e: Throwable): String {
    val host = Regex("\"([^\"]+)\"").find(e.message.orEmpty())?.groupValues?.get(1) ?: e.message.orEmpty().substringBefore(':').trim()
    val othersOk = listOf("www.google.com", "api.upbit.com").any { it != host && hostResolves(it) }
    return if (othersOk) {
        "휴대폰이 이 서버 주소${if (host.isNotBlank()) "($host)" else ""}만 찾지 못합니다 (세 번 시도). 다른 곳은 연결되므로 인터넷은 정상이고, 서버도 정상입니다. " +
            "휴대폰이나 공유기의 DNS 가 이 주소를 막고 있을 가능성이 큽니다. ① 와이파이를 끄고 모바일 데이터로 다시 ② 휴대폰 설정 → 연결 → 기타 연결 설정 → 비공개 DNS 를 '자동'으로 ③ VPN·광고 차단 앱을 끄고 다시 해 보세요."
    } else {
        "서버 주소를 찾지 못했습니다 (세 번 시도). 지금 인터넷이 끊겨 있는 것 같습니다. 와이파이·모바일 데이터가 켜져 있는지 확인하고 다시 해 보세요."
    }
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
