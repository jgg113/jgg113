package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 온비드 물건유형 (공고목록 cltrTypeCd) 과 전용 물건목록·물건상세 API */
enum class MovableKind(val label: String, val code: String, val category: Category, val listPath: String, val detailPath: String) {
    VEHICLE("자동차", "0002", Category.VEHICLE, "OnbidCarListSrvc2/getCarCltrList2", "OnbidCarDtlSrvc2/getCarDtlInf2"),
    GOODS("동산", "0003", Category.MOVABLE, "OnbidMvastListSrvc2/getMvastCltrList2", "OnbidMvastDtlSrvc2/getMvastDtlInf2"),
}

/**
 * 동산을 물건명·용도로 나눈 묶음. 통합 검색어("양주", "명품")와 상세 검색 칩에 쓴다.
 * [keywords] 중 하나라도 물건명이나 용도에 들어 있으면 그 묶음이다.
 */
enum class GoodsGroup(val label: String, val keywords: List<String>) {
    CAR("승용·승합차", listOf("승용", "승합", "SUV", "세단", "쏘나타", "그랜저", "아반떼", "카니발", "벤츠", "BMW", "아우디", "제네시스")),
    TRUCK("화물·특수차", listOf("화물", "트럭", "덤프", "탑차", "카고", "특수자동차", "버스", "견인")),
    MOTORCYCLE("이륜차", listOf("이륜", "오토바이", "스쿠터", "바이크")),
    MACHINERY("중장비·기계", listOf("중기", "건설기계", "굴착기", "굴삭기", "지게차", "기계", "장비", "설비", "선박", "공구")),
    LIQUOR("주류(양주·와인)", listOf("양주", "주류", "위스키", "와인", "꼬냑", "코냑", "브랜디", "보드카", "샴페인", "고량주", "사케", "맥주", "소주", "발렌타인", "조니워커", "로얄살루트", "맥캘란")),
    LUXURY("명품·잡화", listOf("명품", "가방", "핸드백", "지갑", "시계", "롤렉스", "샤넬", "루이비통", "에르메스", "구찌", "프라다", "까르띠에", "디올", "의류", "신발", "향수", "화장품")),
    JEWELRY("귀금속", listOf("귀금속", "금괴", "골드바", "순금", "다이아", "반지", "목걸이", "팔찌", "보석", "은괴")),
    ELECTRONICS("전자·컴퓨터", listOf("컴퓨터", "노트북", "PC", "모니터", "휴대폰", "핸드폰", "스마트폰", "태블릿", "아이패드", "아이폰", "갤럭시", "TV", "텔레비전", "카메라", "전자", "가전", "냉장고", "세탁기", "에어컨", "프린터", "서버")),
    ETC("기타 물품", emptyList());

    /** 이 묶음이 속하는 물건 종류 */
    val category: Category
        get() = if (this == CAR || this == TRUCK || this == MOTORCYCLE) Category.VEHICLE else Category.MOVABLE

    val group: KindGroup get() = category.group

