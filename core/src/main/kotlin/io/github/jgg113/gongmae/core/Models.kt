package io.github.jgg113.gongmae.core

import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 온비드 재산유형. 부동산 물건목록 API 에서 실제로 데이터가 나오는 유형만 둔다. */
enum class AssetType(val code: String, val label: String, val hasPrivateContract: Boolean) {
    SEIZED("0007", "압류재산", false),
    GENERAL("0005", "기타일반재산", true),
    NATIONAL("0010", "국유재산", true),
    PUBLIC_OWNED("0002", "공유재산", true),
    TRUSTED("0008", "수탁재산", false);

    companion object {
        fun labelOf(code: String): String = entries.firstOrNull { it.code == code }?.label ?: ""
    }
}

enum class BidWindow(val label: String) {
    OPEN("입찰중"),
    UPCOMING("입찰예정"),
    ENDED("입찰마감"),
    UNKNOWN("일정미정"),
}

enum class Disposal(val label: String) {
    ALL("전체"),
    SALE("매각"),
    LEASE("임대");

    fun matches(method: String): Boolean = when (this) {
        ALL -> true
        SALE -> method.contains("매각")
        LEASE -> method.contains("임대") || method.contains("대부")
    }
}

enum class Category(val label: String) {
    APARTMENT("아파트"),
    VILLA("빌라·연립"),
    HOUSE("단독·다가구"),
    OFFICETEL("오피스텔"),
    COMMERCIAL("상가·업무"),
    FACTORY("공장·창고"),
    FOREST("임야"),
    LAND("토지"),
    ETC("기타");

    companion object {
        private val landTokens = setOf("전", "답", "대", "대지", "과수원", "목장용지", "잡종지", "도로", "구거", "하천", "유지", "묘지", "주차장")
        private val commercialWords = listOf("근린", "상가", "점포", "판매", "업무", "사무", "숙박", "상업", "의료", "교육", "위락", "운동", "종교")

        fun classify(usageLarge: String, usageMedium: String, usageSmall: String, name: String = ""): Category {
            fromText("$usageSmall $usageMedium", usageSmall)?.let { return it }
            if (usageMedium.contains("토지") || usageLarge.contains("토지")) return LAND
            return fromText(name, "") ?: ETC
        }

        private fun fromText(text: String, small: String): Category? {
            fun has(vararg words: String) = words.any { text.contains(it) }
            return when {
                has("오피스텔") -> OFFICETEL
                has("아파트") -> APARTMENT
                has("다세대", "연립", "빌라") -> VILLA
                small.contains("용지") -> LAND
                has("단독", "다가구", "주택") -> HOUSE
                has("임야") -> FOREST
                has("공장", "창고") -> FACTORY
                commercialWords.any { text.contains(it) } -> COMMERCIAL
                small.isNotEmpty() && (small in landTokens || has("토지")) -> LAND
                else -> null
            }
        }
    }
}

/** 한 물건의 입찰 회차 1건. 날짜는 yyyyMMddHHmm 12자리 문자열. */
@Serializable
data class BidRound(
    val round: String = "",
    val conditionNo: String = "",
    val bidStart: String = "",
    val bidEnd: String = "",
    val minBidPrice: Long? = null,
    val statusName: String = "",
)

/** 공매 물건 1건 (현재 회차 기준). 관심물건 저장을 위해 직렬화 가능하게 둔다. */
@Serializable
data class Property(
    val id: String,
    val conditionNo: String = "",
    val name: String = "",
    val address: String = "",
    val roadAddress: String = "",
    val sido: String = "",
    val sigungu: String = "",
    val dong: String = "",
    val assetTypeCode: String = "",
    val assetTypeName: String = "",
    val disposalMethod: String = "",
    val bidMethod: String = "",
    val usageLarge: String = "",
    val usageMedium: String = "",
    val usageSmall: String = "",
    val buildingAreaSqm: Double? = null,
    val landAreaSqm: Double? = null,
    val areaSqm: Double? = null,
    val appraisalPrice: Long? = null,
    val minBidPrice: Long? = null,
    val failCount: Int = 0,
    val statusCode: String = "",
    val statusName: String = "",
    val round: String = "",
    val bidStart: String = "",
    val bidEnd: String = "",
    val thumbnailUrl: String = "",
    val organization: String = "",
    val privateContract: Boolean = false,
    val rounds: List<BidRound> = emptyList(),
    val onbidCltrNo: String = "",
    val onbidPbancNo: String = "",
    val pbctNo: String = "",
    val lotPnu: String = "",
    /** 공매를 맡긴 기관 (세무서·지자체 등). [organization] 은 공고기관. */
    val requestOrg: String = "",
    val bidType: String = "",
    /** 배분요구종기 */
    val distributionDeadline: String = "",
    /** 명도책임 (매수인/매도인) */
    val evictionBy: String = "",
    val jointBidAllowed: String = "",
    val proxyBidAllowed: String = "",
) {
    /** 지오코딩에 넣을 주소 후보. 지번까지 있는 주소가 먼저, 읍면동 중심이 마지막. */
    val geocodeQueries: List<String>
        get() = listOf(AddressParser.stripTrailers(address), AddressParser.join(sido, sigungu, dong))
            .filter { it.isNotBlank() }
            .distinct()

    val onbidUrl: String? get() = OnbidLinks.detailUrl(this)

    val category: Category get() = Category.classify(usageLarge, usageMedium, usageSmall, name)

    val usageLabel: String get() = usageSmall.ifBlank { usageMedium }.ifBlank { category.label }

    val displayAddress: String
        get() = address.ifBlank { roadAddress }.ifBlank { listOf(sido, sigungu, dong).filter { it.isNotBlank() }.joinToString(" ") }

    /** 감정가 대비 최저입찰가 비율(%). */
    val appraisalRatio: Double?
        get() {
            val appraisal = appraisalPrice ?: return null
            val price = minBidPrice ?: return null
            if (appraisal <= 0) return null
            return price * 100.0 / appraisal
        }

    /** 최저입찰가 기준 평당 가격(원). */
    val pricePerPyeong: Long?
        get() {
            val area = areaSqm ?: return null
            val price = minBidPrice ?: return null
            if (area <= 0.0) return null
            return Math.round(price / (area / SQM_PER_PYEONG))
        }

    fun bidWindow(now: LocalDateTime): BidWindow {
        if (bidStart.isEmpty() || bidEnd.isEmpty()) return BidWindow.UNKNOWN
        val nowKey = now.format(KEY_FORMAT)
        return when {
            bidEnd < nowKey -> BidWindow.ENDED
            bidStart > nowKey -> BidWindow.UPCOMING
            else -> BidWindow.OPEN
        }
    }

    /** 입찰 마감일까지 남은 날짜 수 (오늘 마감이면 0, 지났으면 음수). */
    fun daysUntilEnd(now: LocalDateTime): Long? {
        val end = parseKey(bidEnd) ?: return null
        return ChronoUnit.DAYS.between(now.toLocalDate(), end.toLocalDate())
    }

    companion object {
        const val SQM_PER_PYEONG = 3.305785
        val KEY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

        fun parseKey(key: String): LocalDateTime? =
            if (key.length == 12) runCatching { LocalDateTime.parse(key, KEY_FORMAT) }.getOrNull() else null
    }
}
