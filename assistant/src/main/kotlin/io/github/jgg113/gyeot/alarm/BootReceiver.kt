package io.github.jgg113.gyeot.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.audio.ListeningService

/**
 * 재부팅·앱 업데이트 뒤: 일정 알람을 다시 걸고, 듣기를 켜 두었으면 다시 켠다.
 * 안드로이드 14 이상은 부팅 직후 마이크 서비스를 못 켜게 막을 수 있어, 그때는 '눌러서 다시 듣기' 알림을 띄운다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = Graph.get(context)
        g.store.upcomingEvents().filter { it.status == "registered" }.forEach { g.alarms.schedule(it) }
        g.alarms.scheduleDigest(g.settings.digestHour)
        if (!g.settings.listeningWanted) return
        val started = runCatching { ListeningService.start(context) }.isSuccess
        if (!started) {
            Notifier.simple(context, "곁비서 듣기가 꺼져 있어요", "휴대폰이 다시 켜졌어요. 눌러서 앱을 열면 다시 듣기 시작합니다.", Notifier.ID_RESUME)
        }
    }
}
