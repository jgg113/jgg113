package io.github.jgg113.stocknote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jgg113.stocknote.core.AiForecastResult
import io.github.jgg113.stocknote.core.AiHorizon
import io.github.jgg113.stocknote.core.AiOpinion
import io.github.jgg113.stocknote.core.Currency
import io.github.jgg113.stocknote.core.Fmt
import io.github.jgg113.stocknote.core.IndicatorConfig
import io.github.jgg113.stocknote.core.IndicatorType
import io.github.jgg113.stocknote.core.Market
import io.github.jgg113.stocknote.core.ModelReport
import io.github.jgg113.stocknote.core.PatternMatch
import io.github.jgg113.stocknote.core.Reliability
import io.github.jgg113.stocknote.core.StockPrompt
import io.github.jgg113.stocknote.ui.theme.PriceColors

private val patternIndicators = listOf(IndicatorConfig(IndicatorType.MA, listOf(5.0, 10.0, 20.0, 60.0, 0.0)))

@Composable
fun aiOpinionColor(o: AiOpinion): Color = when (o.tone) {
    2, 1 -> PriceColors.up
    -1, -2 -> PriceColors.down
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun reliabilityColor(r: Reliability): Color = when (r) {
    Reliability.HIGH -> Color(0xFF13A38B)
    Reliability.MEDIUM -> Color(0xFFF2A007)
    Reliability.LOW -> MaterialTheme.colorScheme.error
}

/** 종목 화면 → AI 예측 탭 */
@Composable
fun AiForecastTab(vm: AppViewModel) {
    val d = vm.detail ?: return
    val h = vm.aiHorizon
    val state = vm.ai[h]
    val context = LocalContext.current
    LaunchedEffect(d.asset.key, h, d.daily.size) { if (d.daily.isNotEmpty()) vm.loadAi(h) }

    ChipRow(AiHorizon.entries, { it == h }, { "${it.label} (${it.period})" }, Modifier.padding(top = 8.dp)) { vm.loadAi(it) }
    if (d.loading) return
    if (state == null || state.loading) {
        Loading(state?.progress?.ifBlank { null } ?: "AI 모델을 준비하는 중…")
        SmallLabel("같은 시장 대표 종목들의 6년치 일봉으로 모델 4종을 학습합니다. 처음에는 시세를 받느라 1~2분 걸릴 수 있고, 하루 동안은 다시 학습하지 않습니다.", Modifier.padding(horizontal = 16.dp))
        return
    }
    state.error?.let { ErrorNotice(it) { vm.loadAi(h, force = true) }; return }
    val r = state.result ?: return
    val cur = d.asset.market.currency
    val levels = if (vm.aiShowOnChart) listOf(
        ChartLevel("목표2", r.target2, PriceColors.up),
        ChartLevel("목표1", r.target1, PriceColors.up.copy(alpha = 0.75f)),
        ChartLevel("손절가", r.stop, PriceColors.down),
    ) else emptyList()
    CandleChart(
        d.daily, patternIndicators, cur, Modifier.padding(top = 6.dp),
        future = if (vm.aiShowOnChart) r.path else null,
        levels = levels, mainHeight = 300.dp, initialBars = if (h == AiHorizon.SHORT) 80 else 160,
    )

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI 예측기는 ${d.asset.name}을(를) ", style = MaterialTheme.typography.titleMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(r.opinion.label, aiOpinionColor(r.opinion))
            Text(" (으)로 예측했어요", style = MaterialTheme.typography.titleMedium)
        }
        SmallLabel("일봉 · 분석 기간 ${Fmt.decimal(r.analysisYears).removeSuffix(".00")}년 · 예측 기간 ${h.period} 기준 · ${r.trainAssets}종목으로 학습")
        Spacer(Modifier.height(8.dp))
        OpinionGauge(r.score, r.opinion)
        Spacer(Modifier.height(8.dp))
        BigRow("예상주가", Fmt.price(r.expectedPrice, cur), PriceColors.color(r.expectedReturn))
        BigRow("예상수익률", Fmt.signedRate(r.expectedReturn * 100), PriceColors.color(r.expectedReturn))
        BigRow("상승확률", "${Fmt.decimal(r.upProb * 100).removeSuffix(".00")}%", MaterialTheme.colorScheme.onSurface)
        SmallLabel("같은 기간 이 시장 종목이 오른 비율은 평소 ${Fmt.decimal(r.baseUpProb * 100)}%였습니다.")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("검증 신뢰도 ", style = MaterialTheme.typography.bodyMedium)
            Pill(r.reliability.label, reliabilityColor(r.reliability))
        }
        SmallLabel(
            when (r.reliability) {
                Reliability.HIGH -> "과거 검증에서 우연보다 뚜렷이 잘 맞혔습니다. 그래도 작은 우위일 뿐입니다."
                Reliability.MEDIUM -> "과거 검증에서 약간의 우위가 있었습니다. 다른 근거와 함께 보세요."
                Reliability.LOW -> "과거 검증에서 우연과 구별할 만큼 잘 맞히지 못했습니다. 그래서 예측을 평소 확률 쪽으로 크게 줄였습니다." +
                    if (h != AiHorizon.SHORT) " 기간이 길수록 겹치지 않는 검증 사례가 적어 증명하기 어렵습니다." else ""
            },
        )
    }

    SectionCard("AI가 분석한 목표·손절가", action = {
        Text("차트표기", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Switch(vm.aiShowOnChart, { vm.aiShowOnChart = it })
    }) {
        TargetLadder(r, cur)
        SmallLabel("목표1·목표2는 예상 수익률에 이 종목의 같은 기간 수익률 분포(상위 30%·10%)를 더한 값, 손절가는 하위 20% 또는 변동폭(ATR)의 1.5배 중 더 낮은 값입니다.")
    }

    SectionCard("모델별 예측과 검증 성적") {
        Row(Modifier.fillMaxWidth()) {
            SmallLabel("모델", Modifier.weight(1.4f)); SmallLabel("상승확률", Modifier.weight(1f)); SmallLabel("검증 AUC", Modifier.weight(1.2f)); SmallLabel("가중치", Modifier.weight(0.8f))
        }
        r.models.forEach { m -> ModelRow(m) }
        Spacer(Modifier.height(6.dp))
        SmallLabel(
            "검증: 시간 순서대로 앞부분으로 학습하고 그 뒤 기간으로 두 번 시험했습니다 (검증 ${Fmt.number(r.validSamples.toDouble())}건, 정답 기간이 겹치지 않게 세면 ${r.effectiveSamples}건). " +
                "AUC 0.5 는 동전 던지기, 0.55 면 작지만 쓸 만한 우위입니다. 우연 범위(±)를 뺀 실력만큼만 가중치를 줍니다.",
        )
        if (r.topFeatures.isNotEmpty()) SmallLabel("많이 쓰인 특징: ${r.topFeatures.joinToString(", ")}")
    }

    if (r.matches.isNotEmpty()) SectionCard("AI가 참고한 차트 패턴") {
        r.matches.take(3).forEachIndexed { i, m ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            PatternCard(m, h)
        }
        SmallLabel("지금 ${h.window}일 차트와 모양(정규화한 가격 흐름)이 비슷했던 과거 구간입니다. 칠한 부분이 그 뒤 ${h.period} 동안의 실제 움직임입니다.")
    }

    SectionCard("AI 해설") {
        val provider = vm.store.aiProvider
        Button(onClick = { vm.askAi(StockPrompt.Mode.SUMMARY) { p, text -> handoffToAi(context, p, text) } }) {
            Text(if (provider == io.github.jgg113.stocknote.core.AiProvider.NONE) "AI 해설 받기" else "AI 해설 받기 (${provider.label})")
        }
        d.aiText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
        d.aiError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (d.aiLoading) Loading("AI 가 답을 쓰는 중…")
    }
    OutlinedButton(onClick = { vm.loadAi(h, force = true) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("다시 학습") }
    SmallLabel("과거 자료로 계산한 통계 예측입니다. 미래를 보장하지 않으며 투자 판단의 책임은 본인에게 있습니다.", Modifier.padding(16.dp))
}

@Composable
private fun BigRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
    }
}