    fun matches(p: Property): Boolean {
        val text = listOf(p.name, p.usageLarge, p.usageMedium, p.usageSmall).joinToString(" ")
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    companion object {
        /** 이 물건이 속한 묶음 (못 찾으면 [ETC]) */
        fun of(p: Property): GoodsGroup =
            if (!p.category.isMovable) ETC else entries.firstOrNull { it != ETC && it.matches(p) } ?: ETC
    }
}

data class MovableSearchRequest(
    val kinds: Set<MovableKind> = MovableKind.entries.toSet(),
    /** 비우면 전국 */
    val sido: String = "",
    val sigungu: String = "",
    val includePrivateContract: Boolean = false,
    /** 종류마다 받을 최대 쪽 수 (1쪽 500건) */
    val maxPages: Int = 6,
    /** (공고 경로) 오늘부터 며칠 안에 개찰하는 공고까지 */
    val days: Int = 21,
    /** (공고 경로) 종류마다 상세를 불러올 최대 공고 수 (하루 호출 한도 1,000건을 아끼려고 제한) */
    val maxNotices: Int = 30,
)

/**
 * 온비드 차량·동산 공매.
 * 차량·동산 물건목록 API (연식·주행거리·연료 같은 제원 포함) 로 먼저 받고,
 * 그 API 활용신청이 안 됐으면 공고목록(OnbidPbancListSrvc2) → 공고별 물건(OnbidPbancCltrDtlSrvc2) 두 단계로 받는다.
 */
class MovablesClient(
    private val onbid: OnbidClient,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val gapMs: Long = 120,
) {
    fun search(request: MovableSearchRequest, now: LocalDateTime = LocalDateTime.now(), progress: ProgressListener? = null): SearchOutcome {
        val warnings = mutableListOf<String>()
        val found = mutableListOf<Property>()
        var rows = 0
        val viaNotices = mutableSetOf<MovableKind>()
        val kinds = MovableKind.entries.filter { it in request.kinds }
        kinds.forEachIndexed { kindIndex, kind ->
            progress?.onProgress(kindIndex, kinds.size, "${kind.label} 물건목록")
            try {
                val list = listItems(kind, request, progress)
                rows += list.size
                found += assembleList(list, kind, now)
            } catch (e: OnbidApiException) {
                if (e.code !in APPROVAL_CODES) throw e
                viaNotices += kind
                warnings += "${kind.label} 물건목록 API 활용신청이 확인되지 않아 공고 경로로 불러왔습니다. (제원·지역 검색은 물건목록 API 승인 후 가능)"
            }
        }
        kinds.filter { it in viaNotices }.forEachIndexed { kindIndex, kind ->
            progress?.onProgress(kindIndex, kinds.size, "${kind.label} 공고 목록")
            val notices = notices(kind, now.toLocalDate(), request.days)
            if (notices.size > request.maxNotices) {
                warnings += "${kind.label}: 공고 ${notices.size}건 중 최근 ${request.maxNotices}건만 불러왔습니다."
            }
            val picked = notices.take(request.maxNotices)
            picked.forEachIndexed { i, notice ->
                progress?.onProgress(i, picked.size, "${kind.label} 공고 ${i + 1}/${picked.size}")
                val items = try {
                    noticeItems(notice)
                } catch (e: OnbidApiException) {
                    if (e.isFatal) throw e
                    warnings += "${kind.label} 공고 $notice: ${e.message}"
                    emptyList()
                }
                rows += items.size
                found += assemble(items, kind, now)
                sleeper(gapMs)
            }
        }
        return SearchOutcome(found.distinctBy { it.id }, rows, warnings, regionFilteredLocally = false)
    }

    /** 차량·동산 물건목록 API. 재산유형은 한 번에 묶어 부르고, 안 되면 하나씩 부른다. */
    fun listItems(kind: MovableKind, request: MovableSearchRequest, progress: ProgressListener? = null): List<JsonObject> {
        val flags = if (request.includePrivateContract) listOf("N", "Y") else listOf("N")
        val out = mutableListOf<JsonObject>()
        for (flag in flags) {
            val base = buildMap {
                put("pvctTrgtYn", flag)
                if (request.sido.isNotBlank()) put("lctnSdnm", request.sido)
                if (request.sigungu.isNotBlank()) put("lctnSggnm", request.sigungu)
            }
            val combined = try {
                listPages(kind.listPath, base + ("prptDivCd" to ALL_DIVS), request.maxPages) { page ->
                    progress?.onProgress(page, request.maxPages, "${kind.label} 물건목록 ${page}쪽")
                }
            } catch (e: OnbidApiException) {
                if (e.isFatal || e.code in APPROVAL_CODES) throw e
                null
            }
            out += combined ?: DIVS.flatMap { div ->
                runCatching { listPages(kind.listPath, base + ("prptDivCd" to div), 2) {} }.getOrElse { e ->
                    if (e is OnbidApiException && (e.isFatal || e.code in APPROVAL_CODES)) throw e
                    emptyList()
                }
            }
        }
        return out
    }

    private fun listPages(path: String, params: Map<String, String>, maxPages: Int, onPage: (Int) -> Unit): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for (page in 1..maxPages) {
            onPage(page)
            val result = onbid.call(path, params + mapOf("pageNo" to page.toString(), "numOfRows" to "500"))
            out += result.items
            val total = result.totalCount ?: out.size
            if (result.items.size < 500 || out.size >= total) break
            sleeper(gapMs)
        }
        return out
    }

