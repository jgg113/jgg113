package io.github.jgg113.gongmae.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 법원경매정보 검색 결과의 xCordi/yCordi 는 KATEC(TM128) 좌표다.
 * (베셀 타원체, 중앙자오선 128°, 원점위도 38°, 축척 0.9999, 가산값 400000/600000)
 * 횡메르카토르 역변환 후 3변수 이동으로 WGS84 경위도로 바꾼다. 오차는 수 m~수십 m 수준.
 */
object Katec {
    private const val A = 6_377_397.155
    private const val INV_F = 299.1528128
    private const val K0 = 0.9999
    private const val LAT0 = 38.0
    private const val LON0 = 128.0
    private const val FALSE_EASTING = 400_000.0
    private const val FALSE_NORTHING = 600_000.0

    // 베셀(한국 측지계) → WGS84 3변수
    private const val DX = -146.43
    private const val DY = 507.89
    private const val DZ = 681.46

    private const val WGS_A = 6_378_137.0
    private const val WGS_INV_F = 298.257223563

    fun toWgs84(x: Double, y: Double): LatLng {
        val (lat, lon) = inverseTm(x, y)
        return besselToWgs84(lat, lon)
    }

    /** 대한민국 범위의 그럴듯한 좌표만 받아들인다. */
    fun toWgs84OrNull(x: Double?, y: Double?): LatLng? {
        if (x == null || y == null || x <= 0 || y <= 0) return null
        val p = toWgs84(x, y)
        return p.takeIf { it.lat in 33.0..39.0 && it.lng in 124.0..132.0 }
    }

    private fun meridianArc(phi: Double, e2: Double): Double {
        val e4 = e2 * e2
        val e6 = e4 * e2
        return A * (
            (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
                (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
                (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
                (35 * e6 / 3072) * sin(6 * phi)
            )
    }

    /** 횡메르카토르 역변환 (Snyder). 반환값은 베셀 타원체 기준 위도·경도(도). */
    private fun inverseTm(x: Double, y: Double): Pair<Double, Double> {
        val f = 1 / INV_F
        val e2 = 2 * f - f * f
        val ep2 = e2 / (1 - e2)
        val m0 = meridianArc(Math.toRadians(LAT0), e2)
        val m = m0 + (y - FALSE_NORTHING) / K0
        val mu = m / (A * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256))
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val phi1 = mu +
            (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) +
            (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val sin1 = sin(phi1)
        val cos1 = cos(phi1)
        val tan1 = tan(phi1)
        val c1 = ep2 * cos1 * cos1
        val t1 = tan1 * tan1
        val n1 = A / sqrt(1 - e2 * sin1 * sin1)
        val r1 = A * (1 - e2) / (1 - e2 * sin1 * sin1).pow(1.5)
        val d = (x - FALSE_EASTING) / (n1 * K0)
        val lat = phi1 - (n1 * tan1 / r1) * (
            d * d / 2 -
                (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6) / 720
            )
        val lon = Math.toRadians(LON0) + (
            d -
                (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5) / 120
            ) / cos1
        return Math.toDegrees(lat) to Math.toDegrees(lon)
    }

    private fun besselToWgs84(latDeg: Double, lonDeg: Double): LatLng {
        val f = 1 / INV_F
        val e2 = 2 * f - f * f
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val n = A / sqrt(1 - e2 * sin(lat) * sin(lat))
        val x = n * cos(lat) * cos(lon) + DX
        val y = n * cos(lat) * sin(lon) + DY
        val z = n * (1 - e2) * sin(lat) + DZ

        val wf = 1 / WGS_INV_F
        val we2 = 2 * wf - wf * wf
        val wb = WGS_A * (1 - wf)
        val wep2 = (WGS_A * WGS_A - wb * wb) / (wb * wb)
        val p = sqrt(x * x + y * y)
        val theta = atan2(z * WGS_A, p * wb)
        val outLat = atan2(z + wep2 * wb * sin(theta).pow(3), p - we2 * WGS_A * cos(theta).pow(3))
        val outLon = atan2(y, x)
        return LatLng(Math.toDegrees(outLat), Math.toDegrees(outLon))
    }
}
