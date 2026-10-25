package io.github.jgg113.gyeot.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.alarm.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 24시간 듣기. '듣는 중' 알림을 늘 띄워 둔 포그라운드 서비스라 화면이 꺼져도 안드로이드가 멈추지 않는다.
 * 마이크 소리는 메모리에서만 다루고, 말 구간만 받아쓰기로 보낸 뒤 버린다.
 */
class ListeningService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recordJob: Job? = null
    private var processor: Processor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { setPaused(true); return START_STICKY }
            ACTION_RESUME -> { setPaused(false); return START_STICKY }
            ACTION_ANALYZE -> { processor?.analyzeNow(); return START_STICKY }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ListenState.update { it.copy(running = false, error = "마이크 권한이 없습니다") }
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = Notifier.listening(this, "말소리를 기다리는 중", paused)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Notifier.ID_LISTEN, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(Notifier.ID_LISTEN, notification)
            }
        } catch (e: Exception) {
            // 안드로이드 14+ 는 앱이 화면에 없을 때(시스템이 서비스를 되살릴 때 등) 마이크 서비스를 못 켜게 막는다.
            // 그때 앱이 죽지 않게, "눌러서 다시 듣기" 알림을 남기고 멈춘다.
            ListenState.update { it.copy(running = false, error = "휴대폰이 듣기를 멈췄어요. 앱을 열면 다시 들어요") }
            Notifier.simple(this, "수빈이가 잠시 못 듣고 있어요", "휴대폰이 백그라운드 듣기를 멈췄어요. 눌러서 앱을 열면 다시 들어요.", Notifier.ID_RESUME)
            stopSelf()
            return START_NOT_STICKY
        }
        if (recordJob == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gyeot:listen")
                .apply { setReferenceCounted(false); acquire() }
            val p = Processor(this, scope).also { processor = it; it.start() }
            recordJob = scope.launch(Dispatchers.IO) { record(p) }
            scope.launch { keepNotificationFresh() }
            scope.launch(Dispatchers.IO) { watchCallRecordings() }
            ListenState.update { it.copy(running = true, paused = paused) }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(p: Processor) {
        var segmenter = Graph.get(this).newSegmenter()
        var frames = 0L
        val rate = SpeechSegmenter.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val frame = ShortArray(segmenter.frameSamples)
        while (scope.isActive) {
            // VOICE_RECOGNITION: 잡음 억제·자동 음량 없이 받아쓰기에 맞춘 마이크 경로.
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame.size * 2 * 50))
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                ListenState.update { it.copy(error = "마이크를 열 수 없습니다. 다른 앱이 쓰는 중일 수 있어요") }
                delay(10_000)
                continue
            }
            rec.startRecording()
            try {
                while (scope.isActive) {
                    if (paused) {
                        segmenter.flush()?.let { offer(p, it) }
                        break
                    }
                    var read = 0
                    while (read < frame.size) {
                        val n = rec.read(frame, read, frame.size - read)
                        if (n < 0) throw IllegalStateException("마이크 읽기 오류 $n")
                        read += n
                    }
                    // 말소리 감지 모델을 듣는 도중에 받았으면, 조용할 때 그 모델로 바꾼다 (10초마다 확인).
                    if (++frames % 500 == 0L && segmenter !is SileroSegmenter && !segmenter.inSpeech && Graph.get(this).models.installed(Graph.get(this).models.vad)) {
                        segmenter.release(); segmenter = Graph.get(this).newSegmenter()
                    }
                    val wasHearing = segmenter.inSpeech
                    segmenter.push(frame.copyOf())?.let { offer(p, it) }
                    if (segmenter.inSpeech != wasHearing) ListenState.update { it.copy(hearing = segmenter.inSpeech) }
                }
            } catch (e: Exception) {
                ListenState.update { it.copy(error = e.message.orEmpty()) }
                delay(3_000)
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
            if (!scope.isActive) segmenter.release()
            // 멈춘 동안은 마이크를 아예 놓아 준다 (상태 표시줄의 마이크 표시도 꺼진다).
            while (paused && scope.isActive) delay(500)
        }
    }

    /** 수빈이(앱)가 스피커로 말하던 때 들어온 소리는 받아쓰지 않는다 — 자기 말을 일정으로 넣지 않게. */
    private fun offer(p: Processor, pcm: ShortArray) {
        val end = System.currentTimeMillis()
        val start = end - pcm.size * 1000L / SpeechSegmenter.SAMPLE_RATE
        if (io.github.jgg113.gyeot.voice.SelfSpeech.overlaps(start, end)) return
        p.offer(end, pcm)
    }

    /**
     * 통화가 끝나 삼성 전화 앱이 녹음 파일을 저장하면 곧바로 받아써 정리한다.
     * 미디어 저장소 변경을 지켜보고(10초 모아서), 놓친 것이 없게 15분마다 한 번 더 훑는다.
     */
    private suspend fun watchCallRecordings() {
        val g = Graph.get(this)
        val changed = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { changed.trySend(Unit) }
        }
        contentResolver.registerContentObserver(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        try {
            changed.trySend(Unit)
            while (scope.isActive) {
                kotlinx.coroutines.withTimeoutOrNull(15 * 60_000L) { changed.receive() }
                delay(10_000)
                if (g.settings.importCalls) runCatching { g.calls.scan() }
            }
        } finally {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    private suspend fun keepNotificationFresh() {
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        ListenState.state.collectLatest { s ->
            val status = when {
                s.paused -> "멈춤 — 알림의 [다시 듣기]를 누르세요"
                s.error.isNotBlank() -> "⚠ ${s.error.take(60)}"
                s.hearing -> "말소리 듣는 중…"
                s.lastHeardAt > 0 -> "마지막 들은 말 ${Instant.ofEpochMilli(s.lastHeardAt).atZone(ZoneId.of("Asia/Seoul")).format(hm)} · ${s.lastText.take(30)}"
                else -> "말소리를 기다리는 중"
            }
            getSystemService(NotificationManager::class.java).notify(Notifier.ID_LISTEN, Notifier.listening(this@ListeningService, status, s.paused))
            delay(1_000) // 알림을 너무 자주 고치지 않게
        }
    }

    private fun setPaused(value: Boolean) {
        paused = value
        ListenState.update { it.copy(paused = value, hearing = false) }
    }

    override fun onDestroy() {
        scope.cancel()
        recordJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
        ListenState.update { it.copy(running = false, hearing = false) }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_ANALYZE = "analyze"

        fun start(context: Context) {
            Graph.get(context).settings.listeningWanted = true
            context.startForegroundService(Intent(context, ListeningService::class.java))
        }

        fun stop(context: Context) {
            Graph.get(context).settings.listeningWanted = false
            context.stopService(Intent(context, ListeningService::class.java))
        }

        fun pause(context: Context, value: Boolean) {
            if (!ListenState.state.value.running) return
            context.startService(Intent(context, ListeningService::class.java).setAction(if (value) ACTION_PAUSE else ACTION_RESUME))
        }

        fun analyzeNow(context: Context) {
            if (!ListenState.state.value.running) return
            context.startService(Intent(context, ListeningService::class.java).setAction(ACTION_ANALYZE))
        }
    }
}
