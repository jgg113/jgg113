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
    data class StrategyDetail(val asset: Asset, val strategyId: String) : Screen
}

/** 종목 화면의 AI 예측 한 기간 */
data class AiUiState(
    val loading: Boolean = false,
    val progress: String = "",
    val result: io.github.jgg113.stocknote.core.AiForecastResult? = null,
    val error: String? = null,
)

/** 설정 화면의 AI [연결 테스트] 결과 */
data class AiTestState(val loading: Boolean = false, val ok: Boolean = false, val text: String = "", val model: String = "", val error: String = "")

/** 미리 학습된 Kronos 모델의 상태 */
data class KronosState(
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0,
    val running: Boolean = false,
    val progress: String = "",
    val result: io.github.jgg113.stocknote.core.KronosResult? = null,
    /** 결과가 어느 종목 것인지 */
    val resultKey: String? = null,
    val validating: Boolean = false,
    val validateProgress: Pair<Int, Int>? = null,
    val error: String? = null,
) {
    val totalBytes: Long get() = io.github.jgg113.stocknote.core.KronosModel.MODEL_BYTES + io.github.jgg113.stocknote.core.KronosModel.TOKENIZER_BYTES
}

/** 발굴 → AI 순위 */
data class AiRankState(
    val market: Market = Market.KR,
    val horizon: io.github.jgg113.stocknote.core.AiHorizon = io.github.jgg113.stocknote.core.AiHorizon.SHORT,
    val size: Int = 40,
    val running: Boolean = false,
    val progress: String = "",
    val rows: List<io.github.jgg113.stocknote.core.AiRankRow> = emptyList(),
    val reliability: io.github.jgg113.stocknote.core.Reliability? = null,
    val models: List<io.github.jgg113.stocknote.core.ModelReport> = emptyList(),
    val effectiveSamples: Int = 0,
    val error: String? = null,
    /** '시장 가운데보다 잘할 확률'로 줄을 세웠는지 */
    val rankedByRelative: Boolean = false,
    val relAuc: Double = Double.NaN,
    val relSe: Double = Double.NaN,
)

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
    /** 시장 심리 (0 공포 ~ 100 탐욕) */
    val mood: io.github.jgg113.stocknote.core.MarketMood? = null,
    /** 히트맵에 그릴 큰 종목들 */
    val heat: List<Quote> = emptyList(),
    val fearGreed: io.github.jgg113.stocknote.core.FearGreed? = null,
    val kimchi: List<io.github.jgg113.stocknote.core.Premium> = emptyList(),
    val kimchiSource: String = "",
)

/** AI 에게 물은 글 하나의 상태 (시장 브리핑·포트폴리오 진단) */
data class BriefState(val loading: Boolean = false, val text: String? = null, val error: String? = null)

/** 계좌 하나의 포트폴리오 진단 */
data class DiagState(
    val loading: Boolean = false,
    val result: io.github.jgg113.stocknote.core.PortfolioDiagnosis? = null,
    val error: String? = null,
    val totalKrw: Double = 0.0,
    val pnlPct: Double = 0.0,
    val ai: BriefState = BriefState(),
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
    /** 종합 탭: 수급·투자의견(한국투자증권), 김치 프리미엄(코인) */
    val extraLoading: Boolean = false,
    val extraLoaded: Boolean = false,
    val flows: List<io.github.jgg113.stocknote.core.InvestorFlow> = emptyList(),
    val flowsError: String? = null,
    val opinions: List<io.github.jgg113.stocknote.core.AnalystOpinion> = emptyList(),
    val premium: io.github.jgg113.stocknote.core.Premium? = null,
    val premiumSource: String = "",
    /** 재무 탭: 지분 변동·배당 (DART). null 이면 아직 받지 않음 */
    val ownership: List<io.github.jgg113.stocknote.core.OwnershipReport>? = null,
    val ownershipError: String? = null,
    val dividend: io.github.jgg113.stocknote.core.DividendInfo? = null,
)

