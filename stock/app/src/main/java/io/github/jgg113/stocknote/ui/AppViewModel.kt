package io.github.jgg113.stocknote.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jgg113.stocknote.core.Account
import io.github.jgg113.stocknote.core.AccountType
import io.github.jgg113.stocknote.core.AiEngines
import io.github.jgg113.stocknote.core.AiProvider
import io.github.jgg113.stocknote.core.AiText
import io.github.jgg113.stocknote.core.Asset
import io.github.jgg113.stocknote.core.BacktestConfig
import io.github.jgg113.stocknote.core.BacktestResult
import io.github.jgg113.stocknote.core.Backtester
import io.github.jgg113.stocknote.core.Breadth
import io.github.jgg113.stocknote.core.Candle
import io.github.jgg113.stocknote.core.CandleAggregator
import io.github.jgg113.stocknote.core.ChartGame
import io.github.jgg113.stocknote.core.Currency
import io.github.jgg113.stocknote.core.DataKeys
import io.github.jgg113.stocknote.core.epochKst
import io.github.jgg113.stocknote.core.FeatureKind
import io.github.jgg113.stocknote.core.FeaturedRow
import io.github.jgg113.stocknote.core.Financials
import io.github.jgg113.stocknote.core.ForecastReport
import io.github.jgg113.stocknote.core.Forecaster
import io.github.jgg113.stocknote.core.IndexQuote
import io.github.jgg113.stocknote.core.IndicatorConfig
import io.github.jgg113.stocknote.core.IndicatorReport
import io.github.jgg113.stocknote.core.Market
import io.github.jgg113.stocknote.core.MarketData
import io.github.jgg113.stocknote.core.MarketStats
import io.github.jgg113.stocknote.core.NewsItem
import io.github.jgg113.stocknote.core.PaperBroker
import io.github.jgg113.stocknote.core.PriceData
import io.github.jgg113.stocknote.core.Quote
import io.github.jgg113.stocknote.core.ScanResult
import io.github.jgg113.stocknote.core.Screener
import io.github.jgg113.stocknote.core.ScreenerFilter
import io.github.jgg113.stocknote.core.ScreenerRow
import io.github.jgg113.stocknote.core.SignalScanner
import io.github.jgg113.stocknote.core.SourceStatus
import io.github.jgg113.stocknote.core.StockPrompt
import io.github.jgg113.stocknote.core.Strategies
import io.github.jgg113.stocknote.core.Theme
import io.github.jgg113.stocknote.core.ThemePerformance
import io.github.jgg113.stocknote.core.ThemeStats
import io.github.jgg113.stocknote.core.Themes
import io.github.jgg113.stocknote.core.Timeframe
import io.github.jgg113.stocknote.core.TradeException
import io.github.jgg113.stocknote.core.UpDownQuiz
import io.github.jgg113.stocknote.data.AlertSettings
import io.github.jgg113.stocknote.data.AlertWorker
import io.github.jgg113.stocknote.data.AppStore
import io.github.jgg113.stocknote.data.FileCache
import io.github.jgg113.stocknote.data.PriceAlert
import io.github.jgg113.stocknote.data.ScanAlert
import io.github.jgg113.stocknote.data.SignalAlert
import io.github.jgg113.stocknote.data.Watchlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.random.Random

enum class Tab(val label: String) { HOME("시장"), WATCH("관심"), DISCOVER("발굴"), INVEST("투자"), SETTINGS("설정") }

sealed interface Screen {
    data class Detail(val asset: Asset) : Screen
    data class ThemeDetail(val theme: Theme) : Screen
    data object Search : Screen
    data class Pick(val purpose: PickPurpose) : Screen
}

/** 검색 화면을 고르기용으로 열 때 */
sealed interface PickPurpose {
    data class Watch(val listId: String) : PickPurpose
    data class ThemeMember(val themeId: String) : PickPurpose
    data class Holding(val accountId: String) : PickPurpose
}

data class HomeState(
    val market: Market = Market.KR,
    val loading: Boolean = false,
    val error: String? = null,
    val indices: List<IndexQuote> = emptyList(),
    val proxies: List<Quote> = emptyList(),
    val breadth: Breadth? = null,
    val featured: Map<FeatureKind, List<FeaturedRow>> = emptyMap(),
    val themes: List<ThemePerformance> = emptyList(),
    val news: List<NewsItem> = emptyList(),
    val disclosures: List<NewsItem> = emptyList(),
    val usdKrw: Double? = null,
    val dataDate: Long = 0,
    val realtime: Boolean = false,
)

data class DetailState(
    val asset: Asset,
    val quote: Quote? = null,
    val timeframe: Timeframe = Timeframe.DAY,
    /** 일봉 원본 (분석용) */
    val daily: List<Candle> = emptyList(),
    /** 차트에 그리는 봉 */
    val candles: List<Candle> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val chartError: String? = null,
    val report: IndicatorReport? = null,
    val forecast: ForecastReport? = null,
    val analyzing: Boolean = false,
    val financials: Financials? = null,
    val financialsError: String? = null,
    val news: List<NewsItem> = emptyList(),
    val disclosures: List<NewsItem> = emptyList(),
    val issuesLoading: Boolean = false,
    val selected: BacktestResult? = null,
    val aiText: String? = null,
    val aiLoading: Boolean = false,
    val aiError: String? = null,
)

