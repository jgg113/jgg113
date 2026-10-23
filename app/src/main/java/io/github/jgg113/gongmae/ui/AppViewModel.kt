package io.github.jgg113.gongmae.ui

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jgg113.gongmae.core.Analysis
import io.github.jgg113.gongmae.core.Analyzer
import io.github.jgg113.gongmae.core.AssetType
import io.github.jgg113.gongmae.core.Comparison
import io.github.jgg113.gongmae.core.CourtClient
import io.github.jgg113.gongmae.core.CourtDetail
import io.github.jgg113.gongmae.core.CourtSearchRequest
import io.github.jgg113.gongmae.core.LatLng
import io.github.jgg113.gongmae.core.MapPayload
import io.github.jgg113.gongmae.core.NearbyCategory
import io.github.jgg113.gongmae.core.NearbyPlace
import io.github.jgg113.gongmae.core.OnbidApiException
import io.github.jgg113.gongmae.core.OnbidClient
import io.github.jgg113.gongmae.core.OverpassClient
import io.github.jgg113.gongmae.core.ProgressListener
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.PropertyDetail
import io.github.jgg113.gongmae.core.PropertyFilter
import io.github.jgg113.gongmae.core.Regions
import io.github.jgg113.gongmae.core.SearchRequest
import io.github.jgg113.gongmae.core.ServiceKey
import io.github.jgg113.gongmae.core.SortOrder
import io.github.jgg113.gongmae.core.Source
import io.github.jgg113.gongmae.data.AppStore
import io.github.jgg113.gongmae.data.DeviceGeocoder
import io.github.jgg113.gongmae.data.SearchSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

sealed interface CourtDetailState {
    data object Loading : CourtDetailState
    data class Error(val message: String) : CourtDetailState
    data class Loaded(val detail: CourtDetail) : CourtDetailState
}

sealed interface NearbyState {
    data object Loading : NearbyState
    data class Error(val message: String) : NearbyState
    data class Loaded(val places: List<NearbyPlace>) : NearbyState
}

sealed interface KeyTestState {
    data object Idle : KeyTestState
    data object Running : KeyTestState
    data class Ok(val totalCount: Int?) : KeyTestState
    data class Failed(val message: String) : KeyTestState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AppStore(app)
    private val geocoder = DeviceGeocoder(app)
    private val courtClient = CourtClient()
    // 법원경매정보는 세션 쿠키·요청 간격을 지켜야 해서 한 번에 한 요청만 보낸다.
    private val courtLock = Mutex()
    private var searchJob: Job? = null
    private var geocodeJob: Job? = null

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
    var courtDetails by mutableStateOf<Map<String, CourtDetailState>>(emptyMap())
        private set
    var nearby by mutableStateOf<Map<String, NearbyState>>(emptyMap())
        private set
    var keyTest by mutableStateOf<KeyTestState>(KeyTestState.Idle)
        private set

    private val geoCache = HashMap(store.loadGeoCache())

    /** 좌표가 새로 생길 때마다 올라간다 (지도 다시 그리기 신호). */
    var geoVersion by mutableIntStateOf(0)
        private set
    var geocodeProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    val favorites = mutableStateListOf<Property>().apply { addAll(store.loadFavorites()) }
    val compareItems = mutableStateListOf<Property>()

    /** 검색 결과·관심·비교 물건의 자동 분석. 권리 상세를 불러온 물건은 그것까지 반영한다. */
    val analyses: Map<String, Analysis> by derivedStateOf {
        val all = (results + favorites + compareItems).distinctBy { it.id }
        val rights = details.mapNotNull { (id, state) -> (state as? DetailState.Loaded)?.let { id to it.detail } }.toMap()
        Analyzer.analyzeAll(all, rights)
    }

