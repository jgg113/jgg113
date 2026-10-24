package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun ok(items: String, total: Int = 1) =
    HttpResult(200, """{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":$items},"totalCount":$total}}}""")

private val noData = HttpResult(200, """{"response":{"header":{"resultCode":"03","resultMsg":"NODATA_ERROR"}}}""")
private val notApproved = HttpResult(401, "Unauthorized")

class OnbidDocsTest {
    private val detail = Json.parseToJsonElement(
        """
        {"cltrMngNo":"2025-0100-001","onbidCltrNm":"쏘나타","yrmdl":"2019","drvDstc":82000,"dsvlm":1999,"fuelCont":"휘발유","pnsNm":"자동",
         "cltrMkrNm":"현대","carMdlNm":"쏘나타","vhrnoCont":"12가3456",
         "potoUrlList":[{"urlAdr":"http://img.onbid.co.kr/a.jpg"},{"urlAdr":"/b.jpg"}],
         "lrmUrlAdrList":"https://x/l1.png|https://x/l2.png",
         "apslEvlClgList":[{"apslEvlOrgNm":"가나감정","apslEvlYmd":"20250901","apslEvlAmt":12000000,"urlAdr":"https://x/apsl.pdf"}],
         "crtnLstClgList":[{"crtnYmd":"20250910","crtnItemCont":"최저입찰가","bfmdfLstCont":"1,000","afmdfLstCont":"900"}],
         "pytnMtrsCont":"차량 인도는 보관장소에서","cstdPlcNm":"서울 강서구 보관소","frstPbancYmd":"20250801",
         "papsInf":[{"dlgtOrgNm":"강서구청","dodispDudt":"20251020","ersrExclRgtCont":"없음"}]}
        """,
    ).jsonObject

    @Test
    fun parsesPhotosDocumentsAndNotes() {
        val docs = OnbidDocs.parse(listOf(detail))
        assertEquals(listOf("https://img.onbid.co.kr/a.jpg", "https://www.onbid.co.kr/b.jpg"), docs.photos)
        assertEquals(2, docs.cadastralMaps.size)
        assertEquals("https://x/apsl.pdf", docs.appraisals.single().url)
        assertTrue(docs.documents.first().first.startsWith("감정평가서 · 가나감정 · 2025.09.01"))
        assertTrue(docs.documents.any { it.first == "지적도 2" })
        assertEquals("900", docs.corrections.single().after)
        assertTrue(docs.notes.any { it.first == "유의사항" })
        assertTrue(docs.notes.any { it.first == "보관 장소" && it.second.contains("강서구") })
        assertEquals("강서구청", docs.statement.first { it.first == "처분청" }.second)
        assertEquals("20250801", docs.firstNoticeDate)
        val spec = assertNotNull(docs.spec)
        assertEquals(2019, spec.yearNumber)
        assertEquals("2019년식 · 8.2만km · 휘발유 · 1,999cc · 자동", spec.summary)
        assertEquals("12가3456", spec.rows.first { it.first == "차량번호" }.second)
    }

    @Test
    fun emptySpecIsNull() {
        assertNull(ItemSpec.from(Json.parseToJsonElement("""{"cltrMngNo":"1","drvDstc":0}""").jsonObject))
        assertEquals("9,500km", ItemSpec.formatKm(9_500))
        assertEquals("12만km", ItemSpec.formatKm(120_000))
    }
}

class MovableListTest {
    private val car = """[{"cltrMngNo":"C-1","pbctCdtnNo":"7","onbidCltrNm":"그랜저 IG","cltrUsgLclsCtgrNm":"자동차","cltrUsgMclsCtgrNm":"승용차",
        "lctnSdnm":"경기도","lctnSggnm":"수원시","lctnEmdNm":"권선동","yrmdl":"2018","drvDstc":"120000","fuelCont":"경유","cltrMkrNm":"현대","carMdlNm":"그랜저",
        "apslEvlAmt":"15000000","lowstBidPrcIndctCont":"12000000","pbctNsq":"001","cltrBidBgngDt":"202610260900","cltrBidEndDt":"202610281700"}]"""

