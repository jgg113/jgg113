package io.github.jgg113.gyeot.ai

import io.github.jgg113.gyeot.data.Note
import io.github.jgg113.gyeot.data.Utterance

/**
 * 받아쓴 말을 "대화 묶음"으로 나눈다: 5분 넘게 조용하면, 또는 통화와 곁의 대화가 바뀌면 새 묶음.
 * 기록 탭은 묶음마다 요약을 먼저 보여 주고, [전문 보기]로 받아쓴 줄 전체(눌러서 그때 소리 듣기)를 펼친다.
 */
object Sessions {
    data class Session(
        val from: Long,
        val to: Long,
        val lines: List<Utterance>,
        /** 통화 녹음이면 상대 이름 */
        val callWith: String?,
        /** 이 묶음을 정리한 노트들 (요약·핵심·할 일·일정) */
        val notes: List<Note>,
    ) {
        val key: String get() = "$from-${callWith.orEmpty()}"
        val mine: Int get() = lines.count { it.speaker == "me" }
        val others: Int get() = lines.count { it.speaker == "other" }
        val withAudio: List<Utterance> get() = lines.filter { it.audio.isNotBlank() }
        val tvLikely: Boolean get() = lines.count { it.media } * 2 > lines.size
    }

    private const val CALL_LEAD_MS = 3 * 60_000L

    /** 통화 녹음을 정리한 노트는 "📞 엄마 통화 (3분) · …" 로 시작한다. */
    fun isCallNote(n: Note): Boolean = n.summary.startsWith("📞")

    fun build(lines: List<Utterance>, notes: List<Note>, gapMs: Long = 5 * 60_000L): List<Session> {
        val sorted = lines.sortedBy { it.at }
        val groups = mutableListOf<MutableList<Utterance>>()
        for (u in sorted) {
            val cur = groups.lastOrNull()
            val last = cur?.last()
            if (cur == null || u.at - last!!.at > gapMs || u.callWith != last.callWith) groups += mutableListOf(u) else cur += u
        }
        val used = HashSet<Long>()
        return groups.map { g ->
            val from = g.first().at
            val to = g.last().at
            val call = g.first().callWith != null
            // 노트는 정리를 시작한 첫 말(from)이 이 묶음 안에 있으면 이 묶음의 것.
            // 통화 노트는 통화가 시작된 때(첫 말보다 조금 앞)를 from 으로 두므로 앞쪽을 넉넉히 본다.
            val lead = if (call) CALL_LEAD_MS else 1_000L
            val mine = notes.filter { n ->
                n.id !in used && isCallNote(n) == call && n.from in (from - lead)..(to + 1_000)
            }.sortedBy { it.from }
            used += mine.map { it.id }
            Session(from, to, g, g.first().callWith, mine)
        }
    }
}
