package io.github.jgg113.stocknote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jgg113.stocknote.core.EvoLab
import io.github.jgg113.stocknote.core.EvolvedStrategy
import io.github.jgg113.stocknote.core.Fmt
import io.github.jgg113.stocknote.core.Market
import io.github.jgg113.stocknote.core.PeriodStats
import io.github.jgg113.stocknote.ui.theme.PriceColors

private val Pass = Color(0xFF43A047)

private fun count(n: Long): String = when {
    n >= 100_000_000 -> Fmt.decimal(n / 100_000_000.0) + "억"
    n >= 10_000 -> Fmt.decimal(n / 10_000.0) + "만"
    else -> Fmt.number(n.toDouble())
}

/** 발굴 → AI 연구소: 전략 조합을 진화시키며 찾고, 학습·검증·시험 기간 성적과 과최적화 확률을 함께 보여 준다. */
@Composable
fun LabTab(vm: AppViewModel) {
    val l = vm.lab
    val p = l.progress
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SectionCard("AI 전략 연구소") {
                Text(
                    "기본 전략 57개 가운데 2~4개를 골라 '몇 개가 매수 상태일 때 살지'와 '어떤 시장 조건에서만 살지'를 섞은 조합을 만들고, " +
                        "대표 종목들의 과거 일봉으로 모의투자해 성적이 좋은 것끼리 다시 섞는 일을 수십만 번 되풀이합니다 (진화 탐색).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                SmallLabel("기간을 학습(앞 60%)·검증(다음 20%)·시험(최근 20%)으로 나눠, 찾기는 학습, 고르기는 학습+검증으로 하고 시험 기간은 마지막 확인에만 씁니다. 많이 시험할수록 우연히 좋아 보이는 전략이 섞이므로 '시험 통과'와 '과최적화 확률'을 꼭 함께 보세요.")
                Spacer(Modifier.height(10.dp))
                Text("시장", style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf(Market.KR, Market.US, Market.CRYPTO), { it == l.market }, { it.label }) { vm.updateLab(l.copy(market = it)) }
                Text("종목 수", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                ChipRow(listOf(20, 40, 60, 100), { it == l.poolSize }, { "${it}종목" }) { vm.updateLab(l.copy(poolSize = it)) }
                Text("시험할 조합 수", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                ChipRow(listOf(50_000L, 200_000L, 1_000_000L), { it == l.target }, { count(it) + "개" }) { vm.updateLab(l.copy(target = it)) }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (l.running) OutlinedButton(onClick = { vm.stopLab() }) { Text("멈추기") }
                    else Button(onClick = { vm.startLab() }) { Text(if (p == null) "연구 시작" else "새로 연구") }
                }
                SmallLabel("앱이 켜져 있는 동안 돕니다. 국내·미국은 시세 키가 있어야 합니다. 비용(수수료·세금)은 [설정]의 값을 씁니다.", Modifier.padding(top = 6.dp))
            }
        }
        item {
            SectionCard("진행") {
                if (l.running) LinearProgressIndicator(progress = { ((p?.evaluations ?: 0) / l.target.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                if (l.status.isNotEmpty()) Text(l.status, style = MaterialTheme.typography.bodyMedium)
                l.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    BigCount("시험한 조합", count(p?.evaluations ?: 0), Modifier.weight(1f))
                    BigCount("모의투자 횟수", count(p?.simulations ?: 0), Modifier.weight(1f))
                    BigCount("지금까지 모의투자", count(vm.labTotals.second), Modifier.weight(1f))
                }
                if (l.periods.isNotEmpty()) SmallLabel(l.periods, Modifier.padding(top = 6.dp))
                if (p != null) {
                    Text("과최적화 확률 " + EvoLab.pboText(p.pbo), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp),
                        color = if (p.pbo.isNaN()) MaterialTheme.colorScheme.onSurface else if (p.pbo < 0.2) Pass else if (p.pbo < 0.5) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    SmallLabel("이번 탐색에서 상위로 뽑힌 전략들이, 뽑을 때 안 본 기간에서는 가운데보다 못할 확률입니다 (Bailey·López de Prado 의 CSCV 방식). 50% 근처면 고르는 것 자체가 운과 다를 바 없다는 뜻입니다.")
                }
            }
        }
        // 시험 기간까지 통과한 것을 앞에 둔다.
        val best = p?.best.orEmpty().sortedByDescending { it.passedTest }
        if (best.isNotEmpty()) {
            item { Text("이번 연구 상위 전략", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, top = 10.dp)) }
            items(best, key = { "run_" + it.id }) { e -> EvolvedCard(vm, e, saved = vm.evolved.any { it.id == e.id }) }
        }
        item { Text("내 AI 전략 (${vm.evolved.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, top = 14.dp)) }
        if (vm.evolved.isEmpty()) item { SmallLabel("연구 결과에서 [저장]을 누르면 여기에 모입니다. 저장한 전략은 지표분석·매매신호·알림에서도 쓸 수 있습니다.", Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
        items(vm.evolved, key = { "saved_" + it.id }) { e -> EvolvedCard(vm, e, saved = true) }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun BigCount(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        SmallLabel(label)
    }
}

@Composable
private fun EvolvedCard(vm: AppViewModel, e: EvolvedStrategy, saved: Boolean) {
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Pill(if (e.passedTest) "시험 통과" else "시험 미통과", if (e.passedTest) Pass else MaterialTheme.colorScheme.outline)
        }
        SmallLabel(e.genome.describe() + " · ${e.market.label} ${e.poolSize}종목 · 평균 ${e.avgHoldBars.toInt()}일 보유 (${e.horizon.label})", Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("", "연 수익", "보유만", "승률", "낙폭", "거래").forEachIndexed { i, h ->
                Text(h, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(if (i == 0) 0.9f else 1f))
            }
        }
        StatRow("학습", e.train)
        StatRow("검증", e.valid)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 2.dp))
        StatRow("시험", e.test, bold = true)
        Text(EvoLab.luckText(e.luckPercentile), style = MaterialTheme.typography.bodySmall, color = if (e.luckPercentile >= 90) Pass else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        SmallLabel(e.periods)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
            TextButton(onClick = { vm.openEvolved(e) }) { Text("차트로 보기") }
            if (!saved) TextButton(onClick = { vm.saveEvolved(e) }) { Text("저장") }
            TextButton(onClick = { vm.useEvolvedForSignals(e) }) { Text("매매신호에 쓰기") }
            if (saved && vm.evolved.any { it.id == e.id }) TextButton(onClick = { vm.deleteEvolved(e.id) }) { Text("삭제") }
        }
    }
}

@Composable
private fun StatRow(label: String, s: PeriodStats, bold: Boolean = false) {
    val w = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.9f))
        Text(Fmt.signedRate(s.annReturnPct), style = MaterialTheme.typography.bodySmall, fontWeight = w, color = PriceColors.color(s.annReturnPct), modifier = Modifier.weight(1f))
        Text(Fmt.signedRate(s.buyHoldAnnPct), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("${s.winRatePct.toInt()}%", style = MaterialTheme.typography.bodySmall, fontWeight = w, modifier = Modifier.weight(1f))
        Text("-${s.maxDrawdownPct.toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text("${s.trades}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Suppress("unused")
private val keep = Modifier.width(1.dp)