    @Test
    fun usesCarListApiWithSpecs() {
        val urls = mutableListOf<String>()
        val http = HttpGetter { url ->
            urls += url
            when {
                url.contains("OnbidCarListSrvc2") -> ok(car)
                else -> noData
            }
        }
        val outcome = MovablesClient(OnbidClient("k", http), sleeper = {}).search(
            MovableSearchRequest(kinds = setOf(MovableKind.VEHICLE)),
            LocalDateTime.of(2026, 10, 24, 10, 0),
        )
        val p = outcome.properties.single()
        assertEquals(MovableKind.VEHICLE, p.movableKind)
        assertEquals(Category.VEHICLE, p.category)
        assertEquals(120_000L, p.spec?.mileageKm)
        assertEquals("경기도 수원시 권선동", p.address)
        assertTrue(urls.first().contains("prptDivCd=0007%2C0004"))
        assertTrue(urls.none { it.contains("OnbidPbancListSrvc2") })
        assertTrue(outcome.warnings.isEmpty())
    }

    @Test
    fun fallsBackToNoticesWhenListApiNotApproved() {
        val http = HttpGetter { url ->
            when {
                url.contains("OnbidCarListSrvc2") -> notApproved
                url.contains("OnbidPbancListSrvc2") -> ok("""[{"pbancMngNo":"N-1","pbancYmd":"20261020"}]""")
                url.contains("OnbidPbancCltrDtlSrvc2") -> ok(
                    """[{"cltrMngNo":"C-9","onbidCltrNm":"포터","cltrAdr":"서울특별시 강서구 공항동","pbctNsq":"001","cltrBidBgngDt":"202610260900","cltrBidEndDt":"202610281700"}]""",
                )
                else -> noData
            }
        }
        val outcome = MovablesClient(OnbidClient("k", http), sleeper = {}).search(
            MovableSearchRequest(kinds = setOf(MovableKind.VEHICLE)),
            LocalDateTime.of(2026, 10, 24, 10, 0),
        )
        assertEquals("C-9", outcome.properties.single().id)
        assertTrue(outcome.warnings.single().contains("공고 경로"))
    }
}

class SoldCasesTest {
    private fun case(id: String, ratio: String, bidders: String = "3") =
        """{"cltrMngNo":"$id","pbctCdtnNo":"1","onbidCltrNm":"아파트 $id","cltrUsgSclsCtgrNm":"아파트","lctnSdnm":"서울특별시","lctnSggnm":"강남구",
            "cltrOpbdDt":"202609011100","pbctStatCd":"0010","apslEvlAmt":"1000000000","lowstBidPrcIndctCont":"800000000",
            "scfbAmt":"${ratio.toDouble().times(10_000_000).toLong()}","apslPrcCtrsScfbPrcRto":"$ratio","vldBddrNope":"$bidders"}"""

    @Test
    fun comparablesUseSameSigunguFirst() {
        val urls = mutableListOf<String>()
        val http = HttpGetter { url ->
            urls += url
            if (url.contains("OnbidCltrBidRsltListSrvc2") && url.contains("lctnSggnm")) {
                ok("[${case("A", "85")},${case("B", "92.5")},${case("C", "101")},${case("SELF", "70")}]", 4)
            } else {
                noData
            }
        }
        val p = Property(id = "SELF", sido = "서울특별시", sigungu = "강남구", usageSmall = "아파트", appraisalPrice = 500_000_000)
        val stats = assertNotNull(OnbidMoreClient(OnbidClient("k", http)).comparables(p, LocalDate.of(2026, 10, 24)))
        assertEquals(3, stats.sold.size)
        assertEquals(92.5, stats.medianRatio)
        assertEquals(462_500_000L, stats.expectedPrice(p.appraisalPrice))
        assertEquals(3.0, stats.averageBidders)
        assertTrue(stats.scope.contains("강남구"))
        val url = urls.first()
        assertTrue(url.contains("opbdDtEnd=20261023"))
        assertTrue(url.contains("pbctStatCd=0010"))
        assertTrue(url.contains("cltrTypeCd=0001"))
    }

    @Test
    fun vehiclesCompareByModelNationwide() {
        val urls = mutableListOf<String>()
        val http = HttpGetter { url ->
            urls += url
            ok("[${case("X", "110")},${case("Y", "120")}]", 2)
        }
        val p = Property(id = "CAR", movableKind = MovableKind.VEHICLE, spec = ItemSpec(maker = "현대", model = "그랜저"))
        val stats = assertNotNull(OnbidMoreClient(OnbidClient("k", http)).comparables(p, LocalDate.of(2026, 10, 24)))
        assertEquals("전국 현대 그랜저", stats.scope)
        assertTrue(urls.first().contains("cltrTypeCd=0002"))
        assertTrue(urls.first().contains("carMdlNm="))
        assertTrue(!urls.first().contains("lctnSdnm"))
    }
}

