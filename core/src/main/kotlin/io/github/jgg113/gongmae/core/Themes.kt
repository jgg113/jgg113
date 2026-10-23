package io.github.jgg113.gongmae.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 홈 화면 추천 묶음. 불러온 물건과 자동 분석으로 고른다. */
enum class Theme(val title: String, val subtitle: String, val emoji: String) {
    PICK("권리 깨끗 + 저평가", "안전 등급이고 감정가·시세보다 싼 물건", "⭐"),
    BELOW_MARKET("시세보다 싼", "최저가가 실거래 시세 추정의 80% 이하", "💰"),
    LIKELY_SOLD("이번에 팔릴 만한", "안전하고 환금성이 좋으며 관심이 많은 물건", "🔥"),
    BIG_DISCOUNT("많이 깎인", "감정가의 60% 이하로 내려온 물건", "📉"),
    NEW("새로 나온 신건", "유찰 없이 처음 나온 물건", "🆕"),
    CLOSING("마감 임박", "3일 안에 입찰·매각기일이 오는 물건", "⏰"),
    POPULAR("조회수 TOP", "법원경매 조회수·관심등록이 많은 물건", "👀"),
    SMALL("소액 투자", "필요 현금(대출 가정)이 적은 순", "🪙");

    fun pick(list: List<Property>, analyses: Map<String, Analysis>, now: LocalDateTime): List<Property> {
        fun a(p: Property) = analyses[p.id]
        fun score(p: Property) = a(p)?.total ?: 0
        val live = list.filter { it.bidWindow(now) != BidWindow.ENDED }
        return when (this) {
            PICK -> live.filter { p ->
                val x = a(p) ?: return@filter false
                x.riskLevel == RiskLevel.SAFE && x.specialRights.isEmpty() &&
                    ((p.appraisalRatio ?: 100.0) <= 80 || (x.marketRatio ?: 100.0) <= 90) && !p.category.isMovable
            }.sortedByDescending { score(it) }
            BELOW_MARKET -> live.filter { (a(it)?.marketRatio ?: Double.MAX_VALUE) <= 80 }.sortedBy { a(it)?.marketRatio }
            LIKELY_SOLD -> live.filter { p ->
                val x = a(p) ?: return@filter false
                x.riskLevel == RiskLevel.SAFE && p.category in setOf(Category.APARTMENT, Category.OFFICETEL, Category.VILLA, Category.VEHICLE) && p.failCount <= 2
            }.sortedByDescending { it.interestCount * 3 + it.viewCount / 10 + score(it) }
            BIG_DISCOUNT -> live.filter { (it.appraisalRatio ?: 100.0) <= 60 }.sortedBy { it.appraisalRatio }
            NEW -> live.filter { it.failCount == 0 }.sortedByDescending { score(it) }
            CLOSING -> live.filter { p -> p.daysUntilEnd(now)?.let { it in 0..3 } == true }.sortedBy { it.bidEnd }
            POPULAR -> list.filter { it.viewCount > 0 || it.interestCount > 0 }.sortedByDescending { it.viewCount + it.interestCount * 20 }
            SMALL -> live.mapNotNull { p -> SmallInvest.cashNeeded(p, a(p))?.let { p to it } }.sortedBy { it.second }.map { it.first }
        }
    }
}

/** 소액 투자: 대출을 끼고 낙찰받을 때 내 돈이 얼마 드는지 대략 계산한다. */
object SmallInvest {
    /** 주거용은 낙찰가의 60%, 나머지 부동산은 50% 대출을 가정. 차량·동산은 대출 없이 세금·비용 7%. */
    fun cashNeeded(p: Property, analysis: Analysis?, market: MarketSummary? = null): Long? {
        val price = p.minBidPrice ?: return null
        if (price <= 0) return null
        if (p.category.isMovable) return Math.round(price * 1.07)
        val housing = p.category in setOf(Category.APARTMENT, Category.VILLA, Category.HOUSE, Category.OFFICETEL)
        // 위험하거나 지분·토지면 대출이 잘 안 나온다.
        val loan = when {
            analysis?.riskLevel == RiskLevel.DANGER -> 0.0
            p.category == Category.LAND || p.category == Category.FOREST -> 0.4
            housing -> 0.6
            else -> 0.5
        }
        val input = BidCalculator.defaults(p, market).copy(loanRatio = loan, holdingMonths = 6)
        return BidCalculator.calculate(input).cashNeeded
    }

    /** [budget] 원 안에서 살 수 있는 물건 (종합점수 높은 순) */
    fun within(budget: Long, list: List<Property>, analyses: Map<String, Analysis>, markets: Map<String, MarketSummary> = emptyMap()): List<Pair<Property, Long>> =
        list.mapNotNull { p -> cashNeeded(p, analyses[p.id], markets[p.id])?.takeIf { it <= budget }?.let { p to it } }
            .sortedWith(compareByDescending<Pair<Property, Long>> { analyses[it.first.id]?.total ?: 0 }.thenBy { it.second })
}

/** 매각기일·입찰마감 달력 */
object Schedule {
    private val ymd: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** 법원경매는 매각기일, 공매는 입찰 마감일 기준 */
    fun dateOf(p: Property): LocalDate? {
        val key = (if (p.isCourt) p.bidStart else p.bidEnd).take(8)
        if (key.length != 8) return null
        return runCatching { LocalDate.parse(key, ymd) }.getOrNull()
    }

    fun byDate(list: List<Property>, from: LocalDate, days: Int = 31): Map<LocalDate, List<Property>> {
        val to = from.plusDays(days.toLong())
        return list.mapNotNull { p -> dateOf(p)?.takeIf { !it.isBefore(from) && it.isBefore(to) }?.let { it to p } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
    }
}

/** 관심 물건이 다시 불러온 결과에서 바뀐 점 */
object Changes {
    fun between(old: Property, new: Property): List<String> = buildList {
        val before = old.minBidPrice
        val after = new.minBidPrice
        if (before != null && after != null && before != after) {
            val pct = (after - before) * 100.0 / before
            add(if (after < before) "최저가 인하 ${Format.wonShort(before)} → ${Format.wonShort(after)} (${Format.percent(pct)})" else "최저가 변경 ${Format.wonShort(before)} → ${Format.wonShort(after)}")
        }
        if (new.failCount > old.failCount) add("유찰 ${old.failCount}회 → ${new.failCount}회")
        if (old.statusName.isNotBlank() && new.statusName.isNotBlank() && old.statusName != new.statusName) add("상태 ${old.statusName} → ${new.statusName}")
        if (old.bidEnd.isNotBlank() && new.bidEnd.isNotBlank() && old.bidEnd.take(8) != new.bidEnd.take(8)) {
            add("일정 변경 ${Format.dateTime(old.bidEnd).substringBefore(' ')} → ${Format.dateTime(new.bidEnd).substringBefore(' ')}")
        }
    }
}
