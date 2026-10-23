package io.github.jgg113.gongmae.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 2, 12, 0)

private fun row(
    id: String = "2024-18146-006",
    nsq: String = "034",
    cdtn: Any = 1,
    start: String = "202609281400",
    end: String = "202609301700",
    price: Any = 42_400_000,
    sggu: String = "은평구",
    addr: String = "서울특별시 은평구 대조동 93-3",
    extra: String = "",
): String = """
    {"cltrMngNo":"$id","pbctNsq":"$nsq","pbctCdtnNo":$cdtn,"cltrBidBgngDt":"$start","cltrBidEndDt":"$end",
     "lowstBidPrcIndctCont":${if (price is String) "\"$price\"" else price},"apslEvlAmt":424000000,"usbdNft":10,
     "pbctStatCd":"0001","pbctStatNm":"0001","prptDivCd":"0007","prptDivNm":"압류재산","dspsMthodNm":"매각",
     "cltrUsgLclsCtgrNm":"부동산","cltrUsgMclsCtgrNm":"주거용건물","cltrUsgSclsCtgrNm":"다세대주택",
     "lctnSdnm":"서울특별시","lctnSggnm":"$sggu","zadrNm":"$addr","bldSqms":"39.94",
     "onbidCltrNm":"호안빌 101호"$extra}
""".trimIndent()

private fun page(vararg rows: String, total: Int = rows.size) =
    """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"items":{"item":[${rows.joinToString(",")}]},"numOfRows":500,"pageNo":1,"totalCount":$total}}}"""

class ParserTest {
    @Test
    fun parsesWrappedAndBareResponses() {
        assertEquals(2, OnbidResponseParser.parse(page(row(), row(nsq = "035"))).items.size)
        val bare = """{"header":{"resultCode":"00"},"body":{"items":[${row()}],"totalCount":1}}"""
        assertEquals(1, OnbidResponseParser.parse(bare).items.size)
        val single = """{"header":{"resultCode":"00"},"body":{"items":{"item":${row()}},"totalCount":1}}"""
        assertEquals(1, OnbidResponseParser.parse(single).items.size)
        val empty = """{"header":{"resultCode":"00"},"body":{"items":"","totalCount":0}}"""
        assertTrue(OnbidResponseParser.parse(empty).items.isEmpty())
    }

    @Test
    fun noDataIsEmptyNotError() {
        val noData = """{"response":{"header":{"resultCode":"03","resultMsg":"NODATA_ERROR"}}}"""
        assertTrue(OnbidResponseParser.parse(noData).items.isEmpty())
    }

    @Test
    fun xmlKeyErrorBecomesKoreanMessage() {
        val xml = """<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>
            <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg><returnReasonCode>30</returnReasonCode>
            </cmmMsgHeader></OpenAPI_ServiceResponse>"""
        val e = assertFailsWith<OnbidApiException> { OnbidResponseParser.parse(xml) }
        assertEquals("30", e.code)
        assertTrue(e.isFatal)
        assertTrue(e.message!!.contains("인증키"))
    }

    @Test
    fun lenientNumbers() {
        assertEquals(42_400_000L, parseAmount("42,400,000"))
        assertEquals(424_000_000L, parseAmount("4.24E8"))
        assertEquals(123.4, parseDecimal("토지 123.4"))
        assertNull(parseAmount(""))
        assertNull(parseAmount("비공개"))
        assertEquals("202609281400", normalizeDateTime("2026-09-28 14:00:00"))
        assertEquals("202609302359", normalizeDateTime("20260930", endOfDay = true))
    }
}

class AssemblerTest {
    private val rows = listOf(
        row(nsq = "043", cdtn = 3, start = "202611301400", end = "202612021700", price = 4_240_000),
        row(nsq = "035", cdtn = 2, start = "202610061400", end = "202610071700", price = "38,160,000"),
        row(nsq = "034", cdtn = 1, start = "202609281400", end = "202609301700", price = 42_400_000),
        row(nsq = "033", cdtn = 0, start = "202608011400", end = "202608031700", price = 46_640_000),
    ).map { OnbidResponseParser.parse(page(it)).items.single() }

