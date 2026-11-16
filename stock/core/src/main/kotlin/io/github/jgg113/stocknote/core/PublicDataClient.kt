package io.github.jgg113.stocknote.core

import java.net.URLDecoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object ServiceKey {
    /** data.go.kr 의 Encoding 키(%2B…)와 Decoding 키(+/=) 를 모두 받아 Decoding 형태로 맞춘다. */
    fun normalize(raw: String): String {
        val compact = KeyText.clean(raw)
        if (!compact.contains('%')) return compact
        return runCatching { URLDecoder.decode(compact, "UTF-8") }.getOrDefault(compact)
    }

    /** 어떤 종류의 키인지 (포털 마이페이지에 Encoding·Decoding 두 가지로 나온다) */
    fun format(normalized: String): String = when {
        normalized.matches(Regex("[0-9a-fA-F]{64}")) -> "영문·숫자 64자 키"
        normalized.any { it == '+' || it == '/' || it == '=' } -> "기호(+ / =)가 든 키"
        else -> "영문·숫자 키"
    }
}

/**
 * 공공데이터포털 '금융위원회_주식시세정보'·'금융위원회_지수시세정보' (무료 인증키).
 * 장이 끝난 다음 영업일 오후에 전날 종가가 올라오므로 실시간이 아니다.
 */
class PublicDataClient(serviceKey: String, private val http: Http = UrlConnectionHttp()) {
    private val key = ServiceKey.normalize(serviceKey)

    private fun call(path: String, params: Map<String, String>): Page {
        if (key.isBlank()) throw DataException("설정에서 공공데이터포털 인증키를 넣으세요.", "NOKEY")
        val url = "$BASE/$path?" + query(linkedMapOf("serviceKey" to key, "resultType" to "json") + params)
        val r = network("공공데이터포털") { http.get(url) }
        return parse(r.status, r.body)
    }

    /**
     * 이 키가 공공데이터포털에 등록된 키인지, 다른 공공 API(온비드·국토교통부)로 확인해 본다.
     * 포털은 '키가 틀림'과 '이 API 를 신청하지 않음'을 같은 오류로 돌려주기 때문에, 다른 API 에서 통하는지 보면 둘을 가릴 수 있다.
     */
    fun standingElsewhere(): KeyStanding {
        var refused = false
        for (url in PROBES) {
            val r = runCatching { http.get(url + "serviceKey=" + enc(key)) }.getOrNull() ?: continue
            when (standing(r.status, r.body)) {
                KeyStanding.KNOWN -> return KeyStanding.KNOWN
                KeyStanding.UNKNOWN -> refused = true
                KeyStanding.UNSURE -> {}
            }
        }
        return if (refused) KeyStanding.UNKNOWN else KeyStanding.UNSURE
    }

    /** 화면에 보여 줄 키 모양 (값은 앞뒤 몇 글자만) */
    fun describeKey(): String = "${KeyText.masked(key)}, ${ServiceKey.format(key)}"

    /** 가장 최근 시세가 있는 날짜 (삼성전자 기록으로 찾는다) */
    fun latestDate(today: LocalDate = LocalDate.now(KST)): LocalDate? {
        val page = call(STOCK, mapOf("likeSrtnCd" to "005930", "beginBasDt" to ymd(today.minusDays(15)), "numOfRows" to "30", "pageNo" to "1"))
        return page.items.mapNotNull { runCatching { LocalDate.parse(it.str("basDt"), YMD) }.getOrNull() }.maxOrNull()
    }

    /** 그날 전 종목 시세 */
    fun snapshot(date: LocalDate, rows: Int = 1000): List<Quote> {
        val out = ArrayList<Quote>()
        var page = 1
        while (page <= 10) {
            val p = call(STOCK, mapOf("basDt" to ymd(date), "numOfRows" to rows.toString(), "pageNo" to page.toString()))
            out += p.items.mapNotNull { toQuote(it) }
            if (p.items.isEmpty() || out.size >= p.totalCount) break
            page++
        }
        return out.distinctBy { it.asset.code }
    }

