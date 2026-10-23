package io.github.jgg113.gongmae.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        val json = MapPayload.items(listOf(p, p.copy(id = "other", address = "", dong = "x"))) { item -> if (MapPayload.geoKey(item) == "서울특별시 마포구 연남동 245-42") LatLng(37.56, 126.92) else null }
        assertTrue(json.contains("\"gk\":\"서울특별시 마포구 연남동 245-42\""))
        assertTrue(json.contains("\"lat\":37.56"))
        assertTrue(json.contains("\"label\":\"4,890만\""))
        assertTrue(!json.contains("other"), "좌표 없는 물건은 빠진다")
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
    fun parsesOverpassElements() {
        val center = LatLng(37.5600, 126.9200)
        val body = """{"elements":[
            {"type":"node","id":1,"lat":37.5571,"lon":126.9245,"tags":{"name":"홍대입구","name:ko":"홍대입구역","railway":"station"}},
            {"type":"way","id":2,"center":{"lat":37.5610,"lon":126.9210},"tags":{"name":"연남초등학교"}},
            {"type":"node","id":3,"lat":37.5572,"lon":126.9246,"tags":{"name:ko":"홍대입구역"}},
            {"type":"node","id":4,"lat":37.5,"lon":126.9}]}"""
        val places = OverpassClient().parse(body, NearbyCategory.SUBWAY, center)
        assertEquals(listOf("연남초등학교", "홍대입구역"), places.map { it.name })
        assertTrue(places.last().distanceM in 450..550)
        val q = OverpassClient().query(NearbyCategory.MART, center, 1000)
        assertTrue(q.contains("nwr[\"shop\"~\"^(supermarket|department_store|mall)$\"](around:1000,37.56,126.92);"))
        assertTrue(q.contains("nwr[\"amenity\"=\"marketplace\"](around:1000,37.56,126.92);"))
        assertEquals("350m · 도보 5분", Format.distance(350))
        assertEquals("1.2km · 도보 18분", Format.distance(1200))
    }

    @Test
    fun fallsBackToSecondServer() {
        val transport = object : WebTransport {
            val urls = mutableListOf<String>()
            override fun get(url: String, headers: Map<String, String>) = error("unused")
            override fun post(url: String, headers: Map<String, String>, body: String): WebResponse {
                urls += url
                return if (urls.size == 1) WebResponse(504, "busy") else WebResponse(200, """{"elements":[]}""")
            }
        }
        assertTrue(OverpassClient(transport).search(NearbyCategory.BANK, LatLng(37.0, 127.0)).isEmpty())
        assertEquals(OverpassClient.ENDPOINTS, transport.urls)
    }
}

/** 법원경매정보 검색 응답 모양 (2026-06 화면 캡처 구조). */
private fun courtRow(
    docid: String,
    bigo: String = "",
    share: String = "",
    usage: String = "다세대",
    large: String = "20000",
    appraisal: String = "271000000",
    minPrice: String = "14899000",
    fails: String = "14",
    sigungu: String = "관악구",
    extra: String = "",
) = """{"docid":"$docid","boCd":"B000210","saNo":"20230130006292","maemulSer":"1","mokmulSer":"1","srnSaNo":"2023타경6292",
    "mulJinYn":"Y","mulBigo":"$bigo","gamevalAmt":"$appraisal","minmaePrice":"$minPrice","yuchalCnt":"$fails","inqCnt":"12",
    "gwansMulRegCnt":"21","remaeordDay":"","ipchalGbncd":"000331","maeGiil":"20260916","maeHh1":"1000","maePlace":"제4별관2층211호법정",
    "stopsaGbncd":"00","hjguSido":"서울특별시","hjguSigu":"$sigungu","hjguDong":"남현동","hjguRd":"","daepyoLotno":"1064-23",
    "buldNm":"씨에스타운","buldList":"비동 2층205호","areaList":"","lclsUtilCd":"$large","sclsUtilCd":"20106",
    "pjbBuldList":"철근콘크리트구조 28.33㎡","minArea":"28","jiwonNm":"서울중앙지방법원","jpDeptNm":"경매3계",
    "tel":"530-1815","maejibun":"$share","rd1Nm":"서울특별시","rd2Nm":"관악구","rdEubMyun":"","rdNm":"남현3길","buldNo":"39",
    "dspslUsgNm":"$usage","xCordi":"309863","yCordi":"541925","srchHjguSiguCd":"11620"$extra}"""

