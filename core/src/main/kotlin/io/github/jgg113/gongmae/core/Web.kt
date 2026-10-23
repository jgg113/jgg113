package io.github.jgg113.gongmae.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

data class WebResponse(val status: Int, val body: String, val setCookies: List<String> = emptyList())

/** 헤더·쿠키가 필요한 웹사이트(법원경매정보 등)용 HTTP. 테스트에서 가짜로 바꿔 끼운다. */
interface WebTransport {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): WebResponse

    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, body: String): WebResponse
}

object UrlConnectionTransport : WebTransport {
    override fun get(url: String, headers: Map<String, String>): WebResponse = request("GET", url, headers, null)

    override fun post(url: String, headers: Map<String, String>, body: String): WebResponse = request("POST", url, headers, body)

    private fun request(method: String, url: String, headers: Map<String, String>, body: String?): WebResponse {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 45_000
        conn.instanceFollowRedirects = true
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val cookies = conn.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return WebResponse(status, text, cookies)
        } finally {
            conn.disconnect()
        }
    }
}

/** Set-Cookie 를 모아 Cookie 헤더로 돌려주는 최소한의 쿠키 저장소. */
class CookieJar {
    private val cookies = linkedMapOf<String, String>()

    fun store(setCookies: List<String>) {
        setCookies.forEach { raw ->
            val pair = raw.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty() && pair.contains('=')) cookies[name] = pair.substringAfter('=').trim()
        }
    }

    fun has(name: String): Boolean = name in cookies

    fun header(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
