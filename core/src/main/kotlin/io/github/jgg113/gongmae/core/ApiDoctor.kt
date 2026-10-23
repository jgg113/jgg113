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

data class ApiCheck(val name: String, val pageUrl: String, val health: ApiHealth, val detail: String)

/**
 * 공공데이터포털 인증키로 앱이 쓰는 API 를 하나씩 1건만 불러 보며 활용신청 상태를 확인한다.
 * 하루 호출 한도를 아끼려고 API 마다 한 번만 부른다.
 */
class ApiDoctor(
    serviceKey: String,
    private val http: HttpGetter = UrlConnectionGetter,
    private val today: LocalDate = LocalDate.now(),
) {
    private val onbid = OnbidClient(serviceKey, http)
    private val molit = MolitClient(serviceKey, http)
    private val records = PublicRecordsClient(serviceKey, GetterTransport(http))

    fun run(progress: ProgressListener? = null): List<ApiCheck> {
        val out = mutableListOf<ApiCheck>()
        val steps = 11 + MarketKind.entries.size + RentKind.entries.size
        fun step(label: String) = progress?.onProgress(out.size, steps, label)
        fun check(name: String, page: String, block: () -> String) {
            step(name)
            out += try {
                ApiCheck(name, page, ApiHealth.OK, block())
            } catch (e: OnbidApiException) {
                ApiCheck(name, page, classify(e), e.message.orEmpty())
            } catch (e: Exception) {
                ApiCheck(name, page, ApiHealth.ERROR, e.message ?: e.javaClass.simpleName)
            }
        }
        val ymd = DateTimeFormatter.ofPattern("yyyyMMdd")

        var sample: Property? = null
        check("온비드 부동산 물건목록", ONBID_PAGE) {
            val page = onbid.fetchListPage(AssetType.SEIZED, false, "서울특별시", "", page = 1, rows = 5)
            sample = PropertyAssembler.assemble(page.items, today.atStartOfDay()).firstOrNull()
            "진행 중 물건 ${page.totalCount ?: page.items.size}건 (서울 압류재산)"
        }
        val item = sample
        check("온비드 부동산 물건상세", ONBID_PAGE) {
            if (item == null) throw IllegalStateException("물건목록이 안 돼 확인하지 못했습니다.")
            onbid.call(OnbidClient.DETAIL_PATH, mapOf("pageNo" to "1", "numOfRows" to "1", "cltrMngNo" to item.id))
            "권리·임대차 상세 응답 확인"
        }
        check("온비드 물건상세 입찰정보", ONBID_PAGE) {
            if (item == null) throw IllegalStateException("물건목록이 안 돼 확인하지 못했습니다.")
            "회차 이력 ${OnbidExtrasClient(onbid).bidHistory(item.id, item.conditionNo).size}건"
        }
        check("온비드 물건 입찰결과상세", ONBID_PAGE) {
            if (item == null) throw IllegalStateException("물건목록이 안 돼 확인하지 못했습니다.")
            OnbidExtrasClient(onbid).bidResult(item.id)
            "응답 확인 (진행 중 물건은 결과가 비어 있을 수 있음)"
        }
        var notice = ""
        check("온비드 공고목록", ONBID_PAGE) {
            val page = onbid.call(
                MovablesClient.LIST_PATH,
                mapOf(
                    "cltrTypeCd" to "0001", "prptDivCd" to "0007", "pageNo" to "1", "numOfRows" to "5",
                    "opbdDtStart" to today.format(ymd), "opbdDtEnd" to today.plusDays(14).format(ymd),
                ),
            )
            notice = page.items.firstOrNull()?.str("pbancMngNo").orEmpty()
            "2주 안 개찰 공고 ${page.totalCount ?: page.items.size}건"
        }
        check("온비드 공고상세 물건정보", ONBID_PAGE) {
            if (notice.isEmpty()) throw IllegalStateException("공고목록이 안 돼 확인하지 못했습니다.")
            "공고 물건 ${MovablesClient(onbid).noticeItems(notice).size}건"
        }
        val extras = OnbidExtrasClient(onbid)
        RankKind.entries.forEach { kind ->
            check("온비드 ${kind.label}", ONBID_PAGE) { "${extras.ranking(kind, RankDivision.REAL_ESTATE, rows = 3).size}건" }
        }
        check("온비드 용도별 입찰 통계", ONBID_PAGE) {
            "${extras.usageStats("0041", OnbidExtrasClient.defaultPeriod(today)).size}개 용도"
        }
        check("국토교통부 건축물대장(건축HUB)", BUILDING_PAGE) {
            // 서울 강남구 대치동 936-31 (아무 건물이나 확인용)
            val info = records.building(Parcel("1168010600109360031"))
            if (info == null) "응답 확인 (건물 없음)" else "응답 확인 (${info.mainPurpose})"
        }
        val ym = today.minusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"))
        MarketKind.entries.forEach { kind ->
            check("실거래가 · ${kind.apiName.removePrefix("국토교통부_")}", kind.pageUrl) { "강남구 지난달 ${molit.trades(kind, "11680", ym).size}건" }
        }
        RentKind.entries.forEach { kind ->
            check("전월세 · ${kind.apiName.removePrefix("국토교통부_")}", kind.pageUrl) { "강남구 지난달 ${molit.rents(kind, "11680", ym).size}건" }
        }
        progress?.onProgress(steps, steps, "완료")
        return out
    }

    companion object {
        const val ONBID_PAGE = "https://www.data.go.kr/tcs/dss/selectDataSetList.do?keyword=%EC%B0%A8%EC%84%B8%EB%8C%80%20%EC%98%A8%EB%B9%84%EB%93%9C"
        const val BUILDING_PAGE = "https://www.data.go.kr/data/15134735/openapi.do"

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