private fun courtPage(vararg rows: String, total: Int = rows.size) =
    """{"status":200,"message":"검색 결과가 조회되었습니다.","errors":null,"data":{"dma_pageInfo":{"pageNo":1,"pageSize":50,"totalCnt":"$total"},"ipcheck":true,"dlt_srchResult":[${rows.joinToString(",")}]}}"""

private class FakeWeb(val onPost: (String, Map<String, String>, String) -> WebResponse) : WebTransport {
    val posts = mutableListOf<Pair<Map<String, String>, String>>()
    var gets = 0
    override fun get(url: String, headers: Map<String, String>): WebResponse {
        gets++
        return WebResponse(200, "<html/>", listOf("JSESSIONID=abc123; Path=/; HttpOnly", "WMONID=w1; Path=/"))
    }
    override fun post(url: String, headers: Map<String, String>, body: String): WebResponse {
        posts += headers to body
        return onPost(url, headers, body)
    }
}

class CourtTest {
    @Test
    fun mapsSearchRow() {
        val row = OnbidResponseParser.parse("""{"header":{"resultCode":"00"},"body":{"items":[${courtRow("B0002102023013000629211")}]}}""").items.single()
        val p = CourtClient.toProperty(row)!!
        assertEquals(Source.COURT, p.source)
        assertEquals("2023타경6292", p.caseNo)
        assertEquals("서울특별시 관악구 남현동 1064-23", p.address)
        assertEquals("서울특별시 관악구 남현동 1064-23 씨에스타운 비동 2층205호", p.name)
        assertEquals("서울특별시 관악구 남현3길 39", p.roadAddress)
        assertEquals(271_000_000L, p.appraisalPrice)
        assertEquals(14_899_000L, p.minBidPrice)
        assertEquals(14, p.failCount)
        assertEquals(28.33, p.areaSqm)
        assertEquals("202609161000", p.bidStart)
        assertEquals(BidWindow.UPCOMING, p.bidWindow(NOW))
        assertEquals("D-14", Format.dDay(p, NOW))
        assertEquals(Category.VILLA, p.category)
        assertEquals("서울중앙지방법원 경매3계", p.organization)
        assertEquals(21, p.interestCount)
        assertEquals("11620", p.lawdCode)
        // KATEC(309863, 541925) → 관악구 남현동
        assertEquals(37.4750, p.latitude!!, 0.002)
        assertEquals(126.9787, p.longitude!!, 0.002)
        assertNull(CourtClient.toProperty(OnbidResponseParser.parse("""{"body":{"items":[{"docid":"x","lclsUtilCd":"30000"}]}}""").items.single()))
    }

    private fun client(web: FakeWeb, sleeps: MutableList<Long> = mutableListOf(), clock: () -> Long = { 1_000L }) =
        CourtClient(web, minDelayMs = 2_000, jitterMs = 1_000, random = { 500L }, sleeper = { sleeps += it }, clock = clock)

