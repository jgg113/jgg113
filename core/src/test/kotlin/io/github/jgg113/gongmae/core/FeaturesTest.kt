package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class GetWeb(val handler: (String, Map<String, String>) -> WebResponse) : WebTransport {
    val urls = mutableListOf<String>()
    override fun get(url: String, headers: Map<String, String>): WebResponse {
        urls += url
        return handler(url, headers)
    }
    override fun post(url: String, headers: Map<String, String>, body: String): WebResponse = handler(url, headers)
}

class PublicRecordsTest {
    @Test
    fun parcelConversions() {
        // 온비드: 산 여부 0 = 일반 → 표준 1
        val parcel = Parcel.fromOnbid("1144012400002450042")!!
        assertEquals("1144012400102450042", parcel.pnu)
        assertEquals("11440", parcel.sigunguCode)
        assertEquals("12400", parcel.dongCode)
        assertEquals("245-42", parcel.lot)
        assertEquals("산12", Parcel.fromOnbid("4161025321100120000")!!.lot)
        assertNull(Parcel.fromOnbid("1144012400000000000"))
        assertEquals("1168010600009360031", Parcel.onbidStylePnu("1168010600", "936-31"))
        assertEquals("1168010600100120003", Parcel.onbidStylePnu("1168010600", "산 12-3"))
        assertEquals("", Parcel.onbidStylePnu("11680106", "936"))
    }

    @Test
    fun courtRowGetsPnu() {
        val row = Json.parseToJsonElement(
            """{"docid":"B1","srnSaNo":"2022타경105284","boCd":"B000210","lclsUtilCd":"20000","hjguSido":"서울특별시","hjguSigu":"강남구",
               "hjguDong":"대치동","daepyoLotno":"936-31","srchHjguRdCd":"1168010600","srchHjguSiguCd":"11680","gamevalAmt":"450000000","minmaePrice":"360000000"}""",
        ).jsonObject
        val p = CourtClient.toProperty(row)!!
        assertEquals("936-31", Parcel.of(p)!!.lot)
        assertEquals("1168010600109360031", Parcel.of(p)!!.pnu)
    }

    @Test
    fun landAndHousePricesFromRealtyPrice() {
        val web = GetWeb { url, headers ->
            assertTrue(headers["Referer"]!!.startsWith("https://www.realtyprice.kr/notice/"))
            if (url.contains("gsiSearchListApi")) {
                WebResponse(200, """{"model":{"list":[{"base_year":"2025","gakuka_w":"12,000,000","notice_ymd":"20250430"},{"base_year":"2024","gakuka_w":"10,000,000","notice_ymd":"20240430"}]}}""")
            } else {
                WebResponse(200, """{"model":{"list":[{"base_ymd":"20250101","hprice_w":"321,000,000","tbook_area":"150.5","bldg_garea":"200"}]}}""")
            }
        }
        val client = PublicRecordsClient("", web)
        val parcel = Parcel.fromOnbid("1168010600009360031")!!
        val land = client.landPrices(parcel)
        assertEquals(listOf(2025, 2024), land.map { it.year })
        assertEquals(12_000_000, land.first().pricePerSqm)
        assertTrue(web.urls.first().contains("reg=11680&eub=10600&san=1&bun1=0936&bun2=0031"))
        val house = client.housePrices(parcel)
        assertEquals(321_000_000, house.single().price)
        val facts = PublicFacts(parcel, land, house)
        assertEquals(20.0, facts.landPriceChange!!, 0.01)
        val lot = Property(id = "x", usageSmall = "대", landAreaSqm = 10.0)
        assertEquals(120_000_000, facts.landValue(lot))
    }

