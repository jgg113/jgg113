package io.github.jgg113.stocknote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jgg113.stocknote.core.AxisScore
import io.github.jgg113.stocknote.core.Currency
import io.github.jgg113.stocknote.core.DividendInfo
import io.github.jgg113.stocknote.core.FearGreed
import io.github.jgg113.stocknote.core.Fmt
import io.github.jgg113.stocknote.core.InvestorFlow
import io.github.jgg113.stocknote.core.Kimchi
import io.github.jgg113.stocknote.core.Market
import io.github.jgg113.stocknote.core.MarketMood
import io.github.jgg113.stocknote.core.MarketMoodCalc
import io.github.jgg113.stocknote.core.NewsItem
import io.github.jgg113.stocknote.core.NewsSentiment
import io.github.jgg113.stocknote.core.OwnershipReport
import io.github.jgg113.stocknote.core.Quote
import io.github.jgg113.stocknote.core.ScoreAxis
import io.github.jgg113.stocknote.core.StockScore
import io.github.jgg113.stocknote.core.StockScorer
import io.github.jgg113.stocknote.core.Treemap
import io.github.jgg113.stocknote.ui.theme.PriceColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Good = Color(0xFF43A047)
private val Warn = Color(0xFFF2A007)
private val Bad = Color(0xFFE5323B)

/** 0~100 점수의 색: 낮으면 빨강, 가운데 주황, 높으면 초록 */
fun scoreColor(score: Int): Color = when {
    score >= 65 -> Good
    score >= 45 -> Warn
    else -> Bad
}

// ───────────────────────── 종합 탭 (종목 화면) ─────────────────────────

/** 종목 화면의 [종합] 탭: 여러 분석을 한 장으로 모은 점수와 그 이유, 수급·투자의견, 김치 프리미엄 */
@Composable
fun SummaryTab(vm: AppViewModel, onOpenAi: () -> Unit) {
    val d = vm.detail ?: return
    val asset = d.asset
    LaunchedEffect(asset.key) { vm.loadSummary() }
    val aiResults = vm.ai.values.mapNotNull { it.result }
    val score = remember(d.daily.size, d.report, aiResults.size, d.financials, d.flows.size, d.quote?.marketCap) {
        if (d.daily.size < 60) null else StockScorer.combine(
            listOf(
                StockScorer.trend(d.daily, d.quote), StockScorer.signal(d.report), StockScorer.ai(aiResults),
                StockScorer.safety(d.daily, asset.market), StockScorer.value(d.quote, d.financials), StockScorer.flow(d.flows),
            ),
        )
    }
    if (score == null) {
        if (!d.loading) Notice("종합 점수에는 일봉이 3개월 넘게(60개 이상) 있어야 합니다. 지금 ${d.daily.size}개입니다.")
        return
    }
    SectionCard("종합 점수") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${score.total}", fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, color = scoreColor(score.total))
                    Text(" / 100", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
                }
                Pill(score.grade, scoreColor(score.total))
                Spacer(Modifier.height(6.dp))
                SmallLabel("자료가 있는 ${score.axes.size}개 항목을 모은 요약입니다.")
            }
            RadarChart(score.axes, Modifier.padding(start = 8.dp).size(176.dp))
        }
        SmallLabel("규칙으로 계산한 요약 점수입니다. 점수가 높다고 오른다는 뜻은 아니며, 아래 이유를 함께 보세요.")
    }
    if (score.strengths.isNotEmpty() || score.weaknesses.isNotEmpty()) SectionCard("점수를 올린 이유 · 내린 이유") {
        score.strengths.forEach { ReasonLine(it, true) }
        if (score.strengths.isNotEmpty() && score.weaknesses.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
        score.weaknesses.forEach { ReasonLine(it, false) }
    }
    SectionCard("항목별 점수") {
        ScoreAxis.entries.forEach { axis ->
            val a = score.axis(axis)
            if (a != null) AxisRow(a) else MissingAxisRow(vm, axis, onOpenAi)
        }
    }
    if (asset.market == Market.KR) {
        FlowCard(vm)
        OpinionCard(vm)
    }
    if (asset.market == Market.CRYPTO) d.premium?.let { p ->
        SectionCard("김치 프리미엄") {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.signedRate(p.premiumPct), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = PriceColors.color(p.premiumPct))
                Text("  국내가 해외보다 ${if (p.premiumPct >= 0) "비쌈" else "쌈"}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
            InfoRow("업비트", Fmt.priceWithUnit(p.krwPrice, Currency.KRW))
            InfoRow("해외 (${d.premiumSource})", "$" + Fmt.decimal(p.usdPrice) + (vm.usdKrw?.let { " ≈ ${Fmt.priceWithUnit(p.usdPrice * it, Currency.KRW)}" } ?: ""))
            SmallLabel("해외 가격은 달러 테더(USDT) 기준이며 환율을 곱해 견줍니다. 프리미엄이 크면 국내에서 과열됐다는 신호로 봅니다.")
        }
    }
}

