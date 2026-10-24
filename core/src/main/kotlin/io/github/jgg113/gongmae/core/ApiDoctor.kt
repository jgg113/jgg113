package io.github.jgg113.gongmae.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** API 점검 결과 */
enum class ApiHealth(val label: String) {
    OK("정상"),
    NEEDS_APPROVAL("활용신청 필요"),
    LIMIT("오늘 한도 초과"),
    NETWORK("인터넷 연결 안 됨"),
    ERROR("오류"),
    SKIPPED("확인 못 함"),
}

data class ApiCheck(val name: String, val pageUrl: String, val health: ApiHealth, val detail: String, val group: String = "")

/** 앞 단계가 안 돼 확인할 거리가 없을 때 */
class ApiSkip(message: String) : Exception(message)

/**
 * 공공데이터포털 인증키로 앱이 쓰는 API 38개(온비드 26 · 국토교통부 실거래 11 · 건축HUB 1)를
 * 하나씩 1건만 불러 보며 활용신청 상태를 확인한다. 하루 호출 한도를 아끼려고 API 마다 한 번만 부른다.
 */
class ApiDoctor(
    serviceKey: String,
    private val http: HttpGetter = UrlConnectionGetter,
    private val today: LocalDate = LocalDate.now(),
) {
    private val onbid = OnbidClient(serviceKey, http)
    private val molit = MolitClient(serviceKey, http)
    private val records = PublicRecordsClient(serviceKey, GetterTransport(http))

    private class Step(val group: String, val name: String, val datasetId: String, val block: () -> String)

    fun run(progress: ProgressListener? = null): List<ApiCheck> {
        val steps = steps()
        val out = mutableListOf<ApiCheck>()
        steps.forEachIndexed { index, step ->
            progress?.onProgress(index, steps.size, step.name)
            val page = page(step.datasetId)
            out += try {
                ApiCheck(step.name, page, ApiHealth.OK, step.block(), step.group)
            } catch (e: OnbidApiException) {
                ApiCheck(step.name, page, classify(e), e.message.orEmpty(), step.group)
            } catch (e: ApiSkip) {
                ApiCheck(step.name, page, ApiHealth.SKIPPED, e.message.orEmpty(), step.group)
            } catch (e: Exception) {
                ApiCheck(step.name, page, ApiHealth.ERROR, e.message ?: e.javaClass.simpleName, step.group)
            }
        }
        progress?.onProgress(steps.size, steps.size, "완료")
        return out
    }

    private fun steps(): List<Step> {
        val ymd = DateTimeFormatter.ofPattern("yyyyMMdd")
        val extras = OnbidExtrasClient(onbid)
        val more = OnbidMoreClient(onbid)
        val movables = MovablesClient(onbid, sleeper = {})
        var sample: Property? = null
        var notice = ""
        var resultNotice = ""
        var sold: SoldCase? = null
        var car: Property? = null
        var goods: Property? = null
        fun need(value: Any?, what: String) {
            if (value == null || value == "") throw ApiSkip("$what 확인이 안 돼 건너뛰었습니다.")
        }
        val o = "온비드"
        val list = mutableListOf<Step>()
        fun add(group: String, name: String, id: String, block: () -> String) {
            list += Step(group, name, id, block)
        }

        add(o, "부동산 물건목록", "15157207") {
            val page = onbid.fetchListPage(AssetType.SEIZED, false, "서울특별시", "", page = 1, rows = 5)
            sample = PropertyAssembler.assemble(page.items, today.atStartOfDay()).firstOrNull()
            "진행 중 물건 ${page.totalCount ?: page.items.size}건 (서울 압류재산)"
        }
        add(o, "부동산 물건상세", "15157247") {
            val item = sample
            need(item, "부동산 물건목록")
            val docs = onbid.fetchDetail(item!!.id).docs
            "권리·임대차 확인 · 사진 ${docs.photos.size}장 · 감정평가서 ${docs.appraisals.count { it.url.isNotBlank() }}건"
        }
        add(o, "물건상세 입찰정보", "15157251") {
            val item = sample
            need(item, "부동산 물건목록")
            val info = extras.bidInfo(item!!.id, item.conditionNo)
            if (notice.isEmpty()) notice = info.noticeNo
            "회차 이력 ${info.history.size}건 · 입찰 조건 ${info.rules.size}개"
        }
        add(o, "물건 입찰결과목록", "15157252") {
            val cases = more.soldCases(ResultType.REAL_ESTATE, today.minusDays(14), today.minusDays(1), sido = "서울특별시", rows = 5)
            sold = cases.firstOrNull()
            "최근 2주 서울 낙찰 ${cases.size}건 (최대 5건만 확인)"
        }
        add(o, "물건 입찰결과상세", "15157254") {
            val case = sold
            need(case, "물건 입찰결과목록")
            val result = extras.bidResult(case!!.id, case.conditionNo).firstOrNull()
            "낙찰가 ${Format.won(result?.winningPrice)}"
        }
        add(o, "공고목록", "15157216") {
            val page = onbid.call(
                MovablesClient.LIST_PATH,
                mapOf(
                    "cltrTypeCd" to "0001", "prptDivCd" to "0007", "pageNo" to "1", "numOfRows" to "5",
                    "opbdDtStart" to today.format(ymd), "opbdDtEnd" to today.plusDays(14).format(ymd),
                ),
            )
            if (notice.isEmpty()) notice = page.items.firstOrNull()?.str("pbancMngNo").orEmpty()
            "2주 안 개찰 공고 ${page.totalCount ?: page.items.size}건"
        }
        add(o, "공고상세", "15157218") {
            need(notice, "공고목록")
            val page = onbid.call(OnbidMoreClient.NOTICE_DETAIL_PATH, mapOf("pbancMngNo" to notice, "pageNo" to "1", "numOfRows" to "1"))
            val info = OnbidMoreClient.parseNotice(notice, page.items, emptyList())
            "공고문 ${if (info.fullText.isNotBlank()) "있음" else "없음"} · 첨부 ${info.attachments.size}개"
        }
        add(o, "공고상세 물건정보", "15157220") {
            need(notice, "공고목록")
            "공고 물건 ${movables.noticeItems(notice).size}건"
        }
        add(o, "공고상세 입찰정보", "15157256") {
            need(notice, "공고목록")
            val page = onbid.call(OnbidMoreClient.NOTICE_BID_PATH, mapOf("pbancMngNo" to notice, "pageNo" to "1", "numOfRows" to "1"))
            "회차 일정 ${OnbidMoreClient.parseNotice(notice, emptyList(), page.items).schedule.size}개"
        }
        add(o, "공고 입찰결과목록", "15157222") {
            val results = more.noticeResults(ResultType.REAL_ESTATE, today.minusDays(14), today.minusDays(1), rows = 5)
            resultNotice = results.firstOrNull()?.noticeNo.orEmpty()
            "최근 2주 개찰완료 공고 ${results.size}건 (최대 5건만 확인)"
        }
        add(o, "공고 입찰결과상세", "15157258") {
            need(resultNotice, "공고 입찰결과목록")
            "물건 결과 ${more.noticeResultDetail(resultNotice).size}건"
        }
        add(o, "차량 물건목록", "15157233") {
            val page = onbid.call(
                MovableKind.VEHICLE.listPath,
                mapOf("prptDivCd" to OnbidMoreClient.ALL_DIVS, "pvctTrgtYn" to "N", "pageNo" to "1", "numOfRows" to "5"),
            )
            car = MovablesClient.assembleList(page.items, MovableKind.VEHICLE, today.atStartOfDay()).firstOrNull()
            "진행 중 차량 ${page.totalCount ?: page.items.size}건" + (car?.spec?.summary?.takeIf { it.isNotBlank() }?.let { " (예: $it)" } ?: "")
        }
        add(o, "차량 물건상세", "15157248") {
            val item = car
            need(item, "차량 물건목록")
            val docs = movables.detail(MovableKind.VEHICLE, item!!.id, item.conditionNo)
            "사진 ${docs.photos.size}장 · 감정평가서 ${docs.appraisals.size}건"
        }
        add(o, "동산 물건목록", "15157232") {
            val page = onbid.call(
                MovableKind.GOODS.listPath,
                mapOf("prptDivCd" to OnbidMoreClient.ALL_DIVS, "pvctTrgtYn" to "N", "pageNo" to "1", "numOfRows" to "5"),
            )
            goods = MovablesClient.assembleList(page.items, MovableKind.GOODS, today.atStartOfDay()).firstOrNull()
            "진행 중 동산 ${page.totalCount ?: page.items.size}건"
        }
        add(o, "동산 물건상세", "15157245") {
            val item = goods
            need(item, "동산 물건목록")
            val docs = movables.detail(MovableKind.GOODS, item!!.id, item.conditionNo)
            "사진 ${docs.photos.size}장 · 유의사항 ${docs.notes.size}개"
        }
        add(o, "유가증권 상세정보", "15157687") {
            val item = goods ?: sample
            need(item, "물건목록")
            more.security(item!!.id)
            "응답 확인 (유가증권 물건일 때만 내용이 있습니다)"
        }
        RankKind.entries.forEach { kind ->
            add(o, "순위 · ${kind.label}", kind.datasetId) { "${extras.ranking(kind, RankDivision.REAL_ESTATE, rows = 3).size}건" }
        }
        add(o, "용도별 입찰 통계", "15157757") {
            "${extras.usageStats("0041", OnbidExtrasClient.defaultPeriod(today)).size}개 용도 (작년 압류재산)"
        }
        add(o, "지역별 입찰 통계", "15157756") {
            "${more.regionStats("0021", OnbidExtrasClient.defaultPeriod(today)).size}개 지역 (작년 압류재산)"
        }
        GovPropertyKind.entries.forEach { kind ->
            val id = when (kind) {
                GovPropertyKind.NATIONAL -> "15157719"
                GovPropertyKind.PUBLIC -> "15157725"
                GovPropertyKind.JAPANESE -> "15157733"
            }
            add(o, "정부재산 · ${kind.label}", id) {
                val (items, total) = more.govProperties(kind, "서울특별시", rows = 3)
                "서울 ${total ?: items.size}건"
            }
        }
        add(o, "정부재산 · 불용품", "15157738") {
            val (items, total) = more.disusedGoods(rows = 3)
            "${total ?: items.size}건"
        }
        add(o, "코드 및 주소", "15157768") {
            "용도 코드 ${more.usageCodes().size}개"
        }

        val m = "국토교통부 실거래"
        val ym = today.minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"))
        add(m, "아파트 매매 상세", "15126468") {
            "강남구 지난달 ${molit.tradesAt(MolitClient.APT_DETAIL_PATH, MarketKind.APT, "11680", ym).size}건"
        }
        MarketKind.entries.filter { !it.recommended }.forEach { kind ->
            add(m, "${kind.label} 매매", kind.datasetId) { "강남구 지난달 ${molit.tradesAt(kind.path, kind, "11680", ym).size}건" }
        }
        RentKind.entries.forEach { kind ->
            add(m, "${kind.label} 전월세", kind.datasetId) { "강남구 지난달 ${molit.rents(kind, "11680", ym).size}건" }
        }
        add("국토교통부 건축HUB", "건축물대장 표제부", "15134735") {
            // 서울 강남구 대치동 936-31 (아무 건물이나 확인용)
            val info = records.building(Parcel("1168010600109360031"))
            if (info == null) "응답 확인 (건물 없음)" else "응답 확인 (${info.mainPurpose})"
        }
        MarketKind.entries.filter { it.recommended }.forEach { kind ->
            add(RECOMMENDED_GROUP, "${kind.label} 매매 실거래", kind.datasetId) {
                "화성시 지난달 ${molit.tradesAt(kind.path, kind, "41590", ym).size}건 — 공장·창고 시세 비교에 씁니다"
            }
        }
        return list
    }

    companion object {
        const val ONBID_PAGE = "https://www.data.go.kr/tcs/dss/selectDataSetList.do?keyword=%EC%B0%A8%EC%84%B8%EB%8C%80%20%EC%98%A8%EB%B9%84%EB%93%9C"
        const val BUILDING_PAGE = "https://www.data.go.kr/data/15134735/openapi.do"

        /** 점검하는 승인 API 수 (추천 API 는 따로) */
        const val TOTAL = 38
        const val RECOMMENDED_GROUP = "추천 API (신청하면 바로 사용)"

        fun page(datasetId: String) = "https://www.data.go.kr/data/$datasetId/openapi.do"

        fun classify(e: OnbidApiException): ApiHealth = when {
            e.code in setOf("12", "20", "30", "HTTP401", "HTTP403") -> ApiHealth.NEEDS_APPROVAL
            e.code == "22" || e.code == "HTTP429" -> ApiHealth.LIMIT
            e.code == OnbidApiException.NETWORK -> ApiHealth.NETWORK
            else -> ApiHealth.ERROR
        }
    }
}

/** [HttpGetter] 를 [WebTransport] 처럼 쓴다 (헤더는 무시). */
class GetterTransport(private val http: HttpGetter) : WebTransport {
    override fun get(url: String, headers: Map<String, String>): WebResponse = http.get(url).let { WebResponse(it.status, it.body) }

    override fun post(url: String, headers: Map<String, String>, body: String): WebResponse = throw UnsupportedOperationException("POST")
}
