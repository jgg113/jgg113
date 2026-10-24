package io.github.jgg113.gyeot.stt

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import io.github.jgg113.gyeot.audio.SpeechSegmenter
import io.github.jgg113.gyeot.data.ModelManager

/**
 * 휴대폰 안에서 한국어를 받아쓴다 (sherpa-onnx + Zipformer 한국어 모델). 인터넷·요금·한도 없음.
 * 모델을 여는 데 1~2초 걸려 한 번 만들어 계속 쓴다.
 */
class LocalTranscriber(private val models: ModelManager) {
    private var recognizer: OfflineRecognizer? = null

    val available get() = models.installed(models.asr)

    @Synchronized
    fun transcribe(pcm: ShortArray): String {
        val r = recognizer ?: open().also { recognizer = it }
        val samples = FloatArray(pcm.size) { pcm[it] / 32768f }
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, SpeechSegmenter.SAMPLE_RATE)
            r.decode(stream)
            // 결과 글은 띄어쓰기가 빠져 나오므로, 띄어쓰기를 품은 토큰을 직접 잇는다.
            val result = r.getResult(stream)
            return result.tokens.joinToString("").trim().ifEmpty { result.text }
        } finally {
            stream.release()
        }
    }

    @Synchronized
    fun close() {
        recognizer?.release()
        recognizer = null
    }

    private fun open(): OfflineRecognizer {
        if (!available) throw Transcriber.SttException("한국어 음성 인식 모델을 아직 받지 않았어요 (설정 탭)", retryable = false)
        val p = models.asr
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = models.file(p, "encoder-epoch-99-avg-1.int8.onnx").path,
                    decoder = models.file(p, "decoder-epoch-99-avg-1.onnx").path,
                    joiner = models.file(p, "joiner-epoch-99-avg-1.int8.onnx").path,
                ),
                tokens = models.file(p, "tokens.txt").path,
                modelType = "transducer",
                numThreads = 2,
            ),
        )
        return OfflineRecognizer(config = config)
    }
}
