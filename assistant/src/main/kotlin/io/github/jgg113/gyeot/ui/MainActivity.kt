package io.github.jgg113.gyeot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.ai.Analyzer
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.alarm.Notifier
import io.github.jgg113.gyeot.audio.ListenState
import io.github.jgg113.gyeot.audio.ListeningService
import io.github.jgg113.gyeot.calendar.CalendarWriter
import io.github.jgg113.gyeot.data.Note
import io.github.jgg113.gyeot.data.SavedEvent
import io.github.jgg113.gyeot.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val tab = mutableIntStateOf(0)
    private var afterPermissions: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermissions?.invoke()
        afterPermissions = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab.intValue = intent.getIntExtra(EXTRA_TAB, 0)
        val g = Graph.get(this)
        // 부팅 뒤 '다시 듣기' 알림을 눌러 들어온 경우 등: 켜 두었던 듣기를 이어 간다.
        if (g.settings.listeningWanted && !ListenState.state.value.running && hasMic()) runCatching { ListeningService.start(this) }
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF7FD1BC)) else lightColorScheme(primary = Color(0xFF2E7D6B))) {
                App(tab.intValue, onTab = { tab.intValue = it })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tab.intValue = intent.getIntExtra(EXTRA_TAB, tab.intValue)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun requestAll(then: () -> Unit) {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) then() else {
            afterPermissions = then
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    fun startListening() = requestAll {
        if (hasMic()) ListeningService.start(this)
    }

    fun ignoringBattery(): Boolean = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @android.annotation.SuppressLint("BatteryLife")
    fun askBatteryExemption() {
        runCatching {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    fun openAppSettings() {
        startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(EventRules.SEOUL).format(HM)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(tab: Int, onTab: (Int) -> Unit) {
    val titles = listOf("오늘", "기록", "묻기", "설정")
    Scaffold(
        topBar = { TopAppBar(title = { Text("곁비서 · ${titles[tab]}") }) },
        bottomBar = {
            NavigationBar {
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { onTab(i) }, icon = { Text(listOf("☀", "🗒", "?", "⚙")[i]) }, label = { Text(t) })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TodayScreen()
                1 -> HistoryScreen()
                2 -> AskScreen()
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun TodayScreen() {
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity
    val g = remember { Graph.get(activity) }
    val state by ListenState.state.collectAsState()
    val version by g.store.version.collectAsState()
    val events = remember(version) { g.store.upcomingEvents() }
    val today = LocalDate.now(EventRules.SEOUL)
    val notes = remember(version) { g.store.notes().filter { Store.dayOf(it.at) == today }.reversed() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(
                        when {
                            !state.running -> "듣기가 꺼져 있어요"
                            state.paused -> "잠시 멈춤"
                            state.hearing -> "말소리 듣는 중…"
                            else -> "듣는 중 · 조용함"
                        },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    )
                    if (state.lastHeardAt > 0) Text("마지막 ${clock(state.lastHeardAt)}: ${state.lastText}", maxLines = 2)
                    if (state.queued > 0) Text("받아쓰기 대기 ${state.queued}개")
                    if (state.error.isNotBlank()) Text("⚠ ${state.error}", color = MaterialTheme.colorScheme.error)
                    if (!g.settings.ready) Text("설정 탭에서 음성 인식 키와 Claude 키를 먼저 넣어 주세요.", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.running) Button(onClick = { activity.startListening() }) { Text("듣기 시작") }
                        else {
                            Button(onClick = { ListeningService.pause(activity, !state.paused) }) { Text(if (state.paused) "다시 듣기" else "잠시 멈춤") }
                            OutlinedButton(onClick = { ListeningService.analyzeNow(activity) }) { Text("지금 정리") }
                            TextButton(onClick = { ListeningService.stop(activity) }) { Text("끄기") }
                        }
                    }
                }
            }
        }
        item { SectionTitle("다가오는 일정 (${events.size})") }
        if (events.isEmpty()) item { Text("대화에서 찾은 일정이 여기에 쌓여요. 예) \"내일 3시에 치과 가야 돼\"", color = MaterialTheme.colorScheme.outline) }
        items(events, key = { it.id }) { e -> EventCard(e, g) }
        item { SectionTitle("오늘 들은 중요한 이야기 (${notes.size})") }
        if (notes.isEmpty()) item { Text("대화가 끝나면 요약·핵심·할 일이 여기에 정리돼요.", color = MaterialTheme.colorScheme.outline) }
        items(notes, key = { it.id }) { NoteCard(it) }
    }
}

@Composable
private fun EventCard(e: SavedEvent, g: Graph) {
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(e.title, fontWeight = FontWeight.Bold)
            Text(Notifier.describe(e) + if (e.location.isNotBlank()) " · ${e.location}" else "")
            if (e.evidence.isNotBlank()) Text("\"${e.evidence}\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        e.status == "pending" -> "확인 기다림"
                        e.calendarEventId != null -> "캘린더 등록됨 · ${e.reminderMinutes}분 전 알림"
                        else -> "앱에만 저장 (캘린더 권한 필요)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (e.status == "pending") TextButton(onClick = { scope.launch(Dispatchers.IO) { g.events.confirm(e.id) } }) { Text("등록") }
                TextButton(onClick = { scope.launch(Dispatchers.IO) { g.events.cancel(e.id) } }) { Text("취소") }
            }
        }
    }
}

@Composable
private fun NoteCard(n: Note) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (n.importance == "high") CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("${clock(n.from)}~${clock(n.at)}" + if (n.importance == "high") " · 중요" else "", style = MaterialTheme.typography.labelMedium)
            Text(n.summary)
            n.keyPoints.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            n.todos.forEach { Text("✔ ${it.text}" + if (it.due.isNotBlank()) " (${it.due.replace('T', ' ')})" else "", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun HistoryScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val g = remember { Graph.get(ctx) }
    val version by g.store.version.collectAsState()
    var day by remember { mutableStateOf(LocalDate.now(EventRules.SEOUL)) }
    val lines = remember(version, day) { g.store.utterances(day).reversed() }
    val notes = remember(version, day) { g.store.notes().filter { Store.dayOf(it.at) == day }.reversed() }
    var showNotes by remember { mutableStateOf(true) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { day = day.minusDays(1) }) { Text("◀") }
            Text(day.format(DateTimeFormatter.ofPattern("M월 d일 (E)", java.util.Locale.KOREAN)), fontWeight = FontWeight.Bold)
            TextButton(onClick = { if (day.isBefore(LocalDate.now(EventRules.SEOUL))) day = day.plusDays(1) }) { Text("▶") }
            Spacer(Modifier.weight(1f))
            FilterChip(selected = showNotes, onClick = { showNotes = !showNotes }, label = { Text("정리만") })
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (showNotes) {
                if (notes.isEmpty()) item { Text("이 날 정리된 대화가 없어요.", color = MaterialTheme.colorScheme.outline) }
                items(notes, key = { it.id }) { NoteCard(it) }
            } else {
                if (lines.isEmpty()) item { Text("이 날 받아쓴 말이 없어요.", color = MaterialTheme.colorScheme.outline) }
                items(lines) { u ->
                    Row {
                        Text(clock(u.at), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
                        Text(u.text)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AskScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val g = remember { Graph.get(ctx) }
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var days by remember { mutableIntStateOf(3) }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("들은 대화를 근거로 물어보세요. 예) \"아까 팀장님이 보고서 언제까지라고 했지?\", \"어제 들은 계좌번호 알려줘\"")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 3, 7).forEach { d -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text(if (d == 1) "오늘" else "${d}일") }) }
        }
        OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), label = { Text("질문") }, minLines = 2)
        Spacer(Modifier.height(8.dp))
        Button(enabled = !busy && question.isNotBlank(), onClick = {
            busy = true
            answer = ""
            scope.launch {
                answer = withContext(Dispatchers.IO) {
                    try {
                        g.analyzer.ask(question, material(g, days), ZonedDateTime.now(EventRules.SEOUL))
                    } catch (e: Analyzer.AnalyzeException) {
                        "⚠ ${e.message}"
                    }
                }
                busy = false
            }
        }) { Text(if (busy) "찾는 중…" else "물어보기") }
        if (answer.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) { Text(answer, Modifier.padding(12.dp)) }
        }
    }
}

