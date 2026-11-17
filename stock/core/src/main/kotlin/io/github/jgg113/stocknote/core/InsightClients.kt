package io.github.jgg113.stocknote.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 증권사 투자의견 한 건 */
data class AnalystOpinion(val date: Long, val opinion: String, val previous: String, val target: Double, val broker: String)

/** 지분 변동 보고 한 건 (5% 이상 주주 또는 임원) */
data class OwnershipReport(
    val date: Long,
    val reporter: String,
    val role: String,
    /** 보고 뒤 가진 주식 수 */
    val shares: Double,
    /** 이번에 늘거나 줄어든 주식 수 */
    val change: Double,
    val ratePct: Double?,
    val rateChangePct: Double?,
    val reason: String,
    val link: String,
)

/** 배당 (사업보고서 기준) */
@Serializable
data class DividendInfo(
    val year: Int,
    /** 주당 현금배당금 (원) */
    val perShare: Double?,
    val yieldPct: Double?,
    /** 배당성향: 순이익 중 배당으로 준 비율 (%) */
    val payoutPct: Double?,
    val prevPerShare: Double?,
    val prev2PerShare: Double?,
)

/** 코인 공포·탐욕 지수 (alternative.me, 0 = 극단적 공포 ~ 100 = 극단적 탐욕) */
data class FearGreed(val value: Int, val label: String, val time: Long, val history: List<Int>)

/** 키 없이 쓰는 공개 자료: 코인 공포·탐욕 지수 */
class FearGreedClient(private val http: Http = NetHttp()) {
    fun crypto(days: Int = 30): FearGreed? = parse(network("공포·탐욕 지수") { http.get("$URL?limit=$days") }.body)

    companion object {
        const val URL = "https://api.alternative.me/fng/"

        fun label(v: Int): String = MarketMoodCalc.label(v)

        fun parse(body: String): FearGreed? {
            // 최근 것부터 온다.
            val rows = (parseJson(body).obj()?.get("data") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val values = rows.mapNotNull { it.dbl("value")?.toInt() }
            val first = rows.firstOrNull() ?: return null
            val v = values.firstOrNull() ?: return null
            return FearGreed(v, label(v), first.dbl("timestamp")?.toLong() ?: 0, values.reversed())
        }
    }
}

/**
 * 해외 거래소의 코인 달러(USDT) 가격. 키 없이 쓰는 공개 시세다.
 * 한 곳이 막혀 있어도 되도록 OKX → 바이낸스 순으로 물어본다.
 */
class GlobalCoinClient(private val http: Http = NetHttp()) {
    /** 코인 기호(BTC…) → 달러 가격, 그리고 어디서 받았는지 */
    fun usdPrices(): Pair<Map<String, Double>, String> {
        var last: Exception? = null
        for ((name, fetch) in listOf<Pair<String, () -> Map<String, Double>>>("OKX" to ::okx, "바이낸스" to ::binance)) {
            try {
                val m = fetch()
                if (m.size >= 10) return m to name
            } catch (e: Exception) {
                last = e
            }
        }
        throw DataException("해외 코인 시세를 받지 못했습니다. ${last?.message.orEmpty()}".trim(), "GLOBAL_COIN", retryable = true)
    }

    private fun okx(): Map<String, Double> = parseOkx(network("OKX") { http.get(OKX_URL) }.body)

    private fun binance(): Map<String, Double> = parseBinance(network("바이낸스") { http.get(BINANCE_URL) }.body)