    @Test
    fun buildingRegisterParsing() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><response><header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>
            <body><items>
            <item><bldNm>대치아파트</bldNm><mainAtchGbCdNm>주건축물</mainAtchGbCdNm><mainPurpsCdNm>공동주택</mainPurpsCdNm><etcPurps>아파트</etcPurps>
              <strctCdNm>철근콘크리트구조</strctCdNm><useAprDay>19920515</useAprDay><grndFlrCnt>15</grndFlrCnt><ugrndFlrCnt>1</ugrndFlrCnt>
              <totArea>12000.5</totArea><platArea>5000</platArea><hhldCnt>300</hhldCnt><rideUseElvtCnt>4</rideUseElvtCnt><indrAutoUtcnt>200</indrAutoUtcnt><regstrKindCdNm>집합</regstrKindCdNm></item>
            <item><bldNm>대치아파트</bldNm><mainAtchGbCdNm>주건축물</mainAtchGbCdNm><mainPurpsCdNm>공동주택</mainPurpsCdNm><useAprDay>19930101</useAprDay>
              <grndFlrCnt>20</grndFlrCnt><totArea>15000</totArea><hhldCnt>400</hhldCnt></item>
            <item><bldNm>경비실</bldNm><mainAtchGbCdNm>부속건축물</mainAtchGbCdNm><mainPurpsCdNm>제1종근린생활시설</mainPurpsCdNm></item>
            </items><totalCount>3</totalCount></body></response>"""
        val b = PublicRecordsClient.parseBuilding(DataGoXml.items(xml))!!
        assertEquals(700, b.households)
        assertEquals(20, b.groundFloors)
        assertEquals("19920515", b.approvalDate)
        assertEquals(34, b.age(LocalDate.of(2026, 10, 1)))
        assertEquals(2, b.buildingCount)
        assertEquals(false, b.residentialOnCommercial)

        val json = """{"response":{"header":{"resultCode":"00","resultMsg":"OK"},"body":{"items":{"item":[{"bldNm":"A","mainPurpsCdNm":"공동주택","hhldCnt":"10","useAprDay":"20200101"}]},"totalCount":1}}}"""
        assertEquals(10, PublicRecordsClient.parseBuilding(DataGoXml.items(json))!!.households)
        val err = """<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>30</returnReasonCode><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg></cmmMsgHeader></OpenAPI_ServiceResponse>"""
        assertEquals("30", assertFailsWith<OnbidApiException> { DataGoXml.items(err) }.code)
    }

    @Test
    fun analyzerUsesPublicFacts() {
        val villa = Property(id = "v", usageSmall = "다세대주택", appraisalPrice = 200_000_000, minBidPrice = 160_000_000)
        val building = BuildingInfo("", "제2종근린생활시설", "사무소", "", "19900101", 4, 0, null, null, null, null, 0, 0, 0, 0, "일반", 1)
        val a = Analyzer.analyze(villa, facts = PublicFacts(building = building), today = LocalDate.of(2026, 1, 1))
        assertTrue(a.risks.any { it.title.contains("근린생활") })
        assertTrue(a.risks.any { it.title.startsWith("노후 건물") })
        assertTrue(a.recordsChecked)

        val land = Property(id = "l", usageSmall = "답", landAreaSqm = 1000.0, areaSqm = 1000.0, appraisalPrice = 100_000_000, minBidPrice = 50_000_000)
        val facts = PublicFacts(landPrices = listOf(LandPriceYear(2025, 100_000, "")))
        val withFacts = Analyzer.analyze(land, facts = facts)
        assertTrue(withFacts.merits.any { it.title.startsWith("공시지가의 50") })
    }
}

class MarketScopeTest {
    @Test
    fun prefersSameComplexAndComputesTrend() {
        val p = Property(id = "a", usageSmall = "아파트", dong = "대치동", lotPnu = "1168010600009360031", areaSqm = 84.0, minBidPrice = 900_000_000)
        val trades = listOf(
            Trade("대치A", "대치동", "936-31", 84.0, 1_000_000_000, "2026.09.10", "5"),
            Trade("대치A", "대치동", "936-31", 84.5, 1_020_000_000, "2026.08.20", "7"),
            Trade("대치A", "대치동", "936-31", 84.0, 900_000_000, "2026.05.01", "3"),
            Trade("대치A", "대치동", "936-31", 84.0, 880_000_000, "2026.04.01", "2"),
            Trade("다른", "대치동", "1000", 84.0, 2_000_000_000, "2026.09.01", "9"),
        )
        val m = MolitClient.summarize(p, MarketKind.APT, trades, 6, LocalDate.of(2026, 10, 1))
        assertEquals("같은 단지", m.scope)
        assertEquals(4, m.trades.size)
        assertTrue(m.trendPercent!! > 5)
        val a = Analyzer.analyze(p, market = m)
        assertTrue(a.merits.any { it.title.startsWith("실거래 시세 상승세") })
    }
}

class AiCompareTest {
    @Test
    fun openAiCompatibleParsingAndFallback() {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"결론: 조건부 입찰\n위험: 임차인"}}]}"""
        assertEquals("결론: 조건부 입찰\n위험: 임차인", OpenAiCompatClient.parse("Groq", 200, ok))
        assertFailsWith<OpenAiCompatClient.ModelUnavailable> {
            OpenAiCompatClient.parse("Groq", 404, """{"error":{"message":"The model `x` does not exist","code":"model_not_found"}}""")
        }
        val quota = assertFailsWith<AiException> { OpenAiCompatClient.parse("OpenRouter", 429, """{"error":{"message":"quota","type":"insufficient_quota","code":"insufficient_quota"}}""") }
        assertTrue(quota.message!!.contains("크레딧"))
        assertTrue(assertFailsWith<AiException> { OpenAiCompatClient.parse("Groq", 401, "{}") }.message!!.contains("키"))