data class SignalsState(
    val market: Market = Market.KR,
    /** 관심종목만 (시장 무관) */
    val watchOnly: Boolean = false,
    val size: Int = 50,
    val strategyIds: Set<String> = emptySet(),
    /** 참이면 고른 전략만, 거짓이면 아래 기간의 전략 전부 */
    val useCustom: Boolean = false,
    val horizon: io.github.jgg113.stocknote.core.StrategyHorizon? = null,
    /** 결과 보기: null 전체, 매수, 매도 */
    val side: io.github.jgg113.stocknote.core.Signal? = null,
    /** 0 누적수익률, 1 승률, 2 신뢰도 */
    val sort: Int = 0,
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
    var aiHorizon by mutableStateOf(io.github.jgg113.stocknote.core.AiHorizon.SHORT)
    var ai by mutableStateOf<Map<io.github.jgg113.stocknote.core.AiHorizon, AiUiState>>(emptyMap()); private set
    var aiShowOnChart by mutableStateOf(true)
    var aiRank by mutableStateOf(AiRankState()); private set
    private val kronosDir = java.io.File(app.filesDir, "models")
    var kronos by mutableStateOf(KronosState(installed = io.github.jgg113.stocknote.core.KronosModel.installed(kronosDir))); private set
    var kronosValidations by mutableStateOf(store.kronosValidations); private set
    @Volatile private var kronosModel: io.github.jgg113.stocknote.core.KronosModel? = null
    /** 시장·기간별로 학습한 모델 (하루 동안 다시 쓴다) */
    private val ensembles = java.util.concurrent.ConcurrentHashMap<String, Pair<io.github.jgg113.stocknote.core.AiEnsemble, java.time.LocalDate>>()
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

    /**
     * 키는 넣는 즉시 저장한다. ([저장]을 따로 눌러야만 반영되던 탓에 키를 넣고도 연결되지 않던 문제를 없앤다.)
     * 붙여넣다 섞인 빈칸·줄바꿈·보이지 않는 글자는 걷어 낸다.
     */
    fun updateKeys(k: DataKeys) {
        val c = k.cleaned()
        if (c == keys) return
        store.keys = c
        keys = c
        _data = null
        keysChanged = true
        checks = null
    }

    /** 설정 화면을 떠날 때: 키가 바뀌었으면 새 키로 시세를 다시 받는다. */
    fun applyKeyChanges() {
        if (!keysChanged) return
        keysChanged = false
        refreshHome()
    }

    private var keysChanged = false

    fun saveIndicators(list: List<IndicatorConfig>) {
        indicators = list
        store.indicators = list
    }

    fun updateAlertSettings(s: AlertSettings) {
        alertSettings = s
        store.alertSettings = s
        AlertWorker.schedule(getApplication(), s)
    }

    /** 지금 저장된 키로 각 서버에 실제로 물어본다. */
    fun runChecks() {
        if (checking) return
        checking = true
        checks = null
        val used = data
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { used.check() }.getOrElse { listOf(SourceStatus("점검", false, err(it))) } }
            // 한국투자증권 키가 반대쪽 서버(실전↔모의) 것으로 확인되면 설정을 맞춰 준다.
            list.firstNotNullOfOrNull { it.switchKisVirtual }?.let { v ->
                if (keys.kisVirtual != v) {
                    val k = keys.copy(kisVirtual = v)
                    store.keys = k
                    keys = k
                    _data = null
                }
            }
            checks = list
            checking = false
            keysChanged = false
            refreshHome()
        }
    }

    /** 점검 결과를 글로 (도움을 청할 때 붙여넣을 수 있게). 키 자체는 넣지 않고 길이만 적는다. */
    fun checksText(): String = buildString {
        appendLine("주식노트 ${io.github.jgg113.stocknote.BuildConfig.VERSION_NAME} 연결 점검")
        checks.orEmpty().forEach { s -> appendLine("${if (s.ok) "[정상]" else if (s.unused) "[키 없음]" else "[실패]"} ${s.name}: ${s.detail}") }
        val k = keys
        appendLine("키 길이: 공공데이터포털 ${k.dataGoKr.length}자, 한국투자 앱키 ${k.kisAppKey.length}자·시크릿 ${k.kisAppSecret.length}자 (${if (k.kisVirtual) "모의" else "실전"}), DART ${k.dart.length}자, Twelve Data ${k.twelveData.length}자")
        append("AI: ${aiProvider.label}")
        if (aiProvider.isApi) append(" (키 ${aiKey(aiProvider).length}자)")
        aiTest?.let { t -> append(" · 테스트 ${if (t.loading) "중" else if (t.ok) "정상 (${t.model})" else "실패: ${t.error}"}") }
    }

    // ───────── AI 해설 설정 ─────────

    var aiProvider by mutableStateOf(store.aiProvider); private set
    var aiKeys by mutableStateOf(AiProvider.entries.filter { it.isApi }.associateWith { store.aiKey(it) }); private set
    var aiTest by mutableStateOf<AiTestState?>(null); private set

    /** 고르는 즉시 저장한다. */
    fun chooseAiProvider(p: AiProvider) {
        aiProvider = p
        store.aiProvider = p
        aiTest = null
    }

    /** 넣는 즉시 저장한다. */
    fun updateAiKey(p: AiProvider, raw: String) {
        val key = io.github.jgg113.stocknote.core.KeyText.clean(raw)
        if (key == aiKeys[p]) return
        store.saveAiKey(p, key)
        aiKeys = aiKeys + (p to key)
        aiTest = null
    }

    /** 짧은 질문 하나를 보내 키가 되는지 본다. */
    fun testAi() {
        val p = aiProvider
        val engine = AiEngines.create(p, aiKey(p))
        if (engine == null) {
            aiTest = AiTestState(error = "키를 먼저 넣으세요.")
            return
        }
        aiTest = AiTestState(loading = true)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { engine.generate(io.github.jgg113.stocknote.core.AiTest.PROMPT) } }
            if (aiProvider != p) return@launch
            aiTest = r.fold({ AiTestState(ok = true, text = AiText.plain(it.text).take(240), model = it.model) }, { AiTestState(error = err(it)) })
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) { data.clearCache() }
        message("저장해 둔 시세를 지웠습니다.")
    }

    fun cacheSize(): Long = cache.sizeBytes()

    fun aiKey(p: AiProvider): String = aiKeys[p].orEmpty()

    // ───────── 시장 요약 ─────────

    fun setHomeMarket(m: Market) {
        store.homeMarket = m
        home = HomeState(market = m)
        brief = BriefState()
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
                // 시장 심리·히트맵: 거래가 거의 없는 종목은 빼고 본다. 코인은 비트코인 일봉을 지수 삼아 쓴다.
                val liquid = quotes.filter { it.value >= minValue(market) && !MarketStats.isSpacOrPreferred(it.asset.name) }
                val indexCandles = if (market == Market.CRYPTO) runCatching { data.daily(Asset(Market.CRYPTO, "KRW-BTC", "비트코인"), 300) }.getOrNull() else null
                val fearGreed = if (market == Market.CRYPTO) runCatching { data.fearGreed() }.getOrNull() else null
                val kimchi = if (market == Market.CRYPTO && quotes.isNotEmpty()) runCatching { data.kimchi(quotes.sortedByDescending { it.value }.take(30)) }.getOrNull() else null
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
                    mood = io.github.jgg113.stocknote.core.MarketMoodCalc.of(liquid, indexCandles),
                    heat = liquid.sortedByDescending { if (it.marketCap > 0) it.marketCap else it.value }.take(30),
                    fearGreed = fearGreed,
                    kimchi = kimchi?.first.orEmpty(),
                    kimchiSource = kimchi?.second.orEmpty(),
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
        ai = emptyMap()
        detailJob = viewModelScope.launch {
            val quote = withContext(Dispatchers.IO) { runCatching { data.quote(asset) } }
            quote.getOrNull()?.let { q -> update(asset) { it.copy(quote = q, asset = if (it.asset.name.isBlank() || it.asset.name == it.asset.code) q.asset else it.asset) } }
            val daily = withContext(Dispatchers.IO) { runCatching { data.daily(asset, 1500) } }
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
        val provider = aiProvider
        val prompt = StockPrompt.build(d.asset, d.quote, d.daily, d.forecast, d.report, d.financials, d.disclosures + d.news, mode, question, ai[aiHorizon]?.result)
        if (provider == AiProvider.NONE) {
            update(d.asset) { it.copy(aiError = "[설정] → AI 해설에서 Gemini 무료 키 등을 고르면 AI 해설을 받을 수 있습니다.") }
            return
        }
        if (provider.isAppHandoff) { handoff(provider, prompt); return }
        val engine = AiEngines.create(provider, aiKey(provider))
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

    // ───────── AI 예측 ─────────

    private fun aiPoolSize(m: Market) = when (m) { Market.KR -> 25; Market.CRYPTO -> 20; Market.US -> if (keys.hasKis) 20 else 8 }

    /** 같은 시장 대표 종목들의 일봉(약 6년)을 모아 학습 자료로 쓴다. 저장해 둔 것은 다시 받지 않는다. */
    private fun buildPool(market: Market, size: Int, extra: List<Pair<Asset, List<Candle>>>, progress: (String) -> Unit): List<Pair<Asset, List<Candle>>> {
        val assets = data.universe(market, size).filter { a -> extra.none { it.first.key == a.key } }
        val got = data.dailyMany(assets, 1500) { d, t -> progress("학습할 종목 시세 받는 중 $d/$t") }
        return extra + got.map { it.key to it.value }.filter { it.second.size >= io.github.jgg113.stocknote.core.AiFeatures.WARMUP + 60 }
    }

    private fun ensemble(market: Market, h: io.github.jgg113.stocknote.core.AiHorizon, pool: () -> List<Pair<Asset, List<Candle>>>, progress: (String) -> Unit): io.github.jgg113.stocknote.core.AiEnsemble? {
        val key = "${market.name}:${h.name}"
        val today = java.time.LocalDate.now(io.github.jgg113.stocknote.core.KST)
        ensembles[key]?.let { (e, day) -> if (day == today) return e }
        val p = pool()
        progress("AI 모델 학습 중 (${p.size}종목)")
        val e = io.github.jgg113.stocknote.core.AiForecaster.train(p, h) { progress(it) } ?: return null
        ensembles[key] = e to today
        return e
    }

    /** 종목 화면의 AI 예측 (기간별) */
    fun loadAi(h: io.github.jgg113.stocknote.core.AiHorizon = aiHorizon, force: Boolean = false) {
        aiHorizon = h
        val d = detail ?: return
        val cur = ai[h]
        if (!force && (cur?.loading == true || cur?.result != null)) return
        if (d.daily.size < io.github.jgg113.stocknote.core.AiFeatures.WARMUP + 20) {
            ai = ai + (h to AiUiState(error = "AI 예측에는 일봉이 1년 넘게(${io.github.jgg113.stocknote.core.AiFeatures.WARMUP + 20}개 이상) 있어야 합니다. 지금 ${d.daily.size}개입니다."))
            return
        }
        if (force) ensembles.remove("${d.asset.market.name}:${h.name}")
        val asset = d.asset
        ai = ai + (h to AiUiState(loading = true, progress = "준비 중"))
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val progress = { text: String -> if (detail?.asset?.key == asset.key) ai = ai + (h to AiUiState(loading = true, progress = text)) }
                    val e = ensemble(asset.market, h, { buildPool(asset.market, aiPoolSize(asset.market), listOf(asset to d.daily), progress) }, progress)
                        ?: throw io.github.jgg113.stocknote.core.DataException("학습할 자료가 모자랍니다. ${h.label} 예측은 종목마다 ${io.github.jgg113.stocknote.core.AiFeatures.WARMUP + h.bars}봉 넘는 일봉이 필요합니다.")
                    progress("예측하는 중")
                    io.github.jgg113.stocknote.core.AiForecaster.predict(e, asset, d.daily, d.quote?.price ?: d.daily.last().close, kronosExternal(asset, h))
                        ?: throw io.github.jgg113.stocknote.core.DataException("이 종목의 최근 지표를 계산하지 못했습니다.")
                }
            }
            if (detail?.asset?.key == asset.key) ai = ai + (h to AiUiState(result = r.getOrNull(), error = r.exceptionOrNull()?.let(::err)))
        }
    }

    fun updateAiRank(s: AiRankState) { aiRank = s }

    /** 발굴 → AI 순위: 같은 모델로 대표 종목 전체를 점수 매긴다. */
    fun runAiRank() {
        val s = aiRank
        if (s.running) return
        aiRank = s.copy(running = true, progress = "준비 중", error = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val progress = { text: String -> aiRank = aiRank.copy(progress = text) }
                    val pool = buildPool(s.market, s.size, emptyList(), progress)
                    if (pool.size < 3) throw io.github.jgg113.stocknote.core.DataException("종목 시세를 충분히 받지 못했습니다. " + noDataHint(s.market))
                    val e = ensemble(s.market, s.horizon, { pool }, progress) ?: throw io.github.jgg113.stocknote.core.DataException("학습할 자료가 모자랍니다.")
                    e to io.github.jgg113.stocknote.core.AiForecaster.rank(e, pool)
                }
            }
            val (e, rows) = r.getOrNull() ?: (null to emptyList())
            aiRank = aiRank.copy(
                running = false, progress = "", rows = rows, reliability = e?.reliability, models = e?.reports.orEmpty(),
                effectiveSamples = e?.effectiveSamples ?: 0, error = r.exceptionOrNull()?.let(::err),
                rankedByRelative = e != null && e.relZ >= 1 && rows.any { !it.beatProb.isNaN() }, relAuc = e?.relAuc ?: Double.NaN, relSe = e?.relSe ?: Double.NaN,
            )
        }
    }

    // ───────── Kronos (미리 학습된 모델) ─────────

    /** 이 시장에서 Kronos 를 검증한 결과 (직접 검증한 것이 있으면 그것, 코인은 개발 때 검증한 값) */
    fun kronosValidation(market: Market): io.github.jgg113.stocknote.data.KronosValidation? =
        kronosValidations.firstOrNull { it.market == market } ?: io.github.jgg113.stocknote.data.KronosValidation.bundledCrypto.takeIf { market == Market.CRYPTO }

    private fun kronosExternal(asset: Asset, h: io.github.jgg113.stocknote.core.AiHorizon): io.github.jgg113.stocknote.core.ExternalModel? {
        if (h != io.github.jgg113.stocknote.core.AiHorizon.SHORT) return null
        val r = kronos.result?.takeIf { kronos.resultKey == asset.key } ?: return null
        val v = kronosValidation(asset.market)
        return io.github.jgg113.stocknote.core.ExternalModel(
            "kronos", "Kronos (사전학습)", "45개 거래소의 캔들 120억 개로 미리 학습된 금융 전용 모델입니다. 앞으로 20일의 봉을 직접 그려 냅니다.",
            r.upProb, r.expectedReturn, v?.auc ?: Double.NaN, v?.se ?: Double.NaN, r.path,
        )
    }

    private fun kronosThreads() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private fun loadKronos(): io.github.jgg113.stocknote.core.KronosModel =
        kronosModel ?: io.github.jgg113.stocknote.core.KronosModel.load(kronosDir).also { kronosModel = it }

    fun downloadKronos() {
        if (kronos.downloading) return
        kronos = kronos.copy(downloading = true, downloadedBytes = 0, error = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val km = io.github.jgg113.stocknote.core.KronosModel
                    val files = listOf(
                        Triple(km.TOKENIZER_URL, java.io.File(kronosDir, km.TOKENIZER_FILE), km.TOKENIZER_BYTES),
                        Triple(km.MODEL_URL, java.io.File(kronosDir, km.MODEL_FILE), km.MODEL_BYTES),
                    )
                    var done = 0L
                    for ((url, file, bytes) in files) {
                        if (!(file.exists() && file.length() == bytes)) {
                            io.github.jgg113.stocknote.data.Downloader.download(url, file, bytes) { n -> kronos = kronos.copy(downloadedBytes = done + n) }
                        }
                        done += bytes
                        kronos = kronos.copy(downloadedBytes = done)
                    }
                    loadKronos()   // 파일이 온전한지 읽어서 확인한다
                }
            }
            kronos = kronos.copy(
                downloading = false, installed = io.github.jgg113.stocknote.core.KronosModel.installed(kronosDir) && r.isSuccess,
                error = r.exceptionOrNull()?.let(::err),
            )
            if (r.isSuccess) message("Kronos 모델을 받았습니다.")
        }
    }

    fun deleteKronos() {
        kronosModel = null
        kronosDir.listFiles()?.forEach { it.delete() }
        kronos = KronosState(installed = false)
        message("Kronos 모델 파일을 지웠습니다.")
    }

    /** 지금 종목의 앞으로 20일을 Kronos 로 예측한다. */
    fun runKronos() {
        val d = detail ?: return
        if (kronos.running || !kronos.installed) return
        if (d.daily.size < 64) { kronos = kronos.copy(error = "Kronos 예측에는 일봉이 64개 이상 필요합니다."); return }
        val asset = d.asset
        kronos = kronos.copy(running = true, progress = "과거 봉을 읽는 중", error = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val steps = io.github.jgg113.stocknote.core.AiHorizon.SHORT.bars
                    loadKronos().forecast(
                        d.daily, steps, io.github.jgg113.stocknote.core.KronosModel.futureDays(d.daily.last().time, steps, asset.market),
                        context = 256, samples = 16, threads = kronosThreads(),
                    ) { done, total -> kronos = kronos.copy(progress = if (done < 2) "과거 봉을 읽는 중" else "앞으로 20일을 16가지 경로로 그리는 중 ($done/$total)") }
                }
            }
            kronos = kronos.copy(running = false, progress = "", result = r.getOrNull() ?: kronos.result, resultKey = if (r.isSuccess) asset.key else kronos.resultKey, error = r.exceptionOrNull()?.let(::err))
            // 검증된 시장이면 종합 의견에 반영되도록 단기 예측을 다시 계산한다 (학습은 다시 하지 않는다).
            if (r.isSuccess && detail?.asset?.key == asset.key && ai[io.github.jgg113.stocknote.core.AiHorizon.SHORT]?.result != null) {
                ai = ai - io.github.jgg113.stocknote.core.AiHorizon.SHORT
                loadAi(io.github.jgg113.stocknote.core.AiHorizon.SHORT)
            }
        }
    }

    /**
     * 지금 보는 종목의 시장에서 Kronos 가 방향을 맞히는지 검증한다.
     * 대표 종목마다 최근 약 10개월을 20일씩 끊어, 그 시점까지의 봉만 주고 20일 뒤 오를지 물어본다.
     * (모델은 2025년에 학습이 끝났으므로 이 구간은 모델이 본 적 없는 자료다.)
     */
    fun validateKronos() {
        val market = detail?.asset?.market ?: return
        if (kronos.validating || !kronos.installed) return
        kronos = kronos.copy(validating = true, validateProgress = 0 to 1, error = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) {
                runCatching {
                    val model = loadKronos()
                    val pool = buildPool(market, 12, emptyList()) { }
                    val horizon = 20
                    val jobs = pool.flatMap { (asset, c) -> ((c.size - 1 - horizon) downTo maxOf(200, c.size - 1 - horizon * 10) step horizon).map { asset to (c to it) } }
                    if (jobs.size < 30) throw io.github.jgg113.stocknote.core.DataException("검증할 자료가 모자랍니다. " + noDataHint(market))
                    val done = java.util.concurrent.atomic.AtomicInteger(0)
                    val exec = java.util.concurrent.Executors.newFixedThreadPool(kronosThreads())
                    val rows = try {
                        exec.invokeAll(jobs.map { (asset, pair) ->
                            java.util.concurrent.Callable {
                                val (c, end) = pair
                                val window = c.subList(0, end + 1)
                                val f = model.forecast(window, horizon, io.github.jgg113.stocknote.core.KronosModel.futureDays(window.last().time, horizon, asset.market), context = 128, samples = 8, threads = 1, seed = end.toLong())
                                kronos = kronos.copy(validateProgress = done.incrementAndGet() to jobs.size)
                                Triple(f.upProb, c[end + horizon].close > c[end].close, c[end].time)
                            }
                        }).map { it.get() }
                    } finally { exec.shutdown() }
                    val up = rows.map { it.second }.toBooleanArray()
                    val prob = rows.map { it.first }.toDoubleArray()
                    val frac = up.count { it }.toDouble() / up.size
                    // 같은 기간의 종목들은 함께 움직이므로, 20일 구간을 한 덩어리로 묶어 통계 오차를 잰다.
                    val t0 = rows.minOf { it.third }
                    val block = rows.map { ((it.third - t0) / (horizon * 86_400L * if (market == Market.CRYPTO) 1.0 else 1.45)).toInt() }.toIntArray()
                    io.github.jgg113.stocknote.data.KronosValidation(
                        market, io.github.jgg113.stocknote.core.Metrics.auc(prob, up),
                        io.github.jgg113.stocknote.core.Metrics.blockBootstrapAucSe(prob, up, block, kotlin.random.Random(3)),
                        rows.size, prob.average(), frac, System.currentTimeMillis() / 1000,
                    )
                }
            }
            r.getOrNull()?.let { v ->
                kronosValidations = kronosValidations.filterNot { it.market == v.market } + v
                store.kronosValidations = kronosValidations
            }
            kronos = kronos.copy(validating = false, validateProgress = null, error = r.exceptionOrNull()?.let(::err))
            if (r.isSuccess && ai[io.github.jgg113.stocknote.core.AiHorizon.SHORT]?.result != null && kronos.resultKey == detail?.asset?.key) {
                ai = ai - io.github.jgg113.stocknote.core.AiHorizon.SHORT
                loadAi(io.github.jgg113.stocknote.core.AiHorizon.SHORT)
            }
        }
    }

    fun kronosFileBytes(): Long = kronosDir.listFiles()?.sumOf { it.length() } ?: 0

    // ───────── 백업·복원 ─────────

    fun exportBackup(): String = store.exportBackup()

    fun importBackup(text: String): Boolean = try {
        store.importBackup(text)
        keys = store.keys; _data = null; checks = null
        aiProvider = store.aiProvider; aiKeys = AiProvider.entries.filter { it.isApi }.associateWith { store.aiKey(it) }; aiTest = null
        watchlists = store.watchlists; customThemes = store.customThemes; accounts = store.accounts; gameStats = store.gameStats
        indicators = store.indicators; priceAlerts = store.priceAlerts; signalAlerts = store.signalAlerts; scanAlerts = store.scanAlerts
        alertSettings = store.alertSettings
        AlertWorker.schedule(getApplication(), alertSettings)
        screener = screener.copy(filter = store.screener)
        message("백업에서 복원했습니다.")
        refreshHome()
        true
    } catch (e: Exception) {
        message("백업 파일을 읽지 못했습니다: ${e.message?.take(80) ?: e.javaClass.simpleName}")
        false
    }

    // ───────── AI 브리핑 (시장·포트폴리오) ─────────

    var brief by mutableStateOf(BriefState()); private set

    /** 고른 AI 방법으로 글을 묻는다. 앱 넘기기면 그 앱으로 보내고, API 면 답을 [set] 으로 돌려준다. */
    private fun runAi(prompt: String, handoff: (AiProvider, String) -> Unit, set: (BriefState) -> Unit) {
        val provider = aiProvider
        if (provider == AiProvider.NONE) { set(BriefState(error = "[설정] → AI 해설에서 방법을 고르면 쓸 수 있습니다 (Gemini 무료 키, 또는 키 없이 ChatGPT·Gemini·Claude 앱으로 넘기기).")); return }
        if (provider.isAppHandoff) { handoff(provider, prompt); return }
        val engine = AiEngines.create(provider, aiKey(provider))
        if (engine == null) { set(BriefState(error = "[설정]에서 ${provider.label} 키를 넣으세요.")); return }
        set(BriefState(loading = true))
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { engine.generate(prompt) } }
            set(r.fold({ BriefState(text = AiText.plain(it.text) + "\n\n(${it.model})") }, { BriefState(error = err(it)) }))
        }
    }

    /** 오늘 시장을 AI 가 몇 문장으로 정리한다 (앱이 계산한 숫자와 기사 제목만 넘긴다). */
    fun askBrief(handoff: (AiProvider, String) -> Unit) {
        val h = home
        val prompt = io.github.jgg113.stocknote.core.BriefPrompt.market(
            h.market, h.mood, h.breadth, h.indices,
            h.featured[FeatureKind.GAINERS].orEmpty().map { it.quote }, h.featured[FeatureKind.LOSERS].orEmpty().map { it.quote },
            h.news, h.fearGreed, io.github.jgg113.stocknote.core.Kimchi.median(h.kimchi),
        )
        runAi(prompt, handoff) { if (home.market == h.market) brief = it }
    }

    // ───────── 포트폴리오 진단 ─────────

    var diagnoses by mutableStateOf<Map<String, DiagState>>(emptyMap()); private set

    private fun setDiag(id: String, f: (DiagState) -> DiagState) { diagnoses = diagnoses + (id to f(diagnoses[id] ?: DiagState())) }

    /** 계좌의 보유 종목을 지난 1년 일봉으로 진단한다 (쏠림·함께 움직임·변동성). */
    fun diagnose(accountId: String) {
        val acc = account(accountId) ?: return
        if (diagnoses[accountId]?.loading == true) return
        if (acc.holdings.isEmpty()) { setDiag(accountId) { DiagState(error = "보유 종목이 없습니다.") }; return }
        setDiag(accountId) { DiagState(loading = true) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val fx = usdKrw ?: data.usdKrw() ?: 0.0
                    val assets = acc.holdings.map { it.asset }
                    val prices = data.quotes(assets, 60).mapValues { it.value.price }
                    val v = io.github.jgg113.stocknote.core.PortfolioValuation.of(acc, accountPrices + prices, fx)
                    val candles = data.dailyMany(assets, 300).mapKeys { it.key.key }
                    val items = v.rows.map { row ->
                        io.github.jgg113.stocknote.core.PortfolioItem(row.holding.asset, if (row.currency == Currency.USD) row.value * fx else row.value, candles[row.holding.asset.key].orEmpty())
                    }
                    val d = io.github.jgg113.stocknote.core.PortfolioDoctor.diagnose(items, acc.cashKrw + acc.cashUsd * fx)
                        ?: throw io.github.jgg113.stocknote.core.DataException("진단에는 종목마다 일봉이 한 달 넘게 있어야 합니다. 시세를 받지 못한 종목이 있는지 확인하세요.")
                    Triple(d, v.totalKrw, v.pnlPct)
                }
            }
            setDiag(accountId) { r.fold({ (d, total, pnl) -> DiagState(result = d, totalKrw = total, pnlPct = pnl) }, { DiagState(error = err(it)) }) }
        }
    }

    fun askPortfolio(accountId: String, handoff: (AiProvider, String) -> Unit) {
        val acc = account(accountId) ?: return
        val st = diagnoses[accountId] ?: return
        val d = st.result ?: return
        runAi(io.github.jgg113.stocknote.core.BriefPrompt.portfolio(acc.name, d, st.totalKrw, st.pnlPct), handoff) { b -> setDiag(accountId) { it.copy(ai = b) } }
    }

    // ───────── 종합 탭·지분 (종목 화면) ─────────

    /** 종합 탭을 열 때: 수급·투자의견(한국투자증권), 재무(DART), 김치 프리미엄(코인)을 받아 둔다. */
    fun loadSummary() {
        val d = detail ?: return
        val asset = d.asset
        if (asset.market == Market.KR && keys.hasDart) loadFinancials()
        if (d.extraLoaded || d.extraLoading) return
        update(asset) { it.copy(extraLoading = true) }
        viewModelScope.launch {
            var flows = emptyList<io.github.jgg113.stocknote.core.InvestorFlow>()
            var flowsError: String? = null
            var opinions = emptyList<io.github.jgg113.stocknote.core.AnalystOpinion>()
            var premium: io.github.jgg113.stocknote.core.Premium? = null
            var source = ""
            withContext(Dispatchers.IO) {
                if (asset.market == Market.KR && keys.hasKis) {
                    runCatching { data.investorFlows(asset) }.onSuccess { flows = it }.onFailure { flowsError = err(it) }
                    opinions = runCatching { data.analystOpinions(asset) }.getOrDefault(emptyList())
                }
                if (asset.market == Market.CRYPTO) runCatching {
                    val (list, src) = data.kimchi(listOf(data.quote(asset, 30)))
                    premium = list.firstOrNull(); source = src
                }
            }
            update(asset) { it.copy(extraLoading = false, extraLoaded = true, flows = flows, flowsError = flowsError, opinions = opinions, premium = premium, premiumSource = source) }
        }
    }

    /** 재무 탭: 5% 이상 주주·임원의 지분 변동과 배당 (DART) */
    fun loadOwnership() {
        val d = detail ?: return
        if (d.asset.market != Market.KR || !keys.hasDart || d.ownership != null || d.ownershipError != null) return
        viewModelScope.launch {
            val (own, div) = withContext(Dispatchers.IO) { runCatching { data.ownership(d.asset) } to runCatching { data.dividend(d.asset) }.getOrNull() }
            update(d.asset) { it.copy(ownership = own.getOrNull() ?: emptyList(), ownershipError = own.exceptionOrNull()?.let(::err), dividend = div) }
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
        // 지금 화면에서 실제로 쓰는 전략(고른 전략 또는 고른 기간의 전략 전부)을 그대로 알림에 쓴다.
        val ids = if (s.useCustom && s.strategyIds.isNotEmpty()) s.strategyIds.toList() else Strategies.of(s.horizon).map { it.id }
        val list = if (exists) scanAlerts.filterNot { it.market == s.market } else scanAlerts + ScanAlert(s.market, ids, s.size)
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
        val strategies = if (s.useCustom && s.strategyIds.isNotEmpty()) s.strategyIds.mapNotNull { Strategies.find(it) } else Strategies.of(s.horizon)
        if (strategies.isEmpty()) { message("전략을 하나 이상 고르세요."); return }
        signals = s.copy(running = true, progress = 0 to s.size, error = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val universe = if (s.watchOnly) watchlists.flatMap { it.assets }.distinctBy { it.key } else {
                        val extra = if (s.includeWatch) watchlists.flatMap { it.assets } else emptyList()
                        data.universe(s.market, s.size, extra)
                    }
                    if (universe.isEmpty()) throw io.github.jgg113.stocknote.core.DataException(if (s.watchOnly) "관심종목이 없습니다. 먼저 관심종목을 넣으세요." else noDataHint(s.market))
                    val candles = data.dailyMany(universe, 1000) { done, total -> signals = signals.copy(progress = done to total) }
                    if (candles.isEmpty()) throw io.github.jgg113.stocknote.core.DataException("일봉을 받지 못했습니다. " + noDataHint(s.market))
                    withContext(Dispatchers.Default) {
                        SignalScanner.scan(candles, strategies, { backtestConfig(it) }, lookback = 3, signal = null, minTrades = 3, portfolioBars = 250)
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
