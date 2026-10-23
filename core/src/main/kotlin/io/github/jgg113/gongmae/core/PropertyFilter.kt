package io.github.jgg113.gongmae.core

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/** 받아온 물건을 기기 안에서 거르는 조건. 금액은 만원 단위로 입력받는다. */
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
) {
    val activeCount: Int
        get() = listOf(
            categories.isNotEmpty(),
            minPriceManwon != null || maxPriceManwon != null,
            maxRatioPercent != null,
            minFailCount > 0,
            minAreaSqm != null || maxAreaSqm != null,
            windows != DEFAULT_WINDOWS,
            disposal != Disposal.ALL,
            keyword.isNotBlank(),
        ).count { it }

    fun matches(p: Property, now: LocalDateTime): Boolean {
        if (categories.isNotEmpty() && p.category !in categories) return false
        if (minPriceManwon != null && (p.minBidPrice ?: return false) < minPriceManwon * 10_000) return false
        if (maxPriceManwon != null && (p.minBidPrice ?: return false) > maxPriceManwon * 10_000) return false
        if (maxRatioPercent != null && (p.appraisalRatio ?: return false) > maxRatioPercent + 1e-9) return false
        if (p.failCount < minFailCount) return false
        if (minAreaSqm != null && (p.areaSqm ?: return false) < minAreaSqm) return false
        if (maxAreaSqm != null && (p.areaSqm ?: return false) > maxAreaSqm) return false
        val window = p.bidWindow(now)
        if (window != BidWindow.UNKNOWN && windows.isNotEmpty() && window !in windows) return false
        if (!disposal.matches(p.disposalMethod)) return false
        if (keyword.isNotBlank()) {
            val words = keyword.trim().split(Regex("\\s+"))
            val haystack = "${p.name} ${p.displayAddress} ${p.roadAddress} ${p.usageLabel} ${p.id}"
            if (words.any { !haystack.contains(it, ignoreCase = true) }) return false
        }
        return true
    }

    fun apply(list: List<Property>, now: LocalDateTime): List<Property> = list.filter { matches(it, now) }

    companion object {
        val DEFAULT_WINDOWS = setOf(BidWindow.OPEN, BidWindow.UPCOMING)
    }
}

enum class SortOrder(val label: String) {
    DEADLINE("마감 임박순"),
    PRICE_ASC("최저가 낮은순"),
    RATIO_ASC("감정가 대비 낮은순"),
    FAILS_DESC("유찰 많은순"),
    PYEONG_ASC("평당가 낮은순"),
    APPRAISAL_DESC("감정가 높은순");

    fun sort(list: List<Property>, now: LocalDateTime): List<Property> = when (this) {
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
