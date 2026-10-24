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

/**
 * 뽑힌 일정 → (중복·확신도 검사) → 바로 등록하거나 "추가할까요?" 하고 묻기.
 * 묻는 일정은 알림 버튼(캘린더에 추가 / 알림만 / 아니요)이나 오늘 탭에서 답한다.
 */
class EventRegistrar(
    private val context: Context,
    private val store: Store,
    private val settings: Settings,
    private val calendar: CalendarWriter,
    private val alarms: AlarmScheduler,
) {
    private val ids = AtomicLong(System.currentTimeMillis())

    /** @return 저장한(등록했거나 물어본) 일정 id 들 */
    @Synchronized
    fun handle(found: List<ExtractedEvent>, now: ZonedDateTime = ZonedDateTime.now(EventRules.SEOUL), mode: String = settings.addMode): List<Long> {
        val saved = mutableListOf<Long>()
        for (e in found) {
            val timing = EventRules.timing(e) ?: continue
            val decision = EventRules.decide(e, timing, now, mode)
            if (decision == EventRules.Decision.DROP) continue
            val startMillis = timing.start.toInstant().toEpochMilli()
            // 이미 넣었거나, 물어봤거나, "아니요"라고 한 일정은 다시 묻지 않는다.
            val recent = store.recentEvents()
            if (e.timeUnknown) {
                if (recent.any { EventRules.isDuplicate(e.title, it.startMillis, listOf(it.title to it.startMillis)) || (it.timeUnknown && EventRules.isDuplicate(e.title, 0, listOf(it.title to 0L))) }) continue
            } else if (EventRules.isDuplicate(e.title, startMillis, recent.map { it.title to it.startMillis })) continue
            val event = SavedEvent(
                id = ids.incrementAndGet(),
                title = e.title.trim(),
                startMillis = startMillis,
                endMillis = timing.end.toInstant().toEpochMilli(),
                allDay = timing.allDay,
                location = e.location.trim(),
                reminderMinutes = if (e.reminderMinutesBefore > 0) e.reminderMinutesBefore else settings.reminderMinutes,
                evidence = e.evidence.trim(),
                status = SavedEvent.SUGGESTED,
                timeUnknown = e.timeUnknown,
            )
            // "회의 4시로 미뤄졌어": 며칠 안쪽의 비슷한 제목 일정은 새 시각으로 옮긴다 (바로 넣을 때만 예전 것을 지운다).
            val replaced = if (e.isChange) store.upcomingEvents().filter {
                it.startMillis != startMillis && Math.abs(it.startMillis - startMillis) <= CHANGE_WINDOW_MS &&
                    EventRules.isDuplicate(e.title, it.startMillis, listOf(it.title to it.startMillis))
            } else emptyList()
            saved += if (decision == EventRules.Decision.REGISTER) {
                replaced.forEach { cancel(it.id) }
                register(event.copy(status = SavedEvent.REGISTERED))
            } else {
                store.upsertEvent(event)
                Notifier.suggestion(context, event, replaced.firstOrNull())
                event.id
            }
        }
        return saved
    }

    /** [캘린더에 추가] — 묻던 일정을 캘린더에 넣는다. 시각이 바뀌는 말이었으면 예전 것을 지운다. */
    fun addToCalendar(eventId: Long) {
        val e = store.event(eventId) ?: return
        if (e.active && e.calendarEventId != null) return
        replaceOld(e)
        alarms.cancel(e.id)
        register(e.copy(status = SavedEvent.REGISTERED, timeUnknown = false))
    }

    /** [알림만] — 캘린더에는 넣지 않고 앱이 그 시각에 알려 준다. */
    fun remindOnly(eventId: Long) {
        val e = store.event(eventId) ?: return
        replaceOld(e)
        val stored = e.copy(status = SavedEvent.REMINDER, timeUnknown = false)
        store.upsertEvent(stored)
        alarms.schedule(stored)
        Notifier.reminderSet(context, stored)
    }

    /** [아니요] — 다시 묻지 않는다. */
    fun dismiss(eventId: Long) {
        val e = store.event(eventId) ?: return
        store.upsertEvent(e.copy(status = SavedEvent.DISMISSED))
        Notifier.cancelEvent(context, e.id)
    }

    /** 오늘 탭에서 날짜·시각을 고쳐 정한 뒤 넣기. */
    fun reschedule(eventId: Long, startMillis: Long, allDay: Boolean, toCalendar: Boolean) {
        val e = store.event(eventId) ?: return
        val length = if (allDay) 24L * 60 * 60 * 1000 else (e.endMillis - e.startMillis).takeIf { it > 0 && !e.allDay } ?: 60L * 60 * 1000
        e.calendarEventId?.let { calendar.delete(it) }
        alarms.cancel(e.id)
        val moved = e.copy(startMillis = startMillis, endMillis = startMillis + length, allDay = allDay, calendarEventId = null, timeUnknown = false)
        store.upsertEvent(moved)
        if (toCalendar) addToCalendar(moved.id) else remindOnly(moved.id)
    }

    /** 이미 넣은 일정을 지운다 (캘린더·알람 모두). */
    fun cancel(eventId: Long) {
        val e = store.event(eventId) ?: return
        e.calendarEventId?.let { calendar.delete(it) }
        alarms.cancel(e.id)
        store.upsertEvent(e.copy(status = SavedEvent.CANCELLED))
        Notifier.cancelEvent(context, e.id)
    }

    /** 이전 버전 이름 (알림 버튼 호환). */
    fun confirm(eventId: Long) = addToCalendar(eventId)

    private fun replaceOld(e: SavedEvent) {
        if (!CHANGE.containsMatchIn(e.evidence)) return
        store.upcomingEvents().filter {
            it.id != e.id && it.startMillis != e.startMillis && Math.abs(it.startMillis - e.startMillis) <= CHANGE_WINDOW_MS &&
                EventRules.isDuplicate(e.title, it.startMillis, listOf(it.title to it.startMillis))
        }.forEach { cancel(it.id) }
    }

    private fun register(event: SavedEvent): Long {
        val cal = calendar.resolveCalendar(settings.calendarId)
        val calId = cal?.let { calendar.insert(event, it.id) }
        val stored = event.copy(calendarEventId = calId, status = if (calId != null) SavedEvent.REGISTERED else SavedEvent.REMINDER)
        store.upsertEvent(stored)
        alarms.schedule(stored)
        Notifier.eventRegistered(context, stored, cal?.label ?: "앱 알림으로만 (캘린더 권한 없음)")
        return stored.id
    }

    private companion object {
        const val CHANGE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        val CHANGE = Regex("미뤄|미루|당겨|당기|바뀌|바꿔|변경|옮겨|연기")
    }
}
