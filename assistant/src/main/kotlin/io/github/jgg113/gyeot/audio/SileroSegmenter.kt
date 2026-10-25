package io.github.jgg113.gyeot.audio

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Silero VAD(작은 AI 모델)로 말소리 구간을 찾는다. 소리 크기가 아니라 '사람 목소리인지'로 판단해서,
 * 작게 들리는 말도 놓치지 않고, 냉장고·선풍기 같은 소음은 말소리로 잘못 보내지 않는다.
 * 첫 음절이 잘리지 않게 구간 앞 0.3초를 붙인다.
 */
class SileroSegmenter(modelPath: String, private val maxSegmentSec: Float = 20f, minSilenceSec: Float = 0.6f) : Segmenter {
    override val frameSamples = SpeechSegmenter.SAMPLE_RATE * SpeechSegmenter.FRAME_MS / 1000

    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath,
                threshold = 0.45f,
                minSilenceDuration = minSilenceSec,
                minSpeechDuration = 0.3f,
                windowSize = WINDOW,
                maxSpeechDuration = maxSegmentSec,
            ),
            sampleRate = SpeechSegmenter.SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    private val window = FloatArray(WINDOW)
    private var filled = 0
    // 앞 0.3초를 붙이려고 최근 소리를 돌림 버퍼에 둔다.
    private val history = ShortArray(SpeechSegmenter.SAMPLE_RATE * 40)
    private var total = 0L
    private val ready = ArrayDeque<ShortArray>()

    override val inSpeech: Boolean get() = vad.isSpeechDetected()

    override fun push(frame: ShortArray): ShortArray? {
        for (s in frame) {
            history[(total % history.size).toInt()] = s
            total++
            window[filled++] = s / 32768f
            if (filled == WINDOW) {
                // 작은 소리도 잘 잡아서 따로 키우지 않는다 (시험해 보니 키우면 오히려 조금 덜 잡았다).
                vad.acceptWaveform(window.copyOf())
                filled = 0
                drain()
            }
        }
        // 하루 넘게 켜 두면 샘플 번호가 커지므로, 조용할 때 한 번씩 처음부터 다시 센다.
        if (total > RESET_AFTER && !vad.isSpeechDetected() && ready.isEmpty()) { vad.reset(); total = 0 }
        return ready.removeFirstOrNull()
    }

    override fun flush(): ShortArray? {
        vad.flush()
        drain()
        return ready.removeFirstOrNull()
    }

    override fun release() = vad.release()

    private fun drain() {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            val start = seg.start.toLong()
            val pre = PRE_ROLL.coerceAtMost(start)
            val len = seg.samples.size
            // 돌림 버퍼에 아직 남아 있으면 원래 소리(앞 0.3초 포함)를 그대로 꺼낸다.
            if (total - (start - pre) <= history.size) {
                out(ShortArray((pre + len).toInt()) { i -> history[((start - pre + i) % history.size).toInt()] })
            } else {
                out(ShortArray(len) { (seg.samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() })
            }
        }
    }

    private fun out(pcm: ShortArray) { ready.addLast(pcm) }

    companion object {
        private const val WINDOW = 512
        private const val PRE_ROLL = SpeechSegmenter.SAMPLE_RATE * 3L / 10
        private const val RESET_AFTER = SpeechSegmenter.SAMPLE_RATE * 60L * 60 * 6
    }
}
