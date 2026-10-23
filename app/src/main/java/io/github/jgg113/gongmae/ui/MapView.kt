package io.github.jgg113.gongmae.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jgg113.gongmae.core.LatLng
import io.github.jgg113.gongmae.core.MapCluster
import io.github.jgg113.gongmae.core.MapClusterer
import io.github.jgg113.gongmae.core.MapPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** 지도 그림 종류 */
enum class MapStyle(val label: String) {
    STREET("일반"),
    OSM("OSM"),
    SATELLITE("위성"),
}

/** 지도에 찍을 물건 하나 */
data class MapMarker(
    val id: String,
    val position: LatLng,
    /** 첫 줄 (가격) */
    val title: String,
    /** 둘째 줄 (종류·평수·유찰) */
    val subtitle: String,
    /** 말풍선 색 (ARGB) */
    val color: Int,
    /** 위험도 점 색. null 이면 안 그린다 */
    val riskColor: Int? = null,
)

/** 주변 시설처럼 작게 찍는 점 */
data class PoiMarker(val name: String, val position: LatLng)

/** 지도를 밖에서 움직일 때 쓴다. */
@Stable
class NativeMapState {
    internal var view: MapView? = null
    var zoom by mutableDoubleStateOf(DEFAULT_ZOOM)
        internal set

    /** 지도가 화면에 붙어 크기가 정해졌는지 */
    var ready by mutableStateOf(false)
        internal set

    fun fit(points: List<LatLng>, maxZoom: Double = 16.5) {
        val map = view ?: return
        when {
            points.isEmpty() -> Unit
            points.size == 1 -> moveTo(points.first(), maxZoom)
            else -> {
                val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lng) }).increaseByScale(1.25f)
                map.zoomToBoundingBox(box, false, 48)
                if (map.zoomLevelDouble > maxZoom) map.controller.setZoom(maxZoom)
            }
        }
    }

    fun moveTo(point: LatLng, zoom: Double? = null) {
        val map = view ?: return
        if (zoom != null) map.controller.setZoom(zoom)
        map.controller.animateTo(GeoPoint(point.lat, point.lng))
    }

    fun zoomIn(point: LatLng) {
        val map = view ?: return
        map.controller.animateTo(GeoPoint(point.lat, point.lng), (map.zoomLevelDouble + 2).coerceAtMost(19.0), 400L)
    }

    companion object {
        const val DEFAULT_ZOOM = 7.0
        val KOREA_CENTER = LatLng(36.35, 127.8)
    }
}

@Composable
fun rememberNativeMapState(): NativeMapState = remember { NativeMapState() }