    @Test
    fun picksEarliestLiveRoundNotHighestRoundNumber() {
        val p = PropertyAssembler.assemble(rows, NOW).single()
        assertEquals("034", p.round)
        assertEquals(42_400_000L, p.minBidPrice)
        assertEquals("입찰준비중", p.statusName)
        assertEquals(4, p.rounds.size)
        assertEquals("033", p.rounds.first().round)
        assertEquals(10.0, p.appraisalRatio!!, 0.001)
        assertEquals(Category.VILLA, p.category)
        assertEquals(BidWindow.UPCOMING, p.bidWindow(NOW))
        assertEquals(28L, p.daysUntilEnd(NOW))
    }

    @Test
    fun endedItemsUseLatestRound() {
        val later = LocalDateTime.of(2027, 1, 1, 0, 0)
        val p = PropertyAssembler.assemble(rows, later).single()
        assertEquals("043", p.round)
        assertEquals(BidWindow.ENDED, p.bidWindow(later))
    }

    @Test
    fun pricePerPyeong() {
        val p = PropertyAssembler.assemble(rows, NOW).single()
        // 42,400,000원 / (39.94㎡ / 3.305785) ≈ 3,509,396원
        assertEquals(3_509_396L, p.pricePerPyeong)
    }
}

class CategoryTest {
    @Test
    fun classifiesCommonUsages() {
        assertEquals(Category.APARTMENT, Category.classify("부동산", "주거용건물", "아파트"))
        assertEquals(Category.OFFICETEL, Category.classify("부동산", "상가용및업무용건물", "오피스텔"))
        assertEquals(Category.COMMERCIAL, Category.classify("부동산", "상가용및업무용건물", "근린생활시설"))
        assertEquals(Category.FOREST, Category.classify("부동산", "토지", "임야"))
        assertEquals(Category.LAND, Category.classify("부동산", "토지", "전"))
        assertEquals(Category.LAND, Category.classify("부동산", "토지", "공장용지"))
        assertEquals(Category.FACTORY, Category.classify("부동산", "산업용및기타특수용건물", "창고시설"))
        assertEquals(Category.APARTMENT, Category.classify("", "", "", "래미안 101동 1001호 아파트"))
        assertEquals(Category.ETC, Category.classify("", "", "", "기타"))
    }
}

class FilterSortCompareTest {
    private fun prop(id: String, price: Long?, appraisal: Long?, fails: Int, end: String, area: Double? = 50.0, usage: String = "아파트") =
        Property(
            id = id, name = "물건$id", minBidPrice = price, appraisalPrice = appraisal, failCount = fails,
            bidStart = "202609010900", bidEnd = end, areaSqm = area, usageSmall = usage, disposalMethod = "매각",
        )

    private val a = prop("A", 100_000_000, 200_000_000, 3, "202609101700")
    private val b = prop("B", 300_000_000, 330_000_000, 0, "202609051700", usage = "임야")
    private val c = prop("C", null, null, 1, "202608011700")

    @Test
    fun filterByCategoryPriceRatioAndWindow() {
        assertEquals(listOf(a, b), PropertyFilter().apply(listOf(a, b, c), NOW))
        assertEquals(listOf(a), PropertyFilter(categories = setOf(Category.APARTMENT)).apply(listOf(a, b, c), NOW))
        assertEquals(listOf(a), PropertyFilter(maxPriceManwon = 20_000).apply(listOf(a, b, c), NOW))
        assertEquals(listOf(a), PropertyFilter(maxRatioPercent = 50).apply(listOf(a, b, c), NOW))
        assertEquals(listOf(a), PropertyFilter(minFailCount = 2).apply(listOf(a, b, c), NOW))
        assertEquals(listOf(c), PropertyFilter(windows = setOf(BidWindow.ENDED)).apply(listOf(a, b, c), NOW))
        assertEquals(listOf(b), PropertyFilter(keyword = "물건B").apply(listOf(a, b, c), NOW))
        assertEquals(2, PropertyFilter(maxRatioPercent = 50, minFailCount = 1).activeCount)
    }

    @Test
    fun sortOrders() {
        val all = listOf(c, a, b)
        assertEquals(listOf(b, a, c), SortOrder.DEADLINE.sort(all, NOW))
        assertEquals(listOf(a, b, c), SortOrder.PRICE_ASC.sort(all, NOW))
        assertEquals(listOf(a, b, c), SortOrder.RATIO_ASC.sort(all, NOW))
        assertEquals(listOf(a, c, b), SortOrder.FAILS_DESC.sort(all, NOW))
    }

