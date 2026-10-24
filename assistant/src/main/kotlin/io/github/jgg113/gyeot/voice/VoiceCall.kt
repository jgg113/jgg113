package io.github.jgg113.gyeot.voice

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import io.github.jgg113.gyeot.R
import io.github.jgg113.gyeot.alarm.Notifier
import io.github.jgg113.gyeot.ui.CallActivity

/**
 * "클로드 수빈이"가 전화를 건다: 잠금 화면 위로 삼성 수신 전화 같은 화면을 띄우고 벨을 울린다.
 * 받으면 수빈이가 말로 알려 주고, 이어서 말로 대답·질문할 수 있다.
 * 진짜 통화 중이면 방해하지 않고 보통 알림으로 대신한다.
 */
object VoiceCall {
    const val EXTRA_KIND = "kind"
    const val EXTRA_TITLE = "title"
    const val EXTRA_SPEECH = "speech"
    const val EXTRA_EVENT = "event"
    const val KIND_EVENT = "event"
    const val KIND_BRIEFING = "briefing"
    const val KIND_CHAT = "chat"
    const val ID_CALL = 5

    fun ring(context: Context, kind: String, title: String, fallbackSpeech: String, eventId: Long = -1) {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
            Notifier.simple(context, "${VoiceScript.CALLER}: $title", fallbackSpeech, ID_CALL)
            return
        }
        val intent = intent(context, kind, title, fallbackSpeech, eventId)
        val full = PendingIntent.getActivity(context, 77, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val answer = PendingIntent.getActivity(context, 78, Intent(intent).putExtra(CallActivity.EXTRA_ANSWER, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, Notifier.CH_CALL)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentTitle(VoiceScript.CALLER)
            .setContentText(title)
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .addAction(Notification.Action.Builder(null, "받기", answer).build())
            .build()
        if (Notifier.canPost(context)) context.getSystemService(NotificationManager::class.java).notify(ID_CALL, n)
        // 앱을 보고 있을 때는 전체 화면 알림이 작은 알림으로만 뜨므로 화면을 직접 연다.
        runCatching { if (foregroundActivities > 0) context.startActivity(intent) }
    }

    /** 오늘 탭의 [수빈이와 통화]: 바로 받은 상태로 시작해 무엇이든 말로 묻는다. */
    fun chat(context: Context) {
        context.startActivity(intent(context, KIND_CHAT, "통화", "", -1).putExtra(CallActivity.EXTRA_ANSWER, true))
    }

    private fun intent(context: Context, kind: String, title: String, speech: String, eventId: Long) =
        Intent(context, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_SPEECH, speech)
            .putExtra(EXTRA_EVENT, eventId)

    /** 안드로이드 14+ 는 '전체 화면 알림' 허용이 따로 필요하다 (설정에서 켜기). */
    fun canFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID_CALL)

    @Volatile var foregroundActivities = 0
}
