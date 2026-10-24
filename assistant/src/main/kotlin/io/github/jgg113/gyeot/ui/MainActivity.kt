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
import androidx.lifecycle.lifecycleScope
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
import io.github.jgg113.gyeot.data.Settings as AppSettings
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.ScheduleSummary
import android.app.AlertDialog.Builder as AlertDialogBuilder
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

    /** 권한 몇 개를 묻고, 대답이 끝나면 [then]. */
    fun request(perms: List<String>, then: () -> Unit) {
        val wanted = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) then() else {
            afterPermissions = then
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        io.github.jgg113.gyeot.voice.VoiceCall.foregroundActivities++
        // 앱을 열 때도 새 통화 녹음이 있는지 본다 (듣기가 꺼져 있어도).
        val g = Graph.get(this)
        if (g.settings.importCalls) lifecycleScopeLaunchIO { g.calls.scan() }
    }

    override fun onPause() {
        super.onPause()
        io.github.jgg113.gyeot.voice.VoiceCall.foregroundActivities--
    }

    private fun lifecycleScopeLaunchIO(block: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { runCatching { block() } }
    }

    fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT >= 34) runCatching {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        }
    }

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
        val why = Graph.get(this).notReadyReason()
        if (why != null) android.widget.Toast.makeText(this, why, android.widget.Toast.LENGTH_LONG).show()
        else if (hasMic()) ListeningService.start(this)
    }

    fun ignoringBattery(): Boolean = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @android.annotation.SuppressLint("BatteryLife")
    fun askBatteryExemption() {
        runCatching {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    /** 날짜 → (종일이 아니면) 시각을 차례로 고르게 한다. */
    fun pickDateTime(initial: Long, allDayInitially: Boolean, done: (Long, Boolean) -> Unit) {
        val t = Instant.ofEpochMilli(initial).atZone(EventRules.SEOUL)
        android.app.DatePickerDialog(this, { _, y, m, d ->
            val day = LocalDate.of(y, m + 1, d)
            AlertDialogBuilder(this).setTitle("시각").setItems(arrayOf("시각 고르기", "종일")) { _, which ->
                if (which == 1) done(day.atStartOfDay(EventRules.SEOUL).toInstant().toEpochMilli(), true)
                else android.app.TimePickerDialog(this, { _, h, min ->
                    done(day.atTime(h, min).atZone(EventRules.SEOUL).toInstant().toEpochMilli(), false)
                }, if (allDayInitially) 9 else t.hour, if (allDayInitially) 0 else t.minute, false).show()
            }.show()
        }, t.year, t.monthValue - 1, t.dayOfMonth).show()
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
        topBar = { TopAppBar(title = { Text("클로드비서 · ${titles[tab]}") }) },
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
    val asking = remember(version) { g.store.suggestions().sortedBy { it.startMillis } }
    val today = LocalDate.now(EventRules.SEOUL)
    val notes = remember(version) { g.store.notes().filter { Store.dayOf(it.at) == today }.reversed() }
    var range by remember { mutableIntStateOf(0) } // 0 오늘·내일, 1 이번 주, 2 다음 주
    var summary by remember { mutableStateOf("") }
    LaunchedEffect(version, range) {
        summary = withContext(Dispatchers.IO) {
            val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            val (from, to) = when (range) {
                1 -> today to monday.plusDays(6)
                2 -> monday.plusWeeks(1) to monday.plusWeeks(1).plusDays(6)
                else -> today to today.plusDays(1)
            }
            ScheduleSummary.text(g.scheduleItems(from, to), from, to, today)
        }
    }

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
                    val why = remember(state.running, version) { g.notReadyReason() }
                    if (why != null) Text(why, color = MaterialTheme.colorScheme.error)
                    else Text(
                        (if (g.settings.localStt) "받아쓰기: 휴대폰 안 (무료·무제한)" else "받아쓰기: 클라우드") + " · " +
                            (if (g.settings.localBrain) "정리: 휴대폰 안" else "정리: Claude"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { io.github.jgg113.gyeot.voice.VoiceCall.chat(activity) }) { Text("📞 수빈이와 통화") }
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
        if (asking.isNotEmpty()) {
            item { SectionTitle("추가할까요? (${asking.size})") }
            items(asking, key = { it.id }) { e -> SuggestionCard(e, g, activity) }
        }
        item { SectionTitle("일정 요약") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("오늘·내일", "이번 주", "다음 주").forEachIndexed { i, t -> FilterChip(selected = range == i, onClick = { range = i }, label = { Text(t) }) }
                    }
                    Text(summary.ifBlank { "불러오는 중…" })
                    if (!g.calendar.hasPermission()) Text("캘린더 권한을 허용하면 삼성 캘린더의 다른 일정도 함께 보여요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        item { SectionTitle("대화에서 넣은 일정 (${events.size})") }
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
                        e.calendarEventId != null -> "캘린더 등록됨 · ${e.reminderMinutes}분 전 알림"
                        else -> "🔔 앱 알림만 · ${if (e.allDay) "그날 아침 8시" else "${e.reminderMinutes}분 전·정각"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch(Dispatchers.IO) { g.events.cancel(e.id) } }) { Text("취소") }
            }
        }
    }
}

/** "추가할까요?" 카드: 캘린더 / 알림만 / 날짜·시각 바꾸기 / 아니요. */
@Composable
private fun SuggestionCard(e: SavedEvent, g: Graph, activity: MainActivity) {
    val scope = rememberCoroutineScope()
    fun act(block: () -> Unit) { scope.launch(Dispatchers.IO) { block() } }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(e.title, fontWeight = FontWeight.Bold)
            Text(if (e.timeUnknown) "언제인지 못 들었어요 — [날짜 정하기]로 정해 주세요" else Notifier.describe(e) + if (e.location.isNotBlank()) " · ${e.location}" else "")
            if (e.evidence.isNotBlank()) Text("들은 말: \"${e.evidence}\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!e.timeUnknown) {
                    Button(onClick = { act { g.events.addToCalendar(e.id) } }) { Text("캘린더") }
                    OutlinedButton(onClick = { act { g.events.remindOnly(e.id) } }) { Text("알림만") }
                }
                OutlinedButton(onClick = {
                    activity.pickDateTime(e.startMillis, e.allDay && !e.timeUnknown) { start, allDay ->
                        act { g.events.reschedule(e.id, start, allDay, toCalendar = true) }
                    }
                }) { Text(if (e.timeUnknown) "날짜 정하기" else "바꾸기") }
                TextButton(onClick = { act { g.events.dismiss(e.id) } }) { Text("아니요") }
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
                        Text(if (u.callWith != null) "📞 ${u.callWith}: ${u.text}" else u.text)
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
        Text("들은 대화와 캘린더를 근거로 물어보세요. 예) \"이번 주 일정 뭐 있어?\", \"아까 팀장님이 보고서 언제까지라고 했지?\", \"어제 들은 계좌번호 알려줘\"")
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
                        g.ask(question, g.recentMaterial(days))
                    } catch (e: Exception) {
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
    var addMode by remember { mutableStateOf(s.addMode) }
    var briefing by remember { mutableStateOf(s.briefingHour.toString()) }
    var importCalls by remember { mutableStateOf(s.importCalls && g.calls.hasPermission()) }
    var callRemind by remember { mutableStateOf(s.callForReminders) }
    var callBrief by remember { mutableStateOf(s.callForBriefing) }
    var callScan by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf(s.detailLevel) }
    var fullScreenOk by remember { mutableStateOf(io.github.jgg113.gyeot.voice.VoiceCall.canFullScreen(activity)) }
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
    val llmProgress by g.models.progress(g.models.llm).collectAsState()

    LaunchedEffect(Unit) { calendars = withContext(Dispatchers.IO) { g.calendar.writableCalendars() } }

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
    }

    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("1. 받아쓰기 (음성 인식)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sttEngine == AppSettings.ENGINE_LOCAL, onClick = { sttEngine = AppSettings.ENGINE_LOCAL; s.sttEngine = sttEngine }, label = { Text("휴대폰 안 (무료·무제한)") })
            FilterChip(selected = sttEngine == AppSettings.ENGINE_CLOUD, onClick = { sttEngine = AppSettings.ENGINE_CLOUD; s.sttEngine = sttEngine }, label = { Text("클라우드 API") })
        }
        if (sttEngine == AppSettings.ENGINE_LOCAL) {
            Text("인터넷 없이 휴대폰 안에서 한국어를 받아씁니다 (sherpa-onnx, 한국어 일상 대화로 학습한 모델). 처음 한 번 85MB 를 받습니다.", style = MaterialTheme.typography.bodySmall)
            ModelRow(g.models.installed(g.models.asr) && !asrProgress.running, asrProgress, g.models.asr.label, onGet = { g.models.download(g.models.asr) }, onDelete = { g.localTranscriber.close(); g.models.delete(g.models.asr) })
        } else {
            Text("OpenAI 호환 받아쓰기 API. Groq(무료 한도): console.groq.com 에서 키. OpenAI: 주소 https://api.openai.com/v1, 모델 gpt-4o-transcribe.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(sttKey, { sttKey = it }, Modifier.fillMaxWidth(), label = { Text("음성 인식 API 키") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(sttBase, { sttBase = it }, Modifier.fillMaxWidth(), label = { Text("주소") }, singleLine = true)
            OutlinedTextField(sttModel, { sttModel = it }, Modifier.fillMaxWidth(), label = { Text("모델") }, singleLine = true)
        }

        SectionTitle("2. 정리·일정 찾기")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = brainEngine == AppSettings.ENGINE_LOCAL, onClick = { brainEngine = AppSettings.ENGINE_LOCAL; s.brainEngine = brainEngine }, label = { Text("휴대폰 안 (무료·무제한)") })
            FilterChip(selected = brainEngine == AppSettings.ENGINE_CLAUDE, onClick = { brainEngine = AppSettings.ENGINE_CLAUDE; s.brainEngine = brainEngine }, label = { Text("Claude (유료, 더 똑똑함)") })
        }
        if (brainEngine == AppSettings.ENGINE_LOCAL) {
            Text("일정·할 일·중요한 숫자는 한국어 규칙으로 바로 찾습니다 (\"내일 오후 세 시\", \"다음 주 화요일\", \"삼십 분 뒤\" 등). 아래 요약 AI 를 받아 두면 요약 문장도 휴대폰 안에서 다듬습니다 (최신 갤럭시 S 권장).", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("휴대폰 안 요약 AI 쓰기 (실험 기능 — 작은 모델이라 문장이 어색하거나 틀릴 수 있어요)", Modifier.weight(1f))
                Switch(useLlm, { useLlm = it; s.useLocalLlm = it })
            }
            if (useLlm) ModelRow(g.models.installed(g.models.llm) && !llmProgress.running, llmProgress, g.models.llm.label, onGet = { g.models.download(g.models.llm) }, onDelete = { g.models.delete(g.models.llm) })
        } else {
            Text("console.anthropic.com → API Keys 에서 키를 받아 넣으세요. 기본 모델 claude-opus-5-5, 싸게 쓰려면 claude-haiku-5-5.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(claudeKey, { claudeKey = it }, Modifier.fillMaxWidth(), label = { Text("Claude API 키") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(claudeModel, { claudeModel = it }, Modifier.fillMaxWidth(), label = { Text("Claude 모델") }, singleLine = true)
        }
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
            }) { Text("작동 확인") }
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
        Text("대화에서 일정 같은 말을 들었을 때", style = MaterialTheme.typography.bodyMedium)
        listOf(
            EventRules.MODE_SMART to "알아서: 확실한 약속은 바로 넣고, 애매하거나 돌려 말한 건 \"추가할까요?\" 묻기",
            EventRules.MODE_ASK to "늘 묻기: 모든 일정을 \"추가할까요?\" 하고 물어보기",
            EventRules.MODE_AUTO to "바로 넣기: 웬만하면 묻지 않고 넣기",
        ).forEach { (mode, label) ->
            FilterChip(selected = addMode == mode, onClick = { addMode = mode; s.addMode = mode }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("캘린더 알림과 별도로 앱이 직접 알리기 (미리 + 정각)", Modifier.weight(1f))
            Switch(own, { own = it; s.ownReminders = it })
        }
        OutlinedTextField(remind, { remind = it }, Modifier.fillMaxWidth(), label = { Text("미리 알림 (분 전)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(briefing, { briefing = it }, Modifier.fillMaxWidth(), label = { Text("아침 일정 브리핑 시각 (0~23시, -1 끔)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(digest, { digest = it }, Modifier.fillMaxWidth(), label = { Text("저녁 하루 요약 시각 (0~23시, -1 끔)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

        SectionTitle("4. 24시간 듣기 (삼성 갤럭시)")
        Text(
            "갤럭시는 배터리 절약을 위해 오래 켜진 앱을 재웁니다. 아래 '배터리 최적화 제외'를 허용하고, " +
                "설정 → 배터리 → 백그라운드 사용 제한 → '절전 예외 앱'에 클로드비서를 넣어 주세요. 듣기 중에는 상단에 알림이 늘 떠 있어요.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !battery, onClick = { activity.askBatteryExemption(); battery = activity.ignoringBattery() }) {
                Text(if (battery) "배터리 최적화 제외됨" else "배터리 최적화 제외")
            }
            OutlinedButton(onClick = { activity.openAppSettings() }) { Text("앱 설정 열기") }
        }

        SectionTitle("5. 통화 녹음도 정리하기")
        Text(
            "통화 중에는 안드로이드가 다른 앱의 마이크를 막아서 수빈이가 직접 못 들어요. 삼성 전화 앱의 '통화 자동 녹음'을 켜 두면, 통화가 끝나 녹음 파일이 저장될 때 " +
                "수빈이가 그 파일을 받아써서 일정·할 일·중요한 말을 함께 정리해요 (녹음 파일은 읽기만 해요).",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("통화 녹음 자동으로 정리", Modifier.weight(1f))
            Switch(importCalls, { on ->
                if (!on) { importCalls = false; s.importCalls = false } else activity.request(listOf(io.github.jgg113.gyeot.calls.CallRecordingImporter.permission())) {
                    val ok = g.calls.hasPermission()
                    if (ok && !s.importCalls) g.calls.startFrom(System.currentTimeMillis() - 24L * 60 * 60 * 1000) // 처음 켜면 최근 하루치부터
                    s.importCalls = ok; importCalls = ok
                    if (!ok) callScan = "음악과 오디오(파일) 권한을 허용해야 통화 녹음을 읽을 수 있어요."
                }
            })
        }
        if (importCalls) OutlinedButton(onClick = {
            callScan = "통화 녹음 찾는 중… (긴 통화는 몇 분 걸려요)"
            scope.launch { callScan = withContext(Dispatchers.IO) { runCatching { g.calls.scan() }.fold({ "새 통화 녹음 ${it}개 정리했어요" }, { "실패: ${it.message}" }) } }
        }) { Text("지금 새 통화 녹음 정리") }
        if (callScan.isNotBlank()) Text(callScan, style = MaterialTheme.typography.bodySmall)

        SectionTitle("6. 전화로 알려 주기")
        Text("수빈이가 수신 전화처럼 벨을 울리고, 받으면 할 일을 한국어 음성으로 읽어 줘요. 진짜 통화 중일 때는 방해하지 않고 보통 알림으로 대신해요.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("일정 미리 알림을 전화로", Modifier.weight(1f))
            Switch(callRemind, { callRemind = it; s.callForReminders = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("아침 브리핑을 전화로", Modifier.weight(1f))
            Switch(callBrief, { callBrief = it; s.callForBriefing = it })
        }
        if (!fullScreenOk) {
            Text("화면이 꺼져 있을 때 전화 화면을 띄우려면 '전체 화면 알림'을 허용해 주세요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { activity.openFullScreenSettings(); fullScreenOk = io.github.jgg113.gyeot.voice.VoiceCall.canFullScreen(activity) }) { Text("전체 화면 알림 허용") }
        }
        Text("말 길이 (알림·브리핑·전화)", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = detail == AppSettings.DETAIL_BRIEF, onClick = { detail = AppSettings.DETAIL_BRIEF; s.detailLevel = detail }, label = { Text("간단히") })
            FilterChip(selected = detail == AppSettings.DETAIL_FULL, onClick = { detail = AppSettings.DETAIL_FULL; s.detailLevel = detail }, label = { Text("자세히") })
        }
        Text(
            if (detail == AppSettings.DETAIL_BRIEF) "예) \"수빈이에요! 30분 뒤 오후 3시, 민수 약속이에요.\""
            else "예) \"안녕하세요, 수빈이에요! 30분 뒤 오후 3시에 강남역에서 민수 약속 일정이 있어요. 전에 '…'라고 하셨거든요. 지금 준비하시면 딱 좋아요!\" (브리핑은 내일 일정까지)",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("통화 중에도 \"자세히 말해 줘\" / \"짧게\" 라고 하면 그때만 바꿔 읽어요.", style = MaterialTheme.typography.bodySmall)
        VoiceSettings(g)
        OutlinedButton(onClick = {
            io.github.jgg113.gyeot.voice.VoiceCall.ring(activity, io.github.jgg113.gyeot.voice.VoiceCall.KIND_BRIEFING, "시험 전화 · 오늘 일정", "")
        }) { Text("지금 시험 전화 받아 보기") }

        SectionTitle("7. 개인정보")
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

/** 수빈이 목소리 고르기: 휴대폰에 있는 한국어 목소리 목록 + 높이·빠르기 + 미리 듣기. */
@Composable
private fun VoiceSettings(g: Graph) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val s = g.settings
    var ready by remember { mutableStateOf(false) }
    val speaker = remember { io.github.jgg113.gyeot.voice.Speaker(ctx, s) { ok -> ready = ok } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    var voice by remember { mutableStateOf(s.ttsVoice) }
    var pitch by remember { mutableStateOf(s.ttsPitch) }
    var rate by remember { mutableStateOf(s.ttsRate) }
    val voices = remember(ready) { if (ready) speaker.voices() else emptyList() }
    fun preview() { speaker.reload(); speaker.speak("안녕하세요! 수빈이에요. 오늘 오후 3시에 민수 약속이 있어요. 오늘도 화이팅이에요!") }

    Text("수빈이 목소리", style = MaterialTheme.typography.bodyMedium)
    if (!ready) Text("한국어 음성 엔진을 불러오는 중이거나 없어요. 휴대폰 설정 → 일반 → 텍스트 음성 변환에서 삼성 또는 Google 엔진의 한국어 음성을 받아 주세요.", style = MaterialTheme.typography.bodySmall)
    else {
        Text("밝고 자연스러운 목소리를 고르세요. '아주 좋음'이 가장 사람 같아요. 더 많은 목소리는 Google 음성 서비스(텍스트 음성 변환) 앱에서 받을 수 있어요.", style = MaterialTheme.typography.bodySmall)
        FilterChip(selected = voice.isEmpty(), onClick = { voice = ""; s.ttsVoice = ""; preview() }, label = { Text("자동 (가장 좋은 목소리)") })
        voices.take(8).forEachIndexed { i, v ->
            FilterChip(selected = voice == v.name, onClick = { voice = v.name; s.ttsVoice = v.name; preview() }, label = { Text(io.github.jgg113.gyeot.voice.Speaker.label(v, i)) })
        }
    }
    Text("목소리 높이 ${"%.2f".format(pitch)} (높을수록 밝고 명랑하게)", style = MaterialTheme.typography.bodySmall)
    androidx.compose.material3.Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.8f..1.5f, onValueChangeFinished = { s.ttsPitch = pitch; preview() })
    Text("말 빠르기 ${"%.2f".format(rate)}", style = MaterialTheme.typography.bodySmall)
    androidx.compose.material3.Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.7f..1.4f, onValueChangeFinished = { s.ttsRate = rate; preview() })
    OutlinedButton(enabled = ready, onClick = { preview() }) { Text("목소리 미리 듣기") }
}

@Composable
private fun ModelRow(installed: Boolean, p: io.github.jgg113.gyeot.data.ModelManager.Progress, label: String, onGet: () -> Unit, onDelete: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f))
            when {
                p.running -> Text("${p.percent}% 받는 중")
                installed -> { Text("✓ 받음"); TextButton(onClick = onDelete) { Text("지우기") } }
                else -> Button(onClick = onGet) { Text("받기") }
            }
        }
        if (p.running) androidx.compose.material3.LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth())
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
        val a = g.brain.analyze(
            emptyList(),
            listOf(io.github.jgg113.gyeot.data.Utterance(System.currentTimeMillis(), "내일 오후 세 시에 강남역에서 민수랑 만나기로 했어")),
            emptyList(), now,
        )
        val e = a.events.firstOrNull()
        (if (g.settings.localBrain) "정리 ✓" else "Claude ✓") + if (e != null) " — 시험 문장에서 찾은 일정: ${e.title} ${e.start.replace('T', ' ')} (캘린더에는 넣지 않았어요)" else ""
    }.getOrElse { "정리 ✗ ${it.message}" }
    return "$stt\n$claude"
}
