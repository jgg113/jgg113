package io.github.jgg113.gyeot.calendar

import android.content.Context
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.ai.ExtractedEvent
import io.github.jgg113.gyeot.alarm.AlarmScheduler
import io.github.jgg113.gyeot.alarm.Notifier
import io.github.jgg113.gyeot.data.SavedEvent
import io.github.jgg113.gyeot.data.Settings
import io.github.jgg113.gyeot.data.Store
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong

/** 뽑힌 일정 → (중복·확신도 검사) → 캘린더 등록 + 알람 예약 + 알림. 취소·확인도 여기서. */
class EventRegistrar(
    private val context: Context,
    private val store: Store,
    private val settings: Settings,
    private val calendar: CalendarWriter,
    private val alarms: AlarmScheduler,
) {
    private val ids = AtomicLong(System.currentTimeMillis())

    /** @return 저장한 일정 id 들 */
    @Synchronized
    fun handle(found: List<ExtractedEvent>, now: ZonedDateTime = ZonedDateTime.now(EventRules.SEOUL)): List<Long> {
        val saved = mutableListOf<Long>()
        for (e in found) {
            val timing = EventRules.timing(e) ?: continue
            if (!EventRules.shouldRegister(e, timing, now)) continue
            val startMillis = timing.start.toInstant().toEpochMilli()
            // "회의 4시로 미뤄졌어": 며칠 안쪽의 비슷한 제목 일정을 지우고 새 시각으로 넣는다.
            if (e.isChange) {
                store.upcomingEvents().filter {
                    it.startMillis != startMillis && Math.abs(it.startMillis - startMillis) <= CHANGE_WINDOW_MS &&
                        EventRules.isDuplicate(e.title, it.startMillis, listOf(it.title to it.startMillis))
                }.forEach { cancel(it.id) }
            }
            val existing = store.upcomingEvents().map { it.title to it.startMillis }
            if (EventRules.isDuplicate(e.title, startMillis, existing)) continue
            val event = SavedEvent(
                id = ids.incrementAndGet(),
                title = e.title.trim(),
                startMillis = startMillis,
                endMillis = timing.end.toInstant().toEpochMilli(),
                allDay = timing.allDay,
                location = e.location.trim(),
                reminderMinutes = if (e.reminderMinutesBefore > 0) e.reminderMinutesBefore else settings.reminderMinutes,
                evidence = e.evidence.trim(),
                status = if (settings.autoRegister) "registered" else "pending",
            )
            saved += if (event.status == "registered") register(event) else {
                store.upsertEvent(event)
                Notifier.eventRegistered(context, event, null)
                event.id
            }
        }
        return saved
    }

    fun confirm(eventId: Long) {
        val e = store.event(eventId) ?: return
        if (e.status == "pending") register(e.copy(status = "registered"))
    }

    fun cancel(eventId: Long) {
        val e = store.event(eventId) ?: return
        e.calendarEventId?.let { calendar.delete(it) }
        alarms.cancel(e.id)
        store.upsertEvent(e.copy(status = "cancelled"))
        Notifier.cancelEvent(context, e.id)
    }

    private companion object {
        const val CHANGE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    }

    private fun register(event: SavedEvent): Long {
        val cal = calendar.resolveCalendar(settings.calendarId)
        val calId = cal?.let { calendar.insert(event, it.id) }
        val stored = event.copy(calendarEventId = calId)
        store.upsertEvent(stored)
        alarms.schedule(stored)
        Notifier.eventRegistered(context, stored, cal?.label ?: "앱 안 (캘린더 권한 없음)")
        return stored.id
    }
}
