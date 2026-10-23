package io.github.jgg113.gongmae.core

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 지도 거리·면적 재기 */
object GeoMath {
    private const val EARTH_RADIUS = 6_371_008.8

    private fun rad(deg: Double) = deg * PI / 180

    /** 두 점 사이 거리 (m) */
    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = rad(b.lat - a.lat)
        val dLng = rad(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** 꺾은선 길이 (m) */
    fun pathLength(points: List<LatLng>): Double = points.zipWithNext { a, b -> distance(a, b) }.sum()

    /** 닫힌 다각형 넓이 (㎡). 점이 3개 미만이면 0. 구면 공식을 쓰므로 수 km 범위에서 충분히 정확하다. */
    fun area(points: List<LatLng>): Double {
        if (points.size < 3) return 0.0
        var total = 0.0
        for (i in points.indices) {
            val p1 = points[i]
            val p2 = points[(i + 1) % points.size]
            total += rad(p2.lng - p1.lng) * (2 + sin(rad(p1.lat)) + sin(rad(p2.lat)))
        }
        return abs(total * EARTH_RADIUS * EARTH_RADIUS / 2.0)
    }

    fun formatLength(meters: Double): String =
        if (meters < 1000) "${Math.round(meters)}m" else String.format(Locale.US, "%.2fkm", meters / 1000)

    fun formatArea(sqm: Double): String {
        val pyeong = sqm / Property.SQM_PER_PYEONG
        return if (sqm >= 1_000_000) {
            String.format(Locale.US, "%.2f㎢ (%,d평)", sqm / 1_000_000, Math.round(pyeong))
        } else {
            String.format(Locale.US, "%,d㎡ (%,d평)", Math.round(sqm), Math.round(pyeong))
        }
    }
}
