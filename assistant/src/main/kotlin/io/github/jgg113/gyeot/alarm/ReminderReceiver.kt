package io.github.jgg113.gyeot.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.data.Store
import java.time.LocalDate

/** 예약해 둔 시각에 깨어나 일정 알림·하루 요약을 띄운다. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = Graph.get(context)
        when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_EARLY, KIND_START -> {
                val e = g.store.event(intent.getLongExtra(EXTRA_EVENT, -1)) ?: return
                if (e.status != "registered" || !g.settings.ownReminders) return
                Notifier.reminder(context, e, atStart = intent.getStringExtra(EXTRA_KIND) == KIND_START)
            }
            KIND_DIGEST -> {
                val today = LocalDate.now(io.github.jgg113.gyeot.ai.EventRules.SEOUL)
                val notes = g.store.notes().filter { Store.dayOf(it.at) == today }
                val tomorrowEnd = today.plusDays(2).atStartOfDay(io.github.jgg113.gyeot.ai.EventRules.SEOUL).toInstant().toEpochMilli()
                val events = g.store.upcomingEvents().filter { it.status == "registered" && it.startMillis < tomorrowEnd }
                Notifier.digest(context, notes, events)
                g.store.purgeOlderThan(g.settings.retentionDays)
                g.alarms.scheduleDigest(g.settings.digestHour)
            }
        }
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_EVENT = "event"
        const val KIND_EARLY = "early"
        const val KIND_START = "start"
        const val KIND_DIGEST = "digest"
    }
}