    /** 차량·동산 물건상세 (실제 사진·감정평가서·보관/인도 장소·유의사항·제원) */
    fun detail(kind: MovableKind, cltrMngNo: String, conditionNo: String = ""): OnbidDocs {
        val params = buildMap {
            put("cltrMngNo", cltrMngNo)
            if (conditionNo.isNotBlank()) put("pbctCdtnNo", conditionNo)
            put("pageNo", "1")
            put("numOfRows", "10")
        }
        return OnbidDocs.parse(onbid.call(kind.detailPath, params).items)
    }

    /** 개찰일이 오늘~[days]일 뒤인 공고의 관리번호 (최근 공고 먼저) */
    fun notices(kind: MovableKind, today: LocalDate, days: Int): List<String> {
        val base = mapOf(
            "cltrTypeCd" to kind.code,
            "opbdDtStart" to today.format(YMD),
            "opbdDtEnd" to today.plusDays(days.toLong()).format(YMD),
        )
        val rows = try {
            listAll(base + ("prptDivCd" to ALL_DIVS))
        } catch (e: OnbidApiException) {
            if (e.isFatal) throw e
            null
        }
        // 재산유형 여러 개를 한 번에 못 받는 경우 하나씩 받는다.
        val all = rows?.takeIf { it.isNotEmpty() } ?: DIVS.flatMap { div ->
            runCatching { listAll(base + ("prptDivCd" to div)) }.getOrElse { e ->
                if (e is OnbidApiException && e.isFatal) throw e
                emptyList()
            }
        }
        return all.groupBy { it.str("pbancMngNo") }
            .filterKeys { it.isNotEmpty() }
            .entries
            .sortedByDescending { (_, list) -> list.maxOf { it.str("pbancYmd") } }
            .map { it.key }
    }

    private fun listAll(params: Map<String, String>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for (page in 1..5) {
            val result = onbid.call(LIST_PATH, params + mapOf("pageNo" to page.toString(), "numOfRows" to "500"))
            out += result.items
            val total = result.totalCount ?: out.size
            if (result.items.isEmpty() || out.size >= total) break
            sleeper(gapMs)
        }
        return out
    }

    fun noticeItems(pbancMngNo: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for (page in 1..4) {
            val result = onbid.call(ITEMS_PATH, mapOf("pbancMngNo" to pbancMngNo, "pageNo" to page.toString(), "numOfRows" to "500"))
            out += result.items
            val total = result.totalCount ?: out.size
            if (result.items.isEmpty() || out.size >= total) break
            sleeper(gapMs)
        }
        return out
    }