        val web = object : WebTransport {
            val models = mutableListOf<String>()
            override fun get(url: String, headers: Map<String, String>) = error("unused")
            override fun post(url: String, headers: Map<String, String>, body: String): WebResponse {
                val model = Json.parseToJsonElement(body).jsonObject["model"].toString().trim('"')
                models += model
                assertEquals("Bearer k", headers["Authorization"])
                return if (model == "m1") WebResponse(404, """{"error":{"message":"not found","code":"model_not_found"}}""") else WebResponse(200, ok)
            }
        }
        val answer = OpenAiCompatClient("Groq", "https://x/v1/chat/completions", "k", listOf("m1", "m2"), web).generate("q")
        assertEquals("m2", answer.model)
        assertEquals(listOf("m1", "m2"), web.models)
        assertTrue(OpenAiCompatClient.requestBody("gpt-5-mini", "q", "low").contains("\"reasoning_effort\":\"low\""))
        assertNull(AiEngines.create(AiProvider.GROQ_API, ""))
        assertNotNull(AiEngines.create(AiProvider.GROQ_API, "gsk-1"))
        assertEquals(listOf(AiProvider.GEMINI_API, AiProvider.GROQ_API, AiProvider.OPENROUTER_API), AiProvider.apis)
    }

    @Test
    fun verdicts() {
        assertEquals(AiVerdict.CONDITIONAL, AiVerdict.of("1) 한 줄 결론 (입찰 추천 / 조건부 / 비추천)\n결론: 조건부 — 임차인 확인 후 입찰"))
        assertEquals(AiVerdict.AVOID, AiVerdict.of("결론: 비추천합니다. 유치권이 있습니다."))
        assertEquals(AiVerdict.RECOMMEND, AiVerdict.of("**결론**: 입찰 추천\n장점…"))
        assertEquals(AiVerdict.AVOID, AiVerdict.of("최종 의견: 입찰을 추천하지 않습니다"))
        assertEquals(AiVerdict.UNKNOWN, AiVerdict.of("정보가 부족합니다."))
    }
}

