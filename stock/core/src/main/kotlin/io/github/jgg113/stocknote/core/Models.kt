package io.github.jgg113.stocknote.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 시장 구분. 국내 주식, 해외(미국) 주식, 가상화폐(업비트 원화 마켓). */
@Serializable
enum class Market(val label: String, val currency: Currency) {
    KR("국내", Currency.KRW),
    US("해외", Currency.USD),
    CRYPTO("코인", Currency.KRW),
}

@Serializable
enum class Currency(val symbol: String) { KRW("원"), USD("$") }

/**
 * 종목 하나.
 * - KR: code = 단축코드(005930), exchange = KOSPI/KOSDAQ/KONEX
 * - US: code = 티커(AAPL), exchange = NAS/NYS/AMS (한국투자증권 거래소 코드)
 * - CRYPTO: code = 업비트 마켓(KRW-BTC), exchange = UPBIT
 */
@Serializable
data class Asset(
    val market: Market,
    val code: String,
    val name: String,
    val exchange: String = "",
) {
    val key: String get() = "${market.name}:$code"

    /** 목록에 보여 줄 짧은 코드 (KRW-BTC → BTC) */
    val shortCode: String get() = if (market == Market.CRYPTO) code.removePrefix("KRW-") else code

    companion object {
        fun parseKey(key: String): Pair<Market, String>? {
            val market = runCatching { Market.valueOf(key.substringBefore(':')) }.getOrNull() ?: return null
            return market to key.substringAfter(':')
        }
    }
}

/** 봉 간격 */
@Serializable
enum class Timeframe(val label: String, val minutes: Int) {
    MIN1("1분", 1),
    MIN5("5분", 5),
    MIN15("15분", 15),
    MIN60("60분", 60),
    DAY("일", 1440),
    WEEK("주", 10080),
    MONTH("월", 43200),
    ;

    val intraday: Boolean get() = minutes < 1440
}

/** 봉 하나. [time] 은 봉 시작 시각(epoch 초). 일봉은 그날 0시(한국 시각). */
@Serializable
data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    /** 거래대금 (해당 통화) */
    val value: Double = 0.0,
) {
    val date: LocalDate get() = Instant.ofEpochSecond(time).atZone(KST).toLocalDate()
    val dateTime: LocalDateTime get() = Instant.ofEpochSecond(time).atZone(KST).toLocalDateTime()
    val bullish: Boolean get() = close >= open
}

val KST: ZoneId = ZoneId.of("Asia/Seoul")

fun LocalDate.epochKst(): Long = atStartOfDay(KST).toEpochSecond()

fun LocalDateTime.epochKst(): Long = atZone(KST).toEpochSecond()

/** 현재가 (또는 가장 최근 종가) */
@Serializable
data class Quote(
    val asset: Asset,
    val price: Double,
    val change: Double,
    /** 등락률 (%) */
    val changeRate: Double,
    val open: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val prevClose: Double = 0.0,
    val volume: Double = 0.0,
    /** 거래대금 (해당 통화) */
    val value: Double = 0.0,
    /** 시가총액 (해당 통화) */
    val marketCap: Double = 0.0,
    val shares: Double = 0.0,
    val per: Double? = null,
    val pbr: Double? = null,
    val eps: Double? = null,
    val bps: Double? = null,
    val high52: Double? = null,
    val low52: Double? = null,
    /** 자료 기준 시각 (epoch 초) */
    val time: Long = 0,
    /** 실시간이면 참, 전일 종가 자료면 거짓 */
    val realtime: Boolean = false,
    val source: String = "",
) {
    val up: Boolean get() = change > 0
    val down: Boolean get() = change < 0
    val gapRate: Double get() = if (prevClose > 0 && open > 0) (open / prevClose - 1) * 100 else 0.0
}

/** 지수 (코스피·코스닥 등) */
@Serializable
data class IndexQuote(
    val name: String,
    val value: Double,
    val change: Double,
    val changeRate: Double,
    val time: Long = 0,
    val source: String = "",
)

/** 공시·뉴스 한 줄 */
@Serializable
data class NewsItem(
    val title: String,
    val link: String,
    val source: String,
    /** epoch 초, 모르면 0 */
    val published: Long = 0,
    val kind: NewsKind = NewsKind.NEWS,
)