@Composable
private fun ReasonLine(text: String, positive: Boolean) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(if (positive) "▲" else "▼", color = if (positive) Good else Bad, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp, top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AxisRow(a: AxisScore) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.axis.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(84.dp))
            ScoreBar(a.score.toDouble(), Modifier.weight(1f), scoreColor(a.score))
            Text("${a.score}", fontWeight = FontWeight.Bold, color = scoreColor(a.score), modifier = Modifier.width(36.dp).padding(start = 8.dp))
        }
        SmallLabel(a.axis.help)
        a.reasons.forEach { r -> Text((if (r.positive) "＋ " else "－ ") + r.text, style = MaterialTheme.typography.bodySmall, color = if (r.positive) Good else Bad) }
    }
}

@Composable
private fun MissingAxisRow(vm: AppViewModel, axis: ScoreAxis, onOpenAi: () -> Unit) {
    val d = vm.detail ?: return
    val kr = d.asset.market == Market.KR
    val why = when (axis) {
        ScoreAxis.AI -> if (vm.ai.values.any { it.loading }) "AI 모델을 학습하는 중입니다…" else "AI 예측을 아직 돌리지 않았습니다."
        ScoreAxis.VALUE -> when {
            !kr -> "재무 자료는 국내 종목만 됩니다."
            !vm.keys.hasDart -> "[설정]에서 DART 인증키(무료)를 넣으면 계산됩니다."
            d.financialsError != null -> d.financialsError
            else -> "재무 자료를 받는 중입니다…"
        }
        ScoreAxis.FLOW -> when {
            !kr -> "수급 자료는 국내 종목만 됩니다."
            !vm.keys.hasKis -> "[설정]에서 한국투자증권 앱키(무료)를 넣으면 계산됩니다."
            d.flowsError != null -> d.flowsError
            d.extraLoading -> "수급 자료를 받는 중입니다…"
            else -> "수급 자료가 없습니다."
        }
        ScoreAxis.SIGNAL -> if (d.analyzing) "전략을 계산하는 중입니다…" else "전략 신호가 없습니다."
        else -> "자료가 부족합니다."
    }
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(axis.label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SmallLabel(why)
        }
        if (axis == ScoreAxis.AI && vm.ai.values.none { it.loading }) TextButton(onClick = { vm.loadAi(); onOpenAi() }) { Text("AI 예측 보기") }
    }
}

/** 항목별 점수를 거미줄 그림으로 (바깥일수록 높은 점수) */
@Composable
fun RadarChart(axes: List<AxisScore>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primary
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier) {
        val n = axes.size
        if (n < 3) {
            // 항목이 둘이면 막대로 그린다.
            axes.forEachIndexed { i, a ->
                val y = size.height * (0.3f + i * 0.3f)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 14f, StrokeCap.Round)
                drawLine(scoreColor(a.score), Offset(0f, y), Offset(size.width * a.score / 100f, y), 14f, StrokeCap.Round)
                drawText(measurer, a.axis.label, Offset(0f, y - 30f), labelStyle)
            }
            return@Canvas
        }
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 - 44f
        fun point(i: Int, k: Float): Offset {
            val ang = -Math.PI / 2 + 2 * Math.PI * i / n
            return Offset(c.x + (r * k * cos(ang)).toFloat(), c.y + (r * k * sin(ang)).toFloat())
        }
        for (level in 1..4) {
            val path = Path()
            for (i in 0 until n) point(i, level / 4f).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) }
            path.close()
            drawPath(path, grid, style = Stroke(1.5f))
        }
        for (i in 0 until n) drawLine(grid, c, point(i, 1f), 1.5f)
        val shape = Path()
        axes.forEachIndexed { i, a -> point(i, (a.score / 100f).coerceIn(0.04f, 1f)).let { if (i == 0) shape.moveTo(it.x, it.y) else shape.lineTo(it.x, it.y) } }
        shape.close()
        drawPath(shape, fill.copy(alpha = 0.28f))
        drawPath(shape, fill, style = Stroke(4f))
        axes.forEachIndexed { i, a ->
            val text = measurer.measure(a.axis.label, labelStyle)
            val p = point(i, 1f)
            val dx = (p.x - c.x) / r
            val dy = (p.y - c.y) / r
            // 글자가 그림 밖으로 나가 옆 글과 겹치지 않게 안쪽으로 당긴다.
            val x = (p.x - text.size.width / 2f + dx * (text.size.width / 2f + 4f)).coerceIn(0f, (size.width - text.size.width).coerceAtLeast(0f))
            val y = (p.y - text.size.height / 2f + dy * (text.size.height / 2f + 6f)).coerceIn(0f, (size.height - text.size.height).coerceAtLeast(0f))
            drawText(text, topLeft = Offset(x, y))
        }
    }
}

