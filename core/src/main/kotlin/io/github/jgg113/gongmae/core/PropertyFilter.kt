package io.github.jgg113.gongmae.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.LocalDateTime

/** 대항력 있는 임차인 여부 (상세 검색) */
enum class TenantStatus(val label: String) {
    NONE("대항력 없음"),
    PRESENT("대항력 있음"),
    UNKNOWN("미상"),
}

/** 감정가 대비 최저가 구간 (여러 개 고를 수 있다) */
enum class RatioBand(val label: String, val min: Double, val max: Double) {
    UNDER_50("50% 미만", 0.0, 50.0),
    FROM_50("50~70%", 50.0, 70.0),
    FROM_70("70~90%", 70.0, 90.0),
    FROM_90("90% 이상", 90.0, Double.MAX_VALUE);

    fun contains(ratio: Double): Boolean = ratio >= min && ratio < max
}

/** 금액 구간 (원). 감정가·최저가 칩 */
enum class PriceBand(val label: String, val min: Long, val max: Long) {
    UNDER_10M("1천만 미만", 0, 10_000_000),
    TO_50M("1천~5천만", 10_000_000, 50_000_000),
    TO_100M("5천만~1억", 50_000_000, 100_000_000),
    TO_300M("1억~3억", 100_000_000, 300_000_000),
    TO_500M("3억~5억", 300_000_000, 500_000_000),
    TO_1B("5억~10억", 500_000_000, 1_000_000_000),
    TO_3B("10억~30억", 1_000_000_000, 3_000_000_000),
    OVER_3B("30억 이상", 3_000_000_000, Long.MAX_VALUE);

    fun contains(won: Long): Boolean = won >= min && won < max
}

/** 유찰 횟수 칩: 0 신건, 1·2·3회, 4 = 4회 이상 */
object FailBucket {
    val all: List<Int> = listOf(0, 1, 2, 3, 4)
    fun label(bucket: Int): String = when (bucket) {
        0 -> "신건"
        4 -> "4회 이상"
        else -> "${bucket}회"
    }
    fun of(failCount: Int): Int = failCount.coerceIn(0, 4)
}

/**
 * 받아온 물건을 기기 안에서 거르는 조건. 금액은 만원 단위로 입력받는다.
 * [keyword] 는 통합 검색어로, [QueryParser] 가 지역·종류·가격 등으로 해석해 함께 건다.
 */
