package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.sin

/**
 * OpenStreetMap Nominatim 지오코더 (키 필요 없음). 휴대폰 내장 지오코더가 안 될 때의 예비 수단이다.
 * 공개 서버 이용 규칙에 따라 1초에 1번 넘게 부르지 않고, 앱을 알리는 User-Agent 를 보낸다.
 * 한국 지번까지는 잘 못 찾는 경우가 많아 동 단위로 물러나 근사 위치를 쓴다.
 */
class NominatimClient(
    private val transport: WebTransport = UrlConnectionTransport(),
    private val minIntervalMs: Long = 1_100,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var lastCall = 0L

    private val headers = mapOf(
        "User-Agent" to "GongmaeCompare/1.0 (Android personal app; https://github.com/jgg113/jgg113)",
        "Accept" to "application/json",
        "Accept-Language" to "ko",
    )

    @Synchronized
    private fun get(url: String): String? {
        val wait = minIntervalMs - (clock() - lastCall)
        if (lastCall > 0 && wait > 0) sleeper(wait)
        return try {
            transport.get(url, headers).takeIf { it.status == 200 }?.body
        } catch (e: IOException) {
            null
        } finally {
            lastCall = clock()
        }
    }

    fun search(query: String): LatLng? {
        val url = "$BASE/search?format=jsonv2&limit=1&countrycodes=kr&accept-language=ko&q=" + URLEncoder.encode(query, "UTF-8")
        return parseSearch(get(url) ?: return null)
    }

    /** 좌표의 주소 문자열 ("연남동, 마포구, 서울특별시, …") */
    fun reverse(position: LatLng): String? {
        val url = "$BASE/reverse?format=jsonv2&zoom=14&accept-language=ko&lat=${position.lat}&lon=${position.lng}"
        return parseReverse(get(url) ?: return null)
    }

    fun parseSearch(body: String): LatLng? {
        val first = (runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val lat = first.decimal("lat") ?: return null
        val lng = first.decimal("lon") ?: return null
        return LatLng(lat, lng).takeIf { it.lat in 33.0..39.5 && it.lng in 124.0..132.0 }
    }

    fun parseReverse(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val address = root["address"] as? JsonObject
        val parts = listOfNotNull(
            root.str("display_name").takeIf { it.isNotEmpty() },
            address?.firstStr("province", "state", "city"),
            address?.firstStr("city", "county"),
            address?.firstStr("borough", "city_district", "county"),
        )
        return parts.joinToString(" ").ifBlank { null }
    }

    companion object {
        const val BASE = "https://nominatim.openstreetmap.org"

        /**
         * 동 중심처럼 근사 좌표를 여러 물건이 같이 쓸 때 말풍선이 겹치지 않도록 물건마다 조금(최대 약 150m) 비킨다.
         */
        fun spread(center: LatLng, seed: String): LatLng {
            val h = seed.hashCode()
            val angle = (h and 0xFFFF) / 65535.0 * 2 * Math.PI
            val radius = 40 + ((h ushr 16) and 0xFF) / 255.0 * 110 // m
            val dLat = radius * cos(angle) / 111_000.0
            val dLng = radius * sin(angle) / (111_000.0 * cos(Math.toRadians(center.lat)))
            return LatLng(center.lat + dLat, center.lng + dLng)
        }
    }
}