// ───────────────────────── 수급·투자의견 (국내) ─────────────────────────

@Composable
private fun FlowCard(vm: AppViewModel) {
    val d = vm.detail ?: return
    SectionCard("수급 (외국인·기관·개인)") {
        when {
            !vm.keys.hasKis -> SmallLabel("외국인·기관이 사는지 파는지는 한국투자증권 앱키(무료)를 넣으면 볼 수 있습니다. [설정]에서 넣으세요.")
            d.extraLoading -> SmallLabel("수급 자료를 받는 중입니다…")
            d.flowsError != null -> SmallLabel("수급 자료를 받지 못했습니다: ${d.flowsError}")
            d.flows.isEmpty() -> SmallLabel("수급 자료가 없습니다.")
            else -> {
                val recent = d.flows.takeLast(20)
                fun sum(days: Int, pick: (InvestorFlow) -> Double) = recent.takeLast(days).sumOf { pick(it) * it.close }
                Row(Modifier.fillMaxWidth()) {
                    Text("", Modifier.weight(1f))
                    listOf("최근 5일", "최근 ${recent.size}일").forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f)) }
                }
                listOf<Pair<String, (InvestorFlow) -> Double>>("외국인" to { it.foreign }, "기관" to { it.institution }, "개인" to { it.individual }).forEach { (name, pick) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        listOf(5, recent.size).forEach { days ->
                            val v = sum(days, pick)
                            Text((if (v >= 0) "+" else "-") + Fmt.big(abs(v)), color = PriceColors.color(v), modifier = Modifier.weight(1.2f))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                SmallLabel("외국인 + 기관 하루 순매수 (최근 ${recent.size}거래일, 위로 = 순매수)")
                FlowBars(recent.map { (it.foreign + it.institution) * it.close })
                SmallLabel("순매수 수량에 그날 종가를 곱한 어림 금액입니다. 한국투자증권 자료.")
            }
        }
    }
}

@Composable
private fun FlowBars(values: List<Double>) {
    val up = PriceColors.up
    val down = PriceColors.down
    val axis = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(90.dp).padding(vertical = 4.dp)) {
        val max = values.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: return@Canvas
        val mid = size.height / 2
        val w = size.width / values.size
        drawLine(axis, Offset(0f, mid), Offset(size.width, mid), 1.5f)
        values.forEachIndexed { i, v ->
            val h = (abs(v) / max * (mid - 2f)).toFloat()
            drawRect(if (v >= 0) up else down, Offset(i * w + w * 0.15f, if (v >= 0) mid - h else mid), Size(w * 0.7f, h))
        }
    }
}

