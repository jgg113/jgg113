package io.github.jgg113.gongmae.core

/**
 * 통합 검색어를 해석한 결과. "논현동 아파트 5억 이하", "양주", "자동차", "2025타경103932" 같은 말을
 * 지역·물건 종류·가격·유찰 조건과 남는 낱말로 나눈다.
 */
data class QueryIntent(
    val text: String = "",
    val sido: String = "",
    val sigungu: String = "",
    /** 동·읍·면·리 (주소에 들어 있어야 한다) */
    val dong: String = "",
    val categories: Set<Category> = emptySet(),
    val goods: Set<GoodsGroup> = emptySet(),
    val sources: Set<Source> = emptySet(),
    val caseNo: String = "",
    val minPrice: Long? = null,
    val maxPrice: Long? = null,
    val minFails: Int? = null,
    val newOnly: Boolean = false,
    /** 어디에도 해당하지 않는 낱말 — 물건명·주소·비고에 모두 들어 있어야 한다 */
    val keywords: List<String> = emptyList(),
    /** "전국" 이라고 썼는지 */
    val nationwide: Boolean = false,
) {
    val isEmpty: Boolean get() = text.isBlank()

    /** 차량·동산을 따로 불러와야 하는 검색인지 */
    val wantsMovables: Boolean get() = categories.any { it.isMovable } || goods.isNotEmpty()

    /** 부동산 지역 검색이 필요한지 (지역이 들어 있고 차량·동산만 찾는 게 아닐 때) */
    val hasRegion: Boolean get() = sido.isNotEmpty() || sigungu.isNotEmpty() || dong.isNotEmpty()

    /** 화면에 보여 줄 해석 ("서울 강남구 · 아파트 · 5억 이하") */
    val description: String
        get() = listOfNotNull(
            if (nationwide && sido.isEmpty()) "전국" else listOf(Regions.shortName(sido), sigungu, dong).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null },
            sources.singleOrNull()?.label,
            ((categories - goods.map { it.category }.toSet()).map { it.label } + goods.map { it.label })
                .joinToString(", ").ifBlank { null },
            caseNo.ifBlank { null },
            priceText(),
            when {
                newOnly -> "신건"
                minFails != null -> "유찰 ${minFails}회 이상"
                else -> null
            },
            keywords.joinToString(" ").ifBlank { null }?.let { "\"$it\"" },
        ).joinToString(" · ")

    private fun priceText(): String? = when {
        minPrice != null && maxPrice != null -> "${Format.wonShort(minPrice)}~${Format.wonShort(maxPrice)}"
        maxPrice != null -> "${Format.wonShort(maxPrice)} 이하"
        minPrice != null -> "${Format.wonShort(minPrice)} 이상"
        else -> null
    }

    fun matches(p: Property): Boolean {
        if (isEmpty) return true
        if (caseNo.isNotEmpty()) return p.caseNo.replace(" ", "") == caseNo
        if (sources.isNotEmpty() && p.source !in sources) return false
        if (sido.isNotEmpty() && p.sido.isNotEmpty() && p.sido != sido && !p.displayAddress.contains(Regions.shortName(sido))) return false
        val address = "${p.displayAddress} ${p.roadAddress} ${p.name}"
        if (sigungu.isNotEmpty() && p.sigungu != sigungu && !address.contains(sigungu)) return false
        if (dong.isNotEmpty() && !address.contains(dong) && p.dong != dong) return false
        if (categories.isNotEmpty() || goods.isNotEmpty()) {
            // "트럭"처럼 세부 묶음을 말했으면 그 묶음만, "자동차"처럼 종류만 말했으면 그 종류 전체.
            val implied = goods.map { it.category }.toSet()
            val categoryHit = p.category in categories - implied
            val goodsHit = p.category.isMovable && goods.any { it.matches(p) }
            if (!categoryHit && !goodsHit) return false
        }
        val price = p.minBidPrice
        if (minPrice != null && (price == null || price < minPrice)) return false
        if (maxPrice != null && (price == null || price > maxPrice)) return false
        if (newOnly && p.failCount > 0) return false
        if (minFails != null && p.failCount < minFails) return false
        if (keywords.isNotEmpty()) {
            val haystack = "$address ${p.usageLarge} ${p.usageMedium} ${p.usageSmall} ${p.notes} ${p.id} ${p.caseNo} ${p.organization}"
            if (keywords.any { !haystack.contains(it, ignoreCase = true) }) return false
        }
        return true
    }
}

