package io.github.jgg113.gongmae.data

import android.content.Context
import io.github.jgg113.gongmae.core.AssetType
import io.github.jgg113.gongmae.core.LatLng
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.PropertyFilter
import io.github.jgg113.gongmae.core.SortOrder
import io.github.jgg113.gongmae.core.Source
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 검색 조건 (지역·공매/경매·재산유형). */
data class SearchSettings(
    val sido: String = "서울특별시",
    val sigungu: String = "",
    val sources: Set<Source> = Source.entries.toSet(),
    val assetTypes: Set<AssetType> = AssetType.entries.toSet(),
    val includePrivateContract: Boolean = false,
    /** 법원경매: 오늘부터 몇 주 안의 매각기일까지 */
    val courtWeeks: Int = 4,
)

/** 인증키·검색조건·관심물건을 기기에만 저장한다. */
class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("gongmae", Context.MODE_PRIVATE)
    private val geoPrefs = context.getSharedPreferences("geocode", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val propertyList = ListSerializer(Property.serializer())

    var serviceKey: String
        get() = prefs.getString("service_key", null).orEmpty()
        set(value) {
            prefs.edit().putString("service_key", value).apply()
        }

    /** 주소 → 좌표 캐시. 같은 주소를 다시 지오코딩하지 않도록 기기에 남겨 둔다. */
    fun loadGeoCache(): Map<String, LatLng> = geoPrefs.all.mapNotNull { (key, value) ->
        val parts = (value as? String)?.split(',') ?: return@mapNotNull null
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
        val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
        key to LatLng(lat, lng)
    }.toMap()

    fun saveGeo(key: String, position: LatLng) {
        geoPrefs.edit().putString(key, "${position.lat},${position.lng}").apply()
    }

    fun loadSettings(): SearchSettings {
        val types = prefs.getStringSet("asset_types", null)
            ?.mapNotNull { code -> AssetType.entries.firstOrNull { it.code == code } }
            ?.toSet()
        val sources = prefs.getStringSet("sources", null)
            ?.mapNotNull { name -> Source.entries.firstOrNull { it.name == name } }
            ?.toSet()
        return SearchSettings(
            sources = sources?.takeIf { it.isNotEmpty() } ?: Source.entries.toSet(),
            courtWeeks = prefs.getInt("court_weeks", 4),
            sido = prefs.getString("sido", null) ?: "서울특별시",
            sigungu = prefs.getString("sigungu", null).orEmpty(),
            assetTypes = types?.takeIf { it.isNotEmpty() } ?: AssetType.entries.toSet(),
            includePrivateContract = prefs.getBoolean("private_contract", false),
        )
    }

    fun saveSettings(settings: SearchSettings) {
        prefs.edit()
            .putString("sido", settings.sido)
            .putString("sigungu", settings.sigungu)
            .putStringSet("asset_types", settings.assetTypes.map { it.code }.toSet())
            .putBoolean("private_contract", settings.includePrivateContract)
            .putStringSet("sources", settings.sources.map { it.name }.toSet())
            .putInt("court_weeks", settings.courtWeeks)
            .apply()
    }

    fun loadFilter(): PropertyFilter = decode("filter", PropertyFilter.serializer()) ?: PropertyFilter()

    fun saveFilter(filter: PropertyFilter) = encode("filter", PropertyFilter.serializer(), filter)

    fun loadSort(): SortOrder =
        prefs.getString("sort", null)?.let { name -> SortOrder.entries.firstOrNull { it.name == name } } ?: SortOrder.SCORE_DESC

    fun saveSort(sort: SortOrder) {
        prefs.edit().putString("sort", sort.name).apply()
    }

    fun loadFavorites(): List<Property> = decode("favorites", propertyList).orEmpty()

    fun saveFavorites(list: List<Property>) = encode("favorites", propertyList, list)

    private fun <T> decode(key: String, serializer: KSerializer<T>): T? {
        val text = prefs.getString(key, null) ?: return null
        return runCatching { json.decodeFromString(serializer, text) }.getOrNull()
    }

    private fun <T> encode(key: String, serializer: KSerializer<T>, value: T) {
        prefs.edit().putString(key, json.encodeToString(serializer, value)).apply()
    }
}
