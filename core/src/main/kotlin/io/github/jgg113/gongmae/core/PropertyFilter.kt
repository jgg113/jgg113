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
) {
    @Transient
    val intent: QueryIntent = QueryParser.parse(keyword)

    val activeCount: Int
        get() = listOf(
            categories.isNotEmpty() || goods.isNotEmpty(),
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
        ).count { it }

    fun matches(p: Property, now: LocalDateTime, analysis: Analysis? = null): Boolean {
        if (maxRisk != null && analysis != null && analysis.riskLevel > maxRisk) return false
        if (minScore != null && (analysis?.total ?: 0) < minScore) return false
        if (sources.isNotEmpty() && p.source !in sources) return false
        val categoryOk = when {
            p.category.isMovable && goods.isNotEmpty() -> goods.any { it.matches(p) }
            categories.isNotEmpty() -> p.category in categories
            goods.isNotEmpty() -> false
            else -> true
        }
        if (!categoryOk) return false
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
