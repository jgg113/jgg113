package io.github.jgg113.gongmae.core

import kotlin.math.abs
import kotlin.math.roundToInt

enum class RiskLevel(val label: String) {
    SAFE("안전"),
    CAUTION("주의"),
    DANGER("위험"),
}

/**
 * 점수에 반영된 근거 하나.
 * [isRisk] 가 참이면 위험도 항목(양수 = 위험 증가, 음수 = 안전 신호), 거짓이면 투자매력 항목(양수 = 장점, 음수 = 단점).
 */
data class Factor(val title: String, val detail: String, val points: Int, val isRisk: Boolean)

data class CheckItem(val title: String, val detail: String, val important: Boolean = false)

/**
 * 경매 실무에서 '특수물건'으로 따로 보는 권리·조건. 상세 검색에서 고르거나 빼는 데도 쓴다.
 * [points] 는 위험도에 더하는 점수다.
 */
enum class SpecialRight(val label: String, pattern: String, val points: Int, val title: String, val detail: String) {
    SENIOR_TENANT("대항력 임차인", "대항력|선순위\\s*(임차|전세)|매수인이\\s*인수|인수\\s*(됨|함|할)", 30, "매수인이 떠안는 권리", "선순위 임차인 보증금 등 낙찰자가 인수해야 하는 권리가 있다는 문구가 있습니다."),
    LIEN("유치권", "유치권", 30, "유치권 신고·주장", "공사대금 등을 이유로 점유자가 물건을 넘겨주지 않을 수 있습니다. 성립 여부를 꼭 확인하세요."),
    STATUTORY_SURFACE("법정지상권", "법정지상권", 25, "법정지상권 성립 여지", "땅 위 건물 주인이 땅을 계속 쓸 권리가 생길 수 있어 토지 활용이 막힐 수 있습니다."),
    SHARE("지분매각", "지분\\s*매각|공유\\s*지분|지분\\s*전부|\\d\\s*분의\\s*\\d", 25, "지분 매각", "전체가 아닌 일부 지분이라 단독 사용·대출·되팔기가 어렵습니다."),
    INJUNCTION("가처분", "가처분", 25, "가처분 등기", "소유권 분쟁 등으로 낙찰 후에도 권리를 잃을 위험이 있습니다."),
    BUILDING_ONLY("건물만 입찰", "건물만\\s*(매각|입찰)|토지(는|를)?\\s*(매각\\s*(에서|대상\\s*에서)?\\s*)?제외", 20, "건물만 매각", "땅은 빠지고 건물만 사는 것이라 땅 주인에게 지료를 내거나 철거 소송을 당할 수 있습니다."),
    LAND_ONLY("토지만 입찰", "토지만\\s*(매각|입찰)|건물(은|을)?\\s*(매각\\s*(에서|대상\\s*에서)?\\s*)?제외", 15, "토지만 매각", "땅 위 건물은 매각에서 빠져 건물 주인과 분쟁이 생길 수 있습니다."),
    GRAVE("분묘기지권", "분묘", 20, "분묘(묘지) 존재", "분묘기지권 때문에 묘를 옮기기 어려울 수 있습니다."),
    WARNING_REG("예고등기", "예고등기", 20, "예고등기", "등기 원인에 대한 소송이 진행 중일 수 있습니다."),
    NO_LAND_RIGHT("대지권미등기", "대지권\\s*(미등기|없음)|대지권이\\s*없", 20, "대지권 문제", "건물만 매각되거나 대지권이 등기되지 않아 추가 비용·분쟁이 생길 수 있습니다."),
    PROVISIONAL_REG("가등기", "가등기", 15, "가등기", "선순위 소유권이전청구권 가등기라면 낙찰 후에도 소유권을 잃을 수 있습니다."),
    LAND_SEPARATE("토지별도등기", "토지\\s*별도\\s*등기", 15, "토지별도등기", "토지에 따로 설정된 권리가 있어 인수 여부를 확인해야 합니다."),
    LANDLOCKED("맹지", "맹지", 15, "맹지 (도로 없음)", "도로에 붙어 있지 않아 건축·활용이 어렵습니다."),
    REAUCTION("재매각", "재매각", 15, "재매각 물건", "앞선 낙찰자가 잔금을 포기한 물건입니다. 숨은 문제가 있는지 확인하세요."),
    FARMLAND("농취증 필요", "농지\\s*취득\\s*자격|농취증", 10, "농지취득자격증명 필요", "기한 안에 내지 못하면 입찰보증금을 잃을 수 있습니다."),
    ILLEGAL_BUILDING("위반건축물", "위반\\s*건축물", 10, "위반건축물", "이행강제금이 나오거나 대출이 제한될 수 있습니다."),
    EXCLUDED_BUILDING("제시외건물", "제시\\s*외", 10, "감정 밖 건물(제시외)", "매각 대상에 포함되지 않은 건물이 있어 철거·인수 문제가 생길 수 있습니다."),
    ;

