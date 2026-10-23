package io.github.jgg113.gongmae.ui

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jgg113.gongmae.core.AssetType
import io.github.jgg113.gongmae.core.Comparison
import io.github.jgg113.gongmae.core.LatLng
import io.github.jgg113.gongmae.core.OnbidApiException
import io.github.jgg113.gongmae.core.OnbidClient
import io.github.jgg113.gongmae.core.ProgressListener
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.PropertyDetail
import io.github.jgg113.gongmae.core.PropertyFilter
import io.github.jgg113.gongmae.core.Regions
import io.github.jgg113.gongmae.core.SearchRequest
import io.github.jgg113.gongmae.core.ServiceKey
import io.github.jgg113.gongmae.core.SortOrder
import io.github.jgg113.gongmae.data.AppStore
import io.github.jgg113.gongmae.data.SearchSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

sealed interface SearchState {
    data object Idle : SearchState
    data class Loading(val done: Int, val total: Int, val label: String) : SearchState
    data class Error(val message: String) : SearchState
    data class Done(val fetchedAt: LocalDateTime) : SearchState
}

sealed interface DetailState {
    data object Loading : DetailState
    data class Error(val message: String) : DetailState
    data class Loaded(val detail: PropertyDetail) : DetailState
}

sealed interface KeyTestState {
    data object Idle : KeyTestState
    data object Running : KeyTestState
    data class Ok(val totalCount: Int?) : KeyTestState
    data class Failed(val message: String) : KeyTestState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AppStore(app)
    private var searchJob: Job? = null

    var serviceKey by mutableStateOf(store.serviceKey)
        private set
    var settings by mutableStateOf(store.loadSettings())
        private set
    var filter by mutableStateOf(store.loadFilter())
        private set
    var sort by mutableStateOf(store.loadSort())
        private set
    var searchState by mutableStateOf<SearchState>(SearchState.Idle)
        private set
    var results by mutableStateOf<List<Property>>(emptyList())
        private set
    var warnings by mutableStateOf<List<String>>(emptyList())
        private set
    var regionFilteredLocally by mutableStateOf(false)
        private set
    var now by mutableStateOf(LocalDateTime.now())
        private set
    var details by mutableStateOf<Map<String, DetailState>>(emptyMap())
        private set
    var keyTest by mutableStateOf<KeyTestState>(KeyTestState.Idle)
        private set
    var kakaoKey by mutableStateOf(store.kakaoKey)
        private set

    private val geoCache = HashMap(store.loadGeoCache())

    val favorites = mutableStateListOf<Property>().apply { addAll(store.loadFavorites()) }
    val compareItems = mutableStateListOf<Property>()

    val visibleResults: List<Property> by derivedStateOf { sort.sort(filter.apply(results, now), now) }

    fun updateSettings(newSettings: SearchSettings) {
        settings = newSettings
        store.saveSettings(newSettings)
    }

    fun updateFilter(newFilter: PropertyFilter) {
        filter = newFilter
        store.saveFilter(newFilter)
    }

    fun updateSort(newSort: SortOrder) {
        sort = newSort
        store.saveSort(newSort)
    }