/**
 * 안드로이드 기본 지도 (osmdroid). 웹페이지 없이 지도 그림(타일)을 직접 받아 그리므로 키가 필요 없고,
 * 물건은 '가격 / 종류·평수·유찰' 말풍선으로, 가까이 몰린 물건은 개수 동그라미로 묶어 보여 준다.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun NativeMap(
    markers: List<MapMarker>,
    modifier: Modifier = Modifier,
    state: NativeMapState = rememberNativeMapState(),
    style: MapStyle = MapStyle.STREET,
    selectedId: String? = null,
    myLocation: LatLng? = null,
    pois: List<PoiMarker> = emptyList(),
    cluster: Boolean = true,
    onSelect: (String?) -> Unit = {},
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    val renderer = remember { MarkerRenderer() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> state.view?.onResume()
                Lifecycle.Event.ON_PAUSE -> state.view?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            configureOsmdroid(context)
            MapView(context).apply {
                setTileSource(tileSource(style))
                renderer.style = style
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                setTilesScaledToDpi(true)
                setHorizontalMapRepetitionEnabled(false)
                setVerticalMapRepetitionEnabled(false)
                setMinZoomLevel(5.0)
                setMaxZoomLevel(19.0)
                controller.setZoom(NativeMapState.DEFAULT_ZOOM)
                controller.setCenter(GeoPoint(NativeMapState.KOREA_CENTER.lat, NativeMapState.KOREA_CENTER.lng))
                overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        renderer.onSelect(null)
                        return false
                    }

                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                }))
                overlays.add(CopyrightOverlay(context).apply { setCopyrightNotice(attribution(style)) })
                addMapListener(DelayedMapListener(object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean = false

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        state.zoom = zoomLevelDouble
                        renderer.onZoomChanged(this@apply)
                        return false
                    }
                }, 150))
                addOnFirstLayoutListener { _, _, _, _, _ ->
                    state.ready = true
                    renderer.render(this)
                }
                // 목록 안에 있어도 지도를 끌면 목록이 아니라 지도가 움직이게 한다.
                setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
                state.view = this
                state.zoom = zoomLevelDouble
            }
        },
        update = { map ->
            renderer.onSelect = { currentOnSelect(it) }
            if (renderer.style != style) {
                renderer.style = style
                map.setTileSource(tileSource(style))
                map.overlays.filterIsInstance<CopyrightOverlay>().forEach { it.setCopyrightNotice(attribution(style)) }
                map.invalidate()
            }
            renderer.update(map, markers, selectedId, myLocation, pois, cluster)
        },
        onRelease = { map ->
            if (state.view === map) {
                state.view = null
                state.ready = false
            }
            map.onDetach()
        },
    )
}

/** 지도 그림 받을 곳과 저장 폴더. 앱 전용 캐시 폴더를 써서 저장소 권한이 필요 없다. */
private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    config.userAgentValue = context.packageName
    val base = File(context.cacheDir, "osmdroid")
    config.osmdroidBasePath = base
    config.osmdroidTileCache = File(base, "tiles")
}

private val cartoVoyager = XYTileSource(
    "CartoVoyager",
    0,
    20,
    256,
    ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://d.basemaps.cartocdn.com/rastertiles/voyager/",
    ),
    "© OpenStreetMap © CARTO",
)

