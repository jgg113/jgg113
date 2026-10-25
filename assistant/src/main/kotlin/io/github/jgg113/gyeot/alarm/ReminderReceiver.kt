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
                // 캘린더에 넣은 일정은 '앱도 직접 알리기'를 켰을 때만, 알림만 맞춘 일정은 늘 알린다.
                if (!e.active || (e.status == io.github.jgg113.gyeot.data.SavedEvent.REGISTERED && !g.settings.ownReminders)) return
                val atStart = intent.getStringExtra(EXTRA_KIND) == KIND_START
                // 미리 알림(또는 종일 일정 아침 알림)은 전화처럼 걸어 목소리로 알려 준다. 정각 알림은 보통 알림.
                val callNow = g.settings.callForReminders && (!atStart || e.allDay || e.reminderMinutes <= 0)
                if (callNow) {
                    val speech = io.github.jgg113.gyeot.voice.VoiceScript.event(e.title, e.startMillis, e.allDay, e.location, e.evidence, System.currentTimeMillis(), g.settings.detailed)
                    io.github.jgg113.gyeot.voice.VoiceCall.ring(context, io.github.jgg113.gyeot.voice.VoiceCall.KIND_EVENT, "${Notifier.describe(e)} ${e.title}", speech, e.id)
                }
                Notifier.reminder(context, e, atStart = atStart)
            }
            KIND_DIGEST -> {
                val today = LocalDate.now(io.github.jgg113.gyeot.ai.EventRules.SEOUL)
                val notes = g.store.notes().filter { Store.dayOf(it.at) == today }
                val tomorrowEnd = today.plusDays(2).atStartOfDay(io.github.jgg113.gyeot.ai.EventRules.SEOUL).toInstant().toEpochMilli()
                val events = g.store.upcomingEvents().filter { it.startMillis < tomorrowEnd }
                Notifier.digest(context, notes, events)
                g.store.purgeOlderThan(g.settings.retentionDays)
                g.audio.purge(g.settings.keepAudioDays)
                g.alarms.scheduleDigest(g.settings.digestHour)
            }
            KIND_BRIEFING -> {
                val today = LocalDate.now(io.github.jgg113.gyeot.ai.EventRules.SEOUL)
                val items = g.scheduleItems(today, today)
                val lines = items.filter { it.source != "ask" }.sortedWith(compareBy<io.github.jgg113.gyeot.ai.ScheduleSummary.Item> { !it.allDay }.thenBy { it.start })
                    .map { io.github.jgg113.gyeot.ai.ScheduleSummary.line(it) }.toMutableList()
                g.store.notes().flatMap { it.todos }.filter { it.due.startsWith(today.toString()) }.map { "✔ ${it.text}" }.distinct().forEach { lines += it }
                val asking = g.store.suggestions().size
                if (asking > 0) lines += "❓ 추가할지 대답을 기다리는 일정 ${asking}개 — 눌러서 확인"
                if (lines.isNotEmpty()) Notifier.briefing(context, io.github.jgg113.gyeot.ai.ScheduleSummary.headline(items, today), lines)
                if (g.settings.callForBriefing) ringBriefing(context, g)
                g.alarms.scheduleBriefing(g.settings.briefingHour)
            }
            KIND_BRIEFING_CALL -> ringBriefing(context, g)
        }
    }

    /** 브리핑 전화: 할 말은 전화를 받을 때 그 순간 일정으로 만든다 (여기 글은 받지 못했을 때 남길 알림용). */
    private fun ringBriefing(context: Context, g: Graph) {
        val today = LocalDate.now(io.github.jgg113.gyeot.ai.EventRules.SEOUL)
        val items = g.scheduleItems(today, today)
        val todos = g.store.notes().flatMap { it.todos }.filter { it.due.startsWith(today.toString()) }.map { it.text }.distinct()
        val fallback = io.github.jgg113.gyeot.voice.VoiceScript.briefing(items, todos, g.store.suggestions().size, today, detailed = g.settings.detailed)
        io.github.jgg113.gyeot.voice.VoiceCall.ring(context, io.github.jgg113.gyeot.voice.VoiceCall.KIND_BRIEFING, io.github.jgg113.gyeot.ai.ScheduleSummary.headline(items, today), fallback)
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_EVENT = "event"
        const val KIND_EARLY = "early"
        const val KIND_START = "start"
        const val KIND_DIGEST = "digest"
        const val KIND_BRIEFING = "briefing"
        const val KIND_BRIEFING_CALL = "briefing_call"
    }
}
