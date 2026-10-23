package io.github.jgg113.gongmae.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import io.github.jgg113.gongmae.core.LatLng
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 휴대폰 내장 지오코더(구글 위치 서비스). 키가 필요 없다.
 * Android 13 이상은 콜백 방식만 제대로 동작하는 기기가 있어 그쪽을 쓰고, 응답이 없으면 8초 뒤 포기한다.
 */
class DeviceGeocoder(private val context: Context) {
    private val available: Boolean get() = Geocoder.isPresent()

    suspend fun locate(query: String): LatLng? {
        if (!available) return null
        val address = lookup { geocoder, done ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocationName(query, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = done(addresses.firstOrNull())
                    override fun onError(errorMessage: String?) = done(null)
                })
            } else {
                @Suppress("DEPRECATION")
                done(geocoder.getFromLocationName(query, 1)?.firstOrNull())
            }
        } ?: return null
        val position = LatLng(address.latitude, address.longitude)
        // 엉뚱한 나라로 잡히는 경우를 거른다.
        return position.takeIf { it.lat in 33.0..39.5 && it.lng in 124.0..132.0 }
    }

    /** 좌표의 주소 문자열 ("대한민국 서울특별시 마포구 …") */
    suspend fun addressOf(position: LatLng): String? {
        if (!available) return null
        val address = lookup { geocoder, done ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(position.lat, position.lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = done(addresses.firstOrNull())
                    override fun onError(errorMessage: String?) = done(null)
                })
            } else {
                @Suppress("DEPRECATION")
                done(geocoder.getFromLocation(position.lat, position.lng, 1)?.firstOrNull())
            }
        } ?: return null
        return listOfNotNull(address.getAddressLine(0), address.adminArea, address.subAdminArea, address.locality, address.subLocality)
            .joinToString(" ")
            .ifBlank { null }
    }

    private suspend fun lookup(call: (Geocoder, (Address?) -> Unit) -> Unit): Address? = withTimeoutOrNull(8_000) {
        suspendCancellableCoroutine { continuation ->
            try {
                call(Geocoder(context, Locale.KOREA)) { address ->
                    if (continuation.isActive) continuation.resume(address)
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
