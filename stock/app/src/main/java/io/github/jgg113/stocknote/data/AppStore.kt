package io.github.jgg113.stocknote.data

import android.content.Context
import io.github.jgg113.stocknote.core.Account
import io.github.jgg113.stocknote.core.AiProvider
import io.github.jgg113.stocknote.core.Asset
import io.github.jgg113.stocknote.core.BacktestConfig
import io.github.jgg113.stocknote.core.DataCache
import io.github.jgg113.stocknote.core.DataKeys
import io.github.jgg113.stocknote.core.FeeConfig
import io.github.jgg113.stocknote.core.GameStats
import io.github.jgg113.stocknote.core.IndicatorConfig
import io.github.jgg113.stocknote.core.KisToken
import io.github.jgg113.stocknote.core.KisTokenStore
import io.github.jgg113.stocknote.core.Market
import io.github.jgg113.stocknote.core.ScreenerFilter
import io.github.jgg113.stocknote.core.Theme
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Watchlist(val id: String, val name: String, val assets: List<Asset> = emptyList())

/** 가격 알림: 위·아래 가격을 넘거나, 하루 등락률이 기준을 넘으면 알린다. */
@Serializable
data class PriceAlert(
    val asset: Asset,
    val above: Double? = null,
    val below: Double? = null,
    val movePct: Double? = null,
    val lastFired: Long = 0,
)

/** 종목 하나 + 전략 하나의 다음 신호 알림 */
@Serializable
data class SignalAlert(val asset: Asset, val strategyId: String, val lastSignalTime: Long = 0)

/** 매매신호 화면 조건 그대로 새 신호를 알린다 */
@Serializable
data class ScanAlert(
    val market: Market,
    val strategyIds: List<String>,
    val size: Int,
    val notified: List<String> = emptyList(),
)

@Serializable
data class AlertSettings(val enabled: Boolean = false, val intervalMinutes: Int = 30)

/** 설정·관심종목·계좌 등 기기에만 저장한다. */
class AppStore(context: Context) {
    private val prefs = context.getSharedPreferences("stocknote", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun <T> load(key: String, serializer: KSerializer<T>, default: T): T =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: default

    private fun <T> save(key: String, serializer: KSerializer<T>, value: T) {
        prefs.edit().putString(key, json.encodeToString(serializer, value)).apply()
    }

    var keys: DataKeys
        get() = load("keys", DataKeys.serializer(), DataKeys())
        set(value) = save("keys", DataKeys.serializer(), value)

    var aiProvider: AiProvider
        get() = prefs.getString("ai_provider", null)?.let { n -> AiProvider.entries.firstOrNull { it.name == n } } ?: AiProvider.NONE
        set(value) = prefs.edit().putString("ai_provider", value.name).apply()

    fun aiKey(provider: AiProvider): String = prefs.getString("ai_key_${provider.name}", null).orEmpty()
    fun saveAiKey(provider: AiProvider, key: String) = prefs.edit().putString("ai_key_${provider.name}", key.trim()).apply()

    var fees: FeeConfig
        get() = load("fees", FeeConfig.serializer(), FeeConfig())
        set(value) = save("fees", FeeConfig.serializer(), value)

    /** 손절·익절 (백테스트) */
    var backtest: BacktestConfig
        get() = load("backtest", BacktestConfig.serializer(), BacktestConfig())
        set(value) = save("backtest", BacktestConfig.serializer(), value)

    var indicators: List<IndicatorConfig>
        get() = load("indicators", ListSerializer(IndicatorConfig.serializer()), IndicatorConfig.defaults)
        set(value) = save("indicators", ListSerializer(IndicatorConfig.serializer()), value)

    var watchlists: List<Watchlist>
        get() = load("watchlists", ListSerializer(Watchlist.serializer()), listOf(Watchlist("w1", "관심종목")))
        set(value) = save("watchlists", ListSerializer(Watchlist.serializer()), value)

    var customThemes: List<Theme>
        get() = load("themes", ListSerializer(Theme.serializer()), emptyList())
        set(value) = save("themes", ListSerializer(Theme.serializer()), value)

    var accounts: List<Account>
        get() = load("accounts", ListSerializer(Account.serializer()), listOf(Account.paper("paper1"), Account.manual("manual1")))
        set(value) = save("accounts", ListSerializer(Account.serializer()), value)

    var gameStats: GameStats
        get() = load("game_stats", GameStats.serializer(), GameStats())
        set(value) = save("game_stats", GameStats.serializer(), value)

    var priceAlerts: List<PriceAlert>
        get() = load("price_alerts", ListSerializer(PriceAlert.serializer()), emptyList())
        set(value) = save("price_alerts", ListSerializer(PriceAlert.serializer()), value)

    var signalAlerts: List<SignalAlert>
        get() = load("signal_alerts", ListSerializer(SignalAlert.serializer()), emptyList())
        set(value) = save("signal_alerts", ListSerializer(SignalAlert.serializer()), value)

    var scanAlerts: List<ScanAlert>
        get() = load("scan_alerts", ListSerializer(ScanAlert.serializer()), emptyList())
        set(value) = save("scan_alerts", ListSerializer(ScanAlert.serializer()), value)

    var alertSettings: AlertSettings
        get() = load("alert_settings", AlertSettings.serializer(), AlertSettings())
        set(value) = save("alert_settings", AlertSettings.serializer(), value)

    var screener: ScreenerFilter
        get() = load("screener", ScreenerFilter.serializer(), ScreenerFilter())
        set(value) = save("screener", ScreenerFilter.serializer(), value)

    var recentSearches: List<String>
        get() = load("recent_searches", ListSerializer(kotlinx.serialization.builtins.serializer<String>()), emptyList())
        set(value) = save("recent_searches", ListSerializer(kotlinx.serialization.builtins.serializer<String>()), value.take(12))

    var signalStrategies: List<String>
        get() = load("signal_strategies", ListSerializer(kotlinx.serialization.builtins.serializer<String>()), emptyList())
        set(value) = save("signal_strategies", ListSerializer(kotlinx.serialization.builtins.serializer<String>()), value)

    var homeMarket: Market
        get() = prefs.getString("home_market", null)?.let { n -> Market.entries.firstOrNull { it.name == n } } ?: Market.KR
        set(value) = prefs.edit().putString("home_market", value.name).apply()

    val tokenStore: KisTokenStore = object : KisTokenStore {
        override fun load(): KisToken? = load("kis_token", KisToken.serializer(), KisToken("", 0, 0)).takeIf { it.token.isNotEmpty() }
        override fun save(token: KisToken) = save("kis_token", KisToken.serializer(), token)
    }
}

/** 내부 저장소 파일 캐시 */
class FileCache(context: Context) : DataCache {
    private val dir = File(context.filesDir, "market").apply { mkdirs() }

    private fun file(name: String) = File(dir, name.replace('/', '_'))

    override fun read(name: String): String? = runCatching { file(name).takeIf { it.exists() }?.readText() }.getOrNull()

    override fun write(name: String, text: String) {
        runCatching {
            val f = file(name)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    override fun modified(name: String): Long? = file(name).takeIf { it.exists() }?.lastModified()?.div(1000)

    override fun delete(name: String) { file(name).delete() }

    override fun list(prefix: String): List<String> = dir.list()?.filter { it.startsWith(prefix) && !it.endsWith(".tmp") }.orEmpty()

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0
}
