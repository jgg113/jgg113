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

data class SourceStatus(val name: String, val ok: Boolean, val detail: String, val page: String? = null)

/**
 * 앱이 쓰는 모든 시세·자료를 한곳에서 받는다. 키가 있는 출처를 먼저 쓰고, 받은 자료는 파일에 저장해 다시 받지 않는다.
 * 모든 함수는 네트워크를 쓰므로 백그라운드 스레드에서 부른다.
 */
class MarketData(
    private val keys: DataKeys,
    private val cache: DataCache,
    private val tokenStore: KisTokenStore = MemoryTokenStore(),
    private val http: Http = UrlConnectionHttp(),
    private val now: () -> LocalDateTime = { LocalDateTime.now(KST) },
) {
    private val json = lenientJson
    private val upbit = UpbitClient(http)
    private val publicData = PublicDataClient(keys.dataGoKr, http)
    private val kis = KisClient(keys.kisAppKey, keys.kisAppSecret, keys.kisVirtual, tokenStore, http)
    private val twelve = TwelveDataClient(keys.twelveData, http)
    private val dart = DartClient(keys.dart, http)
    private val newsClient = NewsClient(http)
    private val fx = FxClient(http)

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

    /** 국내 종목 목록: 공공데이터 시세 → DART 회사 목록 → 기본 테마 종목 순으로 찾는다. */
    fun krAssets(): List<Asset> {
        runCatching { krSnapshot() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { s -> return s.map { it.asset } }
        if (keys.hasDart) runCatching { dartCorps() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { corps ->
            return corps.map { Asset(Market.KR, it.stockCode, it.name, "") }
        }
        return Themes.builtIn.flatMap { it.members }.filter { it.startsWith("KR:") }.distinct().map { Asset(Market.KR, it.removePrefix("KR:"), it.removePrefix("KR:"), "") }
    }

    fun search(text: String, markets: Set<Market> = Market.entries.toSet(), limit: Int = 60): List<Asset> {
        val q = text.trim()
        if (q.isEmpty()) return emptyList()
        val lower = q.lowercase()
        val out = ArrayList<Asset>()
        if (Market.KR in markets) {
            val list = runCatching { krAssets() }.getOrDefault(emptyList())
            out += list.filter { it.code.startsWith(q) || it.name.lowercase().contains(lower) }
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
        need.filter { it.market == Market.US }.forEach { a -> runCatching { quote(a, maxAgeSec) }.getOrNull()?.let { out[a.key] = it } }
        return out
    }

    /** 전 종목 시세 (특징종목·종목필터용). 국내는 공공데이터 스냅숏, 코인은 업비트 전체, 해외는 기본 목록. */
    fun marketQuotes(market: Market): List<Quote> = when (market) {
        Market.KR -> krSnapshot()
        Market.CRYPTO -> upbit.allTickers(cryptoNameMap()).also { list -> list.forEach { memoryQuotes[it.asset.key] = it to nowSec() } }
        Market.US -> if (hasUs) quotes(UsStocks.list, maxAgeSec = 300).values.toList() else emptyList()
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
        if (!upToDate || cached.size < bars / 2) {
            val from = if (cached.size >= bars / 2 && lastDate != null) lastDate.minusDays(3) else today.minusDays((bars * 1.5).toLong() + 10)
            val fresh = fetchDaily(asset, from, if (cached.size >= bars / 2) 0 else bars)
            candles = CandleMerge.merge(if (cached.size >= bars / 2) cached else emptyList(), fresh).takeLast(bars + 200)
            if (candles.isNotEmpty()) {
                cache.write(candleFile(asset), CandleCodec.encode(candles))
                cache.write("fetched_" + candleFile(asset), nowSec().toString())
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
                .getOrElse { SourceStatus("공공데이터포털 주식시세", false, it.message.orEmpty(), PublicDataClient.STOCK_PAGE) },
        )
        add(
            if (!keys.hasPublic) SourceStatus("공공데이터포털 지수시세", false, "키 없음", PublicDataClient.INDEX_PAGE)
            else runCatching { val l = publicData.indices(listOf("코스피"), now().toLocalDate()); SourceStatus("공공데이터포털 지수시세", l.isNotEmpty(), l.firstOrNull()?.let { "코스피 ${Fmt.decimal(it.value)}" } ?: "자료 없음", PublicDataClient.INDEX_PAGE) }
                .getOrElse { SourceStatus("공공데이터포털 지수시세", false, it.message.orEmpty(), PublicDataClient.INDEX_PAGE) },
        )
        add(
            if (!keys.hasKis) SourceStatus("한국투자증권 (실시간)", false, "키 없음", KisClient.SIGNUP)
            else runCatching { val q = kis.domesticQuote("005930", "삼성전자"); SourceStatus("한국투자증권 (실시간)", true, "삼성전자 ${Fmt.number(q.price)}원 (${if (keys.kisVirtual) "모의" else "실전"} 서버)") }
                .getOrElse { SourceStatus("한국투자증권 (실시간)", false, it.message.orEmpty(), KisClient.SIGNUP) },
        )
        if (keys.hasKis) add(
            runCatching { val q = kis.overseasQuote("AAPL", "NAS", "애플"); SourceStatus("한국투자증권 해외시세", q != null, q?.let { "애플 $${Fmt.decimal(it.price)}" } ?: "빈 응답") }
                .getOrElse { SourceStatus("한국투자증권 해외시세", false, it.message.orEmpty()) },
        )
        add(
            if (!keys.hasTwelve) SourceStatus("Twelve Data (해외)", false, "키 없음 (한국투자증권이 있으면 필요 없음)", TwelveDataClient.KEY_PAGE)
            else runCatching { val q = twelve.quote(Asset(Market.US, "AAPL", "애플", "NAS")); SourceStatus("Twelve Data (해외)", q != null, q?.let { "애플 $${Fmt.decimal(it.price)}" } ?: "빈 응답") }
                .getOrElse { SourceStatus("Twelve Data (해외)", false, it.message.orEmpty(), TwelveDataClient.KEY_PAGE) },
        )
        add(
            if (!keys.hasDart) SourceStatus("DART (공시·재무)", false, "키 없음", DartClient.KEY_PAGE)
            else runCatching { SourceStatus("DART (공시·재무)", true, "최근 공시 ${dart.disclosures(null, now().toLocalDate().minusDays(3), now().toLocalDate(), 10).size}건 확인") }
                .getOrElse { SourceStatus("DART (공시·재무)", false, it.message.orEmpty(), DartClient.KEY_PAGE) },
        )
        add(runCatching { SourceStatus("뉴스 (Google 뉴스)", true, "${newsClient.search("코스피", 5).size}건") }.getOrElse { SourceStatus("뉴스 (Google 뉴스)", false, it.message.orEmpty()) })
        add(runCatching { SourceStatus("환율", true, "1달러 ${Fmt.decimal(fx.usdKrw().first)}원") }.getOrElse { SourceStatus("환율", false, it.message.orEmpty()) })
    }

    fun clearCache() {
        listOf("candles_", "fetched_", "kr_snapshot", "news_", "fin_", "kr_indices", "crypto_markets", "fx_", "dart_").forEach { p -> cache.list(p).forEach { cache.delete(it) } }
        memoryQuotes.clear()
        krSnapshotMemo = null
    }

    companion object {
        @Suppress("unused")
        private val mapSer = MapSerializer(String.serializer(), String.serializer())

        fun epochToDate(t: Long): LocalDate = Instant.ofEpochSecond(t).atZone(KST).toLocalDate()
    }
}
