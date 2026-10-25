package io.github.jgg113.gyeot.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import io.github.jgg113.gyeot.R
import io.github.jgg113.gyeot.data.Note
import io.github.jgg113.gyeot.data.SavedEvent
import io.github.jgg113.gyeot.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** 알림 채널과 알림 모양을 한곳에 모은다. */
object Notifier {
    const val CH_LISTEN = "listen"
    const val CH_EVENT = "event"
    const val CH_REMIND = "remind"
    const val CH_NOTE = "note"
    const val CH_ASK = "ask"
    const val CH_BRIEF = "brief"
    const val CH_CALL = "voice_call"

    const val ID_LISTEN = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_LISTEN, "듣는 중 표시", NotificationManager.IMPORTANCE_LOW).apply {
                description = "듣기가 켜져 있는 동안 늘 떠 있는 알림"
                setShowBadge(false)
            },
            NotificationChannel(CH_EVENT, "일정 등록", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "대화에서 일정을 찾아 캘린더에 넣었을 때"
            },
            NotificationChannel(CH_REMIND, "일정 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "일정 시각이 다가왔을 때"
                enableVibration(true)
            },
            NotificationChannel(CH_NOTE, "중요한 말·하루 요약", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "놓치면 안 되는 이야기, 하루 정리"
            },
            NotificationChannel(CH_ASK, "일정 추가할까요?", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "대화에서 일정 같은 말을 들었을 때 캘린더·알림에 넣을지 묻기"
            },
            NotificationChannel(CH_BRIEF, "일정 브리핑", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "아침마다 오늘 일정 요약"
            },
            NotificationChannel(CH_CALL, "수빈이 전화", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "일정·브리핑을 전화처럼 걸어 목소리로 알려 주기"
                setSound(null, null) // 벨소리는 전화 화면이 직접 울린다
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        ))
    }

    fun canPost(context: Context) =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun nm(context: Context) = context.getSystemService(NotificationManager::class.java)

    fun openApp(context: Context, tab: Int = 0): PendingIntent = PendingIntent.getActivity(
        context, 100 + tab,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 일정 알림을 누르면 그 일정의 근거 화면을 연다. */
    fun openEvent(context: Context, eventId: Long): PendingIntent = PendingIntent.getActivity(
        context, 200_000 + (eventId % 100_000).toInt(),
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_EVENT, eventId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(context: Context, action: String, eventId: Long, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, ActionReceiver::class.java).setAction(action).putExtra(ActionReceiver.EXTRA_EVENT, eventId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 삼성 시계 앱에 알람을 넣는 화면 (알림 버튼에서 바로 연다 — 앱이 뒤에 있을 때는 직접 못 띄운다). */
    private fun clockAlarm(context: Context, e: SavedEvent, code: Int): PendingIntent {
        val t = Instant.ofEpochMilli(e.startMillis - e.reminderMinutes * 60_000L).atZone(ZoneId.of("Asia/Seoul"))
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, t.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, e.title)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun listening(context: Context, status: String, paused: Boolean): Notification {
        val toggle = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, ActionReceiver::class.java).setAction(if (paused) ActionReceiver.RESUME else ActionReceiver.PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context, 3,
            Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CH_LISTEN)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentTitle(if (paused) "수빈이 잠시 멈춤" else "수빈이가 듣는 중")
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(Notification.Action.Builder(null, if (paused) "다시 듣기" else "잠시 멈춤", toggle).build())
            .addAction(Notification.Action.Builder(null, "끄기", stop).build())
            .build()
    }

    fun eventRegistered(context: Context, e: SavedEvent, calendarName: String?) {
        if (!canPost(context)) return
        val b = Notification.Builder(context, CH_EVENT)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentTitle("일정 등록: ${e.title}")
            .setContentText(describe(e) + " · 눌러서 근거 보기")
            .setStyle(Notification.BigTextStyle().bigText(buildString {
                append(describe(e))
                if (e.location.isNotBlank()) append("\n장소: ${e.location}")
                if (e.evidence.isNotBlank() && detailed(context)) append("\n들은 말: \"${e.evidence}\"")
                if (calendarName != null) append("\n→ $calendarName 에 넣었어요")
            }))
            .setContentIntent(openEvent(context, e.id))
            .setAutoCancel(true)
        val code = (e.id % 100_000).toInt() * 10
        b.addAction(Notification.Action.Builder(null, "근거 보기", openEvent(context, e.id)).build())
        b.addAction(Notification.Action.Builder(null, "취소", action(context, ActionReceiver.CANCEL, e.id, code + 2)).build())
        if (e.startMillis - System.currentTimeMillis() in 0..CLOCK_ALARM_WINDOW_MS && !e.allDay) {
            b.addAction(Notification.Action.Builder(null, "시계 알람도", clockAlarm(context, e, code + 3)).build())
        }
        nm(context).notify(eventNotificationId(e.id), b.build())
    }

    /**
     * "캘린더에 추가할까요?" — 대화에서 일정 같은 말을 들었는데 확실하지 않을 때 묻는다.
     * [캘린더에 추가] [알림만] [아니요], 눌러서 열면 날짜·시각을 고칠 수 있다.
     */
    fun suggestion(context: Context, e: SavedEvent, replaces: SavedEvent?) {
        if (!canPost(context)) return
        val code = (e.id % 100_000).toInt() * 10
        val title = when {
            replaces != null -> "일정을 옮길까요? ${e.title}"
            e.timeUnknown -> "챙겨 드릴까요? ${e.title}"
            else -> "캘린더에 추가할까요? ${e.title}"
        }
        val whenText = when {
            replaces != null -> "${describe(replaces)} → ${describe(e)}"
            e.timeUnknown -> "언제인지는 못 들었어요. 누르면 날짜를 정할 수 있어요"
            else -> describe(e)
        }
        val b = Notification.Builder(context, CH_ASK)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentTitle(title)
            .setContentText(whenText)
            .setStyle(Notification.BigTextStyle().bigText(buildString {
                append(whenText)
                if (e.location.isNotBlank()) append("\n장소: ${e.location}")
                if (e.evidence.isNotBlank()) append("\n들은 말: \"${e.evidence}\"")
                if (e.mediaLikely) append("\n📺 TV·영상 소리일 수 있어 바로 넣지 않았어요")
                append("\n눌러서 근거·앞뒤 대화 보기")
            }))
            .setContentIntent(openEvent(context, e.id))
            .setAutoCancel(true)
        if (e.timeUnknown) {
            b.addAction(Notification.Action.Builder(null, "내일 아침 9시에 알림", action(context, ActionReceiver.REMIND_ONLY, e.id, code + 5)).build())
        } else {
            b.addAction(Notification.Action.Builder(null, "캘린더에 추가", action(context, ActionReceiver.ADD_CALENDAR, e.id, code + 4)).build())
            b.addAction(Notification.Action.Builder(null, "알림만", action(context, ActionReceiver.REMIND_ONLY, e.id, code + 5)).build())
        }
        b.addAction(Notification.Action.Builder(null, "아니요", action(context, ActionReceiver.DISMISS, e.id, code + 6)).build())
        nm(context).notify(eventNotificationId(e.id), b.build())
    }

    fun reminderSet(context: Context, e: SavedEvent) {
        if (!canPost(context)) return
        val n = Notification.Builder(context, CH_EVENT)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentTitle("알림 맞춤: ${e.title}")
            .setContentText("${describe(e)} · ${if (e.allDay) "그날 아침 8시" else "${e.reminderMinutes}분 전과 정각"}에 알려 드려요")
            .setContentIntent(openEvent(context, e.id))
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "취소", action(context, ActionReceiver.CANCEL, e.id, (e.id % 100_000).toInt() * 10 + 2)).build())
            .build()
        nm(context).notify(eventNotificationId(e.id), n)
    }

    /** 아침 브리핑: 오늘 일정 + 대답 기다리는 제안 + 오늘 기한 할 일. */
    fun briefing(context: Context, title: String, lines: List<String>) {
        if (!canPost(context) || lines.isEmpty()) return
        val style = Notification.InboxStyle()
        lines.take(7).forEach { style.addLine(it) }
        if (lines.size > 7) style.setSummaryText("외 ${lines.size - 7}개")
        val n = Notification.Builder(context, CH_BRIEF)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        nm(context).notify(ID_BRIEFING, n)
    }

    private fun detailed(context: Context) = io.github.jgg113.gyeot.Graph.get(context).settings.detailed

    fun reminder(context: Context, e: SavedEvent, atStart: Boolean) {
        if (!canPost(context)) return
        val left = ((e.startMillis - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val title = if (atStart || left <= 1) "지금: ${e.title}" else "${left}분 뒤: ${e.title}"
        val n = Notification.Builder(context, CH_REMIND)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentTitle(title)
            .setContentText(listOf(describe(e), e.location).filter { it.isNotBlank() }.joinToString(" · "))
            .setStyle(Notification.BigTextStyle().bigText(buildString {
                append(describe(e))
                if (e.location.isNotBlank()) append("\n장소: ${e.location}")
                if (e.evidence.isNotBlank()) append("\n들은 말: \"${e.evidence}\"")
            }))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openEvent(context, e.id))
            .setAutoCancel(true)
            .build()
        nm(context).notify(eventNotificationId(e.id) + if (atStart) 2 else 1, n)
    }

    fun important(context: Context, note: Note) {
        if (!canPost(context)) return
        val lines = note.keyPoints + note.todos.map { "할 일: ${it.text}" + if (it.due.isNotBlank()) " (${it.due.replace('T', ' ')})" else "" }
        val n = Notification.Builder(context, CH_NOTE)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentTitle("놓치면 안 될 이야기")
            .setContentText(note.summary)
            .setStyle(Notification.BigTextStyle().bigText(note.summary + if (lines.isEmpty()) "" else "\n• " + lines.joinToString("\n• ")))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        nm(context).notify((note.id % 1_000_000).toInt() + 3_000_000, n)
    }

    fun digest(context: Context, notes: List<Note>, events: List<SavedEvent>) {
        if (!canPost(context)) return
        val points = notes.flatMap { it.keyPoints }.distinct()
        val todos = notes.flatMap { it.todos }.map { it.text }.distinct()
        if (points.isEmpty() && todos.isEmpty() && events.isEmpty()) return
        val style = Notification.InboxStyle()
        events.take(4).forEach { style.addLine("📅 ${describe(it)} ${it.title}") }
        todos.take(4).forEach { style.addLine("✔ $it") }
        points.take(6).forEach { style.addLine("• $it") }
        val n = Notification.Builder(context, CH_NOTE)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentTitle("오늘 정리: 일정 ${events.size} · 할 일 ${todos.size} · 핵심 ${points.size}")
            .setContentText(points.firstOrNull() ?: todos.firstOrNull() ?: events.first().title)
            .setStyle(style)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        nm(context).notify(ID_DIGEST, n)
    }

    fun callSummary(context: Context, note: Note, who: String, events: Int) {
        if (!canPost(context)) return
        val lines = note.keyPoints + note.todos.map { "할 일: ${it.text}" + if (it.due.isNotBlank()) " (${it.due.replace('T', ' ')})" else "" }
        val n = Notification.Builder(context, CH_NOTE)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentTitle("📞 $who 통화 정리" + if (events > 0) " · 일정 $events" else "" + if (note.todos.isNotEmpty()) " · 할 일 ${note.todos.size}" else "")
            .setContentText(note.summary.substringAfter(" · ", note.summary))
            .setStyle(Notification.BigTextStyle().bigText(note.summary + if (lines.isEmpty()) "" else "\n• " + lines.joinToString("\n• ")))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        nm(context).notify((note.id % 1_000_000).toInt() + 4_000_000, n)
    }

    fun simple(context: Context, title: String, text: String, id: Int) {
        if (!canPost(context)) return
        val n = Notification.Builder(context, CH_NOTE)
            .setSmallIcon(R.drawable.ic_ear)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        nm(context).notify(id, n)
    }

    fun cancelEvent(context: Context, eventId: Long) = nm(context).cancel(eventNotificationId(eventId))

    fun describe(e: SavedEvent): String {
        val t = Instant.ofEpochMilli(e.startMillis).atZone(ZoneId.of("Asia/Seoul"))
        val day = t.format(DateTimeFormatter.ofPattern("M월 d일")) + "(" + t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN) + ")"
        if (e.allDay) return "$day 종일"
        val h = t.hour
        val ampm = if (h < 12) "오전" else "오후"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$day $ampm $h12:${"%02d".format(t.minute)}"
    }

    private fun eventNotificationId(eventId: Long) = (eventId % 1_000_000).toInt() * 4 + 10_000_000
    const val ID_DIGEST = 9
    const val ID_BRIEFING = 6
    const val ID_RESUME = 8
    const val ID_ERROR = 7
    private const val CLOCK_ALARM_WINDOW_MS = 23L * 60 * 60 * 1000
}
