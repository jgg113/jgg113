package io.github.jgg113.gongmae.core

import kotlin.math.roundToLong

data class BidInputs(
    val bidPrice: Long,
    /** 예상 매도가(시세). 모르면 감정가 */
    val expectedSalePrice: Long,
    val isHousing: Boolean,
    val over85Sqm: Boolean = false,
    /** 최저입찰가. 법원경매 보증금은 최저가 기준, 공매는 입찰가 기준이다. */
    val minPrice: Long,
    val depositOnBidPrice: Boolean = false,
    /** 입찰보증금 비율 (보통 10%, 재매각 20%) */
    val depositRate: Double = 0.10,
    val loanRatio: Double = 0.6,
    val loanRate: Double = 0.05,
    val holdingMonths: Int = 12,
    val evictionAndRepair: Long = 0,
)

data class BidResult(
    val deposit: Long,
    val acquisitionTaxRate: Double,
    val acquisitionTax: Long,
    val legalFee: Long,
    val loanAmount: Long,
    val interest: Long,
    val saleCommission: Long,
    val totalCost: Long,
    /** 대출을 뺀 실제 필요한 현금 */
    val cashNeeded: Long,
    val profit: Long,
    /** 현금 대비 수익률(%) */
    val returnOnCash: Double?,
)

/**
 * 입찰가·비용·수익 추정. 1주택(무주택→1주택) 기준 취득세이며 다주택 중과, 양도세는 빼고 계산한다.
 * 비율·금액은 모두 참고용 추정이다.
 */
object BidCalculator {
    /** 취득세 + 지방교육세 + 농어촌특별세 합산 세율 */
    fun acquisitionTaxRate(price: Long, isHousing: Boolean, over85Sqm: Boolean): Double {
        if (!isHousing) return 0.046
        val base = when {
            price <= 600_000_000 -> 0.01
            price <= 900_000_000 -> (price / 100_000_000.0 * 2 / 3 - 3) / 100
            else -> 0.03
        }
        return base + base * 0.1 + if (over85Sqm) 0.002 else 0.0
    }

    fun calculate(input: BidInputs): BidResult {
        val price = input.bidPrice
        val taxRate = acquisitionTaxRate(price, input.isHousing, input.over85Sqm)
        val tax = (price * taxRate).roundToLong()
        // 법무사 보수·등록면허세·채권 할인 등 등기 비용을 대략 0.4% + 50만원으로 본다.
        val legal = (price * 0.004).roundToLong() + 500_000
        val loan = (price * input.loanRatio).roundToLong()
        val interest = (loan * input.loanRate * input.holdingMonths / 12.0).roundToLong()
        val commission = (input.expectedSalePrice * 0.005).roundToLong()
        val total = price + tax + legal + interest + commission + input.evictionAndRepair
        val cash = total - loan
        val profit = input.expectedSalePrice - total
        return BidResult(
            deposit = ((if (input.depositOnBidPrice) price else input.minPrice) * input.depositRate).roundToLong(),
            acquisitionTaxRate = taxRate,
            acquisitionTax = tax,
            legalFee = legal,
            loanAmount = loan,
            interest = interest,
            saleCommission = commission,
            totalCost = total,
            cashNeeded = cash,
            profit = profit,
            returnOnCash = if (cash > 0) profit * 100.0 / cash else null,
        )
    }

    /** 기본 입력값: 입찰가 = 최저가, 매도가 = 실거래 추정 → 감정가 순 */
    fun defaults(p: Property, market: MarketSummary?): BidInputs {
        val minPrice = p.minBidPrice ?: p.appraisalPrice ?: 0
        return BidInputs(
            bidPrice = minPrice,
            expectedSalePrice = market?.estimatedValue ?: p.appraisalPrice ?: minPrice,
            // 오피스텔은 주택 수에 들 수는 있어도 취득세는 업무시설 세율(4.6%)이다.
            isHousing = p.category in setOf(Category.APARTMENT, Category.VILLA, Category.HOUSE),
            over85Sqm = (p.areaSqm ?: 0.0) > 85.0,
            minPrice = minPrice,
            depositOnBidPrice = !p.isCourt,
            depositRate = if (p.reauctionDate.isNotEmpty() || Regex("보증금\\s*(은|:)?\\s*20\\s*%|20\\s*%\\s*보증").containsMatchIn(p.notes)) 0.2 else 0.1,
            evictionAndRepair = when (p.category) {
                Category.APARTMENT, Category.VILLA, Category.OFFICETEL, Category.HOUSE -> 3_000_000
                Category.COMMERCIAL, Category.FACTORY -> 5_000_000
                else -> 0
            },
        )
    }

    /**
     * 적정 입찰가 범위 (참고). 기준가(실거래 추정 → 감정가)에 용도·위험도별 목표 비율을 곱하고, 최저가 아래로는 내리지 않는다.
     */
    fun suggestedRange(p: Property, analysis: Analysis?, market: MarketSummary?): Pair<Long, Long>? {
        val reference = market?.estimatedValue ?: p.appraisalPrice ?: return null
        val minPrice = p.minBidPrice ?: return null
        val target = when (p.category) {
            Category.APARTMENT -> 0.85
            Category.OFFICETEL, Category.VILLA -> 0.75
            Category.HOUSE, Category.COMMERCIAL -> 0.72
            else -> 0.65
        } - when (analysis?.riskLevel) {
            RiskLevel.DANGER -> 0.15
            RiskLevel.CAUTION -> 0.07
            else -> 0.0
        }
        val high = maxOf(minPrice, (reference * target).roundToLong())
        val low = maxOf(minPrice, (reference * (target - 0.08)).roundToLong())
        return roundDown(low) to roundDown(high)
    }

    /** 입찰표에 쓰기 좋게 십만원 단위로 내린다. */
    private fun roundDown(value: Long): Long = value / 100_000 * 100_000
}