    companion object {
        const val OKX_URL = "https://www.okx.com/api/v5/market/tickers?instType=SPOT"
        const val BINANCE_URL = "https://api.binance.com/api/v3/ticker/price"

        fun parseOkx(body: String): Map<String, Double> =
            (parseJson(body).obj()?.get("data") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
                val id = o.str("instId")
                if (!id.endsWith("-USDT")) return@mapNotNull null
                val p = o.dbl("last")?.takeIf { it > 0 } ?: return@mapNotNull null
                id.removeSuffix("-USDT") to p
            }.toMap()

        fun parseBinance(body: String): Map<String, Double> = parseJson(body).asList().mapNotNull { o ->
            val s = o.str("symbol")
            if (!s.endsWith("USDT")) return@mapNotNull null
            val p = o.dbl("price")?.takeIf { it > 0 } ?: return@mapNotNull null
            s.removeSuffix("USDT") to p
        }.toMap()
    }
}

/** 시장 브리핑·포트폴리오 진단을 AI 에게 물을 글. 앱이 계산한 숫자만 넣어 AI 가 숫자를 지어내지 않게 한다. */
object BriefPrompt {
    fun market(
        market: Market,
        mood: MarketMood?,
        breadth: Breadth?,
        indices: List<IndexQuote>,
        gainers: List<Quote>,
        losers: List<Quote>,
        headlines: List<NewsItem>,
        fearGreed: FearGreed? = null,
        kimchiPct: Double? = null,
    ): String = buildString {
        appendLine("너는 개인 투자자를 돕는 차분한 한국어 시장 해설가야. 아래 자료만 근거로 오늘 ${market.label} 시장을 5~7문장으로 정리해 줘.")
        appendLine("자료에 없는 숫자는 지어내지 말고, 좋은 점과 조심할 점을 함께 말해. 특정 종목을 사라고 하지 마. 마지막 줄에 '참고용이며 투자 판단은 본인 책임'이라고 덧붙여.")
        appendLine()
        indices.forEach { appendLine("[지수] ${it.name} ${Fmt.decimal(it.value)} (${Fmt.signedRate(it.changeRate)})") }
        if (breadth != null) appendLine("[등락 종목 수] 상승 ${breadth.up}, 하락 ${breadth.down}, 보합 ${breadth.flat}")
        if (mood != null) {
            appendLine("[시장 심리 점수] ${mood.score}/100 (${mood.label}) — 0은 극단적 공포, 100은 극단적 탐욕")
            mood.parts.forEach { appendLine("- ${it.name}: ${it.text} (${it.score}점)") }
        }
        if (fearGreed != null) appendLine("[코인 공포·탐욕 지수] ${fearGreed.value}/100 (${fearGreed.label})")
        if (kimchiPct != null) appendLine("[김치 프리미엄] ${Fmt.signedRate(kimchiPct)} (국내 가격이 해외보다 비싼 정도)")
        val cur = market.currency
        if (gainers.isNotEmpty()) appendLine("[많이 오른 종목] " + gainers.take(8).joinToString { "${it.asset.name} ${Fmt.signedRate(it.changeRate)}" })
        if (losers.isNotEmpty()) appendLine("[많이 내린 종목] " + losers.take(8).joinToString { "${it.asset.name} ${Fmt.signedRate(it.changeRate)}" })
        val active = (gainers + losers).sortedByDescending { it.value }.take(5)
        if (active.isNotEmpty()) appendLine("[거래대금 큰 종목] " + active.joinToString { "${it.asset.name} ${Fmt.big(it.value, cur)}" })
        if (headlines.isNotEmpty()) {
            appendLine("[최근 기사 제목]")
            headlines.take(12).forEach { appendLine("- ${it.title}") }
        }
    }

    fun portfolio(name: String, d: PortfolioDiagnosis, totalKrw: Double, pnlPct: Double): String = buildString {
        appendLine("너는 개인 투자자를 돕는 차분한 한국어 자산 관리 도우미야. 아래는 내 '$name' 계좌를 앱이 계산한 진단이야.")
        appendLine("이 자료만 근거로 ① 지금 구성의 좋은 점 ② 위험한 점 ③ 고려해 볼 조정 방향을 쉬운 말로 정리해 줘. 자료에 없는 숫자는 지어내지 말고, 특정 종목을 사거나 팔라고 단정하지 마.")
        appendLine("마지막 줄에 '참고용이며 투자 판단은 본인 책임'이라고 덧붙여.")
        appendLine()
        appendLine("[전체] 평가액 약 ${Fmt.big(totalKrw, Currency.KRW)}, 평가손익 ${Fmt.signedRate(pnlPct)}, 현금 비중 ${Fmt.decimal(d.cashPct)}%")
        appendLine("[진단 점수] ${d.score}/100 (${d.grade})")
        appendLine("[지난 ${d.days}거래일 기준] 연 변동성 ${Fmt.decimal(d.volatilityPct)}%, 최대 낙폭 -${Fmt.decimal(d.maxDrawdownPct)}%, 같은 비중으로 들고 있었다면 수익률 ${Fmt.signedRate(d.returnPct)}")
        appendLine("[분산] 가장 큰 종목 비중 ${Fmt.decimal(d.topWeightPct)}%, 고르게 나눈 종목 수 환산 ${Fmt.decimal(d.effectiveCount)}개" + if (d.avgCorrelation.isNaN()) "" else ", 종목 간 평균 상관 ${Fmt.decimal(d.avgCorrelation)}")
        appendLine("[종목별] 이름: 비중 / 연 변동성 / 전체 흔들림에서 차지하는 몫 / 흔들림을 고르게 맞춘 비중")
        d.rows.forEach { appendLine("- ${it.asset.name}: ${Fmt.decimal(it.weightPct)}% / ${Fmt.decimal(it.volPct)}% / ${Fmt.decimal(it.riskSharePct)}% / ${Fmt.decimal(it.balancedWeightPct)}%") }
        appendLine("[앱이 찾은 점]")
        d.findings.forEach { appendLine("- ${it.text}") }
    }
}