object QueryParser {
    private val caseNoPattern = Regex("(\\d{4})\\s*타경\\s*(\\d+)")
    private val failPattern = Regex("유찰\\s*(\\d+)\\s*(회|번)?\\s*(이상)?")
    private val amountPattern = Regex("(\\d+(?:\\.\\d+)?)\\s*(억|천만|천|만)(?:\\s*(\\d+)\\s*(천만|천|만))?\\s*원?")
    private val rangeWords = Regex("~|에서|부터")
    private val maxWords = Regex("^\\s*(이하|까지|미만|아래|안쪽|밑)")
    private val minWords = Regex("^\\s*(이상|초과|넘는|넘게|부터|위)")

    private val categoryWords: List<Pair<String, Category>> = listOf(
        "오피스텔" to Category.OFFICETEL,
        "아파트" to Category.APARTMENT, "아파트형" to Category.FACTORY,
        "빌라" to Category.VILLA, "다세대" to Category.VILLA, "연립" to Category.VILLA,
        "다가구" to Category.HOUSE, "단독" to Category.HOUSE, "주택" to Category.HOUSE, "전원주택" to Category.HOUSE,
        "상가" to Category.COMMERCIAL, "근린" to Category.COMMERCIAL, "점포" to Category.COMMERCIAL, "사무실" to Category.COMMERCIAL,
        "숙박" to Category.COMMERCIAL, "모텔" to Category.COMMERCIAL, "호텔" to Category.COMMERCIAL, "상업" to Category.COMMERCIAL,
        "공장" to Category.FACTORY, "창고" to Category.FACTORY,
        "임야" to Category.FOREST, "산지" to Category.FOREST,
        "토지" to Category.LAND, "땅" to Category.LAND, "대지" to Category.LAND, "농지" to Category.LAND, "전답" to Category.LAND, "잡종지" to Category.LAND,
        "자동차" to Category.VEHICLE, "차량" to Category.VEHICLE, "중고차" to Category.VEHICLE, "승용차" to Category.VEHICLE, "차" to Category.VEHICLE,
        "동산" to Category.MOVABLE, "물품" to Category.MOVABLE,
    )

    /** 시군구 이름 → 그 이름이 있는 시도들 */
    private val sigunguIndex: Map<String, List<String>> by lazy {
        Regions.sidoList.flatMap { sido -> Regions.sigunguOf(sido).map { it to sido } }
            .groupBy({ it.first }, { it.second })
    }