/** Esri 위성 사진은 주소가 z/y/x 순서다. */
private val esriImagery = object : OnlineTileSourceBase(
    "EsriWorldImagery",
    0,
    19,
    256,
    "",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "© Esri, Maxar, Earthstar Geographics",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

private fun tileSource(style: MapStyle) = when (style) {
    MapStyle.STREET -> cartoVoyager
    MapStyle.OSM -> TileSourceFactory.MAPNIK
    MapStyle.SATELLITE -> esriImagery
}

private fun attribution(style: MapStyle) = when (style) {
    MapStyle.STREET -> "© OpenStreetMap 기여자 © CARTO"
    MapStyle.OSM -> "© OpenStreetMap 기여자"
    MapStyle.SATELLITE -> "© Esri, Maxar"
}

/** 마커·묶음을 다시 그린다. 같은 입력이면 다시 그리지 않는다. */
private class MarkerRenderer {
    var style: MapStyle? = null
    var onSelect: (String?) -> Unit = {}
    private var markers: List<MapMarker> = emptyList()
    private var selectedId: String? = null
    private var me: LatLng? = null
    private var pois: List<PoiMarker> = emptyList()
    private var cluster = true
    private var renderedZoom = Double.NaN
    private val drawn = mutableListOf<Marker>()
    private val bitmapCache = HashMap<String, Bitmap>()

    fun update(map: MapView, markers: List<MapMarker>, selectedId: String?, me: LatLng?, pois: List<PoiMarker>, cluster: Boolean) {
        if (markers == this.markers && selectedId == this.selectedId && me == this.me && pois == this.pois && cluster == this.cluster) return
        this.markers = markers
        this.selectedId = selectedId
        this.me = me
        this.pois = pois
        this.cluster = cluster
        if (map.width > 0) render(map)
    }

    fun onZoomChanged(map: MapView) {
        if (floor(map.zoomLevelDouble) != floor(renderedZoom)) render(map)
    }

    fun render(map: MapView) {
        val context = map.context
        renderedZoom = map.zoomLevelDouble
        drawn.forEach { map.overlays.remove(it) }
        drawn.clear()
        if (bitmapCache.size > 600) bitmapCache.clear()

        pois.forEach { poi ->
            add(map, poi.position, poiBitmap(context, poi.name), anchorBottom = false) { false }
        }

        val byId = markers.associateBy { it.id }
        val groups = if (cluster) {
            MapClusterer.cluster(markers.map { MapPoint(it.id, it.position) }, renderedZoom)
        } else {
            markers.map { MapCluster(listOf(it.id), it.position) }
        }
        // 선택한 물건은 묶음에서 빼서 항상 보이게 한다.
        val selected = selectedId?.let { byId[it] }
        groups.forEach { group ->
            val ids = group.ids.filter { it != selected?.id }
            when {
                ids.isEmpty() -> Unit
                ids.size == 1 -> {
                    val m = byId.getValue(ids.single())
                    add(map, m.position, bubble(context, m, selected = false)) {
                        onSelect(m.id)
                        true
                    }
                }
                else -> {
                    val members = ids.mapNotNull { byId[it] }
                    val colors = members.map { it.color }.distinct()
                    val center = LatLng(members.map { it.position.lat }.average(), members.map { it.position.lng }.average())
                    add(map, center, clusterBitmap(context, members.size, colors.singleOrNull() ?: MIXED_COLOR), anchorBottom = false) {
                        map.controller.animateTo(GeoPoint(center.lat, center.lng), (map.zoomLevelDouble + 2).coerceAtMost(18.0), 400L)
                        true
                    }
                }
            }
        }
        if (selected != null) {
            add(map, selected.position, bubble(context, selected, selected = true)) {
                onSelect(selected.id)
                true
            }
        }
        me?.let { add(map, it, meBitmap(context), anchorBottom = false) { false } }
        map.invalidate()
    }

    private fun add(map: MapView, at: LatLng, bitmap: Bitmap, anchorBottom: Boolean = true, onClick: () -> Boolean) {
        val marker = Marker(map).apply {
            position = GeoPoint(at.lat, at.lng)
            icon = BitmapDrawable(map.resources, bitmap)
            setAnchor(Marker.ANCHOR_CENTER, if (anchorBottom) Marker.ANCHOR_BOTTOM else Marker.ANCHOR_CENTER)
            setInfoWindow(null as MarkerInfoWindow?)
            setPanToView(false)
            setOnMarkerClickListener { _, _ -> onClick() }
        }
        map.overlays.add(marker)
        drawn += marker
    }

    private fun bubble(context: Context, m: MapMarker, selected: Boolean): Bitmap =
        bitmapCache.getOrPut("b|${m.title}|${m.subtitle}|${m.color}|${m.riskColor}|$selected") {
            bubbleBitmap(context, m.title, m.subtitle, if (selected) SELECTED_COLOR else m.color, m.riskColor, selected)
        }

    private fun clusterBitmap(context: Context, count: Int, color: Int): Bitmap =
        bitmapCache.getOrPut("c|$count|$color") { countBitmap(context, count, color) }

    private fun poiBitmap(context: Context, name: String): Bitmap =
        bitmapCache.getOrPut("p|$name") { tagBitmap(context, name) }

    private fun meBitmap(context: Context): Bitmap = bitmapCache.getOrPut("me") { dotBitmap(context) }

    companion object {
        val SELECTED_COLOR = 0xFFE53935.toInt()
        val MIXED_COLOR = 0xFF00897B.toInt()
    }
}

/** 가격(굵게) + 종류·평수·유찰 두 줄 말풍선, 아래쪽에 꼬리 */
private fun bubbleBitmap(context: Context, title: String, subtitle: String, color: Int, riskColor: Int?, selected: Boolean): Bitmap {
    val d = context.resources.displayMetrics.density
    val scale = if (selected) 1.15f else 1f
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13.5f * d * scale
        typeface = Typeface.DEFAULT_BOLD
        this.color = android.graphics.Color.WHITE
    }
    val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * d * scale
        this.color = android.graphics.Color.argb(235, 255, 255, 255)
    }
    val padH = 8f * d
    val padV = 4.5f * d
    val gap = 1f * d
    val tail = 7f * d
    val border = 1.5f * d
    val titleH = titlePaint.fontMetrics.let { it.descent - it.ascent }
    val subH = if (subtitle.isEmpty()) 0f else subPaint.fontMetrics.let { it.descent - it.ascent }
    val dotSpace = if (riskColor != null) 9f * d else 0f
    val contentW = max(titlePaint.measureText(title) + dotSpace, subPaint.measureText(subtitle))
    val w = contentW + padH * 2
    val h = padV * 2 + titleH + (if (subtitle.isEmpty()) 0f else gap + subH)
    val bitmap = Bitmap.createBitmap(ceil(w + border * 2).toInt(), ceil(h + tail + border * 2).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val rect = RectF(border, border, border + w, border + h)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = border
        this.color = android.graphics.Color.WHITE
    }
    val cx = rect.centerX()
    val tailPath = Path().apply {
        moveTo(cx - tail * 0.8f, rect.bottom - border)
        lineTo(cx, rect.bottom + tail)
        lineTo(cx + tail * 0.8f, rect.bottom - border)
        close()
    }
    canvas.drawRoundRect(rect, 9f * d, 9f * d, fill)
    canvas.drawRoundRect(rect, 9f * d, 9f * d, stroke)
    canvas.drawPath(tailPath, fill)
    val titleW = titlePaint.measureText(title) + dotSpace
    var x = cx - titleW / 2
    if (riskColor != null) {
        val r = 3.5f * d
        val cy = border + padV + titleH / 2
        canvas.drawCircle(x + r, cy, r + 1f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE })
        canvas.drawCircle(x + r, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = riskColor })
        x += dotSpace
    }
    canvas.drawText(title, x, border + padV - titlePaint.fontMetrics.ascent, titlePaint)
    if (subtitle.isNotEmpty()) {
        canvas.drawText(subtitle, cx - subPaint.measureText(subtitle) / 2, border + padV + titleH + gap - subPaint.fontMetrics.ascent, subPaint)
    }
    return bitmap
}