    val pattern = Regex(pattern)

    companion object {
        /** 물건명·비고·지분 내용에서 찾은 특수권리 */
        fun of(p: Property): Set<SpecialRight> {
            val text = listOf(p.notes, p.name, p.shareInfo).joinToString(" ")
            val found = entries.filterTo(LinkedHashSet()) { it.pattern.containsMatchIn(text) }
            if (p.reauctionDate.isNotEmpty()) found += REAUCTION
            return found
        }
    }
}

data class Analysis(
    /** 0~100, 높을수록 위험 */
    val riskScore: Int,
    val riskLevel: RiskLevel,
    /** 0~100, 높을수록 투자 매력 */
    val attractiveness: Int,
    /** 0~100 종합 점수 */
    val total: Int,
    val grade: String,
    val factors: List<Factor>,
    val summary: String,
    /** 권리·임대차 상세까지 반영했는지 */
    val rightsChecked: Boolean,
    /** 최저가가 실거래 시세 추정의 몇 % 인지 (실거래가를 불러온 경우) */
    val marketRatio: Double? = null,
    /** 찾은 특수권리 */
    val specialRights: Set<SpecialRight> = emptySet(),
) {
    val risks: List<Factor> get() = factors.filter { it.isRisk && it.points > 0 }.sortedByDescending { it.points }
    val merits: List<Factor> get() = factors.filter { !it.isRisk && it.points > 0 }.sortedByDescending { it.points }

    /** 대항력 있는 임차인이 있는지 (공고·권리 상세·사건 이해관계인으로 판단) */
    val tenantStatus: TenantStatus
        get() = when {
            SpecialRight.SENIOR_TENANT in specialRights || factors.any { it.isRisk && it.title.startsWith("선순위 임차보증금") } -> TenantStatus.PRESENT
            rightsChecked && factors.none { it.isRisk && it.points > 0 && it.title.startsWith("임차인") } -> TenantStatus.NONE
            else -> TenantStatus.UNKNOWN
        }

    /** 목록 카드에 한 줄로 보여 줄 핵심 근거 */
    val headline: String
        get() = if (riskLevel != RiskLevel.SAFE && risks.isNotEmpty()) "⚠ " + risks.first().title else merits.firstOrNull()?.title ?: summary
}

/**
 * 공개 데이터(가격·용도·비고·권리 요약)를 경매 실무에서 흔히 보는 기준으로 점수화한다.
 * 규칙 기반 자동 분석이며 결과는 참고용이다.
 */
object Analyzer {
    private data class Rule(val pattern: Regex, val points: Int, val title: String, val detail: String)

    /** 특수권리 말고도 점수에 반영하는 문구 */
    private val extraRules = listOf(
        Rule(Regex("전세권"), 10, "전세권 설정", "말소 여부와 순위를 확인해야 합니다."),
        Rule(Regex("임차인|임대차"), 8, "임차인 관련 내용", "점유 중인 임차인이 있으면 명도 비용·기간이 듭니다."),
        Rule(Regex("공유자\\s*우선\\s*매수"), 5, "공유자 우선매수 가능", "공유자가 같은 가격에 먼저 살 수 있어 낙찰받지 못할 수 있습니다."),
    )

