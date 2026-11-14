package io.github.jgg113.stocknote.core

import kotlinx.serialization.Serializable

/** 상승·하락 종목 수 */
data class Breadth(val up: Int, val down: Int, val flat: Int, val upperLimit: Int, val lowerLimit: Int) {
    val total: Int get() = up + down + flat
}

/** 특징종목 종류 */
enum class FeatureKind(val label: String, val description: String) {
    GAINERS("급등", "많이 오른 종목"),
    LOSERS("급락", "많이 내린 종목"),
    VALUE("거래대금", "돈이 가장 많이 몰린 종목"),
    VOLUME("거래량", "가장 많이 거래된 종목"),
    VOLUME_SURGE("거래량 급증", "평소(20일 평균)보다 거래량이 크게 는 종목"),
    NEW_HIGH("52주 신고가", "1년 중 가장 높은 값에 닿은 종목"),
    NEW_LOW("52주 신저가", "1년 중 가장 낮은 값에 닿은 종목"),
    GAP_UP("갭 상승", "전날 종가보다 3% 넘게 높게 시작한 종목"),
    LIMIT_UP("상한가", "하루 오를 수 있는 최대 폭(30%) 가까이 오른 종목"),
    MARKET_CAP("시가총액", "덩치가 큰 종목"),
}

data class FeaturedRow(val quote: Quote, val metric: Double, val note: String)

/**
 * 시세 목록(전 종목 스냅숏)과 저장해 둔 일봉 기록으로 시장 요약·특징종목을 만든다.
 * [history] 가 비어 있으면 기록이 필요한 항목(거래량 급증·신고가)은 시세에 있는 52주 값으로만 계산한다.
 */
object MarketStats {
    private val spacOrPref = Regex("스팩|\\d+호$|우[A-C]?$|우\\(전환\\)$")

    fun isSpacOrPreferred(name: String): Boolean = spacOrPref.containsMatchIn(name.trim())

    fun breadth(quotes: List<Quote>, limitPct: Double = 29.5): Breadth = Breadth(
        up = quotes.count { it.changeRate > 0 },
        down = quotes.count { it.changeRate < 0 },
        flat = quotes.count { it.changeRate == 0.0 },
        upperLimit = quotes.count { it.changeRate >= limitPct },
        lowerLimit = quotes.count { it.changeRate <= -limitPct },
    )

    fun featured(
        kind: FeatureKind,
        quotes: List<Quote>,
        history: Map<String, List<Candle>> = emptyMap(),
        limit: Int = 50,
        excludeSpacPref: Boolean = true,
        minValue: Double = 0.0,
    ): List<FeaturedRow> {
        val base = quotes.filter { q ->
            q.price > 0 && (!excludeSpacPref || !isSpacOrPreferred(q.asset.name)) && q.value >= minValue
        }
        val rows: List<FeaturedRow> = when (kind) {
            FeatureKind.GAINERS -> base.filter { it.changeRate > 0 }.sortedByDescending { it.changeRate }.map { FeaturedRow(it, it.changeRate, Fmt.signedRate(it.changeRate)) }
            FeatureKind.LOSERS -> base.filter { it.changeRate < 0 }.sortedBy { it.changeRate }.map { FeaturedRow(it, it.changeRate, Fmt.signedRate(it.changeRate)) }
            FeatureKind.VALUE -> base.sortedByDescending { it.value }.map { FeaturedRow(it, it.value, Fmt.big(it.value, it.asset.market.currency)) }
            FeatureKind.VOLUME -> base.sortedByDescending { it.volume }.map { FeaturedRow(it, it.volume, Fmt.volume(it.volume)) }
            FeatureKind.MARKET_CAP -> base.filter { it.marketCap > 0 }.sortedByDescending { it.marketCap }.map { FeaturedRow(it, it.marketCap, Fmt.big(it.marketCap, it.asset.market.currency)) }
            FeatureKind.GAP_UP -> base.filter { it.gapRate >= 3 }.sortedByDescending { it.gapRate }.map { FeaturedRow(it, it.gapRate, "시가 " + Fmt.signedRate(it.gapRate)) }
            FeatureKind.LIMIT_UP -> base.filter { it.asset.market == Market.KR && it.changeRate >= 29.5 }.sortedByDescending { it.value }.map { FeaturedRow(it, it.changeRate, Fmt.signedRate(it.changeRate)) }
            FeatureKind.VOLUME_SURGE -> base.mapNotNull { q ->
                val h = history[q.asset.key] ?: return@mapNotNull null
                val past = h.filter { it.time < todayStart(q) }.takeLast(20)
                if (past.size < 10) return@mapNotNull null
                val avg = past.sumOf { it.volume } / past.size
                if (avg <= 0) return@mapNotNull null
                val ratio = q.volume / avg
                if (ratio < 2.5 || q.changeRate <= 0) null else FeaturedRow(q, ratio, "평소의 ${Fmt.decimal(ratio)}배")
            }.sortedByDescending { it.metric }
            FeatureKind.NEW_HIGH -> base.mapNotNull { q ->
                val high = high52(q, history) ?: return@mapNotNull null
                if (q.high >= high && q.high > 0 || q.price >= high) FeaturedRow(q, q.changeRate, "1년 최고 " + Fmt.price(high, q.asset.market.currency)) else null
            }.sortedByDescending { it.quote.value }
            FeatureKind.NEW_LOW -> base.mapNotNull { q ->
                val low = low52(q, history) ?: return@mapNotNull null
                if (q.low in 0.000001..low || q.price <= low) FeaturedRow(q, q.changeRate, "1년 최저 " + Fmt.price(low, q.asset.market.currency)) else null
            }.sortedByDescending { it.quote.value }
        }
        return rows.take(limit)
    }

