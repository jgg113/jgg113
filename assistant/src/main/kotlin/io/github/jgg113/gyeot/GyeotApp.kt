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
        repairOldNotes(g)
    }

    /**
     * 1.0.4 까지 휴대폰 안 요약 AI 가 남긴 노트는 깨진 글자("Ġì¹ľ")와 지어낸 문장이 섞여 있어,
     * 받아쓴 원문에서 규칙으로 한 번 다시 정리한다 (원문은 그대로 둔다).
     */
    private fun repairOldNotes(g: Graph) {
        val prefs = getSharedPreferences("gyeot", MODE_PRIVATE)
        if (prefs.getBoolean("notes_rebuilt_v2", false)) return
        Thread {
            runCatching { g.store.rewriteNotes { n -> g.rebuildNote(n) } }
            prefs.edit().putBoolean("notes_rebuilt_v2", true).apply()
        }.start()
    }
}
