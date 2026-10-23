@file:OptIn(ExperimentalLayoutApi::class)

package io.github.jgg113.gongmae.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.jgg113.gongmae.core.Analysis
import io.github.jgg113.gongmae.core.BidCalculator
import io.github.jgg113.gongmae.core.Category
import io.github.jgg113.gongmae.core.CheckItem
import io.github.jgg113.gongmae.core.Factor
import io.github.jgg113.gongmae.core.Format
import io.github.jgg113.gongmae.core.MarketKind
import io.github.jgg113.gongmae.core.MarketSummary
import io.github.jgg113.gongmae.core.OfficialDoc
import io.github.jgg113.gongmae.core.Property
import kotlin.math.roundToLong

private const val CLAUDE_NOTE = "버튼을 누르면 이 물건 정보로 만든 질문이 Claude 앱 채팅으로 넘어가 실시간으로 분석됩니다. 내 Claude 구독으로 돌리므로 이 앱에서 따로 드는 비용은 없습니다."

/** AI 요약: 등급·점수, 핵심 근거 몇 줄, 적정 입찰가, Claude 실시간 요약 버튼. */
@Composable
fun AiSummaryCard(
    property: Property,
    analysis: Analysis,
    range: Pair<Long, Long>?,
    rights: DetailState?,
    onLoadRights: () -> Unit,
    onAskClaude: () -> Unit,
) {
    val risk = riskColors(analysis.riskLevel)
    SectionCard("AI 요약") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(analysis.grade, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${analysis.total}점", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ScoreBar("위험도", analysis.riskScore, "${analysis.riskLevel.label} ${analysis.riskScore}", risk.content)
                ScoreBar("투자매력", analysis.attractiveness, "${analysis.attractiveness}점", MaterialTheme.colorScheme.secondary)
            }
        }
        Text(analysis.summary, style = MaterialTheme.typography.bodyMedium)
        analysis.risks.take(2).forEach { KeyPoint("⚠", it.title, risk.content) }
        analysis.merits.take(2).forEach { KeyPoint("✓", it.title, MaterialTheme.colorScheme.primary) }
        analysis.marketRatio?.let { InfoRow("실거래 대비", "최저가가 시세 추정의 ${Format.percent(it)}") }
        if (range != null) InfoRow("적정 입찰가", "${Format.wonShort(range.first)} ~ ${Format.wonShort(range.second)} (참고)")
        if (!property.isCourt && !analysis.rightsChecked) {
            when (rights) {
                DetailState.Loading -> Text("권리정보 불러오는 중…", style = MaterialTheme.typography.bodySmall)
                is DetailState.Error -> Text(rights.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> OutlinedButton(onClick = onLoadRights) { Text("권리·임대차 정보로 정밀 분석") }
            }
        }
        Button(onClick = onAskClaude, modifier = Modifier.fillMaxWidth()) { Text("Claude로 실시간 요약 받기") }
        Text(CLAUDE_NOTE, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** AI 상세 분석: 근거 전체, 가격 분석, 입찰 전 체크리스트, Claude 실시간 상세 분석. 처음엔 접혀 있다. */
@Composable
fun AiDetailCard(
    property: Property,
    analysis: Analysis,
    market: MarketSummary?,
    range: Pair<Long, Long>?,
    checklist: List<CheckItem>,
    onAskClaude: () -> Unit,
    onOpenRegistry: () -> Unit,
) {
    var expanded by rememberSaveable(property.id) { mutableStateOf(false) }
    var checked by remember(property.id) { mutableStateOf(setOf<String>()) }
    SectionCard("AI 상세 분석") {
        if (!expanded) {
            Text(
                "위험·장점 근거 ${analysis.factors.size}개, 가격 분석, 입찰 전 체크리스트 ${checklist.size}개 (중요 ${checklist.count { it.important }}개)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("상세 분석 펼치기") }
        } else {
            DetailContent(property, analysis, market, range, checklist, checked, { checked = checked.toggle(it) }, onAskClaude, onOpenRegistry)
            TextButton(onClick = { expanded = false }) { Text("접기") }
        }
    }
}

@Composable
private fun DetailContent(
    property: Property,
    analysis: Analysis,
    market: MarketSummary?,
    range: Pair<Long, Long>?,
    checklist: List<CheckItem>,
    checked: Set<String>,
    onCheck: (String) -> Unit,
    onAskClaude: () -> Unit,
    onOpenRegistry: () -> Unit,
) {
    val risk = riskColors(analysis.riskLevel)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("1. 권리·위험")
        FactorList("위험 요인", analysis.risks, risk.content)
        FactorList("안전 신호", analysis.factors.filter { it.isRisk && it.points < 0 }, MaterialTheme.colorScheme.secondary)
        if (analysis.risks.isEmpty()) {
            Text("공개된 공고 문구에서 위험 신호는 찾지 못했습니다. 그래도 등기부등본으로 말소기준권리를 꼭 확인하세요.", style = MaterialTheme.typography.bodySmall)
        }

        Heading("2. 가격")
        InfoRow("감정가 대비", Format.percent(property.appraisalRatio))
        val marketRatio = market?.ratioOf(property.minBidPrice)
        InfoRow(
            "실거래 대비",
            if (market != null && marketRatio != null) {
                "시세 추정 ${Format.wonShort(market.estimatedValue)}의 ${Format.percent(marketRatio)}"
            } else {
                "아래 '주변 실거래가'에서 불러옵니다"
            },
        )
        InfoRow("평당 최저가", Format.wonShort(property.pricePerPyeong))
        if (range != null) {
            InfoRow("적정 입찰가", "${Format.won(range.first)} ~ ${Format.won(range.second)}")
            Text(
                "기준가(실거래 시세 추정, 없으면 감정가)에 용도별 평균 낙찰가율을 곱하고 위험도만큼 낮춘 참고값입니다. 최저가 아래로는 내리지 않습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FactorList("투자 장점", analysis.merits, MaterialTheme.colorScheme.primary)
        FactorList("투자 단점", analysis.factors.filter { !it.isRisk && it.points < 0 }, MaterialTheme.colorScheme.error)

        Heading("3. 입찰 전 체크리스트")
        checklist.forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(checked = item.title in checked, onCheckedChange = { onCheck(item.title) })
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text(
                        (if (item.important) "[중요] " else "") + item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (item.important) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Heading("4. Claude 실시간 상세 분석")
        Text(
            "권리분석·배당·수익률·입찰가를 Claude 가 실시간으로 자세히 풀어 줍니다. 등기부등본 PDF 를 그 채팅에 함께 첨부하면 훨씬 정확해집니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAskClaude, modifier = Modifier.weight(1f)) { Text("Claude로 상세 분석") }
            OutlinedButton(onClick = onOpenRegistry, modifier = Modifier.weight(1f)) { Text("등기부등본 떼기") }
        }
        Text(CLAUDE_NOTE, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 국토교통부 실거래가로 본 주변 시세. 화면에 보이면 자동으로 불러온다. */
@Composable
fun MarketCard(vm: AppViewModel, property: Property) {
    val kind = MarketKind.of(property.category) ?: return
    val context = LocalContext.current
    LaunchedEffect(property.id, vm.serviceKey) { vm.loadMarket(property) }
    SectionCard("주변 실거래가 (${kind.label})") {
        when (val state = vm.markets[property.id]) {
            null, MarketState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("최근 6개월 실거래가를 불러오는 중…", style = MaterialTheme.typography.bodySmall)
            }
            is MarketState.Error -> {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.needsApiApproval) {
                        OutlinedButton(onClick = { Links.openUrl(context, "https://www.data.go.kr/") }) { Text("공공데이터포털") }
                    }
                    OutlinedButton(onClick = { vm.loadMarket(property, retry = true) }) { Text("다시 시도") }
                    OutlinedButton(onClick = { Links.openOfficialDoc(context, OfficialDoc.TRADE, property) }) { Text("국토부 실거래가 사이트") }
                }
            }
            is MarketState.Loaded -> {
                val s = state.summary
                if (s.trades.isEmpty() || s.estimatedValue == null) {
                    Text("최근 ${s.months}개월 동안 이 시군구에서 비슷한 면적의 거래가 없습니다.", style = MaterialTheme.typography.bodySmall)
                } else {
                    InfoRow("시세 추정", Format.won(s.estimatedValue))
                    InfoRow("최저가 비율", "시세의 ${Format.percent(s.ratioOf(property.minBidPrice))}")
                    val perPyeong = s.medianPerSqm?.let { (it * Property.SQM_PER_PYEONG).roundToLong() }
                    InfoRow("단가 중간값", "㎡당 ${Format.wonShort(s.medianPerSqm)} · 평당 ${Format.wonShort(perPyeong)}")
                    InfoRow("비교 기준", "${if (s.sameDong) "같은 동" else "같은 시군구"} · 면적 ±20% · ${s.trades.size}건")
                    HorizontalDivider()
                    s.trades.take(8).forEach { t ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name.ifBlank { t.dong }, style = MaterialTheme.typography.bodyMedium)
                                val floor = t.floor.takeIf { it.isNotBlank() }?.let { " · ${it}층" }.orEmpty()
                                Text("${t.date} · ${t.dong} · ${Format.area(t.areaSqm)}$floor", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(Format.wonShort(t.price), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text("출처: 국토교통부 실거래가 공개 (해제된 거래 제외). 층·향·상태에 따라 실제 시세는 다릅니다.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 입찰가를 넣으면 보증금·세금·필요 현금·예상 수익을 계산한다. 금액은 만원 단위. */
@Composable
fun BidCalculatorCard(property: Property, market: MarketSummary?, range: Pair<Long, Long>?) {
    val defaults = remember(property.id, market) { BidCalculator.defaults(property, market) }
    var bid by rememberSaveable(property.id) { mutableStateOf(toMan(defaults.bidPrice)) }
    var sale by rememberSaveable(property.id, market?.estimatedValue) { mutableStateOf(toMan(defaults.expectedSalePrice)) }
    var extra by rememberSaveable(property.id) { mutableStateOf(toMan(defaults.evictionAndRepair)) }
    var loanPercent by rememberSaveable(property.id) { mutableIntStateOf((defaults.loanRatio * 100).toInt()) }
    var months by rememberSaveable(property.id) { mutableIntStateOf(defaults.holdingMonths) }

    val input = defaults.copy(
        bidPrice = fromMan(bid) ?: defaults.bidPrice,
        expectedSalePrice = fromMan(sale) ?: defaults.expectedSalePrice,
        evictionAndRepair = fromMan(extra) ?: 0,
        loanRatio = loanPercent / 100.0,
        holdingMonths = months,
    )
    val result = BidCalculator.calculate(input)

    SectionCard("입찰 계산기") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            property.minBidPrice?.let { min -> FilterChip(selected = bid == toMan(min), onClick = { bid = toMan(min) }, label = { Text("최저가") }) }
            range?.let { (low, high) ->
                FilterChip(selected = bid == toMan(low), onClick = { bid = toMan(low) }, label = { Text("추천 하단 ${Format.wonShort(low)}") })
                FilterChip(selected = bid == toMan(high), onClick = { bid = toMan(high) }, label = { Text("추천 상단 ${Format.wonShort(high)}") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManField("입찰가", bid, Modifier.weight(1f)) { bid = it }
            ManField("예상 매도가", sale, Modifier.weight(1f)) { sale = it }
        }
        if (property.minBidPrice != null && input.bidPrice < property.minBidPrice!!) {
            Text("최저입찰가(${Format.won(property.minBidPrice)})보다 낮으면 무효입니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        ManField("명도·수리비", extra, Modifier.fillMaxWidth()) { extra = it }
        Text("대출 비율", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 50, 60, 70, 80).forEach { v -> FilterChip(selected = loanPercent == v, onClick = { loanPercent = v }, label = { Text(if (v == 0) "없음" else "$v%") }) }
        }
        Text("보유 기간", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(6, 12, 24).forEach { v -> FilterChip(selected = months == v, onClick = { months = v }, label = { Text("${v}개월") }) }
        }
        HorizontalDivider()
        InfoRow("입찰보증금", "${Format.won(result.deposit)} (${if (input.depositOnBidPrice) "입찰가" else "최저가"}의 ${(input.depositRate * 100).toInt()}%)")
        InfoRow("취득세 등", "${Format.won(result.acquisitionTax)} (${Format.percent(result.acquisitionTaxRate * 100)})")
        InfoRow("등기·법무비", Format.won(result.legalFee))
        if (result.loanAmount > 0) InfoRow("대출·이자", "${Format.won(result.loanAmount)} · 이자 ${Format.won(result.interest)}")
        InfoRow("매도 중개비", Format.won(result.saleCommission))
        InfoRow("총 비용", Format.won(result.totalCost))
        Row {
            Text("필요 현금", modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(Format.won(result.cashNeeded), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
        Row {
            Text("예상 차익", modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                "${Format.won(result.profit)} (현금 대비 ${Format.percent(result.returnOnCash)})",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (result.profit >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "무주택자 1주택 취득세, 연 ${Format.percent(input.loanRate * 100)} 금리 기준이며 양도세·다주택 중과·인수 보증금은 빠져 있습니다. 참고용 추정입니다.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 등기부등본·토지이용계획·건축물대장 등 공적 서류로 가는 길. */
@Composable
fun DocumentsCard(property: Property, onOpenRegistry: () -> Unit) {
    val docs = OfficialDoc.relevant(property)
    if (docs.isEmpty() && !property.isCourt) return
    val context = LocalContext.current
    SectionCard("서류 떼기·확인") {
        if (property.isCourt) {
            DocRow("매각물건명세서·현황조사서·감정평가서", "법원경매정보 · 무료", "사건번호를 복사해 두고 법원경매정보를 엽니다.") { Links.openCourt(context, property) }
        }
        docs.forEach { doc ->
            DocRow(doc.title, "${doc.site} · ${doc.fee}", doc.note) {
                if (doc == OfficialDoc.REGISTRY) onOpenRegistry() else Links.openOfficialDoc(context, doc, property)
            }
        }
    }
}

@Composable
private fun DocRow(title: String, sub: String, note: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onOpen) { Text("열기") }
    }
}

/** 등기부등본은 인터넷등기소에서 사용자가 직접 로그인·결제한다. 앱은 주소를 복사해 두고 사이트를 열기만 한다. */
@Composable
fun RegistryDialog(property: Property, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val address = OfficialDoc.searchAddress(property)
    val kind = when (property.category) {
        Category.APARTMENT, Category.VILLA, Category.OFFICETEL -> "집합건물"
        Category.LAND, Category.FOREST -> "토지"
        else -> "건물과 토지 (따로 발급)"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("등기부등본 떼기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("대법원 인터넷등기소에서 직접 결제하고 받습니다. 이 앱은 결제·로그인 정보를 받거나 저장하지 않습니다.", style = MaterialTheme.typography.bodyMedium)
                InfoRow("수수료", OfficialDoc.REGISTRY.fee)
                InfoRow("등기 종류", kind)
                InfoRow("검색 주소", address)
                Text(
                    "1. 아래 버튼을 누르면 주소가 복사되고 인터넷등기소가 열립니다.\n" +
                        "2. 로그인 후 '부동산 등기 → 열람/발급'에서 주소를 붙여넣어 찾습니다.\n" +
                        "3. 등기 종류를 고르고 카드·계좌이체 등으로 결제합니다.\n" +
                        "4. 받은 PDF 를 'Claude로 상세 분석' 채팅에 첨부하면 권리분석을 더 정확하게 받을 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("권리 확인에는 열람용(700원)이면 충분합니다. 제출할 곳이 있을 때만 발급용을 받으세요.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                Links.openOfficialDoc(context, OfficialDoc.REGISTRY, property)
                onDismiss()
            }) { Text("주소 복사하고 열기") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun ManField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(9)) },
        label = { Text(label) },
        suffix = { Text("만원") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

private fun toMan(won: Long): String = (won / 10_000).toString()

private fun fromMan(text: String): Long? = text.toLongOrNull()?.times(10_000)

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun KeyPoint(mark: String, text: String, color: Color) {
    Row {
        Text(mark, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ScoreBar(label: String, value: Int, text: String, color: Color) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { value / 100f },
            color = color,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
fun FactorList(title: String, factors: List<Factor>, color: Color) {
    if (factors.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
    factors.forEach { f ->
        Column(Modifier.padding(start = 4.dp)) {
            Row {
                Text("• " + f.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (f.points > 0) "+${f.points}" else "${f.points}", style = MaterialTheme.typography.labelMedium, color = color)
            }
            Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