    private fun todayStart(q: Quote): Long =
        if (q.time > 0) java.time.Instant.ofEpochSecond(q.time).atZone(KST).toLocalDate().epochKst() else Long.MAX_VALUE

    private fun high52(q: Quote, history: Map<String, List<Candle>>): Double? {
        q.high52?.takeIf { it > 0 }?.let { return it }
        val h = history[q.asset.key]?.filter { it.time < todayStart(q) }?.takeLast(250) ?: return null
        if (h.size < 120) return null
        return h.maxOf { it.high }
    }

    private fun low52(q: Quote, history: Map<String, List<Candle>>): Double? {
        q.low52?.takeIf { it > 0 }?.let { return it }
        val h = history[q.asset.key]?.filter { it.time < todayStart(q) }?.takeLast(250) ?: return null
        if (h.size < 120) return null
        return h.minOf { it.low }
    }
}

/** 종목필터 조건. 비워 둔 조건은 보지 않는다. */
@Serializable
data class ScreenerFilter(
    val exchanges: Set<String> = emptySet(),
    val priceMin: Double? = null,
    val priceMax: Double? = null,
    val changeMin: Double? = null,
    val changeMax: Double? = null,
    /** 시가총액 (억 원 / 백만 달러) */
    val capMin: Double? = null,
    val capMax: Double? = null,
    /** 거래대금 최소 (억 원 / 백만 달러) */
    val valueMin: Double? = null,
    val excludeSpacPref: Boolean = true,
    // 아래는 일봉 기록이 있어야 볼 수 있다.
    val aboveMa: Int? = null,
    val belowMa: Int? = null,
    val rsiMin: Double? = null,
    val rsiMax: Double? = null,
    val goldenCrossDays: Int? = null,
    val nearHighPct: Double? = null,
    val volumeRatioMin: Double? = null,
    /** 최근 n일 안에 이 전략이 매수 신호를 낸 종목 */
    val strategyId: String? = null,
    val strategyDays: Int = 3,
    val perMax: Double? = null,
    val sort: ScreenerSort = ScreenerSort.VALUE,
) {
    val needsHistory: Boolean
        get() = aboveMa != null || belowMa != null || rsiMin != null || rsiMax != null || goldenCrossDays != null ||
            nearHighPct != null || volumeRatioMin != null || strategyId != null

    val activeCount: Int
        get() = listOf(
            exchanges.isNotEmpty(), priceMin != null || priceMax != null, changeMin != null || changeMax != null,
            capMin != null || capMax != null, valueMin != null, aboveMa != null, belowMa != null,
            rsiMin != null || rsiMax != null, goldenCrossDays != null, nearHighPct != null, volumeRatioMin != null,
            strategyId != null, perMax != null,
        ).count { it }
}

