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
 * 곁비서가 "전화를 건다": 잠금 화면 위로 수신 전화 같은 화면을 띄우고 벨을 울린다.
 * 받으면 할 말을 한국어 음성으로 읽어 준다. 진짜 통화 중이면 방해하지 않고 보통 알림으로 대신한다.
 */
object VoiceCall {
    const val EXTRA_TITLE = "title"
    const val EXTRA_SPEECH = "speech"
    const val EXTRA_EVENT = "event"
    const val ID_CALL = 5

    fun ring(context: Context, title: String, speech: String, eventId: Long = -1) {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) {
            Notifier.simple(context, title, speech, ID_CALL)
            return
        }
        val intent = Intent(context, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_SPEECH, speech)
            .putExtra(EXTRA_EVENT, eventId)
        val full = PendingIntent.getActivity(context, 77, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val answer = PendingIntent.getActivity(context, 78, Intent(intent).putExtra(CallActivity.EXTRA_ANSWER, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(context, Notifier.CH_CALL)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentTitle("곁비서 전화")
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
        // 화면이 켜져 있고 앱을 쓰는 중이면 전체 화면 알림이 위에 작은 알림으로만 뜨므로, 그때는 화면을 직접 연다.
        runCatching { if (isForeground()) context.startActivity(intent) }
    }

    /** 안드로이드 14+ 는 '전체 화면 알림' 허용이 따로 필요하다 (설정에서 켜기). */
    fun canFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID_CALL)

    @Volatile var foregroundActivities = 0
    private fun isForeground() = foregroundActivities > 0
}
