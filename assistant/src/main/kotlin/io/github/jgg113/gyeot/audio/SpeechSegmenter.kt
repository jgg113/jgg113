package io.github.jgg113.gyeot.audio

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 마이크에서 들어오는 16kHz 모노 PCM 을 20ms 프레임 단위로 받아 '사람이 말한 구간'만 잘라 낸다.
 *
 * 하루 종일 들으면서 조용한 시간까지 음성 인식에 보내면 비용·배터리만 든다. 그래서 주변 소음 크기를
 * 계속 따라가면서(noise floor) 그보다 확실히 큰 소리가 이어질 때만 말소리로 본다.
 * 말 앞 300ms 를 붙여 첫 음절이 잘리지 않게 하고, 말이 끝난 뒤 [endSilenceMs] 만큼 조용하면 구간을 닫는다.
 */
class SpeechSegmenter(
    private val sampleRate: Int = SAMPLE_RATE,
    private val endSilenceMs: Int = 1_200,
    private val minSpeechMs: Int = 500,
    private val maxSegmentMs: Int = 30_000,
    private val preRollMs: Int = 300,
    /** 소음보다 이 배수 이상 커야 말소리로 본다. */
    private val speechRatio: Double = 3.0,
    /**
     * 아주 조용한 방에서도 이 크기(16비트 RMS) 아래는 말소리로 보지 않는다.
     * 1~2m 떨어진 말소리는 RMS 60~100 정도로 아주 작아서, 너무 높게 잡으면 말을 통째로 놓친다.
     */
    private val minSpeechRms: Double = 70.0,
) : Segmenter {
    override val frameSamples = sampleRate * FRAME_MS / 1000

    private var noiseFloor = minSpeechRms / speechRatio
    private val preRoll = ArrayDeque<ShortArray>()
    private val preRollFrames = preRollMs / FRAME_MS
    private var current: MutableList<ShortArray>? = null
    private var speechFrames = 0
    private var silenceFrames = 0

    /** 말 구간이 막 시작됐거나 진행 중이면 true (상태 표시용). */
    override val inSpeech: Boolean get() = current != null

    /**
     * 프레임 하나를 넣는다. 말 구간 하나가 끝나면 그 PCM 을 돌려주고, 아니면 null.
     */
    override fun push(frame: ShortArray): ShortArray? {
        val rms = rms(frame)
        val threshold = max(noiseFloor * speechRatio, minSpeechRms)
        val loud = rms >= threshold

        val segment = current
        if (segment == null) {
            // 조용할 때만 소음 크기를 천천히 따라간다 (말소리가 소음으로 학습되지 않게).
            if (!loud) noiseFloor = noiseFloor * 0.995 + rms * 0.005
            preRoll.addLast(frame)
            while (preRoll.size > preRollFrames) preRoll.removeFirst()
            if (loud) {
                current = preRoll.toMutableList()
                preRoll.clear()
                speechFrames = 1
                silenceFrames = 0
            }
            return null
        }

        segment.add(frame)
        if (loud) {
            speechFrames++
            silenceFrames = 0
        } else {
            silenceFrames++
        }
        val lengthMs = segment.size * FRAME_MS
        return if (silenceFrames * FRAME_MS >= endSilenceMs || lengthMs >= maxSegmentMs) close() else null
    }

    /** 지금 듣고 있던 구간을 강제로 닫는다 (듣기를 멈출 때). */
    override fun flush(): ShortArray? = if (current != null) close() else null

    private fun close(): ShortArray? {
        val segment = current ?: return null
        current = null
        val spoke = speechFrames * FRAME_MS
        speechFrames = 0
        silenceFrames = 0
        if (spoke < minSpeechMs) return null
        val out = ShortArray(segment.sumOf { it.size })
        var i = 0
        for (f in segment) {
            f.copyInto(out, i)
            i += f.size
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20

        fun rms(frame: ShortArray): Double {
            if (frame.isEmpty()) return 0.0
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            return sqrt(sum / frame.size)
        }
    }
}