class FilterGroupsTest {
    private val apt = Property(id = "apt", usageSmall = "아파트", minBidPrice = 250_000_000, appraisalPrice = 500_000_000, failCount = 2, statusName = "진행")
    private val land = Property(id = "land", usageSmall = "답", minBidPrice = 20_000_000, appraisalPrice = 25_000_000, statusName = "정지")
    private val car = Property(id = "car", name = "2019 쏘나타", usageLarge = "자동차", usageMedium = "승용자동차", minBidPrice = 9_000_000, movableKind = MovableKind.VEHICLE)
    private val truck = Property(id = "truck", name = "1톤 화물 트럭", usageLarge = "자동차", usageMedium = "화물자동차", minBidPrice = 5_000_000, movableKind = MovableKind.VEHICLE)
    private val whisky = Property(id = "w", name = "발렌타인 30년 양주", usageLarge = "물품", minBidPrice = 300_000, movableKind = MovableKind.GOODS)
    private val all = listOf(apt, land, car, truck, whisky)
    private val now = LocalDateTime.of(2026, 10, 1, 9, 0)

    private fun ids(f: PropertyFilter) = f.copy(windows = emptySet()).apply(all, now).map { it.id }

    @Test
    fun groupsAndSubcategories() {
        assertEquals(listOf("apt", "land"), ids(PropertyFilter(groups = setOf(KindGroup.REAL_ESTATE))))
        assertEquals(listOf("car", "truck"), ids(PropertyFilter(groups = setOf(KindGroup.VEHICLE))))
        assertEquals(listOf("truck"), ids(PropertyFilter(goods = setOf(GoodsGroup.TRUCK))))
        assertEquals(listOf("car"), ids(PropertyFilter(goods = setOf(GoodsGroup.CAR))))
        // 부동산 중 아파트만 + 차량 전체
        assertEquals(listOf("apt", "car", "truck"), ids(PropertyFilter(categories = setOf(Category.APARTMENT), groups = setOf(KindGroup.VEHICLE))))
        // 예전 저장값(categories 에 VEHICLE)도 그대로 동작
        assertEquals(listOf("car", "truck"), ids(PropertyFilter(categories = setOf(Category.VEHICLE))))
        assertEquals(listOf("w"), ids(PropertyFilter(goods = setOf(GoodsGroup.LIQUOR))))
    }

    @Test
    fun multiSelectBands() {
        assertEquals(listOf("land", "car", "truck", "w"), ids(PropertyFilter(failBuckets = setOf(0))))
        assertEquals(listOf("apt", "land", "car", "truck", "w"), ids(PropertyFilter(failBuckets = setOf(0, 2))))
        assertEquals(listOf("apt"), ids(PropertyFilter(ratioBands = setOf(RatioBand.FROM_50))))
        assertEquals(listOf("apt", "land"), ids(PropertyFilter(ratioBands = setOf(RatioBand.FROM_50, RatioBand.FROM_70))))
        assertEquals(listOf("car", "truck", "w"), ids(PropertyFilter(priceBands = setOf(PriceBand.UNDER_10M))))
        assertEquals(listOf("apt", "land"), ids(PropertyFilter(priceBands = setOf(PriceBand.TO_300M, PriceBand.TO_50M))))
        assertEquals(listOf("land"), ids(PropertyFilter(statuses = setOf("정지"))))
        assertEquals(2, PropertyFilter(failBuckets = setOf(1), priceBands = setOf(PriceBand.TO_1B)).activeCount)
        val analyses = Analyzer.analyzeAll(all)
        val safeOrCaution = PropertyFilter(riskLevels = setOf(RiskLevel.SAFE), windows = emptySet()).apply(all, now, analyses)
        assertTrue(safeOrCaution.all { analyses.getValue(it.id).riskLevel == RiskLevel.SAFE })
    }
}

class HomeThemesTest {
    private val now = LocalDateTime.of(2026, 10, 1, 9, 0)