    /** 한 종목의 일봉 ([from] 부터) */
    fun history(code: String, from: LocalDate): List<Candle> {
        val out = ArrayList<Candle>()
        var page = 1
        while (page <= 5) {
            val p = call(STOCK, mapOf("likeSrtnCd" to code, "beginBasDt" to ymd(from), "numOfRows" to "1000", "pageNo" to page.toString()))
            out += p.items.filter { it.str("srtnCd").removePrefix("A") == code }.mapNotNull { toCandle(it) }
            if (p.items.isEmpty() || page * 1000 >= p.totalCount) break
            page++
        }
        return out.distinctBy { it.time }.sortedBy { it.time }
    }

    /** 지수 (코스피·코스닥·코스피 200 …) 최근 값 */
    fun indices(names: List<String> = INDEX_NAMES, today: LocalDate = LocalDate.now(KST)): List<IndexQuote> = names.mapNotNull { name ->
        val items = call(INDEX, mapOf("idxNm" to name, "beginBasDt" to ymd(today.minusDays(15)), "numOfRows" to "30", "pageNo" to "1")).items
        val latest = items.maxByOrNull { it.str("basDt") } ?: return@mapNotNull null
        IndexQuote(
            name, latest.dbl("clpr") ?: return@mapNotNull null, latest.dbl("vs") ?: 0.0, latest.dbl("fltRt") ?: 0.0,
            runCatching { LocalDate.parse(latest.str("basDt"), YMD).epochKst() }.getOrDefault(0), "공공데이터포털",
        )
    }

    /** 지수 일봉 */
    fun indexHistory(name: String, from: LocalDate): List<Candle> =
        call(INDEX, mapOf("idxNm" to name, "beginBasDt" to ymd(from), "numOfRows" to "1000", "pageNo" to "1")).items
            .filter { it.str("idxNm") == name }.mapNotNull { toCandle(it) }.distinctBy { it.time }.sortedBy { it.time }

    class Page(val items: List<kotlinx.serialization.json.JsonObject>, val totalCount: Int)

    /** KNOWN: 포털이 아는 키, UNKNOWN: 물어본 곳 모두 모르는 키라고 함, UNSURE: 확인하지 못함 */
    enum class KeyStanding { KNOWN, UNKNOWN, UNSURE }