@Composable
private fun OpinionCard(vm: AppViewModel) {
    val d = vm.detail ?: return
    if (!vm.keys.hasKis || d.opinions.isEmpty()) return
    val cur = d.asset.market.currency
    val price = d.quote?.price ?: 0.0
    SectionCard("증권사 투자의견 (최근 6개월)") {
        val targets = d.opinions.map { it.target }.filter { it > 0 }
        if (targets.isNotEmpty()) {
            val avg = targets.average()
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.priceWithUnit(avg, cur), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (price > 0) Text("  지금보다 ${Fmt.signedRate((avg / price - 1) * 100)}", color = PriceColors.color(avg - price), modifier = Modifier.padding(bottom = 3.dp))
            }
            SmallLabel("목표가 평균 (${targets.size}건, 최저 ${Fmt.price(targets.min(), cur)} ~ 최고 ${Fmt.price(targets.max(), cur)})")
        }
        val counts = d.opinions.groupingBy { it.opinion.ifBlank { "의견 없음" } }.eachCount().entries.sortedByDescending { it.value }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            counts.take(4).forEach { (name, n) -> Pill("$name $n", MaterialTheme.colorScheme.primary) }
        }
        d.opinions.take(8).forEach { o ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(Fmt.date(o.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
                Text(o.broker.ifBlank { "-" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(o.opinion + if (o.previous.isNotBlank() && o.previous != o.opinion) " (전 ${o.previous})" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(if (o.target > 0) Fmt.price(o.target, cur) else "-", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
        SmallLabel("증권사 보고서의 의견·목표가입니다 (한국투자증권 자료). 목표가는 대체로 낙관적으로 나오는 편입니다.")
    }
}

// ───────────────────────── 지분·배당 (재무 탭) ─────────────────────────

@Composable
fun DividendCard(div: DividendInfo, price: Double) {
    SectionCard("배당 (${div.year}년 사업보고서)") {
        div.perShare?.let { InfoRow("주당 배당금", "${Fmt.number(it)}원") }
        (div.yieldPct ?: div.perShare?.takeIf { price > 0 }?.let { it / price * 100 })?.let { InfoRow("배당수익률", "${Fmt.decimal(it)}%") }
        div.payoutPct?.let { InfoRow("배당성향", "${Fmt.decimal(it)}% (순이익 중 배당으로 준 비율)") }
        val history = listOfNotNull(div.prev2PerShare?.let { "${div.year - 2}년 ${Fmt.number(it)}원" }, div.prevPerShare?.let { "${div.year - 1}년 ${Fmt.number(it)}원" }, div.perShare?.let { "${div.year}년 ${Fmt.number(it)}원" })
        if (history.size > 1) SmallLabel("주당 배당금 흐름: " + history.joinToString(" → "))
    }
}

@Composable
fun OwnershipCard(list: List<OwnershipReport>, error: String?) {
    val context = LocalContext.current
    SectionCard("큰손·임원 지분 변동 (DART)") {
        if (error != null) { SmallLabel("받지 못했습니다: $error"); return@SectionCard }
        if (list.isEmpty()) { SmallLabel("최근 보고가 없습니다."); return@SectionCard }
        list.take(10).forEach { o ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).pointerInput(o.link) { detectTapGestures { openUrl(context, o.link) } }) {
                Row {
                    Text(o.reporter, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    val ch = o.change
                    Text(if (ch == 0.0) "변동 없음" else (if (ch > 0) "+" else "-") + Fmt.volume(abs(ch)) + "주", color = PriceColors.color(ch), fontWeight = FontWeight.SemiBold)
                }
                SmallLabel(listOfNotNull(Fmt.date(o.date), o.role, o.ratePct?.let { "지분 ${Fmt.decimal(it)}%" }, o.reason.takeIf { it.isNotBlank() }).joinToString(" · "))
            }
        }
        SmallLabel("5% 넘게 가진 주주와 임원이 낸 보고입니다. 임원이 자기 회사 주식을 사면 좋게, 팔면 나쁘게 보는 경우가 많습니다. 누르면 공시 원문이 열립니다.")
    }
}

// ───────────────────────── 뉴스 분위기 (이슈 탭) ─────────────────────────

@Composable
fun SentimentCard(items: List<NewsItem>) {
    val s = remember(items) { NewsSentiment.of(items) } ?: return
    val color = if (s.score >= 10) Good else if (s.score <= -10) Bad else Warn
    SectionCard("뉴스 분위기") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.weight(1f))
            Text("긍정 ${s.positive} · 부정 ${s.negative} · 중립 ${s.neutral}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().height(10.dp).padding(top = 4.dp).clip(RoundedCornerShape(5.dp))) {
            if (s.positive > 0) Box(Modifier.weight(s.positive.toFloat()).height(10.dp).background(Good))
            if (s.neutral > 0) Box(Modifier.weight(s.neutral.toFloat()).height(10.dp).background(MaterialTheme.colorScheme.outlineVariant))
            if (s.negative > 0) Box(Modifier.weight(s.negative.toFloat()).height(10.dp).background(Bad))
        }
        Spacer(Modifier.height(4.dp))
        SmallLabel("기사 제목 ${items.size}건에 나온 낱말(급등·호실적 / 급락·적자 등)을 센 것입니다. 맥락은 읽지 못하니 위의 AI 이슈 요약과 함께 보세요.")
    }
}

// ───────────────────────── 시장 심리·히트맵·AI 브리핑 (시장 요약) ─────────────────────────

/** 반원 게이지: 왼쪽 공포(파랑) → 오른쪽 탐욕(빨강) */
@Composable
fun MoodGauge(score: Int, label: String, modifier: Modifier = Modifier) {
    val colors = listOf(Color(0xFF1E64E6), Color(0xFF7FA6F2), Color(0xFFD5D8DE), Color(0xFFF5A08A), Color(0xFFE5323B))
    val needle = MaterialTheme.colorScheme.onSurface
    Box(modifier.size(170.dp, 104.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.size(170.dp, 96.dp)) {
            val stroke = 22f
            val d = size.width - stroke
            colors.forEachIndexed { i, c -> drawArc(c, 180f + i * 36f + 1f, 34f, false, Offset(stroke / 2, stroke / 2), Size(d, d), style = Stroke(stroke)) }
            val ang = Math.toRadians(180.0 + score.coerceIn(0, 100) / 100.0 * 180)
            val center = Offset(size.width / 2, stroke / 2 + d / 2)
            val tip = Offset(center.x + ((d / 2 - 6f) * cos(ang)).toFloat(), center.y + ((d / 2 - 6f) * sin(ang)).toFloat())
            drawLine(needle, center, tip, 6f, StrokeCap.Round)
            drawCircle(needle, 9f, center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 16.dp)) {
            Text("$score", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        }
    }
}

@Composable
fun MoodCard(mood: MarketMood, fearGreed: FearGreed?, market: Market) {
    SectionCard("시장 심리") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoodGauge(mood.score, mood.label)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(mood.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (mood.score > 55) PriceColors.up else if (mood.score < 45) PriceColors.down else MaterialTheme.colorScheme.onSurface)
                SmallLabel("0 극단적 공포 ↔ 100 극단적 탐욕")
                if (fearGreed != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("세계 코인 공포·탐욕 ${fearGreed.value} (${fearGreed.label})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    if (fearGreed.history.size >= 7) SmallLabel("7일 전 ${fearGreed.history[fearGreed.history.size - 7]} · 30일 전 ${fearGreed.history.first()}")
                }
            }
        }
        mood.parts.forEach { p ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.3f))
                ScoreBar(p.score.toDouble(), Modifier.weight(1f), if (p.score > 55) PriceColors.up else if (p.score < 45) PriceColors.down else MaterialTheme.colorScheme.outline)
                Text(p.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.2f).padding(start = 8.dp))
            }
        }
        SmallLabel(
            "오늘 ${market.label} 시세로 앱이 계산한 값입니다. 모두가 겁낼 때(공포)는 싸게 살 기회, 모두가 들떴을 때(탐욕)는 조심할 때로 보는 지표입니다." +
                if (fearGreed != null) " 세계 지수는 alternative.me 자료." else "",
        )
    }
}

