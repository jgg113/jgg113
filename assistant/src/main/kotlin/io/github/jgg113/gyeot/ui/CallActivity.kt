package io.github.jgg113.gyeot.ui

import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.alarm.Notifier
import io.github.jgg113.gyeot.data.SavedEvent
import io.github.jgg113.gyeot.voice.VoiceCall
import java.util.Locale

/**
 * 곁비서 전화 화면: 수신 전화처럼 울리고, [받기]를 누르면 할 말을 한국어 음성으로 읽어 준다.
 * 40초 안에 안 받으면 끊고 보통 알림으로 남긴다.
 */
class CallActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var ringtone: Ringtone? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakWhenReady = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var title by mutableStateOf("")
    private var speech by mutableStateOf("")
    private var eventId = -1L
    private var answered by mutableStateOf(false)
    private var speaking by mutableStateOf(false)
    private var asking by mutableStateOf(emptyList<SavedEvent>())

    private val missed = Runnable {
        if (!answered) {
            stopRinging()
            Notifier.simple(this, "부재중 · 곁비서: $title", speech, VoiceCall.ID_CALL + 100)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        load(intent)
        tts = TextToSpeech(this, this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { CallScreen() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        load(intent)
    }

    private fun load(i: android.content.Intent) {
        title = i.getStringExtra(VoiceCall.EXTRA_TITLE).orEmpty()
        speech = i.getStringExtra(VoiceCall.EXTRA_SPEECH).orEmpty()
        eventId = i.getLongExtra(VoiceCall.EXTRA_EVENT, -1)
        asking = Graph.get(this).store.suggestions().take(3)
        if (i.getBooleanExtra(EXTRA_ANSWER, false)) answer() else if (!answered) startRinging()
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status == TextToSpeech.SUCCESS) {
            val r = t.setLanguage(Locale.KOREAN)
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            t.setSpeechRate(0.95f)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { speaking = true }
                override fun onDone(id: String?) { speaking = false }
                @Deprecated("old API") override fun onError(id: String?) { speaking = false }
            })
        }
        if (speakWhenReady) speak()
    }

    private fun startRinging() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
            if (Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        handler.postDelayed(missed, 40_000)
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        getSystemService(Vibrator::class.java)?.cancel()
        handler.removeCallbacks(missed)
    }

    private fun answer() {
        answered = true
        stopRinging()
        VoiceCall.cancel(this)
        speak()
    }

    private fun speak() {
        val t = tts
        if (t == null || !ttsReady) { speakWhenReady = true; return }
        speakWhenReady = false
        t.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "gyeot-call")
    }

    private fun hangUp() {
        stopRinging()
        tts?.stop()
        VoiceCall.cancel(this)
        finish()
    }

    override fun onResume() { super.onResume(); VoiceCall.foregroundActivities++ }
    override fun onPause() { super.onPause(); VoiceCall.foregroundActivities-- }

    override fun onDestroy() {
        stopRinging()
        tts?.shutdown()
        super.onDestroy()
    }

    @Composable
    private fun CallScreen() {
        val g = Graph.get(this)
        Box(Modifier.fillMaxSize().background(Color(0xFF10261F)).padding(24.dp)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(48.dp))
                Box(Modifier.size(96.dp).background(Color(0xFF2E7D6B), CircleShape), contentAlignment = Alignment.Center) {
                    Text("곁", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                Text("곁비서", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(if (!answered) "전화 왔어요…" else if (speaking) "말하는 중…" else "통화 중", color = Color(0xFFB7D9CF))
                Spacer(Modifier.height(24.dp))
                Text(title, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center)
                if (answered) {
                    Spacer(Modifier.height(16.dp))
                    Text(speech, color = Color(0xFFE0F2EC), textAlign = TextAlign.Center)
                    if (!ttsReady) Text("이 휴대폰에 한국어 음성이 없어 글로 보여 드려요 (설정 → 일반 → 텍스트 음성 변환에서 한국어 받기).", color = Color(0xFFFFC83D), fontSize = 12.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { speak() }) { Text("다시 듣기") }
                        if (eventId >= 0) OutlinedButton(onClick = { g.alarms.snooze(eventId, 10); hangUp() }) { Text("10분 뒤 다시") }
                    }
                    asking.forEach { e ->
                        Spacer(Modifier.height(12.dp))
                        Text("추가할까요? ${e.title} · ${if (e.timeUnknown) "날짜 미정" else Notifier.describe(e)}", color = Color.White, textAlign = TextAlign.Center)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!e.timeUnknown) Button(onClick = { Thread { g.events.addToCalendar(e.id) }.start(); asking = asking - e }) { Text("캘린더") }
                            OutlinedButton(onClick = { Thread { g.events.remindOnly(e.id) }.start(); asking = asking - e }) { Text("알림만") }
                            OutlinedButton(onClick = { Thread { g.events.dismiss(e.id) }.start(); asking = asking - e }) { Text("아니요") }
                        }
                    }
                }
                Spacer(Modifier.height(48.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = { hangUp() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD9443B)), modifier = Modifier.size(88.dp), shape = CircleShape) { Text("끊기") }
                    if (!answered) Button(onClick = { answer() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34A853)), modifier = Modifier.size(88.dp), shape = CircleShape) { Text("받기") }
                }
            }
        }
    }

    companion object {
        const val EXTRA_ANSWER = "answer"
    }
}