    private val hardLand = Regex("도로|구거|하천|유지|제방|묘지")

    /** 목록 전체를 분석한다. 같은 시군구·같은 용도의 평당가와 비교하기 위해 목록 단위로 계산한다. */
    fun analyzeAll(
        list: List<Property>,
        rights: Map<String, PropertyDetail> = emptyMap(),
        markets: Map<String, MarketSummary> = emptyMap(),
        cases: Map<String, CourtCase> = emptyMap(),
    ): Map<String, Analysis> {
        val medians = list.filter { it.pricePerPyeong != null }
            .groupBy { it.sigungu to it.category }
            .filterValues { it.size >= 3 }
            .mapValues { (_, items) -> median(items.mapNotNull { it.pricePerPyeong }) }
        return list.associate { p -> p.id to analyze(p, medians[p.sigungu to p.category], rights[p.id], markets[p.id], cases[p.id]) }
    }

    fun analyze(
        p: Property,
        peerMedianPerPyeong: Long? = null,
        rights: PropertyDetail? = null,
        market: MarketSummary? = null,
        courtCase: CourtCase? = null,
    ): Analysis {
        val factors = mutableListOf<Factor>()
        fun risk(points: Int, title: String, detail: String) = factors.add(Factor(title, detail, points, isRisk = true))
        fun merit(points: Int, title: String, detail: String) = factors.add(Factor(title, detail, points, isRisk = false))

        // ── 위험도 ──
        val text = listOf(p.notes, p.name, p.shareInfo).joinToString(" ")
        val specials = SpecialRight.of(p)
        specials.forEach { risk(it.points, it.title, it.detail) }
        extraRules.forEach { rule -> if (rule.pattern.containsMatchIn(text)) risk(rule.points, rule.title, rule.detail) }
        when {
            p.category.isMovable -> Unit
            hardLand.containsMatchIn(p.usageSmall) -> risk(20, "활용이 어려운 땅 (${p.usageSmall})", "도로·하천·묘지 등은 개인이 쓰거나 되팔기 어렵습니다.")
            p.category == Category.FOREST -> risk(12, "임야", "개발 제한·진입로 문제를 확인해야 합니다.")
            p.category == Category.LAND -> risk(8, "토지 단독 매각", "도로 접함·용도지역·건축 가능 여부를 따로 확인해야 합니다.")
            else -> Unit
        }

        // 많이 유찰됐는데 공고에 이유가 안 보이면, 매각물건명세서에만 적힌 인수 권리(대항력 임차인·유치권 등)를 의심해야 한다.
        val ratio = p.appraisalRatio
        val explained = specials.isNotEmpty()
        when {
            p.failCount >= 5 -> risk(25, "유찰 ${p.failCount}회 (매우 많음)", "보통 1~2회 안에 팔립니다. 여러 번 유찰된 데는 인수 권리·점유·하자 같은 이유가 있는 경우가 많습니다.")
            p.failCount == 4 -> risk(18, "유찰 4회", "유찰이 많습니다. 값이 떨어진 이유를 매각물건명세서·현황조사서에서 확인하세요.")
            p.failCount == 3 -> risk(12, "유찰 3회", "세 번 이상 유찰됐습니다. 숨은 인수 권리가 없는지 확인하세요.")
            p.failCount == 2 -> risk(5, "유찰 2회", "두 번 유찰됐습니다.")
            else -> Unit
        }
        if (ratio != null && ratio < 35) {
            risk(15, "감정가의 ${Format.percent(ratio)} (지나치게 쌈)", "감정가의 3분의 1 아래로 떨어진 물건은 낙찰자가 떠안는 돈이 있을 가능성이 큽니다.")
        } else if (ratio != null && ratio < 50) {
            risk(8, "감정가의 절반 이하", "절반 아래로 떨어진 이유가 공고에서 보이지 않으면 인수 권리를 의심해 보세요.")
        }
        if (p.failCount >= 3 && p.category in setOf(Category.APARTMENT, Category.OFFICETEL)) {
            risk(10, "${p.category.label}인데 유찰 ${p.failCount}회", "환금성이 좋은 ${p.category.label}가 이렇게 유찰되는 건 이례적입니다. 대항력 있는 임차인·관리비 체납 등을 확인하세요.")
        }
        val caseRisks = courtCase?.riskFactors().orEmpty()
        if (p.failCount >= 3 && !explained && (rights?.seniorLeaseDeposit ?: 0L) == 0L && caseRisks.isEmpty()) {
            risk(8, "유찰 이유가 공고에 안 보임", "공개된 비고에는 이유가 없습니다. 매각물건명세서(경매)나 권리 상세(공매)를 꼭 확인하세요.")
        }

        if (p.isCourt) {
            if (p.notes.isBlank() && p.shareInfo.isBlank() && p.failCount < 2) risk(-5, "특이사항 없음", "법원 공고의 비고란에 적힌 위험 문구가 없습니다.")
        } else {
            when (p.assetTypeCode) {
                "0010", "0002" -> risk(-10, "국가·지자체 재산", "공공기관 소유 재산이라 권리관계가 비교적 깨끗합니다.")
                "0008" -> risk(-5, "수탁재산", "금융기관 등이 맡긴 재산으로 권리관계가 비교적 단순합니다.")
                "0007" -> if (!p.category.isMovable) risk(12, "압류재산", "세금 체납으로 압류된 재산이라 임차인·선순위 권리를 직접 확인해야 합니다.")
                else -> Unit
            }
            if (p.evictionBy.contains("매수인") && !p.category.isMovable) risk(8, "명도 책임: 매수인", "점유자를 내보내는 일(명도)을 낙찰자가 해야 합니다. 공매는 인도명령이 없어 소송이 필요할 수 있습니다.")
        }

        caseRisks.forEach { risk(it.points, it.title, it.detail) }

        if (rights != null) {
            if (rights.seniorLeaseDeposit > 0) {
                risk(30, "선순위 임차보증금 ${Format.won(rights.seniorLeaseDeposit)}", "말소기준권리보다 먼저 전입한 임차인의 보증금을 낙찰자가 떠안을 수 있습니다.")
            }
            rights.cautions.filterNot { it.startsWith("전입일이") }.take(3).forEach { caution ->
                risk(10, caution.substringBefore(" —").substringBefore("."), caution)
            }
            if (rights.seniorLeaseDeposit == 0L && rights.cautions.isEmpty()) risk(-8, "권리 점검 이상 없음", "등기·임대차 자동 점검에서 위험 신호가 없었습니다.")
        } else if (!p.isCourt && p.assetTypeCode == "0007" && !p.category.isMovable) {
            risk(5, "권리정보 미확인", "상세 화면에서 권리·임대차 정보를 불러오면 더 정확해집니다.")
        }

        val riskScore = (10 + factors.filter { it.isRisk }.sumOf { it.points }).coerceIn(0, 100)
        val level = when {
            riskScore >= 55 -> RiskLevel.DANGER
            riskScore >= 30 -> RiskLevel.CAUTION
            else -> RiskLevel.SAFE
        }

        // ── 투자매력도 ──
        if (ratio == null || p.minBidPrice == null) {
            merit(-10, "가격 정보 부족", "감정가나 최저가가 없어 싸고 비싼지 판단하기 어렵습니다.")
        } else {
            val discount = ((100 - ratio) * 0.55).coerceIn(0.0, 33.0).roundToInt()
            if (discount > 0) merit(discount, "감정가의 ${Format.percent(ratio)}", "감정가보다 ${Format.percent(100 - ratio)} 싸게 시작합니다.")
            if (ratio > 100) merit(-5, "감정가보다 비싼 최저가", "최저가가 감정가를 넘습니다.")
        }
        val perPyeong = p.pricePerPyeong
        if (perPyeong != null && peerMedianPerPyeong != null && peerMedianPerPyeong > 0) {
            val diff = (peerMedianPerPyeong - perPyeong) * 100.0 / peerMedianPerPyeong
            if (diff >= 5) {
                merit((diff * 0.3).coerceAtMost(12.0).roundToInt(), "주변 비슷한 물건보다 평당 ${diff.roundToInt()}% 쌈", "같은 시군구·같은 용도 물건 평당가 중간값(${Format.wonShort(peerMedianPerPyeong)})보다 쌉니다.")
            } else if (diff <= -5) {
                merit(-(abs(diff) * 0.2).coerceAtMost(10.0).roundToInt(), "주변보다 평당 ${abs(diff).roundToInt()}% 비쌈", "같은 시군구·같은 용도 물건 평당가 중간값(${Format.wonShort(peerMedianPerPyeong)})보다 비쌉니다.")
            }
        }
        val marketRatio = market?.ratioOf(p.minBidPrice)
        if (market != null && marketRatio != null && market.trades.size >= 2) {
            val gap = 100 - marketRatio
            val basis = "최근 ${market.months}개월 ${if (market.sameDong) "같은 동" else "같은 시군구"} 비슷한 면적 실거래 ${market.trades.size}건으로 추정한 시세 ${Format.wonShort(market.estimatedValue)}"
            if (gap >= 5) {
                merit((gap * 0.5).coerceAtMost(25.0).roundToInt(), "실거래 시세의 ${Format.percent(marketRatio)}", "$basis 보다 쌉니다.")
            } else if (gap <= -5) {
                merit(-(abs(gap) * 0.4).coerceAtMost(15.0).roundToInt(), "실거래 시세보다 비싼 최저가", "$basis 보다 ${Format.percent(abs(gap))} 비쌉니다.")
            }
        }
        val liquidity = when (p.category) {
            Category.APARTMENT -> 15
            Category.OFFICETEL -> 8
            Category.VILLA, Category.VEHICLE -> 7
            Category.COMMERCIAL, Category.HOUSE -> 5
            Category.FACTORY, Category.LAND, Category.MOVABLE -> 3
            Category.FOREST, Category.ETC -> 0
        }
        if (liquidity > 0) merit(liquidity, "${p.category.label} (환금성)", "되팔거나 임대하기 쉬운 정도를 반영했습니다.")
        if (p.failCount in 1..2) merit(p.failCount * 2, "유찰 ${p.failCount}회로 가격 하락", "유찰될 때마다 최저가가 내려갔습니다.")
        if (Disposal.LEASE.matches(p.disposalMethod)) merit(-10, "임대(대부) 물건", "소유권을 사는 것이 아니라 일정 기간 빌려 쓰는 물건입니다.")
        if (p.interestCount >= 20) merit(-5, "관심 ${p.interestCount}명 (경쟁 예상)", "관심 등록이 많아 낙찰가가 올라갈 수 있습니다.")

        // 싸 보여도 위험하면 그만큼 매력이 줄어든다 (위험도 50 → 25% 감점, 80 → 40% 감점).
        val raw = (20 + factors.filter { !it.isRisk }.sumOf { it.points }).coerceIn(0, 100)
        val penalty = (raw * riskScore / 200.0).roundToInt()
        if (penalty >= 3 && riskScore >= 30) {
            merit(-penalty, "위험도 반영 감점", "위험도 ${riskScore}점만큼 투자매력을 낮췄습니다. 싸게 나온 이유가 위험 요소일 수 있습니다.")
        }
        val attractiveness = (raw - if (penalty >= 3 && riskScore >= 30) penalty else 0).coerceIn(0, 100)
        val rawTotal = (attractiveness * 0.5 + (100 - riskScore) * 0.5).roundToInt().coerceIn(0, 100)
        val total = when (level) {
            RiskLevel.DANGER -> rawTotal.coerceAtMost(45)
            RiskLevel.CAUTION -> rawTotal.coerceAtMost(69)
            RiskLevel.SAFE -> rawTotal
        }
        val grade = when {
            total >= 80 -> "A"
            total >= 70 -> "B+"
            total >= 60 -> "B"
            total >= 50 -> "C+"
            total >= 40 -> "C"
            else -> "D"
        }
        val analysis = Analysis(riskScore, level, attractiveness, total, grade, factors.toList(), "", rights != null || courtCase != null, marketRatio, specials)
        return analysis.copy(summary = summarize(analysis))
    }