    @Test
    fun warmsUpSendsBrowserHeadersAndPacesPages() {
        val sleeps = mutableListOf<Long>()
        val web = FakeWeb { _, _, body ->
            if (body.contains("\"pageNo\":1,")) WebResponse(200, courtPage(*Array(100) { courtRow("D$it") }, total = 101))
            else WebResponse(200, courtPage(courtRow("E1"), total = 101))
        }
        val out = client(web, sleeps).search(CourtSearchRequest("서울특별시", weeks = 4), NOW)
        assertEquals(1, web.gets)
        assertEquals(2, web.posts.size)
        val (headers, body) = web.posts.first()
        assertEquals("JSESSIONID=abc123; WMONID=w1", headers["Cookie"])
        assertEquals("XMLHttpRequest", headers["X-Requested-With"])
        assertEquals("mf_wfm_mainFrame_sbm_selectGdsDtlSrch", headers["submissionid"])
        assertEquals("SYSTEM", headers["sc-userid"])
        assertTrue(headers.getValue("User-Agent").contains("Windows NT"))
        assertTrue(body.contains("\"rprsAdongSdCd\":\"11\""))
        assertTrue(body.contains("\"rprsAdongSggCd\":\"\""))
        assertTrue(body.contains("\"cortStDvs\":\"2\""))
        assertTrue(body.contains("\"bidBgngYmd\":\"20260902\""))
        assertTrue(body.contains("\"bidEndYmd\":\"20260930\""))
        assertTrue(body.contains("\"pageSize\":100"))
        assertEquals(listOf(2_500L), sleeps) // 2초 + 지터
        assertEquals(101, out.properties.size)
    }

    @Test
    fun knownSigunguIsFilteredOnServer() {
        val web = FakeWeb { _, _, _ -> WebResponse(200, courtPage(courtRow("A1"), courtRow("A2", sigungu = "동작구"))) }
        val out = client(web).search(CourtSearchRequest("서울특별시", sigungu = "관악구"), NOW)
        assertTrue(web.posts.single().second.contains("\"rprsAdongSggCd\":\"11620\""))
        assertEquals(2, out.properties.size) // 서버가 거른 결과는 그대로 쓴다
    }

    @Test
    fun cityWithDistrictsSearchesEachDistrictAndUnknownFallsBackToSido() {
        val web = FakeWeb { _, _, _ -> WebResponse(200, courtPage()) }
        client(web).search(CourtSearchRequest("경기도", sigungu = "수원시"), NOW)
        val codes = web.posts.map { Regex("\"rprsAdongSggCd\":\"(\\d*)\"").find(it.second)!!.groupValues[1] }
        assertEquals(listOf("41111", "41113", "41115", "41117", ""), codes) // 4개 구 → 결과 없으면 경기도 전체

        val web2 = FakeWeb { _, _, _ -> WebResponse(200, courtPage(courtRow("B1", sigungu = "제물포구"), courtRow("B2", sigungu = "부평구"))) }
        val out = client(web2).search(CourtSearchRequest("인천광역시", sigungu = "제물포구"), NOW)
        assertTrue(web2.posts.single().second.contains("\"rprsAdongSdCd\":\"28\""))
        assertEquals(listOf("B1"), out.properties.map { it.id })
    }

    @Test
    fun warmUpErrorStatusIsIgnoredAndPageSizeFallsBack() {
        var gets = 0
        val web = object : WebTransport {
            val bodies = mutableListOf<String>()
            override fun get(url: String, headers: Map<String, String>): WebResponse {
                gets++
                return WebResponse(500, "error", listOf("JSESSIONID=s1; Path=/"))
            }
            override fun post(url: String, headers: Map<String, String>, body: String): WebResponse {
                bodies += body
                return if (body.contains("\"pageSize\":100")) WebResponse(400, "") else WebResponse(200, courtPage(courtRow("C1")))
            }
        }
        val out = CourtClient(web, random = { 0 }, sleeper = {}).search(CourtSearchRequest("서울특별시"), NOW)
        assertEquals(1, gets)
        assertEquals(listOf("C1"), out.properties.map { it.id })
        assertTrue(web.bodies.last().contains("\"pageSize\":50"))
    }

    @Test
    fun blockedAccessCoolsDown() {
        val web = FakeWeb { _, _, _ -> WebResponse(200, """{"status":200,"data":{"ipcheck":false,"dlt_srchResult":[]}}""") }
        val court = client(web)
        val e = assertFailsWith<OnbidApiException> { court.search(CourtSearchRequest("서울특별시"), NOW) }
        assertEquals("COURT_LIMIT", e.code)
        assertTrue(e.message!!.contains("1시간"))
        val again = assertFailsWith<OnbidApiException> { court.search(CourtSearchRequest("서울특별시"), NOW) }
        assertEquals("COURT_LIMIT", again.code)
        assertEquals(1, web.posts.size) // 막힌 동안은 사이트를 다시 부르지 않는다
    }

