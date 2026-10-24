package io.github.jgg113.gyeot

import android.content.Context
import io.github.jgg113.gyeot.ai.Analyzer
import io.github.jgg113.gyeot.alarm.AlarmScheduler
import io.github.jgg113.gyeot.calendar.CalendarWriter
import io.github.jgg113.gyeot.calendar.EventRegistrar
import io.github.jgg113.gyeot.data.Settings
import io.github.jgg113.gyeot.data.Store
import io.github.jgg113.gyeot.stt.Transcriber

/** 앱 전체에서 하나씩만 쓰는 것들. */
class Graph private constructor(context: Context) {
    val settings = Settings(context)
    val store = Store(context)
    val calendar = CalendarWriter(context)
    val alarms = AlarmScheduler(context)
    val events = EventRegistrar(context, store, settings, calendar, alarms)
    val transcriber = Transcriber(settings)
    val analyzer = Analyzer(settings)

    companion object {
        @Volatile private var instance: Graph? = null
        fun get(context: Context): Graph =
            instance ?: synchronized(this) { instance ?: Graph(context.applicationContext).also { instance = it } }
    }
}