    /** 입찰 전에 확인할 일. 이 물건에서 특히 중요한 항목이 앞에 온다. */
    fun checklist(p: Property, analysis: Analysis?, rights: PropertyDetail?, market: MarketSummary?): List<CheckItem> {
        if (p.category.isMovable) return movableChecklist(p)
        val items = mutableListOf<CheckItem>()
        val text = listOf(p.notes, p.name, p.shareInfo).joinToString(" ")
        val housing = p.category in setOf(Category.APARTMENT, Category.VILLA, Category.HOUSE, Category.OFFICETEL)

        items += CheckItem(
            "등기사항증명서로 말소기준권리 확인",
            "가장 먼저 설정된 (근)저당·(가)압류·담보가등기·경매개시결정이 말소기준권리입니다. 그보다 늦은 권리는 대부분 지워지고, 앞선 권리는 낙찰자가 떠안습니다.",
            important = rights == null || (analysis?.riskLevel ?: RiskLevel.SAFE) != RiskLevel.SAFE,
        )
        if (p.isCourt) {
            items += CheckItem("매각물건명세서 확인", "법원이 정리한 임차인·인수되는 권리·특별매각조건이 적혀 있습니다. 법원경매정보 사건 화면에서 무료로 볼 수 있습니다.", important = true)
            items += CheckItem("현황조사서·감정평가서 확인", "점유자가 누구인지, 건물 상태와 감정가 산정 근거를 확인합니다.")
        } else {
            items += CheckItem(
                "온비드 공고문·감정평가서 확인",
                "공매는 법원경매와 달리 인도명령 제도가 없어 점유자가 버티면 명도소송을 해야 합니다. 명도 책임과 특약을 꼭 읽으세요.",
                important = p.assetTypeCode == "0007" || p.evictionBy.contains("매수인"),
            )
        }
        if (housing) {
            items += CheckItem(
                "전입세대·임차인 대항력 확인",
                "말소기준권리보다 전입이 빠른 임차인은 대항력이 있어 보증금을 낙찰자가 떠안을 수 있습니다. 주민센터 전입세대열람으로 확인합니다.",
                important = (rights?.seniorLeaseDeposit ?: 0) > 0 || Regex("임차|대항력|전입").containsMatchIn(text),
            )
        }
        if (p.category in setOf(Category.APARTMENT, Category.OFFICETEL, Category.COMMERCIAL)) {
            items += CheckItem("체납 관리비 확인", "공용부분 체납 관리비는 낙찰자가 낼 수 있습니다. 관리사무소에 금액을 물어보세요.")
        }
        if (p.category == Category.LAND || p.category == Category.FOREST) {
            items += CheckItem("토지이용계획 확인 (토지이음)", "용도지역, 건축 가능 여부, 개발제한·보전 지역인지 확인합니다.", important = true)
            items += CheckItem("도로 접함·진입로 확인", "맹지는 건축이 어렵고 되팔기도 어렵습니다. 지적도와 현장을 함께 보세요.")
        }
        if (Regex("^(전|답|과수원)$|농지").containsMatchIn(p.usageSmall) || Regex("농지\\s*취득|농취증").containsMatchIn(text)) {
            items += CheckItem("농지취득자격증명 준비", "낙찰 후 7일 안에 내지 못하면 보증금을 잃을 수 있습니다. 미리 발급 가능 여부를 확인하세요.", important = true)
        }
        if (p.category in setOf(Category.HOUSE, Category.COMMERCIAL, Category.FACTORY, Category.VILLA)) {
            items += CheckItem("건축물대장 확인 (정부24)", "위반건축물 여부, 실제 용도·면적이 공고와 같은지 확인합니다.")
        }
        if (text.contains("유치권")) {
            items += CheckItem("유치권 성립 여부 확인", "공사대금 등 유치권 신고가 있습니다. 현장 점유와 신고 내용을 확인하고, 해결 비용을 입찰가에 반영하세요.", important = true)
        }
        if (Regex("지분").containsMatchIn(text)) {
            items += CheckItem("지분 활용 계획", "다른 공유자가 우선매수할 수 있고, 대출·사용이 어렵습니다. 공유물분할 등 출구를 생각해 두세요.", important = true)
        }
        if (MarketKind.of(p.category) != null && market == null) {
            items += CheckItem("주변 실거래가·시세 확인", "감정가는 몇 달 전 가격입니다. 최근 실거래가와 중개사무소 시세로 매도가를 따로 잡으세요.")
        }
        items += CheckItem("현장 방문", "점유자, 누수·균열 등 건물 상태, 주변 환경, 실제 시세를 직접 확인합니다.")
        items += CheckItem("경락잔금대출 가능 여부", "낙찰가의 몇 %까지 대출되는지 미리 알아보세요. 잔금을 못 내면 입찰보증금을 잃습니다.")
        items += if (p.isCourt) {
            CheckItem("입찰 당일 준비물", "신분증, 도장, 입찰보증금(최저가의 10%, 재매각은 20%) 수표. 입찰표 금액은 고칠 수 없으니 틀리면 새로 쓰세요.")
        } else {
            CheckItem("온비드 입찰 준비", "공동인증서 등록, 입찰보증금 납부 계좌 확인. 마감 직전에는 접속이 몰리니 미리 입찰하세요.")
        }
        return items.sortedByDescending { it.important }
    }

