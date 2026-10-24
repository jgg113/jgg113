package io.github.jgg113.gongmae.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jgg113.gongmae.core.BidInfo
import io.github.jgg113.gongmae.core.Format
import io.github.jgg113.gongmae.core.ItemSpec
import io.github.jgg113.gongmae.core.OnbidDocs
import io.github.jgg113.gongmae.core.Property
import io.github.jgg113.gongmae.core.RightsParser
import io.github.jgg113.gongmae.core.SoldCase

/** [Load] 상태별 공통 표시. 완료되면 [content] 를 그린다. */
@Composable
fun <T> LoadView(state: Load<T>?, idle: String = "", onRetry: (() -> Unit)? = null, content: @Composable (T) -> Unit) {
    when (state) {
        null -> if (idle.isNotBlank()) Text(idle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Load.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        is Load.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            if (onRetry != null) TextButton(onClick = onRetry) { Text("다시") }
        }
        is Load.Done -> content(state.value)
    }
}

@Composable
private fun SubTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

/** 접었다 펴는 긴 글 */
@Composable
private fun LongText(label: String, text: String, collapsedLines: Int = 4) {
    var open by rememberSaveable(label, text.length) { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (open) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (text.lines().size > collapsedLines || text.length > collapsedLines * 40) {
            TextButton(onClick = { open = !open }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if (open) "접기" else "더 보기", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 차량·동산 제원 */
@Composable
fun SpecCard(spec: ItemSpec, vehicle: Boolean) {
    SectionCard(if (vehicle) "🚗 차량 정보" else "📦 물품 정보") {
        if (spec.summary.isNotBlank()) {
            Text(spec.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        spec.rows.forEach { (label, value) -> InfoRow(label, value) }
        if (vehicle) {
            Text(
                "차량번호로 정부24 '자동차등록원부'(무료)를 떼어 압류·저당이 정리되는지, 연식·차대번호가 맞는지 확인하세요.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 온비드 물건상세의 무료 서류·유의사항·공매재산명세서 */
@Composable
fun OnbidDocsCard(vm: AppViewModel, property: Property) {
    val movable = property.movableKind != null
    val state: Load<OnbidDocs>? = if (movable) {
        vm.movableDocs[property.id]
    } else {
        when (val d = vm.details[property.id]) {
            null -> null
            DetailState.Loading -> Load.Loading
            is DetailState.Error -> Load.Error(d.message)
            is DetailState.Loaded -> Load.Done(d.detail.docs)
        }
    }
    SectionCard("📎 온비드 무료 서류·사진 (자동)") {
        LoadView(
            state,
            idle = "물건상세 API 로 실제 사진·감정평가서·지적도·유의사항을 불러옵니다.",
            onRetry = { if (movable) vm.loadMovableDocs(property, force = true) else vm.loadDetail(property.id) },
        ) { docs ->
            if (docs.isEmpty) {
                Text("온비드가 따로 올린 사진·서류가 없습니다. 온비드 원문 첨부파일을 확인하세요.", style = MaterialTheme.typography.bodySmall)
            } else {
                DocsBody(docs)
            }
        }
    }
}

@Composable
private fun DocsBody(docs: OnbidDocs) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (docs.photos.isNotEmpty()) Text("실제 사진 ${docs.photos.size}장 (위 사진에 반영)", style = MaterialTheme.typography.bodySmall)
        docs.documents.forEach { (label, url) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { Links.openUrl(context, url) }) { Text("열기") }
            }
        }
        docs.appraisals.filter { it.amount != null }.takeIf { it.isNotEmpty() }?.let { list ->
            SubTitle("감정평가")
            list.forEach { a ->
                InfoRow(a.org.ifBlank { "감정평가" }, listOfNotNull(Format.won(a.amount), a.date.takeIf { it.length == 8 }?.let(RightsParser::formatYmd), a.appraiser.ifBlank { null }).joinToString(" · "))
            }
        }
        if (docs.firstNoticeDate.isNotBlank()) InfoRow("최초 공고일", RightsParser.formatYmd(docs.firstNoticeDate))
        if (docs.batchItems.isNotEmpty()) InfoRow("일괄 매각", docs.batchItems.take(5).joinToString(", "))
        if (docs.statement.isNotEmpty()) {
            SubTitle("공매재산명세서")
            docs.statement.forEach { (k, v) -> if (v.length > 60) LongText(k, v, 3) else InfoRow(k, v) }
        }
        docs.notes.forEach { (k, v) -> LongText(k, v) }
        if (docs.corrections.isNotEmpty()) {
            SubTitle("정정 내역 ${docs.corrections.size}건")
            docs.corrections.take(5).forEach { c ->
                Text(
                    "${RightsParser.formatYmd(c.date)} ${c.item}: ${c.before.ifBlank { "-" }} → ${c.after.ifBlank { "-" }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 낙찰 사례 한 줄 */
@Composable
fun SoldRow(c: SoldCase, showRegion: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name.ifBlank { c.usage }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Text(
                if (c.sold) Format.percent(c.ratio) else c.status.ifBlank { "-" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (c.sold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            listOfNotNull(
                c.openedAt.takeIf { it.length == 12 }?.let { Format.dateTime(it).take(10) },
                c.region.takeIf { showRegion && it.isNotBlank() },
                c.spec?.summary?.ifBlank { null },
                "감정 ${Format.wonShort(c.appraisal)}",
                c.winningPrice?.let { "낙찰 ${Format.wonShort(it)}" },
                c.bidders?.let { "${it}명 입찰" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 비슷한 물건 최근 낙찰 사례 (온비드 물건 입찰결과목록) */
@Composable
fun SoldCasesCard(vm: AppViewModel, property: Property) {
    SectionCard("🔨 온비드 비슷한 물건 낙찰 사례 (최근 1년)") {
        LoadView(vm.solds[property.id], idle = "물건 입찰결과목록 API 로 불러옵니다.", onRetry = { vm.loadSold(property, force = true) }) { stats ->
            if (stats == null || stats.cases.isEmpty()) {
                Text("최근 1년 안에 비슷한 물건의 공매 낙찰 사례가 없습니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                SoldBody(stats, property)
            }
        }
    }
}

@Composable
private fun SoldBody(stats: io.github.jgg113.gongmae.core.SoldStats, property: Property) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stats.scope, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        InfoRow("낙찰가율 중간값", "감정가의 ${Format.percent(stats.medianRatio)} (낙찰 ${stats.sold.size}건)")
        stats.medianMinRatio?.let { InfoRow("최저가 대비", "최저가의 ${Format.percent(it)}에 낙찰") }
        stats.averageBidders?.let { InfoRow("평균 입찰자", "%.1f명".format(it)) }
        stats.expectedPrice(property.appraisalPrice)?.let { expected ->
            InfoRow("이 물건이라면", "약 ${Format.won(expected)} 안팎에서 낙찰 예상")
        }
        HorizontalDivider()
        stats.cases.take(8).forEach { SoldRow(it) }
        Text(
            "같은 지역·같은 용도(차량은 같은 모델)의 공매 낙찰 결과입니다. 물건마다 상태·권리가 달라 참고만 하세요.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 공고문·입찰 조건·회차 일정·첨부파일, 같은 공고 낙찰 결과 */
@Composable
fun NoticeCard(vm: AppViewModel, property: Property) {
    val context = LocalContext.current
    val info = (vm.bidInfos[property.id] as? Load.Done<BidInfo>)?.value
    val noticeNo = info?.noticeNo.orEmpty()
    SectionCard("📄 공고문·입찰 조건") {
        LoadView(vm.bidInfos[property.id], idle = "물건상세 입찰정보 API 로 입찰 조건을 불러옵니다.") { bid ->
            if (bid.noticeName.isNotBlank()) Text(bid.noticeName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            bid.rules.forEach { (k, v) -> if (v.length > 60) LongText(k, v, 3) else InfoRow(k, v) }
        }
        if (noticeNo.isNotBlank()) {
            HorizontalDivider()
            LoadView(vm.notices[noticeNo], onRetry = { vm.loadNotice(noticeNo, force = true) }) { notice ->
                if (notice.org.isNotBlank()) InfoRow("공고기관", notice.org)
                if (notice.noticeDate.isNotBlank()) InfoRow("공고일", RightsParser.formatYmd(notice.noticeDate))
                if (notice.kind.isNotBlank()) InfoRow("공고 종류", notice.kind)
                if (notice.bidMethod.isNotBlank()) InfoRow("입찰 방식", notice.bidMethod)
                notice.bidAmountsPublic?.let { InfoRow("입찰금액 공개", if (it) "모든 입찰자 공개" else "낙찰자만 공개") }
                if (notice.fee.isNotBlank()) InfoRow("참가수수료", notice.fee)
                if (notice.cancelReason.isNotBlank()) InfoRow("취소 사유", notice.cancelReason)
                if (notice.qualification.isNotBlank()) LongText("참가 자격", notice.qualification, 3)
                if (notice.schedule.isNotEmpty()) {
                    SubTitle("회차별 일정·장소")
                    notice.schedule.take(8).forEach { r ->
                        Text(
                            listOfNotNull(
                                "${r.round}회",
                                Format.period(r.bidStart, r.bidEnd),
                                r.openedAt.takeIf { it.length == 12 }?.let { "개찰 ${Format.shortDateTime(it)}" },
                                r.place.ifBlank { null },
                                r.decisionAt.takeIf { it.length == 12 }?.let { "매각결정 ${Format.shortDateTime(it)}" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (notice.attachments.isNotEmpty()) {
                    SubTitle("첨부파일 (무료)")
                    notice.attachments.forEach { (name, url) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { Links.openUrl(context, url) }) { Text("열기") }
                        }
                    }
                }
                if (notice.fullText.isNotBlank()) LongText("공고문 전문", notice.fullText, 6)
            }
            HorizontalDivider()
            SubTitle("같은 공고 다른 물건 낙찰 결과")
            LoadView(
                vm.noticeResults[noticeNo],
                onRetry = { vm.loadNoticeResults(noticeNo, force = true) },
            ) { cases ->
                val others = cases.filter { it.id != property.id }
                if (others.isEmpty()) {
                    Text("아직 개찰이 끝난 회차가 없습니다.", style = MaterialTheme.typography.bodySmall)
                } else {
                    others.take(10).forEach { SoldRow(it) }
                }
            }
            if (vm.noticeResults[noticeNo] == null) {
                OutlinedButton(onClick = { vm.loadNoticeResults(noticeNo) }) { Text("공고 입찰결과 불러오기") }
            }
        }
    }
}

/** 유가증권 (주식·출자증권) 주주·재무 */
@Composable
fun SecurityCard(vm: AppViewModel, property: Property) {
    LaunchedEffect(property.id) { vm.loadSecurity(property) }
    SectionCard("📈 유가증권 정보") {
        LoadView(vm.securities[property.id]) { info ->
            if (info == null) {
                Text("유가증권 상세 정보가 없습니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                SecurityBody(info)
            }
        }
    }
}

@Composable
private fun SecurityBody(info: io.github.jgg113.gongmae.core.SecurityInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoRow("법인", info.name)
        if (info.ceo.isNotBlank()) InfoRow("대표자", info.ceo)
        if (info.industry.isNotBlank()) InfoRow("업종", info.industry)
        if (info.products.isNotBlank()) InfoRow("주요 제품", info.products)
        if (info.foundedAt.isNotBlank()) InfoRow("설립일", RightsParser.formatYmd(info.foundedAt))
        if (info.kind.isNotBlank()) InfoRow("주식 종류", info.kind)
        if (info.shareRatio.isNotBlank()) InfoRow("매각 지분율", "${info.shareRatio}%")
        info.faceValue?.let { InfoRow("주당 액면", Format.won(it)) }
        if (info.totalShares.isNotBlank()) InfoRow("발행주식 수", "${info.totalShares}주")
        if (info.shareholders.isNotEmpty()) {
            SubTitle("주요 주주")
            info.shareholders.take(6).forEach { (name, value) -> InfoRow(name, value) }
        }
        info.financials.take(2).forEach { (year, rows) ->
            SubTitle("${year}년 재무")
            rows.forEach { (k, v) -> InfoRow(k, v) }
        }
        Text(
            "공고 당시 자료를 온비드가 참고용으로 제공한 것입니다. 비상장 주식은 되팔기 어렵고 배당이 없을 수 있습니다.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