    @Test
    fun compareHighlightsBestValues() {
        val rows = Comparison.rows(listOf(a, b, c), NOW).associateBy { it.label }
        assertEquals(setOf(0), rows.getValue("최저입찰가").best)
        assertEquals(setOf(0), rows.getValue("감정가 대비").best)
        assertEquals(setOf(0), rows.getValue("유찰 횟수").best)
        assertTrue(rows.getValue("용도").best.isEmpty())
        assertEquals("50%", rows.getValue("감정가 대비").values[0])
    }
}

class FormatTest {
    @Test
    fun koreanMoney() {
        assertEquals("4억 2,400만원", Format.won(424_000_000))
        assertEquals("4,240만원", Format.won(42_400_000))
        assertEquals("12억원", Format.won(1_200_000_000))
        assertEquals("5,000원", Format.won(5_000))
        assertEquals("-", Format.won(null))
        assertEquals("4.24억", Format.wonShort(424_000_000))
        assertEquals("4,240만", Format.wonShort(42_400_000))
        assertEquals("80%", Format.percent(80.0))
        assertEquals("72.5%", Format.percent(72.46))
        assertEquals("39.94㎡ (12.1평)", Format.area(39.94))
        assertEquals("2026.09.28 14:00", Format.dateTime("202609281400"))
    }
}

class RegionTest {
    @Test
    fun sigunguMatchingIsWordBased() {
        assertTrue(Regions.matchesSigungu("수원시 장안구", "", "수원시"))
        assertTrue(Regions.matchesSigungu("", "서울특별시 강서구 화곡동 1", "강서구"))
        assertTrue(!Regions.matchesSigungu("강서구", "서울특별시 강서구 화곡동 1", "서구"))
        assertEquals("서울특별시", Regions.resolveSido("서울시"))
        assertEquals(25, Regions.sigunguOf("서울특별시").size)
        assertEquals(31, Regions.sigunguOf("경기도").size)
    }
}

class ClientTest {
    private class FakeHttp(val handler: (String) -> HttpResult) : HttpGetter {
        val urls = mutableListOf<String>()
        override fun get(url: String): HttpResult {
            urls += url
            return handler(url)
        }
    }

    @Test
    fun encodesKeyAndParams() {
        val http = FakeHttp { HttpResult(200, page()) }
        OnbidClient("abc%2Bdef%3D%3D", http).fetchListPage(AssetType.SEIZED, false, "서울특별시", "강남구", 1)
        val url = http.urls.single()
        assertTrue(url.startsWith("https://apis.data.go.kr/B010003/OnbidRlstListSrvc2/getRlstCltrList2?serviceKey=abc%2Bdef%3D%3D&resultType=json"))
        assertTrue(url.contains("prptDivCd=0007"))
        assertTrue(url.contains("pvctTrgtYn=N"))
        assertTrue(url.contains("lctnSggnm=%EA%B0%95%EB%82%A8%EA%B5%AC"))
    }

    @Test
    fun decodingKeyIsEncodedOnce() {
        val http = FakeHttp { HttpResult(200, page()) }
        OnbidClient("abc+def==", http).testConnection()
        assertTrue(http.urls.single().contains("serviceKey=abc%2Bdef%3D%3D&"))
    }

    @Test
    fun searchPagesAndGroups() {
        val http = FakeHttp { url ->
            when {
                url.contains("prptDivCd=0007") && url.contains("pageNo=1") ->
                    HttpResult(200, page(row(nsq = "034"), row(nsq = "035", start = "202610061400", end = "202610071700"), total = 3))
                url.contains("prptDivCd=0007") && url.contains("pageNo=2") ->
                    HttpResult(200, page(row(id = "2025-0001-001"), total = 3))
                else -> HttpResult(200, """{"response":{"header":{"resultCode":"03","resultMsg":"NODATA_ERROR"}}}""")
            }
        }
        val out = OnbidClient("k", http, pageSize = 2).search(SearchRequest("서울특별시"), NOW)
        assertEquals(3, out.rowCount)
        assertEquals(2, out.properties.size)
        assertTrue(out.warnings.isEmpty())
        assertEquals(1 + 1 + 4, http.urls.size) // 압류 2페이지 + 나머지 4유형
    }

