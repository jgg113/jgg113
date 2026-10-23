package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 지도(WebView)로 넘기는 마커 데이터. 좌표는 앱이 미리 지오코딩해 둔다. */
object MapPayload {
    /** 지오코딩 캐시 키. 같은 지번의 여러 호실이 좌표 하나를 나눠 쓴다. */
    fun geoKey(p: Property): String = p.geocodeQueries.firstOrNull().orEmpty()

    fun item(p: Property, cached: LatLng?): JsonObject = buildJsonObject {
        put("id", p.id)
        put("label", Format.wonShort(p.minBidPrice))
        put("sub", p.appraisalRatio?.let { Format.percent(it) } ?: "")
        put("title", p.name.ifBlank { p.usageLabel })
        put("gk", geoKey(p))
        put("court", p.isCourt)
        if (cached != null) {
            put("lat", cached.lat)
            put("lng", cached.lng)
        }
    }

    /** 좌표를 아는 물건만 지도로 보낸다. 원본 좌표(법원경매)가 있으면 그것을 쓴다. */
    fun items(list: List<Property>, cache: (Property) -> LatLng?): String = buildJsonArray {
        list.forEach { p -> (p.position ?: cache(p))?.let { add(item(p, it)) } }
    }.toString()
}
