package io.github.jgg113.gyeot

import android.app.Application
import io.github.jgg113.gyeot.ai.KoreanText
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

    /** 1.0.4 까지의 휴대폰 안 요약 AI 가 남긴 깨진 글자("Ġë¹ł", 한자)를 한 번 고치고, 못 고치는 줄은 뺀다. */
    private fun repairOldNotes(g: Graph) {
        val prefs = getSharedPreferences("gyeot", MODE_PRIVATE)
        if (prefs.getBoolean("notes_repaired_v1", false)) return
        Thread {
            runCatching {
                g.store.rewriteNotes { n ->
                    fun fix(t: String) = KoreanText.fixByteLevel(t).takeIf { KoreanText.isCleanKorean(it) }
                    n.copy(
                        summary = fix(n.summary) ?: n.summary.substringBefore(" · ").takeIf { KoreanText.isCleanKorean(it) }.orEmpty().ifBlank { "정리 내용을 읽을 수 없어 지웠어요 (받아쓴 원문은 기록 탭에 있어요)" },
                        keyPoints = n.keyPoints.mapNotNull { fix(it) },
                        todos = n.todos.mapNotNull { t -> fix(t.text)?.let { t.copy(text = it) } },
                    )
                }
            }
            prefs.edit().putBoolean("notes_repaired_v1", true).apply()
        }.start()
    }
}
