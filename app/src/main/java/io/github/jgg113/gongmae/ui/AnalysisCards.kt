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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import android.widget.Toast
import io.github.jgg113.gongmae.core.AiProvider
import io.github.jgg113.gongmae.core.AiText
import io.github.jgg113.gongmae.core.AiVerdict
import io.github.jgg113.gongmae.core.Analysis
import io.github.jgg113.gongmae.core.ClaudePrompt
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

/** 'AI 실시간 분석' 버튼 아래에 붙는 설명 (설정에서 고른 AI 에 따라 다르다) */
private fun aiNote(provider: AiProvider): String = when {
    provider == AiProvider.BUILT_IN -> "앱 내장 분석은 규칙 기반이라 무료이고 바로 나옵니다. 설정 탭에서 무료 API(Gemini·Groq·OpenRouter)나 ChatGPT·Gemini·Claude 앱을 고를 수 있습니다."
    provider.isApi -> "내 ${provider.short} 무료 키로 앱 안에서 바로 분석합니다. 한도를 넘으면 멈출 뿐 요금은 나가지 않습니다."
    else -> "질문이 ${provider.label}으로 넘어가고 클립보드에도 복사됩니다. 그 앱의 내 계정(무료 계정 가능)으로 분석되며 이 앱에서 드는 비용은 없습니다."
}

/**
 * 'AI 실시간 요약/상세' 버튼과 결과, 'AI 비교' 버튼.
 * 내장·API 는 결과를 여기 보여 주고, 앱 연결은 해당 앱으로 넘긴다. AI 비교는 설정에서 고른 AI 들의 답을 나란히 보여 준다.
 * [prompt] 는 AI 에 보낼 질문, [local] 은 앱 내장 분석 글을 만든다.
 */
@Composable
fun AiActionBlock(vm: AppViewModel, property: Property, mode: ClaudePrompt.Mode, prompt: () -> String, local: () -> String) {
    val context = LocalContext.current
    val provider = vm.aiProvider
    val key = vm.aiKey(property.id, mode)
    val state = vm.aiResults[key]
    val compare = vm.compareResults[key]
    val title = if (mode == ClaudePrompt.Mode.SUMMARY) "AI 실시간 요약" else "AI 실시간 상세 분석"
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                when {
                    provider == AiProvider.BUILT_IN -> vm.showLocalAi(property.id, mode, local())
                    provider.isApi && vm.keyOf(provider).isBlank() ->
                        Toast.makeText(context, "설정 탭에서 ${provider.short} API 키를 먼저 넣으세요.", Toast.LENGTH_LONG).show()
                    provider.isApi -> vm.runApi(provider, property.id, mode, prompt())
                    else -> Links.askAiApp(context, provider, prompt())
                }
            },
            enabled = state !is AiState.Loading,
            modifier = Modifier.weight(1f),
        ) { Text("$title · ${provider.short}", maxLines = 1) }
        OutlinedButton(onClick = { vm.runCompare(property.id, mode, prompt(), local()) }) { Text("AI 비교") }
    }
    when (state) {
        AiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("AI가 분석하는 중… (보통 5~20초)", style = MaterialTheme.typography.bodySmall)
        }
        is AiState.Error -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        is AiState.Done -> AiAnswerCard(state, onCopy = { Links.copy(context, "AI 분석", AiText.plain(state.text)) }, onClose = { vm.clearAi(property.id, mode) })
        null -> Unit
    }
    if (compare != null) AiCompareView(compare, onClose = { vm.clearAi(property.id, mode) }, onCopy = { text -> Links.copy(context, "AI 분석", text) })
    Text(aiNote(provider), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AiAnswerCard(state: AiState.Done, onCopy: () -> Unit, onClose: (() -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(AiText.plain(state.text), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onCopy) { Text("복사") }
                if (onClose != null) TextButton(onClick = onClose) { Text("닫기") }
            }
        }
    }
}

