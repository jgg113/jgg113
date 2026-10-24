package io.github.jgg113.gyeot.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import io.github.jgg113.gyeot.data.SavedEvent

/**
 * 휴대폰 캘린더(CalendarContract)에 일정을 넣는다. 삼성 캘린더 앱의 '삼성 계정' 캘린더도
 * 여기에 그대로 보이므로, 넣은 일정은 삼성 클라우드로 동기화되고 삼성 캘린더가 미리 알림을 띄운다.
 */
class CalendarWriter(private val context: Context) {

    data class CalendarInfo(val id: Long, val name: String, val account: String, val accountType: String) {
        val isSamsung get() = accountType == SAMSUNG_ACCOUNT_TYPE
        val label get() = when {
            isSamsung -> "삼성 계정 · $name"
            accountType == "com.google" -> "Google · $name ($account)"
            accountType == CalendarContract.ACCOUNT_TYPE_LOCAL -> "내 휴대폰 · $name"
            else -> "$name ($account)"
        }
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 일정을 쓸 수 있는 캘린더 목록 (삼성 계정 먼저). */
    fun writableCalendars(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val out = mutableListOf<CalendarInfo>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty())
            }
        }
        return out.sortedWith(compareByDescending<CalendarInfo> { it.isSamsung }.thenByDescending { it.accountType == "com.google" })
    }

    /** 설정에서 고른 캘린더, 없으면 삼성 계정 → Google → 아무거나 순. */
    fun resolveCalendar(preferredId: Long): CalendarInfo? {
        val all = writableCalendars()
        return all.firstOrNull { it.id == preferredId } ?: all.firstOrNull()
    }

    /** @return 캘린더 이벤트 id */
    fun insert(event: SavedEvent, calendarId: Long): Long? {
        if (!hasPermission()) return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, buildString {
                if (event.evidence.isNotBlank()) append("들은 말: \"${event.evidence}\"\n")
                append("곁비서가 대화에서 찾아 넣은 일정입니다.")
            })
            if (event.location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, event.location)
            put(CalendarContract.Events.DTSTART, event.startMillis)
            put(CalendarContract.Events.DTEND, event.endMillis)
            if (event.allDay) {
                // 종일 일정은 UTC 자정 기준이어야 날짜가 밀리지 않는다.
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                put(CalendarContract.Events.DTSTART, utcMidnight(event.startMillis))
                put(CalendarContract.Events.DTEND, utcMidnight(event.startMillis) + DAY_MS)
            } else {
                put(CalendarContract.Events.EVENT_TIMEZONE, "Asia/Seoul")
            }
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        val id = ContentUris.parseId(uri)
        val reminder = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, id)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            put(CalendarContract.Reminders.MINUTES, event.reminderMinutes)
        }
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder)
        return id
    }

    /** 기간 안의 모든 캘린더 일정 (삼성 캘린더에 직접 넣은 것 포함, 반복 일정은 펼쳐서). */
    fun instances(fromMillis: Long, toMillis: Long): List<io.github.jgg113.gyeot.ai.ScheduleSummary.Item> {
        if (!hasPermission()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .let { ContentUris.appendId(it, fromMillis); ContentUris.appendId(it, toMillis); it.build() }
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = mutableListOf<io.github.jgg113.gyeot.ai.ScheduleSummary.Item>()
        runCatching {
            context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(3) == 1
                    var begin = c.getLong(1)
                    var end = c.getLong(2)
                    if (allDay) {
                        // 종일 일정은 UTC 자정으로 저장돼 있다 → 한국 날짜의 0시로 바꾼다.
                        val day = java.time.Instant.ofEpochMilli(begin).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        begin = day.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
                        end = begin + DAY_MS
                    }
                    out += io.github.jgg113.gyeot.ai.ScheduleSummary.Item(c.getString(0).orEmpty().ifBlank { "(제목 없음)" }, begin, end, allDay, c.getString(4).orEmpty())
                }
            }
        }
        return out
    }

    fun delete(calendarEventId: Long): Boolean {
        if (!hasPermission()) return false
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, calendarEventId)
        return context.contentResolver.delete(uri, null, null) > 0
    }

    private fun utcMidnight(seoulMillis: Long): Long {
        val day = java.time.Instant.ofEpochMilli(seoulMillis).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()
        return day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    companion object {
        /** 삼성 계정 캘린더의 계정 종류. */
        const val SAMSUNG_ACCOUNT_TYPE = "com.osp.app.signin"
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