    fun parse(raw: String): QueryIntent {
        var text = raw.trim()
        if (text.isEmpty()) return QueryIntent()

        val caseMatch = caseNoPattern.find(text)
        val caseNo = caseMatch?.let { "${it.groupValues[1]}타경${it.groupValues[2]}" }.orEmpty()
        if (caseMatch != null) text = text.removeRange(caseMatch.range)

        var minFails: Int? = null
        failPattern.find(text)?.let {
            minFails = it.groupValues[1].toInt()
            text = text.removeRange(it.range)
        }
        val newOnly = text.contains("신건")
        text = text.replace("신건", " ")
        if (minFails == null && Regex("유찰").containsMatchIn(text)) minFails = 1
        text = text.replace("유찰", " ")

        // 금액: "3억~5억", "5억 이하", "1억 이상", "5천만원"
        var minPrice: Long? = null
        var maxPrice: Long? = null
        val amounts = amountPattern.findAll(text).toList()
        amounts.forEachIndexed { index, m ->
            val value = toWon(m) ?: return@forEachIndexed
            val after = text.substring(m.range.last + 1)
            val next = amounts.getOrNull(index + 1)
            val between = if (next != null) text.substring(m.range.last + 1, next.range.first) else ""
            when {
                next != null && rangeWords.containsMatchIn(between) -> minPrice = value
                minWords.containsMatchIn(after) -> minPrice = value
                index > 0 && rangeWords.containsMatchIn(text.substring(amounts[index - 1].range.last + 1, m.range.first)) -> maxPrice = value
                else -> maxPrice = value
            }
        }
        amounts.asReversed().forEach { text = text.removeRange(it.range) }
        text = text.replace(Regex("이하|이상|까지|미만|초과|부터|아래|넘는|~|에서"), " ")

        val sources = mutableSetOf<Source>()
        if (Regex("(?<!경)공매").containsMatchIn(text)) sources += Source.ONBID
        if (Regex("경매(?!공)").containsMatchIn(text) && !text.contains("경공매")) sources += Source.COURT
        text = text.replace(Regex("경공매|공매|경매|물건|매물|찾아\\S*|보여\\S*|검색"), " ")

        var sido = ""
        var sigungu = ""
        var dong = ""
        val categories = mutableSetOf<Category>()
        val goods = mutableSetOf<GoodsGroup>()
        val keywords = mutableListOf<String>()
        val nationwide = Regex("전국").containsMatchIn(text)
        text = text.replace("전국", " ")
        val tokens = text.split(Regex("[\\s,]+")).map { it.trim('.', '·') }.filter { it.isNotEmpty() }
        val sidoTokens = tokens.mapNotNull { sidoOf(it) }
        if (sidoTokens.isNotEmpty()) sido = sidoTokens.first()

        for (token in tokens) {
            if (sido.isNotEmpty() && sidoOf(token) == sido) continue
            // 지역 이름이 '…시'로 끝나면 동산 낱말보다 지역이 먼저다 (양주시 ≠ 양주).
            val sigunguHit = sigunguFor(token, sido)
            if (sigunguHit != null && (token.endsWith("시") || token.endsWith("군") || token.endsWith("구") || goodsFor(token) == null && categoryFor(token) == null)) {
                if (sido.isEmpty() && sigunguHit.second.isNotEmpty()) sido = sigunguHit.second
                sigungu = sigunguHit.first
                continue
            }
            val category = categoryFor(token)
            if (category != null) {
                categories += category
                val group = goodsFor(token)
                if (group != null && group.category == Category.VEHICLE && token !in setOf("자동차", "차량", "차")) goods += group
                continue
            }
            val group = goodsFor(token)
            if (group != null) {
                goods += group
                categories += group.category
                continue
            }
            if (token.length >= 2 && Regex("(동|읍|면|리|가|로|길)$").containsMatchIn(token) && !Regex("\\d+(동|가)$").matches(token)) {
                if (dong.isEmpty()) dong = token else keywords += token
                continue
            }
            keywords += token
        }

        return QueryIntent(
            text = raw.trim(),
            sido = sido,
            sigungu = sigungu,
            dong = dong,
            categories = categories,
            goods = goods,
            sources = sources,
            caseNo = caseNo,
            minPrice = minPrice,
            maxPrice = maxPrice,
            minFails = minFails,
            newOnly = newOnly,
            keywords = keywords,
            nationwide = nationwide,
        )
    }

    private val oldSidoNames = mapOf("전라북도" to "전북특별자치도", "강원도" to "강원특별자치도", "제주도" to "제주특별자치도")

    /** 낱말이 시도 이름이면 정식 명칭 ("서울", "서울시", "경기도", "전라북도" …) */
    fun sidoOf(token: String): String? {
        oldSidoNames[token]?.let { return it }
        return Regions.sidoList.firstOrNull { full ->
            val short = Regions.shortName(full)
            token == full || token == short || token == short + "시" || token == short + "도" || token.length >= 3 && full.startsWith(token)
        }
    }

    private fun categoryFor(token: String): Category? =
        categoryWords.firstOrNull { (word, _) -> token == word || word.length >= 2 && token.startsWith(word) && token.length <= word.length + 1 }?.second

    private fun goodsFor(token: String): GoodsGroup? =
        GoodsGroup.entries.firstOrNull { group -> group.keywords.any { it.equals(token, ignoreCase = true) || token.length >= 2 && token.startsWith(it, ignoreCase = true) && it.length >= 2 } }

    /** (시군구, 시도). 시도를 알면 그 안에서만, 모르면 한 곳에만 있는 이름일 때만 인정한다. */
    private fun sigunguFor(token: String, sido: String): Pair<String, String>? {
        val candidates = listOf(token, token + "시", token + "군", token + "구").distinct()
        for (name in candidates) {
            val sidos = sigunguIndex[name] ?: continue
            if (sido.isNotEmpty()) {
                if (sido in sidos) return name to sido
            } else if (sidos.size == 1 && (name == token || token.length >= 2)) {
                return name to sidos.single()
            }
        }
        return null
    }

    private fun toWon(m: MatchResult): Long? {
        val first = m.groupValues[1].toDoubleOrNull() ?: return null
        var won = first * unit(m.groupValues[2])
        val second = m.groupValues[3].toDoubleOrNull()
        if (second != null) won += second * unit(m.groupValues[4])
        return Math.round(won)
    }

    private fun unit(word: String): Double = when (word) {
        "억" -> 100_000_000.0
        "천만" -> 10_000_000.0
        "천" -> 10_000_000.0
        "만" -> 10_000.0
        else -> 1.0
    }
}
