package io.github.jgg113.gongmae.core

import java.net.URLEncoder
import java.time.LocalDateTime

/**
 * 물건 정보를 정리해 Claude 채팅에 붙여 넣을 질문을 만든다.
 * 앱이 AI 를 직접 부르지 않고 사용자의 Claude 앱(구독)에서 실시간으로 분석하게 하므로 API 비용이 들지 않는다.
 */
object ClaudePrompt {
    enum class Mode(val label: String) {
        SUMMARY("요약"),
        DETAIL("상세"),
    }

    /** claude.ai/new?q= 주소로 넘길 때 쓰는 최대 글자 수 (주소가 너무 길면 열리지 않는다). */
    const val URL_MAX_CHARS = 1800

    fun build(
        p: Property,
        analysis: Analysis?,
        rights: PropertyDetail?,
        market: MarketSummary?,
        court: CourtDetail?,
        mode: Mode,
        now: LocalDateTime,
        courtCase: CourtCase? = null,
        facts: PublicFacts? = null,
        rent: RentSummary? = null,
        usageStat: UsageStat? = null,
        sold: SoldStats? = null,
        docs: OnbidDocs? = null,
        bidInfo: BidInfo? = null,
    ): String = buildString {
        if (p.category.isMovable) {
            appendLine("너는 한국 공매(온비드) ${p.category.label} 입찰 전문가야. 아래 물건을 분석해 줘.")
        } else {
            appendLine("너는 한국 부동산 경매·공매 권리분석 전문가야. 아래 ${p.source.label} 물건을 분석해 줘.")
        }
        appendLine()
        appendLine("[물건]")
        line("구분", if (p.isCourt) "법원경매 ${p.organization} ${p.caseNo}" else "온비드 공매 ${p.id} (${p.assetTypeName})")
        line("이름", p.name)
        line("주소", p.displayAddress)
        line("도로명", p.roadAddress)
        line("용도", listOf(p.usageLarge, p.usageMedium, p.usageSmall).filter { it.isNotBlank() }.distinct().joinToString(" > ").ifBlank { p.category.label })
        if (p.buildingAreaSqm != null) line("건물면적", Format.area(p.buildingAreaSqm))
        if (p.landAreaSqm != null) line("토지면적", Format.area(p.landAreaSqm))
        if (p.buildingAreaSqm == null && p.landAreaSqm == null && p.areaSqm != null) line("면적", Format.area(p.areaSqm))
        line("감정가", Format.won(p.appraisalPrice))
        line("최저입찰가", "${Format.won(p.minBidPrice)} (감정가의 ${Format.percent(p.appraisalRatio)}, 유찰 ${p.failCount}회)")
        line("입찰기간", if (p.isCourt) Format.dateTime(p.bidStart) else Format.period(p.bidStart, p.bidEnd).let { "${it} (${p.bidWindow(now).label})" })
        line("명도책임", p.evictionBy)
        line("배분요구종기", p.distributionDeadline.takeIf { it.isNotBlank() }?.let(RightsParser::formatYmd).orEmpty())
        line("재매각", p.reauctionDate.takeIf { it.isNotBlank() }?.let { "재매각 명령 ${RightsParser.formatYmd(it)}" }.orEmpty())
        line("비고", p.notes.take(if (mode == Mode.DETAIL) 600 else 250))
        line("지분", p.shareInfo.take(200))
        (docs?.spec ?: p.spec)?.let { spec -> line("제원", spec.rows.joinToString(", ") { "${it.first} ${it.second}" }.take(300)) }
        if (docs != null) {
            docs.notes.take(if (mode == Mode.DETAIL) 6 else 3).forEach { (k, v) -> line(k, v.take(if (mode == Mode.DETAIL) 300 else 120)) }
            if (mode == Mode.DETAIL) docs.statement.forEach { (k, v) -> line("명세서 $k", v.take(200)) }
            if (docs.appraisals.isNotEmpty()) {
                line("감정평가", docs.appraisals.joinToString(" / ") { "${it.org} ${RightsParser.formatYmd(it.date)} ${Format.won(it.amount)}" })
            }
        }
        if (bidInfo != null && bidInfo.rules.isNotEmpty()) {
            line("입찰 조건", bidInfo.rules.take(if (mode == Mode.DETAIL) 10 else 4).joinToString(", ") { "${it.first}: ${it.second}" }.take(500))
        }

        val rounds = court?.rounds?.takeIf { it.isNotEmpty() } ?: p.rounds
        if (mode == Mode.DETAIL && rounds.size > 1) {
            appendLine("회차: " + rounds.take(8).joinToString(" / ") { r ->
                "${r.round.trimStart('0').ifEmpty { "-" }}회 ${Format.wonShort(r.minBidPrice)}${if (r.statusName.isNotBlank()) " ${r.statusName}" else ""}"
            })
        }
        if (mode == Mode.DETAIL && court != null && court.objects.isNotEmpty()) {
            appendLine("매각대상: " + court.objects.take(5).joinToString(" / ").take(300))
        }

        if (courtCase != null) {
            appendLine()
            appendLine("[법원 사건 정보]")
            line("사건명", courtCase.caseName)
            line("청구금액", courtCase.claimAmount?.let(Format::won).orEmpty())
            line("배당요구종기", courtCase.claimDeadline.takeIf { it.isNotBlank() }?.let(RightsParser::formatYmd).orEmpty())
            val kinds = courtCase.stakeholders.groupingBy { it.first }.eachCount()
            if (kinds.isNotEmpty()) appendLine("이해관계인: " + kinds.entries.joinToString(", ") { (k, n) -> if (n > 1) "$k ${n}명" else k })
            if (courtCase.appeals > 0) appendLine("항고 ${courtCase.appeals}건")
        }

        if (rights != null) {
            appendLine()
            appendLine("[권리·임대차 (온비드 제공)]")
            line("말소기준권리(추정)", RightsParser.formatYmd(rights.baseRightDate))
            val registryLimit = if (mode == Mode.DETAIL) 10 else 4
            rights.registry.take(registryLimit).forEach {
                appendLine("- 등기 ${it.kind} ${RightsParser.formatYmd(it.date)} ${it.holder} ${it.amount?.let(Format::won).orEmpty()}".trimEnd())
            }
            rights.leases.take(registryLimit).forEach {
                appendLine("- 임차 ${it.holder} 전입 ${RightsParser.formatYmd(it.moveInDate)} 확정 ${RightsParser.formatYmd(it.confirmDate)} 보증금 ${Format.won(it.deposit)}")
            }
            if (rights.seniorLeaseDeposit > 0) appendLine("- 인수 가능 보증금(추정) ${Format.won(rights.seniorLeaseDeposit)}")
        }

        if (market != null && market.estimatedValue != null) {
            appendLine()
            appendLine("[최근 ${market.months}개월 실거래 (${market.scope}, 비슷한 면적 ${market.trades.size}건)]")
            line("㎡당 중간값", Format.won(market.medianPerSqm))
            market.trendPercent?.let { line("최근 3개월 추세", "${if (it >= 0) "+" else ""}${Format.percent(it)}") }
            line("이 면적 시세 추정", "${Format.won(market.estimatedValue)} (최저가는 그 ${Format.percent(market.ratioOf(p.minBidPrice))})")
            if (mode == Mode.DETAIL) {
                market.trades.take(5).forEach { t ->
                    appendLine("- ${t.date} ${t.name} ${Format.area(t.areaSqm)} ${t.floor.takeIf { it.isNotBlank() }?.let { "${it}층 " }.orEmpty()}${Format.won(t.price)}")
                }
            }
        }

        if (rent != null && (rent.jeonseCount > 0 || rent.monthlyCount > 0)) {
            appendLine()
            appendLine("[최근 ${rent.months}개월 전월세 (${rent.scope}, 비슷한 면적)]")
            if (rent.jeonseEstimate != null) line("전세 추정", "${Format.won(rent.jeonseEstimate)} (전세 ${rent.jeonseCount}건, 최저가 대비 ${Format.percent(rent.jeonseRatio(p.minBidPrice))})")
            if (rent.monthlyRent != null) line("월세 추정", "보증금 ${Format.won(rent.monthlyDeposit)} / 월 ${Format.won(rent.monthlyRent)} (월세 ${rent.monthlyCount}건, 최저가 기준 연 ${Format.percent(rent.monthlyYield(p.minBidPrice))})")
        }

        if (usageStat != null) {
            appendLine()
            appendLine("[온비드 같은 용도 낙찰 통계 (${usageStat.usage})]")
            line("평균 낙찰가율", Format.percent(usageStat.priceRate))
            line("낙찰률·경쟁률", "${Format.percent(usageStat.soldRate)} · ${usageStat.competition ?: "-"}:1 (입찰 ${usageStat.bidCount}건 중 낙찰 ${usageStat.soldCount}건)")
        }

        if (sold != null && sold.cases.isNotEmpty()) {
            appendLine()
            appendLine("[온비드 비슷한 물건 낙찰 사례 (최근 ${sold.months}개월, ${sold.scope})]")
            line("낙찰가율 중간값", "감정가 대비 ${Format.percent(sold.medianRatio)}, 최저가 대비 ${Format.percent(sold.medianMinRatio)} (낙찰 ${sold.sold.size}건)")
            sold.averageBidders?.let { line("평균 입찰자", "%.1f명".format(it)) }
            sold.expectedPrice(p.appraisalPrice)?.let { line("이 물건 예상 낙찰가", Format.won(it)) }
            if (mode == Mode.DETAIL) {
                sold.sold.take(5).forEach { c ->
                    appendLine("- ${Format.dateTime(c.openedAt).take(10)} ${c.name.take(40)} 감정 ${Format.wonShort(c.appraisal)} → 낙찰 ${Format.wonShort(c.winningPrice)} (${Format.percent(c.ratio)}, ${c.bidders ?: "-"}명)")
                }
            }
        }

        if (facts != null && !facts.isEmpty) {
            appendLine()
            appendLine("[공적 자료 (자동 조회)]")
            facts.parcel?.let { line("지번", it.lot) }
            facts.latestLandPrice?.let { lp ->
                line("개별공시지가", "${lp.year}년 ㎡당 ${Format.won(lp.pricePerSqm)}" + (facts.landPriceChange?.let { " (전년 대비 ${if (it >= 0) "+" else ""}${Format.percent(it)})" } ?: ""))
                facts.landValue(p)?.let { line("공시지가 × 토지면적", Format.won(it)) }
            }
            facts.latestHousePrice?.let { line("개별주택가격", "${it.year}년 ${Format.won(it.price)}") }
            facts.building?.let { b ->
                line("건축물대장", listOf(b.mainPurpose, b.structure, "지상 ${b.groundFloors}층/지하 ${b.undergroundFloors}층").filter { it.isNotBlank() }.joinToString(", "))
                line("사용승인", b.approvalDate.takeIf { it.length == 8 }?.let { RightsParser.formatYmd(it) + (b.age()?.let { a -> " (${a}년 경과)" } ?: "") }.orEmpty())
                if (b.households > 0) line("세대수", "${b.households}세대")
                if (b.parking > 0 || b.elevators > 0) line("주차·승강기", "주차 ${b.parking}대, 승강기 ${b.elevators}대")
                if (b.otherPurposes.isNotBlank()) line("기타 용도", b.otherPurposes.take(80))
            }
        }

        if (analysis != null) {
            appendLine()
            appendLine("[앱 자동 점수 (규칙 기반)]")
            appendLine("종합 ${analysis.total}점(${analysis.grade}), 위험도 ${analysis.riskLevel.label} ${analysis.riskScore}, 투자매력 ${analysis.attractiveness}")
            val factors = if (mode == Mode.DETAIL) 6 else 3
            if (analysis.risks.isNotEmpty()) appendLine("위험 요인: " + analysis.risks.take(factors).joinToString(", ") { it.title })
            if (analysis.merits.isNotEmpty()) appendLine("장점: " + analysis.merits.take(factors).joinToString(", ") { it.title })
        }

        line("원문", if (p.isCourt) "법원경매정보 ${p.organization} ${p.caseNo}" else p.onbidUrl.orEmpty())

        appendLine()
        appendLine("[요청]")
        if (p.category.isMovable) {
            appendLine("1) 중고 시세와 비교한 적정 입찰가 범위")
            appendLine("2) 상태·진품·인도(보관료·운반) 위험")
            appendLine("3) 세금·이전 비용과 되팔 때 예상 수익")
            appendLine("4) 입찰 전 확인할 것과 최종 의견 (입찰 추천 / 조건부 / 비추천)")
            if (mode == Mode.SUMMARY) appendLine("짧고 쉽게 답해 줘.")
            return@buildString
        }
        when (mode) {
            Mode.SUMMARY -> {
                appendLine("1) 한 줄 결론 (입찰 추천 / 조건부 / 비추천)")
                appendLine("2) 핵심 위험 3가지와 장점 3가지")
                appendLine("3) 적정 입찰가 범위 한 줄")
                appendLine("짧고 쉽게, 모르는 내용은 추측하지 말고 확인할 서류를 알려 줘.")
            }
            Mode.DETAIL -> {
                appendLine("1) 권리분석: 말소기준권리, 인수·소멸 권리, 임차인 대항력과 배당 예상")
                appendLine("2) 물건 위험: 비고 문구(유치권·법정지상권·지분·대지권 등) 해석과 해결 방법·비용")
                appendLine("3) 시세와 수익성: 예상 매도가·임대료, 취득세·명도비 포함 총비용, 예상 수익률")
                appendLine("4) 적정 입찰가 범위와 그 근거")
                appendLine("5) 입찰 전 체크리스트 (현장·서류·대출)")
                appendLine("6) 최종 의견 (입찰 추천 / 조건부 / 비추천)")
                appendLine("등기부등본·매각물건명세서 PDF를 첨부하면 그 내용으로 다시 검토해 줘. 확실하지 않은 건 확인이 필요하다고 표시해 줘.")
            }
        }
    }.trim()