    @Test
    fun themesScheduleAndChanges() {
        val fresh = Property(id = "n", usageSmall = "아파트", appraisalPrice = 300_000_000, minBidPrice = 300_000_000, bidStart = "202610050900", bidEnd = "202610051800")
        val cheap = Property(id = "c", usageSmall = "아파트", appraisalPrice = 300_000_000, minBidPrice = 150_000_000, failCount = 2, bidStart = "202610020900", bidEnd = "202610021800", viewCount = 500, interestCount = 3)
        val list = listOf(fresh, cheap)
        val analyses = Analyzer.analyzeAll(list)
        assertEquals(listOf("n"), Theme.NEW.pick(list, analyses, now).map { it.id })
        assertEquals(listOf("c"), Theme.BIG_DISCOUNT.pick(list, analyses, now).map { it.id })
        assertEquals(listOf("c"), Theme.CLOSING.pick(list, analyses, now).map { it.id })
        assertEquals(listOf("c"), Theme.POPULAR.pick(list, analyses, now).map { it.id })
        assertEquals(listOf("c", "n"), Theme.SMALL.pick(list, analyses, now).map { it.id })

        val cash = SmallInvest.cashNeeded(cheap, analyses["c"])!!
        assertTrue(cash in 60_000_000..90_000_000, "cash=$cash")
        assertEquals(listOf("c"), SmallInvest.within(100_000_000, list, analyses).map { it.first.id })

        val days = Schedule.byDate(list, LocalDate.of(2026, 10, 1))
        assertEquals(listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5)), days.keys.toList())

        val changes = Changes.between(fresh, fresh.copy(minBidPrice = 240_000_000, failCount = 1, bidEnd = "202611051800"))
        assertEquals(3, changes.size)
        assertTrue(changes.first().startsWith("최저가 인하"))
    }
}

class GeoNewsRegionTest {
    @Test
    fun measuring() {
        val a = LatLng(37.5665, 126.9780)
        val b = LatLng(37.5665, 126.9893)
        assertTrue(abs(GeoMath.distance(a, b) - 997) < 10)
        // 약 100m × 100m 정사각형
        val square = listOf(LatLng(37.0, 127.0), LatLng(37.0, 127.0011257), LatLng(37.0008993, 127.0011257), LatLng(37.0008993, 127.0))
        assertTrue(abs(GeoMath.area(square) - 10_000) < 100, "area=${GeoMath.area(square)}")
        assertEquals("998m", GeoMath.formatLength(997.6))
        assertEquals("10,000㎡ (3,025평)", GeoMath.formatArea(10_000.0))
    }

