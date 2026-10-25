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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.audio.ListenState
import io.github.jgg113.gyeot.audio.ListeningService
import io.github.jgg113.gyeot.audio.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import android.app.AlertDialog.Builder as AlertDialogBuilder

class MainActivity : ComponentActivity() {
    private val tab = mutableIntStateOf(0)
    private var afterPermissions: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermissions?.invoke()
        afterPermissions = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        tab.intValue = intent.getIntExtra(EXTRA_TAB, 0)
        intent.getLongExtra(EXTRA_EVENT, -1).takeIf { it >= 0 }?.let { openEvent.longValue = it }
        handleShortcut(intent)
        val g = Graph.get(this)
        // 부팅 뒤 '다시 듣기' 알림을 눌러 들어온 경우 등: 켜 두었던 듣기를 이어 간다.
        if (g.settings.listeningWanted && !ListenState.state.value.running && hasMic()) runCatching { ListeningService.start(this) }
        setContent {
            BrandTheme { App(tab.intValue, onTab = { tab.intValue = it }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tab.intValue = intent.getIntExtra(EXTRA_TAB, tab.intValue)
        intent.getLongExtra(EXTRA_EVENT, -1).takeIf { it >= 0 }?.let { openEvent.longValue = it; tab.intValue = 0 }
        handleShortcut(intent)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 앱 아이콘 길게 누르기 바로가기: 수빈이와 통화 / 듣기 잠시 멈춤. */
    private fun handleShortcut(i: Intent) {
        when (i.getStringExtra("shortcut")) {
            "call" -> io.github.jgg113.gyeot.voice.VoiceCall.chat(this)
            "pause" -> {
                val s = ListenState.state.value
                if (s.running) ListeningService.pause(this, !s.paused)
                android.widget.Toast.makeText(this, if (s.running && !s.paused) "듣기를 잠시 멈췄어요" else if (s.running) "다시 들어요" else "듣기가 꺼져 있어요", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        i.removeExtra("shortcut")
    }

    /** 권한 몇 개를 묻고, 대답이 끝나면 [then]. */
    fun request(perms: List<String>, then: () -> Unit) {
        val wanted = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) then() else {
            afterPermissions = then
            permissionLauncher.launch(wanted.toTypedArray())
        }
    }

    /** 마이크 권한을 받은 뒤에만 [then]. */
    fun withMic(then: () -> Unit) = request(listOf(Manifest.permission.RECORD_AUDIO)) { if (hasMic()) then() }

    override fun onResume() {
        super.onResume()
        io.github.jgg113.gyeot.voice.VoiceCall.foregroundActivities++
        // 앱을 열 때도 새 통화 녹음이 있는지 본다 (듣기가 꺼져 있어도).
        val g = Graph.get(this)
        if (g.settings.importCalls) lifecycleScope.launch(Dispatchers.IO) { runCatching { g.calls.scan() } }
    }

    override fun onPause() {
        super.onPause()
        io.github.jgg113.gyeot.voice.VoiceCall.foregroundActivities--
    }

    override fun onStop() {
        super.onStop()
        Player.stop()
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
        }
        request(wanted, then)
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

    fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_EVENT = "event_id"
        const val TOOL_DICTATION = "dictation"
        const val TOOL_VOICE = "voice"
        /** 근거 화면으로 열 일정 id (-1 이면 닫힘). */
        val openEvent = mutableLongStateOf(-1L)
        /** 열어 둘 도구 창: [TOOL_DICTATION] 받아쓰기 시험, [TOOL_VOICE] 내 목소리 등록 (null 이면 닫힘). */
        val openTool = mutableStateOf<String?>(null)
    }
}

private data class TabItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun App(tab: Int, onTab: (Int) -> Unit) {
    val tabs = listOf(
        TabItem("오늘", Icons.Filled.Home),
        TabItem("기록", Icons.AutoMirrored.Filled.List),
        TabItem("묻기", Icons.Filled.Search),
        TabItem("설정", Icons.Filled.Settings),
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { onTab(i) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        val detail = MainActivity.openEvent.longValue
        if (detail >= 0) EventEvidence(detail) { MainActivity.openEvent.longValue = -1 }
        when (MainActivity.openTool.value) {
            MainActivity.TOOL_DICTATION -> DictationTestDialog { MainActivity.openTool.value = null }
            MainActivity.TOOL_VOICE -> VoiceEnrollDialog { MainActivity.openTool.value = null }
        }
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TodayScreen(onTab)
                1 -> HistoryScreen()
                2 -> AskScreen()
                else -> SettingsScreen()
            }
        }
    }
}
