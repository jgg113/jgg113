package io.github.jgg113.stocknote.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/** 사용자가 설정에 넣은 키 (모두 무료) */
@Serializable
data class DataKeys(
    val dataGoKr: String = "",
    val kisAppKey: String = "",
    val kisAppSecret: String = "",
    val kisVirtual: Boolean = false,
    val dart: String = "",
    val twelveData: String = "",
) {
    val hasKis: Boolean get() = kisAppKey.isNotBlank() && kisAppSecret.isNotBlank()
    val hasPublic: Boolean get() = dataGoKr.isNotBlank()
    val hasDart: Boolean get() = dart.isNotBlank()
    val hasTwelve: Boolean get() = twelveData.isNotBlank()

    /** 붙여넣다 섞인 빈칸·줄바꿈·보이지 않는 글자를 걷어 낸 키 */
    fun cleaned(): DataKeys = copy(
        dataGoKr = KeyText.clean(dataGoKr), kisAppKey = KeyText.clean(kisAppKey), kisAppSecret = KeyText.clean(kisAppSecret),
        dart = KeyText.clean(dart), twelveData = KeyText.clean(twelveData),
    )

    /** 키 모양이 평소와 다를 때 알려 줄 말 (틀렸다고 단정하지 않는다). 문제없으면 null */
    fun hintDataGoKr(): String? = dataGoKr.takeIf { it.isNotEmpty() }?.let { k ->
        when {
            k.startsWith("http", true) || k.contains("serviceKey=", true) -> "주소 전체가 아니라 인증키만 넣으세요."
            k.length < 40 -> "공공데이터포털 인증키는 보통 64자 이상입니다. 끝까지 복사됐는지 확인하세요."
            else -> null
        }
    }

    fun hintKisKey(): String? = kisAppKey.takeIf { it.isNotEmpty() }?.let { k ->
        when {
            k.length > 100 -> "앱시크릿(긴 것)을 앱키 칸에 넣은 것 같습니다. 앱키는 보통 36자입니다."
            k.length < 30 -> "한국투자증권 앱키는 보통 36자입니다. 끝까지 복사됐는지 확인하세요."
            else -> null
        }
    }

    fun hintKisSecret(): String? = kisAppSecret.takeIf { it.isNotEmpty() && it.length < 100 }?.let {
        "한국투자증권 앱시크릿은 보통 180자입니다. 앱키(짧은 것)를 넣었거나 중간에 잘렸는지 확인하세요."
    }

    fun hintDart(): String? = dart.takeIf { it.isNotEmpty() && it.length != 40 }?.let { "DART 인증키는 40자입니다. 지금 ${it.length}자입니다." }
}

/** 파일 캐시 (앱에서는 내부 저장소 파일) */
interface DataCache {
    fun read(name: String): String?
    fun write(name: String, text: String)
    /** 마지막으로 쓴 시각 (epoch 초), 없으면 null */
    fun modified(name: String): Long?
    fun delete(name: String)
    fun list(prefix: String): List<String>
}

class MemoryCache : DataCache {
    private val map = ConcurrentHashMap<String, Pair<String, Long>>()
    override fun read(name: String) = map[name]?.first
    override fun write(name: String, text: String) { map[name] = text to System.currentTimeMillis() / 1000 }
    override fun modified(name: String) = map[name]?.second
    override fun delete(name: String) { map.remove(name) }
    override fun list(prefix: String) = map.keys.filter { it.startsWith(prefix) }
}

/** 일봉을 짧은 CSV 로 저장한다 (JSON 보다 훨씬 작다). */
object CandleCodec {
    fun encode(candles: List<Candle>): String = buildString {
        candles.forEach { c ->
            append(c.time).append(',').append(c.open).append(',').append(c.high).append(',').append(c.low).append(',')
                .append(c.close).append(',').append(c.volume).append(',').append(c.value).append('\n')
        }
    }

    fun decode(text: String): List<Candle> = text.lineSequence().mapNotNull { line ->
        val p = line.split(',')
        if (p.size < 6) return@mapNotNull null
        runCatching { Candle(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p.getOrNull(6)?.toDouble() ?: 0.0) }.getOrNull()
    }.toList()
}

/** 장 상태 */
object MarketClock {
    private val holidays2026 = setOf(
        "2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18", "2026-03-02", "2026-05-01", "2026-05-05", "2026-05-25",
        "2026-06-03", "2026-08-17", "2026-09-24", "2026-09-25", "2026-10-05", "2026-10-09", "2026-12-25", "2026-12-31",
    )

    fun isKrTradingDay(d: LocalDate): Boolean = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && d.toString() !in holidays2026

    fun krOpen(now: LocalDateTime = LocalDateTime.now(KST)): Boolean =
        isKrTradingDay(now.toLocalDate()) && !now.toLocalTime().isBefore(LocalTime.of(9, 0)) && now.toLocalTime().isBefore(LocalTime.of(15, 31))

    /** 미국 정규장 (한국 시각, 서머타임은 대략 3~11월로 본다) */
    fun usOpen(now: LocalDateTime = LocalDateTime.now(KST)): Boolean {
        val et = now.atZone(KST).withZoneSameInstant(java.time.ZoneId.of("America/New_York")).toLocalDateTime()
        if (et.dayOfWeek == DayOfWeek.SATURDAY || et.dayOfWeek == DayOfWeek.SUNDAY) return false
        val t = et.toLocalTime()
        return !t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(16, 0))
    }

    fun open(market: Market): Boolean = when (market) {
        Market.KR -> krOpen()
        Market.US -> usOpen()
        Market.CRYPTO -> true
    }

    /** 이 날짜(포함) 이전의 마지막 국내 거래일 */
    fun lastKrTradingDay(d: LocalDate): LocalDate {
        var x = d
        while (!isKrTradingDay(x)) x = x.minusDays(1)
        return x
    }
}