    private fun movableChecklist(p: Property): List<CheckItem> = buildList {
        if (p.category == Category.VEHICLE) {
            add(CheckItem("자동차등록원부 확인 (정부24, 무료)", "압류·저당권이 남아 있는지, 차대번호·연식이 공고와 같은지 확인합니다.", important = true))
            add(CheckItem("차량 상태 직접 보기", "보관 장소에서 사고·침수 흔적, 주행거리, 열쇠 유무를 확인하세요. 시동이 안 되는 차량도 많습니다.", important = true))
            add(CheckItem("이전등록 비용", "취득세(비영업용 승용 7%, 그 밖에 대체로 4~5%)와 번호판·대행료가 듭니다. 보험 가입 후 운행하세요."))
            add(CheckItem("보관료·인도 기한", "낙찰 후 정해진 날까지 가져가지 않으면 보관료가 붙거나 계약이 취소될 수 있습니다."))
        } else {
            add(CheckItem("물품 상태·진품 확인", "공고 사진과 열람 일정으로 상태를 확인하세요. 명품·귀금속은 감정서·보증서가 있는지 봅니다.", important = true))
            add(CheckItem("인도 장소·기한·운반비", "직접 가져가야 하는 경우가 많아 운반비와 일정을 미리 생각하세요."))
            if (GoodsGroup.LIQUOR.matches(p)) {
                add(CheckItem("주류 매수 자격", "주류는 면허가 있는 사업자만 살 수 있는 공고도 있습니다. 공고문의 입찰 자격을 꼭 확인하세요.", important = true))
            }
            add(CheckItem("부가세·수수료 포함 여부", "입찰가에 부가세가 포함되는지 공고문에서 확인합니다."))
        }
        add(CheckItem("온비드 입찰 준비", "공동인증서 등록, 입찰보증금 납부 계좌 확인. 마감 직전에는 접속이 몰리니 미리 입찰하세요."))
    }.sortedByDescending { it.important }

    private fun summarize(a: Analysis): String {
        val merit = a.merits.take(2).joinToString(", ") { it.title }
        val risks = a.risks.take(2).joinToString(", ") { it.title }
        val head = if (merit.isNotEmpty()) "장점: $merit." else "뚜렷한 가격 장점은 없습니다."
        val tail = when (a.riskLevel) {
            RiskLevel.SAFE -> "큰 위험 신호는 보이지 않습니다."
            RiskLevel.CAUTION -> "주의할 점: $risks. 입찰 전 확인이 필요합니다."
            RiskLevel.DANGER -> "위험 요소: $risks. 권리관계를 반드시 직접 확인하세요."
        }
        return "$head $tail"
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}
