package io.github.jgg113.gyeot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.calendar.CalendarWriter
import io.github.jgg113.gyeot.calls.CallRecordingImporter
import io.github.jgg113.gyeot.data.ModelManager
import io.github.jgg113.gyeot.data.Settings as AppSettings
import io.github.jgg113.gyeot.data.Utterance
import io.github.jgg113.gyeot.voice.Speaker
import io.github.jgg113.gyeot.voice.VoiceCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/** 고를 것이 몇 개 안 되는 설정은 칩 한 줄로. */
@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) }, border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String) =
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) =
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val activity = LocalContext.current as MainActivity
    val g = remember { Graph.get(activity) }
    val s = g.settings
    val scope = rememberCoroutineScope()
    var sttKey by remember { mutableStateOf(s.sttApiKey) }
    var sttBase by remember { mutableStateOf(s.sttBaseUrl) }
    var sttModel by remember { mutableStateOf(s.sttModel) }
    var claudeKey by remember { mutableStateOf(s.anthropicApiKey) }
    var claudeModel by remember { mutableStateOf(s.claudeModel) }
    var myName by remember { mutableStateOf(s.myName) }
    var vocab by remember { mutableStateOf(s.vocabulary) }
    var remind by remember { mutableStateOf(s.reminderMinutes.toString()) }
    var digest by remember { mutableStateOf(s.digestHour.toString()) }
    var keep by remember { mutableStateOf(s.retentionDays.toString()) }
    var keepAudio by remember { mutableStateOf(s.keepAudioDays) }
    var audioMb by remember { mutableStateOf(-1.0) }
    var addMode by remember { mutableStateOf(s.addMode) }
    var briefing by remember { mutableStateOf(s.briefingHour.toString()) }
    var importCalls by remember { mutableStateOf(s.importCalls && g.calls.hasPermission()) }
    var callRemind by remember { mutableStateOf(s.callForReminders) }
    var callBrief by remember { mutableStateOf(s.callForBriefing) }
    var callScan by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf(s.detailLevel) }
    var fullScreenOk by remember { mutableStateOf(VoiceCall.canFullScreen(activity)) }
    var own by remember { mutableStateOf(s.ownReminders) }
    var calendars by remember { mutableStateOf(emptyList<CalendarWriter.CalendarInfo>()) }
    var calId by remember { mutableStateOf(s.calendarId) }
    var test by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(activity.ignoringBattery()) }
    var sttEngine by remember { mutableStateOf(s.sttEngine) }
    var brainEngine by remember { mutableStateOf(s.brainEngine) }
    var useLlm by remember { mutableStateOf(s.useLocalLlm) }
    val asrProgress by g.models.progress(g.models.asr).collectAsState()
    val qwenProgress by g.models.progress(g.models.qwen3).collectAsState()
    val dolphinProgress by g.models.progress(g.models.dolphin).collectAsState()
    val llmProgress by g.models.progress(g.models.llm).collectAsState()
    var localModel by remember { mutableStateOf(s.localModel) }
    var corrections by remember { mutableStateOf(s.corrections) }
    var cloudBoost by remember { mutableStateOf(s.cloudBoost) }
    var localBoost by remember { mutableStateOf(s.localBoost) }
    // 목소리 등록 창이 닫히면 다시 읽어 '등록됨' 표시를 새로 고친다.
    val enrolled = remember(MainActivity.openTool.value) { g.voiceId.enrolled }

    LaunchedEffect(Unit) { calendars = withContext(Dispatchers.IO) { g.calendar.writableCalendars() } }
    LaunchedEffect(keepAudio) { audioMb = withContext(Dispatchers.IO) { g.audio.sizeMb() } }

    fun save() {
        s.sttApiKey = sttKey; s.sttBaseUrl = sttBase; s.sttModel = sttModel
        s.anthropicApiKey = claudeKey; s.claudeModel = claudeModel
        s.myName = myName; s.vocabulary = vocab
        remind.toIntOrNull()?.let { s.reminderMinutes = it }
        digest.toIntOrNull()?.let { s.digestHour = it; g.alarms.scheduleDigest(s.digestHour) }
        keep.toIntOrNull()?.let { s.retentionDays = it }
        s.addMode = addMode; s.ownReminders = own; s.calendarId = calId
        briefing.toIntOrNull()?.let { s.briefingHour = it; g.alarms.scheduleBriefing(s.briefingHour) }
        s.sttEngine = sttEngine; s.brainEngine = brainEngine; s.useLocalLlm = useLlm
        s.corrections = corrections
    }
    // 저장 버튼을 깜빡해도, 설정 탭을 떠날 때 입력한 값을 저장한다.
    DisposableEffect(Unit) { onDispose { save() } }

    Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenHeader("설정", subtitle = "클로드비서 · 수빈이")

        // ---- 내 목소리 (가장 자주 찾는 것: 받아쓰기 시험·목소리 등록) ----
        SettingGroup("🙋", "내 목소리 · 받아쓰기 시험", if (enrolled) "목소리 등록됨 ✓ · 받아쓰기가 잘 되는지 시험해 보세요" else "받아쓰기를 시험하고, 내 목소리를 등록해요", startOpen = true) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction("🎤", "받아쓰기 시험\n(6초 말해 보기)", Modifier.weight(1f), MaterialTheme.colorScheme.primaryContainer) { MainActivity.openTool.value = MainActivity.TOOL_DICTATION }
                QuickAction("🙋", if (enrolled) "내 목소리\n등록됨 ✓" else "내 목소리\n등록하기", Modifier.weight(1f), MaterialTheme.colorScheme.primaryContainer) { MainActivity.openTool.value = MainActivity.TOOL_VOICE }
            }
            Hint("목소리를 등록하면 받아쓴 말마다 🙋 나 / 👤 상대 가 붙고, 내가 한 약속은 더 믿고 TV·남의 말은 덜 믿어요.")
        }

        // ---- 받아쓰기 ----
        SettingGroup("🎙", "받아쓰기 (음성 인식)", "지금: " + when {
            sttEngine != AppSettings.ENGINE_LOCAL -> "클라우드"
            g.localTranscriber.engine() == AppSettings.MODEL_QWEN3 -> "고정밀 모델 · 휴대폰 안"
            g.localTranscriber.engine() == AppSettings.MODEL_ZIPFORMER -> "예전 모델 — 새 모델을 받아 주세요"
            else -> "기본 모델 · 휴대폰 안" + if (g.models.installed(g.models.qwen3) && localBoost) " + 중요한 말 고정밀" else ""
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(sttEngine == AppSettings.ENGINE_LOCAL, "휴대폰 안 (무료·무제한)") { sttEngine = AppSettings.ENGINE_LOCAL; s.sttEngine = sttEngine }
                Choice(sttEngine == AppSettings.ENGINE_CLOUD, "클라우드 API") { sttEngine = AppSettings.ENGINE_CLOUD; s.sttEngine = sttEngine }
            }
            if (sttEngine == AppSettings.ENGINE_LOCAL) {
                Hint("인터넷 없이 휴대폰 안에서 한국어를 받아써요. 작은 목소리는 자동으로 키워서 받아써요.")
                Label("받아쓰기 모델")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(localModel != AppSettings.MODEL_QWEN3, "기본 (105MB)") { localModel = AppSettings.MODEL_DOLPHIN; s.localModel = localModel; g.localTranscriber.close() }
                    Choice(localModel == AppSettings.MODEL_QWEN3, "고정밀 (약 1GB)") { localModel = AppSettings.MODEL_QWEN3; s.localModel = localModel; g.localTranscriber.close() }
                }
                if (localModel != AppSettings.MODEL_QWEN3) {
                    Hint("Dolphin (무료 공개 모델). 한국어 평가 음성에서 예전 모델보다 오류가 절반이고, 시끄럽고 먼 소리에 훨씬 강해요. 빠르고 가벼워요.")
                    ModelRow(g.models.installed(g.models.dolphin) && !dolphinProgress.running, dolphinProgress, g.models.dolphin.label, onGet = { g.models.download(g.models.dolphin); g.models.download(g.models.vad) }, onDelete = { g.localTranscriber.close(); g.models.delete(g.models.dolphin) })
                    Hint("더 정확하게: 고정밀 모델도 받아 두면 일정·약속 같은 중요한 말만 고정밀로 한 번 더 받아써요 (휴대폰 안, 무료).")
                    ModelRow(g.models.installed(g.models.qwen3) && !qwenProgress.running, qwenProgress, "고정밀 모델 (약 1GB, 와이파이)", onGet = { g.models.download(g.models.qwen3) }, onDelete = { g.localTranscriber.close(); g.models.delete(g.models.qwen3) })
                    if (g.models.installed(g.models.qwen3)) ToggleRow("중요한 말은 고정밀로 한 번 더", null, localBoost) { localBoost = it; s.localBoost = it }
                } else {
                    Hint("Qwen3-ASR (무료 공개 모델). 아래 '자주 나오는 이름'을 힌트로 써서 이름을 더 잘 알아들어요. 시간이 더 걸리고 메모리를 1.5GB 안팎 써요 (갤럭시 S·폴드급 권장). 받기 전까지는 기본 모델로 받아써요.")
                    ModelRow(g.models.installed(g.models.qwen3) && !qwenProgress.running, qwenProgress, g.models.qwen3.label, onGet = { g.models.download(g.models.qwen3); g.models.download(g.models.vad) }, onDelete = { g.localTranscriber.close(); g.models.delete(g.models.qwen3) })
                }
                if (g.models.installed(g.models.asr) && !asrProgress.running && (g.models.installed(g.models.dolphin) || g.models.installed(g.models.qwen3))) {
                    TextButton(onClick = { g.localTranscriber.close(); g.models.delete(g.models.asr) }) { Text("예전 모델(85MB) 지우기") }
                }
                Label("자꾸 틀리는 말 고치기")
                OutlinedTextField(corrections, { corrections = it }, Modifier.fillMaxWidth(), label = { Text("한 줄에 하나, 틀린말=바른말 (예: 민스=민수)") }, minLines = 2)
                Hint("기록 탭에서 받아쓴 줄을 길게 눌러 고쳐도 여기에 자동으로 더해져요.")
                ToggleRow("중요한 말만 클라우드로 한 번 더", "Groq 무료 키 필요, 나머지는 휴대폰 안", cloudBoost) { cloudBoost = it; s.cloudBoost = it }
                if (cloudBoost) SecretField(sttKey, { sttKey = it }, "Groq API 키 (console.groq.com, 무료)")
            } else {
                Hint("OpenAI 호환 받아쓰기 API. Groq(무료 한도): console.groq.com 에서 키. OpenAI: 주소 https://api.openai.com/v1, 모델 gpt-4o-transcribe.")
                SecretField(sttKey, { sttKey = it }, "음성 인식 API 키")
                OutlinedTextField(sttBase, { sttBase = it }, Modifier.fillMaxWidth(), label = { Text("주소") }, singleLine = true)
                OutlinedTextField(sttModel, { sttModel = it }, Modifier.fillMaxWidth(), label = { Text("모델") }, singleLine = true)
            }
        }

        // ---- 정리 ----
        SettingGroup("🧠", "정리 · 일정 찾기", if (brainEngine == AppSettings.ENGINE_LOCAL) "휴대폰 안 한국어 규칙 (무료)" else "Claude") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(brainEngine == AppSettings.ENGINE_LOCAL, "휴대폰 안 (무료)") { brainEngine = AppSettings.ENGINE_LOCAL; s.brainEngine = brainEngine }
                Choice(brainEngine == AppSettings.ENGINE_CLAUDE, "Claude (유료, 더 똑똑함)") { brainEngine = AppSettings.ENGINE_CLAUDE; s.brainEngine = brainEngine }
            }
            if (brainEngine == AppSettings.ENGINE_LOCAL) {
                Hint("일정·할 일·중요한 숫자는 한국어 규칙으로 바로 찾아요 (\"내일 오후 세 시\", \"다음 주 화요일\", \"삼십 분 뒤\" 등).")
                ToggleRow("휴대폰 안 요약 AI (실험)", "작은 모델이라 문장이 어색하거나 틀릴 수 있어요", useLlm) { useLlm = it; s.useLocalLlm = it }
                if (useLlm) ModelRow(g.models.installed(g.models.llm) && !llmProgress.running, llmProgress, g.models.llm.label, onGet = { g.models.download(g.models.llm) }, onDelete = { g.models.delete(g.models.llm) })
            } else {
                Hint("console.anthropic.com → API Keys 에서 키를 받아 넣으세요. 기본 모델 claude-opus-5-5, 싸게 쓰려면 claude-haiku-5-5.")
                SecretField(claudeKey, { claudeKey = it }, "Claude API 키")
                OutlinedTextField(claudeModel, { claudeModel = it }, Modifier.fillMaxWidth(), label = { Text("Claude 모델") }, singleLine = true)
            }
            OutlinedTextField(myName, { myName = it }, Modifier.fillMaxWidth(), label = { Text("내 이름·호칭 (예: 김지훈, 지훈 씨, 과장님)") }, singleLine = true)
            OutlinedTextField(vocab, { vocab = it }, Modifier.fillMaxWidth(), label = { Text("자주 나오는 이름 (쉼표로: 사람, 회사, 장소)") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(); test = "저장했어요" }) { Text("저장") }
                OutlinedButton(onClick = {
                    save()
                    test = "확인 중…"
                    scope.launch { test = withContext(Dispatchers.IO) { connectionTest(g) } }
                }) { Text("작동 확인") }
            }
            if (test.isNotBlank()) Text(test, style = MaterialTheme.typography.bodyMedium)
        }

        // ---- 캘린더 ----
        SettingGroup("📅", "캘린더 · 알림", when (addMode) {
            EventRules.MODE_ASK -> "모든 일정을 먼저 물어봐요"
            EventRules.MODE_AUTO -> "웬만하면 바로 넣어요"
            else -> "확실한 건 바로, 애매하면 물어봐요"
        }) {
            if (!g.calendar.hasPermission()) {
                OutlinedButton(onClick = { activity.requestAll { scope.launch { calendars = withContext(Dispatchers.IO) { g.calendar.writableCalendars() } } } }) { Text("캘린더·알림 권한 허용") }
            } else if (calendars.isEmpty()) {
                Text("쓸 수 있는 캘린더가 없어요. 삼성 캘린더 앱에서 삼성 계정을 연결해 주세요.", color = MaterialTheme.colorScheme.error)
            } else {
                Label("일정을 넣을 캘린더")
                Hint("삼성 계정을 고르면 삼성 캘린더·갤럭시 기기에 동기화돼요.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    calendars.forEach { c -> Choice(calId == c.id || (calId == -1L && c == calendars.first()), c.label) { calId = c.id; s.calendarId = c.id } }
                }
            }
            Label("대화에서 일정 같은 말을 들었을 때")
            listOf(
                EventRules.MODE_SMART to "알아서 — 확실한 약속은 바로, 애매하면 \"추가할까요?\"",
                EventRules.MODE_ASK to "늘 묻기 — 모든 일정을 먼저 물어보기 (TV를 자주 켠다면 추천)",
                EventRules.MODE_AUTO to "바로 넣기 — 웬만하면 묻지 않고 넣기",
            ).forEach { (mode, label) ->
                Choice(addMode == mode, label) { addMode = mode; s.addMode = mode }
            }
            ToggleRow("앱이 직접도 알리기", "캘린더 알림과 별도로 미리 + 정각에", own) { own = it; s.ownReminders = it }
            NumberField(remind, { remind = it }, "미리 알림 (분 전)")
            NumberField(briefing, { briefing = it }, "아침 일정 브리핑 시각 (0~23시, -1 끔)")
            NumberField(digest, { digest = it }, "저녁 하루 요약 시각 (0~23시, -1 끔)")
        }

        // ---- 수빈이 전화 ----
        SettingGroup("📞", "수빈이 전화 · 목소리", if (detail == AppSettings.DETAIL_BRIEF) "간단히 말해요" else "자세히 말해요") {
            Hint("수빈이가 수신 전화처럼 벨을 울리고, 받으면 할 일을 음성으로 읽어 줘요. 진짜 통화 중일 때는 방해하지 않고 보통 알림으로 대신해요.")
            ToggleRow("일정 미리 알림을 전화로", null, callRemind) { callRemind = it; s.callForReminders = it }
            ToggleRow("아침 브리핑을 전화로", null, callBrief) { callBrief = it; s.callForBriefing = it }
            if (!fullScreenOk) {
                Text("화면이 꺼져 있을 때 전화 화면을 띄우려면 '전체 화면 알림'을 허용해 주세요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { activity.openFullScreenSettings(); fullScreenOk = VoiceCall.canFullScreen(activity) }) { Text("전체 화면 알림 허용") }
            }
            Label("말 길이 (알림·브리핑·전화)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(detail == AppSettings.DETAIL_BRIEF, "간단히") { detail = AppSettings.DETAIL_BRIEF; s.detailLevel = detail }
                Choice(detail == AppSettings.DETAIL_FULL, "자세히") { detail = AppSettings.DETAIL_FULL; s.detailLevel = detail }
            }
            Hint(
                if (detail == AppSettings.DETAIL_BRIEF) "예) \"수빈이에요! 30분 뒤 오후 3시, 민수 약속이에요.\""
                else "예) \"안녕하세요, 수빈이에요! 30분 뒤 오후 3시에 강남역에서 민수 약속 일정이 있어요. 전에 '…'라고 하셨거든요.\" (브리핑은 내일 일정까지)",
            )
            Hint("통화 중에도 \"자세히 말해 줘\" / \"짧게\" 라고 하면 그때만 바꿔 읽어요.")
            VoiceSettings(g)
            OutlinedButton(onClick = { VoiceCall.ring(activity, VoiceCall.KIND_BRIEFING, "시험 전화 · 오늘 일정", "") }) { Text("지금 시험 전화 받아 보기") }
        }

        // ---- 통화 녹음 ----
        SettingGroup("☎️", "통화 녹음 정리", if (importCalls) "켜짐 · 통화가 끝나면 받아써서 정리해요" else "꺼짐") {
            Hint(
                "통화 중에는 안드로이드가 다른 앱의 마이크를 막아서 수빈이가 직접 못 들어요. 삼성 전화 앱의 '통화 자동 녹음'을 켜 두면, 통화가 끝나 녹음 파일이 저장될 때 " +
                    "수빈이가 그 파일을 받아써서 요약·일정·할 일을 정리해요 (원본 파일은 읽기만 해요). 기록 탭에서 📞 통화로 보여요.",
            )
            ToggleRow("통화 녹음 자동으로 정리", null, importCalls) { on ->
                if (!on) { importCalls = false; s.importCalls = false } else activity.request(listOf(CallRecordingImporter.permission())) {
                    val ok = g.calls.hasPermission()
                    if (ok && !s.importCalls) g.calls.startFrom(System.currentTimeMillis() - 24L * 60 * 60 * 1000) // 처음 켜면 최근 하루치부터
                    s.importCalls = ok; importCalls = ok
                    if (!ok) callScan = "음악과 오디오(파일) 권한을 허용해야 통화 녹음을 읽을 수 있어요."
                }
            }
            if (importCalls) OutlinedButton(onClick = {
                callScan = "통화 녹음 찾는 중… (긴 통화는 몇 분 걸려요)"
                scope.launch { callScan = withContext(Dispatchers.IO) { runCatching { g.calls.scan() }.fold({ "새 통화 녹음 ${it}개 정리했어요" }, { "실패: ${it.message}" }) } }
            }) { Text("지금 새 통화 녹음 정리") }
            if (callScan.isNotBlank()) Hint(callScan)
        }

        // ---- 녹음 보관·개인정보 ----
        SettingGroup("🎧", "녹음 보관 · 개인정보", if (keepAudio > 0) "녹음 최근 ${keepAudio}일 보관" + if (audioMb >= 0) " · ${"%.0f".format(audioMb)}MB" else "" else "녹음 보관 꺼짐") {
            Label("말소리 녹음 보관")
            Hint("받아쓴 말마다 그때 소리를 휴대폰 안에 저장해, 기록 탭에서 다시 들을 수 있어요. 조용한 시간은 저장하지 않아 하루 대화 3시간이면 40MB 안팎이에요.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 3, 7, 14).forEach { d ->
                    Choice(keepAudio == d, if (d == 0) "보관 안 함" else "${d}일") {
                        keepAudio = d; s.keepAudioDays = d
                        scope.launch(Dispatchers.IO) { g.audio.purge(d) }
                    }
                }
            }
            if (audioMb >= 0) Hint("지금 보관 중인 녹음: ${"%.1f".format(audioMb)}MB")
            NumberField(keep, { keep = it }, "받아쓴 글·요약 보관 일수")
            AppCard(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    "• 녹음·받아쓴 글·요약·목소리 특징은 이 휴대폰에만 저장돼요. 휴대폰 안 받아쓰기를 쓰면 소리가 밖으로 나가지 않아요.\n" +
                        "• 클라우드 받아쓰기나 Claude 정리를 켠 경우에만 그 말소리·글을 해당 서비스로 보내요.\n" +
                        "• 수빈이가 스피커로 한 말은 받아쓰지 않고 버려요.\n" +
                        "• 대한민국 통신비밀보호법상 내가 참여한 대화는 녹음·기록할 수 있지만, 내가 빠진 다른 사람들끼리의 대화를 몰래 녹음하면 불법이에요. " +
                        "자리를 비울 때는 [잠시 멈춤]을 눌러 주세요 (빠른 설정 타일 '수빈이 듣기'로도 돼요).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = { confirmClear = true }) { Text("녹음·받아쓴 글·요약 모두 지우기", color = MaterialTheme.colorScheme.error) }
        }

        // ---- 배터리 ----
        SettingGroup("🔋", "24시간 듣기 (삼성 갤럭시)", if (battery) "배터리 최적화 제외됨 ✓" else "배터리 최적화를 꺼 주세요") {
            Hint(
                "갤럭시는 배터리 절약을 위해 오래 켜진 앱을 재워요. '배터리 최적화 제외'를 허용하고, " +
                    "설정 → 배터리 → 백그라운드 사용 제한 → '절전 예외 앱'에 클로드비서를 넣어 주세요. 듣는 동안에는 상단에 알림이 늘 떠 있어요.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !battery, onClick = { activity.askBatteryExemption(); battery = activity.ignoringBattery() }) {
                    Text(if (battery) "최적화 제외됨" else "배터리 최적화 제외")
                }
                OutlinedButton(onClick = { activity.openAppSettings() }) { Text("앱 설정 열기") }
            }
        }

        val version = remember { runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull().orEmpty() }
        Text("클로드비서 $version", style = TextStyles.caption, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("모두 지울까요?") },
        text = { Text("보관한 녹음, 받아쓴 글, 요약을 지웁니다. 캘린더에 넣은 일정과 등록한 내 목소리는 그대로 둡니다.") },
        confirmButton = {
            TextButton(onClick = {
                g.store.clearAll()
                scope.launch(Dispatchers.IO) { g.audio.purge(0); audioMb = g.audio.sizeMb() }
                confirmClear = false
            }) { Text("지우기") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("그만두기") } },
    )
}

