package io.github.jgg113.gyeot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.voice.VoiceCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val EXAMPLES = listOf(
    "이번 주 일정 뭐 있어?",
    "아까 팀장님이 보고서 언제까지라고 했지?",
    "어제 통화에서 무슨 얘기 했어?",
    "오늘 할 일 정리해 줘",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen() {
    val activity = LocalContext.current as MainActivity
    val g = remember { Graph.get(activity) }
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var days by remember { mutableIntStateOf(3) }

    fun ask() {
        if (question.isBlank() || busy) return
        busy = true
        asked = question.trim()
        answer = ""
        scope.launch {
            answer = withContext(Dispatchers.IO) {
                runCatching { g.ask(asked, g.recentMaterial(days)) }.getOrElse { "⚠ ${it.message}" }
            }
            busy = false
        }
    }

    Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("수빈이에게 묻기", subtitle = "들은 대화·통화·캘린더를 근거로 답해요")
        AppCard {
            OutlinedTextField(
                question, { question = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("무엇이든 물어보세요") }, minLines = 2,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 3, 7).forEach { d ->
                    FilterChip(
                        selected = days == d, onClick = { days = d }, border = null,
                        label = { Text(if (d == 1) "오늘만" else "최근 ${d}일") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && question.isNotBlank(), onClick = ::ask, modifier = Modifier.weight(1f)) { Text(if (busy) "찾는 중…" else "물어보기") }
                OutlinedButton(onClick = { VoiceCall.chat(activity) }) { Text("📞 말로 묻기") }
            }
        }
        Text("이렇게 물어보세요", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXAMPLES.forEach { q ->
                AssistChip(
                    onClick = { question = q; ask() },
                    label = { Text(q) },
                    border = null,
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
        if (asked.isNotBlank()) {
            AppCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text("🙋 $asked", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            AppCard {
                Row {
                    EmojiBadge("💬", size = 34)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("수빈이", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(if (busy) "기록을 찾아보는 중이에요…" else answer, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
