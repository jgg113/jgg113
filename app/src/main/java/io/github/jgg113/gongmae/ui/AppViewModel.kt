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
import io.github.jgg113.gongmae.core.AiEngines
import io.github.jgg113.gongmae.core.AiException
import io.github.jgg113.gongmae.core.AiProvider
import io.github.jgg113.gongmae.core.Analysis
import io.github.jgg113.gongmae.core.Analyzer
import io.github.jgg113.gongmae.core.ApiCheck
import io.github.jgg113.gongmae.core.ApiDoctor
import io.github.jgg113.gongmae.core.AssetType
import io.github.jgg113.gongmae.core.BidHistoryRound
import io.github.jgg113.gongmae.core.BidResultInfo
import io.github.jgg113.gongmae.core.Category
import io.github.jgg113.gongmae.core.Changes
import io.github.jgg113.gongmae.core.ClaudePrompt
import io.github.jgg113.gongmae.core.Comparison
import io.github.jgg113.gongmae.core.CourtCase
import io.github.jgg113.gongmae.core.CourtClient
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
import io.github.jgg113.gongmae.core.NewsClient
import io.github.jgg113.gongmae.core.NewsItem
import io.github.jgg113.gongmae.core.NominatimClient
import io.github.jgg113.gongmae.core.OnbidApiException
import io.github.jgg113.gongmae.core.OnbidClient
import io.github.jgg113.gongmae.core.OnbidExtrasClient
import io.github.jgg113.gongmae.core.OverpassClient
import io.github.jgg113.gongmae.core.ProgressListener
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.PropertyDetail
import io.github.jgg113.gongmae.core.PropertyFilter
import io.github.jgg113.gongmae.core.PublicFacts
import io.github.jgg113.gongmae.core.PublicRecordsClient
import io.github.jgg113.gongmae.core.QueryParser
import io.github.jgg113.gongmae.core.RankDivision
import io.github.jgg113.gongmae.core.RankKind
import io.github.jgg113.gongmae.core.Regions
import io.github.jgg113.gongmae.core.Rent
import io.github.jgg113.gongmae.core.RentKind
import io.github.jgg113.gongmae.core.RentSummary
import io.github.jgg113.gongmae.core.SearchRequest
import io.github.jgg113.gongmae.core.ServiceKey
import io.github.jgg113.gongmae.core.SortOrder
import io.github.jgg113.gongmae.core.Source
import io.github.jgg113.gongmae.core.Trade
import io.github.jgg113.gongmae.core.UsageStat
import io.github.jgg113.gongmae.data.AlertWorker
import io.github.jgg113.gongmae.data.AppStore
import io.github.jgg113.gongmae.data.DeviceGeocoder
import io.github.jgg113.gongmae.data.SearchSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** 한 번 불러오는 자료 (순위·통계·뉴스·입찰 이력 …) */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Error(val message: String, val needsApproval: Boolean = false) : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
}