    val visibleResults: List<Property> by derivedStateOf {
        val scored = analyses
        sort.sort(filter.apply(results, now, scored), now, scored)
    }

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
        val s = settings
        val useOnbid = Source.ONBID in s.sources
        val useCourt = Source.COURT in s.sources
        if (!useOnbid && !useCourt) {
            searchState = SearchState.Error("공매·경매 중 하나 이상 선택하세요.")
            return
        }
        val key = serviceKey
        searchJob?.cancel()
        searchState = SearchState.Loading(0, 1, "준비 중")
        searchJob = viewModelScope.launch {
            val found = mutableListOf<Property>()
            val problems = mutableListOf<String>()
            val notes = mutableListOf<String>()
            var localFilter = false
            fun progress(prefix: String) = ProgressListener { done, total, label ->
                viewModelScope.launch {
                    if (searchState is SearchState.Loading) searchState = SearchState.Loading(done, total, "$prefix · $label")
                }
            }

            if (useOnbid) {
                if (key.isBlank()) {
                    problems += "공매: 설정 탭에 공공데이터포털 인증키를 넣으면 공매 물건도 함께 볼 수 있습니다."
                } else if (s.assetTypes.isEmpty()) {
                    problems += "공매: 재산유형을 하나 이상 선택하세요."
                } else {
                    try {
                        val request = SearchRequest(
                            sido = s.sido,
                            sigungu = s.sigungu,
                            assetTypes = AssetType.entries.filter { it in s.assetTypes },
                            includePrivateContract = s.includePrivateContract,
                        )
                        val outcome = withContext(Dispatchers.IO) { OnbidClient(key).search(request, LocalDateTime.now(), progress("공매")) }
                        found += outcome.properties
                        notes += outcome.warnings.map { "공매 $it" }
                        localFilter = outcome.regionFilteredLocally
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        problems += "공매: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            }

            if (useCourt) {
                try {
                    val request = CourtSearchRequest(s.sido, s.sigungu, s.courtWeeks)
                    val outcome = courtLock.withLock {
                        withContext(Dispatchers.IO) { courtClient.search(request, LocalDateTime.now(), progress("경매")) }
                    }
                    found += outcome.properties
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problems += "경매: " + (e.message ?: e.javaClass.simpleName)
                }
            }

            now = LocalDateTime.now()
            if (found.isEmpty() && problems.isNotEmpty()) {
                searchState = SearchState.Error(problems.joinToString("\n"))
            } else {
                results = found.distinctBy { it.id }
                warnings = problems + notes
                regionFilteredLocally = localFilter
                searchState = SearchState.Done(now)
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

    /** 공매 물건의 권리·임대차 상세 (온비드 부동산 물건상세 API) */
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

    /** 법원경매 물건상세(사진·기일). 목록 카드가 화면에 보일 때도 불러 사진을 채운다. */
    fun loadCourtDetail(p: Property, retry: Boolean = false) {
        if (!p.isCourt) return
        val current = courtDetails[p.id]
        if (current is CourtDetailState.Loading || current is CourtDetailState.Loaded) return
        if (current is CourtDetailState.Error && !retry) return
        courtDetails = courtDetails + (p.id to CourtDetailState.Loading)
        viewModelScope.launch {
            val state = try {
                CourtDetailState.Loaded(courtLock.withLock { withContext(Dispatchers.IO) { courtClient.fetchDetail(p) } })
            } catch (e: CancellationException) {
                courtDetails = courtDetails - p.id
                throw e
            } catch (e: Exception) {
                CourtDetailState.Error(e.message ?: e.javaClass.simpleName)
            }
            courtDetails = courtDetails + (p.id to state)
        }
    }

    fun courtPhotos(id: String): List<ByteArray> = (courtDetails[id] as? CourtDetailState.Loaded)?.detail?.photos.orEmpty()

    fun cachedPosition(key: String): LatLng? = geoCache[key]

    fun positionOf(p: Property): LatLng? = geoCache[MapPayload.geoKey(p)]

    /** 좌표가 없는 물건을 휴대폰 지오코더로 차례로 찾는다. 찾은 좌표는 기기에 남긴다. */
    fun ensurePositions(list: List<Property>) {
        val pending = list.filter { MapPayload.geoKey(it).isNotBlank() && positionOf(it) == null }
            .distinctBy { MapPayload.geoKey(it) }
        if (pending.isEmpty() || !geocoder.available) return
        geocodeJob?.cancel()
        geocodeJob = viewModelScope.launch {
            geocodeProgress = 0 to pending.size
            pending.forEachIndexed { index, p ->
                val found = withContext(Dispatchers.IO) { p.geocodeQueries.firstNotNullOfOrNull { geocoder.locate(it) } }
                if (found != null) {
                    val key = MapPayload.geoKey(p)
                    geoCache[key] = found
                    store.saveGeo(key, found)
                }
                geocodeProgress = (index + 1) to pending.size
                if (index % 10 == 9 || index == pending.lastIndex) geoVersion++
            }
            geocodeProgress = null
        }
    }

    /** 내 위치의 시도·시군구를 찾아 그 지역을 검색한다. 찾은 지역 이름(실패하면 null)을 돌려준다. */
    fun searchAt(position: LatLng, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val address = withContext(Dispatchers.IO) { geocoder.addressOf(position) }
            val region = address?.let { Regions.fromAddress(it) }
            if (region == null) {
                onResult(null)
                return@launch
            }
            updateSettings(settings.copy(sido = region.first, sigungu = region.second))
            search()
            onResult("${Regions.shortName(region.first)} ${region.second.ifBlank { "전체" }}")
        }
    }

    fun nearbyKey(p: Property, category: NearbyCategory) = "${p.id}|${category.name}"

    fun loadNearby(p: Property, center: LatLng, category: NearbyCategory) {
        val key = nearbyKey(p, category)
        if (nearby[key] is NearbyState.Loaded || nearby[key] is NearbyState.Loading) return
        nearby = nearby + (key to NearbyState.Loading)
        viewModelScope.launch {
            val state = try {
                NearbyState.Loaded(withContext(Dispatchers.IO) { OverpassClient().search(category, center) })
            } catch (e: CancellationException) {
                nearby = nearby - key
                throw e
            } catch (e: Exception) {
                NearbyState.Error(e.message ?: e.javaClass.simpleName)
            }
            nearby = nearby + (key to state)
        }
    }

    fun retryNearby(p: Property, center: LatLng, category: NearbyCategory) {
        nearby = nearby - nearbyKey(p, category)
        loadNearby(p, center, category)
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
