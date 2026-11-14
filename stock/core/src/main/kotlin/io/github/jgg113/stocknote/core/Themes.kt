package io.github.jgg113.stocknote.core

import kotlinx.serialization.Serializable

/**
 * 테마 하나. 기본 테마는 업종·사업 내용으로 이 앱에서 직접 묶은 대표 종목 목록이며, 설정에서 고치거나 새로 만들 수 있다.
 * [members] 는 Asset.key (예: "KR:005930", "US:NVDA", "CRYPTO:KRW-BTC").
 */
@Serializable
data class Theme(
    val id: String,
    val name: String,
    val description: String = "",
    val members: List<String>,
    val custom: Boolean = false,
) {
    val market: Market? get() = members.firstOrNull()?.let { Asset.parseKey(it)?.first }
}

data class ThemePerformance(
    val theme: Theme,
    val quotes: List<Quote>,
    val avgChangePct: Double,
    val upCount: Int,
    val leader: Quote?,
)

object Themes {
    private fun kr(id: String, name: String, desc: String, vararg codes: String) = Theme(id, name, desc, codes.map { "KR:$it" })
    private fun us(id: String, name: String, desc: String, vararg codes: String) = Theme(id, name, desc, codes.map { "US:$it" })
    private fun coin(id: String, name: String, desc: String, vararg codes: String) = Theme(id, name, desc, codes.map { "CRYPTO:KRW-$it" })

    val builtIn: List<Theme> = listOf(
        kr("kr_semi", "반도체", "메모리·장비·소재·부품", "005930", "000660", "042700", "058470", "039030", "240810", "403870", "095340", "357780", "009150", "011070"),
        kr("kr_battery", "2차전지", "배터리 셀·양극재·소재", "373220", "006400", "051910", "003670", "247540", "086520", "066970", "096770"),
        kr("kr_bio", "바이오·제약", "신약·바이오시밀러·위탁생산", "207940", "068270", "196170", "000100", "128940", "326030", "302440", "141080", "145020"),
        kr("kr_auto", "자동차", "완성차·부품", "005380", "000270", "012330"),
        kr("kr_defense", "방산", "무기·항공·우주", "012450", "047810", "079550", "064350"),
        kr("kr_ship", "조선", "선박 건조", "009540", "329180", "010140", "042660"),
        kr("kr_power", "전력 설비", "변압기·전선·전력기기", "267260", "298040", "010120", "034020"),
        kr("kr_nuclear", "원자력", "원전 설계·정비·기자재", "034020", "052690", "051600", "083650", "105840", "015760"),
        kr("kr_robot", "로봇", "협동로봇·로봇 부품", "277810", "454910", "108490", "090360"),
        kr("kr_platform", "인터넷·플랫폼", "포털·메신저·핀테크", "035420", "035720", "323410", "377300", "018260"),
        kr("kr_game", "게임", "게임 개발·배급", "259960", "036570", "251270", "263750", "293490", "112040", "225570", "095660", "078340"),
        kr("kr_ent", "엔터테인먼트", "음악·연예 기획", "352820", "041510", "035900", "122870"),
        kr("kr_beauty", "화장품", "화장품 브랜드·제조", "090430", "051900", "192820", "161890", "278470", "214150"),
        kr("kr_food", "음식료", "식품 제조", "097950", "271560", "004370", "003230", "033780"),
        kr("kr_bank", "금융지주·은행", "은행 중심 금융그룹", "105560", "055550", "086790", "316140", "323410"),
        kr("kr_broker", "증권·보험", "증권사·보험사", "006800", "005940", "016360", "071050", "032830", "000810"),
        kr("kr_construct", "건설", "건설·플랜트", "000720", "006360", "047040", "028050"),
        kr("kr_telecom", "통신", "이동통신 3사", "017670", "030200", "032640"),
        kr("kr_steel", "철강·소재", "철강·비철금속·화학", "005490", "004020", "010130", "011170", "051910"),
        kr("kr_transport", "운송", "항공·해운", "003490", "011200", "028670"),
        kr("kr_retail", "유통", "백화점·마트·편의점", "139480", "023530", "282330", "007070"),
        kr("kr_holding", "지주사", "그룹 지주회사", "003550", "034730", "028260", "000150", "001040"),
        kr("kr_space", "우주항공", "위성·발사체·항공", "012450", "047810", "099320", "189300"),
        us("us_bigtech", "미국 빅테크", "미국 대형 기술주", "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA"),
        us("us_semi", "미국 반도체", "설계·장비·파운드리", "NVDA", "AMD", "AVGO", "QCOM", "INTC", "MU", "TSM", "ASML", "AMAT", "LRCX", "TXN", "ARM"),
        us("us_software", "미국 소프트웨어", "클라우드·보안·데이터", "MSFT", "ORCL", "CRM", "ADBE", "NOW", "PLTR", "PANW", "CRWD", "SNOW", "DDOG"),
        us("us_finance", "미국 금융", "은행·카드·증권", "JPM", "BAC", "GS", "V", "MA"),
        us("us_health", "미국 헬스케어", "제약·바이오·의료", "LLY", "JNJ", "UNH", "ABBV", "MRK", "PFE", "AMGN"),
        us("us_consumer", "미국 소비재", "유통·음식료·생활용품", "WMT", "COST", "KO", "PEP", "PG", "MCD", "NKE", "SBUX"),
        us("us_etf", "미국 대표 ETF", "지수·채권·금 ETF", "SPY", "QQQ", "DIA", "IWM", "VOO", "SCHD", "TLT", "GLD", "SOXX"),
        coin("coin_major", "메이저 코인", "시가총액 상위", "BTC", "ETH", "XRP", "SOL"),
        coin("coin_l1", "레이어1", "자체 블록체인", "ETH", "SOL", "ADA", "AVAX", "DOT", "NEAR", "SUI", "APT", "TRX"),
        coin("coin_meme", "밈 코인", "커뮤니티·밈 기반", "DOGE", "SHIB", "PEPE"),
        coin("coin_defi", "디파이", "탈중앙 금융", "LINK", "UNI", "AAVE", "ONDO"),
    )
}