@Serializable
data class PropertyFilter(
    val categories: Set<Category> = emptySet(),
    val minPriceManwon: Long? = null,
    val maxPriceManwon: Long? = null,
    val maxRatioPercent: Int? = null,
    val minFailCount: Int = 0,
    val minAreaSqm: Double? = null,
    val maxAreaSqm: Double? = null,
    val windows: Set<BidWindow> = setOf(BidWindow.OPEN, BidWindow.UPCOMING),
    val disposal: Disposal = Disposal.ALL,
    val keyword: String = "",
    /** 이 위험도까지만 보여준다. null 이면 전부 */
    val maxRisk: RiskLevel? = null,
    /** 비어 있으면 공매·경매 모두 */
    val sources: Set<Source> = emptySet(),
    /** 동산 세부 묶음 (주류·명품 …) */
    val goods: Set<GoodsGroup> = emptySet(),
    val minAppraisalManwon: Long? = null,
    val maxAppraisalManwon: Long? = null,
    /** 0 이면 신건만 */
    val maxFailCount: Int? = null,
    /** 고른 특수권리. [excludeSpecials] 면 이 권리가 있는 물건을 뺀다 */
    val specials: Set<SpecialRight> = emptySet(),
    val excludeSpecials: Boolean = false,
    /** 빠른 검색 '특수 제외': 특수권리가 하나라도 있으면 뺀다 */
    val excludeAllSpecial: Boolean = false,
    val tenants: Set<TenantStatus> = emptySet(),
    /** 입찰 마감(매각기일)까지 남은 날이 이 안인 것만 */
    val withinDays: Int? = null,
    /** AI 종합점수 하한 */
    val minScore: Int? = null,
    /** 부동산·차량·동산 (비어 있으면 [categories]·[goods] 에서 짐작한다) */
    val groups: Set<KindGroup> = emptySet(),
    /** 유찰 횟수 칩 (여러 개). [FailBucket] */
    val failBuckets: Set<Int> = emptySet(),
    val riskLevels: Set<RiskLevel> = emptySet(),
    val ratioBands: Set<RatioBand> = emptySet(),
    val priceBands: Set<PriceBand> = emptySet(),
    val appraisalBands: Set<PriceBand> = emptySet(),
    /** 진행상태 이름에 들어 있는 낱말 ("진행", "정지", "입찰준비" …) */
    val statuses: Set<String> = emptySet(),
    val disposals: Set<Disposal> = emptySet(),
) {
    @Transient
    val intent: QueryIntent = QueryParser.parse(keyword)

    val activeCount: Int
        get() = listOf(
            categories.isNotEmpty() || goods.isNotEmpty() || groups.isNotEmpty(),
            minPriceManwon != null || maxPriceManwon != null,
            maxRatioPercent != null,
            minFailCount > 0 || maxFailCount != null,
            minAreaSqm != null || maxAreaSqm != null,
            windows != DEFAULT_WINDOWS,
            disposal != Disposal.ALL,
            keyword.isNotBlank(),
            maxRisk != null,
            sources.isNotEmpty(),
            minAppraisalManwon != null || maxAppraisalManwon != null,
            specials.isNotEmpty() || excludeAllSpecial,
            tenants.isNotEmpty(),
            withinDays != null,
            minScore != null,
            failBuckets.isNotEmpty(),
            riskLevels.isNotEmpty(),
            ratioBands.isNotEmpty(),
            priceBands.isNotEmpty(),
            appraisalBands.isNotEmpty(),
            statuses.isNotEmpty(),
            disposals.isNotEmpty(),
        ).count { it }

    /** 고른 큰 종류. 직접 고르지 않았으면 세부 종류에서 짐작한다. */
    val effectiveGroups: Set<KindGroup>
        get() = groups + categories.map { it.group } + goods.map { it.group }

    /** 큰 종류·세부 종류 조건 */
    fun matchesKind(p: Property): Boolean {
        val wanted = effectiveGroups
        if (wanted.isEmpty()) return true
        val group = p.category.group
        if (group !in wanted) return false
        return when (group) {
            KindGroup.REAL_ESTATE -> {
                val subs = categories.filter { it.group == KindGroup.REAL_ESTATE }
                subs.isEmpty() || p.category in subs
            }
            else -> {
                val subs = goods.filter { it.group == group }
                subs.isEmpty() || subs.any { it.matches(p) } || (GoodsGroup.ETC in subs && GoodsGroup.of(p) == GoodsGroup.ETC)
            }
        }
    }

    fun matches(p: Property, now: LocalDateTime, analysis: Analysis? = null): Boolean {
        if (maxRisk != null && analysis != null && analysis.riskLevel > maxRisk) return false
        if (riskLevels.isNotEmpty() && analysis != null && analysis.riskLevel !in riskLevels) return false
        if (minScore != null && (analysis?.total ?: 0) < minScore) return false
        if (sources.isNotEmpty() && p.source !in sources) return false
        if (!matchesKind(p)) return false
        if (failBuckets.isNotEmpty() && FailBucket.of(p.failCount) !in failBuckets) return false
        if (ratioBands.isNotEmpty() && ratioBands.none { band -> p.appraisalRatio?.let(band::contains) == true }) return false
        if (priceBands.isNotEmpty() && priceBands.none { band -> p.minBidPrice?.let(band::contains) == true }) return false
        if (appraisalBands.isNotEmpty() && appraisalBands.none { band -> p.appraisalPrice?.let(band::contains) == true }) return false
        if (statuses.isNotEmpty() && statuses.none { p.statusName.contains(it) }) return false
        if (disposals.isNotEmpty() && disposals.none { it.matches(p.disposalMethod) }) return false
        if (minPriceManwon != null && (p.minBidPrice ?: return false) < minPriceManwon * 10_000) return false
        if (maxPriceManwon != null && (p.minBidPrice ?: return false) > maxPriceManwon * 10_000) return false
        if (minAppraisalManwon != null && (p.appraisalPrice ?: return false) < minAppraisalManwon * 10_000) return false
        if (maxAppraisalManwon != null && (p.appraisalPrice ?: return false) > maxAppraisalManwon * 10_000) return false
        if (maxRatioPercent != null && (p.appraisalRatio ?: return false) > maxRatioPercent + 1e-9) return false
        if (p.failCount < minFailCount) return false
        if (maxFailCount != null && p.failCount > maxFailCount) return false
        if (minAreaSqm != null && (p.areaSqm ?: return false) < minAreaSqm) return false
        if (maxAreaSqm != null && (p.areaSqm ?: return false) > maxAreaSqm) return false
        val window = p.bidWindow(now)
        if (window != BidWindow.UNKNOWN && windows.isNotEmpty() && window !in windows) return false
        if (withinDays != null) {
            val days = p.daysUntilEnd(now) ?: return false
            if (days < 0 || days > withinDays) return false
        }
        if (!disposal.matches(p.disposalMethod)) return false
        if (specials.isNotEmpty() || excludeAllSpecial) {
            val found = analysis?.specialRights ?: SpecialRight.of(p)
            if (excludeAllSpecial && found.isNotEmpty()) return false
            if (specials.isNotEmpty()) {
                val hit = found.any { it in specials }
                if (excludeSpecials == hit) return false
            }
        }
        if (tenants.isNotEmpty() && (analysis?.tenantStatus ?: TenantStatus.UNKNOWN) !in tenants) return false
        if (!intent.matches(p)) return false
        return true
    }

    fun apply(list: List<Property>, now: LocalDateTime, analyses: Map<String, Analysis> = emptyMap()): List<Property> =
        list.filter { matches(it, now, analyses[it.id]) }

    companion object {
        val DEFAULT_WINDOWS = setOf(BidWindow.OPEN, BidWindow.UPCOMING)
    }
}