@Composable
fun KimchiCard(vm: AppViewModel) {
    val h = vm.home
    val median = Kimchi.median(h.kimchi) ?: return
    SectionCard("김치 프리미엄") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Fmt.signedRate(median), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = PriceColors.color(median))
            Text("  거래 많은 코인 ${h.kimchi.size}개의 가운데 값", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
        }
        val sorted = h.kimchi.sortedByDescending { it.premiumPct }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                SmallLabel("프리미엄이 큰 코인")
                sorted.take(3).forEach { p -> Row(Modifier.pointerInput(p.asset.key) { detectTapGestures { vm.openAsset(p.asset) } }.padding(vertical = 2.dp)) { Text(p.asset.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f)); Text(Fmt.signedRate(p.premiumPct), style = MaterialTheme.typography.bodySmall, color = PriceColors.color(p.premiumPct)); Spacer(Modifier.width(12.dp)) } }
            }
            Column(Modifier.weight(1f)) {
                SmallLabel("프리미엄이 작은 코인")
                sorted.takeLast(3).reversed().forEach { p -> Row(Modifier.pointerInput(p.asset.key) { detectTapGestures { vm.openAsset(p.asset) } }.padding(vertical = 2.dp)) { Text(p.asset.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f)); Text(Fmt.signedRate(p.premiumPct), style = MaterialTheme.typography.bodySmall, color = PriceColors.color(p.premiumPct)) } }
            }
        }
        SmallLabel("업비트 원화 가격을 해외(${h.kimchiSource}) 달러 가격 × 환율과 견준 값입니다. 크게 벌어지면 국내 과열 신호로 봅니다.")
    }
}