    companion object {
        /** 이 오류면 해당 API 활용신청이 안 된 것으로 보고 다른 경로를 쓴다. */
        val APPROVAL_CODES = setOf("12", "20", "30", "HTTP401", "HTTP403")

        /** 물건목록 API 행 → 물건 (부동산 목록과 필드가 같고 제원이 더 있다) */
        fun assembleList(rows: List<JsonObject>, kind: MovableKind, now: LocalDateTime): List<Property> {
            val specs = rows.groupBy { it.str("cltrMngNo") }.mapValues { (_, group) -> group.firstNotNullOfOrNull { ItemSpec.from(it) } }
            return PropertyAssembler.assemble(rows, now).map { p ->
                p.copy(
                    usageLarge = p.usageLarge.ifEmpty { kind.label },
                    movableKind = kind,
                    spec = specs[p.id],
                    // 차량·동산은 지번이 보관 장소라서 시군구까지만 둔다 (지도는 보관 장소 근처).
                    address = p.address.ifEmpty { listOf(p.sido, p.sigungu, p.dong).filter { it.isNotBlank() }.joinToString(" ") },
                )
            }
        }

        const val LIST_PATH = "OnbidPbancListSrvc2/getPbancList2"
        const val ITEMS_PATH = "OnbidPbancCltrDtlSrvc2/getPbancCltrInf2"
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        /** 압류·불용품·기타일반·공유·국유·수탁·파산·금융권담보·유입·공공개발 */
        private val DIVS = listOf("0007", "0004", "0005", "0002", "0010", "0008", "0013", "0003", "0006", "0011")
        private val ALL_DIVS = DIVS.joinToString(",")

        /** 공고 물건 행(회차마다 한 행)을 물건 단위로 묶는다. */
        fun assemble(rows: List<JsonObject>, kind: MovableKind, now: LocalDateTime): List<Property> {
            val nowKey = now.format(Property.KEY_FORMAT)
            return rows.groupBy { it.str("cltrMngNo") }
                .filterKeys { it.isNotEmpty() }
                .map { (id, group) ->
                    val rounds = group.map { row ->
                        BidRound(
                            round = row.str("pbctNsq"),
                            conditionNo = row.str("pbctsn"),
                            bidStart = normalizeDateTime(row.str("cltrBidBgngDt")),
                            bidEnd = normalizeDateTime(row.str("cltrBidEndDt"), endOfDay = true),
                            minBidPrice = row.amount("lowstBidPrcIndctCont")?.takeIf { it > 0 },
                            statusName = row.str("pbctStatNm"),
                        )
                    }.distinctBy { it.round to it.bidStart }.sortedBy { it.bidStart }
                    // 아직 끝나지 않은 회차 중 가장 이른 것이 지금 회차다.
                    val current = rounds.firstOrNull { it.bidEnd.isEmpty() || it.bidEnd >= nowKey } ?: rounds.lastOrNull()
                    val row = group.firstOrNull { it.str("pbctNsq") == current?.round && normalizeDateTime(it.str("cltrBidBgngDt")) == current.bidStart } ?: group.first()
                    val address = row.str("cltrAdr")
                    val region = Regions.fromAddress(address)
                    Property(
                        id = id,
                        name = row.str("onbidCltrNm"),
                        address = address,
                        sido = region?.first.orEmpty(),
                        sigungu = region?.second.orEmpty(),
                        assetTypeCode = row.str("prptDivCd"),
                        assetTypeName = row.str("prptDivNm"),
                        disposalMethod = row.str("dspsMthodNm"),
                        bidMethod = row.str("bidMthodNm"),
                        usageLarge = row.str("cltrUsgLclsCtgrNm").ifEmpty { kind.label },
                        usageMedium = row.str("cltrUsgMclsCtgrNm"),
                        usageSmall = row.str("cltrUsgSclsCtgrNm"),
                        appraisalPrice = row.amount("apslEvlAmt")?.takeIf { it > 0 },
                        minBidPrice = current?.minBidPrice ?: row.amount("lowstBidPrcIndctCont"),
                        failCount = row.str("usbdNft").toIntOrNull() ?: 0,
                        statusCode = row.str("pbctStatCd"),
                        statusName = row.str("pbctStatNm"),
                        round = current?.round.orEmpty(),
                        bidStart = current?.bidStart.orEmpty(),
                        bidEnd = current?.bidEnd.orEmpty(),
                        thumbnailUrl = row.str("thmImgUrl"),
                        rounds = rounds,
                        onbidPbancNo = row.str("onbidPbancNo"),
                        pbctNo = row.str("pbctNo"),
                        source = Source.ONBID,
                        movableKind = kind,
                    )
                }
        }
    }
}