/** 최근 며칠의 정리 노트 + 받아쓴 말 원문을 날짜·시각과 함께 이어 붙인다. */
private fun material(g: Graph, days: Int): String {
    val today = LocalDate.now(EventRules.SEOUL)
    val from = today.minusDays((days - 1).toLong())
    return buildString {
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.forEach { d ->
            val notes = g.store.notes().filter { Store.dayOf(it.at) == d }
            val lines = g.store.utterances(d)
            if (notes.isEmpty() && lines.isEmpty()) return@forEach
            appendLine("## $d")
            notes.forEach { n ->
                appendLine("[정리 ${clock(n.from)}~${clock(n.at)}] ${n.summary}")
                n.keyPoints.forEach { appendLine("  - $it") }
                n.todos.forEach { appendLine("  - 할 일: ${it.text} ${it.due}") }
            }
            lines.forEach { appendLine("[${clock(it.at)}] ${it.text}") }
        }
    }.ifBlank { "(기록 없음)" }
}

@Composable
private fun SettingsScreen() {
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity
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
    var auto by remember { mutableStateOf(s.autoRegister) }
    var own by remember { mutableStateOf(s.ownReminders) }
    var calendars by remember { mutableStateOf(emptyList<CalendarWriter.CalendarInfo>()) }
    var calId by remember { mutableStateOf(s.calendarId) }
    var test by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(activity.ignoringBattery()) }

    LaunchedEffect(Unit) { calendars = withContext(Dispatchers.IO) { g.calendar.writableCalendars() } }

    fun save() {
        s.sttApiKey = sttKey; s.sttBaseUrl = sttBase; s.sttModel = sttModel
        s.anthropicApiKey = claudeKey; s.claudeModel = claudeModel
        s.myName = myName; s.vocabulary = vocab
        remind.toIntOrNull()?.let { s.reminderMinutes = it }
        digest.toIntOrNull()?.let { s.digestHour = it; g.alarms.scheduleDigest(s.digestHour) }
        keep.toIntOrNull()?.let { s.retentionDays = it }
        s.autoRegister = auto; s.ownReminders = own; s.calendarId = calId
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("1. 받아쓰기 (음성 인식)")
        Text("기본은 Groq 의 Whisper(무료 한도). console.groq.com → API Keys 에서 키를 받아 넣으세요. OpenAI 를 쓰려면 주소 https://api.openai.com/v1, 모델 gpt-4o-transcribe.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(sttKey, { sttKey = it }, Modifier.fillMaxWidth(), label = { Text("음성 인식 API 키") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(sttBase, { sttBase = it }, Modifier.fillMaxWidth(), label = { Text("주소") }, singleLine = true)
        OutlinedTextField(sttModel, { sttModel = it }, Modifier.fillMaxWidth(), label = { Text("모델") }, singleLine = true)

        SectionTitle("2. 정리·일정 찾기 (Claude)")
        Text("console.anthropic.com → API Keys 에서 키를 받아 넣으세요. 모델 기본값은 claude-opus-5-5. 비용을 줄이려면 claude-haiku-5-5 로 바꿀 수 있어요.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(claudeKey, { claudeKey = it }, Modifier.fillMaxWidth(), label = { Text("Claude API 키") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(claudeModel, { claudeModel = it }, Modifier.fillMaxWidth(), label = { Text("Claude 모델") }, singleLine = true)
        OutlinedTextField(myName, { myName = it }, Modifier.fillMaxWidth(), label = { Text("내 이름·호칭 (예: 김지훈, 지훈 씨, 과장님)") }, singleLine = true)
        OutlinedTextField(vocab, { vocab = it }, Modifier.fillMaxWidth(), label = { Text("자주 나오는 이름 (쉼표로: 사람, 회사, 장소)") })

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save(); test = "저장했어요" }) { Text("저장") }
            OutlinedButton(onClick = {
                save()
                test = "확인 중…"
                scope.launch {
                    test = withContext(Dispatchers.IO) { connectionTest(g) }
                }
            }) { Text("연결 확인") }
        }
        if (test.isNotBlank()) Text(test)

        SectionTitle("3. 캘린더·알림")
        if (!g.calendar.hasPermission()) {
            OutlinedButton(onClick = { activity.requestAll { scope.launch { calendars = withContext(Dispatchers.IO) { g.calendar.writableCalendars() } } } }) { Text("캘린더·알림 권한 허용") }
        } else if (calendars.isEmpty()) {
            Text("쓸 수 있는 캘린더가 없어요. 삼성 캘린더 앱에서 삼성 계정을 연결해 주세요.", color = MaterialTheme.colorScheme.error)
        } else {
            Text("일정을 넣을 캘린더 (삼성 계정을 고르면 삼성 캘린더·갤럭시 기기에 동기화돼요)", style = MaterialTheme.typography.bodySmall)
            calendars.forEach { c ->
                FilterChip(selected = calId == c.id || (calId == -1L && c == calendars.first()), onClick = { calId = c.id; s.calendarId = c.id }, label = { Text(c.label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("묻지 않고 바로 캘린더에 넣기", Modifier.weight(1f))
            Switch(auto, { auto = it; s.autoRegister = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("캘린더 알림과 별도로 앱이 직접 알리기 (미리 + 정각)", Modifier.weight(1f))
            Switch(own, { own = it; s.ownReminders = it })
        }
        OutlinedTextField(remind, { remind = it }, Modifier.fillMaxWidth(), label = { Text("미리 알림 (분 전)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(digest, { digest = it }, Modifier.fillMaxWidth(), label = { Text("하루 요약 알림 시각 (0~23시, -1 끔)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

        SectionTitle("4. 24시간 듣기 (삼성 갤럭시)")
        Text(
            "갤럭시는 배터리 절약을 위해 오래 켜진 앱을 재웁니다. 아래 '배터리 최적화 제외'를 허용하고, " +
                "설정 → 배터리 → 백그라운드 사용 제한 → '절전 예외 앱'에 곁비서를 넣어 주세요. 듣기 중에는 상단에 알림이 늘 떠 있어요.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !battery, onClick = { activity.askBatteryExemption(); battery = activity.ignoringBattery() }) {
                Text(if (battery) "배터리 최적화 제외됨" else "배터리 최적화 제외")
            }
            OutlinedButton(onClick = { activity.openAppSettings() }) { Text("앱 설정 열기") }
        }

        SectionTitle("5. 개인정보")
        Text(
            "• 원본 음성은 저장하지 않아요. 말소리 구간만 받아쓰기 서비스로 보내고 바로 버립니다.\n" +
                "• 받아쓴 글·정리는 이 휴대폰에만 저장되고, 정리할 때만 Claude 로 보냅니다.\n" +
                "• 대한민국 통신비밀보호법상 내가 참여한 대화는 녹음·기록할 수 있지만, 내가 빠진 다른 사람들끼리의 대화를 몰래 기록하면 불법입니다. 자리를 비울 때는 [잠시 멈춤]을 눌러 주세요 (빠른 설정 타일 '곁비서 듣기' 로도 됩니다).",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(keep, { keep = it }, Modifier.fillMaxWidth(), label = { Text("받아쓴 글 보관 일수") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedButton(onClick = { confirmClear = true }) { Text("받아쓴 글·정리 모두 지우기") }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("모두 지울까요?") },
            text = { Text("받아쓴 글과 정리 노트를 지웁니다. 캘린더에 넣은 일정은 그대로 둡니다.") },
            confirmButton = { TextButton(onClick = { g.store.clearAll(); confirmClear = false }) { Text("지우기") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("그만두기") } },
        )
    }
}

/** 받아쓰기(무음 1초)와 Claude(짧은 정리)를 한 번씩 불러 본다. */
private fun connectionTest(g: Graph): String {
    val stt = runCatching {
        g.transcriber.transcribe(io.github.jgg113.gyeot.audio.Wav.encode(ShortArray(16_000)))
        "받아쓰기 ✓"
    }.getOrElse { "받아쓰기 ✗ ${it.message}" }
    val claude = runCatching {
        val now = ZonedDateTime.now(EventRules.SEOUL)
        val a = g.analyzer.analyze(
            emptyList(),
            listOf(io.github.jgg113.gyeot.data.Utterance(System.currentTimeMillis(), "내일 오후 세 시에 강남역에서 민수랑 만나기로 했어")),
            emptyList(), now,
        )
        val e = a.events.firstOrNull()
        "Claude ✓" + if (e != null) " — 시험 문장에서 찾은 일정: ${e.title} ${e.start.replace('T', ' ')} (캘린더에는 넣지 않았어요)" else ""
    }.getOrElse { "Claude ✗ ${it.message}" }
    return "$stt\n$claude"
}