    @Test
    fun newsRss() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>x</title>
            <item><title>경매 아파트 낙찰가율 상승 - 한국경제</title><link>https://news.google.com/a</link><pubDate>Thu, 22 Oct 2026 01:00:00 GMT</pubDate><source url="https://hankyung.com">한국경제</source></item>
            <item><title>공매 물건 늘어</title><link>https://news.google.com/b</link><pubDate>Fri, 23 Oct 2026 01:00:00 GMT</pubDate></item>
            </channel></rss>"""
        val items = NewsClient.parse(xml)
        assertEquals(listOf("공매 물건 늘어", "경매 아파트 낙찰가율 상승"), items.map { it.title })
        assertEquals("한국경제", items[1].source)
        assertTrue(NewsClient.parse("not xml").isEmpty())
    }

    @Test
    fun regionLabelsAndNationwideQuery() {
        assertEquals("전국", Regions.label("", ""))
        assertEquals("서울 전체", Regions.label("서울특별시", ""))
        assertEquals("서울 강남구 외 1", Regions.label("서울특별시", "강남구,서초구"))
        assertEquals(listOf("강남구", "서초구"), Regions.splitSigungu(" 강남구, 서초구,강남구"))
        val q = QueryParser.parse("전국 아파트")
        assertTrue(q.nationwide)
        assertEquals("", q.sido)
        assertEquals(setOf(Category.APARTMENT), q.categories)
        assertTrue(q.description.startsWith("전국"))
    }
}

class OnbidExtrasTest {
    private fun objects(json: String) = Json.parseToJsonElement(json).let { it as kotlinx.serialization.json.JsonArray }.map { it.jsonObject }

    @Test
    fun rankingUsageAndHistory() {
        val ranked = OnbidExtrasClient.rankingItems(
            objects("""[{"sn":"1","cltrMngNo":"2024-1100-084555","onbidCltrNm":"강남 아파트 101호","apslEvlAmt":"500000000","lowstBidPrcIndctCont":"250000000",
                "pbctStatCd":"0002","lctnSdnm":"서울특별시","lctnSggnm":"강남구","cltrUsgSclsCtgrNm":"아파트","usbdNft":"5","ltnoPnu":"1168010600009360031"},
                {"onbidCltrNm":"번호 없음"}]"""),
            RankDivision.REAL_ESTATE, LocalDateTime.of(2026, 10, 1, 9, 0),
        )
        assertEquals(1, ranked.size)
        val p = ranked.single()
        assertEquals("입찰진행중", p.statusName)
        assertEquals(50.0, p.appraisalRatio!!, 0.01)
        assertEquals(Category.APARTMENT, p.category)
        assertEquals("11680", p.lawdCode)
        assertEquals(Category.VEHICLE, OnbidExtrasClient.rankingItems(objects("""[{"cltrMngNo":"V-1","onbidCltrNm":"쏘나타"}]"""), RankDivision.VEHICLE, LocalDateTime.now()).single().category)

        val stats = OnbidExtrasClient.parseUsageStats(objects("""[{"clsCdNm":"아파트","pbctCltrCnt":"120","mtSumCnt":"60","scbrt":"50.0","compRate":"3.2","scfbAmtRto1":"88.5"},
            {"clsCdNm":"다세대주택","pbctCltrCnt":"80","mtSumCnt":"20","scbrt":"25","compRate":"1.4","scfbAmtRto1":"72.1"}]"""))
        assertEquals(88.5, stats.first().priceRate!!, 0.01)
        assertEquals("아파트", OnbidExtrasClient.statFor(p, stats)!!.usage)
        assertEquals("다세대주택", OnbidExtrasClient.statFor(Property(id = "v", usageSmall = "다세대주택"), stats)!!.usage)

        val history = OnbidExtrasClient.parseBidHistory(objects("""[{"cltrMngNo":"x","prcnBidClgList":[
            {"pbctNsq":"002","cltrOpbdDt":"2026-09-10 11:00","pbctStatNm":"유찰","lowstBidPrcIndctCont":"450000000","apslPrcCtrsLowstBidRto":"90"},
            {"pbctNsq":"001","cltrOpbdDt":"2026-09-03 11:00","pbctStatNm":"유찰","lowstBidPrcIndctCont":"500000000","apslPrcCtrsLowstBidRto":"100"},
            {"pbctNsq":"003","cltrOpbdDt":"2026-09-17 11:00","pbctStatNm":"낙찰","lowstBidPrcIndctCont":"400000000","scfbAmt":"431000000"}]}]"""))
        assertEquals(listOf("1", "2", "3"), history.map { it.round })
        assertEquals(431_000_000, history.last().winningPrice)

        val result = OnbidExtrasClient.parseBidResults(objects("""[{"pbctStatNm":"낙찰","scfbAmt":"431000000","apslEvlAmt":"500000000","vldBddrNope":"7","etc":"x"}]""")).single()
        assertEquals(86.2, result.winningRatio!!, 0.01)
        assertEquals(7, result.bidders)
        assertTrue(result.fields.any { it.first == "낙찰가" })
    }
}

class RentTest {
    @Test
    fun rentParsingAndAnalysis() {
        val xml = """<response><header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header><body><items>
            <item><aptNm>대치A</aptNm><umdNm>대치동</umdNm><jibun>936-31</jibun><excluUseAr>84</excluUseAr><deposit>90,000</deposit><monthlyRent>0</monthlyRent><dealYear>2026</dealYear><dealMonth>9</dealMonth><dealDay>3</dealDay></item>
            <item><aptNm>대치A</aptNm><umdNm>대치동</umdNm><jibun>936-31</jibun><excluUseAr>84</excluUseAr><deposit>95,000</deposit><monthlyRent></monthlyRent><dealYear>2026</dealYear><dealMonth>8</dealMonth><dealDay>3</dealDay></item>
            <item><aptNm>대치A</aptNm><umdNm>대치동</umdNm><jibun>936-31</jibun><excluUseAr>84</excluUseAr><deposit>10,000</deposit><monthlyRent>300</monthlyRent><dealYear>2026</dealYear><dealMonth>7</dealMonth><dealDay>3</dealDay></item>
            <item><aptNm>대치A</aptNm><umdNm>대치동</umdNm><jibun>936-31</jibun><excluUseAr>84</excluUseAr><deposit>10,000</deposit><monthlyRent>320</monthlyRent><dealYear>2026</dealYear><dealMonth>6</dealMonth><dealDay>3</dealDay></item>
            </items></body></response>"""
        val rents = MolitClient.parseRents(xml, RentKind.APT)
        assertEquals(4, rents.size)
        assertEquals(900_000_000, rents.first().deposit)
        val p = Property(id = "a", usageSmall = "아파트", dong = "대치동", lotPnu = "1168010600009360031", areaSqm = 84.0, minBidPrice = 850_000_000, appraisalPrice = 1_100_000_000)
        val summary = MolitClient.summarizeRents(p, RentKind.APT, rents, 6)
        assertEquals("같은 단지", summary.scope)
        assertEquals(2, summary.jeonseCount)
        assertTrue(summary.jeonseRatio(p.minBidPrice)!! > 100)
        val a = Analyzer.analyze(p, rent = summary)
        assertTrue(a.merits.any { it.title == "최저가가 전세 시세 이하" })
        assertTrue(ClaudePrompt.build(p, a, null, null, null, ClaudePrompt.Mode.DETAIL, LocalDateTime.now(), rent = summary).contains("전세 추정"))
    }
}

class ApiDoctorTest {
    @Test
    fun classifiesEachApi() {
        val http = HttpGetter { url ->
            when {
                url.contains("OnbidRlstListSrvc2") -> HttpResult(200, """{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[{"cltrMngNo":"2024-1","pbctCdtnNo":"1","onbidCltrNm":"x","pbctNsq":"001","cltrBidBgngDt":"202610010900","cltrBidEndDt":"202610031700"}]},"totalCount":7}}}""")
                url.contains("RTMSDataSvcAptTrade") -> HttpResult(200, "<response><header><resultCode>000</resultCode></header><body><items></items></body></response>")
                url.contains("RTMSDataSvc") -> HttpResult(200, "<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>30</returnReasonCode><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg></cmmMsgHeader></OpenAPI_ServiceResponse>")
                else -> HttpResult(200, """{"response":{"header":{"resultCode":"03","resultMsg":"NODATA_ERROR"}}}""")
            }
        }
        val results = ApiDoctor("k", http, LocalDate.of(2026, 10, 23)).run()
        val byName = results.associateBy { it.name }
        assertEquals(ApiHealth.OK, byName.getValue("부동산 물건목록").health)
        assertTrue(byName.getValue("부동산 물건목록").detail.contains("7건"))
        assertEquals(ApiHealth.OK, byName.getValue("아파트 매매").health)
        assertEquals(ApiHealth.OK, byName.getValue("아파트 매매 상세").health)
        assertEquals(ApiHealth.NEEDS_APPROVAL, byName.getValue("아파트 전월세").health)
        // 차량 목록이 비어 있으면 차량 상세는 건너뛴다.
        assertEquals(ApiHealth.SKIPPED, byName.getValue("차량 물건상세").health)
        assertEquals(ApiDoctor.TOTAL, results.count { it.group != ApiDoctor.RECOMMENDED_GROUP })
        assertEquals(26, results.count { it.group == "온비드" })
        assertEquals(ApiHealth.NEEDS_APPROVAL, byName.getValue("공장·창고 매매 실거래").health)
        assertEquals(results.size, results.map { it.pageUrl }.distinct().size)
    }
}