/**
 * 연결 점검 한 줄. [detail] 은 서버가 한 말, [help] 는 고치는 방법, [page] 는 신청·발급 페이지.
 * [switchKisVirtual] 이 있으면 한국투자증권 키가 반대쪽 서버(실전↔모의) 것이어서 설정을 그 값으로 바꿔야 한다.
 */
data class SourceStatus(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val page: String? = null,
    val help: String = "",
    val pageLabel: String = "신청 페이지 열기",
    val switchKisVirtual: Boolean? = null,
) {
    /** 키를 넣지 않아 안 쓰는 것뿐인 줄 (오류가 아니다) */
    val unused: Boolean get() = !ok && detail.startsWith("키 없음")
}

/**
 * 앱이 쓰는 모든 시세·자료를 한곳에서 받는다. 키가 있는 출처를 먼저 쓰고, 받은 자료는 파일에 저장해 다시 받지 않는다.
 * 모든 함수는 네트워크를 쓰므로 백그라운드 스레드에서 부른다.
 */
class MarketData(
    rawKeys: DataKeys,
    private val cache: DataCache,
    private val tokenStore: KisTokenStore = MemoryTokenStore(),
    private val http: Http = UrlConnectionHttp(),
    private val now: () -> LocalDateTime = { LocalDateTime.now(KST) },
) {
    private val keys: DataKeys = rawKeys.cleaned()
    private val json = lenientJson
    private val upbit = UpbitClient(http)
    private val publicData = PublicDataClient(keys.dataGoKr, http)
    private val kis = KisClient(keys.kisAppKey, keys.kisAppSecret, keys.kisVirtual, tokenStore, http)
    private val twelve = TwelveDataClient(keys.twelveData, http)
    private val dart = DartClient(keys.dart, http)
    private val newsClient = NewsClient(http)
    private val fx = FxClient(http)
    private val fearGreedClient = FearGreedClient(http)
    private val globalCoin = GlobalCoinClient(http)

    private val memoryQuotes = ConcurrentHashMap<String, Pair<Quote, Long>>()
    private val usExchange = ConcurrentHashMap<String, String>()
    @Volatile private var cryptoNames: Map<String, String>? = null
    @Volatile private var krSnapshotMemo: Pair<LocalDate, List<Quote>>? = null

    val hasRealtimeKr: Boolean get() = keys.hasKis
    val hasUs: Boolean get() = keys.hasKis || keys.hasTwelve

    private fun nowSec() = now().atZone(KST).toEpochSecond()

    // ───────────── 종목 목록 ─────────────

    fun cryptoAssets(): List<Asset> {
        val cached = cache.read("crypto_markets.json")?.takeIf { (nowSec() - (cache.modified("crypto_markets.json") ?: 0)) < 86_400 }
        val list = cached?.let { runCatching { json.decodeFromString(ListSerializer(Asset.serializer()), it) }.getOrNull() }
            ?: upbit.markets().also { cache.write("crypto_markets.json", json.encodeToString(ListSerializer(Asset.serializer()), it)) }
        cryptoNames = list.associate { it.code to it.name }
        return list
    }

    private fun cryptoNameMap(): Map<String, String> = cryptoNames ?: runCatching { cryptoAssets() }.getOrDefault(emptyList()).associate { it.code to it.name }

    /** 국내 종목 목록: 공공데이터 시세 → 한국투자증권 종목 파일 → DART 회사 목록 → 기본 테마 종목 순으로 찾는다. */
    fun krAssets(): List<Asset> {
        runCatching { krSnapshot() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { s -> return s.map { it.asset } }
        if (keys.hasKis) runCatching { kisMaster() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (keys.hasDart) runCatching { dartCorps() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { corps ->
            return corps.map { Asset(Market.KR, it.stockCode, it.name, "") }
        }
        return Themes.builtIn.flatMap { it.members }.filter { it.startsWith("KR:") }.distinct().map { k -> k.removePrefix("KR:").let { Asset(Market.KR, it, KrNames.of(it) ?: it, "") } }
    }

    /** 한국투자증권 종목 파일 (코드·이름). 일주일에 한 번만 받는다. */
    fun kisMaster(): List<Asset> {
        val name = "kis_master.json"
        cache.read(name)?.takeIf { (nowSec() - (cache.modified(name) ?: 0)) < 7 * 86_400 }?.let { text ->
            runCatching { json.decodeFromString(ListSerializer(Asset.serializer()), text) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val list = KisMaster.download(http)
        if (list.isNotEmpty()) cache.write(name, json.encodeToString(ListSerializer(Asset.serializer()), list))
        return list
    }

    /** 코드로 종목 이름을 찾는다 (목록에 없으면 null). */
    fun krName(code: String): String? = KrNames.of(code) ?: runCatching { krAssets() }.getOrNull()?.firstOrNull { it.code == code }?.name?.takeIf { it != code }

    fun search(text: String, markets: Set<Market> = Market.entries.toSet(), limit: Int = 60): List<Asset> {
        val q = text.trim()
        if (q.isEmpty()) return emptyList()
        val lower = q.lowercase()
        val out = ArrayList<Asset>()
        if (Market.KR in markets) {
            val list = runCatching { krAssets() }.getOrDefault(emptyList())
            out += list.filter { it.code.startsWith(q) || it.name.lowercase().contains(lower) }
            // 목록에 없는 6자리 코드는 그대로 넣어 볼 수 있게 한다.
            if (Regex("^\\d{6}$").matches(q) && list.none { it.code == q }) out += Asset(Market.KR, q, q, "")
        }
        if (Market.US in markets) {
            val hits = UsStocks.list.filter { it.code.lowercase().startsWith(lower) || it.name.lowercase().contains(lower) }
            out += hits
            if (hits.none { it.code.equals(q, true) } && Regex("^[A-Za-z.]{1,6}$").matches(q)) {
                out += Asset(Market.US, q.uppercase(), q.uppercase(), "")
            }
        }
        if (Market.CRYPTO in markets) {
            val list = runCatching { cryptoAssets() }.getOrDefault(emptyList())
            out += list.filter { it.shortCode.lowercase().startsWith(lower) || it.name.lowercase().contains(lower) }
        }
        // 이름·코드가 똑같은 것 → 앞부분이 같은 것 → 짧은 이름 순
        fun rank(a: Asset): Int {
            val name = a.name.lowercase()
            val code = a.shortCode.lowercase()
            return when {
                name == lower || code == lower -> 0
                name.startsWith(lower) || code.startsWith(lower) -> 1
                else -> 2
            }
        }
        return out.distinctBy { it.key }.sortedWith(compareBy({ rank(it) }, { it.name.length })).take(limit)
    }

    // ───────────── 국내 전 종목 스냅숏 (공공데이터) ─────────────

    /** 가장 최근 거래일의 전 종목 시세. 하루에 한 번만 받는다. */
    fun krSnapshot(force: Boolean = false): List<Quote> {
        if (!keys.hasPublic) return emptyList()
        val today = now().toLocalDate()
        krSnapshotMemo?.let { (d, list) -> if (!force && list.isNotEmpty() && d >= expectedPublicDate(today)) return list }
        val names = cache.list("kr_snapshot_").sorted()
        val latestCached = names.lastOrNull()?.let { n ->
            val d = runCatching { LocalDate.parse(n.removePrefix("kr_snapshot_").removeSuffix(".json"), PublicDataClient.YMD) }.getOrNull()
            d?.let { it to n }
        }
        if (!force && latestCached != null && (latestCached.first >= expectedPublicDate(today) || recentlyChecked("kr_snapshot_check"))) {
            val list = decodeQuotes(cache.read(latestCached.second))
            if (list.isNotEmpty()) {
                krSnapshotMemo = latestCached.first to list
                return list
            }
        }
        cache.write("kr_snapshot_check", nowSec().toString())
        val date = publicData.latestDate(today) ?: return latestCached?.let { decodeQuotes(cache.read(it.second)) }.orEmpty()
        if (latestCached != null && latestCached.first == date) {
            val list = decodeQuotes(cache.read(latestCached.second))
            if (list.isNotEmpty()) { krSnapshotMemo = date to list; return list }
        }
        val list = publicData.snapshot(date)
        if (list.isNotEmpty()) {
            cache.write("kr_snapshot_${PublicDataClient.ymd(date)}.json", json.encodeToString(ListSerializer(Quote.serializer()), list))
            names.dropLast(2).forEach { cache.delete(it) }
            krSnapshotMemo = date to list
        }
        return list
    }

    /** 공공데이터는 다음 영업일 오후 1시쯤 전날 종가를 올린다. 지금 있을 법한 가장 최근 날짜. */
    private fun expectedPublicDate(today: LocalDate): LocalDate {
        val t = now()
        var d = today.minusDays(1)
        if (MarketClock.isKrTradingDay(today) && t.toLocalTime().isBefore(LocalTime.of(13, 30))) d = d.minusDays(1).let { MarketClock.lastKrTradingDay(it) }
        return MarketClock.lastKrTradingDay(d)
    }

    private fun recentlyChecked(name: String, seconds: Long = 3600): Boolean =
        cache.read(name)?.toLongOrNull()?.let { nowSec() - it < seconds } ?: false

    private fun decodeQuotes(text: String?): List<Quote> =
        text?.let { runCatching { json.decodeFromString(ListSerializer(Quote.serializer()), it) }.getOrNull() }.orEmpty()

    // ───────────── 시세 ─────────────

    /** 현재가. [maxAgeSec] 안에 받은 값이 있으면 다시 받지 않는다. */
    fun quote(asset: Asset, maxAgeSec: Long = 5): Quote {
        memoryQuotes[asset.key]?.let { (q, t) -> if (nowSec() - t <= maxAgeSec) return q }
        val q = when (asset.market) {
            Market.CRYPTO -> upbit.tickers(listOf(asset.code), cryptoNameMap()).firstOrNull()
            Market.KR -> krQuote(asset)
            Market.US -> usQuote(asset)
        } ?: throw DataException("${asset.name} 시세를 받지 못했습니다.", "EMPTY")
        val fixed = if (q.asset.name.isBlank() || q.asset.name == q.asset.code) q.copy(asset = q.asset.copy(name = asset.name.ifBlank { q.asset.name })) else q
        memoryQuotes[asset.key] = fixed to nowSec()
        return fixed
    }

    private fun krQuote(asset: Asset): Quote? {
        if (keys.hasKis) {
            val snap = runCatching { krSnapshot() }.getOrNull()?.firstOrNull { it.asset.code == asset.code }
            return kis.domesticQuote(asset.code, asset.name.ifBlank { snap?.asset?.name.orEmpty() }, asset.exchange.ifBlank { snap?.asset?.exchange.orEmpty() })
        }
        if (keys.hasPublic) return krSnapshot().firstOrNull { it.asset.code == asset.code }
        throw DataException("국내 시세를 보려면 설정에서 공공데이터포털 인증키 또는 한국투자증권 앱키를 넣으세요.", "NOKEY")
    }

    private fun usQuote(asset: Asset): Quote? {
        if (keys.hasKis) {
            val known = usExchange[asset.code] ?: asset.exchange.takeIf { it.isNotBlank() } ?: UsStocks.find(asset.code)?.exchange
            val order = listOfNotNull(known) + UsStocks.exchangeOrder.filter { it != known }
            for (ex in order) {
                val q = kis.overseasQuote(asset.code, ex, asset.name) ?: continue
                usExchange[asset.code] = ex
                return q.copy(asset = q.asset.copy(exchange = ex))
            }
            if (!keys.hasTwelve) return null
        }
        if (keys.hasTwelve) return twelve.quote(asset)
        throw DataException("해외 시세를 보려면 설정에서 한국투자증권 앱키 또는 Twelve Data 키를 넣으세요.", "NOKEY")
    }

    /** 여러 종목 현재가. 실패한 종목은 빠진다. */
    fun quotes(assets: List<Asset>, maxAgeSec: Long = 10): Map<String, Quote> {
        val out = LinkedHashMap<String, Quote>()
        val fresh = assets.filter { a -> memoryQuotes[a.key]?.let { nowSec() - it.second <= maxAgeSec } == true }
        fresh.forEach { out[it.key] = memoryQuotes[it.key]!!.first }
        val need = assets.filter { it.key !in out }
        val crypto = need.filter { it.market == Market.CRYPTO }
        if (crypto.isNotEmpty()) runCatching {
            val all = if (crypto.size > 20) upbit.allTickers(cryptoNameMap()) else upbit.tickers(crypto.map { it.code }, cryptoNameMap())
            val wanted = crypto.map { it.key }.toSet()
            all.forEach { q -> memoryQuotes[q.asset.key] = q to nowSec(); if (q.asset.key in wanted) out[q.asset.key] = q }
        }
        val kr = need.filter { it.market == Market.KR }
        if (kr.isNotEmpty()) {
            if (keys.hasKis && kr.size <= 40) {
                kr.forEach { a -> runCatching { quote(a, maxAgeSec) }.getOrNull()?.let { out[a.key] = it } }
            } else if (keys.hasPublic) {
                val snap = runCatching { krSnapshot() }.getOrDefault(emptyList()).associateBy { it.asset.key }
                kr.forEach { a -> snap[a.key]?.let { out[a.key] = it } }
            } else if (keys.hasKis) {
                kr.take(40).forEach { a -> runCatching { quote(a, maxAgeSec) }.getOrNull()?.let { out[a.key] = it } }
            }
        }
        // Twelve Data 무료 키는 1분에 8번까지만 되므로, 한국투자증권이 없으면 한 번에 8종목까지만 받는다.
        val us = need.filter { it.market == Market.US }.let { if (keys.hasKis) it else it.take(8) }
        us.forEach { a -> runCatching { quote(a, maxAgeSec) }.getOrNull()?.let { out[a.key] = it } }
        return out
    }

    /** 전 종목 시세 (특징종목·종목필터용). 국내는 공공데이터 스냅숏, 코인은 업비트 전체, 해외는 기본 목록. */
    fun marketQuotes(market: Market): List<Quote> = when (market) {
        Market.KR -> krSnapshot()
        Market.CRYPTO -> upbit.allTickers(cryptoNameMap()).also { list -> list.forEach { memoryQuotes[it.asset.key] = it to nowSec() } }
        Market.US -> if (hasUs) quotes(if (keys.hasKis) UsStocks.list else UsStocks.list.take(8), maxAgeSec = if (keys.hasKis) 300 else 900).values.toList() else emptyList()
    }

    /** 실시간 거래대금·거래량 상위 (한국투자증권 실전 서버만, 실패하면 빈 목록) */
    fun krLiveRank(byValue: Boolean): List<Quote> = if (keys.hasKis && !keys.kisVirtual) runCatching { kis.volumeRank(byValue) }.getOrDefault(emptyList()) else emptyList()

    // ───────────── 지수·환율 ─────────────

    fun indices(): List<IndexQuote> {
        val out = ArrayList<IndexQuote>()
        if (keys.hasKis) {
            listOf("0001" to "코스피", "1001" to "코스닥", "2001" to "코스피 200").forEach { (code, name) ->
                runCatching { kis.index(code, name) }.getOrNull()?.let { out += it }
            }
        }
        if (out.isEmpty() && keys.hasPublic) {
            val cached = cache.read("kr_indices.json")?.takeIf { recentlyChecked("kr_indices_time", 3 * 3600) }
            val list = cached?.let { runCatching { json.decodeFromString(ListSerializer(IndexQuote.serializer()), it) }.getOrNull() }
                ?: runCatching { publicData.indices(today = now().toLocalDate()) }.getOrDefault(emptyList()).also {
                    if (it.isNotEmpty()) {
                        cache.write("kr_indices.json", json.encodeToString(ListSerializer(IndexQuote.serializer()), it))
                        cache.write("kr_indices_time", nowSec().toString())
                    }
                }
            out += list
        }
        return out
    }

    /** 미국 시장 대표 ETF (지수 대신) */
    fun usProxies(): List<Quote> = if (!hasUs) emptyList() else
        listOf("SPY", "QQQ", "DIA").mapNotNull { t -> UsStocks.find(t)?.let { runCatching { quote(it, 60) }.getOrNull() } }

    fun usdKrw(): Double? {
        cache.read("fx_usdkrw")?.split(',')?.let { p ->
            val v = p.getOrNull(0)?.toDoubleOrNull(); val t = p.getOrNull(1)?.toLongOrNull() ?: 0
            if (v != null && nowSec() - t < 6 * 3600) return v
        }
        return runCatching { fx.usdKrw().first }.getOrNull()?.also { cache.write("fx_usdkrw", "$it,${nowSec()}") }
            ?: cache.read("fx_usdkrw")?.substringBefore(',')?.toDoubleOrNull()
    }

    // ───────────── 일봉·분봉 ─────────────

    private fun candleFile(asset: Asset) = "candles_${asset.market.name}_${asset.code.replace(Regex("[^A-Za-z0-9_-]"), "_")}.csv"

    fun cachedDaily(asset: Asset): List<Candle> = cache.read(candleFile(asset))?.let { CandleCodec.decode(it) }.orEmpty()

    /**
     * 일봉 (약 [bars] 개). 저장해 둔 것이 있으면 빠진 최근 부분만 받는다.
     * 장중에는 마지막 봉에 지금 시세를 반영한다 (실시간 출처가 있을 때).
     */
    fun daily(asset: Asset, bars: Int = 600, live: Boolean = true): List<Candle> {
        val today = now().toLocalDate()
        val cached = cachedDaily(asset)
        val lastDate = cached.lastOrNull()?.date
        val upToDate = lastDate != null && when (asset.market) {
            Market.KR -> if (keys.hasKis) lastDate >= MarketClock.lastKrTradingDay(today) && !MarketClock.krOpen(now()) && recentlyFetched(asset, 1800)
                         else lastDate >= expectedPublicDate(today) || recentlyFetched(asset, 3 * 3600)
            Market.US -> recentlyFetched(asset, if (MarketClock.usOpen(now())) 600 else 6 * 3600)
            Market.CRYPTO -> recentlyFetched(asset, 60)
        }
        var candles = cached
        // 전에 더 짧은 기간만 받아 둔 종목은 한 번 더 길게 받는다 (상장한 지 얼마 안 된 종목을 매번 다시 받지 않도록 받은 깊이를 적어 둔다).
        val depthFile = "depth_" + candleFile(asset)
        val depth = cache.read(depthFile)?.toIntOrNull() ?: 0
        val enough = cached.isNotEmpty() && (cached.size >= bars * 9 / 10 || depth >= bars)
        if (!upToDate || !enough) {
            val from = if (enough && lastDate != null) lastDate.minusDays(3) else today.minusDays((bars * 1.5).toLong() + 10)
            val fresh = fetchDaily(asset, from, if (enough) 0 else bars)
            candles = CandleMerge.merge(if (enough) cached else emptyList(), fresh).takeLast(maxOf(bars, depth) + 200)
            if (candles.isNotEmpty()) {
                cache.write(candleFile(asset), CandleCodec.encode(candles))
                cache.write("fetched_" + candleFile(asset), nowSec().toString())
                if (!enough) cache.write(depthFile, bars.toString())
            }
        }
        if (live && candles.isNotEmpty() && asset.market == Market.KR && keys.hasKis && MarketClock.krOpen(now())) {
            runCatching { quote(asset, 15) }.getOrNull()?.let { q -> candles = CandleMerge.withQuote(candles, q, today.epochKst()) }
        }
        if (live && candles.isNotEmpty() && asset.market == Market.CRYPTO) {
            runCatching { quote(asset, 15) }.getOrNull()?.let { q ->
                val day = java.time.Instant.ofEpochSecond(nowSec()).atZone(java.time.ZoneOffset.UTC).toLocalDate().epochKst()
                candles = CandleMerge.withQuote(candles, q, day)
            }
        }
        return candles.takeLast(bars)
    }

    private fun recentlyFetched(asset: Asset, seconds: Long) = recentlyChecked("fetched_" + candleFile(asset), seconds)

    private fun fetchDaily(asset: Asset, from: LocalDate, count: Int): List<Candle> = when (asset.market) {
        Market.CRYPTO -> {
            val days = java.time.temporal.ChronoUnit.DAYS.between(from, now().toLocalDate()).toInt() + 2
            upbit.daily(asset.code, maxOf(days, if (count > 0) count else 0).coerceIn(2, 2000))
        }
        Market.KR -> when {
            keys.hasKis -> kis.domesticDaily(asset.code, from, now().toLocalDate())
            keys.hasPublic -> publicData.history(asset.code, from)
            else -> throw DataException("국내 차트를 보려면 설정에서 공공데이터포털 인증키 또는 한국투자증권 앱키를 넣으세요.", "NOKEY")
        }
        Market.US -> {
            val n = (java.time.temporal.ChronoUnit.DAYS.between(from, now().toLocalDate()).toInt() * 5 / 7 + 5).coerceAtLeast(count).coerceAtMost(2000)
            when {
                keys.hasKis -> {
                    val ex = usExchange[asset.code] ?: runCatching { quote(asset, 3600).asset.exchange }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: asset.exchange.ifBlank { "NAS" }
                    kis.overseasDaily(asset.code, ex, n)
                }
                keys.hasTwelve -> twelve.daily(asset, n)
                else -> throw DataException("해외 차트를 보려면 설정에서 한국투자증권 앱키 또는 Twelve Data 키를 넣으세요.", "NOKEY")
            }
        }
    }

    /** 분봉 (오늘 위주). 국내는 한국투자증권, 코인은 업비트. */
    fun intraday(asset: Asset, tf: Timeframe): List<Candle> = when (asset.market) {
        Market.CRYPTO -> upbit.minutes(asset.code, tf.minutes, 400)
        Market.KR -> {
            if (!keys.hasKis) throw DataException("국내 분봉은 한국투자증권 앱키가 있어야 볼 수 있습니다. (공공데이터는 하루 한 번 종가만 줍니다)", "NOKEY")
            val one = kis.domesticMinutes(asset.code, now = now())
            if (tf == Timeframe.MIN1) one else CandleAggregator.minutes(one, tf.minutes)
        }
        Market.US -> throw DataException("해외 주식은 일봉·주봉·월봉만 볼 수 있습니다.", "UNSUPPORTED")
    }

    /**
     * 여러 종목 일봉을 한꺼번에 준비한다 (매매신호·종목필터용). 저장해 둔 것은 다시 받지 않는다.
     * 국내 공공데이터는 빠진 날짜의 전 종목 스냅숏으로 한 번에 이어 붙여 호출 수를 줄인다.
     */
    fun dailyMany(assets: List<Asset>, bars: Int = 400, progress: (Int, Int) -> Unit = { _, _ -> }): Map<Asset, List<Candle>> {
        val out = LinkedHashMap<Asset, List<Candle>>()
        assets.forEachIndexed { i, a ->
            progress(i, assets.size)
            runCatching { daily(a, bars, live = false) }.getOrNull()?.takeIf { it.size >= 30 }?.let { out[a] = it }
        }
        progress(assets.size, assets.size)
        return out
    }

    // ───────────── 공시·재무·뉴스 ─────────────

    fun dartCorps(): List<DartCorp> {
        val name = "dart_corps.json"
        val fresh = (nowSec() - (cache.modified(name) ?: 0)) < 30 * 86_400
        cache.read(name)?.takeIf { fresh }?.let { text ->
            runCatching { json.decodeFromString(ListSerializer(DartCorp.serializer()), text) }.getOrNull()?.let { return it }
        }
        val list = dart.corpCodes()
        cache.write(name, json.encodeToString(ListSerializer(DartCorp.serializer()), list))
        return list
    }

    fun corpCode(asset: Asset): String? = if (asset.market != Market.KR || !keys.hasDart) null else dartCorps().firstOrNull { it.stockCode == asset.code }?.corpCode

    fun disclosures(asset: Asset?, days: Int = 90): List<NewsItem> {
        if (!keys.hasDart) return emptyList()
        val code = asset?.let { corpCode(it) ?: return emptyList() }
        return dart.disclosures(code, now().toLocalDate().minusDays(days.toLong()), now().toLocalDate(), if (asset == null) 100 else 50)
    }

    fun financials(asset: Asset): Financials? {
        val code = corpCode(asset) ?: return null
        val name = "fin_$code.json"
        cache.read(name)?.takeIf { (nowSec() - (cache.modified(name) ?: 0)) < 7 * 86_400 }?.let { text ->
            runCatching { json.decodeFromString(Financials.serializer(), text) }.getOrNull()?.let { return it }
        }
        val f = dart.financials(code, now().toLocalDate()) ?: return null
        cache.write(name, json.encodeToString(Financials.serializer(), f))
        return f
    }

    // ───────────── 수급·투자의견·지분·배당 ─────────────

    /** 국내 종목의 수급(개인·외국인·기관 순매수)을 볼 수 있는지 (한국투자증권 키가 있어야 한다) */
    val hasFlows: Boolean get() = keys.hasKis

    fun investorFlows(asset: Asset): List<InvestorFlow> =
        if (asset.market != Market.KR || !keys.hasKis) emptyList() else kis.investorFlows(asset.code)

    /** 증권사 투자의견 (최근 6개월). 실전 서버 키에서만 된다. */
    fun analystOpinions(asset: Asset): List<AnalystOpinion> =
        if (asset.market != Market.KR || !keys.hasKis || keys.kisVirtual) emptyList() else kis.analystOpinions(asset.code, now().toLocalDate().minusMonths(6), now().toLocalDate())

    /** 5% 이상 주주와 임원의 지분 변동 (DART 키) */
    fun ownership(asset: Asset): List<OwnershipReport> {
        val code = corpCode(asset) ?: return emptyList()
        val major = runCatching { dart.majorHoldings(code) }
        val insider = runCatching { dart.insiderReports(code) }
        if (major.isFailure && insider.isFailure) throw major.exceptionOrNull()!!
        return (major.getOrDefault(emptyList()) + insider.getOrDefault(emptyList())).sortedByDescending { it.date }.take(20)
    }

    fun dividend(asset: Asset): DividendInfo? {
        val code = corpCode(asset) ?: return null
        val name = "div_$code.json"
        cache.read(name)?.takeIf { (nowSec() - (cache.modified(name) ?: 0)) < 7 * 86_400 }?.let { text ->
            runCatching { json.decodeFromString(DividendInfo.serializer(), text) }.getOrNull()?.let { return it }
        }
        val d = dart.dividend(code, now().toLocalDate()) ?: return null
        cache.write(name, json.encodeToString(DividendInfo.serializer(), d))
        return d
    }

    // ───────────── 코인: 공포·탐욕, 김치 프리미엄 (키 없음) ─────────────

    @Volatile private var fearGreedMemo: Pair<FearGreed, Long>? = null
    @Volatile private var globalCoinMemo: Triple<Map<String, Double>, String, Long>? = null

    fun fearGreed(): FearGreed? {
        fearGreedMemo?.let { (v, t) -> if (nowSec() - t < 1800) return v }
        return fearGreedClient.crypto()?.also { fearGreedMemo = it to nowSec() }
    }

    /** 업비트 원화 가격과 해외 달러 가격의 차이. 돌려주는 글은 해외 시세 출처. */
    fun kimchi(upbit: List<Quote>): Pair<List<Premium>, String> {
        val rate = usdKrw() ?: throw DataException("환율을 받지 못해 김치 프리미엄을 계산하지 못했습니다.", "FX")
        val memo = globalCoinMemo
        val (prices, source) = if (memo != null && nowSec() - memo.third < 60) memo.first to memo.second
        else globalCoin.usdPrices().also { globalCoinMemo = Triple(it.first, it.second, nowSec()) }
        return Kimchi.premiums(upbit, prices, rate) to source
    }

    fun news(query: String, limit: Int = 30): List<NewsItem> {
        val name = "news_" + query.hashCode().toString(16)
        cache.read(name)?.takeIf { (nowSec() - (cache.modified(name) ?: 0)) < 900 }?.let { text ->
            runCatching { json.decodeFromString(ListSerializer(NewsItem.serializer()), text) }.getOrNull()?.let { return it.take(limit) }
        }
        val list = newsClient.search(query, limit)
        cache.write(name, json.encodeToString(ListSerializer(NewsItem.serializer()), list))
        return list
    }

    fun newsFor(asset: Asset): List<NewsItem> = news(
        when (asset.market) {
            Market.KR -> "\"${asset.name}\" 주가"
            Market.US -> "${asset.name} 주가"
            Market.CRYPTO -> "${asset.name} 코인"
        },
    )

    // ───────────── 매매신호 대상 ─────────────

    /** 매매신호를 볼 종목 묶음 */
    fun universe(market: Market, size: Int, extra: List<Asset> = emptyList()): List<Asset> {
        val base = when (market) {
            Market.KR -> {
                val snap = runCatching { krSnapshot() }.getOrDefault(emptyList())
                if (snap.isNotEmpty()) snap.filter { !MarketStats.isSpacOrPreferred(it.asset.name) }.sortedByDescending { it.marketCap }.take(size).map { it.asset }
                else Themes.builtIn.flatMap { it.members }.filter { it.startsWith("KR:") }.distinct().take(size).map { Asset(Market.KR, it.removePrefix("KR:"), "", "") }
            }
            Market.CRYPTO -> runCatching { upbit.allTickers(cryptoNameMap()) }.getOrDefault(emptyList())
                .filterNot { it.asset.shortCode.startsWith("USD") || it.asset.shortCode in setOf("USDT", "USDC", "USDE", "USDS", "PYUSD", "RLUSD", "EURC", "XAUT") }
                .sortedByDescending { it.value }.take(size).map { it.asset }
            Market.US -> UsStocks.list.take(size)
        }
        return (extra.filter { it.market == market } + base).distinctBy { it.key }
    }

    // ───────────── 연결 점검 ─────────────

    fun check(): List<SourceStatus> = buildList {
        add(runCatching { SourceStatus("업비트 (코인)", true, "${upbit.allTickers(cryptoNameMap()).size}개 마켓") }.getOrElse { SourceStatus("업비트 (코인)", false, it.message.orEmpty()) })
        add(
            if (!keys.hasPublic) SourceStatus("공공데이터포털 주식시세", false, "키 없음", PublicDataClient.STOCK_PAGE)
            else runCatching { SourceStatus("공공데이터포털 주식시세", true, "최근 ${publicData.latestDate(now().toLocalDate()) ?: "-"} 시세") }
                .getOrElse { SourceStatus("공공데이터포털 주식시세", false, it.message.orEmpty(), PublicDataClient.STOCK_PAGE, publicHelp(it, "금융위원회_주식시세정보")) },
        )
        add(
            if (!keys.hasPublic) SourceStatus("공공데이터포털 지수시세", false, "키 없음", PublicDataClient.INDEX_PAGE)
            else runCatching { val l = publicData.indices(listOf("코스피"), now().toLocalDate()); SourceStatus("공공데이터포털 지수시세", l.isNotEmpty(), l.firstOrNull()?.let { "코스피 ${Fmt.decimal(it.value)}" } ?: "자료 없음", PublicDataClient.INDEX_PAGE) }
                .getOrElse { SourceStatus("공공데이터포털 지수시세", false, it.message.orEmpty(), PublicDataClient.INDEX_PAGE, publicHelp(it, "금융위원회_지수시세정보")) },
        )
        val (kisRow, kisWorking) = kisStatus()
        add(kisRow)
        if (kisWorking != null) add(
            runCatching { val q = kisWorking.overseasQuote("AAPL", "NAS", "애플"); SourceStatus("한국투자증권 해외시세", q != null, q?.let { "애플 $${Fmt.decimal(it.price)}" } ?: "빈 응답") }
                .getOrElse {
                    SourceStatus(
                        "한국투자증권 해외시세", false, it.message.orEmpty(),
                        help = if ((it as? DataException)?.code == "NETWORK") "" else "해외시세는 KIS Developers 에서 앱키를 만들 때 해외주식 서비스도 함께 신청해야 나옵니다. 국내 시세만 쓴다면 그대로 두어도 됩니다.",
                    )
                },
        )
        add(
            if (!keys.hasTwelve) SourceStatus("Twelve Data (해외)", false, "키 없음 (한국투자증권이 있으면 필요 없음)", TwelveDataClient.KEY_PAGE, pageLabel = "키 받는 곳 열기")
            else runCatching { val q = twelve.quote(Asset(Market.US, "AAPL", "애플", "NAS")); SourceStatus("Twelve Data (해외)", q != null, q?.let { "애플 $${Fmt.decimal(it.price)}" } ?: "빈 응답") }
                .getOrElse { SourceStatus("Twelve Data (해외)", false, it.message.orEmpty(), TwelveDataClient.KEY_PAGE, pageLabel = "키 받는 곳 열기") },
        )
        add(
            if (!keys.hasDart) SourceStatus("DART (공시·재무)", false, "키 없음", DartClient.KEY_PAGE, pageLabel = "키 받는 곳 열기")
            else runCatching { SourceStatus("DART (공시·재무)", true, "최근 공시 ${dart.disclosures(null, now().toLocalDate().minusDays(3), now().toLocalDate(), 10).size}건 확인") }
                .getOrElse {
                    SourceStatus(
                        "DART (공시·재무)", false, it.message.orEmpty(), DartClient.KEY_PAGE,
                        if ((it as? DataException)?.code == "NETWORK") "" else "DART 인증키는 opendart.fss.or.kr 에서 신청한 뒤 메일 인증까지 마쳐야 쓸 수 있습니다 (40자).",
                        "키 받는 곳 열기",
                    )
                },
        )
        add(runCatching { SourceStatus("뉴스 (Google 뉴스)", true, "${newsClient.search("코스피", 5).size}건") }.getOrElse { SourceStatus("뉴스 (Google 뉴스)", false, it.message.orEmpty()) })
        add(runCatching { SourceStatus("환율", true, "1달러 ${Fmt.decimal(fx.usdKrw().first)}원") }.getOrElse { SourceStatus("환율", false, it.message.orEmpty()) })
    }

    private fun publicHelp(e: Throwable, api: String): String = when ((e as? DataException)?.code) {
        "NETWORK", "429", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR" -> ""
        else ->
            "공공데이터포털 키는 API 마다 따로 '활용신청'을 해야 열립니다. 다른 앱에서 잘 되던 키라도 '$api' 를 신청하지 않았으면 여기서는 안 됩니다. " +
                "① 아래 [신청 페이지 열기] ② 로그인 ③ [활용신청] 을 누르면 바로 승인됩니다. 키는 지금 넣은 것을 그대로 쓰고, 신청 뒤 1~2시간쯤 지나 다시 점검하세요."
    }

    /** 한국투자증권 점검. 키가 반대쪽 서버(실전↔모의) 것이면 그쪽으로 되는지도 보고 알려 준다. 되는 쪽 연결을 함께 돌려준다. */
    private fun kisStatus(): Pair<SourceStatus, KisClient?> {
        val label = "한국투자증권 (실시간)"
        if (!keys.hasKis) {
            val half = keys.kisAppKey.isNotBlank() || keys.kisAppSecret.isNotBlank()
            return SourceStatus(label, false, if (half) "앱키와 앱시크릿을 둘 다 넣어야 합니다." else "키 없음", KisClient.SIGNUP, pageLabel = "KIS Developers 열기") to null
        }
        fun server(v: Boolean) = if (v) "모의" else "실전"
        fun price(c: KisClient) = Fmt.number(c.domesticQuote("005930", "삼성전자").price)
        val first = runCatching { price(kis) }
        first.getOrNull()?.let { return SourceStatus(label, true, "삼성전자 ${it}원 (${server(keys.kisVirtual)} 서버)") to kis }
        val e = first.exceptionOrNull()
        val code = (e as? DataException)?.code.orEmpty()
        if (code == "KIS_KEY") {
            val other = KisClient(keys.kisAppKey, keys.kisAppSecret, !keys.kisVirtual, tokenStore, http)
            runCatching { price(other) }.getOrNull()?.let {
                return SourceStatus(
                    label, true, "삼성전자 ${it}원 (${server(!keys.kisVirtual)} 서버) · 이 키는 ${server(!keys.kisVirtual)}투자용이어서 서버 설정을 자동으로 바꿨습니다.",
                    switchKisVirtual = !keys.kisVirtual,
                ) to other
            }
        }
        val help = when (code) {
            "KIS_KEY" -> "실전·모의 두 서버 모두 이 키를 받아 주지 않았습니다. KIS Developers 의 [API 신청] 화면에 있는 앱키(36자)와 앱시크릿(180자)을 각각 맞는 칸에 처음부터 끝까지 복사했는지 확인하세요. 증권 앱 로그인 비밀번호나 계좌번호가 아닙니다. 키를 다시 발급받았다면 예전 키는 쓸 수 없습니다."
            "NETWORK" -> "한국투자증권은 9443·29443 번 통로를 쓰는데, 회사·학교·공공 와이파이는 이 통로를 막는 곳이 많습니다. 와이파이를 끄고 모바일 데이터로 다시 점검해 보세요."
            "KIS_WAIT" -> ""
            else -> "KIS Developers 에서 서비스 신청(국내주식 시세)이 끝났는지, 키 유효기간이 지나지 않았는지 확인하세요."
        }
        return SourceStatus(label, false, e?.message.orEmpty(), KisClient.SIGNUP, help, "KIS Developers 열기") to null
    }

    fun clearCache() {
        listOf("candles_", "fetched_", "depth_", "kr_snapshot", "news_", "fin_", "div_", "kr_indices", "crypto_markets", "fx_", "dart_", "kis_master").forEach { p -> cache.list(p).forEach { cache.delete(it) } }
        memoryQuotes.clear()
        krSnapshotMemo = null
    }

    companion object {
        @Suppress("unused")
        private val mapSer = MapSerializer(String.serializer(), String.serializer())

        fun epochToDate(t: Long): LocalDate = Instant.ofEpochSecond(t).atZone(KST).toLocalDate()
    }
}