enum class SortOrder(val label: String) {
    SCORE_DESC("종합점수 높은순"),
    ATTRACT_DESC("투자매력 높은순"),
    SAFE_FIRST("안전한 순"),
    DEADLINE("마감 임박순"),
    PRICE_ASC("최저가 낮은순"),
    RATIO_ASC("감정가 대비 낮은순"),
    FAILS_DESC("유찰 많은순"),
    PYEONG_ASC("평당가 낮은순"),
    APPRAISAL_DESC("감정가 높은순");

    fun sort(list: List<Property>, now: LocalDateTime, analyses: Map<String, Analysis> = emptyMap()): List<Property> = when (this) {
        SCORE_DESC -> list.sortedByDescending { analyses[it.id]?.total ?: -1 }
        ATTRACT_DESC -> list.sortedByDescending { analyses[it.id]?.attractiveness ?: -1 }
        SAFE_FIRST -> list.sortedWith(compareBy<Property> { analyses[it.id]?.riskScore ?: 101 }.thenByDescending { analyses[it.id]?.total ?: -1 })
        DEADLINE -> list.sortedWith(
            compareBy<Property> { deadlineRank(it.bidWindow(now)) }.thenBy { it.bidEnd.ifEmpty { "9".repeat(12) } },
        )
        PRICE_ASC -> list.sortedWith(byNullable { it.minBidPrice })
        RATIO_ASC -> list.sortedWith(byNullable { it.appraisalRatio })
        FAILS_DESC -> list.sortedWith(compareByDescending<Property> { it.failCount }.thenBy { it.appraisalRatio ?: Double.MAX_VALUE })
        PYEONG_ASC -> list.sortedWith(byNullable { it.pricePerPyeong })
        APPRAISAL_DESC -> list.sortedWith(compareByDescending<Property> { it.appraisalPrice ?: Long.MIN_VALUE })
    }

    private fun deadlineRank(window: BidWindow) = when (window) {
        BidWindow.OPEN -> 0
        BidWindow.UPCOMING -> 1
        BidWindow.UNKNOWN -> 2
        BidWindow.ENDED -> 3
    }

    /** 값이 없는 물건은 맨 뒤로 보낸다. */
    private fun <T : Comparable<T>> byNullable(selector: (Property) -> T?): Comparator<Property> =
        compareBy(nullsLast(naturalOrder<T>()), selector)
}
