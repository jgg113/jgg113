package io.github.jgg113.gongmae.data

import android.content.Context
import io.github.jgg113.gongmae.core.AiProvider
import io.github.jgg113.gongmae.core.AssetType
import io.github.jgg113.gongmae.core.LatLng
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.PropertyFilter
import io.github.jgg113.gongmae.core.SortOrder
import io.github.jgg113.gongmae.core.Source
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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

    /** 'AI 실시간 분석' 버튼이 쓰는 곳 */
    var aiProvider: AiProvider
        get() = prefs.getString("ai_provider", null)?.let { name -> AiProvider.entries.firstOrNull { it.name == name } } ?: AiProvider.BUILT_IN
        set(value) {
            prefs.edit().putString("ai_provider", value.name).apply()
        }

    /** Google AI Studio 무료 API 키 (기기에만 저장) */
    var geminiKey: String
        get() = prefs.getString("gemini_key", null).orEmpty()
        set(value) {
            prefs.edit().putString("gemini_key", value).apply()
        }

    /** API 방식 AI 의 키 (Gemini 는 [geminiKey]) */
    fun aiKey(provider: AiProvider): String =
        if (provider == AiProvider.GEMINI_API) geminiKey else prefs.getString("ai_key_${provider.name}", null).orEmpty()

    fun saveAiKey(provider: AiProvider, key: String) {
        if (provider == AiProvider.GEMINI_API) geminiKey = key else prefs.edit().putString("ai_key_${provider.name}", key).apply()
    }

    /** 'AI 비교'에 함께 돌릴 API 들 */
    var compareProviders: Set<AiProvider>
        get() = prefs.getStringSet("ai_compare", null)?.mapNotNull { name -> AiProvider.entries.firstOrNull { it.name == name } }?.toSet()
            ?: setOf(AiProvider.BUILT_IN, AiProvider.GEMINI_API, AiProvider.GROQ_API)
        set(value) {
            prefs.edit().putStringSet("ai_compare", value.map { it.name }.toSet()).apply()
        }

    /** 브이월드 인증키 (지적도·브이월드 지도) */
    var vworldKey: String
        get() = prefs.getString("vworld_key", null).orEmpty()
        set(value) {
            prefs.edit().putString("vworld_key", value).apply()
        }

    /** 검색 결과의 실거래가·공시가격을 자동으로 불러올지 */
    var autoLookup: Boolean
        get() = prefs.getBoolean("auto_lookup", true)
        set(value) {
            prefs.edit().putBoolean("auto_lookup", value).apply()
        }

    /** 새 물건·관심물건 변동 알림 */
    var alertsEnabled: Boolean
        get() = prefs.getBoolean("alerts", false)
        set(value) {
            prefs.edit().putBoolean("alerts", value).apply()
        }

    /** 검색 화면 위쪽(검색창·종류·탭)을 접어 두었는지 */
    var headerCollapsed: Boolean
        get() = prefs.getBoolean("header_collapsed", false)
        set(value) {
            prefs.edit().putBoolean("header_collapsed", value).apply()
        }

    /** 최근 검색어 (최신 먼저, 10개) */
    var recentQueries: List<String>
        get() = decode("recent_queries", ListSerializer(String.serializer())).orEmpty()
        set(value) = encode("recent_queries", ListSerializer(String.serializer()), value.take(10))

    /** 물건별 임장 메모 */
    var memos: Map<String, String>
        get() = decode("memos", MapSerializer(String.serializer(), String.serializer())).orEmpty()
        set(value) = encode("memos", MapSerializer(String.serializer(), String.serializer()), value)

    /** 관심 물건 변동 기록 (물건 → 바뀐 점) */
    var favoriteChanges: Map<String, List<String>>
        get() = decode("favorite_changes", MapSerializer(String.serializer(), ListSerializer(String.serializer()))).orEmpty()
        set(value) = encode("favorite_changes", MapSerializer(String.serializer(), ListSerializer(String.serializer())), value)

    /** 알림용: 지난번에 본 물건 번호 */
    var seenIds: Set<String>
        get() = prefs.getStringSet("seen_ids", null).orEmpty()
        set(value) {
            prefs.edit().putStringSet("seen_ids", value.toList().takeLast(3000).toSet()).apply()
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
