package io.github.jgg113.gyeot.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.jgg113.gyeot.data.SavedEvent
import java.time.LocalDate
import java.time.ZoneId

/**
 * 일정마다 두 번 깨운다: 미리 알림(N분 전)과 정각 알림. 하루 요약도 여기서 매일 예약한다.
 * 정확한 시각 알람(USE_EXACT_ALARM)을 쓰므로 절전 중에도 제때 울린다.
 */
class AlarmScheduler(private val context: Context) {
    private val am = context.getSystemService(AlarmManager::class.java)

    fun schedule(e: SavedEvent) {
        val now = System.currentTimeMillis()
        val early = e.startMillis - e.reminderMinutes * 60_000L
        // 종일 일정은 그날 아침 8시에 한 번만 알린다.
        if (e.allDay) {
            val morning = e.startMillis + 8 * 60 * 60 * 1000L
            if (morning > now) set(morning, pending(e.id, ReminderReceiver.KIND_START))
            return
        }
        if (e.reminderMinutes > 0 && early > now) set(early, pending(e.id, ReminderReceiver.KIND_EARLY))
        if (e.startMillis > now) set(e.startMillis, pending(e.id, ReminderReceiver.KIND_START))
    }

    /** 전화 화면의 [10분 뒤 다시]: 그 일정의 미리 알림을 몇 분 뒤로 다시 건다. */
    fun snooze(eventId: Long, minutes: Int) =
        set(System.currentTimeMillis() + minutes * 60_000L, pending(eventId, ReminderReceiver.KIND_EARLY))

    fun cancel(eventId: Long) {
        am.cancel(pending(eventId, ReminderReceiver.KIND_EARLY))
        am.cancel(pending(eventId, ReminderReceiver.KIND_START))
    }

    /** 다음 하루 요약 시각을 예약한다. hour < 0 이면 끈다. */
    fun scheduleDigest(hour: Int) = daily(hour, DIGEST_CODE, ReminderReceiver.KIND_DIGEST)

    /** 다음 아침 브리핑 시각을 예약한다. hour < 0 이면 끈다. */
    fun scheduleBriefing(hour: Int) = daily(hour, BRIEFING_CODE, ReminderReceiver.KIND_BRIEFING)

    private fun daily(hour: Int, code: Int, kind: String) {
        val pi = PendingIntent.getBroadcast(
            context, code,
            Intent(context, ReminderReceiver::class.java).setAction("gyeot.daily.$kind").putExtra(ReminderReceiver.EXTRA_KIND, kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pi)
        if (hour < 0) return
        val zone = ZoneId.of("Asia/Seoul")
        var at = LocalDate.now(zone).atTime(hour, 0).atZone(zone)
        if (!at.toInstant().isAfter(java.time.Instant.now())) at = at.plusDays(1)
        // 요약·브리핑은 몇 분 늦어도 괜찮으니 정확 알람을 쓰지 않는다.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi)
    }

    private fun set(at: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pending(eventId: Long, kind: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        ((eventId % 1_000_000).toInt() * 2) + if (kind == ReminderReceiver.KIND_START) 1 else 0,
        Intent(context, ReminderReceiver::class.java)
            .setAction("gyeot.remind.$kind.$eventId")
            .putExtra(ReminderReceiver.EXTRA_KIND, kind)
            .putExtra(ReminderReceiver.EXTRA_EVENT, eventId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val DIGEST_CODE = 42
        private const val BRIEFING_CODE = 43
    }
}
