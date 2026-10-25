package io.github.jgg113.gyeot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.audio.ListenState
import io.github.jgg113.gyeot.audio.ListeningService
import io.github.jgg113.gyeot.audio.Mic
import io.github.jgg113.gyeot.audio.SpeechSegmenter
import io.github.jgg113.gyeot.data.Settings as AppSettings
import io.github.jgg113.gyeot.stt.DictationTest
import io.github.jgg113.gyeot.voice.VoiceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 시험·등록하는 동안에는 24시간 듣기를 잠시 멈춘다 (마이크를 한쪽만 쓰도록). */
private suspend fun <T> withListeningPaused(activity: MainActivity, block: suspend () -> T): T {
    val was = ListenState.state.value.running && !ListenState.state.value.paused
    if (was) {
        ListeningService.pause(activity, true)
        delay(400)
    }
    try {
        return block()
    } finally {
        if (was) ListeningService.pause(activity, false)
    }
}

/** 몇 초 녹음하면서 소리 크기와 남은 시간을 알려 준다. */
private fun recordWithMeter(seconds: Int, onLevel: (Int) -> Unit, onLeft: (Int) -> Unit): ShortArray {
    val start = System.currentTimeMillis()
    return Mic.record(seconds.toDouble()) { db ->
        onLevel(db)
        onLeft((seconds - (System.currentTimeMillis() - start) / 1000).toInt().coerceAtLeast(0))
    }
}

