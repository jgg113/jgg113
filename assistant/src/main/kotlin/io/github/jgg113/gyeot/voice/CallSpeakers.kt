package io.github.jgg113.gyeot.voice

import io.github.jgg113.gyeot.audio.SpeechSegmenter
import kotlin.math.abs

/**
 * 통화 녹음의 말 구간들을 사람별 조각으로 나누고, 등록한 내 목소리에 더 가까운 쪽을 "나"로 정한다.
 *
 * 통화 소리는 전화망을 거쳐 휴대폰 마이크로 등록한 목소리와 꽤 달라서, "나와 얼마나 비슷한가"를 정해진 기준으로 보면
 * 내 말도 대부분 "다른 사람"이 된다 (1.0.9 에서 실제로 그랬다). 대신 통화 안의 두 목소리 중 어느 쪽이 더 가까운지로 정한다.
 */
class CallSpeakers(private val voiceId: VoiceId) {
    /** 구간 [seg] 의 [from, to) 표본을 [speaker] ("me" · "other" · "")가 말했다. [score] 는 그 목소리와 내 목소리의 비슷함. */
    class Part(val seg: Int, val from: Int, val to: Int, val speaker: String, val score: Float)

    fun split(segments: List<ShortArray>): List<Part> {
        val whole = segments.mapIndexed { i, s -> Part(i, 0, s.size, "", 0f) }
        if (!voiceId.modelReady) return whole
        val windows = mutableListOf<SpeakerSplit.Window>()
        segments.forEachIndexed { i, s ->
            if (s.size < MIN_WIN) return@forEachIndexed
            var t = 0
            while (true) {
                val end = minOf(s.size, t + WIN)
                if (end - t >= MIN_WIN) voiceId.embed(s.copyOfRange(t, end), minSeconds = 0.6)?.let { windows += SpeakerSplit.Window(i, t, end, it) }
                if (end >= s.size) break
                t += HOP
            }
        }
        if (windows.size < 2) return whole
        val (labels, centroids) = SpeakerSplit.twoClusters(windows.map { it.v })
        val oneVoice = centroids.size < 2 || SpeakerSplit.dot(centroids[0], centroids[1]) > ONE_VOICE
        val sims = centroids.map { voiceId.similarity(it) }
        val me: Int? = when {
            sims.any { it == null } -> null
            oneVoice -> if (sims[0]!! >= VoiceId.OTHER) 0 else -1
            abs(sims[0]!! - sims[1]!!) >= MARGIN -> if (sims[0]!! > sims[1]!!) 0 else 1
            else -> null
        }
        fun speakerOf(k: Int): String = when {
            k < 0 || me == null -> ""
            k == me -> "me"
            else -> "other"
        }
        fun scoreOf(k: Int): Float = sims.getOrNull(k)?.coerceIn(0f, 1f) ?: 0f

        val out = mutableListOf<Part>()
        segments.forEachIndexed { i, s ->
            val mine = windows.indices.filter { windows[it].seg == i }.map { (windows[it].start until windows[it].end) to (if (oneVoice) 0 else labels[it]) }
            if (mine.isEmpty()) { out += Part(i, 0, s.size, "", 0f); return@forEachIndexed }
            val runs = SpeakerSplit.splitSegment(s.size, mine, HOP, minRun = 2)
            // 자르는 자리를 앞뒤 0.3초 안에서 가장 조용한 곳으로 옮겨 낱말이 잘리지 않게 한다.
            var from = 0
            runs.forEachIndexed { r, (range, k) ->
                val to = if (r == runs.lastIndex) s.size else SpeakerSplit.quietestNear(s, range.last + 1, SpeechSegmenter.SAMPLE_RATE * 3 / 10).coerceIn(from + 1, s.size)
                out += Part(i, from, to, speakerOf(k), scoreOf(k))
                from = to
            }
        }
        return out
    }

    companion object {
        private const val SR = SpeechSegmenter.SAMPLE_RATE
        private const val WIN = SR * 3 / 2
        private const val HOP = SR / 2
        private const val MIN_WIN = SR * 6 / 10
        /** 두 묶음 중심이 이만큼 비슷하면 한 사람 목소리뿐이라고 본다 (음성 사서함 등). */
        private const val ONE_VOICE = 0.75f
        /** 두 목소리가 내 목소리와 비슷한 정도의 차이가 이보다 작으면 누가 나인지 정하지 않는다. */
        private const val MARGIN = 0.05f
    }
}
