package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 지도(WebView)로 넘기는 마커 데이터. 좌표를 아는 물건은 지오코딩 없이 바로 찍힌다. */
object MapPayload {
    /** 지오코딩 캐시 키. 같은 지번의 여러 호실이 좌표 하나를 나눠 쓴다. */
    fun geoKey(p: Property): String = p.geocodeQueries.firstOrNull().orEmpty()

    fun item(p: Property, cached: LatLng?): JsonObject = buildJsonObject {
        put("id", p.id)
        put("label", Format.wonShort(p.minBidPrice))
        put("sub", p.appraisalRatio?.let { Format.percent(it) } ?: "")
        put("title", p.name.ifBlank { p.usageLabel })
        put("gk", geoKey(p))
        putJsonArray("q") { p.geocodeQueries.forEach { add(it) } }
        if (cached != null) {
            put("lat", cached.lat)
            put("lng", cached.lng)
        }
    }

    fun items(list: List<Property>, cache: (String) -> LatLng?): String =
        buildJsonArray { list.forEach { add(item(it, cache(geoKey(it)))) } }.toString()
}

/** 카카오 카테고리 검색 결과 한 건 (지도 페이지가 JSON 으로 넘겨준다). */
data class NearbyPlace(val name: String, val distanceM: Int, val category: String, val url: String) {
    companion object {
        fun parse(json: String): List<NearbyPlace> {
            val array = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return emptyList()
            return array.filterIsInstance<JsonObject>().map {
                NearbyPlace(
                    name = it.str("name"),
                    distanceM = parseAmount(it.str("distance"))?.toInt() ?: 0,
                    category = it.str("category").substringAfterLast(">").trim(),
                    url = it.str("url"),
                )
            }.filter { it.name.isNotBlank() }
        }
    }
}

/** 카카오 장소 카테고리 그룹 코드 중 집 고를 때 보는 것들. */
enum class NearbyCategory(val code: String, val label: String) {
    SUBWAY("SW8", "지하철"),
    SCHOOL("SC4", "학교"),
    KINDERGARTEN("PS3", "어린이집·유치원"),
    ACADEMY("AC5", "학원"),
    MART("MT1", "대형마트"),
    CONVENIENCE("CS2", "편의점"),
    HOSPITAL("HP8", "병원"),
    PHARMACY("PM9", "약국"),
    BANK("BK9", "은행"),
    PUBLIC_OFFICE("PO3", "관공서"),
    PARKING("PK6", "주차장"),
    CAFE("CE7", "카페"),
}