/** 큰 종목일수록 넓은 칸, 색은 등락률 (한국식: 빨강 상승, 파랑 하락) */
@Composable
fun HeatmapCard(quotes: List<Quote>, market: Market, onClick: (Quote) -> Unit) {
    if (quotes.size < 4) return
    val byCap = quotes.any { it.marketCap > 0 }
    val measurer = rememberTextMeasurer()
    val up = PriceColors.up
    val down = PriceColors.down
    val neutral = if (MaterialTheme.colorScheme.surface.red < 0.5f) Color(0xFF3A3F4B) else Color(0xFFB8BEC9)
    val limit = if (market == Market.KR) 5.0 else if (market == Market.CRYPTO) 8.0 else 4.0
    SectionCard("히트맵") {
        Canvas(
            Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(8.dp)).pointerInput(quotes) {
                detectTapGestures { pos ->
                    val rects = Treemap.layout(quotes.map { if (byCap) it.marketCap else it.value }, size.width.toFloat(), size.height.toFloat())
                    rects.firstOrNull { pos.x >= it.x && pos.x <= it.x + it.w && pos.y >= it.y && pos.y <= it.y + it.h }?.let { onClick(quotes[it.index]) }
                }
            },
        ) {
            val rects = Treemap.layout(quotes.map { if (byCap) it.marketCap else it.value }, size.width, size.height)
            for (r in rects) {
                val q = quotes[r.index]
                val t = (abs(q.changeRate) / limit).coerceIn(0.0, 1.0).toFloat()
                val color = lerp(neutral, if (q.changeRate >= 0) up else down, 0.25f + 0.75f * t).let { if (q.changeRate == 0.0) neutral else it }
                drawRoundRect(color, Offset(r.x + 1.5f, r.y + 1.5f), Size((r.w - 3f).coerceAtLeast(0f), (r.h - 3f).coerceAtLeast(0f)), CornerRadius(6f))
                if (r.w > 70f && r.h > 44f) {
                    val big = r.w > 190f && r.h > 110f
                    val name = measurer.measure(
                        q.asset.name, TextStyle(color = Color.White, fontSize = if (big) 14.sp else 11.sp, fontWeight = FontWeight.Bold),
                        overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (r.w - 12f).roundToInt().coerceAtLeast(1)),
                    )
                    val rate = measurer.measure(Fmt.signedRate(q.changeRate), TextStyle(color = Color.White, fontSize = if (big) 13.sp else 10.sp))
                    val total = name.size.height + rate.size.height
                    if (total < r.h - 6f) {
                        drawText(name, topLeft = Offset(r.x + (r.w - name.size.width) / 2, r.y + (r.h - total) / 2))
                        if (rate.size.width < r.w - 8f) drawText(rate, topLeft = Offset(r.x + (r.w - rate.size.width) / 2, r.y + (r.h - total) / 2 + name.size.height))
                    }
                }
            }
        }
        SmallLabel("칸 넓이는 ${if (byCap) "시가총액" else "오늘 거래대금"}, 색은 오늘 등락률입니다 (빨강 상승 · 파랑 하락). 칸을 누르면 종목으로 갑니다.")
    }
}

