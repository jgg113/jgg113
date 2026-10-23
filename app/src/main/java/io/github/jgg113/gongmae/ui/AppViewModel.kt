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
import io.github.jgg113.gongmae.core.AiException
import io.github.jgg113.gongmae.core.AiProvider
import io.github.jgg113.gongmae.core.Analysis
import io.github.jgg113.gongmae.core.ClaudePrompt
import io.github.jgg113.gongmae.core.GeminiClient
import io.github.jgg113.gongmae.core.Analyzer
import io.github.jgg113.gongmae.core.AssetType
import io.github.jgg113.gongmae.core.Category
import io.github.jgg113.gongmae.core.Comparison
import io.github.jgg113.gongmae.core.CourtClient
import io.github.jgg113.gongmae.core.CourtCase
import io.github.jgg113.gongmae.core.CourtDetail
import io.github.jgg113.gongmae.core.CourtSearchRequest
import io.github.jgg113.gongmae.core.LatLng
import io.github.jgg113.gongmae.core.MapPayload
import io.github.jgg113.gongmae.core.MarketKind
import io.github.jgg113.gongmae.core.MarketSummary
import io.github.jgg113.gongmae.core.MolitClient
import io.github.jgg113.gongmae.core.MovableKind
import io.github.jgg113.gongmae.core.MovableSearchRequest
import io.github.jgg113.gongmae.core.MovablesClient
import io.github.jgg113.gongmae.core.NearbyCategory
import io.github.jgg113.gongmae.core.NearbyPlace
import io.github.jgg113.gongmae.core.NominatimClient
import io.github.jgg113.gongmae.core.OnbidApiException
import io.github.jgg113.gongmae.core.OnbidClient
import io.github.jgg113.gongmae.core.OverpassClient
import io.github.jgg113.gongmae.core.ProgressListener
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.PropertyDetail
import io.github.jgg113.gongmae.core.PropertyFilter
import io.github.jgg113.gongmae.core.QueryParser
import io.github.jgg113.gongmae.core.Regions
import io.github.jgg113.gongmae.core.SearchRequest
import io.github.jgg113.gongmae.core.ServiceKey
import io.github.jgg113.gongmae.core.SortOrder
import io.github.jgg113.gongmae.core.Source
import io.github.jgg113.gongmae.core.Trade
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
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

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

sealed interface AiState {
    data object Loading : AiState
    data class Error(val message: String) : AiState
    /** [source] 는 "앱 내장 분석", "Gemini (gemini-2.5-flash)" 처럼 답을 만든 곳 */
    data class Done(val text: String, val source: String) : AiState
}

sealed interface MovableState {
    data object Idle : MovableState
    data class Loading(val label: String) : MovableState
    data class Error(val message: String) : MovableState
    data class Done(val fetchedAt: LocalDateTime, val kinds: Set<MovableKind>) : MovableState
}

sealed interface MarketState {
    data object Loading : MarketState
    /** [needsApiApproval] 이면 실거래가 API 활용신청이 안 된 것이다. */
    data class Error(val message: String, val needsApiApproval: Boolean = false) : MarketState
    data class Loaded(val summary: MarketSummary) : MarketState
}

sealed interface KeyTestState {
    data object Idle : KeyTestState
    data object Running : KeyTestState
    data class Ok(val totalCount: Int?) : KeyTestState
    data class Failed(val message: String) : KeyTestState
}

