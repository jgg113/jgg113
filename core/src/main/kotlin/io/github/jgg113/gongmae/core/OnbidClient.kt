package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDateTime

data class HttpResult(val status: Int, val body: String)

fun interface HttpGetter {
    @Throws(IOException::class)
    fun get(url: String): HttpResult
}

object UrlConnectionGetter : HttpGetter {
    override fun get(url: String): HttpResult {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 45_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return HttpResult(status, body)
        } finally {
            conn.disconnect()
        }
    }
}

object ServiceKey {
    /** data.go.kr 의 Encoding 키(%2B…)와 Decoding 키(+/=) 를 모두 받아 Decoding 형태로 맞춘다. */
    fun normalize(raw: String): String {
        val compact = raw.filterNot { it.isWhitespace() }
        if (!compact.contains('%')) return compact
        return runCatching { URLDecoder.decode(compact, "UTF-8") }.getOrDefault(compact)
    }
}

data class SearchRequest(
    val sido: String,
    val sigungu: String = "",
    val assetTypes: List<AssetType> = AssetType.entries,
    val includePrivateContract: Boolean = false,
)

data class SearchOutcome(
    val properties: List<Property>,
    val rowCount: Int,
    val warnings: List<String>,
    /** 시군구 이름이 API 표기와 달라 시도 전체를 받아 주소로 걸렀는지. */
    val regionFilteredLocally: Boolean,
)

fun interface ProgressListener {
    fun onProgress(done: Int, total: Int, label: String)
}

class OnbidClient(
    serviceKey: String,
    private val http: HttpGetter = UrlConnectionGetter,
    private val pageSize: Int = 500,
    private val maxPagesPerType: Int = 20,
) {
    private val key = ServiceKey.normalize(serviceKey)

    fun buildUrl(path: String, params: Map<String, String>): String {
        val all = linkedMapOf("serviceKey" to key, "resultType" to "json") + params
        return "$BASE/$path?" + all.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    fun call(path: String, params: Map<String, String>): OnbidPage {
        val result = try {
            http.get(buildUrl(path, params))
        } catch (e: IOException) {
            throw OnbidApiException(OnbidApiException.NETWORK, "인터넷 연결을 확인하세요. (${e.message ?: e.javaClass.simpleName})")
        }
        if (result.status != 200) {
            // 오류 본문에 공공데이터포털 오류코드가 있으면 그 설명이 더 정확하다.
            try {
                OnbidResponseParser.parse(result.body)
            } catch (e: OnbidApiException) {
                if (e.code != "EMPTY" && e.code != "PARSE") throw e
            }
            throw OnbidApiException("HTTP${result.status}", OnbidResponseParser.describeHttp(result.status))
        }
        return OnbidResponseParser.parse(result.body)
    }

    fun fetchListPage(type: AssetType, privateContract: Boolean, sido: String, sigungu: String, page: Int, rows: Int = pageSize): OnbidPage {
        val params = linkedMapOf(
            "pageNo" to page.toString(),
            "numOfRows" to rows.toString(),
            "prptDivCd" to type.code,
            "pvctTrgtYn" to if (privateContract) "Y" else "N",
        )
        if (sido.isNotBlank()) params["lctnSdnm"] = sido
        if (sigungu.isNotBlank()) params["lctnSggnm"] = sigungu
        return call(LIST_PATH, params)
    }

    /** 인증키와 활용신청 상태를 1건 조회로 확인한다. */
    fun testConnection(): Int? = fetchListPage(AssetType.SEIZED, false, "서울특별시", "", page = 1, rows = 1).totalCount

    fun search(request: SearchRequest, now: LocalDateTime = LocalDateTime.now(), progress: ProgressListener? = null): SearchOutcome {
        val jobs = request.assetTypes.flatMap { type ->
            if (request.includePrivateContract && type.hasPrivateContract) listOf(type to false, type to true) else listOf(type to false)
        }
        val warnings = mutableListOf<String>()
        var rows = fetchAll(jobs, request.sido, request.sigungu, progress, warnings)
        var localFilter = false
        if (rows.isEmpty() && request.sigungu.isNotBlank() && warnings.isEmpty()) {
            // 시군구 표기가 API 와 다를 수 있으므로 시도 전체를 받아 주소로 거른다.
            localFilter = true
            rows = fetchAll(jobs, request.sido, "", progress, warnings)
                .filter { Regions.matchesSigungu(it.str("lctnSggnm"), it.firstStr("zadrNm", "cltrAdr", "cltrRadr"), request.sigungu) }
        }
        return SearchOutcome(PropertyAssembler.assemble(rows, now), rows.size, warnings, localFilter)
    }

    private fun fetchAll(
        jobs: List<Pair<AssetType, Boolean>>,
        sido: String,
        sigungu: String,
        progress: ProgressListener?,
        warnings: MutableList<String>,
    ): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        var failures = 0
        var firstError: OnbidApiException? = null
        jobs.forEachIndexed { index, (type, pvct) ->
            val label = type.label + if (pvct) "(수의계약)" else ""
            progress?.onProgress(index, jobs.size, label)
            try {
                var page = 1
                while (page <= maxPagesPerType) {
                    val result = fetchListPage(type, pvct, sido, sigungu, page)
                    rows += result.items
                    val fetched = (page - 1) * pageSize + result.items.size
                    if (result.items.size < pageSize || (result.totalCount != null && fetched >= result.totalCount)) break
                    page++
                }
            } catch (e: OnbidApiException) {
                if (e.isFatal) throw e
                failures++
                firstError = firstError ?: e
                warnings += "$label 조회 실패: ${e.message}"
            }
        }
        progress?.onProgress(jobs.size, jobs.size, "완료")
        if (jobs.isNotEmpty() && failures == jobs.size) throw firstError!!
        return rows
    }

    /** 부동산 물건상세 (권리관계·임대차·전체 회차). 별도 활용신청이 필요하다. */
    fun fetchDetail(cltrMngNo: String, now: LocalDateTime = LocalDateTime.now()): PropertyDetail {
        val page = call(DETAIL_PATH, mapOf("pageNo" to "1", "numOfRows" to "100", "cltrMngNo" to cltrMngNo))
        if (page.items.isEmpty()) {
            throw OnbidApiException("03", "상세 정보가 없습니다. 현재 입찰 중이거나 입찰 예정인 물건만 상세 조회가 됩니다.")
        }
        return RightsParser.parse(page.items, now)
    }

    companion object {
        const val BASE = "https://apis.data.go.kr/B010003"
        const val LIST_PATH = "OnbidRlstListSrvc2/getRlstCltrList2"
        const val DETAIL_PATH = "OnbidRlstDtlSrvc2/getRlstDtlInf2"
    }
}
