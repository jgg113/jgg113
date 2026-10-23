package io.github.jgg113.gongmae.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jgg113.gongmae.core.LatLng

/** 지도 페이지(assets/kakao_map.html)에서 올라오는 이벤트. */
sealed interface MapEvent {
    data object Ready : MapEvent
    data class Message(val code: String) : MapEvent
    data class MarkerClick(val id: String) : MapEvent
    data class Geocoded(val key: String, val position: LatLng) : MapEvent
    data class Located(val position: LatLng) : MapEvent
    data class Nearby(val json: String) : MapEvent
    data class Region(val sido: String, val sigungu: String) : MapEvent
}

/** 화면 쪽에서 지도에 명령을 보낸다. 지도가 준비되기 전 명령은 버린다(준비되면 화면이 다시 보낸다). */
class KakaoMapController {
    internal var webView: WebView? = null
    var ready by mutableStateOf(false)
        internal set
    var failed by mutableStateOf(false)
        internal set

    fun run(script: String) {
        val view = webView ?: return
        if (ready) view.evaluateJavascript(script, null)
    }
}

@Composable
fun rememberKakaoMapController(): KakaoMapController = remember { KakaoMapController() }

/** JS → Kotlin 다리. 자바스크립트 스레드에서 불리므로 메인 스레드로 넘긴다. */
class MapBridge(private val onEvent: (MapEvent) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    private fun post(event: MapEvent) {
        main.post { onEvent(event) }
    }

    @JavascriptInterface
    fun onReady() = post(MapEvent.Ready)

    @JavascriptInterface
    fun onMessage(code: String) = post(MapEvent.Message(code))

    @JavascriptInterface
    fun onMarkerClick(id: String) = post(MapEvent.MarkerClick(id))

    @JavascriptInterface
    fun onGeocoded(key: String, lat: Double, lng: Double) = post(MapEvent.Geocoded(key, LatLng(lat, lng)))

    @JavascriptInterface
    fun onLocated(lat: Double, lng: Double) = post(MapEvent.Located(LatLng(lat, lng)))

    @JavascriptInterface
    fun onNearby(json: String) = post(MapEvent.Nearby(json))

    @JavascriptInterface
    fun onRegion(sido: String, sigungu: String) = post(MapEvent.Region(sido, sigungu))
}

/**
 * 카카오맵 JavaScript API 를 WebView 로 띄운다.
 * 카카오는 JavaScript 키를 등록된 도메인에서만 허용하므로 페이지 출처를 http://localhost 로 둔다.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun KakaoMap(
    appKey: String,
    controller: KakaoMapController,
    modifier: Modifier = Modifier,
    onEvent: (MapEvent) -> Unit,
) {
    val currentOnEvent by rememberUpdatedState(onEvent)
    key(appKey) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                    addJavascriptInterface(
                        MapBridge { event ->
                            when (event) {
                                MapEvent.Ready -> controller.ready = true
                                is MapEvent.Message -> if (event.code == "SDK_FAILED") controller.failed = true
                                else -> Unit
                            }
                            currentOnEvent(event)
                        },
                        "Android",
                    )
                    // 목록 안에 있어도 지도를 끌면 목록이 아니라 지도가 움직이게 한다.
                    setOnTouchListener { view, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    controller.webView = this
                    controller.ready = false
                    controller.failed = false
                    val html = context.assets.open("kakao_map.html").bufferedReader().use { it.readText() }
                        .replace("__APPKEY__", appKey.trim())
                    loadDataWithBaseURL("http://localhost/", html, "text/html", "UTF-8", null)
                }
            },
            onRelease = { view ->
                if (controller.webView === view) {
                    controller.webView = null
                    controller.ready = false
                }
                view.destroy()
            },
        )
    }
}

object DeviceLocation {
    /** 최근 위치(10분 이내)가 있으면 바로, 없으면 한 번 측위해서 돌려준다. 권한은 호출 전에 받아 둔다. */
    @SuppressLint("MissingPermission")
    fun request(context: Context, onResult: (LatLng?) -> Unit) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            onResult(null)
            return
        }
        val last = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000) {
            onResult(last.toLatLng())
            return
        }
        val fallback = last?.toLatLng()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(providers.first(), null, context.mainExecutor) { location ->
                    onResult(location?.toLatLng() ?: fallback)
                }
            } else {
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(providers.first(), SingleUpdateListener { onResult(it?.toLatLng() ?: fallback) }, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            onResult(fallback)
        }
    }

    private fun Location.toLatLng() = LatLng(latitude, longitude)

    /** Android 10 이하는 LocationListener 의 기본 구현이 없어 모든 메서드를 직접 구현해야 한다. */
    private class SingleUpdateListener(private val onLocation: (Location?) -> Unit) : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = onLocation(null)
    }
}
