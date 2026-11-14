package io.github.jgg113.stocknote.core

import java.time.LocalDate
import java.time.LocalDateTime

/** 업비트 공개 시세 API (키 필요 없음, 실시간). 원화(KRW) 마켓만 쓴다. */
class UpbitClient(private val http: Http = UrlConnectionHttp()) {
    private fun call(path: String, params: Map<String, String> = emptyMap()): String {
        val url = BASE + path + if (params.isEmpty()) "" else "?" + query(params)
        val r = network("업비트") { http.get(url, mapOf("Accept" to "application/json")) }
        if (r.status == 429) throw DataException("업비트 요청이 너무 잦습니다. 잠시 뒤 다시 시도하세요.", "429", retryable = true)
        if (r.status != 200) throw DataException("업비트 시세를 받지 못했습니다 (HTTP ${r.status}).", "HTTP${r.status}", r.status >= 500)
        return r.body
    }

    fun markets(): List<Asset> = parseMarkets(call("/v1/market/all", mapOf("isDetails" to "true")))

    /** 원화 마켓 전체 현재가 (한 번에) */
    fun allTickers(names: Map<String, String>): List<Quote> = parseTickers(call("/v1/ticker/all", mapOf("quote_currencies" to "KRW")), names)

    fun tickers(markets: List<String>, names: Map<String, String>): List<Quote> =
        markets.chunked(100).flatMap { parseTickers(call("/v1/ticker", mapOf("markets" to it.joinToString(","))), names) }

    /** 일봉 [count] 개 (200개씩 나눠 받는다) */
    fun daily(market: String, count: Int = 400): List<Candle> = paged(market, "/v1/candles/days", count, daily = true)

    fun minutes(market: String, unit: Int, count: Int = 200): List<Candle> = paged(market, "/v1/candles/minutes/$unit", count, daily = false)

    private fun paged(market: String, path: String, count: Int, daily: Boolean): List<Candle> {
        val out = ArrayList<Candle>()
        var to: String? = null
        var left = count
        while (left > 0) {
            val params = linkedMapOf("market" to market, "count" to minOf(200, left).toString())
            to?.let { params["to"] = it }
            val page = parseCandles(call(path, params), daily)
            if (page.candles.isEmpty()) break
            out += page.candles
            left -= page.candles.size
            to = page.oldestUtc?.let { it + "Z" } ?: break
            if (page.candles.size < 200) break
            Thread.sleep(120)
        }
        return out.distinctBy { it.time }.sortedBy { it.time }
    }

    class CandlePage(val candles: List<Candle>, val oldestUtc: String?)

    companion object {
        const val BASE = "https://api.upbit.com"

        fun parseMarkets(body: String): List<Asset> = parseJson(body).asList()
            .filter { it.str("market").startsWith("KRW-") }
            .map { Asset(Market.CRYPTO, it.str("market"), it.str("korean_name").ifBlank { it.str("english_name") }, "UPBIT") }

        fun parseTickers(body: String, names: Map<String, String>): List<Quote> = parseJson(body).asList().mapNotNull { o ->
            val market = o.str("market")
            if (!market.startsWith("KRW-")) return@mapNotNull null
            val price = o.dbl("trade_price") ?: return@mapNotNull null
            Quote(
                asset = Asset(Market.CRYPTO, market, names[market] ?: market.removePrefix("KRW-"), "UPBIT"),
                price = price,
                change = o.dbl("signed_change_price") ?: 0.0,
                changeRate = (o.dbl("signed_change_rate") ?: 0.0) * 100,
                open = o.dbl("opening_price") ?: 0.0,
                high = o.dbl("high_price") ?: 0.0,
                low = o.dbl("low_price") ?: 0.0,
                prevClose = o.dbl("prev_closing_price") ?: 0.0,
                volume = o.dbl("acc_trade_volume_24h") ?: 0.0,
                value = o.dbl("acc_trade_price_24h") ?: 0.0,
                high52 = o.dbl("highest_52_week_price"),
                low52 = o.dbl("lowest_52_week_price"),
                time = (o.dbl("trade_timestamp") ?: o.dbl("timestamp") ?: 0.0).toLong() / 1000,
                realtime = true,
                source = "업비트",
            )
        }

        /** 일봉은 UTC 날짜(한국 시각 09시 시작)를 그날로 본다. 분봉은 한국 시각 그대로. */
        fun parseCandles(body: String, daily: Boolean): CandlePage {
            val items = parseJson(body).asList()
            val candles = items.mapNotNull { o ->
                val utc = o.str("candle_date_time_utc")
                val kst = o.str("candle_date_time_kst")
                val time = runCatching {
                    if (daily) LocalDate.parse(utc.take(10)).epochKst() else LocalDateTime.parse(kst.take(19)).epochKst()
                }.getOrNull() ?: return@mapNotNull null
                Candle(
                    time,
                    o.dbl("opening_price") ?: return@mapNotNull null,
                    o.dbl("high_price") ?: return@mapNotNull null,
                    o.dbl("low_price") ?: return@mapNotNull null,
                    o.dbl("trade_price") ?: return@mapNotNull null,
                    o.dbl("candle_acc_trade_volume") ?: 0.0,
                    o.dbl("candle_acc_trade_price") ?: 0.0,
                )
            }
            val oldest = items.lastOrNull()?.str("candle_date_time_utc")?.take(19)
            return CandlePage(candles, oldest)
        }
    }
}