/** 묶음: 동그라미 안에 개수 */
private fun countBitmap(context: Context, count: Int, color: Int): Bitmap {
    val d = context.resources.displayMetrics.density
    val text = if (count > 999) "999+" else count.toString()
    val radius = (17f + 3f * text.length.coerceAtMost(4)) * d
    val size = ceil(radius * 2 + 8 * d).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = size / 2f
    canvas.drawCircle(c, c, radius + 4 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = (color and 0x00FFFFFF) or 0x55000000 })
    canvas.drawCircle(c, c, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    canvas.drawCircle(c, c, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        this.color = android.graphics.Color.WHITE
    })
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * d
        typeface = Typeface.DEFAULT_BOLD
        this.color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(text, c, c - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2, paint)
    return bitmap
}

/** 주변 시설 이름표 */
private fun tagBitmap(context: Context, name: String): Bitmap {
    val d = context.resources.displayMetrics.density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * d
        this.color = 0xFF37474F.toInt()
    }
    val label = name.take(14)
    val padH = 5f * d
    val padV = 2.5f * d
    val textH = paint.fontMetrics.let { it.descent - it.ascent }
    val w = paint.measureText(label) + padH * 2
    val h = textH + padV * 2
    val bitmap = Bitmap.createBitmap(ceil(w + 2).toInt(), ceil(h + 2).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val rect = RectF(1f, 1f, 1f + w, 1f + h)
    canvas.drawRoundRect(rect, 5f * d, 5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xF2FFFFFF.toInt() })
    canvas.drawRoundRect(rect, 5f * d, 5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        this.color = 0xFF90A4AE.toInt()
    })
    canvas.drawText(label, rect.left + padH, rect.top + padV - paint.fontMetrics.ascent, paint)
    return bitmap
}

/** 내 위치 파란 점 */
private fun dotBitmap(context: Context): Bitmap {
    val d = context.resources.displayMetrics.density
    val size = ceil(26 * d).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = size / 2f
    canvas.drawCircle(c, c, 12 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332962FF })
    canvas.drawCircle(c, c, 7 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    canvas.drawCircle(c, c, 5 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2962FF.toInt() })
    return bitmap
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