    @Test
    fun manyResultsStopAtCallBudget() {
        val web = FakeWeb { _, _, _ -> WebResponse(200, courtPage(*Array(100) { courtRow("P${it}") }, total = 5000)) }
        val out = client(web).search(CourtSearchRequest("서울특별시", maxCalls = 3), NOW)
        assertEquals(3, web.posts.size)
        assertTrue(out.warnings.single().contains("일부"))
    }

    @Test
    fun parsesDetailPhotosAndRounds() {
        val jpeg = java.util.Base64.getEncoder().encodeToString(ByteArray(200) { it.toByte() })
        val result = OnbidResponseParser.parse("""{"body":{"items":[{
            "csPicLst":[{"cortAuctnPicDvsCd":"000245","cortAuctnPicSeq":"1","picFile":"$jpeg"},
                        {"cortAuctnPicDvsCd":"000241","cortAuctnPicSeq":"2","picFile":"$jpeg"},
                        {"cortAuctnPicDvsCd":"000241","cortAuctnPicSeq":"1","picFile":""}],
            "gdsDspslDxdyLst":[{"dxdyYmd":"20260812","auctnDxdyKndCd":"01","auctnDxdyRsltCd":"002","tsLwsDspslPrc":21284000},
                               {"dxdyYmd":"20260916","auctnDxdyKndCd":"01","auctnDxdyRsltCd":"","tsLwsDspslPrc":14899000},
                               {"dxdyYmd":"20260923","auctnDxdyKndCd":"02","tsLwsDspslPrc":0}],
            "gdsDspslObjctLst":[{"objctArDts":"28.33㎡","pjbBuldList":"철근콘크리트구조"}]}]}}""").items.single()
        val detail = CourtClient.parseDetail(result)
        assertEquals(2, detail.photos.size)
        assertEquals(2, detail.rounds.size)
        assertEquals(14_899_000L, detail.currentMinPrice)
        assertEquals("예정", detail.rounds[1].statusName)
        assertEquals(listOf("28.33㎡ 철근콘크리트구조"), detail.objects)
    }

    @Test
    fun katecMatchesKnownPlaces() {
        val namchang = Katec.toWgs84(309928.0, 551384.0) // 중구 남창동 (남대문시장 일대)
        assertEquals(37.560, namchang.lat, 0.003)
        assertEquals(126.978, namchang.lng, 0.003)
        assertNull(Katec.toWgs84OrNull(0.0, 0.0))
    }
}

class AnalyzerTest {
    private fun court(bigo: String = "", share: String = "", usage: String = "다세대", large: String = "20000", minPrice: String = "14899000", fails: String = "14") =
        CourtClient.toProperty(OnbidResponseParser.parse("""{"body":{"items":[${courtRow("X", bigo, share, usage, large, minPrice = minPrice, fails = fails)}]}}""").items.single())!!

    @Test
    fun shareSaleWithSurfaceRightIsDangerous() {
        val a = Analyzer.analyze(court(bigo = "1.일괄매각\n2.지분매각임\n3.지상 소재 건물을 위하여 법정지상권 성립할 여지 있음", share = "갑구 1번 지분 160.8분의 40 전부", usage = "대지", large = "10000"))
        assertEquals(RiskLevel.DANGER, a.riskLevel)
        assertTrue(a.risks.map { it.title }.containsAll(listOf("지분 매각", "법정지상권 성립 여지")))
        assertTrue(a.headline.startsWith("⚠ "))
        assertTrue(a.summary.contains("위험 요소"))
    }