object ThemeStats {
    fun performance(theme: Theme, quotes: Map<String, Quote>): ThemePerformance? {
        val qs = theme.members.mapNotNull { quotes[it] }.filter { it.price > 0 }
        if (qs.isEmpty()) return null
        return ThemePerformance(theme, qs.sortedByDescending { it.changeRate }, qs.map { it.changeRate }.average(), qs.count { it.changeRate > 0 }, qs.maxByOrNull { it.changeRate })
    }

    fun rank(themes: List<Theme>, quotes: Map<String, Quote>): List<ThemePerformance> =
        themes.mapNotNull { performance(it, quotes) }.sortedByDescending { it.avgChangePct }

    /** 이 종목이 들어 있는 테마 */
    fun themesOf(assetKey: String, themes: List<Theme>): List<Theme> = themes.filter { assetKey in it.members }
}

/** 해외 종목 검색용 기본 목록 (티커·한국투자증권 거래소 코드·이름). 목록에 없는 티커도 직접 넣을 수 있다. */
object UsStocks {
    val list: List<Asset> = listOf(
        "AAPL" to "애플", "MSFT" to "마이크로소프트", "NVDA" to "엔비디아", "AMZN" to "아마존", "GOOGL" to "알파벳 A", "GOOG" to "알파벳 C",
        "META" to "메타", "TSLA" to "테슬라", "AVGO" to "브로드컴", "COST" to "코스트코", "NFLX" to "넷플릭스", "AMD" to "AMD",
        "ADBE" to "어도비", "PEP" to "펩시코", "CSCO" to "시스코", "INTC" to "인텔", "QCOM" to "퀄컴", "TXN" to "텍사스인스트루먼트",
        "AMAT" to "어플라이드머티어리얼즈", "LRCX" to "램리서치", "MU" to "마이크론", "ASML" to "ASML", "ARM" to "ARM 홀딩스",
        "PLTR" to "팔란티어", "PYPL" to "페이팔", "SBUX" to "스타벅스", "ABNB" to "에어비앤비", "MRVL" to "마벨 테크놀로지",
        "PANW" to "팔로알토 네트웍스", "CRWD" to "크라우드스트라이크", "MSTR" to "스트래티지", "COIN" to "코인베이스", "HOOD" to "로빈후드",
        "SMCI" to "슈퍼마이크로컴퓨터", "INTU" to "인튜이트", "ISRG" to "인튜이티브 서지컬", "BKNG" to "부킹홀딩스", "AMGN" to "암젠",
        "DDOG" to "데이터독", "QQQ" to "인베스코 QQQ", "SOXX" to "아이셰어즈 반도체", "TLT" to "아이셰어즈 20년+ 국채", "TQQQ" to "프로셰어즈 울트라프로 QQQ",
    ).map { (t, n) -> Asset(Market.US, t, n, "NAS") } + listOf(
        "JPM" to "JP모건", "V" to "비자", "MA" to "마스터카드", "JNJ" to "존슨앤드존슨", "WMT" to "월마트", "PG" to "P&G",
        "XOM" to "엑슨모빌", "CVX" to "셰브론", "KO" to "코카콜라", "DIS" to "디즈니", "BA" to "보잉", "NKE" to "나이키",
        "ORCL" to "오라클", "CRM" to "세일즈포스", "IBM" to "IBM", "UNH" to "유나이티드헬스", "HD" to "홈디포", "MCD" to "맥도날드",
        "GS" to "골드만삭스", "BAC" to "뱅크오브아메리카", "TSM" to "TSMC", "UBER" to "우버", "SNOW" to "스노우플레이크",
        "LLY" to "일라이릴리", "ABBV" to "애브비", "MRK" to "머크", "PFE" to "화이자", "T" to "AT&T", "VZ" to "버라이즌",
        "GE" to "GE 에어로스페이스", "CAT" to "캐터필러", "NOW" to "서비스나우", "BABA" to "알리바바", "SPOT" to "스포티파이",
    ).map { (t, n) -> Asset(Market.US, t, n, "NYS") } + listOf(
        "SPY" to "SPDR S&P500", "DIA" to "SPDR 다우존스", "IWM" to "아이셰어즈 러셀2000", "VOO" to "뱅가드 S&P500", "SCHD" to "슈왑 미국배당",
        "GLD" to "SPDR 금", "SLV" to "아이셰어즈 은", "SOXL" to "디렉시온 반도체 3배", "ARKK" to "ARK 이노베이션", "JEPI" to "JP모건 프리미엄 인컴",
    ).map { (t, n) -> Asset(Market.US, t, n, "AMS") }

    private val byTicker = list.associateBy { it.code }

    fun find(ticker: String): Asset? = byTicker[ticker.uppercase()]

    /** 거래소를 모르는 티커는 나스닥 → 뉴욕 → 아멕스 순으로 시도한다. */
    val exchangeOrder = listOf("NAS", "NYS", "AMS")

    fun exchangeLabel(code: String) = when (code) {
        "NAS" -> "나스닥"
        "NYS" -> "뉴욕"
        "AMS" -> "아멕스"
        else -> code
    }
}
