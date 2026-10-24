package io.github.jgg113.gongmae.core

import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime

data class RegistryEntry(val kind: String, val holder: String, val date: String, val amount: Long?)

data class LeaseEntry(
    val kind: String,
    val holder: String,
    val deposit: Long?,
    val monthlyRent: Long?,
    val moveInDate: String,
    val confirmDate: String,
)

data class OccupancyEntry(
    val relation: String,
    val name: String,
    val moveInDate: String,
    val confirmDate: String,
    val deposit: Long?,
    val rent: Long?,
    val part: String,
)

data class ClaimEntry(val kind: String, val name: String, val claimDate: String, val claimAmount: Long?, val note: String)

/** 부동산 물건상세 API 결과. 온비드 상세화면의 등기사항·임대차 탭과 같은 데이터(등기부 원문 아님). */
data class PropertyDetail(
    val property: Property,
    val registry: List<RegistryEntry>,
    val leases: List<LeaseEntry>,
    val occupancy: List<OccupancyEntry>,
    val claims: List<ClaimEntry>,
    /** 말소기준권리 후보: (근)저당·압류·가압류·경매·담보가등기·전세권 중 가장 이른 등기일 (yyyyMMdd). */
    val baseRightDate: String,
    /** 전입일이 말소기준권리보다 앞선 임차인의 보증금 합 — 매수인이 인수할 가능성이 있는 금액. */
    val seniorLeaseDeposit: Long,
    val cautions: List<String>,
    /** 실제 사진·감정평가서·지적도·공매재산명세서·유의사항 */
    val docs: OnbidDocs = OnbidDocs(),
)

object RightsParser {
    private val baseRightKinds = listOf("근저당권", "저당권", "압류", "가압류", "강제경매", "임의경매", "담보가등기", "전세권")

    fun parse(rows: List<JsonObject>, now: LocalDateTime): PropertyDetail {
        val docs = OnbidDocs.parse(rows)
        val assembled = PropertyAssembler.assembleOne(rows, now)
        // 목록 썸네일로 짐작한 사진보다 상세의 실제 사진 목록이 정확하다.
        val property = if (docs.photos.isEmpty()) assembled else assembled.copy(photoUrls = docs.photos)
        fun list(key: String, inner: String) = rows.asSequence().map { it[key].objects(inner) }.firstOrNull { it.isNotEmpty() }.orEmpty()

        val registry = list("rgstPrmrInfList", "rgstPrmrInf").map {
            RegistryEntry(it.str("irstDivNm"), it.str("cltrInprNm"), dateOnly(it.str("rgstYmd")), it.amount("inprStngAmt"))
        }
        val leases = list("leasInfList", "leasInf").map {
            LeaseEntry(
                kind = it.str("irstDivNm"),
                holder = it.str("cltrInprNm"),
                deposit = it.amount("bidGrteeAmt", "convGrteeAmt"),
                monthlyRent = it.amount("mthrAmt"),
                moveInDate = dateOnly(it.str("mvinYmd")),
                confirmDate = dateOnly(it.str("cfmtnYmd")),
            )
        }
        val occupancy = list("ocpyRelList", "ocpyRel").map {
            OccupancyEntry(
                relation = it.str("ocpyRelCdNm"),
                name = it.str("acpmPrptOcpyIrpsFlnm"),
                moveInDate = dateOnly(it.str("mvinYmd")),
                confirmDate = dateOnly(it.str("cfmtnYmd")),
                deposit = it.amount("acpmGrteeAmt"),
                rent = it.amount("rntAmt"),
                part = it.str("lsdPartCont"),
            )
        }
        val claims = list("dtbtRqrMtrsList", "dtbtRqrMtrs").map {
            ClaimEntry(
                kind = it.str("rgtRelCdNm"),
                name = it.str("acpmPrptBondDclFlnm"),
                claimDate = dateOnly(it.str("dtbtRqrYmd")),
                claimAmount = it.amount("dtbtRqrAmt", "bondDclrStngAmt"),
                note = it.str("etcCont"),
            )
        }

        val baseDate = registry.filter { r -> r.date.isNotEmpty() && baseRightKinds.any { r.kind.contains(it) } }
            .minOfOrNull { it.date }.orEmpty()

        // 임차 보증금: 임대차 정보 우선, 없으면 등기의 임차권 설정금액
        data class Deposit(val amount: Long, val moveIn: String)
        val deposits = leases.mapNotNull { l -> l.deposit?.takeIf { it > 0 }?.let { Deposit(it, l.moveInDate) } }
            .ifEmpty { registry.filter { it.kind.contains("임차") }.mapNotNull { r -> r.amount?.takeIf { it > 0 }?.let { Deposit(it, "") } } }
        val senior = deposits.filter { baseDate.isNotEmpty() && it.moveIn.isNotEmpty() && it.moveIn < baseDate }

        val cautions = buildList {
            if (senior.isNotEmpty()) {
                add("전입일이 말소기준권리(${formatYmd(baseDate)})보다 빠른 임차인 ${senior.size}명 — 보증금 ${Format.won(senior.sumOf { it.amount })} 인수 가능성. 배분요구·확정일자를 꼭 확인하세요.")
            } else if (deposits.isNotEmpty() && baseDate.isEmpty()) {
                add("임차 보증금 ${Format.won(deposits.sumOf { it.amount })}이 있으나 말소기준권리를 확인할 수 없어 대항력 판단이 어렵습니다.")
            }
            if (registry.any { it.kind.contains("경매") }) add("경매개시 등기가 있습니다 — 법원경매와 함께 진행되는지 확인하세요.")
            if (registry.any { it.kind.contains("가등기") }) add("가등기가 있습니다 — 담보가등기인지 순위보전 가등기인지 확인하세요.")
            if (registry.any { it.kind == "가처분" || it.kind == "예고등기" }) add("가처분·예고등기가 있습니다 — 매수인이 인수할 위험이 있습니다.")
            if (registry.any { it.kind.contains("신탁") }) add("신탁등기가 있습니다 — 신탁 이전 권리와 임차인 대항력을 따로 확인하세요.")
            if (registry.any { it.kind.contains("유치권") } || occupancy.any { it.relation.contains("유치") }) add("유치권 관련 내용이 있습니다 — 현장 확인이 필요합니다.")
        }

        return PropertyDetail(property, registry, leases, occupancy, claims, baseDate, senior.sumOf { it.amount }, cautions, docs)
    }

    private fun dateOnly(raw: String): String = raw.filter { it.isDigit() }.take(8)

    fun formatYmd(ymd: String): String =
        if (ymd.length == 8) "${ymd.substring(0, 4)}.${ymd.substring(4, 6)}.${ymd.substring(6, 8)}" else ymd.ifEmpty { "-" }
}
