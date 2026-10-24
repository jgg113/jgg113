package io.github.jgg113.gyeot.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.audio.ListeningService

/** 알림의 버튼들: 캘린더에 추가/알림만/아니요/취소, 듣기 멈춤/재개/끄기. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = Graph.get(context)
        val eventId = intent.getLongExtra(EXTRA_EVENT, -1)
        when (intent.action) {
            REGISTER, ADD_CALENDAR -> g.events.addToCalendar(eventId)
            REMIND_ONLY -> g.events.remindOnly(eventId)
            DISMISS -> g.events.dismiss(eventId)
            CANCEL -> g.events.cancel(eventId)
            PAUSE -> ListeningService.pause(context, true)
            RESUME -> ListeningService.pause(context, false)
            STOP -> ListeningService.stop(context)
        }
    }

    companion object {
        const val EXTRA_EVENT = "event"
        const val REGISTER = "io.github.jgg113.gyeot.REGISTER"
        const val CANCEL = "io.github.jgg113.gyeot.CANCEL"
        const val ADD_CALENDAR = "io.github.jgg113.gyeot.ADD_CALENDAR"
        const val REMIND_ONLY = "io.github.jgg113.gyeot.REMIND_ONLY"
        const val DISMISS = "io.github.jgg113.gyeot.DISMISS"
        const val PAUSE = "io.github.jgg113.gyeot.PAUSE"
        const val RESUME = "io.github.jgg113.gyeot.RESUME"
        const val STOP = "io.github.jgg113.gyeot.STOP"
    }
}
