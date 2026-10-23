package io.github.jgg113.gongmae.core

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/** 지도 마커에 쓰는 정보. 좌표는 앱이 미리 지오코딩해 둔다. */
object MapPayload {
    /** 지오코딩 캐시 키. 같은 지번의 여러 호실이 좌표 하나를 나눠 쓴다. */
    fun geoKey(p: Property): String = p.geocodeQueries.firstOrNull().orEmpty()

    /** 말풍선 첫 줄: 최저가 ("1.9억", "6,080만") */
    fun priceLabel(p: Property): String = Format.wonShort(p.minBidPrice)

    /** 말풍선 둘째 줄: 종류·평수·유찰 ("오피스텔 14평 · 유찰2", "아파트 25평 · 신건", "자동차 · 유찰1") */
    fun detailLabel(p: Property): String {
        val kind = when (p.category) {
            Category.MOVABLE -> GoodsGroup.of(p).label.substringBefore("(").substringBefore("·")
            Category.VILLA -> "빌라"
            Category.HOUSE -> "주택"
            Category.COMMERCIAL -> "상가"
            Category.FACTORY -> "공장"
            else -> p.category.label
        }
        val size = p.areaSqm?.takeIf { it > 0 && !p.category.isMovable }?.let { "${(it / Property.SQM_PER_PYEONG).roundToLong()}평" }
        val fails = if (p.failCount > 0) "유찰${p.failCount}" else "신건"
        return listOfNotNull(listOfNotNull(kind, size).joinToString(" "), fails).joinToString(" · ")
    }
}

data class MapPoint(val id: String, val position: LatLng)

/** 지도에서 가까이 붙은 물건 묶음. [ids] 가 하나면 개별 물건이다. */
data class MapCluster(val ids: List<String>, val center: LatLng) {
    val isSingle: Boolean get() = ids.size == 1
}

/**
 * 화면 픽셀 기준으로 가까운 물건을 묶는다. 줌을 정수로 내려 같은 줌에서는 묶음이 흔들리지 않게 한다.
 * [splitZoom] 이상으로 확대하면 묶지 않는다.
 */
object MapClusterer {
    /** 웹 메르카토르 전체 지도 픽셀 좌표 (타일 256px) */
    fun worldPixel(p: LatLng, zoom: Double): Pair<Double, Double> {
        val scale = 256.0 * 2.0.pow(zoom)
        val x = (p.lng + 180.0) / 360.0 * scale
        val s = sin(Math.toRadians(p.lat)).coerceIn(-0.9999, 0.9999)
        val y = (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * scale
        return x to y
    }

    fun cluster(points: List<MapPoint>, zoom: Double, cellPx: Double = 80.0, splitZoom: Double = 17.0): List<MapCluster> {
        if (zoom >= splitZoom) return points.map { MapCluster(listOf(it.id), it.position) }
        val z = floor(zoom)
        val cells = LinkedHashMap<Pair<Long, Long>, MutableList<MapPoint>>()
        for (point in points) {
            val (x, y) = worldPixel(point.position, z)
            cells.getOrPut(floor(x / cellPx).toLong() to floor(y / cellPx).toLong()) { mutableListOf() } += point
        }
        return cells.values.map { group ->
            MapCluster(group.map { it.id }, LatLng(group.map { it.position.lat }.average(), group.map { it.position.lng }.average()))
        }
    }
}
