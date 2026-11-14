package io.github.jgg113.stocknote.core

import kotlinx.serialization.Serializable
import kotlin.math.floor

@Serializable
enum class AccountType(val label: String) {
    /** 가상 돈으로 사고판다 */
    PAPER("모의투자"),
    /** 실제로 가진 종목을 직접 적어 둔다 (현금 없음) */
    MANUAL("내 보유 종목"),
}

@Serializable
data class Holding(val asset: Asset, val quantity: Double, val avgPrice: Double) {
    val cost: Double get() = quantity * avgPrice
}

@Serializable
data class TradeRecord(
    val time: Long,
    val asset: Asset,
    val side: Signal,
    val quantity: Double,
    val price: Double,
    val fee: Double,
    /** 매도일 때 실현 손익 (수수료 뺀 값) */
    val realized: Double = 0.0,
)

@Serializable
data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val cashKrw: Double = 0.0,
    val cashUsd: Double = 0.0,
    val depositedKrw: Double = 0.0,
    val depositedUsd: Double = 0.0,
    val holdings: List<Holding> = emptyList(),
    val history: List<TradeRecord> = emptyList(),
) {
    fun cash(currency: Currency) = if (currency == Currency.USD) cashUsd else cashKrw

    fun holding(asset: Asset): Holding? = holdings.firstOrNull { it.asset.key == asset.key }

    val realizedKrw: Double get() = history.filter { it.asset.market.currency == Currency.KRW }.sumOf { it.realized }
    val realizedUsd: Double get() = history.filter { it.asset.market.currency == Currency.USD }.sumOf { it.realized }

    companion object {
        fun paper(id: String, name: String = "모의투자", krw: Double = 100_000_000.0, usd: Double = 100_000.0) =
            Account(id, name, AccountType.PAPER, krw, usd, krw, usd)

        fun manual(id: String, name: String = "내 보유 종목") = Account(id, name, AccountType.MANUAL)
    }
}

/** 수수료·세금 설정 (%) */
@Serializable
data class FeeConfig(
    val krBuyPct: Double = 0.015,
    val krSellPct: Double = 0.015,
    /** 국내 매도 시 증권거래세 (농특세 포함) */
    val krTaxPct: Double = 0.20,
    val usPct: Double = 0.25,
    val cryptoPct: Double = 0.05,
) {
    fun buyPct(market: Market) = when (market) {
        Market.KR -> krBuyPct
        Market.US -> usPct
        Market.CRYPTO -> cryptoPct
    }

    fun sellPct(market: Market) = when (market) {
        Market.KR -> krSellPct + krTaxPct
        Market.US -> usPct
        Market.CRYPTO -> cryptoPct
    }

    fun roundTripPct(market: Market) = buyPct(market) + sellPct(market)
}

class TradeException(message: String) : Exception(message)

/** 모의 주문을 처리한다. 가격은 지금 시세(또는 최근 종가)를 그대로 쓴다. */
object PaperBroker {
    /** 코인은 소수점 수량, 주식은 정수 수량 */
    fun normalizeQty(market: Market, qty: Double): Double =
        if (market == Market.CRYPTO) floor(qty * 1e8) / 1e8 else floor(qty)

    /** 가진 현금으로 살 수 있는 최대 수량 */
    fun maxBuyQty(account: Account, asset: Asset, price: Double, fees: FeeConfig): Double {
        if (price <= 0) return 0.0
        val cash = account.cash(asset.market.currency)
        return normalizeQty(asset.market, cash / (price * (1 + fees.buyPct(asset.market) / 100)))
    }

    fun buy(account: Account, asset: Asset, quantity: Double, price: Double, fees: FeeConfig, time: Long): Account {
        val qty = normalizeQty(asset.market, quantity)
        if (qty <= 0) throw TradeException("수량을 확인하세요.")
        if (price <= 0) throw TradeException("가격 정보가 없습니다.")
        val amount = qty * price
        val fee = amount * fees.buyPct(asset.market) / 100
        val currency = asset.market.currency
        if (account.type == AccountType.PAPER && account.cash(currency) + 1e-9 < amount + fee) {
            throw TradeException("현금이 모자랍니다. 최대 ${Fmt.number(maxBuyQty(account, asset, price, fees))}${if (asset.market == Market.CRYPTO) "개" else "주"}까지 살 수 있습니다.")
        }
        val old = account.holding(asset)
        val newQty = (old?.quantity ?: 0.0) + qty
        val newAvg = ((old?.cost ?: 0.0) + amount + if (account.type == AccountType.PAPER) fee else 0.0) / newQty
        val holdings = account.holdings.filterNot { it.asset.key == asset.key } + Holding(asset, newQty, newAvg)
        val record = TradeRecord(time, asset, Signal.BUY, qty, price, fee)
        return if (account.type == AccountType.PAPER) {
            if (currency == Currency.USD) account.copy(cashUsd = account.cashUsd - amount - fee, holdings = holdings, history = account.history + record)
            else account.copy(cashKrw = account.cashKrw - amount - fee, holdings = holdings, history = account.history + record)
        } else {
            account.copy(holdings = holdings, history = account.history + record.copy(fee = 0.0))
        }
    }