@Serializable
enum class NewsKind(val label: String) { NEWS("뉴스"), DISCLOSURE("공시") }

/** 데이터를 받다가 생긴 오류. 쉬운 설명을 담는다. */
class DataException(message: String, val code: String = "", val retryable: Boolean = false) : Exception(message)

object Fmt {
    private val krw = java.text.DecimalFormat("#,##0")
    private val dec2 = java.text.DecimalFormat("#,##0.00")
    private val dec4 = java.text.DecimalFormat("#,##0.####")

    /** 가격 표시. 원화는 정수, 달러는 소수 둘째 자리, 작은 코인 값은 넉째 자리까지. */
    fun price(value: Double, currency: Currency): String = when {
        value.isNaN() -> "-"
        currency == Currency.USD -> dec2.format(value)
        value != 0.0 && kotlin.math.abs(value) < 100 -> dec4.format(value)
        else -> krw.format(value)
    }

    fun priceWithUnit(value: Double, currency: Currency): String =
        if (currency == Currency.USD) "$" + price(value, currency) else price(value, currency) + "원"

    fun signedRate(rate: Double): String = when {
        rate.isNaN() -> "-"
        rate > 0 -> "+" + dec2.format(rate) + "%"
        else -> dec2.format(rate) + "%"
    }

    fun rate(rate: Double): String = if (rate.isNaN()) "-" else dec2.format(rate) + "%"

    fun signedPrice(change: Double, currency: Currency): String = when {
        change > 0 -> "▲" + price(change, currency)
        change < 0 -> "▼" + price(-change, currency)
        else -> "0"
    }

    fun number(value: Double): String = if (value.isNaN()) "-" else krw.format(value)

    private val qty8 = java.text.DecimalFormat("#,##0.########")

    /** 수량: 코인은 소수 여덟째 자리까지, 주식은 정수 */
    fun quantity(value: Double, market: Market): String = if (market == Market.CRYPTO) qty8.format(value) else krw.format(value)

    fun decimal(value: Double): String = if (value.isNaN()) "-" else dec2.format(value)

    /** 큰 금액을 조·억·만 단위로 (원화) 또는 B/M (달러) */
    fun big(value: Double, currency: Currency = Currency.KRW): String {
        if (value.isNaN() || value == 0.0) return "-"
        val v = kotlin.math.abs(value)
        val sign = if (value < 0) "-" else ""
        return if (currency == Currency.USD) {
            when {
                v >= 1e12 -> sign + dec2.format(v / 1e12) + "T"
                v >= 1e9 -> sign + dec2.format(v / 1e9) + "B"
                v >= 1e6 -> sign + dec2.format(v / 1e6) + "M"
                else -> sign + krw.format(v)
            }
        } else {
            when {
                v >= 1e12 -> sign + dec2.format(v / 1e12) + "조"
                v >= 1e8 -> sign + krw.format(v / 1e8) + "억"
                v >= 1e4 -> sign + krw.format(v / 1e4) + "만"
                else -> sign + krw.format(v)
            }
        }
    }

    /** 거래량 (주·개) */
    fun volume(value: Double): String {
        val v = kotlin.math.abs(value)
        return when {
            v >= 1e8 -> dec2.format(v / 1e8) + "억"
            v >= 1e4 -> krw.format(v / 1e4) + "만"
            v >= 100 || v == 0.0 -> krw.format(v)
            else -> dec4.format(v)
        }
    }

    fun date(epochSeconds: Long): String =
        if (epochSeconds <= 0) "-" else Instant.ofEpochSecond(epochSeconds).atZone(KST).toLocalDate().toString()

    fun dateTime(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "-"
        val t = Instant.ofEpochSecond(epochSeconds).atZone(KST)
        return "%d-%02d-%02d %02d:%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
    }

    fun shortDate(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "-"
        val t = Instant.ofEpochSecond(epochSeconds).atZone(KST)
        return "%02d.%02d".format(t.monthValue, t.dayOfMonth)
    }
}

/** 문자열 숫자를 읽는다 ("1,234", "+5.2", "" → null). */
fun String?.num(): Double? {
    if (this == null) return null
    val t = trim().replace(",", "").removePrefix("+")
    if (t.isEmpty() || t == "-") return null
    return t.toDoubleOrNull()
}