class NoticeAndGovTest {
    @Test
    fun parsesNoticeTextAttachmentsAndSchedule() {
        val detail = Json.parseToJsonElement(
            """{"pbancMngNo":"N-1","onbidPbancNm":"2026-10 압류재산 공매","pbancOrgNm":"한국자산관리공사","pbancYmd":"20261001","bidAmtRlsYn":"N",
               "anncmAlCont":"<p>공매 공고</p><br/>입찰보증금 10%&nbsp;","atchFileList":[{"btbdAtchFileNm":"공고문.pdf","urlAdr":"/att/1.pdf"}]}""",
        ).jsonObject
        val bid = Json.parseToJsonElement(
            """{"pbancMngNo":"N-1","collbBidPsblYn":"Y","nrnkAplyPsblYn":"N","pbctTdpsCont":"최저입찰가의 10%","bidPtctAplyYn":"N",
               "cseqBidInfClgList":[{"pbctNsq":"002","cltrBidBgngDt":"202611030900","cltrBidEndDt":"202611051700","cltrOpbdDt":"202611061100","pbctOpbdPlcCont":"온비드"},
                                    {"pbctNsq":"001","cltrBidBgngDt":"202610270900","cltrBidEndDt":"202610291700","cltrOpbdDt":"202610301100","pbctOpbdPlcCont":"온비드"}]}""",
        ).jsonObject
        val info = OnbidMoreClient.parseNotice("N-1", listOf(detail), listOf(bid))
        assertEquals("공매 공고\n입찰보증금 10%", info.fullText)
        assertEquals("공고문.pdf" to "https://www.onbid.co.kr/att/1.pdf", info.attachments.single())
        assertEquals(false, info.bidAmountsPublic)
        assertEquals("가능", info.rules.first { it.first == "공동입찰" }.second)
        assertEquals("불가", info.rules.first { it.first == "차순위 매수신청" }.second)
        assertEquals("불필요", info.rules.first { it.first == "입찰참가신청 필요" }.second)
        assertEquals(listOf("1", "2"), info.schedule.map { it.round })
    }

    @Test
    fun parsesGovernmentProperty() {
        val rows = listOf(
            Json.parseToJsonElement(
                """{"gvwsPrptCltrNm":"서울 종로구 사직동 1-1","zadrNm":"서울특별시 종로구 사직동 1-1","clandCont":"대","landSqms":"120.5","mngStatNm":"매각(입찰)검토재산","dlgtOrgNm":"캠코","ltnoPnu":"1111010100100010001"}""",
            ).jsonObject,
            Json.parseToJsonElement(
                """{"onbidCltrNm":"구 청사 부지","pbownPrptLandClandNm":"대","pblbSqms":300,"lngDsplTypeCont":"매각대상","orgNm":"종로구청","apslEvlClgList":{"urlAdr":"https://x/a.pdf","apslEvlAmt":500000000}}""",
            ).jsonObject,
        )
        val list = OnbidMoreClient.parseGovProperties(GovPropertyKind.NATIONAL, rows, "서울특별시", "종로구")
        assertEquals("매각(입찰)검토재산", list[0].status)
        assertEquals(120.5, list[0].landAreaSqm)
        assertEquals("서울특별시 종로구", list[1].address)
        assertEquals(500_000_000L, list[1].appraisal)
        assertEquals("https://x/a.pdf", list[1].docUrl)
    }

    @Test
    fun parsesSecurity() {
        val row = Json.parseToJsonElement(
            """{"splrItmNm":"(주)가나","rprsvNm":"홍길동","tfbNm":"제조업","onrt":"12.5",
               "sthlPscdClgList":[{"sthlNm":"대한민국","onrt":"12.5","scrtStno":"1000"}],
               "fnclStatClgList":[{"fnclYr":"2024","astTotalamt":"5000000000","lbtTotalamt":"2000000000"}],
               "plClgList":[{"fnclYr":"2024","slsAmt":"3000000000","tstrNprfAmt":"100000000"}],
               "fnclRtoClgList":[{"fnclYr":"2024","lbtRto":"66.7"}]}""",
        ).jsonObject
        val info = assertNotNull(OnbidMoreClient.parseSecurity(listOf(row)))
        assertEquals("대한민국" to "12.5% · 1000주", info.shareholders.single())
        val year = info.financials.single()
        assertEquals("2024", year.first)
        assertEquals("50억원", year.second.first { it.first == "자산총계" }.second)
        assertEquals("66.7", year.second.first { it.first == "부채비율(%)" }.second)
    }