    fun sell(account: Account, asset: Asset, quantity: Double, price: Double, fees: FeeConfig, time: Long): Account {
        val old = account.holding(asset) ?: throw TradeException("가지고 있지 않은 종목입니다.")
        val qty = normalizeQty(asset.market, quantity).coerceAtMost(old.quantity)
        if (qty <= 0) throw TradeException("수량을 확인하세요.")
        if (price <= 0) throw TradeException("가격 정보가 없습니다.")
        val amount = qty * price
        val fee = if (account.type == AccountType.PAPER) amount * fees.sellPct(asset.market) / 100 else 0.0
        val realized = amount - fee - qty * old.avgPrice
        val left = old.quantity - qty
        val holdings = account.holdings.filterNot { it.asset.key == asset.key } +
            if (left > 1e-9) listOf(old.copy(quantity = left)) else emptyList()
        val record = TradeRecord(time, asset, Signal.SELL, qty, price, fee, realized)
        val currency = asset.market.currency
        return if (account.type == AccountType.PAPER) {
            if (currency == Currency.USD) account.copy(cashUsd = account.cashUsd + amount - fee, holdings = holdings, history = account.history + record)
            else account.copy(cashKrw = account.cashKrw + amount - fee, holdings = holdings, history = account.history + record)
        } else {
            account.copy(holdings = holdings, history = account.history + record)
        }
    }

    /** 가상 시드를 더 넣는다 (모의투자만) */
    fun deposit(account: Account, currency: Currency, amount: Double): Account {
        if (amount <= 0) return account
        return if (currency == Currency.USD) account.copy(cashUsd = account.cashUsd + amount, depositedUsd = account.depositedUsd + amount)
        else account.copy(cashKrw = account.cashKrw + amount, depositedKrw = account.depositedKrw + amount)
    }
}

/** 평가 결과 */
data class PortfolioValuation(
    val rows: List<Row>,
    val cashKrw: Double,
    val cashUsd: Double,
    val usdKrw: Double,
) {
    data class Row(val holding: Holding, val price: Double, val value: Double, val pnl: Double, val pnlPct: Double, val currency: Currency)

    val valueKrw: Double get() = rows.filter { it.currency == Currency.KRW }.sumOf { it.value }
    val valueUsd: Double get() = rows.filter { it.currency == Currency.USD }.sumOf { it.value }
    val costKrw: Double get() = rows.filter { it.currency == Currency.KRW }.sumOf { it.holding.cost }
    val costUsd: Double get() = rows.filter { it.currency == Currency.USD }.sumOf { it.holding.cost }

    /** 원화로 합친 총자산 (현금 포함, 환율이 없으면 달러 자산은 빠진다) */
    val totalKrw: Double get() = valueKrw + cashKrw + if (usdKrw > 0) (valueUsd + cashUsd) * usdKrw else 0.0

    /** 원화로 합친 평가손익 */
    val pnlKrw: Double get() = (valueKrw - costKrw) + if (usdKrw > 0) (valueUsd - costUsd) * usdKrw else 0.0

    val pnlPct: Double get() {
        val cost = costKrw + if (usdKrw > 0) costUsd * usdKrw else 0.0
        return if (cost <= 0) 0.0 else pnlKrw / cost * 100
    }

    /** 종목별 비중 (원화 환산 기준, %) */
    fun weights(): List<Pair<Holding, Double>> {
        val values = rows.map { it.holding to if (it.currency == Currency.USD) it.value * usdKrw.coerceAtLeast(0.0) else it.value }
        val total = values.sumOf { it.second }
        return if (total <= 0) values.map { it.first to 0.0 } else values.map { it.first to it.second / total * 100 }.sortedByDescending { it.second }
    }

    companion object {
        fun of(account: Account, prices: Map<String, Double>, usdKrw: Double): PortfolioValuation {
            val rows = account.holdings.map { h ->
                val p = prices[h.asset.key]?.takeIf { it > 0 } ?: h.avgPrice
                val value = p * h.quantity
                PortfolioValuation.Row(h, p, value, value - h.cost, if (h.cost > 0) (value / h.cost - 1) * 100 else 0.0, h.asset.market.currency)
            }.sortedByDescending { if (it.currency == Currency.USD) it.value * usdKrw else it.value }
            return PortfolioValuation(rows, account.cashKrw, account.cashUsd, usdKrw)
        }
    }
}