/** 수빈이 목소리 고르기: 휴대폰에 있는 한국어 목소리 목록 + 높이·빠르기 + 미리 듣기. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceSettings(g: Graph) {
    val ctx = LocalContext.current
    val s = g.settings
    var ready by remember { mutableStateOf(false) }
    val speaker = remember { Speaker(ctx, s) { ok -> ready = ok } }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    var voice by remember { mutableStateOf(s.ttsVoice) }
    var pitch by remember { mutableStateOf(s.ttsPitch) }
    var rate by remember { mutableStateOf(s.ttsRate) }
    val voices = remember(ready) { if (ready) speaker.voices() else emptyList() }
    fun preview() { speaker.reload(); speaker.speak("안녕하세요! 수빈이예요. 이 목소리로 알려 드릴게요. 오늘도 좋은 하루 보내세요!") }

    Label("수빈이 목소리")
    if (!ready) Hint("한국어 음성 엔진을 불러오는 중이거나 없어요. 휴대폰 설정 → 일반 → 텍스트 음성 변환에서 삼성 또는 Google 엔진의 한국어 음성을 받아 주세요.")
    else {
        Hint("밝고 자연스러운 목소리를 고르세요. '아주 좋음'이 가장 사람 같아요. 더 많은 목소리는 Google 음성 서비스 앱에서 받을 수 있어요.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice(voice.isEmpty(), "자동 (가장 좋은 목소리)") { voice = ""; s.ttsVoice = ""; preview() }
            voices.take(8).forEachIndexed { i, v ->
                Choice(voice == v.name, Speaker.label(v, i)) { voice = v.name; s.ttsVoice = v.name; preview() }
            }
        }
    }
    Hint("목소리 높이 ${"%.2f".format(pitch)} (높을수록 밝고 명랑하게)")
    Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.8f..1.5f, onValueChangeFinished = { s.ttsPitch = pitch; preview() })
    Hint("말 빠르기 ${"%.2f".format(rate)}")
    Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.7f..1.4f, onValueChangeFinished = { s.ttsRate = rate; preview() })
    OutlinedButton(enabled = ready, onClick = { preview() }) { Text("▶ 목소리 미리 듣기") }
}

@Composable
private fun ModelRow(installed: Boolean, p: ModelManager.Progress, label: String, onGet: () -> Unit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            when {
                p.running -> Text("${p.percent}%", color = MaterialTheme.colorScheme.primary)
                installed -> { Tag("✓ 받음", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer); TextButton(onClick = onDelete) { Text("지우기") } }
                else -> Button(onClick = onGet) { Text("받기") }
            }
        }
        if (p.running) LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth())
        if (p.error.isNotBlank()) Text(p.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/** 받아쓰기(무음 1초)와 정리(시험 문장)를 한 번씩 돌려 본다. */
private fun connectionTest(g: Graph): String {
    val stt = runCatching {
        g.transcribe(ShortArray(16_000))
        "받아쓰기 ✓"
    }.getOrElse { "받아쓰기 ✗ ${it.message}" }
    val claude = runCatching {
        val now = ZonedDateTime.now(EventRules.SEOUL)
        val a = g.brain.analyze(emptyList(), listOf(Utterance(System.currentTimeMillis(), "내일 오후 세 시에 강남역에서 민수랑 만나기로 했어")), emptyList(), now)
        val e = a.events.firstOrNull()
        (if (g.settings.localBrain) "정리 ✓" else "Claude ✓") + if (e != null) " — 시험 문장에서 찾은 일정: ${e.title} ${e.start.replace('T', ' ')} (캘린더에는 넣지 않았어요)" else ""
    }.getOrElse { "정리 ✗ ${it.message}" }
    return "$stt\n$claude"
}