private const val MARKET_MONTHS = 6
private val YEAR_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AppStore(app)
    private val geocoder = DeviceGeocoder(app)
    private val nominatim = NominatimClient()
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
    var markets by mutableStateOf<Map<String, MarketState>>(emptyMap())
        private set
    /** 법원 사건 정보 (이해관계인 등). 실패하면 넣지 않는다. */
    var courtCases by mutableStateOf<Map<String, CourtCase>>(emptyMap())
        private set
    /** 차량·동산 공매 (전국) — 부동산 검색 결과와 따로 둔다 */
    var movables by mutableStateOf<List<Property>>(emptyList())
        private set
    var movableState by mutableStateOf<MovableState>(MovableState.Idle)
        private set
    /** 통합 검색이 한 일 ("논현동 → 서울 강남구를 불러옵니다") */
    var queryNote by mutableStateOf<String?>(null)
        private set
    var keyTest by mutableStateOf<KeyTestState>(KeyTestState.Idle)
        private set
    var aiProvider by mutableStateOf(store.aiProvider)
        private set
    var geminiKey by mutableStateOf(store.geminiKey)
        private set
    var geminiTest by mutableStateOf<AiState?>(null)
        private set
    /** 물건·요약/상세별 AI 답 */
    var aiResults by mutableStateOf<Map<String, AiState>>(emptyMap())
        private set

    private val geoCache = HashMap(store.loadGeoCache())
    // 실거래가는 시군구·월 단위라 같은 동네 물건끼리 다시 쓴다.
    private val tradeCache = ConcurrentHashMap<String, List<Trade>>()

    /** 좌표가 새로 생길 때마다 올라간다 (지도 다시 그리기 신호). */
    var geoVersion by mutableIntStateOf(0)
        private set
    var geocodeProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    val favorites = mutableStateListOf<Property>().apply { addAll(store.loadFavorites()) }
    val compareItems = mutableStateListOf<Property>()

    /** 검색 결과·관심·비교 물건의 자동 분석. 권리 상세를 불러온 물건은 그것까지 반영한다. */
    /** 부동산(공매·경매) + 차량·동산 */
    val allResults: List<Property> by derivedStateOf { (results + movables).distinctBy { it.id } }

    val analyses: Map<String, Analysis> by derivedStateOf {
        val all = (allResults + favorites + compareItems).distinctBy { it.id }
        val rights = details.mapNotNull { (id, state) -> (state as? DetailState.Loaded)?.let { id to it.detail } }.toMap()
        val market = markets.mapNotNull { (id, state) -> (state as? MarketState.Loaded)?.let { id to it.summary } }.toMap()
        Analyzer.analyzeAll(all, rights, market, courtCases)
    }

    fun rightsOf(id: String): PropertyDetail? = (details[id] as? DetailState.Loaded)?.detail

    fun marketOf(id: String): MarketSummary? = (markets[id] as? MarketState.Loaded)?.summary

    fun courtDetailOf(id: String): CourtDetail? = (courtDetails[id] as? CourtDetailState.Loaded)?.detail

    val visibleResults: List<Property> by derivedStateOf {
        val scored = analyses
        sort.sort(filter.apply(allResults, now, scored), now, scored)
    }

    /** 상세 검색 화면에서 '몇 건 보기'를 미리 보여 줄 때 */
    fun countMatching(draft: PropertyFilter): Int = draft.apply(allResults, now, analyses).size

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

    /**
     * 통합 검색. 검색어를 지역·종류·가격 등으로 해석해 필터로 걸고,
     * 지금 불러온 지역과 다르면 그 지역을 새로 검색하고, 차량·동산이면 전국 차량·동산 공매를 불러온다.
     */
    fun runQuery(query: String) {
        val intent = QueryParser.parse(query)
        updateFilter(filter.copy(keyword = query.trim()))
        queryNote = null
        if (intent.isEmpty) return
        if (intent.wantsMovables) {
            val kinds = buildSet {
                if (intent.categories.any { it == Category.VEHICLE }) add(MovableKind.VEHICLE)
                if (intent.categories.any { it == Category.MOVABLE }) add(MovableKind.GOODS)
            }
            loadMovables(kinds)
        }
        val regionChanged = intent.sido.isNotEmpty() &&
            (intent.sido != settings.sido || intent.sigungu.isNotEmpty() && intent.sigungu != settings.sigungu || intent.sigungu.isEmpty() && settings.sigungu.isNotEmpty())
        when {
            // "서울 논현동"처럼 시군구 없이 동이 있으면 동으로 시군구를 찾는다 (시도 전체 검색은 너무 넓다).
            intent.dong.isNotEmpty() && intent.sigungu.isEmpty() && !intent.wantsMovables ->
                resolveDong(listOf(Regions.shortName(intent.sido), intent.dong).filter { it.isNotBlank() }.joinToString(" "))
            regionChanged -> {
                updateSettings(settings.copy(sido = intent.sido, sigungu = intent.sigungu))
                queryNote = "${Regions.shortName(intent.sido)} ${intent.sigungu.ifBlank { "전체" }} 물건을 불러옵니다."
                search()
            }
            results.isEmpty() && !intent.wantsMovables && searchState !is SearchState.Loading -> search()
        }
    }

    /** "논현동"처럼 동 이름만 있으면 어느 시군구인지 찾아 그 지역을 검색한다. */
    private fun resolveDong(dong: String) {
        queryNote = "$dong 위치를 찾는 중…"
        viewModelScope.launch {
            val region = withContext(Dispatchers.IO) {
                val position = geocoder.locate(dong) ?: nominatim.search("$dong 대한민국")
                position?.let { geocoder.addressOf(it) ?: nominatim.reverse(it) }?.let { Regions.fromAddress(it) }
            }
            if (region == null) {
                queryNote = "'$dong'이 어느 지역인지 찾지 못했습니다. '서울 $dong'처럼 시·구를 함께 써 보세요."
                return@launch
            }
            if (region.first == settings.sido && (region.second.isEmpty() || region.second == settings.sigungu) && results.isNotEmpty()) {
                queryNote = null
                return@launch
            }
            updateSettings(settings.copy(sido = region.first, sigungu = region.second))
            queryNote = "$dong → ${Regions.shortName(region.first)} ${region.second.ifBlank { "전체" }} 물건을 불러옵니다. (다른 지역의 같은 이름이면 '시·구 + 동'으로 검색하세요)"
            search()
        }
    }

    /** 차량·동산 공매 (전국). 30분 안에 같은 종류를 이미 받았으면 다시 부르지 않는다. */
    fun loadMovables(kinds: Set<MovableKind> = MovableKind.entries.toSet(), force: Boolean = false) {
        val wanted = kinds.ifEmpty { MovableKind.entries.toSet() }
        val current = movableState
        if (current is MovableState.Loading) return
        if (!force && current is MovableState.Done && current.kinds.containsAll(wanted) &&
            Duration.between(current.fetchedAt, LocalDateTime.now()).toMinutes() < 30
        ) return
        val key = serviceKey
        if (key.isBlank()) {
            movableState = MovableState.Error("차량·동산 공매는 설정 탭에 공공데이터포털 인증키가 있어야 합니다.")
            return
        }
        movableState = MovableState.Loading("차량·동산 공고 확인 중")
        viewModelScope.launch {
            movableState = try {
                val outcome = withContext(Dispatchers.IO) {
                    MovablesClient(OnbidClient(key)).search(MovableSearchRequest(kinds = wanted), LocalDateTime.now()) { done, total, label ->
                        viewModelScope.launch { if (movableState is MovableState.Loading) movableState = MovableState.Loading("$label ($done/$total)") }
                    }
                }
                movables = (movables.filterNot { it.movableKind in wanted } + outcome.properties).distinctBy { it.id }
                if (outcome.warnings.isNotEmpty()) warnings = (warnings + outcome.warnings.take(3)).distinct()
                MovableState.Done(LocalDateTime.now(), wanted + ((current as? MovableState.Done)?.kinds ?: emptySet()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: OnbidApiException) {
                val approval = e.code in setOf("12", "20", "30", "HTTP401", "HTTP403")
                MovableState.Error(
                    if (approval) {
                        "차량·동산은 공공데이터포털의 '차세대 온비드 공고목록 조회서비스'와 '공고상세 물건정보 조회서비스' 활용신청이 필요합니다. (${e.message})"
                    } else {
                        "차량·동산 공매를 불러오지 못했습니다: ${e.message}"
                    },
                )
            } catch (e: Exception) {
                MovableState.Error("차량·동산 공매를 불러오지 못했습니다: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun clearQueryNote() {
        queryNote = null
    }

    /** 목록에서 받은 물건을 우선한다. 상세 API 결과는 목록에 없을 때만 쓴다. */
    fun findProperty(id: String): Property? =
        allResults.firstOrNull { it.id == id }
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

    /**
     * 법원경매 물건상세(사진·기일). 목록 카드가 화면에 보일 때 그 화면의 코루틴에서 부르므로,
     * 카드가 화면 밖으로 나가면 기다리던 요청은 취소된다 (사이트 차단을 피하려고 한 번에 하나씩, 2~3초 간격).
     */
    suspend fun loadCourtDetail(p: Property, retry: Boolean = false) {
        if (!p.isCourt) return
        val current = courtDetails[p.id]
        if (current is CourtDetailState.Loading || current is CourtDetailState.Loaded) return
        if (current is CourtDetailState.Error && !retry) return
        courtDetails = courtDetails + (p.id to CourtDetailState.Loading)
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

    /** 법원 사건 이해관계인(임차인·유치권자 등). 상세 화면에서 한 번만 부르고, 실패해도 조용히 넘어간다. */
    suspend fun loadCourtCase(p: Property) {
        if (!p.isCourt || p.caseNo.isBlank() || p.courtCode.isBlank() || p.id in courtCases || p.id in caseTried) return
        caseTried += p.id
        val case = try {
            courtLock.withLock { withContext(Dispatchers.IO) { courtClient.fetchCase(p) } }
        } catch (e: CancellationException) {
            caseTried -= p.id
            throw e
        } catch (e: Exception) {
            null
        }
        if (case != null) courtCases = courtCases + (p.id to case)
    }

    private val caseTried = mutableSetOf<String>()

    fun courtCaseOf(id: String): CourtCase? = courtCases[id]

    fun courtPhotos(id: String): List<ByteArray> = (courtDetails[id] as? CourtDetailState.Loaded)?.detail?.photos.orEmpty()

    fun cachedPosition(key: String): LatLng? = geoCache[key]

    /** 원본 좌표(법원경매) → 지번 주소 지오코딩 결과 → 동 중심 근사(물건마다 조금 비킴) 순. */
    fun positionOf(p: Property): LatLng? =
        p.position
            ?: geoCache[MapPayload.geoKey(p)]
            ?: geoCache[districtKey(p)]?.let { NominatimClient.spread(it, p.id) }

    private fun districtKey(p: Property) = "동:" + listOf(p.sido, p.sigungu, p.dong).filter { it.isNotBlank() }.joinToString(" ")

    /**
     * 좌표가 없는 물건의 위치를 찾는다. 휴대폰 지오코더 → OpenStreetMap(Nominatim) 지번 → 동 중심 순으로 시도하고 기기에 남긴다.
     * 동 중심은 여러 물건이 같이 쓰므로 한 번만 찾는다.
     */
    fun ensurePositions(list: List<Property>) {
        val pending = list.filter { MapPayload.geoKey(it).isNotBlank() && positionOf(it) == null }
            .distinctBy { MapPayload.geoKey(it) }
        if (pending.isEmpty()) return
        geocodeJob?.cancel()
        geocodeJob = viewModelScope.launch {
            geocodeProgress = 0 to pending.size
            var deviceFailures = 0
            pending.forEachIndexed { index, p ->
                withContext(Dispatchers.IO) {
                    val key = MapPayload.geoKey(p)
                    // 휴대폰 지오코더가 연달아 실패하면 더 부르지 않는다.
                    val exact = if (deviceFailures < 3) geocoder.locate(p.geocodeQueries.first()) else null
                    if (exact == null) deviceFailures++ else deviceFailures = 0
                    val found = exact ?: if (p.address.isNotBlank()) nominatim.search(p.address) else null
                    if (found != null) {
                        geoCache[key] = found
                        store.saveGeo(key, found)
                    } else {
                        val district = districtKey(p)
                        if (geoCache[district] == null) {
                            val query = district.removePrefix("동:")
                            val center = geocoder.locate(query) ?: nominatim.search(query)
                            if (center != null) {
                                geoCache[district] = center
                                store.saveGeo(district, center)
                            }
                        }
                    }
                }
                geocodeProgress = (index + 1) to pending.size
                if (index % 5 == 4 || index == pending.lastIndex) geoVersion++
            }
            geocodeProgress = null
        }
    }

    /** 내 위치의 시도·시군구를 찾아 그 지역을 검색한다. 찾은 지역 이름(실패하면 null)을 돌려준다. */
    fun searchAt(position: LatLng, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val region = withContext(Dispatchers.IO) {
                geocoder.addressOf(position)?.let { Regions.fromAddress(it) }
                    ?: nominatim.reverse(position)?.let { Regions.fromAddress(it) }
            }
            if (region == null) {
                onResult(null)
                return@launch
            }
            updateSettings(settings.copy(sido = region.first, sigungu = region.second))
            search()
            onResult("${Regions.shortName(region.first)} ${region.second.ifBlank { "전체" }}")
        }
    }

    /**
     * 주변 실거래가 (국토교통부 API, 최근 6개월). 같은 공공데이터포털 인증키를 쓰지만 API 마다 활용신청이 따로 필요하다.
     * 같은 시군구·같은 종류는 한 번 받은 거래를 다시 쓴다.
     */
    fun loadMarket(p: Property, retry: Boolean = false) {
        val current = markets[p.id]
        if (current is MarketState.Loading || current is MarketState.Loaded) return
        if (current is MarketState.Error && !retry) return
        val kind = MarketKind.of(p.category)
        val key = serviceKey
        val problem = when {
            kind == null -> "이 용도(${p.category.label})는 실거래가 자료가 없습니다."
            p.lawdCode.length != 5 -> "지역 코드를 알 수 없어 실거래가를 찾지 못했습니다."
            key.isBlank() -> "설정 탭에 공공데이터포털 인증키를 넣으면 주변 실거래가를 볼 수 있습니다."
            else -> null
        }
        if (problem != null || kind == null) {
            markets = markets + (p.id to MarketState.Error(problem.orEmpty()))
            return
        }
        markets = markets + (p.id to MarketState.Loading)
        viewModelScope.launch {
            val state = try {
                val now = LocalDate.now()
                val trades = withContext(Dispatchers.IO) {
                    (0 until MARKET_MONTHS).flatMap { back ->
                        val ym = now.minusMonths(back.toLong()).format(YEAR_MONTH)
                        val cacheKey = "${kind.name}|${p.lawdCode}|$ym"
                        tradeCache[cacheKey] ?: MolitClient(key).trades(kind, p.lawdCode, ym).also { tradeCache[cacheKey] = it }
                    }
                }
                MarketState.Loaded(MolitClient.summarize(p, kind, trades, MARKET_MONTHS))
            } catch (e: CancellationException) {
                markets = markets - p.id
                throw e
            } catch (e: OnbidApiException) {
                val approval = e.code in setOf("12", "20", "30", "HTTP401", "HTTP403")
                MarketState.Error(
                    if (approval) "공공데이터포털에서 '${kind.apiName}' 활용신청이 필요합니다. (승인 후 1~2시간 뒤 반영)" else e.message.orEmpty(),
                    needsApiApproval = approval,
                )
            } catch (e: Exception) {
                MarketState.Error("실거래가 조회 중 오류: ${e.message ?: e.javaClass.simpleName}")
            }
            markets = markets + (p.id to state)
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

    fun aiKey(id: String, mode: ClaudePrompt.Mode) = "$id|${mode.name}"

    fun updateAiProvider(provider: AiProvider) {
        aiProvider = provider
        store.aiProvider = provider
    }

    fun saveGeminiKey(raw: String) {
        val key = raw.trim()
        geminiKey = key
        store.geminiKey = key
        geminiTest = null
    }

    fun testGemini() {
        val key = geminiKey
        geminiTest = AiState.Loading
        viewModelScope.launch {
            geminiTest = try {
                val answer = withContext(Dispatchers.IO) { GeminiClient(key).generate("한 문장으로 답해 줘: 경매에서 말소기준권리란?") }
                AiState.Done(answer.text, "Gemini (${answer.model})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                AiState.Error(e.message.orEmpty())
            } catch (e: Exception) {
                AiState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** 앱 내장 분석 글을 결과 칸에 보여 준다. */
    fun showLocalAi(id: String, mode: ClaudePrompt.Mode, text: String) {
        aiResults = aiResults + (aiKey(id, mode) to AiState.Done(text, "앱 내장 분석 (규칙 기반)"))
    }

    fun clearAi(id: String, mode: ClaudePrompt.Mode) {
        aiResults = aiResults - aiKey(id, mode)
    }

    /** 내 Gemini 무료 키로 실시간 분석 */
    fun runGemini(id: String, mode: ClaudePrompt.Mode, prompt: String) {
        val key = aiKey(id, mode)
        if (aiResults[key] is AiState.Loading) return
        aiResults = aiResults + (key to AiState.Loading)
        val apiKey = geminiKey
        viewModelScope.launch {
            val state = try {
                val answer = withContext(Dispatchers.IO) { GeminiClient(apiKey).generate(prompt) }
                AiState.Done(answer.text, "Gemini (${answer.model}) · 실시간")
            } catch (e: CancellationException) {
                aiResults = aiResults - key
                throw e
            } catch (e: AiException) {
                AiState.Error(e.message.orEmpty())
            } catch (e: Exception) {
                AiState.Error("AI 분석 중 오류: ${e.message ?: e.javaClass.simpleName}")
            }
            aiResults = aiResults + (key to state)
        }
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