    companion object {
        const val BASE = "https://apis.data.go.kr/1160100/service"
        const val STOCK = "GetStockSecuritiesInfoService/getStockPriceInfo"
        const val INDEX = "GetMarketIndexInfoService/getStockMarketIndex"
        const val STOCK_PAGE = "https://www.data.go.kr/data/15094808/openapi.do"
        const val INDEX_PAGE = "https://www.data.go.kr/data/15094807/openapi.do"
        val INDEX_NAMES = listOf("코스피", "코스닥", "코스피 200")
        val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        fun ymd(d: LocalDate): String = d.format(YMD)

        /** 키가 등록됐는지만 보려고 물어보는 다른 공공 API (온비드 공매 물건, 국토교통부 아파트 실거래가·건축물대장) */
        val PROBES = listOf(
            "https://apis.data.go.kr/B010003/OnbidRlstListSrvc2/getRlstCltrList2?pageNo=1&numOfRows=1&resultType=json&",
            "https://apis.data.go.kr/1613000/RTMSDataSvcAptTrade/getRTMSDataSvcAptTrade?LAWD_CD=11110&DEAL_YMD=202601&pageNo=1&numOfRows=1&",
            "https://apis.data.go.kr/1613000/BldRgstHubService/getBrTitleInfo?sigunguCd=11110&bjdongCd=10100&numOfRows=1&pageNo=1&",
        )

        /** 응답 한 건으로 본 키 상태 */
        fun standing(status: Int, body: String): KeyStanding = when {
            body.contains("SERVICE_KEY_IS_NOT_REGISTERED") || body.contains("등록되지 않은") -> KeyStanding.UNKNOWN
            // 신청은 안 했지만 키는 아는 경우, 한도·기한 문제, 또는 정상 응답이면 포털이 아는 키다.
            body.contains("SERVICE_ACCESS_DENIED") || body.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS") || body.contains("DEADLINE_HAS_EXPIRED") ||
                body.contains("UNREGISTERED_IP") || (status == 200 && body.contains("resultCode")) -> KeyStanding.KNOWN
            else -> KeyStanding.UNSURE
        }

        private val reasonText = mapOf(
            "SERVICE_KEY_IS_NOT_REGISTERED_ERROR" to "인증키가 등록되지 않았습니다. 마이페이지의 일반 인증키를 그대로 붙여넣었는지, 이 API 를 활용신청했는지 확인하세요. 신청 직후라면 1~2시간 뒤에 됩니다.",
            "SERVICE_ACCESS_DENIED_ERROR" to "이 API 활용신청이 안 되어 있습니다. 공공데이터포털에서 활용신청하세요.",
            "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR" to "오늘 호출 한도를 넘었습니다. 내일 다시 됩니다.",
            "SERVICE_KEY_IS_NOT_REGISTERED" to "인증키가 등록되지 않았습니다.",
            "TEMPORARILY_DISABLE_THE_SERVICEKEY_ERROR" to "인증키가 잠시 쓸 수 없게 되었습니다. 잠시 후 다시 시도하세요.",
        )

        fun parse(status: Int, body: String): Page {
            val text = body.trim()
            // 오류는 JSON 이든 XML 이든 이름이 같다.
            Regex("(?:\"errMsg\"\\s*:\\s*\"|<errMsg>)([^\"<]+)").find(text)?.let { em ->
                val reason = Regex("(?:\"returnAuthMsg\"\\s*:\\s*\"|<returnAuthMsg>)([^\"<]+)").find(text)?.groupValues?.get(1).orEmpty()
                val code = listOf(reason, em.groupValues[1]).firstOrNull { it in reasonText } ?: em.groupValues[1]
                throw DataException(reasonText[code] ?: "공공데이터포털 오류: $reason ${em.groupValues[1]}".trim(), code)
            }
            if (status == 401 || status == 403) throw DataException("공공데이터포털 인증키가 맞지 않거나 이 API 를 활용신청하지 않았습니다 (HTTP $status).", "HTTP$status")
            if (status == 429) throw DataException("공공데이터포털 호출 한도를 넘었습니다. 잠시 뒤나 내일 다시 시도하세요.", "429", retryable = true)
            if (status != 200) throw DataException("공공데이터포털 응답 오류 (HTTP $status)", "HTTP$status", status >= 500)
            val root = parseJson(text).obj() ?: throw DataException("공공데이터포털 응답을 읽지 못했습니다.", "PARSE")
            val response = root["response"].obj() ?: root
            val header = response["header"].obj()
            val code = header?.str("resultCode").orEmpty()
            if (code.isNotEmpty() && code != "00") throw DataException("공공데이터포털 오류 ($code): ${header?.str("resultMsg")}", code)
            val bodyObj = response["body"].obj()
            val items = bodyObj?.get("items").obj()?.get("item").asList()
            val total = bodyObj?.str("totalCount")?.toIntOrNull() ?: items.size
            return Page(items, total)
        }

        fun toQuote(o: kotlinx.serialization.json.JsonObject): Quote? {
            val code = o.str("srtnCd").removePrefix("A").ifBlank { return null }
            val close = o.dbl("clpr") ?: return null
            val vs = o.dbl("vs") ?: 0.0
            val date = runCatching { LocalDate.parse(o.str("basDt"), YMD) }.getOrNull()
            return Quote(
                asset = Asset(Market.KR, code, o.str("itmsNm"), o.str("mrktCtg")),
                price = close,
                change = vs,
                changeRate = o.dbl("fltRt") ?: 0.0,
                open = o.dbl("mkp") ?: 0.0,
                high = o.dbl("hipr") ?: 0.0,
                low = o.dbl("lopr") ?: 0.0,
                prevClose = close - vs,
                volume = o.dbl("trqu") ?: 0.0,
                value = o.dbl("trPrc") ?: 0.0,
                marketCap = o.dbl("mrktTotAmt") ?: 0.0,
                shares = o.dbl("lstgStCnt") ?: 0.0,
                time = date?.epochKst() ?: 0,
                realtime = false,
                source = "공공데이터포털",
            )
        }

        fun toCandle(o: kotlinx.serialization.json.JsonObject): Candle? {
            val date = runCatching { LocalDate.parse(o.str("basDt"), YMD) }.getOrNull() ?: return null
            val close = o.dbl("clpr") ?: return null
            val open = o.dbl("mkp")?.takeIf { it > 0 } ?: close
            return Candle(
                date.epochKst(), open,
                o.dbl("hipr")?.takeIf { it > 0 } ?: maxOf(open, close),
                o.dbl("lopr")?.takeIf { it > 0 } ?: minOf(open, close),
                close, o.dbl("trqu") ?: 0.0, o.dbl("trPrc") ?: 0.0,
            )
        }
    }
}