    fun search() {
        val key = serviceKey
        if (key.isBlank()) {
            searchState = SearchState.Error("설정 탭에서 공공데이터포털 인증키를 먼저 입력하세요.")
            return
        }
        val s = settings
        if (s.assetTypes.isEmpty()) {
            searchState = SearchState.Error("재산유형을 하나 이상 선택하세요.")
            return
        }
        searchJob?.cancel()
        searchState = SearchState.Loading(0, 1, "준비 중")
        searchJob = viewModelScope.launch {
            try {
                val request = SearchRequest(
                    sido = s.sido,
                    sigungu = s.sigungu,
                    assetTypes = AssetType.entries.filter { it in s.assetTypes },
                    includePrivateContract = s.includePrivateContract,
                )
                val outcome = withContext(Dispatchers.IO) {
                    val progress = ProgressListener { done, total, label ->
                        viewModelScope.launch {
                            if (searchState is SearchState.Loading) searchState = SearchState.Loading(done, total, label)
                        }
                    }
                    OnbidClient(key).search(request, LocalDateTime.now(), progress)
                }
                now = LocalDateTime.now()
                results = outcome.properties
                warnings = outcome.warnings
                regionFilteredLocally = outcome.regionFilteredLocally
                searchState = SearchState.Done(now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnbidApiException) {
                searchState = SearchState.Error(e.message ?: "조회 중 오류가 났습니다.")
            } catch (e: Exception) {
                searchState = SearchState.Error("조회 중 오류가 났습니다: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 목록에서 받은 물건을 우선한다. 상세 API 결과는 목록에 없을 때만 쓴다. */
    fun findProperty(id: String): Property? =
        results.firstOrNull { it.id == id }
            ?: favorites.firstOrNull { it.id == id }
            ?: compareItems.firstOrNull { it.id == id }
            ?: (details[id] as? DetailState.Loaded)?.detail?.property

    fun isFavorite(id: String): Boolean = favorites.any { it.id == id }

    fun toggleFavorite(property: Property) {
        val index = favorites.indexOfFirst { it.id == property.id }
        if (index >= 0) favorites.removeAt(index) else favorites.add(0, property)
        store.saveFavorites(favorites.toList())
    }

    fun isComparing(id: String): Boolean = compareItems.any { it.id == id }

    /** 비교함에 넣거나 뺀다. 가득 차서 넣지 못하면 false. */
    fun toggleCompare(property: Property): Boolean {
        val index = compareItems.indexOfFirst { it.id == property.id }
        return when {
            index >= 0 -> {
                compareItems.removeAt(index)
                true
            }
            compareItems.size >= Comparison.MAX_ITEMS -> false
            else -> {
                compareItems.add(property)
                true
            }
        }
    }

    fun clearCompare() = compareItems.clear()

    fun loadDetail(id: String) {
        val key = serviceKey
        if (key.isBlank()) {
            details = details + (id to DetailState.Error("설정 탭에서 인증키를 먼저 입력하세요."))
            return
        }
        details = details + (id to DetailState.Loading)
        viewModelScope.launch {
            val state = try {
                DetailState.Loaded(withContext(Dispatchers.IO) { OnbidClient(key).fetchDetail(id) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnbidApiException) {
                DetailState.Error(detailErrorMessage(e))
            } catch (e: Exception) {
                DetailState.Error("상세 조회 중 오류가 났습니다: ${e.message ?: e.javaClass.simpleName}")
            }
            details = details + (id to state)
        }
    }

    private fun detailErrorMessage(e: OnbidApiException): String {
        val hint = "\n\n상세 정보는 '차세대 온비드 부동산 물건상세 조회서비스'를 따로 활용신청해야 합니다."
        return (e.message ?: "상세 조회 중 오류가 났습니다.") + if (e.code in setOf("12", "20", "30", "HTTP401", "HTTP403")) hint else ""
    }

    fun saveKakaoKey(raw: String) {
        val key = raw.filterNot { it.isWhitespace() }
        kakaoKey = key
        store.kakaoKey = key
    }

    fun cachedPosition(key: String): LatLng? = geoCache[key]

    fun rememberPosition(key: String, position: LatLng) {
        if (key.isBlank() || geoCache[key] == position) return
        geoCache[key] = position
        store.saveGeo(key, position)
    }

    /**
     * 지도에서 알아낸 내 위치의 시도·시군구로 검색한다. 카카오는 "수원시 장안구" 처럼 구까지 주므로 시 이름만 쓴다.
     * 반환값은 화면에 보여 줄 지역 이름.
     */
    fun searchAround(kakaoSido: String, kakaoSigungu: String): String {
        val sido = Regions.resolveSido(kakaoSido)
        val city = kakaoSigungu.trim().split(' ').firstOrNull().orEmpty()
        val sigungu = city.takeIf { it in Regions.sigunguOf(sido) }.orEmpty()
        updateSettings(settings.copy(sido = sido, sigungu = sigungu))
        search()
        return "${Regions.shortName(sido)} ${sigungu.ifBlank { "전체" }}"
    }

    fun saveServiceKey(raw: String) {
        val normalized = ServiceKey.normalize(raw)
        serviceKey = normalized
        store.serviceKey = normalized
        keyTest = KeyTestState.Idle
    }

    fun testServiceKey() {
        val key = serviceKey
        if (key.isBlank()) {
            keyTest = KeyTestState.Failed("인증키를 입력하고 저장하세요.")
            return
        }
        keyTest = KeyTestState.Running
        viewModelScope.launch {
            keyTest = try {
                KeyTestState.Ok(withContext(Dispatchers.IO) { OnbidClient(key).testConnection() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KeyTestState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}