data class SignalsState(
    val market: Market = Market.KR,
    val size: Int = 50,
    val strategyIds: Set<String> = emptySet(),
    val includeWatch: Boolean = true,
    val running: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val result: ScanResult? = null,
    val error: String? = null,
    val selectedPortfolio: String? = null,
)

data class FeaturedState(
    val market: Market = Market.KR,
    val kind: FeatureKind = FeatureKind.GAINERS,
    val loading: Boolean = false,
    val rows: List<FeaturedRow> = emptyList(),
    val live: Boolean = false,
    val error: String? = null,
)

data class ScreenerState(
    val market: Market = Market.KR,
    val filter: ScreenerFilter = ScreenerFilter(),
    val running: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val rows: List<ScreenerRow>? = null,
    val error: String? = null,
)

data class ThemesState(
    val market: Market = Market.KR,
    val loading: Boolean = false,
    val ranking: List<ThemePerformance> = emptyList(),
    val error: String? = null,
)

data class GameState(
    val game: ChartGame? = null,
    val quiz: UpDownQuiz? = null,
    val quizAnswered: Boolean? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val market: Market = Market.KR,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = AppStore(app)
    private val cache = FileCache(app)
    private var _data: MarketData? = null

    val data: MarketData
        get() = _data ?: MarketData(store.keys, cache, store.tokenStore).also { _data = it }

    var keys by mutableStateOf(store.keys); private set
    var tab by mutableStateOf(Tab.HOME)
    var stack by mutableStateOf<List<Screen>>(emptyList()); private set
    var toast by mutableStateOf<String?>(null)

    var home by mutableStateOf(HomeState(market = store.homeMarket)); private set
    var detail by mutableStateOf<DetailState?>(null); private set
    var watchlists by mutableStateOf(store.watchlists); private set
    var watchQuotes by mutableStateOf<Map<String, Quote>>(emptyMap()); private set
    var signals by mutableStateOf(SignalsState(strategyIds = store.signalStrategies.toSet().ifEmpty { DEFAULT_SIGNAL_STRATEGIES })); private set
    var featured by mutableStateOf(FeaturedState()); private set
    var screener by mutableStateOf(ScreenerState(filter = store.screener)); private set
    var themes by mutableStateOf(ThemesState()); private set
    var customThemes by mutableStateOf(store.customThemes); private set
    var accounts by mutableStateOf(store.accounts); private set
    var accountPrices by mutableStateOf<Map<String, Double>>(emptyMap()); private set
    var usdKrw by mutableStateOf<Double?>(null); private set
    var game by mutableStateOf(GameState()); private set
    var gameStats by mutableStateOf(store.gameStats); private set
    var indicators by mutableStateOf(store.indicators); private set
    var priceAlerts by mutableStateOf(store.priceAlerts); private set
    var signalAlerts by mutableStateOf(store.signalAlerts); private set
    var scanAlerts by mutableStateOf(store.scanAlerts); private set
    var alertSettings by mutableStateOf(store.alertSettings); private set
    var checks by mutableStateOf<List<SourceStatus>?>(null); private set
    var checking by mutableStateOf(false); private set
    var searchResults by mutableStateOf<List<Asset>>(emptyList()); private set
    var searching by mutableStateOf(false); private set

    private var detailJob: Job? = null
    private var searchJob: Job? = null

    val allThemes: List<Theme> get() = customThemes + Themes.builtIn

    // ───────── 이동 ─────────

    fun open(screen: Screen) {
        stack = stack + screen
        if (screen is Screen.Detail) loadDetail(screen.asset)
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack = stack.dropLast(1)
        (stack.lastOrNull() as? Screen.Detail)?.let { if (detail?.asset?.key != it.asset.key) loadDetail(it.asset) }
        return true
    }

    fun openAsset(asset: Asset) = open(Screen.Detail(asset))

    fun message(text: String) { toast = text }

    private fun err(e: Throwable): String = e.message ?: e.javaClass.simpleName

    // ───────── 설정 ─────────

    fun saveKeys(k: DataKeys) {
        store.keys = k
        keys = k
        _data = null
        message("저장했습니다. 연결 점검으로 확인해 보세요.")
        refreshHome()
    }

    fun saveIndicators(list: List<IndicatorConfig>) {
        indicators = list
        store.indicators = list
    }

    fun updateAlertSettings(s: AlertSettings) {
        alertSettings = s
        store.alertSettings = s
        AlertWorker.schedule(getApplication(), s)
    }

    fun runChecks() {
        checking = true
        checks = null
        viewModelScope.launch {
            checks = withContext(Dispatchers.IO) { runCatching { data.check() }.getOrElse { listOf(SourceStatus("점검", false, err(it))) } }
            checking = false
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) { data.clearCache() }
        message("저장해 둔 시세를 지웠습니다.")
    }

    fun cacheSize(): Long = cache.sizeBytes()

    fun aiKey(p: AiProvider) = store.aiKey(p)

    // ───────── 시장 요약 ─────────

    fun setHomeMarket(m: Market) {
        store.homeMarket = m
        home = HomeState(market = m)
        refreshHome()
    }

    fun refreshHome() {
        val market = home.market
        home = home.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val errors = ArrayList<String>()
                fun <T> safe(block: () -> T): T? = runCatching(block).onFailure { errors += err(it) }.getOrNull()
                val quotes = safe { data.marketQuotes(market) }.orEmpty()
                val indices = if (market == Market.KR) safe { data.indices() }.orEmpty() else emptyList()
                val proxies = if (market == Market.US) safe { data.usProxies() }.orEmpty() else emptyList()
                val liveValue = if (market == Market.KR) data.krLiveRank(true) else emptyList()
                val featured = buildMap {
                    put(FeatureKind.GAINERS, MarketStats.featured(FeatureKind.GAINERS, quotes, limit = 8, minValue = minValue(market)))
                    put(FeatureKind.LOSERS, MarketStats.featured(FeatureKind.LOSERS, quotes, limit = 8, minValue = minValue(market)))
                    put(FeatureKind.VALUE, if (liveValue.isNotEmpty()) liveValue.take(8).map { FeaturedRow(it, it.value, it.value.let { v -> io.github.jgg113.stocknote.core.Fmt.big(v) }) } else MarketStats.featured(FeatureKind.VALUE, quotes, limit = 8))
                }
                val quoteMap = quotes.associateBy { it.asset.key }.toMutableMap()
                val themeList = allThemes.filter { it.market == market }
                if (market != Market.KR || quoteMap.isEmpty()) {
                    val missing = themeList.flatMap { it.members }.distinct().filter { it !in quoteMap }
                    val assets = missing.mapNotNull { k -> Asset.parseKey(k)?.let { (m, c) -> Asset(m, c, "") } }
                    if (assets.size <= 60) quoteMap.putAll(safe { data.quotes(assets, 120) }.orEmpty())
                }
                val themes = ThemeStats.rank(themeList, quoteMap)
                val news = safe { data.news(when (market) { Market.KR -> "코스피 코스닥 증시"; Market.US -> "뉴욕증시 미국 주식"; Market.CRYPTO -> "비트코인 가상자산 시장" }, 15) }.orEmpty()
                val disclosures = if (market == Market.KR && keys.hasDart) safe { data.disclosures(null, 2) }.orEmpty().filter { important(it.title) }.take(15) else emptyList()
                HomeState(
                    market = market,
                    loading = false,
                    error = if (quotes.isEmpty() && indices.isEmpty()) errors.firstOrNull() ?: noDataHint(market) else null,
                    indices = indices,
                    proxies = proxies,
                    breadth = if (quotes.isNotEmpty()) MarketStats.breadth(quotes, if (market == Market.KR) 29.5 else 1000.0) else null,
                    featured = featured,
                    themes = themes,
                    news = news,
                    disclosures = disclosures,
                    usdKrw = safe { data.usdKrw() },
                    dataDate = quotes.maxOfOrNull { it.time } ?: 0,
                    realtime = quotes.firstOrNull()?.realtime == true || liveValue.isNotEmpty(),
                )
            }
            if (home.market == market) home = result
            usdKrw = result.usdKrw ?: usdKrw
        }
    }

    private fun minValue(m: Market) = when (m) { Market.KR -> 3e8; Market.CRYPTO -> 1e8; Market.US -> 0.0 }

    private fun noDataHint(m: Market) = when (m) {
        Market.KR -> "국내 시세를 보려면 [설정]에서 공공데이터포털 인증키(무료)나 한국투자증권 앱키를 넣으세요."
        Market.US -> "해외 시세를 보려면 [설정]에서 한국투자증권 앱키나 Twelve Data 키(무료)를 넣으세요."
        Market.CRYPTO -> "코인 시세를 받지 못했습니다. 인터넷 연결을 확인하세요."
    }

    private val importantWords = Regex("단일판매|공급계약|유상증자|무상증자|자기주식|자사주|합병|분할|최대주주|감자|전환사채|신주인수권|영업실적|잠정|배당|상장폐지|거래정지|소송|불성실|대규모")
    private fun important(title: String) = importantWords.containsMatchIn(title)

    // ───────── 검색 ─────────

    fun search(text: String) {
        searchJob?.cancel()
        if (text.isBlank()) { searchResults = emptyList(); searching = false; return }
        searching = true
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            val r = withContext(Dispatchers.IO) { runCatching { data.search(text) }.getOrDefault(emptyList()) }
            searchResults = r
            searching = false
        }
    }

    fun rememberSearch(text: String) {
        if (text.isBlank()) return
        store.recentSearches = listOf(text) + store.recentSearches.filter { it != text }
    }

    // ───────── 종목 상세 ─────────

    fun loadDetail(asset: Asset) {
        detailJob?.cancel()
        detail = DetailState(asset)
        detailJob = viewModelScope.launch {
            val quote = withContext(Dispatchers.IO) { runCatching { data.quote(asset) } }
            quote.getOrNull()?.let { q -> update(asset) { it.copy(quote = q, asset = if (it.asset.name.isBlank() || it.asset.name == it.asset.code) q.asset else it.asset) } }
            val daily = withContext(Dispatchers.IO) { runCatching { data.daily(asset, 600) } }
            update(asset) {
                it.copy(
                    loading = false,
                    daily = daily.getOrDefault(emptyList()),
                    candles = daily.getOrDefault(emptyList()),
                    error = quote.exceptionOrNull()?.let(::err)?.takeIf { daily.isFailure } ?: daily.exceptionOrNull()?.let(::err),
                )
            }
            daily.getOrNull()?.takeIf { it.size > 40 }?.let { analyze(asset, it, quote.getOrNull()) }
        }
    }

    private inline fun update(asset: Asset, f: (DetailState) -> DetailState) {
        val d = detail ?: return
        if (d.asset.key == asset.key) detail = f(d)
    }

    private fun backtestConfig(asset: Asset) = store.backtest.copy(roundTripCostPct = store.fees.roundTripPct(asset.market))

    private suspend fun analyze(asset: Asset, daily: List<Candle>, quote: Quote?) {
        update(asset) { it.copy(analyzing = true) }
        val (report, forecast) = withContext(Dispatchers.Default) {
            val r = IndicatorReport.build(PriceData(daily), backtestConfig(asset))
            r to Forecaster.forecast(daily, report = r, quote = quote)
        }
        update(asset) { it.copy(report = report, forecast = forecast, analyzing = false, selected = it.selected ?: report.byScore.firstOrNull()) }
    }

    fun refreshQuote() {
        val d = detail ?: return
        viewModelScope.launch {
            val q = withContext(Dispatchers.IO) { runCatching { data.quote(d.asset, 3) }.getOrNull() } ?: return@launch
            update(d.asset) { st ->
                val tfDaily = st.timeframe == Timeframe.DAY && st.candles.isNotEmpty() && q.realtime && q.open > 0
                if (tfDaily) {
                    // 코인 일봉은 한국 시각 오전 9시(UTC 0시)에 시작한다.
                    val day = if (d.asset.market == Market.CRYPTO) java.time.Instant.now().atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    else java.time.LocalDate.now(io.github.jgg113.stocknote.core.KST)
                    st.copy(quote = q, candles = io.github.jgg113.stocknote.core.CandleMerge.withQuote(st.candles, q, day.epochKst()))
                } else st.copy(quote = q)
            }
        }
    }

    fun setTimeframe(tf: Timeframe) {
        val d = detail ?: return
        update(d.asset) { it.copy(timeframe = tf, chartError = null) }
        viewModelScope.launch {
            if (tf.intraday) {
                val r = withContext(Dispatchers.IO) { runCatching { data.intraday(d.asset, tf) } }
                update(d.asset) { it.copy(candles = r.getOrDefault(it.candles), chartError = r.exceptionOrNull()?.let(::err), timeframe = if (r.isSuccess) tf else Timeframe.DAY) }
            } else {
                update(d.asset) { it.copy(candles = CandleAggregator.forTimeframe(it.daily, tf)) }
            }
        }
    }

    fun selectStrategy(r: BacktestResult) {
        val d = detail ?: return
        update(d.asset) { it.copy(selected = r) }
    }

    fun loadIssues() {
        val d = detail ?: return
        if (d.issuesLoading || d.news.isNotEmpty() || d.disclosures.isNotEmpty()) return
        update(d.asset) { it.copy(issuesLoading = true) }
        viewModelScope.launch {
            val (news, disc) = withContext(Dispatchers.IO) {
                runCatching { data.newsFor(d.asset) }.getOrDefault(emptyList()) to runCatching { data.disclosures(d.asset) }.getOrDefault(emptyList())
            }
            update(d.asset) { it.copy(news = news, disclosures = disc, issuesLoading = false) }
        }
    }

    fun loadFinancials() {
        val d = detail ?: return
        if (d.asset.market != Market.KR || d.financials != null || d.financialsError != null) return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (!keys.hasDart) throw io.github.jgg113.stocknote.core.DataException("재무정보를 보려면 [설정]에서 DART 인증키(무료)를 넣으세요.")
                    data.financials(d.asset) ?: throw io.github.jgg113.stocknote.core.DataException("DART 에서 이 종목의 재무 자료를 찾지 못했습니다.")
                }
            }
            update(d.asset) { it.copy(financials = r.getOrNull(), financialsError = r.exceptionOrNull()?.let(::err)) }
        }
    }

    /** AI 해설 (요약·이슈·질문). 앱 넘기기 방식이면 질문 글을 돌려준다. */
    fun askAi(mode: StockPrompt.Mode, question: String = "", handoff: (AiProvider, String) -> Unit) {
        val d = detail ?: return
        val provider = store.aiProvider
        val prompt = StockPrompt.build(d.asset, d.quote, d.daily, d.forecast, d.report, d.financials, d.disclosures + d.news, mode, question)
        if (provider == AiProvider.NONE) {
            update(d.asset) { it.copy(aiError = "[설정] → AI 해설에서 Gemini 무료 키 등을 고르면 AI 해설을 받을 수 있습니다.") }
            return
        }
        if (provider.isAppHandoff) { handoff(provider, prompt); return }
        val engine = AiEngines.create(provider, store.aiKey(provider))
        if (engine == null) {
            update(d.asset) { it.copy(aiError = "[설정]에서 ${provider.label} 키를 넣으세요.") }
            return
        }
        update(d.asset) { it.copy(aiLoading = true, aiError = null) }
        viewModelScope.launch {
            if (mode != StockPrompt.Mode.SUMMARY && d.news.isEmpty()) loadIssues()
            val r = withContext(Dispatchers.IO) { runCatching { engine.generate(prompt) } }
            update(d.asset) { it.copy(aiLoading = false, aiText = r.getOrNull()?.let { a -> AiText.plain(a.text) + "\n\n(${a.model})" }, aiError = r.exceptionOrNull()?.let(::err)) }
        }
    }

    // ───────── 관심종목 ─────────

    private fun saveWatch(list: List<Watchlist>) {
        watchlists = list
        store.watchlists = list
    }

    fun isWatched(asset: Asset) = watchlists.any { w -> w.assets.any { it.key == asset.key } }

    fun toggleWatch(asset: Asset, listId: String? = null) {
        val target = listId ?: watchlists.first().id
        if (listId == null && isWatched(asset)) {
            saveWatch(watchlists.map { w -> w.copy(assets = w.assets.filterNot { it.key == asset.key }) })
            message("관심종목에서 뺐습니다.")
        } else {
            saveWatch(watchlists.map { w -> if (w.id == target && w.assets.none { it.key == asset.key }) w.copy(assets = w.assets + asset) else w })
            message("관심종목에 넣었습니다.")
        }
    }

    fun removeFromWatch(listId: String, asset: Asset) =
        saveWatch(watchlists.map { w -> if (w.id == listId) w.copy(assets = w.assets.filterNot { it.key == asset.key }) else w })

    fun moveInWatch(listId: String, asset: Asset, delta: Int) = saveWatch(watchlists.map { w ->
        if (w.id != listId) return@map w
        val i = w.assets.indexOfFirst { it.key == asset.key }
        val j = (i + delta).coerceIn(0, w.assets.size - 1)
        if (i < 0 || i == j) w else w.copy(assets = w.assets.toMutableList().also { l -> l.add(j, l.removeAt(i)) })
    })

    fun addWatchlist(name: String) = saveWatch(watchlists + Watchlist(UUID.randomUUID().toString(), name.ifBlank { "목록 ${watchlists.size + 1}" }))

    fun renameWatchlist(id: String, name: String) = saveWatch(watchlists.map { if (it.id == id) it.copy(name = name) else it })

    fun deleteWatchlist(id: String) {
        if (watchlists.size <= 1) { message("목록은 하나 이상 있어야 합니다."); return }
        saveWatch(watchlists.filterNot { it.id == id })
    }

    fun refreshWatch(listId: String) {
        val list = watchlists.firstOrNull { it.id == listId } ?: return
        if (list.assets.isEmpty()) return
        viewModelScope.launch {
            val q = withContext(Dispatchers.IO) { runCatching { data.quotes(list.assets, 4) }.getOrDefault(emptyMap()) }
            watchQuotes = watchQuotes + q
        }
    }

    // ───────── 알림 ─────────

    fun setPriceAlert(alert: PriceAlert?, asset: Asset) {
        val list = priceAlerts.filterNot { it.asset.key == asset.key } + listOfNotNull(alert)
        priceAlerts = list
        store.priceAlerts = list
        if (alert != null) ensureAlertsOn()
    }

    fun toggleSignalAlert(asset: Asset, strategyId: String) {
        val exists = signalAlerts.any { it.asset.key == asset.key && it.strategyId == strategyId }
        val list = if (exists) signalAlerts.filterNot { it.asset.key == asset.key && it.strategyId == strategyId } else signalAlerts + SignalAlert(asset, strategyId)
        signalAlerts = list
        store.signalAlerts = list
        if (!exists) ensureAlertsOn()
        message(if (exists) "신호 알림을 껐습니다." else "다음 신호가 나면 알려 드립니다.")
    }

    fun hasSignalAlert(asset: Asset, strategyId: String) = signalAlerts.any { it.asset.key == asset.key && it.strategyId == strategyId }

    fun removeSignalAlert(a: SignalAlert) { signalAlerts = signalAlerts - a; store.signalAlerts = signalAlerts }

    fun toggleScanAlert() {
        val s = signals
        val exists = scanAlerts.any { it.market == s.market }
        val list = if (exists) scanAlerts.filterNot { it.market == s.market } else scanAlerts + ScanAlert(s.market, s.strategyIds.toList(), s.size)
        scanAlerts = list
        store.scanAlerts = list
        if (!exists) ensureAlertsOn()
        message(if (exists) "${s.market.label} 매매신호 알림을 껐습니다." else "${s.market.label} 매매신호 알림을 켰습니다 (지금 조건 그대로).")
    }

    fun removeScanAlert(a: ScanAlert) { scanAlerts = scanAlerts - a; store.scanAlerts = scanAlerts }

    private fun ensureAlertsOn() {
        if (!alertSettings.enabled) updateAlertSettings(alertSettings.copy(enabled = true))
    }

    // ───────── 매매신호 ─────────

    fun updateSignals(s: SignalsState) {
        signals = s
        store.signalStrategies = s.strategyIds.toList()
    }

    fun runSignals() {
        val s = signals
        if (s.running) return
        val strategies = s.strategyIds.mapNotNull { Strategies.find(it) }
        if (strategies.isEmpty()) { message("전략을 하나 이상 고르세요."); return }
        signals = s.copy(running = true, progress = 0 to s.size, error = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val extra = if (s.includeWatch) watchlists.flatMap { it.assets } else emptyList()
                    val universe = data.universe(s.market, s.size, extra)
                    if (universe.isEmpty()) throw io.github.jgg113.stocknote.core.DataException(noDataHint(s.market))
                    val candles = data.dailyMany(universe, 500) { done, total -> signals = signals.copy(progress = done to total) }
                    if (candles.isEmpty()) throw io.github.jgg113.stocknote.core.DataException("일봉을 받지 못했습니다. " + noDataHint(s.market))
                    withContext(Dispatchers.Default) {
                        SignalScanner.scan(candles, strategies, { backtestConfig(it) }, lookback = 3, minTrades = 3, portfolioBars = 250)
                    }
                }
            }
            signals = signals.copy(running = false, progress = null, result = r.getOrNull() ?: signals.result, error = r.exceptionOrNull()?.let(::err))
        }
    }

    // ───────── 특징종목 ─────────

    fun loadFeatured(market: Market = featured.market, kind: FeatureKind = featured.kind) {
        featured = featured.copy(market = market, kind = kind, loading = true, error = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val live = if (market == Market.KR && (kind == FeatureKind.VALUE || kind == FeatureKind.VOLUME)) data.krLiveRank(kind == FeatureKind.VALUE) else emptyList()
                    if (live.isNotEmpty()) return@runCatching live.map { FeaturedRow(it, it.value, if (kind == FeatureKind.VALUE) io.github.jgg113.stocknote.core.Fmt.big(it.value) else io.github.jgg113.stocknote.core.Fmt.volume(it.volume)) } to true
                    val quotes = data.marketQuotes(market)
                    if (quotes.isEmpty()) throw io.github.jgg113.stocknote.core.DataException(noDataHint(market))
                    val needsHistory = kind == FeatureKind.VOLUME_SURGE || ((kind == FeatureKind.NEW_HIGH || kind == FeatureKind.NEW_LOW) && quotes.none { it.high52 != null })
                    val history = if (needsHistory) {
                        // 저장해 둔 일봉만 쓴다 (전 종목을 새로 받으면 너무 오래 걸린다).
                        quotes.associate { it.asset.key to data.cachedDaily(it.asset) }.filterValues { it.size >= 20 }
                    } else emptyMap()
                    MarketStats.featured(kind, quotes, history, limit = 100, minValue = if (kind == FeatureKind.GAINERS || kind == FeatureKind.LOSERS) minValue(market) else 0.0) to false
                }
            }
            if (featured.market == market && featured.kind == kind) {
                featured = featured.copy(loading = false, rows = r.getOrNull()?.first.orEmpty(), live = r.getOrNull()?.second == true, error = r.exceptionOrNull()?.let(::err))
            }
        }
    }

    // ───────── 종목필터 ─────────

    fun updateScreener(s: ScreenerState) {
        screener = s
        store.screener = s.filter
    }

    fun runScreener() {
        val s = screener
        screener = s.copy(running = true, error = null, progress = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val quotes = data.marketQuotes(s.market)
                    if (quotes.isEmpty()) throw io.github.jgg113.stocknote.core.DataException(noDataHint(s.market))
                    val history = if (s.filter.needsHistory) {
                        // 기록이 필요한 조건은 먼저 시세 조건으로 줄인 뒤 상위 150 종목만 일봉을 받는다.
                        val pre = Screener.run(s.filter.copy(aboveMa = null, belowMa = null, rsiMin = null, rsiMax = null, goldenCrossDays = null, nearHighPct = null, volumeRatioMin = null, strategyId = null), quotes, emptyMap())
                        val targets = pre.take(150).map { it.quote.asset }
                        data.dailyMany(targets, 300) { d, t -> screener = screener.copy(progress = d to t) }.mapKeys { it.key.key }
                    } else emptyMap()
                    withContext(Dispatchers.Default) { Screener.run(s.filter, quotes, history) }
                }
            }
            screener = screener.copy(running = false, progress = null, rows = r.getOrNull(), error = r.exceptionOrNull()?.let(::err))
        }
    }

    // ───────── 테마 ─────────

    fun loadThemes(market: Market = themes.market) {
        themes = themes.copy(market = market, loading = true, error = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val list = allThemes.filter { it.market == market || (it.custom && it.members.isEmpty()) }
                    val members = list.flatMap { it.members }.distinct().mapNotNull { k -> Asset.parseKey(k)?.let { (m, c) -> Asset(m, c, "") } }
                    val quotes: Map<String, Quote> = if (market == Market.KR && keys.hasPublic) data.krSnapshot().associateBy { it.asset.key }
                    else if (market == Market.CRYPTO) data.marketQuotes(Market.CRYPTO).associateBy { it.asset.key }
                    else data.quotes(members, 120)
                    if (quotes.isEmpty()) throw io.github.jgg113.stocknote.core.DataException(noDataHint(market))
                    ThemeStats.rank(list, quotes)
                }
            }
            if (themes.market == market) themes = themes.copy(loading = false, ranking = r.getOrDefault(emptyList()), error = r.exceptionOrNull()?.let(::err))
        }
    }

    fun themeQuotes(theme: Theme, onResult: (List<Quote>) -> Unit) {
        viewModelScope.launch {
            val assets = theme.members.mapNotNull { k -> Asset.parseKey(k)?.let { (m, c) -> Asset(m, c, "") } }
            val q = withContext(Dispatchers.IO) {
                if (assets.all { it.market == Market.KR } && keys.hasPublic && !keys.hasKis) {
                    val snap = runCatching { data.krSnapshot() }.getOrDefault(emptyList()).associateBy { it.asset.key }
                    assets.mapNotNull { snap[it.key] }
                } else runCatching { data.quotes(assets, 30) }.getOrDefault(emptyMap()).values.toList()
            }
            onResult(q.sortedByDescending { it.changeRate })
        }
    }

    private fun saveThemes(list: List<Theme>) {
        customThemes = list
        store.customThemes = list
    }

    fun createTheme(name: String, members: List<String> = emptyList()): Theme {
        val t = Theme("custom_" + UUID.randomUUID().toString().take(8), name.ifBlank { "내 테마" }, "직접 만든 테마", members, custom = true)
        saveThemes(listOf(t) + customThemes)
        return t
    }

    /** 기본 테마를 고치면 같은 이름의 내 테마로 복사해 고친다. */
    fun editableCopy(theme: Theme): Theme = if (theme.custom) theme else createTheme(theme.name + " (내 것)", theme.members)

    fun addThemeMember(themeId: String, asset: Asset) {
        saveThemes(customThemes.map { if (it.id == themeId && asset.key !in it.members) it.copy(members = it.members + asset.key) else it })
        message("${asset.name} 을(를) 테마에 넣었습니다.")
    }

    fun removeThemeMember(themeId: String, key: String) = saveThemes(customThemes.map { if (it.id == themeId) it.copy(members = it.members - key) else it })

    fun deleteTheme(themeId: String) = saveThemes(customThemes.filterNot { it.id == themeId })

    fun themeById(id: String): Theme? = allThemes.firstOrNull { it.id == id }

    // ───────── 모의투자·보유 ─────────

    private fun saveAccounts(list: List<Account>) {
        accounts = list
        store.accounts = list
    }

    fun account(id: String) = accounts.firstOrNull { it.id == id }

    fun refreshAccounts() {
        val assets = accounts.flatMap { a -> a.holdings.map { it.asset } }.distinctBy { it.key }
        viewModelScope.launch {
            val q = withContext(Dispatchers.IO) { runCatching { data.quotes(assets, 10) }.getOrDefault(emptyMap()) }
            accountPrices = accountPrices + q.mapValues { it.value.price }
            if (usdKrw == null) usdKrw = withContext(Dispatchers.IO) { runCatching { data.usdKrw() }.getOrNull() }
        }
    }

    fun trade(accountId: String, asset: Asset, buy: Boolean, qty: Double, price: Double): Boolean {
        val acc = account(accountId) ?: return false
        return try {
            val time = System.currentTimeMillis() / 1000
            val next = if (buy) PaperBroker.buy(acc, asset, qty, price, store.fees, time) else PaperBroker.sell(acc, asset, qty, price, store.fees, time)
            saveAccounts(accounts.map { if (it.id == accountId) next else it })
            accountPrices = accountPrices + (asset.key to price)
            message(if (acc.type == AccountType.MANUAL) "보유 종목을 고쳤습니다." else "${asset.name} ${io.github.jgg113.stocknote.core.Fmt.quantity(PaperBroker.normalizeQty(asset.market, qty), asset.market)} ${if (buy) "매수" else "매도"} 완료")
            true
        } catch (e: TradeException) {
            message(err(e)); false
        }
    }

    fun deposit(accountId: String, currency: Currency, amount: Double) {
        saveAccounts(accounts.map { if (it.id == accountId) PaperBroker.deposit(it, currency, amount) else it })
        message("가상 시드를 넣었습니다.")
    }

    fun resetAccount(accountId: String) {
        saveAccounts(accounts.map { a ->
            if (a.id != accountId) a else if (a.type == AccountType.PAPER) Account.paper(a.id, a.name) else Account.manual(a.id, a.name)
        })
        message("처음 상태로 되돌렸습니다.")
    }

    fun addAccount(type: AccountType, name: String) {
        val id = UUID.randomUUID().toString().take(8)
        saveAccounts(accounts + if (type == AccountType.PAPER) Account.paper(id, name.ifBlank { "모의투자 ${accounts.size + 1}" }) else Account.manual(id, name.ifBlank { "보유 ${accounts.size + 1}" }))
    }

    fun deleteAccount(id: String) {
        if (accounts.size <= 1) return
        saveAccounts(accounts.filterNot { it.id == id })
    }

    // ───────── 차트게임 ─────────

    fun setGameMarket(m: Market) { game = game.copy(market = m) }

    private suspend fun randomHistory(market: Market, minBars: Int): Pair<Asset, List<Candle>>? = withContext(Dispatchers.IO) {
        // 저장해 둔 일봉 중에서 먼저 고르고, 없으면 대표 종목을 받는다.
        val pool = runCatching { data.universe(market, 40) }.getOrDefault(emptyList()).shuffled()
        val cached = pool.firstOrNull { data.cachedDaily(it).size >= minBars }
        val asset = cached ?: pool.firstOrNull() ?: return@withContext null
        val candles = runCatching { data.daily(asset, 800, live = false) }.getOrNull() ?: return@withContext null
        val named = if (asset.name.isBlank()) runCatching { data.quote(asset, 3600).asset }.getOrDefault(asset) else asset
        named to candles
    }

    fun newGame() {
        val m = game.market
        game = game.copy(loading = true, error = null, quiz = null, quizAnswered = null)
        viewModelScope.launch {
            val h = randomHistory(m, 220)
            val g = h?.let { (a, c) -> ChartGame.start(a.name.ifBlank { a.code }, a.key, c, 120, 40, Random.Default, store.fees.roundTripPct(m)) }
            game = game.copy(loading = false, game = g, error = if (g == null) "게임할 차트를 받지 못했습니다. " + noDataHint(m) else null)
        }
    }

    fun updateGame(g: ChartGame) {
        val wasFinished = game.game?.finished == true
        game = game.copy(game = g)
        if (g.finished && !wasFinished) {
            gameStats = gameStats.record(g)
            store.gameStats = gameStats
        }
    }

    fun newQuiz() {
        val m = game.market
        game = game.copy(loading = true, error = null, game = null, quizAnswered = null)
        viewModelScope.launch {
            val h = randomHistory(m, 140)
            val q = h?.let { (a, c) -> UpDownQuiz.start(a.name.ifBlank { a.code }, a.key, c, 100, 10) }
            game = game.copy(loading = false, quiz = q, error = if (q == null) "퀴즈 차트를 받지 못했습니다. " + noDataHint(m) else null)
        }
    }

    fun answerQuiz(up: Boolean) {
        val q = game.quiz ?: return
        if (game.quizAnswered != null) return
        val correct = q.answerUp == up
        game = game.copy(quizAnswered = correct)
        gameStats = gameStats.recordQuiz(correct)
        store.gameStats = gameStats
    }

    fun closeGame() { game = game.copy(game = null, quiz = null, quizAnswered = null) }

    // ───────── 단일 전략 백테스트 (상세 화면 재계산) ─────────

    fun rerunBacktest(stopLoss: Double?, takeProfit: Double?) {
        store.backtest = store.backtest.copy(stopLossPct = stopLoss, takeProfitPct = takeProfit)
        val d = detail ?: return
        if (d.daily.size < 40) return
        viewModelScope.launch { analyze(d.asset, d.daily, d.quote) }
    }

    fun singleBacktest(asset: Asset, candles: List<Candle>, strategyId: String): BacktestResult? =
        Strategies.find(strategyId)?.let { Backtester.run(it, PriceData(candles), backtestConfig(asset)) }

    companion object {
        val DEFAULT_SIGNAL_STRATEGIES = setOf("ma5_20", "macd_signal", "rsi30", "bb_lower", "donchian20", "volume_spike", "ichimoku_tk", "stoch20")
    }
}
