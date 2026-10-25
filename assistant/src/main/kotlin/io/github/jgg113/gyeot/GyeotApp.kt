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
        removeSelfHeardEvents(g)
    }

    /**
     * 1.0.9 까지는 수빈이가 스피커로 한 말(목소리 미리 듣기 "오늘 오후 3시에 민수 약속이 있어요", 전화 안내)을
     * 듣기 기능이 다시 받아써 일정으로 넣을 수 있었다. 그렇게 들어간 일정을 한 번 찾아 지운다.
     */
    private fun removeSelfHeardEvents(g: Graph) {
        val prefs = getSharedPreferences("gyeot", MODE_PRIVATE)
        if (prefs.getBoolean("self_echo_cleaned_v1", false)) return
        Thread {
            runCatching {
                val echo = Regex("수빈이(에|예)요|곁비서(에|예)요|오늘 오후 3시에 민수 약속이 있어요|캘린더에 넣었어요|여쭤볼 일정")
                g.store.events().filter { (it.active || it.asking) && echo.containsMatchIn(it.evidence) }
                    .forEach { if (it.asking) g.events.dismiss(it.id) else g.events.cancel(it.id, wrong = true) }
            }
            prefs.edit().putBoolean("self_echo_cleaned_v1", true).apply()
        }.start()
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
