package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 주변 시설 종류와 OpenStreetMap 검색 조건. */
enum class NearbyCategory(val label: String, val selectors: List<String>) {
    SUBWAY("지하철·기차역", listOf("nwr[\"railway\"=\"station\"]")),
    BUS("버스정류장", listOf("node[\"highway\"=\"bus_stop\"]")),
    SCHOOL("학교", listOf("nwr[\"amenity\"=\"school\"]")),
    KINDERGARTEN("어린이집·유치원", listOf("nwr[\"amenity\"~\"^(kindergarten|childcare)$\"]")),
    MART("마트·시장", listOf("nwr[\"shop\"~\"^(supermarket|department_store|mall)$\"]", "nwr[\"amenity\"=\"marketplace\"]")),
    CONVENIENCE("편의점", listOf("nwr[\"shop\"=\"convenience\"]")),
    HOSPITAL("병원", listOf("nwr[\"amenity\"~\"^(hospital|clinic|doctors)$\"]")),
    PHARMACY("약국", listOf("nwr[\"amenity\"=\"pharmacy\"]")),
    BANK("은행", listOf("nwr[\"amenity\"=\"bank\"]")),
    PUBLIC_OFFICE("관공서", listOf("nwr[\"amenity\"~\"^(townhall|police|post_office|fire_station)$\"]")),
    PARK("공원", listOf("nwr[\"leisure\"=\"park\"]")),
    CAFE("카페", listOf("nwr[\"amenity\"=\"cafe\"]")),
}

data class NearbyPlace(val name: String, val distanceM: Int, val category: String, val lat: Double, val lng: Double)

/**
 * OpenStreetMap 의 Overpass API 로 주변 시설을 찾는다. 키가 필요 없다.
 * 공개 서버라 느리거나 바쁠 수 있어 예비 서버를 하나 더 둔다.
 */
class OverpassClient(private val transport: WebTransport = UrlConnectionTransport()) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun query(category: NearbyCategory, center: LatLng, radiusM: Int = 1500): String {
        val around = "(around:$radiusM,${center.lat},${center.lng})"
        val parts = category.selectors.joinToString("") { "$it$around;" }
        return "[out:json][timeout:20];($parts);out center 80;"
    }

    fun search(category: NearbyCategory, center: LatLng, radiusM: Int = 1500): List<NearbyPlace> {
        val body = "data=" + URLEncoder.encode(query(category, center, radiusM), "UTF-8")
        val headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "User-Agent" to "GongmaeCompare/1.0 (Android; io.github.jgg113.gongmae)",
            "Accept" to "application/json",
        )
        var lastError: String? = null
        for (endpoint in ENDPOINTS) {
            try {
                val response = transport.post(endpoint, headers, body)
                if (response.status == 200) return parse(response.body, category, center)
                lastError = "HTTP ${response.status}"
            } catch (e: IOException) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        throw OnbidApiException("OVERPASS", "주변 시설 정보를 불러오지 못했습니다 ($lastError). 잠시 후 다시 시도하세요.")
    }

    fun parse(body: String, category: NearbyCategory, center: LatLng): List<NearbyPlace> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        return root["elements"].objects().mapNotNull { element ->
            val tags = element["tags"] as? JsonObject ?: return@mapNotNull null
            val name = tags.firstStr("name:ko", "name").ifEmpty { return@mapNotNull null }
            val point = (element["center"] as? JsonObject) ?: element
            val lat = point.decimal("lat") ?: return@mapNotNull null
            val lng = point.decimal("lon") ?: return@mapNotNull null
            NearbyPlace(name, distanceMeters(center, LatLng(lat, lng)), category.label, lat, lng)
        }
            .sortedBy { it.distanceM }
            .distinctBy { it.name }
    }

    companion object {
        val ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

        fun distanceMeters(a: LatLng, b: LatLng): Int {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
            return (2 * r * asin(sqrt(h))).roundToInt()
        }
    }
}
