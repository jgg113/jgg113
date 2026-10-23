package io.github.jgg113.gongmae.data

import android.content.Context
import android.location.Geocoder
import io.github.jgg113.gongmae.core.LatLng
import java.util.Locale

/** 휴대폰 내장 지오코더(구글 위치 서비스). 키가 필요 없다. 네트워크를 쓰므로 백그라운드에서 부른다. */
class DeviceGeocoder(private val context: Context) {
    val available: Boolean get() = Geocoder.isPresent()

    @Suppress("DEPRECATION")
    fun locate(query: String): LatLng? {
        val address = runCatching { Geocoder(context, Locale.KOREA).getFromLocationName(query, 1)?.firstOrNull() }.getOrNull()
            ?: return null
        val position = LatLng(address.latitude, address.longitude)
        // 엉뚱한 나라로 잡히는 경우를 거른다.
        return position.takeIf { it.lat in 33.0..39.5 && it.lng in 124.0..132.0 }
    }

    /** 좌표의 주소 문자열 ("대한민국 서울특별시 마포구 …") */
    @Suppress("DEPRECATION")
    fun addressOf(position: LatLng): String? {
        val address = runCatching { Geocoder(context, Locale.KOREA).getFromLocation(position.lat, position.lng, 1)?.firstOrNull() }.getOrNull()
            ?: return null
        return listOfNotNull(address.getAddressLine(0), address.adminArea, address.subAdminArea, address.locality, address.subLocality)
            .joinToString(" ")
    }
}