/** 반원 게이지: 왼쪽 강력매도 → 오른쪽 강력매수 */
@Composable
fun OpinionGauge(score: Double, opinion: AiOpinion) {
    val colors = listOf(Color(0xFF6E8BFF), Color(0xFFAFC3FF), Color(0xFFE3E3E3), Color(0xFFFFB27A), Color(0xFFFF5A5F))
    val needleColor = aiOpinionColor(opinion)
    Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.size(260.dp, 150.dp)) {
            val stroke = 26f
            val d = size.width - stroke
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arc = Size(d, d)
            colors.forEachIndexed { i, c ->
                drawArc(c, 180f + i * 36f + 1f, 34f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Butt))
            }
            val angle = Math.toRadians(180.0 + score.coerceIn(0.0, 100.0) / 100 * 180)
            val r = d / 2
            val center = Offset(size.width / 2, stroke / 2 + r)
            val p = Offset(center.x + (r * kotlin.math.cos(angle)).toFloat(), center.y + (r * kotlin.math.sin(angle)).toFloat())
            drawCircle(Color.White, 18f, p)
            drawCircle(needleColor, 18f, p, style = Stroke(6f))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 18.dp)) {
            Text("투자의견", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(opinion.label, color = needleColor, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp)
            Text("${Fmt.decimal(score).substringBefore('.')}점", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 목표2·목표1·현재·손절가를 세로 눈금으로 */
@Composable
private fun TargetLadder(r: AiForecastResult, cur: Currency) {
    val rows = listOf(
        Triple("목표2", r.target2, PriceColors.up),
        Triple("목표1", r.target1, PriceColors.up),
        Triple("현재", r.price, MaterialTheme.colorScheme.onSurface),
        Triple("손절가", r.stop, PriceColors.down),
    )
    rows.forEach { (label, price, color) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(6.dp).height(28.dp).background(color.copy(alpha = if (label == "현재") 0.3f else 0.8f), RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(10.dp))
            Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
                Text(label, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Text(Fmt.price(price, cur), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Text(if (label == "현재") "" else Fmt.signedRate(r.pct(price)), color = color, modifier = Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ModelRow(m: ModelReport) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
            Text(if (m.upProb.isNaN()) "-" else "${Fmt.decimal(m.upProb * 100)}%", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            val z = if (m.aucSe > 0 && !m.auc.isNaN()) (m.auc - 0.5) / m.aucSe else 0.0
            Text(
                if (m.auc.isNaN()) "-" else "${Fmt.decimal(m.auc)} ±${Fmt.decimal(m.aucSe)}",
                color = when { z >= 2 -> Color(0xFF13A38B); z < 0 -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface },
                modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium,
            )
            Text("${(m.weight * 100).toInt()}%", modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodyMedium)
        }
        SmallLabel(m.description + if (!m.expectedReturn.isNaN()) " 예상수익률 ${Fmt.signedRate(m.expectedReturn * 100)}." else "")
    }
}

@Composable
private fun PatternCard(m: PatternMatch, h: AiHorizon) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(m.asset.name.ifBlank { m.asset.code }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("유사도 ${(m.similarity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(10.dp))
        Text("이후 ${Fmt.signedRate(m.forwardReturn * 100)}", color = PriceColors.color(m.forwardReturn), fontWeight = FontWeight.SemiBold)
    }
    CandleChart(
        m.candles, patternIndicators, m.asset.market.currency, mainHeight = 150.dp, initialBars = m.candles.size,
        shadeFrom = m.splitIndex + 1, showExtremes = false,
    )
    SmallLabel("비교 구간 ${Fmt.date(m.candles.first().time)} ~ ${Fmt.date(m.candles[m.splitIndex].time)}, 그 뒤 ${h.period}")
}

/** 발굴 → AI 순위 */
@Composable
fun AiRankTab(vm: AppViewModel) {
    val s = vm.aiRank
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            MarketChips(s.market, { vm.updateAiRank(s.copy(market = it, rows = emptyList(), reliability = null)) }, Modifier.padding(top = 8.dp))
            ChipRow(AiHorizon.entries, { it == s.horizon }, { "${it.label} (${it.period})" }, Modifier.padding(top = 4.dp)) { vm.updateAiRank(s.copy(horizon = it, rows = emptyList(), reliability = null)) }
            SectionCard("AI 종목 순위") {
                Text("대표 ${s.size}종목을 같은 AI 모델로 점수 매겨, 앞으로 ${s.horizon.period} 동안 오를 확률과 예상 수익률이 높은 순서로 보여 줍니다.", style = MaterialTheme.typography.bodySmall)
                SmallLabel("머신러닝 주가 연구에서 가장 성과가 확인된 쓰임새가 이렇게 여러 종목을 서로 비교해 순위를 매기는 것입니다.")
                ChipRow(listOf(20, 40, 80), { it == s.size }, { "${it}종목" }, Modifier.padding(vertical = 4.dp)) { vm.updateAiRank(s.copy(size = it)) }
                Button(onClick = { vm.runAiRank() }, enabled = !s.running) { Text(if (s.running) "계산 중…" else "AI 순위 계산") }
            }
        }
        if (s.running) item { Loading(s.progress.ifBlank { "준비 중" }) }
        s.error?.let { item { ErrorNotice(it) { vm.runAiRank() } } }
        s.reliability?.let { rel ->
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("검증 신뢰도 ")
                    Pill(rel.label, reliabilityColor(rel))
                    Spacer(Modifier.width(8.dp))
                    SmallLabel("최고 AUC ${s.models.maxOfOrNull { it.auc }?.let { Fmt.decimal(it) } ?: "-"} · 유효 검증 ${s.effectiveSamples}건")
                }
            }
        }
        itemsIndexed(s.rows, key = { _, r -> r.asset.key }) { i, r ->
            Row(Modifier.fillMaxWidth().clickable { vm.openAsset(r.asset) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.asset.name.ifBlank { r.asset.code }, fontWeight = FontWeight.SemiBold)
                    SmallLabel("${r.asset.shortCode} · ${Fmt.price(r.price, r.asset.market.currency)}")
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("상승 ${Fmt.decimal(r.upProb * 100)}%", fontWeight = FontWeight.SemiBold, color = if (r.upProb >= 0.5) PriceColors.up else PriceColors.down)
                    Text("예상 ${Fmt.signedRate(r.expectedReturn * 100)}", style = MaterialTheme.typography.bodySmall, color = PriceColors.color(r.expectedReturn))
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (s.rows.isNotEmpty()) item { SmallLabel("순위는 상대 비교입니다. 신뢰도가 낮으면 순위 차이도 우연일 수 있습니다. 투자 판단의 책임은 본인에게 있습니다.", Modifier.padding(16.dp)) }
        if (s.market == Market.US && !vm.keys.hasKis && s.rows.isEmpty() && !s.running) item {
            Notice("해외 종목을 여러 개 받으려면 한국투자증권 키가 좋습니다. Twelve Data 무료 키는 분당 8회 제한이 있어 종목 수가 줄어듭니다.")
        }
    }
}
