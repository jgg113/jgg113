package io.github.jgg113.gyeot

import android.app.Application
import io.github.jgg113.gyeot.alarm.Notifier

class GyeotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        val g = Graph.get(this)
        g.alarms.scheduleDigest(g.settings.digestHour)
        g.alarms.scheduleBriefing(g.settings.briefingHour)
    }
}