    @Test
    fun bidResultUsesRealFields() {
        val row = Json.parseToJsonElement(
            """{"cltrMngNo":"A","pbctStatNm":"낙찰","cltrOpbdDt":"202609011100","apslEvlAmt":"100000000","scfbAmt":"91000000","vldBddrNope":"4","bidAmtClgCont":"91000000|88000000"}""",
        ).jsonObject
        val info = OnbidExtrasClient.parseBidResults(listOf(row)).single()
        assertEquals(4, info.bidders)
        assertEquals(91.0, info.winningRatio)
        assertEquals("9,100만원, 8,800만원", info.fields.first { it.first == "입찰금액 목록" }.second)
        assertEquals("2026.09.01 11:00", info.fields.first { it.first == "개찰일시" }.second)
    }
}

class MolitDetailTest {
    @Test
    fun aptUsesDetailApiThenFallsBack() {
        val urls = mutableListOf<String>()
        val xml = "<response><header><resultCode>000</resultCode></header><body><items><item><aptNm>래미안</aptNm><aptDong>101</aptDong>" +
            "<umdNm>대치동</umdNm><jibun>1</jibun><excluUseAr>84.9</excluUseAr><dealAmount>250,000</dealAmount><dealYear>2026</dealYear><dealMonth>9</dealMonth><dealDay>1</dealDay><floor>10</floor></item></items></body></response>"
        var detailApproved = true
        val http = HttpGetter { url ->
            urls += url
            if (url.contains("AptTradeDev") && !detailApproved) notApproved else HttpResult(200, xml)
        }
        val client = MolitClient("k", http)
        assertEquals("101", client.trades(MarketKind.APT, "11680", "202609").single().building)
        assertTrue(urls.last().contains("RTMSDataSvcAptTradeDev"))
        detailApproved = false
        val other = MolitClient("k", http)
        assertEquals(1, other.trades(MarketKind.APT, "11680", "202609").size)
        assertTrue(other.aptDetailUnavailable)
        assertTrue(!urls.last().contains("Dev"))
    }
}

class AnalyzerSoldTest {
    private fun sold(ratio: Double, bidders: Int = 2) = SoldCase(
        id = "S$ratio", conditionNo = "", name = "", usage = "아파트", region = "", openedAt = "202609011100", status = "낙찰",
        appraisal = 100_000_000, minPrice = 70_000_000, winningPrice = (ratio * 1_000_000).toLong(), ratio = ratio, minRatio = null,
        bidders = bidders, invalidBidders = 0, landAreaSqm = null, buildingAreaSqm = null, spec = null,
    )

    @Test
    fun soldCasesAndVehicleSpecChangeScore() {
        val today = LocalDate.of(2026, 10, 24)
        val car = Property(id = "C", appraisalPrice = 10_000_000, minBidPrice = 7_000_000, movableKind = MovableKind.VEHICLE,
            spec = ItemSpec(year = "2008", mileageKm = 250_000, fuel = "경유"))
        val a = Analyzer.analyze(car, today = today)
        assertTrue(a.risks.any { it.title == "오래된 차량 (18년)" })
        assertTrue(a.risks.any { it.title == "주행거리 많음 (25만km)" })

        val apt = Property(id = "A", appraisalPrice = 100_000_000, minBidPrice = 70_000_000, usageSmall = "아파트")
        val cheap = Analyzer.analyze(apt, sold = SoldStats("강남구 아파트", 12, listOf(sold(71.0), sold(72.0), sold(73.0))), today = today)
        assertTrue(cheap.merits.any { it.title == "최저가 근처에서 낙찰되는 편" })
        val hot = Analyzer.analyze(apt, sold = SoldStats("강남구 아파트", 12, listOf(sold(95.0, 8), sold(99.0, 9), sold(105.0, 7))), today = today)
        assertTrue(hot.factors.any { it.title.startsWith("낙찰가가 최저가보다") && it.points < 0 })
        assertTrue(hot.factors.any { it.title.startsWith("경쟁 치열") })
        assertTrue(hot.attractiveness < cheap.attractiveness)
    }
}