    /** 주소창으로 넘길 수 있게 줄인다. 잘라 낸 경우 끝에 표시를 남긴다. */
    fun forUrl(prompt: String, maxChars: Int = URL_MAX_CHARS): String {
        if (prompt.length <= maxChars) return prompt
        val request = prompt.substringAfterLast("\n[요청]", "")
        val head = prompt.take((maxChars - request.length - 30).coerceAtLeast(maxChars / 2))
        return (head.substringBeforeLast('\n') + "\n…(일부 생략)" + if (request.isNotEmpty()) "\n[요청]$request" else "").take(maxChars)
    }

    fun webUrl(prompt: String): String = "https://claude.ai/new?q=" + URLEncoder.encode(forUrl(prompt), "UTF-8").replace("+", "%20")

    private fun StringBuilder.line(label: String, value: String) {
        if (value.isNotBlank() && value != "-") appendLine("$label: $value")
    }
}

/** 입찰 전에 떼어 보는 공적 서류. 로그인·결제는 각 사이트에서 사용자가 직접 한다. */
enum class OfficialDoc(
    val title: String,
    val site: String,
    val url: String,
    val fee: String,
    val note: String,
    /** 꼭 필요한 최소 비용 (원). 무료면 0 */
    val costWon: Int = 0,
    /** 떼는 순서 */
    val how: String = "",
) {
    REGISTRY(
        "등기사항증명서 (등기부등본)",
        "대법원 인터넷등기소",
        "https://www.iros.go.kr/",
        "열람 700원 · 발급 1,000원",
        "소유권·근저당·압류 등 권리 순서를 확인하는 서류입니다. 권리 확인에는 열람용이면 충분합니다.",
        costWon = 700,
        how = "인터넷등기소 → 부동산 등기 → 열람/발급 → 주소 붙여넣기 → 등기 종류 선택 → 결제(카드·계좌이체·휴대폰·간편결제)",
    ),
    RESIDENT_CHECK(
        "전입세대 열람",
        "주민센터 방문",
        "https://www.gov.kr/",
        "건당 300원 안팎 (방문)",
        "그 집에 누가 언제 전입했는지 보여 줍니다. 말소기준권리보다 먼저 전입한 임차인(대항력)을 찾는 데 씁니다.",
        costWon = 300,
        how = "가까운 주민센터 방문 → 신분증과 경매 사건번호(또는 공매 공고) 제시 → 전입세대 열람 신청",
    ),
    LAND_USE(
        "토지이용계획확인서",
        "토지이음",
        "https://www.eum.go.kr/",
        "열람 무료",
        "용도지역·건축 제한·개발 계획을 확인합니다.",
        how = "토지이음 → 토지이용계획 → 주소 입력",
    ),
    LAND_REGISTER(
        "토지대장·임야대장·지적도",
        "정부24",
        "https://www.gov.kr/",
        "열람·인터넷 발급 무료",
        "지목·면적·소유자 변동과 땅 모양(지적도)을 확인합니다. 도로에 붙어 있는지(맹지 여부)도 지적도로 봅니다.",
        how = "정부24 → '토지대장' 또는 '지적도' 검색 → 주소 입력",
    ),
    BUILDING(
        "건축물대장",
        "정부24",
        "https://www.gov.kr/",
        "열람·발급 무료",
        "위반건축물 여부와 실제 용도·면적을 확인합니다.",
        how = "정부24 → '건축물대장' 검색 → 주소 입력 (집합건물은 동·호까지)",
    ),
    VEHICLE_REGISTER(
        "자동차등록원부",
        "정부24",
        "https://www.gov.kr/",
        "열람 무료",
        "압류·저당권이 남아 있는지, 차대번호·연식이 맞는지 확인합니다.",
        how = "정부24 → '자동차등록원부' 검색 → 차량번호 입력 (공고문에 있음)",
    ),
    TRADE(
        "실거래가",
        "국토교통부 실거래가 공개시스템",
        "https://rt.molit.go.kr/",
        "무료",
        "주변 아파트·빌라·토지 실거래 내역을 지도에서 봅니다.",
    ),
    ;

    companion object {
        /** 등기소 검색창에 넣기 좋은 주소: 지번 주소(동·번지)가 가장 잘 찾아진다. */
        fun searchAddress(p: Property): String = p.displayAddress.ifBlank { p.roadAddress }.ifBlank { p.name }

        fun relevant(p: Property): List<OfficialDoc> = when {
            p.category == Category.VEHICLE -> listOf(VEHICLE_REGISTER)
            p.category.isMovable -> emptyList()
            else -> realEstate(p)
        }

        /** 이 물건 확인에 꼭 드는 서류 비용 합계 (원) */
        fun minimumCost(p: Property): Int = relevant(p).sumOf { it.costWon }

        private fun realEstate(p: Property): List<OfficialDoc> = buildList {
            add(REGISTRY)
            if (p.category in setOf(Category.APARTMENT, Category.VILLA, Category.HOUSE, Category.OFFICETEL)) add(RESIDENT_CHECK)
            if (p.category in setOf(Category.LAND, Category.FOREST, Category.HOUSE, Category.FACTORY)) {
                add(LAND_USE)
                add(LAND_REGISTER)
            }
            if (p.category !in setOf(Category.LAND, Category.FOREST)) add(BUILDING)
            add(TRADE)
        }
    }
}