private const val MARKET_MONTHS = 6
private val YEAR_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")
private val APPROVAL_CODES = setOf("12", "20", "30", "HTTP401", "HTTP403")

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AppStore(app)
    private val geocoder = DeviceGeocoder(app)
    private val nominatim = NominatimClient()
    private val courtClient = CourtClient()
    // 법원경매정보는 세션 쿠키·요청 간격을 지켜야 해서 한 번에 한 요청만 보낸다.
    private val courtLock = Mutex()
    private var searchJob: Job? = null
    private var geocodeJob: Job? = null
    private var lookupJob: Job? = null

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
    /** 주변 전월세 요약 (실거래가와 함께 자동으로 불러온다) */
    var rents by mutableStateOf<Map<String, RentSummary>>(emptyMap())
        private set
    /** 공시지가·개별주택가격·건축물대장 (무료 자동 조회) */
    var facts by mutableStateOf<Map<String, Load<PublicFacts>>>(emptyMap())
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
    /** 물건·요약/상세별 'AI 비교' 답 (AI 마다) */
    var compareResults by mutableStateOf<Map<String, Map<AiProvider, AiState>>>(emptyMap())
        private set
    var aiKeys by mutableStateOf(AiProvider.apis.associateWith { store.aiKey(it) })
        private set
    var compareProviders by mutableStateOf(store.compareProviders)
        private set
    var vworldKey by mutableStateOf(store.vworldKey)
        private set
    var autoLookup by mutableStateOf(store.autoLookup)
        private set
    var alertsEnabled by mutableStateOf(store.alertsEnabled)
        private set
    var headerCollapsed by mutableStateOf(store.headerCollapsed)
        private set
    var recentQueries by mutableStateOf(store.recentQueries)
        private set
    var memos by mutableStateOf(store.memos)
        private set
    /** 관심 물건 변동 (물건 번호 → 바뀐 점) */
    var favoriteChanges by mutableStateOf(store.favoriteChanges)
        private set
    /** 온비드 순위 ("VIEWS|REAL_ESTATE" → 물건들) */
    var rankings by mutableStateOf<Map<String, Load<List<Property>>>>(emptyMap())
        private set
    /** 온비드 용도별 낙찰 통계 (통계 종류 → 용도별) */
    var usageStats by mutableStateOf<Map<String, Load<List<UsageStat>>>>(emptyMap())
        private set
    var news by mutableStateOf<Load<List<NewsItem>>?>(null)
        private set
    var bidHistories by mutableStateOf<Map<String, Load<List<BidHistoryRound>>>>(emptyMap())
        private set
    var bidResults by mutableStateOf<Map<String, Load<List<BidResultInfo>>>>(emptyMap())
        private set
    var apiChecks by mutableStateOf<Load<List<ApiCheck>>?>(null)
        private set
    var apiCheckProgress by mutableStateOf("")
        private set

    private val geoCache = HashMap(store.loadGeoCache())
    private val geoFailed = HashSet<String>()
    // 실거래가는 시군구·월 단위라 같은 동네 물건끼리 다시 쓴다.
    private val tradeCache = ConcurrentHashMap<String, List<Trade>>()
    private val rentCache = ConcurrentHashMap<String, List<Rent>>()
    // 활용신청이 안 된 API 는 자동 조회에서 다시 부르지 않는다.
    private val blockedApis = HashSet<String>()

    /** 좌표가 새로 생길 때마다 올라간다 (지도 다시 그리기 신호). */
    var geoVersion by mutableIntStateOf(0)
        private set
    var geocodeProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    val favorites = mutableStateListOf<Property>().apply { addAll(store.loadFavorites()) }
    val compareItems = mutableStateListOf<Property>()

    /** 부동산(공매·경매) + 차량·동산 */
    val allResults: List<Property> by derivedStateOf { (results + movables).distinctBy { it.id } }

    /** 검색 결과·관심·비교 물건의 자동 분석. 권리 상세·실거래·전월세·공적 자료를 불러온 물건은 그것까지 반영한다. */
    val analyses: Map<String, Analysis> by derivedStateOf {
        val all = (allResults + favorites + compareItems).distinctBy { it.id }
        val rights = details.mapNotNull { (id, state) -> (state as? DetailState.Loaded)?.let { id to it.detail } }.toMap()
        val market = markets.mapNotNull { (id, state) -> (state as? MarketState.Loaded)?.let { id to it.summary } }.toMap()
        val records = facts.mapNotNull { (id, state) -> (state as? Load.Done<PublicFacts>)?.let { id to it.value } }.toMap()
        Analyzer.analyzeAll(all, rights, market, courtCases, records, rents)
    }

    fun rightsOf(id: String): PropertyDetail? = (details[id] as? DetailState.Loaded)?.detail

    fun marketOf(id: String): MarketSummary? = (markets[id] as? MarketState.Loaded)?.summary

    fun factsOf(id: String): PublicFacts? = (facts[id] as? Load.Done<PublicFacts>)?.value

    fun courtDetailOf(id: String): CourtDetail? = (courtDetails[id] as? CourtDetailState.Loaded)?.detail

    val visibleResults: List<Property> by derivedStateOf {
        val scored = analyses
        sort.sort(filter.apply(allResults, now, scored), now, scored)
    }

    /** 상세 검색 화면에서 '몇 건 보기'를 미리 보여 줄 때 */
    fun countMatching(draft: PropertyFilter): Int = draft.apply(allResults, now, analyses).size

    /** 불러온 결과에 있는 진행상태 이름들 (상세 검색 칩) */
    val statusNames: List<String> by derivedStateOf {
        allResults.map { it.statusName.trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.take(12)
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

    fun toggleHeader() {
        headerCollapsed = !headerCollapsed
        store.headerCollapsed = headerCollapsed
    }

    /**
     * 지역 검색. 전국(시도 빈 값)·시군구 여러 개를 지원하고, 받은 만큼 바로 화면(지도·목록)에 보여 준다.
     * 법원경매는 사이트 차단을 피하려고 전국이면 시도마다 1쪽(100건)씩만, 천천히 부른다.
     */
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
            val nationwide = s.sido.isBlank()
            val sigungus = Regions.splitSigungu(s.sigungu).ifEmpty { listOf("") }
            fun progress(prefix: String) = ProgressListener { done, total, label ->
                viewModelScope.launch {
                    if (searchState is SearchState.Loading) searchState = SearchState.Loading(done, total, "$prefix · $label")
                }
            }
            // 받은 만큼 바로 보여 준다 (지도 말풍선이 검색이 끝날 때까지 비어 있지 않게).
            fun publish() {
                now = LocalDateTime.now()
                results = found.distinctBy { it.id }
            }

            if (useOnbid) {
                if (key.isBlank()) {
                    problems += "공매: 설정 탭에 공공데이터포털 인증키를 넣으면 공매 물건도 함께 볼 수 있습니다."
                } else if (s.assetTypes.isEmpty()) {
                    problems += "공매: 재산유형을 하나 이상 선택하세요."
                } else {
                    for (sigungu in sigungus) {
                        try {
                            val request = SearchRequest(
                                sido = s.sido,
                                sigungu = sigungu,
                                assetTypes = AssetType.entries.filter { it in s.assetTypes },
                                includePrivateContract = s.includePrivateContract,
                            )
                            val label = if (nationwide) "공매 전국" else "공매 ${sigungu.ifBlank { Regions.shortName(s.sido) }}"
                            val outcome = withContext(Dispatchers.IO) { OnbidClient(key).search(request, LocalDateTime.now(), progress(label)) }
                            found += outcome.properties
                            notes += outcome.warnings.map { "공매 $it" }
                            localFilter = localFilter || outcome.regionFilteredLocally
                            publish()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            problems += "공매: " + (e.message ?: e.javaClass.simpleName)
                            if (e is OnbidApiException && e.isFatal) break
                        }
                    }
                }
            }

            if (useCourt) {
                val requests = if (nationwide) {
                    Regions.sidoList.map { CourtSearchRequest(it, "", s.courtWeeks, maxCalls = 1) }
                } else {
                    val calls = if (sigungus.size > 1) maxOf(2, 8 / sigungus.size) else 8
                    sigungus.map { CourtSearchRequest(s.sido, it, s.courtWeeks, maxCalls = calls) }
                }
                for ((index, request) in requests.withIndex()) {
                    try {
                        val label = "경매 ${Regions.shortName(request.sido)} ${request.sigungu}".trim() +
                            if (requests.size > 1) " (${index + 1}/${requests.size})" else ""
                        val outcome = courtLock.withLock {
                            withContext(Dispatchers.IO) { courtClient.search(request, LocalDateTime.now(), progress(label)) }
                        }
                        found += outcome.properties
                        notes += outcome.warnings
                        publish()
                        // 전국 검색은 시도 사이를 더 띄워 사이트 차단을 피한다.
                        if (nationwide) delay(1_500)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        problems += "경매: " + (e.message ?: e.javaClass.simpleName)
                        if (e is OnbidApiException && e.code == "COURT_LIMIT") break
                    }
                }
                if (nationwide) notes += "경매 전국 검색은 사이트 차단을 피하려고 시도마다 매각기일이 가까운 100건까지만 불러왔습니다."
            }

            now = LocalDateTime.now()
            if (found.isEmpty() && problems.isNotEmpty()) {
                searchState = SearchState.Error(problems.distinct().joinToString("\n"))
            } else {
                results = found.distinctBy { it.id }
                warnings = (problems + notes).distinct()
                regionFilteredLocally = localFilter
                searchState = SearchState.Done(now)
                trackFavoriteChanges(results)
                store.seenIds = store.seenIds + results.map { it.id }
            }
        }
    }

    /**
     * 통합 검색. 검색어를 지역·종류·가격 등으로 해석해 필터로 걸고,
     * 지금 불러온 지역과 다르면 그 지역을 새로 검색하고, 차량·동산이면 전국 차량·동산 공매를 불러온다.
     */
    fun runQuery(query: String) {
        val text = query.trim()
        val intent = QueryParser.parse(text)
        updateFilter(filter.copy(keyword = text))
        queryNote = null
        if (intent.isEmpty) return
        recentQueries = (listOf(text) + recentQueries.filter { it != text }).take(10)
        store.recentQueries = recentQueries
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
            intent.nationwide && intent.sido.isEmpty() && !intent.wantsMovables -> {
                if (settings.sido.isNotBlank() || results.isEmpty()) {
                    updateSettings(settings.copy(sido = Regions.NATIONWIDE, sigungu = ""))
                    queryNote = "전국 물건을 불러옵니다. 오래 걸릴 수 있어 받은 만큼 먼저 보여 줍니다."
                    search()
                }
            }
            // "서울 논현동"처럼 시군구 없이 동이 있으면 동으로 시군구를 찾는다 (시도 전체 검색은 너무 넓다).
            intent.dong.isNotEmpty() && intent.sigungu.isEmpty() && !intent.wantsMovables ->
                resolveDong(listOf(Regions.shortName(intent.sido), intent.dong).filter { it.isNotBlank() }.joinToString(" "))
            regionChanged -> {
                updateSettings(settings.copy(sido = intent.sido, sigungu = intent.sigungu))
                queryNote = "${Regions.label(intent.sido, intent.sigungu)} 물건을 불러옵니다."
                search()
            }
            results.isEmpty() && !intent.wantsMovables && searchState !is SearchState.Loading -> search()
        }
    }

    fun removeRecentQuery(text: String) {
        recentQueries = recentQueries - text
        store.recentQueries = recentQueries
    }

    /** 지역을 바꾸고 바로 검색한다 (홈·지역 선택 창) */
    fun searchRegion(sido: String, sigungus: List<String>) {
        updateSettings(settings.copy(sido = sido, sigungu = Regions.joinSigungu(sigungus)))
        search()
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
            queryNote = "$dong → ${Regions.label(region.first, region.second)} 물건을 불러옵니다. (다른 지역의 같은 이름이면 '시·구 + 동'으로 검색하세요)"
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
                val approval = e.code in APPROVAL_CODES
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
            ?: rankings.values.firstNotNullOfOrNull { state -> (state as? Load.Done<List<Property>>)?.value?.firstOrNull { it.id == id } }
            ?: (details[id] as? DetailState.Loaded)?.detail?.property

    fun isFavorite(id: String): Boolean = favorites.any { it.id == id }

    fun toggleFavorite(property: Property) {
        val index = favorites.indexOfFirst { it.id == property.id }
        if (index >= 0) favorites.removeAt(index) else favorites.add(0, property)
        store.saveFavorites(favorites.toList())
    }

    /** 다시 불러온 결과로 관심 물건의 가격·유찰·일정 변동을 기록하고 저장해 둔 물건 정보를 새로 바꾼다. */
    private fun trackFavoriteChanges(fresh: List<Property>) {
        if (favorites.isEmpty()) return
        val byId = fresh.associateBy { it.id }
        val log = favoriteChanges.toMutableMap()
        var changed = false
        favorites.toList().forEachIndexed { index, old ->
            val new = byId[old.id] ?: return@forEachIndexed
            val diff = Changes.between(old, new)
            if (diff.isNotEmpty()) log[old.id] = (diff + log[old.id].orEmpty()).distinct().take(6)
            if (new != old) {
                favorites[index] = new
                changed = true
            }
        }
        if (changed) store.saveFavorites(favorites.toList())
        if (log != favoriteChanges) {
            favoriteChanges = log
            store.favoriteChanges = log
        }
    }

    fun clearFavoriteChanges(id: String? = null) {
        favoriteChanges = if (id == null) emptyMap() else favoriteChanges - id
        store.favoriteChanges = favoriteChanges
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

    fun memoOf(id: String): String = memos[id].orEmpty()

    fun saveMemo(id: String, text: String) {
        memos = if (text.isBlank()) memos - id else memos + (id to text)
        store.memos = memos
    }

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
        return (e.message ?: "상세 조회 중 오류가 났습니다.") + if (e.code in APPROVAL_CODES) hint else ""
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
     * 좌표가 없는 물건의 위치를 찾는다.
     * 1) 동 중심을 먼저 찾는다 — 여러 물건이 같이 쓰므로 몇 번만 찾아도 지도에 말풍선이 바로 뜬다.
     * 2) 그다음 지번 주소로 정확한 위치를 찾는다 (휴대폰 지오코더 → OpenStreetMap). 찾은 값은 기기에 남긴다.
     */
    fun ensurePositions(list: List<Property>) {
        val withoutExact = list.filter { it.position == null && MapPayload.geoKey(it).isNotBlank() && geoCache[MapPayload.geoKey(it)] == null }
        val districts = withoutExact.map { districtKey(it) }.distinct().filter { it != "동:" && geoCache[it] == null && it !in geoFailed }
        val exact = withoutExact.filter { MapPayload.geoKey(it) !in geoFailed }.distinctBy { MapPayload.geoKey(it) }.take(150)
        if (districts.isEmpty() && exact.isEmpty()) return
        geocodeJob?.cancel()
        geocodeJob = viewModelScope.launch {
            val total = districts.size + exact.size
            var done = 0
            geocodeProgress = 0 to total
            var deviceFailures = 0
            for (district in districts) {
                val query = district.removePrefix("동:")
                val center = withContext(Dispatchers.IO) { geocoder.locate(query) ?: nominatim.search(query) }
                if (center != null) {
                    geoCache[district] = center
                    store.saveGeo(district, center)
                    geoVersion++
                } else {
                    geoFailed += district
                }
                done++
                geocodeProgress = done to total
            }
            for ((index, p) in exact.withIndex()) {
                val key = MapPayload.geoKey(p)
                val found = withContext(Dispatchers.IO) {
                    // 휴대폰 지오코더가 연달아 실패하면 더 부르지 않는다.
                    val viaDevice = if (deviceFailures < 3) geocoder.locate(p.geocodeQueries.first()) else null
                    if (viaDevice == null) deviceFailures++ else deviceFailures = 0
                    viaDevice ?: if (p.address.isNotBlank()) nominatim.search(p.address) else null
                }
                if (found != null) {
                    geoCache[key] = found
                    store.saveGeo(key, found)
                } else {
                    geoFailed += key
                }
                done++
                geocodeProgress = done to total
                if (index % 5 == 4 || index == exact.lastIndex) geoVersion++
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
            onResult(Regions.label(region.first, region.second))
        }
    }

    private suspend fun trades(kind: MarketKind, lawd: String, months: Int): List<Trade> {
        val key = serviceKey
        val today = LocalDate.now()
        return withContext(Dispatchers.IO) {
            (0 until months).flatMap { back ->
                val ym = today.minusMonths(back.toLong()).format(YEAR_MONTH)
                val cacheKey = "${kind.name}|$lawd|$ym"
                tradeCache[cacheKey] ?: MolitClient(key).trades(kind, lawd, ym).also { tradeCache[cacheKey] = it }
            }
        }
    }

    private suspend fun rentsOf(kind: RentKind, lawd: String, months: Int): List<Rent> {
        val key = serviceKey
        val today = LocalDate.now()
        return withContext(Dispatchers.IO) {
            (0 until months).flatMap { back ->
                val ym = today.minusMonths(back.toLong()).format(YEAR_MONTH)
                val cacheKey = "${kind.name}|$lawd|$ym"
                rentCache[cacheKey] ?: MolitClient(key).rents(kind, lawd, ym).also { rentCache[cacheKey] = it }
            }
        }
    }

    /**
     * 주변 실거래가 (국토교통부 API, 최근 6개월). 같은 공공데이터포털 인증키를 쓰지만 API 마다 활용신청이 따로 필요하다.
     * 같은 시군구·같은 종류는 한 번 받은 거래를 다시 쓴다. 전월세도 함께 불러 전세가율·월세 수익률을 낸다.
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
        if (retry) blockedApis -= kind.name
        markets = markets + (p.id to MarketState.Loading)
        viewModelScope.launch {
            markets = markets + (p.id to marketState(p, kind))
            loadRent(p)
        }
    }

    private suspend fun marketState(p: Property, kind: MarketKind): MarketState = try {
        MarketState.Loaded(MolitClient.summarize(p, kind, trades(kind, p.lawdCode, MARKET_MONTHS), MARKET_MONTHS))
    } catch (e: CancellationException) {
        throw e
    } catch (e: OnbidApiException) {
        val approval = e.code in APPROVAL_CODES
        if (approval) blockedApis += kind.name
        MarketState.Error(
            if (approval) "공공데이터포털에서 '${kind.apiName}' 활용신청이 필요합니다. (승인 후 1~2시간 뒤 반영)" else e.message.orEmpty(),
            needsApiApproval = approval,
        )
    } catch (e: Exception) {
        MarketState.Error("실거래가 조회 중 오류: ${e.message ?: e.javaClass.simpleName}")
    }

    private suspend fun loadRent(p: Property) {
        val kind = RentKind.of(p.category) ?: return
        if (p.id in rents || "RENT_${kind.name}" in blockedApis || p.lawdCode.length != 5 || serviceKey.isBlank()) return
        try {
            rents = rents + (p.id to MolitClient.summarizeRents(p, kind, rentsOf(kind, p.lawdCode, MARKET_MONTHS), MARKET_MONTHS))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OnbidApiException) {
            if (e.code in APPROVAL_CODES) blockedApis += "RENT_${kind.name}"
        } catch (e: Exception) {
            // 전월세는 보조 자료라 실패해도 조용히 넘어간다.
        }
    }

    fun rentOf(id: String): RentSummary? = rents[id]

    /** 전월세 API 가 활용신청이 안 돼 막혀 있는지 */
    fun rentBlocked(p: Property): Boolean = RentKind.of(p.category)?.let { "RENT_${it.name}" in blockedApis } ?: false

    /**
     * 검색 결과의 실거래가·전월세·공시지가·건축물대장을 자동으로 불러와 분석에 넣는다 (설정에서 끌 수 있다).
     * 같은 시군구·종류는 한 번만 받고, 목록 위쪽 물건부터 처리한다.
     */
    fun autoLookups(list: List<Property>) {
        if (!autoLookup) return
        val targets = list.filter { !it.category.isMovable }.take(60)
        if (targets.isEmpty()) return
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            delay(1_000)
            if (serviceKey.isNotBlank()) {
                val groups = targets.filter { markets[it.id] == null && it.lawdCode.length == 5 }
                    .mapNotNull { p -> MarketKind.of(p.category)?.let { (it to p.lawdCode) to p } }
                    .groupBy({ it.first }, { it.second })
                for ((group, props) in groups.entries.take(10)) {
                    val kind = group.first
                    if (kind.name in blockedApis) continue
                    markets = markets + props.associate { it.id to MarketState.Loading }
                    val trades = try {
                        trades(kind, group.second, MARKET_MONTHS)
                    } catch (e: CancellationException) {
                        markets = markets - props.map { it.id }.toSet()
                        throw e
                    } catch (e: Exception) {
                        val state = if (e is OnbidApiException && e.code in APPROVAL_CODES) {
                            blockedApis += kind.name
                            MarketState.Error("공공데이터포털에서 '${kind.apiName}' 활용신청이 필요합니다.", needsApiApproval = true)
                        } else {
                            MarketState.Error("실거래가 조회 중 오류: ${e.message ?: e.javaClass.simpleName}")
                        }
                        markets = markets + props.associate { it.id to state }
                        continue
                    }
                    markets = markets + props.associate { it.id to MarketState.Loaded(MolitClient.summarize(it, kind, trades, MARKET_MONTHS)) }
                    props.forEach { loadRent(it) }
                }
            }
            for (p in targets.take(30)) {
                if (p.id in facts) continue
                loadFactsNow(p)
                delay(250)
            }
        }
    }

    /** 공시지가·개별주택가격·건축물대장 (상세 화면) */
    fun loadFacts(p: Property, retry: Boolean = false) {
        val current = facts[p.id]
        if (current is Load.Loading || current is Load.Done && !retry) return
        if (current is Load.Error && !retry) return
        viewModelScope.launch { loadFactsNow(p) }
    }

    private suspend fun loadFactsNow(p: Property) {
        if (p.category.isMovable) return
        facts = facts + (p.id to Load.Loading)
        val withBuilding = serviceKey.isNotBlank() && BUILDING_API !in blockedApis
        val state: Load<PublicFacts> = try {
            val result = withContext(Dispatchers.IO) { PublicRecordsClient(serviceKey).collect(p, withBuilding) }
            if (result.notes.any { it.startsWith("건축물대장") && it.contains("활용신청") }) blockedApis += BUILDING_API
            Load.Done(result)
        } catch (e: CancellationException) {
            facts = facts - p.id
            throw e
        } catch (e: Exception) {
            Load.Error(e.message ?: e.javaClass.simpleName)
        }
        facts = facts + (p.id to state)
    }

    fun setAutoLookup(enabled: Boolean) {
        autoLookup = enabled
        store.autoLookup = enabled
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

    // ── 온비드 순위·통계·입찰 이력 ──

    fun rankingKey(kind: RankKind, division: RankDivision) = "${kind.name}|${division.name}"

    fun loadRanking(kind: RankKind, division: RankDivision, force: Boolean = false) {
        val id = rankingKey(kind, division)
        val current = rankings[id]
        if (current is Load.Loading || current is Load.Done && !force) return
        val key = serviceKey
        if (key.isBlank()) {
            rankings = rankings + (id to Load.Error("설정 탭에 공공데이터포털 인증키를 넣으세요."))
            return
        }
        rankings = rankings + (id to Load.Loading)
        viewModelScope.launch {
            rankings = rankings + (id to onbidLoad("순위물건목록") { OnbidExtrasClient(OnbidClient(key)).ranking(kind, division, now = LocalDateTime.now()) })
        }
    }

    fun loadUsageStats(statsType: String, force: Boolean = false) {
        val current = usageStats[statsType]
        if (current is Load.Loading || current is Load.Done && !force) return
        val key = serviceKey
        if (key.isBlank()) {
            usageStats = usageStats + (statsType to Load.Error("설정 탭에 공공데이터포털 인증키를 넣으세요."))
            return
        }
        usageStats = usageStats + (statsType to Load.Loading)
        viewModelScope.launch {
            usageStats = usageStats + (statsType to onbidLoad("용도별 입찰 통계") {
                val client = OnbidExtrasClient(OnbidClient(key))
                // 작년 통계가 아직 없으면 재작년으로 물러난다.
                client.usageStats(statsType, OnbidExtrasClient.defaultPeriod()).ifEmpty {
                    client.usageStats(statsType, (LocalDate.now().year - 2).toString())
                }
            })
        }
    }

    /** 공매 물건에 맞는 용도별 통계 종류 (압류 → 0041 …) */
    fun statsTypeOf(p: Property): String = when (p.assetTypeCode) {
        "0010" -> "0044"
        "0008", "0006" -> "0045"
        "0002" -> "0046"
        else -> "0041"
    }

    fun usageStatFor(p: Property): UsageStat? {
        if (p.isCourt || p.category.isMovable) return null
        val stats = (usageStats[statsTypeOf(p)] as? Load.Done<List<UsageStat>>)?.value ?: return null
        return OnbidExtrasClient.statFor(p, stats)
    }

    fun loadBidHistory(p: Property) {
        if (p.isCourt || p.conditionNo.isBlank()) return
        val current = bidHistories[p.id]
        if (current is Load.Loading || current is Load.Done) return
        val key = serviceKey.ifBlank { return }
        bidHistories = bidHistories + (p.id to Load.Loading)
        viewModelScope.launch {
            bidHistories = bidHistories + (p.id to onbidLoad("물건상세 입찰정보") { OnbidExtrasClient(OnbidClient(key)).bidHistory(p.id, p.conditionNo) })
        }
    }

    fun loadBidResult(p: Property) {
        if (p.isCourt) return
        val current = bidResults[p.id]
        if (current is Load.Loading || current is Load.Done) return
        val key = serviceKey.ifBlank { return }
        bidResults = bidResults + (p.id to Load.Loading)
        viewModelScope.launch {
            bidResults = bidResults + (p.id to onbidLoad("물건 입찰결과상세") { OnbidExtrasClient(OnbidClient(key)).bidResult(p.id) })
        }
    }

    private suspend fun <T> onbidLoad(apiName: String, block: () -> T): Load<T> = try {
        Load.Done(withContext(Dispatchers.IO) { block() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: OnbidApiException) {
        val approval = e.code in APPROVAL_CODES
        Load.Error(if (approval) "공공데이터포털에서 '차세대 온비드 $apiName 조회서비스' 활용신청이 필요합니다." else e.message.orEmpty(), approval)
    } catch (e: Exception) {
        Load.Error(e.message ?: e.javaClass.simpleName)
    }

    fun loadNews(force: Boolean = false) {
        val current = news
        if (current is Load.Loading || current is Load.Done && !force) return
        news = Load.Loading
        viewModelScope.launch {
            news = try {
                Load.Done(withContext(Dispatchers.IO) { NewsClient().search("부동산 경매 공매").take(30) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Error("뉴스를 불러오지 못했습니다: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 공공데이터포털 API 를 하나씩 불러 활용신청 상태를 확인한다. */
    fun runApiCheck() {
        if (apiChecks is Load.Loading) return
        val key = serviceKey
        if (key.isBlank()) {
            apiChecks = Load.Error("설정 탭에서 공공데이터포털 인증키를 먼저 저장하세요.")
            return
        }
        apiChecks = Load.Loading
        viewModelScope.launch {
            apiChecks = try {
                Load.Done(
                    withContext(Dispatchers.IO) {
                        ApiDoctor(key).run(
                            ProgressListener { done, total, label ->
                                viewModelScope.launch { apiCheckProgress = "$label ($done/$total)" }
                            },
                        )
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Error(e.message ?: e.javaClass.simpleName)
            }
            blockedApis.clear()
        }
    }

    // ── AI ──

    fun aiKey(id: String, mode: ClaudePrompt.Mode) = "$id|${mode.name}"

    fun updateAiProvider(provider: AiProvider) {
        aiProvider = provider
        store.aiProvider = provider
    }

    fun keyOf(provider: AiProvider): String = aiKeys[provider].orEmpty()

    fun saveAiKey(provider: AiProvider, raw: String) {
        val key = raw.trim()
        store.saveAiKey(provider, key)
        aiKeys = aiKeys + (provider to key)
        if (provider == AiProvider.GEMINI_API) {
            geminiKey = key
            geminiTest = null
        }
    }

    fun saveGeminiKey(raw: String) = saveAiKey(AiProvider.GEMINI_API, raw)

    fun toggleCompareProvider(provider: AiProvider) {
        compareProviders = if (provider in compareProviders) compareProviders - provider else compareProviders + provider
        store.compareProviders = compareProviders
    }

    /** API 키 연결 확인 (짧은 질문 하나) */
    fun testAi(provider: AiProvider) {
        val engine = AiEngines.create(provider, keyOf(provider))
        if (engine == null) {
            geminiTest = AiState.Error("${provider.short} 키를 먼저 저장하세요.")
            return
        }
        geminiTest = AiState.Loading
        viewModelScope.launch {
            geminiTest = try {
                val answer = withContext(Dispatchers.IO) { engine.generate("한 문장으로 답해 줘: 경매에서 말소기준권리란?") }
                AiState.Done(answer.text, "${provider.short} (${answer.model})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                AiState.Error(e.message.orEmpty())
            } catch (e: Exception) {
                AiState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun testGemini() = testAi(AiProvider.GEMINI_API)

    /** 앱 내장 분석 글을 결과 칸에 보여 준다. */
    fun showLocalAi(id: String, mode: ClaudePrompt.Mode, text: String) {
        aiResults = aiResults + (aiKey(id, mode) to AiState.Done(text, "앱 내장 분석 (규칙 기반)"))
    }

    fun clearAi(id: String, mode: ClaudePrompt.Mode) {
        aiResults = aiResults - aiKey(id, mode)
        compareResults = compareResults - aiKey(id, mode)
    }

    /** 고른 API(무료 키)로 실시간 분석 */
    fun runApi(provider: AiProvider, id: String, mode: ClaudePrompt.Mode, prompt: String) {
        val key = aiKey(id, mode)
        if (aiResults[key] is AiState.Loading) return
        val engine = AiEngines.create(provider, keyOf(provider))
        if (engine == null) {
            aiResults = aiResults + (key to AiState.Error("설정 탭에서 ${provider.short} API 키를 먼저 넣으세요."))
            return
        }
        aiResults = aiResults + (key to AiState.Loading)
        viewModelScope.launch {
            aiResults = aiResults + (key to askEngine(provider, engine, prompt))
        }
    }

    fun runGemini(id: String, mode: ClaudePrompt.Mode, prompt: String) = runApi(AiProvider.GEMINI_API, id, mode, prompt)

    /**
     * 'AI 비교': 설정에서 고른 AI(내장 + 키가 있는 무료 API)에 같은 질문을 동시에 보내 나란히 보여 준다.
     * 앱으로 넘기는 AI(ChatGPT·Claude 앱)는 답을 받아올 수 없어 비교에서 빠진다.
     */
    fun runCompare(id: String, mode: ClaudePrompt.Mode, prompt: String, local: String) {
        val key = aiKey(id, mode)
        val providers = compareProviders.filter { it == AiProvider.BUILT_IN || it.isApi && keyOf(it).isNotBlank() }
            .sortedBy { it.ordinal }
            .ifEmpty { listOf(AiProvider.BUILT_IN) }
        compareResults = compareResults + (key to providers.associateWith { provider ->
            if (provider == AiProvider.BUILT_IN) AiState.Done(local, "앱 내장 분석 (규칙 기반)") else AiState.Loading
        })
        providers.filter { it.isApi }.forEach { provider ->
            val engine = AiEngines.create(provider, keyOf(provider)) ?: return@forEach
            viewModelScope.launch {
                val state = askEngine(provider, engine, prompt)
                compareResults = compareResults + (key to (compareResults[key].orEmpty() + (provider to state)))
            }
        }
    }

    private suspend fun askEngine(provider: AiProvider, engine: io.github.jgg113.gongmae.core.AiEngine, prompt: String): AiState = try {
        val answer = withContext(Dispatchers.IO) { engine.generate(prompt) }
        AiState.Done(answer.text, "${provider.short} (${answer.model}) · 실시간")
    } catch (e: CancellationException) {
        throw e
    } catch (e: AiException) {
        AiState.Error(e.message.orEmpty())
    } catch (e: Exception) {
        AiState.Error("AI 분석 중 오류: ${e.message ?: e.javaClass.simpleName}")
    }

    // ── 설정 ──

    fun saveVworldKey(raw: String) {
        vworldKey = raw.trim()
        store.vworldKey = vworldKey
    }

    /** 새 물건·관심 물건 변동 알림 (하루 두 번 배경에서 확인) */
    fun setAlerts(enabled: Boolean) {
        alertsEnabled = enabled
        store.alertsEnabled = enabled
        val context = getApplication<Application>()
        if (enabled) AlertWorker.schedule(context) else AlertWorker.cancel(context)
    }

    fun saveServiceKey(raw: String) {
        val normalized = ServiceKey.normalize(raw)
        serviceKey = normalized
        store.serviceKey = normalized
        keyTest = KeyTestState.Idle
        blockedApis.clear()
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

    private companion object {
        const val BUILDING_API = "BUILDING"
    }
}