@Composable
private fun ToolDialog(close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun Recorder(recording: Boolean, level: Int, left: Int) {
    if (!recording) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(MaterialTheme.colorScheme.error, pulsing = true)
        Text("  듣는 중… ${left}초", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
    }
    LevelBar(level)
}

private fun modelName(m: String) = when (m) {
    AppSettings.MODEL_QWEN3 -> "고정밀 모델"
    AppSettings.MODEL_ZIPFORMER -> "예전 모델"
    "cloud" -> "클라우드"
    else -> "기본 모델"
}

/**
 * 받아쓰기 시험: 6초 동안 말하면 지금 모델이 어떻게 받아쓰는지, 소리가 얼마나 크게 들렸는지,
 * (목소리를 등록했다면) 내 목소리로 알아봤는지 보여 준다.
 */
@Composable
fun DictationTestDialog(close: () -> Unit) {
    val activity = LocalContext.current as MainActivity
    val g = remember { Graph.get(activity) }
    val scope = rememberCoroutineScope()
    var phase by remember { mutableIntStateOf(0) } // 0 준비, 1 녹음, 2 받아쓰는 중, 3 결과
    var level by remember { mutableIntStateOf(-90) }
    var left by remember { mutableIntStateOf(6) }
    var result by remember { mutableStateOf<DictationTest.Result?>(null) }
    var error by remember { mutableStateOf("") }
    val notReady = remember { g.notReadyReason()?.takeIf { g.settings.localStt && !g.localTranscriber.available } }

    fun start() = activity.withMic {
        phase = 1; error = ""; result = null; left = 6
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    withListeningPaused(activity) {
                        val pcm = recordWithMeter(6, { level = it }, { left = it })
                        phase = 2
                        DictationTest.check(g, pcm)
                    }
                }
            }
            r.onSuccess { result = it }.onFailure { error = "시험 실패: ${it.message}" }
            phase = 3
        }
    }

    ToolDialog(close = { if (phase != 1 && phase != 2) close() }) {
        Text("🎤 받아쓰기 시험", style = MaterialTheme.typography.titleLarge)
        if (notReady != null) {
            Text(notReady, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = close, modifier = Modifier.align(Alignment.End)) { Text("닫기") }
            return@ToolDialog
        }
        if (phase <= 1) {
            Hint("[시작]을 누르고 6초 동안 평소처럼 말해 보세요. 휴대폰을 평소 두는 곳(책상·주머니)에 두고 해 보면 실제로 얼마나 잘 듣는지 알 수 있어요.")
            AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
                Text("예) 이렇게 읽어 보세요", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("“내일 오후 세 시에 강남역에서 민수랑 만나기로 했어.”", style = MaterialTheme.typography.titleMedium)
            }
            Recorder(phase == 1, level, left)
            if (phase == 0) Button(onClick = ::start, modifier = Modifier.fillMaxWidth()) { Text("시작") }
        }
        if (phase == 2) {
            Text("받아쓰는 중…", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (phase == 3) {
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            result?.let { r ->
                AppCard {
                    Text("받아쓴 글", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(r.text.ifBlank { "(아무것도 못 알아들었어요)" }, style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 28.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag(modelName(r.model))
                        Tag("${"%.1f".format(r.seconds)}초 걸림")
                        Tag("${r.levelDb}dB" + if (r.gain > 1.5) " · ${"%.0f".format(r.gain)}배 키움" else "")
                    }
                }
                AppCard {
                    Text("소리 크기", style = MaterialTheme.typography.labelLarge)
                    LevelBar(r.levelDb)
                    Text(DictationTest.levelAdvice(r.levelDb), style = MaterialTheme.typography.bodyMedium)
                    val v = r.voice
                    if (v != null) {
                        Text("🙋 등록한 내 목소리와 ${(v * 100).toInt()}% 비슷해요 — " + when (g.voiceId.label(v)) {
                            "me" -> "나로 알아봐요 ✓"
                            "other" -> "다른 사람으로 봤어요 (목소리를 다시 등록해 보세요)"
                            else -> "애매해요 (조금 더 등록하면 좋아져요)"
                        }, style = MaterialTheme.typography.bodyMedium)
                    } else if (!g.voiceId.enrolled) {
                        TextButton(onClick = { MainActivity.openTool.value = MainActivity.TOOL_VOICE }) { Text("🙋 내 목소리를 등록하면 누가 말했는지도 구분해요 ›") }
                    }
                }
                Hint("잘못 들은 낱말은 기록 탭에서 그 줄을 길게 눌러 고치면, 다음부터 자동으로 바로잡아요." +
                    if (r.model != AppSettings.MODEL_QWEN3 && !g.models.installed(g.models.qwen3)) " 더 정확하게 하려면 설정 → 받아쓰기에서 고정밀 모델을 받아 보세요." else "")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::start, modifier = Modifier.weight(1f)) { Text("다시 해 보기") }
                OutlinedButton(onClick = close) { Text("닫기") }
            }
        }
    }
}

private val SENTENCES = listOf(
    "안녕 수빈아, 오늘 날씨가 참 좋아서 기분이 좋아.",
    "나는 아침에 커피를 마시고 천천히 하루를 시작해.",
    "주말에는 가족이랑 공원에서 산책하는 걸 좋아해.",
)

/**
 * 내 목소리 등록: 문장 3개를 읽으면 목소리 특징을 휴대폰에 저장한다.
 * 그 뒤로 받아쓴 말마다 🙋 나 / 👤 상대 를 붙이고, 내가 한 말의 일정은 더 믿고 TV·남의 말은 덜 믿는다.
 */
@Composable
fun VoiceEnrollDialog(close: () -> Unit) {
    val activity = LocalContext.current as MainActivity
    val g = remember { Graph.get(activity) }
    val scope = rememberCoroutineScope()
    val progress by g.models.progress(g.models.speaker).collectAsState()
    val modelReady = g.voiceId.modelReady && !progress.running
    var step by remember { mutableIntStateOf(0) } // 0 안내, 1 문장 읽기, 2 저장 중, 3 확인
    var append by remember { mutableStateOf(false) }
    val recordings = remember { mutableListOf<ShortArray>() }
    var index by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableIntStateOf(-90) }
    var left by remember { mutableIntStateOf(6) }
    var message by remember { mutableStateOf("") }
    var testScore by remember { mutableFloatStateOf(-1f) }
    var enrolled by remember { mutableStateOf(g.voiceId.enrolled) }
    var samples by remember { mutableIntStateOf(g.voiceId.samples) }

    fun begin(more: Boolean) {
        append = more; recordings.clear(); index = 0; message = ""; step = 1
    }

    fun readSentence() = activity.withMic {
        recording = true; message = ""; left = 6
        scope.launch {
            val pcm = withContext(Dispatchers.IO) {
                runCatching { withListeningPaused(activity) { recordWithMeter(6, { level = it }, { left = it }) } }.getOrNull()
            }
            recording = false
            if (pcm == null) { message = "마이크를 열 수 없어요. 잠시 뒤 다시 해 주세요."; return@launch }
            val voice = Mic.trimSilence(pcm)
            val db = Mic.speechLevelDb(pcm)
            when {
                db < -58 -> message = "너무 작게 들렸어요 (${db}dB). 휴대폰을 입에서 한 뼘 정도 거리에 두고 다시 읽어 주세요."
                voice.size < SpeechSegmenter.SAMPLE_RATE * 1.5 -> message = "말소리가 너무 짧게 들렸어요. 문장을 끝까지 읽어 주세요."
                else -> {
                    recordings += voice
                    if (index < SENTENCES.size - 1) index++ else {
                        step = 2
                        val n = withContext(Dispatchers.IO) { runCatching { g.voiceId.enroll(recordings.toList(), append) }.getOrDefault(0) }
                        enrolled = g.voiceId.enrolled; samples = g.voiceId.samples
                        if (n == 0) { message = "목소리를 저장하지 못했어요. 다시 해 주세요."; step = 0 } else { testScore = -1f; step = 3 }
                    }
                }
            }
        }
    }

    fun test() = activity.withMic {
        recording = true; message = ""; left = 5
        scope.launch {
            val score = withContext(Dispatchers.IO) {
                runCatching { withListeningPaused(activity) { g.voiceId.score(Mic.trimSilence(recordWithMeter(5, { level = it }, { left = it }))) } }.getOrNull()
            }
            recording = false
            if (score == null) message = "말소리가 너무 짧았어요. 조금 더 길게 말해 주세요." else testScore = score
        }
    }

    ToolDialog(close = { if (!recording && step != 2) close() }) {
        Text("🙋 내 목소리 등록", style = MaterialTheme.typography.titleLarge)
        when (step) {
            0 -> {
                Text(
                    "문장 3개를 소리 내어 읽어 주시면 수빈이가 목소리를 기억해 둬요. 그다음부터 받아쓴 말마다 🙋 나 / 👤 상대 를 표시하고, " +
                        "내가 직접 한 말의 약속은 더 믿고 TV·다른 사람 말은 덜 믿어요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Hint("목소리 특징(숫자 192개)만 이 휴대폰에 저장하고, 어디에도 보내지 않아요. 조용한 곳에서 평소 말투로 읽어 주세요.")
                if (!modelReady) AppCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("먼저 목소리 알아보기 모델(28MB)을 받아야 해요", style = MaterialTheme.typography.titleMedium)
                    if (progress.running) {
                        LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Hint("${progress.percent}% 받는 중")
                    } else Button(onClick = { g.models.download(g.models.speaker) }) { Text(if (progress.error.isNotBlank()) "다시 받기" else "받기 (무료)") }
                    if (progress.error.isNotBlank()) Text(progress.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                if (enrolled) {
                    AppCard(color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text("✓ 등록돼 있어요 (문장 ${samples}개)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Hint("더 읽어 두면 시끄러운 곳·멀리서도 더 잘 알아봐요.")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = modelReady, onClick = { testScore = -1f; step = 3 }, modifier = Modifier.weight(1f)) { Text("확인해 보기") }
                        OutlinedButton(enabled = modelReady, onClick = { begin(more = true) }, modifier = Modifier.weight(1f)) { Text("더 읽기") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = modelReady, onClick = { begin(more = false) }) { Text("처음부터 다시") }
                        TextButton(onClick = { g.voiceId.forget(); enrolled = false; samples = 0 }) { Text("등록 지우기", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = close) { Text("닫기") }
                    }
                } else {
                    Button(enabled = modelReady, onClick = { begin(more = false) }, modifier = Modifier.fillMaxWidth()) { Text("시작하기") }
                    TextButton(onClick = close, modifier = Modifier.align(Alignment.End)) { Text("나중에") }
                }
            }
            1 -> {
                Text("문장 ${index + 1} / ${SENTENCES.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(progress = { index / SENTENCES.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                AppCard(color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("“${SENTENCES[index]}”", style = MaterialTheme.typography.titleLarge.copy(lineHeight = 30.sp), fontWeight = FontWeight.SemiBold)
                }
                Recorder(recording, level, left)
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (!recording) {
                    Button(onClick = ::readSentence, modifier = Modifier.fillMaxWidth()) { Text("🎤 읽기 시작 (6초)") }
                    TextButton(onClick = { step = 0 }, modifier = Modifier.align(Alignment.End)) { Text("그만두기") }
                }
            }
            2 -> {
                Text("목소리를 기억하는 중…", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            else -> {
                if (append || recordings.isNotEmpty()) Text("✓ 등록했어요! (문장 ${samples}개)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
                Text("확인: [말해 보기]를 누르고 5초 동안 아무 말이나 해 보세요. 다른 사람이 말해 보면 낮게 나와야 해요.", style = MaterialTheme.typography.bodyMedium)
                Recorder(recording, level, left)
                if (testScore >= 0) AppCard(color = if (g.voiceId.label(testScore) == "me") MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface) {
                    Text("${(testScore * 100).toInt()}% 비슷해요", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        when (g.voiceId.label(testScore)) {
                            "me" -> "🙋 나로 알아봐요"
                            "other" -> "👤 다른 사람으로 봐요"
                            else -> "애매해요 — [더 읽기]로 문장을 더 등록하면 좋아져요"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Hint("${(VoiceId.ME * 100).toInt()}% 이상이면 나, ${(VoiceId.OTHER * 100).toInt()}% 아래면 다른 사람으로 봐요.")
                }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (!recording) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::test, modifier = Modifier.weight(1f)) { Text("🎤 말해 보기") }
                    OutlinedButton(onClick = { begin(more = true) }) { Text("더 읽기") }
                    TextButton(onClick = close) { Text("완료") }
                }
            }
        }
    }
}