/** 여러 AI 의 답을 나란히: 맨 위에 AI 별 최종 의견 표, 아래에 고른 AI 의 전체 답 */
@Composable
private fun AiCompareView(results: Map<AiProvider, AiState>, onClose: () -> Unit, onCopy: (String) -> Unit) {
    var shown by rememberSaveable { mutableStateOf(results.keys.firstOrNull()?.name.orEmpty()) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI 비교 (${results.size}개)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("닫기") }
            }
            results.forEach { (provider, state) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.short, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(90.dp))
                    when (state) {
                        AiState.Loading -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        is AiState.Error -> Text("실패: ${state.message}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
                        is AiState.Done -> {
                            val verdict = AiVerdict.of(state.text)
                            val color = when (verdict) {
                                AiVerdict.RECOMMEND -> Color(0xFF2E7D32)
                                AiVerdict.CONDITIONAL -> Color(0xFFEF6C00)
                                AiVerdict.AVOID -> Color(0xFFC62828)
                                AiVerdict.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(verdict.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = color)
                        }
                    }
                }
            }
            val verdicts = results.values.filterIsInstance<AiState.Done>().map { AiVerdict.of(it.text) }.filter { it != AiVerdict.UNKNOWN }
            if (verdicts.size >= 2) {
                Text(
                    if (verdicts.distinct().size == 1) "모든 AI 의견이 '${verdicts.first().label}'(으)로 같습니다." else "AI 마다 의견이 다릅니다. 근거를 비교해 보세요.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                results.keys.forEach { p -> FilterChip(selected = shown == p.name, onClick = { shown = p.name }, label = { Text(p.short) }) }
            }
            val selected = results.entries.firstOrNull { it.key.name == shown }?.value
            if (selected is AiState.Done) AiAnswerCard(selected, onCopy = { onCopy(AiText.plain(selected.text)) }, onClose = null)
            if (selected is AiState.Loading) Text("이 AI 가 답하는 중…", style = MaterialTheme.typography.bodySmall)
            if (selected is AiState.Error) Text(selected.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text(
                "비교에 넣을 AI 는 설정 탭에서 고릅니다. ChatGPT·Claude 앱은 답을 앱으로 가져올 수 없어 비교에서 빠지고, 대신 Groq 의 OpenAI 공개모델(gpt-oss)로 ChatGPT 계열 의견을 봅니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** AI 요약: 등급·점수, 핵심 근거 몇 줄, 적정 입찰가, AI 실시간 요약 버튼. */
@Composable
fun AiSummaryCard(
    property: Property,
    analysis: Analysis,
    range: Pair<Long, Long>?,
    rights: DetailState?,
    onLoadRights: () -> Unit,
    ai: @Composable () -> Unit,
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
        ai()
    }
}

/** AI 상세 분석: 근거 전체, 가격 분석, 입찰 전 체크리스트, AI 실시간 상세 분석. 처음엔 접혀 있다. */
@Composable
fun AiDetailCard(
    property: Property,
    analysis: Analysis,
    market: MarketSummary?,
    range: Pair<Long, Long>?,
    checklist: List<CheckItem>,
    onOpenRegistry: () -> Unit,
    ai: @Composable () -> Unit,
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
            DetailContent(property, analysis, market, range, checklist, checked, { checked = checked.toggle(it) }, onOpenRegistry, ai)
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
    onOpenRegistry: () -> Unit,
    ai: @Composable () -> Unit,
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

        Heading("4. AI 실시간 상세 분석")
        Text(
            if (property.category.isMovable) {
                "시세 대비 적정가, 상태·인도 위험, 비용과 예상 수익을 AI 가 자세히 풀어 줍니다."
            } else {
                "권리분석·배당·수익률·입찰가를 AI 가 자세히 풀어 줍니다. 앱 연결(ChatGPT·Claude 등)을 쓰면 등기부등본 PDF 를 그 채팅에 첨부해 더 정확하게 받을 수 있습니다."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        ai()
        if (!property.category.isMovable) {
            OutlinedButton(onClick = onOpenRegistry, modifier = Modifier.fillMaxWidth()) { Text("등기부등본 떼기") }
        }
    }
}

/** 국토교통부 실거래가로 본 주변 시세. 화면에 보이면 자동으로 불러온다. */
@Composable
fun MarketCard(vm: AppViewModel, property: Property) {
    val kind = MarketKind.of(property.category) ?: return
    val context = LocalContext.current
    LaunchedEffect(property.id, vm.serviceKey) { vm.loadMarket(property) }
    val shown = (vm.markets[property.id] as? MarketState.Loaded)?.summary?.kind ?: kind
    SectionCard("주변 실거래가 (${shown.label})") {
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
                    InfoRow("비교 기준", "${s.scope} · 면적 ±20% · ${s.trades.size}건")
                    s.trendPercent?.let { InfoRow("최근 3개월 추세", (if (it >= 0) "▲ +" else "▼ ") + Format.percent(it)) }
                    RentRows(vm, property)
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

/** 주변 전월세로 본 전세가율·월세 수익률 */
@Composable
private fun RentRows(vm: AppViewModel, property: Property) {
    val rent = vm.rentOf(property.id)
    when {
        rent != null && (rent.jeonseCount > 0 || rent.monthlyCount > 0) -> {
            HorizontalDivider()
            Text("주변 전월세 (${rent.scope}, 최근 ${rent.months}개월)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            rent.jeonseEstimate?.let { InfoRow("전세 추정", "${Format.won(it)} · 최저가 대비 ${Format.percent(rent.jeonseRatio(property.minBidPrice))} (${rent.jeonseCount}건)") }
            rent.monthlyRent?.let {
                InfoRow("월세 추정", "보증금 ${Format.wonShort(rent.monthlyDeposit)} / 월 ${Format.wonShort(it)} (${rent.monthlyCount}건)")
                InfoRow("월세 수익률", "연 ${Format.percent(rent.monthlyYield(property.minBidPrice))} (최저가 기준, 대출·세금 제외)")
            }
        }
        vm.rentBlocked(property) -> Text(
            "전월세 비교는 공공데이터포털 '${io.github.jgg113.gongmae.core.RentKind.of(property.category)?.apiName.orEmpty()}' 활용신청이 필요합니다.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Unit
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
    SectionCard("서류 떼기·확인 (비용 안내)") {
        val cost = OfficialDoc.minimumCost(property)
        Text(
            if (cost > 0) "이 물건을 확인하는 데 꼭 드는 비용은 약 ${"%,d".format(cost)}원입니다. 나머지 서류는 무료입니다." else "아래 서류는 모두 무료로 볼 수 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (property.isCourt) {
            DocRow("매각물건명세서·현황조사서·감정평가서", "법원경매정보 · 무료", "임차인·인수 권리·점유 상황이 적힌 핵심 서류입니다.", "법원경매정보 → 경매사건검색 → 법원·사건번호 입력 (사건번호는 자동 복사)") {
                Links.openCourt(context, property)
            }
        } else {
            DocRow("공고문·감정평가서", "온비드 · 무료", "명도 책임·특약·감정 근거가 적혀 있습니다.", "온비드 물건 화면 → 첨부파일") { Links.openOnbid(context, property) }
        }
        docs.forEach { doc ->
            DocRow(doc.title, "${doc.site} · ${doc.fee}", doc.note, doc.how) {
                if (doc == OfficialDoc.REGISTRY) onOpenRegistry() else Links.openOfficialDoc(context, doc, property)
            }
        }
    }
}

@Composable
private fun DocRow(title: String, sub: String, note: String, how: String, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (how.isNotBlank()) Text("떼는 법: $how", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        "3. 등기 종류를 고르고 결제합니다. 카카오페이·네이버페이 같은 간편결제를 등록해 두면 지문·비밀번호만으로 끝납니다.\n" +
                        "4. 받은 PDF 를 'AI 실시간 상세 분석'(ChatGPT·Claude 앱 연결) 채팅에 첨부하면 권리분석을 더 정확하게 받을 수 있습니다.",
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
