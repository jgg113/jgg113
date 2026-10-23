package io.github.jgg113.gongmae.core

import java.time.LocalDateTime

/** 비교표의 한 줄. [best] 는 그 항목에서 가장 유리한 물건의 위치(동점이면 여러 개). */
data class CompareRow(val label: String, val values: List<String>, val best: Set<Int> = emptySet())

object Comparison {
    const val MAX_ITEMS = 4

    fun rows(items: List<Property>, now: LocalDateTime, analyses: Map<String, Analysis> = emptyMap()): List<CompareRow> = listOfNotNull(
        CompareRow("종합점수", items.map { a -> analyses[a.id]?.let { "${it.total}점 (${it.grade})" } ?: "-" }, highest(items) { analyses[it.id]?.total?.toDouble() })
            .takeIf { analyses.isNotEmpty() },
        CompareRow("위험도", items.map { a -> analyses[a.id]?.let { "${it.riskLevel.label} ${it.riskScore}" } ?: "-" }, lowest(items) { analyses[it.id]?.riskScore?.toDouble() })
            .takeIf { analyses.isNotEmpty() },
        CompareRow("투자매력", items.map { a -> analyses[a.id]?.let { "${it.attractiveness}점" } ?: "-" }, highest(items) { analyses[it.id]?.attractiveness?.toDouble() })
            .takeIf { analyses.isNotEmpty() },
        CompareRow("구분", items.map { it.source.label + if (it.isCourt) " ${it.caseNo}" else "" }),
        CompareRow("용도", items.map { it.usageLabel }),
        CompareRow("재산유형", items.map { it.assetTypeName.ifBlank { "-" } }),
        CompareRow("처분방식", items.map { it.disposalMethod.ifBlank { "-" } }),
        CompareRow("소재지", items.map { it.displayAddress.ifBlank { "-" } }),
        CompareRow("면적", items.map { Format.area(it.areaSqm) }),
        CompareRow("감정가", items.map { Format.won(it.appraisalPrice) }),
        CompareRow("최저입찰가", items.map { Format.won(it.minBidPrice) }, lowest(items) { it.minBidPrice?.toDouble() }),
        CompareRow("감정가 대비", items.map { Format.percent(it.appraisalRatio) }, lowest(items) { it.appraisalRatio }),
        CompareRow("평당 최저가", items.map { Format.won(it.pricePerPyeong) }, lowest(items) { it.pricePerPyeong?.toDouble() }),
        CompareRow("유찰 횟수", items.map { "${it.failCount}회" }, highest(items) { it.failCount.toDouble() }),
        CompareRow("입찰기간", items.map { Format.period(it.bidStart, it.bidEnd) }),
        CompareRow("남은 기간", items.map { Format.dDay(it, now).ifEmpty { "-" } }),
        CompareRow("상태", items.map { it.bidWindow(now).label }),
        CompareRow("집행기관", items.map { it.organization.ifBlank { "-" } }),
        CompareRow("물건관리번호", items.map { it.id }),
    )

    private fun lowest(items: List<Property>, value: (Property) -> Double?): Set<Int> = extreme(items, value) { a, b -> a < b }

    private fun highest(items: List<Property>, value: (Property) -> Double?): Set<Int> = extreme(items, value) { a, b -> a > b }

    private fun extreme(items: List<Property>, value: (Property) -> Double?, better: (Double, Double) -> Boolean): Set<Int> {
        val values = items.map(value)
        val present = values.filterNotNull()
        // 두 개 이상 값이 있고 모두 같지 않을 때만 강조한다.
        if (present.size < 2 || present.distinct().size == 1) return emptySet()
        val target = present.reduce { acc, v -> if (better(v, acc)) v else acc }
        return values.indices.filter { values[it] == target }.toSet()
    }
}