    @Test
    fun cleanCheapApartmentScoresHigh() {
        val apt = court(usage = "아파트", minPrice = "135500000", fails = "2")  // 감정가 2.71억의 50%
        val a = Analyzer.analyze(apt)
        assertEquals(RiskLevel.SAFE, a.riskLevel)
        assertTrue(a.attractiveness >= 60, "attractiveness ${a.attractiveness}")
        assertTrue(a.total >= 70, "total ${a.total}")
        assertTrue(a.merits.any { it.title.startsWith("감정가의 50%") })
    }

    @Test
    fun onbidSeizedNeedsRightsAndSeniorTenantIsDanger() {
        val p = PropertyAssembler.assemble(OnbidResponseParser.parse(page(realShapedRow)).items, NOW).single()
        val before = Analyzer.analyze(p)
        assertTrue(before.factors.any { it.title == "권리정보 미확인" })
        assertTrue(before.factors.any { it.title == "명도 책임: 매수인" })
        val rights = PropertyDetail(p, emptyList(), emptyList(), emptyList(), emptyList(), "20200115", 50_000_000, listOf("전입일이 말소기준권리보다 빠른 임차인"))
        val after = Analyzer.analyze(p, rights = rights)
        assertTrue(after.rightsChecked)
        assertTrue(after.riskScore > before.riskScore)
        assertTrue(after.risks.first().title.startsWith("선순위 임차보증금 5,000만원"))
    }

    @Test
    fun peerComparisonAndSorting() {
        fun apt(id: String, price: Long) = Property(id = id, sigungu = "마포구", usageSmall = "아파트", minBidPrice = price, appraisalPrice = 500_000_000, areaSqm = 84.0)
        val list = listOf(apt("a", 300_000_000), apt("b", 400_000_000), apt("c", 450_000_000), apt("d", 460_000_000))
        val analyses = Analyzer.analyzeAll(list)
        assertTrue(analyses.getValue("a").merits.any { it.title.startsWith("주변 비슷한 물건보다 평당") })
        assertTrue(analyses.getValue("d").factors.any { it.title.startsWith("주변보다 평당") })
        assertEquals("a", SortOrder.SCORE_DESC.sort(list, NOW, analyses).first().id)
        assertEquals(list.size, PropertyFilter(maxRisk = RiskLevel.SAFE).apply(list, NOW, analyses).size)
    }

    @Test
    fun onbidPhotoGallery() {
        val urls = OnbidLinks.photoUrls("https://www.onbid.co.kr/op/cm/syc/filemng/filemngprcs/FileMngPrcsController/dnldFile.do?atchFileLstNo=17076355&atchSn=2&hashCrpsNo=AB&downloadImageKind=THNL_NM", 3)
        assertEquals(3, urls.size)
        assertTrue(urls[0].endsWith("dnldImgFile.do?atchFileLstNo=17076355&atchSn=2&thnImgDownloadFlag=false&downloadImageKind=CLG_FILE_NM"))
        assertTrue(urls[2].contains("atchSn=4"))
        assertTrue(OnbidLinks.photoUrls("").isEmpty())
    }
}

class RegionFromAddressTest {
    @Test
    fun findsSidoAndSigungu() {
        assertEquals("서울특별시" to "마포구", Regions.fromAddress("대한민국 서울특별시 마포구 연남동 245-42"))
        assertEquals("경기도" to "수원시", Regions.fromAddress("대한민국 경기도 수원시 장안구 정자동 1"))
        assertEquals("세종특별자치시" to "", Regions.fromAddress("대한민국 세종특별자치시 한누리대로 2130"))
        assertNull(Regions.fromAddress("Tokyo Japan"))
    }

    @Test
    fun compareIncludesScores() {
        val a = Property(id = "a", minBidPrice = 100, appraisalPrice = 200, usageSmall = "아파트")
        val b = Property(id = "b", minBidPrice = 190, appraisalPrice = 200, usageSmall = "아파트", notes = "유치권 신고 있음")
        val analyses = Analyzer.analyzeAll(listOf(a, b))
        val rows = Comparison.rows(listOf(a, b), NOW, analyses).associateBy { it.label }
        assertEquals(setOf(0), rows.getValue("종합점수").best)
        assertEquals(setOf(0), rows.getValue("위험도").best)
        assertTrue("종합점수" !in Comparison.rows(listOf(a, b), NOW).map { it.label })
    }
}