enum class ScreenerSort(val label: String) {
    VALUE("거래대금"), CHANGE("등락률"), CAP("시가총액"), VOLUME("거래량"), NAME("이름")
}

data class ScreenerRow(val quote: Quote, val notes: List<String>)

object Screener {
    fun run(filter: ScreenerFilter, quotes: List<Quote>, history: Map<String, List<Candle>>): List<ScreenerRow> {
        val unit = { q: Quote -> if (q.asset.market.currency == Currency.USD) 1e6 else 1e8 }
        val strategy = filter.strategyId?.let { Strategies.find(it) }
        val out = ArrayList<ScreenerRow>()
        for (q in quotes) {
            if (q.price <= 0) continue
            if (filter.exchanges.isNotEmpty() && q.asset.exchange !in filter.exchanges) continue
            if (filter.excludeSpacPref && q.asset.market == Market.KR && MarketStats.isSpacOrPreferred(q.asset.name)) continue
            if (filter.priceMin != null && q.price < filter.priceMin) continue
            if (filter.priceMax != null && q.price > filter.priceMax) continue
            if (filter.changeMin != null && q.changeRate < filter.changeMin) continue
            if (filter.changeMax != null && q.changeRate > filter.changeMax) continue
            if (filter.capMin != null && q.marketCap < filter.capMin * unit(q)) continue
            if (filter.capMax != null && q.marketCap > filter.capMax * unit(q)) continue
            if (filter.valueMin != null && q.value < filter.valueMin * unit(q)) continue
            if (filter.perMax != null && (q.per == null || q.per <= 0 || q.per > filter.perMax)) continue
            val notes = ArrayList<String>()
            if (filter.needsHistory) {
                val candles = history[q.asset.key] ?: continue
                if (candles.size < 30) continue
                val d = PriceData(candles)
                val i = d.size - 1
                val above = filter.aboveMa
                if (above != null) {
                    val m = d.sma(above)[i]
                    if (m.isNaN() || d.close[i] <= m) continue
                    notes += "${above}일선 위"
                }
                val below = filter.belowMa
                if (below != null) {
                    val m = d.sma(below)[i]
                    if (m.isNaN() || d.close[i] >= m) continue
                    notes += "${below}일선 아래"
                }
                if (filter.rsiMin != null || filter.rsiMax != null) {
                    val r = d.rsi()[i]
                    if (r.isNaN() || (filter.rsiMin != null && r < filter.rsiMin) || (filter.rsiMax != null && r > filter.rsiMax)) continue
                    notes += "RSI ${Fmt.decimal(r)}"
                }
                val days = filter.goldenCrossDays
                if (days != null) {
                    val hit = (0 until days).any { k -> Cross.up(d.sma(5), d.sma(20), i - k) }
                    if (!hit) continue
                    notes += "${days}일 안 골든크로스"
                }
                val pct = filter.nearHighPct
                if (pct != null) {
                    val h = d.highest(minOf(250, d.size))[i]
                    if (h.isNaN() || d.close[i] < h * (1 - pct / 100)) continue
                    notes += "1년 고가의 ${Fmt.decimal(d.close[i] / h * 100)}%"
                }
                val minRatio = filter.volumeRatioMin
                if (minRatio != null) {
                    val vr = d.volumeRatio()[i]
                    if (vr.isNaN() || vr < minRatio) continue
                    notes += "거래량 ${Fmt.decimal(vr)}배"
                }
                if (strategy != null) {
                    val sig = strategy.signals(d)
                    val hit = (0 until filter.strategyDays).any { k -> i - k >= 0 && sig[i - k] == Signal.BUY }
                    if (!hit) continue
                    notes += strategy.name
                }
            }
            out += ScreenerRow(q, notes)
        }
        return when (filter.sort) {
            ScreenerSort.VALUE -> out.sortedByDescending { it.quote.value }
            ScreenerSort.CHANGE -> out.sortedByDescending { it.quote.changeRate }
            ScreenerSort.CAP -> out.sortedByDescending { it.quote.marketCap }
            ScreenerSort.VOLUME -> out.sortedByDescending { it.quote.volume }
            ScreenerSort.NAME -> out.sortedBy { it.quote.asset.name }
        }
    }
}