    @Test
    fun fallsBackToLocalSigunguFilter() {
        val http = FakeHttp { url ->
            if (url.contains("lctnSggnm")) HttpResult(200, page())
            else if (url.contains("prptDivCd=0007")) HttpResult(200, page(row(), row(id = "X", sggu = "강서구", addr = "서울특별시 강서구 화곡동")))
            else HttpResult(200, page())
        }
        val out = OnbidClient("k", http).search(SearchRequest("서울특별시", "은평구"), NOW)
        assertTrue(out.regionFilteredLocally)
        assertEquals(listOf("2024-18146-006"), out.properties.map { it.id })
    }

    @Test
    fun fatalErrorStopsImmediately() {
        val http = FakeHttp { HttpResult(401, "Unauthorized") }
        val e = assertFailsWith<OnbidApiException> { OnbidClient("k", http).search(SearchRequest("서울특별시"), NOW) }
        assertEquals("HTTP401", e.code)
        assertEquals(1, http.urls.size)
    }

    @Test
    fun partialFailureBecomesWarning() {
        val http = FakeHttp { url ->
            if (url.contains("prptDivCd=0010")) HttpResult(200, """{"response":{"header":{"resultCode":"99","resultMsg":"UNKNOWN"}}}""")
            else HttpResult(200, page(row()))
        }
        val out = OnbidClient("k", http).search(SearchRequest("서울특별시"), NOW)
        assertEquals(1, out.warnings.size)
        assertTrue(out.warnings.single().startsWith("국유재산"))
        assertEquals(1, out.properties.size)
    }
}

class RightsTest {
    @Test
    fun seniorTenantIsFlagged() {
        val detailRow = row(
            extra = """,
            "rgstPrmrInfList":{"rgstPrmrInf":[
              {"irstDivNm":"근저당권","cltrInprNm":"OO은행","rgstYmd":"20200115","inprStngAmt":120000000},
              {"irstDivNm":"압류","cltrInprNm":"OO세무서","rgstYmd":"2023-05-02","inprStngAmt":""}]},
            "leasInfList":[{"irstDivNm":"임차인","cltrInprNm":"홍OO","bidGrteeAmt":"50,000,000","mthrAmt":0,"mvinYmd":"20190301","cfmtnYmd":"20190302"}]
            """,
        )
        val detail = RightsParser.parse(OnbidResponseParser.parse(page(detailRow)).items, NOW)
        assertEquals(2, detail.registry.size)
        assertEquals("20200115", detail.baseRightDate)
        assertEquals(50_000_000L, detail.seniorLeaseDeposit)
        assertTrue(detail.cautions.first().contains("5,000만원"))
        assertEquals("034", detail.property.round)
    }
}

/** 실제 물건목록 응답 모양(2026-08 실측 구조): 전체주소 필드 없음, 썸네일은 thnlImgUrlAdr, 지번은 ltnoPnu. */
private val realShapedRow = """
    {"apslEvlAmt":489000000,"apslPrcCtrsLowstBidRto":null,"bidDivNm":"전자입찰","bidMthodNm":"최고가방식",
     "bldSqms":41.11,"cltrBidBgngDt":"202612141400","cltrBidEndDt":"202612161700","cltrMngNo":"2022-08218-007",
     "cltrUsgLclsCtgrNm":"부동산","cltrUsgMclsCtgrNm":"주거용건물","cltrUsgSclsCtgrNm":"다세대주택",
     "collbBidPsblYn":"Y","dspsMthodNm":"매각","dtbtRqrEdtmCont":"2026/09/28","evcRsbyTrgtCont":"매수인",
     "landSqms":17.5,"lctnEmdNm":"연남동","lctnSdnm":"서울특별시","lctnSggnm":"마포구",
     "lowstBidPrcIndctCont":"48900000","ltnoPnu":"1144012400002450042",
     "onbidCltrNm":"서울특별시 마포구 연남동 245-42 삼정도나빌 제2층 제204호","onbidCltrno":2030560,
     "onbidPbancNo":902219,"orgNm":"한국자산관리공사","pbctCdtnNo":6162684,"pbctNo":10093226,"pbctNsq":"045",
     "pbctStatCd":"0001","pbctStatNm":"입찰준비중","prptDivCd":"0007","prptDivNm":"압류재산","pvctTrgtYn":"N",
     "rqstOrgNm":"남동세무서","subtBidPsblYn":"Y",
     "thnlImgUrlAdr":"https://www.onbid.co.kr/op/cm/syc/filemng/filemngprcs/FileMngPrcsController/dnldFile.do?atchFileLstNo=1&atchSn=2",
     "usbdNft":0}
""".trimIndent()