class NominatimTest {
    @Test
    fun parsesSearchAndReverse() {
        val n = NominatimClient(sleeper = {})
        assertEquals(LatLng(37.5609, 126.9233), n.parseSearch("""[{"lat":"37.5609","lon":"126.9233","display_name":"연남동"}]"""))
        assertNull(n.parseSearch("[]"))
        assertNull(n.parseSearch("""[{"lat":"48.85","lon":"2.35"}]"""))
        val reverse = n.parseReverse("""{"display_name":"연남동, 마포구, 서울특별시, 03731, 대한민국","address":{"quarter":"연남동","borough":"마포구","city":"서울특별시"}}""")!!
        assertEquals("서울특별시" to "마포구", Regions.fromAddress(reverse))
    }

    @Test
    fun pacesCallsAndSendsUserAgent() {
        val sleeps = mutableListOf<Long>()
        var t = 1_000L
        val transport = object : WebTransport {
            val agents = mutableListOf<String>()
            override fun get(url: String, headers: Map<String, String>): WebResponse {
                agents += headers.getValue("User-Agent")
                return WebResponse(200, """[{"lat":"37.5","lon":"127.0"}]""")
            }
            override fun post(url: String, headers: Map<String, String>, body: String) = error("unused")
        }
        val n = NominatimClient(transport, sleeper = { sleeps += it }, clock = { t })
        n.search("서울특별시 마포구 연남동")
        t = 1_300
        n.search("서울특별시 마포구 서교동")
        assertEquals(listOf(800L), sleeps)
        assertTrue(transport.agents.all { it.startsWith("GongmaeCompare/") })
    }

    @Test
    fun spreadKeepsPointsNearbyButDistinct() {
        val c = LatLng(37.56, 126.92)
        val a = NominatimClient.spread(c, "a")
        val b = NominatimClient.spread(c, "b")
        assertTrue(a != b)
        assertTrue(OverpassClient.distanceMeters(c, a) in 30..160)
        assertEquals(a, NominatimClient.spread(c, "a"))
    }
}

class MarketTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<response><header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header><body><items>
<item><aptNm>연남래미안</aptNm><umdNm>연남동</umdNm><jibun>123</jibun><excluUseAr>84.9</excluUseAr><dealAmount>  95,000</dealAmount><dealYear>2026</dealYear><dealMonth>9</dealMonth><dealDay>3</dealDay><floor>7</floor><cdealType> </cdealType></item>
<item><aptNm>연남래미안</aptNm><umdNm>연남동</umdNm><jibun>123</jibun><excluUseAr>84.9</excluUseAr><dealAmount>100,000</dealAmount><dealYear>2026</dealYear><dealMonth>8</dealMonth><dealDay>21</dealDay><floor>12</floor></item>
<item><aptNm>연남래미안</aptNm><umdNm>연남동</umdNm><jibun>123</jibun><excluUseAr>84.9</excluUseAr><dealAmount>90,000</dealAmount><dealYear>2026</dealYear><dealMonth>7</dealMonth><dealDay>2</dealDay><floor>3</floor></item>
<item><aptNm>연남래미안</aptNm><umdNm>연남동</umdNm><jibun>123</jibun><excluUseAr>84.9</excluUseAr><dealAmount>50,000</dealAmount><dealYear>2026</dealYear><dealMonth>7</dealMonth><dealDay>9</dealDay><floor>3</floor><cdealType>O</cdealType></item>
<item><aptNm>큰평수</aptNm><umdNm>연남동</umdNm><jibun>9</jibun><excluUseAr>134.0</excluUseAr><dealAmount>180,000</dealAmount><dealYear>2026</dealYear><dealMonth>7</dealMonth><dealDay>9</dealDay><floor>3</floor></item>
<item><aptNm>옆동</aptNm><umdNm>서교동</umdNm><jibun>9</jibun><excluUseAr>84.0</excluUseAr><dealAmount>120,000</dealAmount><dealYear>2026</dealYear><dealMonth>7</dealMonth><dealDay>9</dealDay><floor>3</floor></item>
</items></body></response>"""

    private val apt = Property(id = "m", sigungu = "마포구", dong = "연남동", usageSmall = "아파트", areaSqm = 84.9, appraisalPrice = 1_000_000_000, minBidPrice = 640_000_000, lawdCode = "11440")

    @Test
    fun parsesTradesAndDropsCancelled() {
        val trades = MolitClient.parse(xml, MarketKind.APT)
        assertEquals(5, trades.size)
        assertEquals(950_000_000L, trades.first().price)
        assertEquals("2026.09.03", trades.first().date)
        assertEquals("연남래미안", trades.first().name)
    }

    @Test
    fun summarizesSameDongSimilarArea() {
        val s = MolitClient.summarize(apt, MarketKind.APT, MolitClient.parse(xml, MarketKind.APT), 6)
        assertTrue(s.sameDong)
        assertEquals(3, s.trades.size)                  // 큰평수·옆동은 빠진다
        assertEquals("2026.09.03", s.trades.first().date)
        assertEquals(950L, s.estimatedValue!! / 1_000_000)  // 중간값 거래(9.5억) 기준
        assertEquals(67.4, Math.round(s.ratioOf(apt.minBidPrice)!! * 10) / 10.0)
    }

    @Test
    fun errorsAreReported() {
        val notRegistered = """<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg><returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>"""
        val e = assertFailsWith<OnbidApiException> { MolitClient.parse(notRegistered, MarketKind.APT) }
        assertEquals("30", e.code)
        val forbidden = assertFailsWith<OnbidApiException> { MolitClient.parse("Forbidden", MarketKind.APT, 403) }
        assertEquals("HTTP403", forbidden.code)
        assertEquals(emptyList(), MolitClient.parse("<response><header><resultCode>03</resultCode></header></response>", MarketKind.APT))
    }

    @Test
    fun marketDiscountRaisesAttractiveness() {
        val market = MolitClient.summarize(apt, MarketKind.APT, MolitClient.parse(xml, MarketKind.APT), 6)
        val without = Analyzer.analyze(apt)
        val with = Analyzer.analyze(apt, market = market)
        assertTrue(with.attractiveness > without.attractiveness)
        assertTrue(with.merits.any { it.title.startsWith("실거래 시세의 67.4%") })
        assertEquals(67.4, Math.round(with.marketRatio!! * 10) / 10.0)
    }
}

class BidCalculatorTest {
    @Test
    fun acquisitionTaxBrackets() {
        assertEquals(0.011, BidCalculator.acquisitionTaxRate(500_000_000, isHousing = true, over85Sqm = false), 1e-9)
        assertEquals(0.022, BidCalculator.acquisitionTaxRate(750_000_000, isHousing = true, over85Sqm = false), 1e-9)
        assertEquals(0.035, BidCalculator.acquisitionTaxRate(1_200_000_000, isHousing = true, over85Sqm = true), 1e-9)
        assertEquals(0.046, BidCalculator.acquisitionTaxRate(300_000_000, isHousing = false, over85Sqm = false), 1e-9)
    }

    @Test
    fun calculatesCashAndProfit() {
        val r = BidCalculator.calculate(
            BidInputs(bidPrice = 300_000_000, expectedSalePrice = 400_000_000, isHousing = true, minPrice = 250_000_000, loanRatio = 0.6, loanRate = 0.05, holdingMonths = 12, evictionAndRepair = 3_000_000),
        )
        assertEquals(25_000_000L, r.deposit)              // 법원: 최저가의 10%
        assertEquals(3_300_000L, r.acquisitionTax)
        assertEquals(1_700_000L, r.legalFee)
        assertEquals(180_000_000L, r.loanAmount)
        assertEquals(9_000_000L, r.interest)
        assertEquals(2_000_000L, r.saleCommission)
        assertEquals(319_000_000L, r.totalCost)
        assertEquals(139_000_000L, r.cashNeeded)
        assertEquals(81_000_000L, r.profit)
        assertEquals(58.3, Math.round(r.returnOnCash!! * 10) / 10.0)
    }

    @Test
    fun defaultsAndSuggestedRange() {
        val officetel = Property(id = "o", usageSmall = "오피스텔", appraisalPrice = 200_000_000, minBidPrice = 98_000_000, areaSqm = 30.0)
        val d = BidCalculator.defaults(officetel, null)
        assertFalse(d.isHousing)
        assertTrue(d.depositOnBidPrice)                    // 공매는 입찰가 기준 보증금
        assertEquals(200_000_000L, d.expectedSalePrice)
        val range = BidCalculator.suggestedRange(officetel, null, null)!!
        assertEquals(134_000_000L to 150_000_000L, range)  // 감정가 × 67~75%
        val danger = Analysis(70, RiskLevel.DANGER, 50, 40, "C", emptyList(), "", true)
        assertEquals(104_000_000L to 120_000_000L, BidCalculator.suggestedRange(officetel, danger, null))
        assertEquals(110_000_000L to 120_000_000L, BidCalculator.suggestedRange(officetel.copy(minBidPrice = 110_000_000), danger, null)) // 최저가 아래로 내리지 않음
    }
}

class ClaudePromptTest {
    private val p = Property(
        id = "2026-0100-000001", name = "연남동 아파트", sido = "서울특별시", sigungu = "마포구", dong = "연남동",
        usageSmall = "아파트", appraisalPrice = 500_000_000, minBidPrice = 350_000_000, failCount = 2, notes = "대항력 있는 임차인 있음",
    )

    @Test
    fun summaryAndDetailDiffer() {
        val a = Analyzer.analyze(p)
        val summary = ClaudePrompt.build(p, a, null, null, null, ClaudePrompt.Mode.SUMMARY, NOW)
        val detail = ClaudePrompt.build(p, a, null, null, null, ClaudePrompt.Mode.DETAIL, NOW)
        assertTrue(summary.contains("최저입찰가: 3억 5,000만원 (감정가의 70%, 유찰 2회)"))
        assertTrue(summary.contains("한 줄 결론"))
        assertTrue(detail.contains("권리분석: 말소기준권리"))
        assertTrue(detail.contains("매수인이 떠안는 권리"))
        assertFalse(summary.contains("도로명:"))            // 빈 값은 넣지 않는다
    }

    @Test
    fun urlVersionIsShortAndKeepsRequest() {
        val long = p.copy(notes = "가".repeat(3000))
        val text = ClaudePrompt.build(long, null, null, null, null, ClaudePrompt.Mode.DETAIL, NOW)
        assertEquals(text, ClaudePrompt.forUrl(text))      // 비고는 600자로 잘려 주소 한도 안에 든다
        val short = ClaudePrompt.forUrl(text, 700)
        assertTrue(short.length <= 700)
        assertTrue(short.contains("[요청]"))
        assertTrue(short.contains("…(일부 생략)"))
        assertTrue(ClaudePrompt.webUrl(text).startsWith("https://claude.ai/new?q=%EB%84%88"))
    }

    @Test
    fun checklistPutsImportantFirst() {
        val land = Property(id = "l", usageSmall = "답", notes = "농지취득자격증명 필요", source = Source.COURT)
        val items = Analyzer.checklist(land, Analyzer.analyze(land), null, null)
        assertTrue(items.first().important)
        assertTrue(items.any { it.title == "농지취득자격증명 준비" && it.important })
        assertTrue(items.any { it.title == "매각물건명세서 확인" })
        assertTrue(OfficialDoc.relevant(land).contains(OfficialDoc.LAND_USE))
        assertFalse(OfficialDoc.relevant(land).contains(OfficialDoc.BUILDING))
    }
}