@Composable
fun BriefCard(vm: AppViewModel) {
    val context = LocalContext.current
    val b = vm.brief
    SectionCard("AI 시장 브리핑") {
        when {
            b.loading -> SmallLabel("AI 가 오늘 시장을 정리하는 중입니다…")
            b.text != null -> Text(b.text, style = MaterialTheme.typography.bodyMedium)
            b.error != null -> Text(b.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else -> SmallLabel("위의 시장 심리·등락 종목·기사 제목을 AI 에게 넘겨 오늘 시장을 몇 문장으로 정리합니다. 숫자는 앱이 계산한 것만 넘깁니다.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = { vm.askBrief { p, text -> handoffToAi(context, p, text) } }, enabled = !b.loading) { Text(if (b.text != null) "다시 받기" else "브리핑 받기") }
            if (vm.aiProvider == io.github.jgg113.stocknote.core.AiProvider.NONE) TextButton(onClick = { vm.tab = Tab.SETTINGS }) { Text("AI 설정") }
        }
    }
}

// ───────────────────────── 포트폴리오 진단 (투자 화면) ─────────────────────────

@Composable
fun PortfolioDiagnosisCard(vm: AppViewModel, accountId: String, holdingCount: Int) {
    val context = LocalContext.current
    val st = vm.diagnoses[accountId]
    SectionCard("포트폴리오 진단") {
        val d = st?.result
        when {
            holdingCount == 0 -> { SmallLabel("종목을 담으면 쏠림·함께 움직이는 정도·변동성을 진단합니다."); return@SectionCard }
            st == null -> SmallLabel("보유 종목의 지난 1년 일봉으로 한 종목에 쏠리지 않았는지, 종목들이 같이 움직이는지, 얼마나 흔들리는지를 봅니다.")
            st.loading -> SmallLabel("보유 종목의 일봉을 받아 계산하는 중입니다…")
            st.error != null -> Text(st.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (d != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${d.score}", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = scoreColor(d.score))
                Column(Modifier.padding(start = 10.dp)) {
                    Pill(d.grade, scoreColor(d.score))
                    SmallLabel("분산·위험 점수 (100점 만점)")
                }
            }
            Spacer(Modifier.height(4.dp))
            d.findings.forEach { f ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(if (f.level == 2) "●" else if (f.level == 1) "●" else "●", color = if (f.level == 2) Bad else if (f.level == 1) Warn else Good, fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp, top = 4.dp))
                    Text(f.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            InfoRow("연 변동성", "${Fmt.decimal(d.volatilityPct)}%")
            InfoRow("최대 낙폭", "-${Fmt.decimal(d.maxDrawdownPct)}%")
            InfoRow("같은 비중 수익률", Fmt.signedRate(d.returnPct) + " (지난 ${d.days}거래일)", PriceColors.color(d.returnPct))
            if (!d.avgCorrelation.isNaN()) InfoRow("평균 상관", Fmt.decimal(d.avgCorrelation) + " (1에 가까울수록 같이 움직임)")
            InfoRow("고르게 환산", "${Fmt.decimal(d.effectiveCount)}종목과 같음")
            if (d.rows.size > 1) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("종목", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.6f))
                    listOf("비중", "흔들림 몫", "균형 비중").forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)) }
                }
                d.rows.take(10).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(r.asset.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.6f))
                        Text("${r.weightPct.roundToInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text("${r.riskSharePct.roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = if (r.riskSharePct - r.weightPct > 10) Bad else Color.Unspecified, modifier = Modifier.weight(1f))
                        Text("${r.balancedWeightPct.roundToInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
                SmallLabel("'흔들림 몫'은 전체 변동 중 그 종목이 만든 비율, '균형 비중'은 종목마다 흔들림이 같아지게 나눈 비중(참고용)입니다.")
            }
            when {
                st.ai.loading -> SmallLabel("AI 가 진단을 풀어 쓰는 중입니다…")
                st.ai.text != null -> { HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant); Text(st.ai.text, style = MaterialTheme.typography.bodyMedium) }
                st.ai.error != null -> Text(st.ai.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = { vm.diagnose(accountId) }, enabled = st?.loading != true) { Text(if (d != null) "다시 진단" else "진단하기") }
            if (d != null) OutlinedButton(onClick = { vm.askPortfolio(accountId) { p, text -> handoffToAi(context, p, text) } }, enabled = !st.ai.loading) { Text("AI 에게 조언 듣기") }
        }
        if (d != null) SmallLabel("지난 가격으로 계산한 참고값입니다. 앞으로도 같다는 뜻은 아닙니다.")
    }
}

@Suppress("unused")
private val keepImports = listOf<Any>(MarketMoodCalc, StockScore::class)