class RealShapeTest {
    private val p = PropertyAssembler.assemble(OnbidResponseParser.parse(page(realShapedRow)).items, NOW).single()

    @Test
    fun mapsRealFields() {
        assertEquals("서울특별시 마포구 연남동 245-42", p.address)
        assertEquals("서울특별시 마포구 연남동 245-42", p.displayAddress)
        assertTrue(p.thumbnailUrl.startsWith("https://www.onbid.co.kr/op/cm/syc/filemng"))
        assertEquals("한국자산관리공사", p.organization)
        assertEquals("남동세무서", p.requestOrg)
        assertEquals("20260928", p.distributionDeadline)
        assertEquals("매수인", p.evictionBy)
        assertEquals(41.11, p.areaSqm)
        assertEquals(10.0, p.appraisalRatio!!, 0.001)
        assertEquals(Category.VILLA, p.category)
    }

    @Test
    fun onbidDeepLinkUsesAllFourIds() {
        assertEquals(
            "https://www.onbid.co.kr/op/cltrpbancinf/cltrdtl/CltrDtlController/mvmnCltrDtl.do" +
                "?onbidCltrno=2030560&onbidPbancNo=902219&pbctNo=10093226&pbctCdtnNo=6162684",
            p.onbidUrl,
        )
        assertNull(p.copy(pbctNo = "").onbidUrl)
    }

    @Test
    fun geocodeQueriesGoFromLotToDistrict() {
        assertEquals(listOf("서울특별시 마포구 연남동 245-42", "서울특별시 마포구 연남동"), p.geocodeQueries)
        val json = MapPayload.items(listOf(p)) { key -> if (key == "서울특별시 마포구 연남동 245-42") LatLng(37.56, 126.92) else null }
        assertTrue(json.contains("\"gk\":\"서울특별시 마포구 연남동 245-42\""))
        assertTrue(json.contains("\"lat\":37.56"))
        assertTrue(json.contains("\"label\":\"4,890만\""))
    }
}

class AddressTest {
    @Test
    fun lots() {
        assertEquals("245-42", AddressParser.lotFromPnu("1144012400002450042"))
        assertEquals("산181-11", AddressParser.lotFromPnu("4146025300101810011"))
        assertEquals("72", AddressParser.lotFromPnu("1141011600000720000"))
        assertNull(AddressParser.lotFromPnu("1141011600000000000"))
        assertNull(AddressParser.lotFromPnu("12345"))
        assertEquals("245-42", AddressParser.lotFromName("서울특별시 마포구 연남동 245-42 삼정도나빌 제2층", "연남동"))
        assertEquals("산93-1", AddressParser.lotFromName("경기도 광주시 남종면 검천리 산 93-1 임야", "검천리"))
        assertNull(AddressParser.lotFromName("래미안 아파트", "연남동"))
        assertEquals("강남구 도곡동 467-6", AddressParser.stripTrailers("강남구 도곡동 467-6 외 2필지"))
    }

    @Test
    fun undecidedDatesAreBlank() {
        assertEquals("", normalizeDateTime("299901021000"))
        val p = Property(id = "x", bidStart = normalizeDateTime("299901021000"), bidEnd = normalizeDateTime("299901021000"))
        assertEquals(BidWindow.UNKNOWN, p.bidWindow(NOW))
    }
}

class NearbyTest {
    @Test
    fun parsesPlacesFromMapPage() {
        val list = NearbyPlace.parse("""[{"name":"홍대입구역 2호선","distance":350,"category":"교통,수송 > 지하철,전철 > 수도권2호선","url":"u"},{"name":""}]""")
        assertEquals(1, list.size)
        assertEquals(350, list[0].distanceM)
        assertEquals("수도권2호선", list[0].category)
        assertTrue(NearbyPlace.parse("not json").isEmpty())
        assertEquals("350m · 도보 5분", Format.distance(350))
        assertEquals("1.2km · 도보 18분", Format.distance(1200))
    }
}
