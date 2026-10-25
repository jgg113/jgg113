package io.github.jgg113.gyeot.stt

import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import io.github.jgg113.gyeot.audio.Gain
import io.github.jgg113.gyeot.audio.SpeechSegmenter
import io.github.jgg113.gyeot.data.ModelManager
import io.github.jgg113.gyeot.data.Settings

/**
 * 휴대폰 안에서 한국어를 받아쓴다 (sherpa-onnx). 인터넷·요금·한도 없음.
 * - dolphin (기본): Dolphin base CTC, 빠르고 정확
 * - qwen3 (고정밀): Qwen3-ASR 0.6B, 이름(자주 나오는 말)을 힌트로 줄 수 있음, 대신 느리고 무거움
 * - zipformer: 예전 기본 모델 — 새 모델을 받기 전까지만 쓴다
 * 받아쓰기 전에 작은 목소리를 키운다 ([Gain]) — 이것만으로도 오류가 크게 준다.
 */
class LocalTranscriber(private val models: ModelManager, private val settings: Settings) {

    /** 받아 둔 모델이 하나라도 있는지. */
    val available get() = models.installed(models.dolphin) || models.installed(models.qwen3) || models.installed(models.asr)

    /** 고른 모델을 아직 안 받았으면, 받아 둔 것 중 가장 좋은 것으로. */
    fun engine(): String {
        val want = settings.localModel
        val order = listOf(want, Settings.MODEL_DOLPHIN, Settings.MODEL_QWEN3, Settings.MODEL_ZIPFORMER).distinct()
        return order.firstOrNull { installed(it) } ?: Settings.MODEL_DOLPHIN
    }

    private fun installed(model: String) = when (model) {
        Settings.MODEL_QWEN3 -> models.installed(models.qwen3)
        Settings.MODEL_ZIPFORMER -> models.installed(models.asr)
        else -> models.installed(models.dolphin)
    }



    // 모델마다 하나씩 열어 둔다 (기본 + 중요한 말 다시 받아쓰기용 고정밀).
    private val open = HashMap<String, OfflineRecognizer>()
    private val used = HashMap<String, Long>()

    /** 지금 고른 모델로 받아쓴다. [model] 을 주면 그 모델로 (받아 둔 경우). */
    @Synchronized
    fun transcribe(pcm: ShortArray, model: String = engine()): String {
        val use = if (installed(model)) model else engine()
        val key = use + "|" + hotwords(use)
        // 이름 힌트가 바뀌었으면 그 모델을 다시 연다.
        open.keys.filter { it.startsWith("$use|") && it != key }.forEach { open.remove(it)?.release() }
        val r = open.getOrPut(key) { open(key) }
        used[key] = System.currentTimeMillis()
        val stream = r.createStream()
        try {
            stream.acceptWaveform(Gain.toFloat(pcm), SpeechSegmenter.SAMPLE_RATE)
            r.decode(stream)
            val result = r.getResult(stream)
            // Zipformer 는 띄어쓰기를 품은 토큰을 직접 이어야 띄어쓰기가 살아난다.
            return if (use == Settings.MODEL_ZIPFORMER) result.tokens.joinToString("").trim().ifEmpty { result.text }
            else result.text.trim()
        } finally {
            stream.release()
        }
    }

    /** 고정밀 모델을 받아 두었는지 (중요한 말 다시 받아쓰기에 쓸 수 있는지). */
    val canBoost get() = installed(Settings.MODEL_QWEN3)

    @Synchronized
    fun close() {
        open.values.forEach { it.release() }
        open.clear()
        used.clear()
    }

    /** 무거운 고정밀 모델은 몇 분 동안 안 쓰면 메모리에서 내린다 (다른 앱이 밀려나지 않게). */
    @Synchronized
    fun closeIfIdle(idleMs: Long = 10 * 60_000L) {
        val now = System.currentTimeMillis()
        open.keys.filter { it.startsWith(Settings.MODEL_QWEN3) && now - (used[it] ?: 0) > idleMs }.forEach { open.remove(it)?.release(); used.remove(it) }
    }

    /** 내 이름·자주 나오는 이름을 고정밀 모델에 힌트로 준다 (쉼표로). */
    private fun hotwords(model: String): String =
        if (model != Settings.MODEL_QWEN3) ""
        else (listOf(settings.myName) + settings.vocabulary.split(',', '、', '\n'))
            .map { it.trim().replace(",", "") }.filter { it.isNotEmpty() }.distinct().take(30).joinToString(",")

    private fun open(key: String): OfflineRecognizer {
        val engine = key.substringBefore("|")
        if (engine == Settings.MODEL_QWEN3) {
            val p = models.qwen3
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = models.file(p, "conv_frontend.onnx").path,
                        encoder = models.file(p, "encoder.int8.onnx").path,
                        decoder = models.file(p, "decoder.int8.onnx").path,
                        tokenizer = models.dir(p).resolve("tokenizer").path,
                        hotwords = key.substringAfter("|"),
                        // 기본 한도(128)면 빠르게 말한 15초 한국어가 잘린다.
                        maxNewTokens = 400,
                        maxTotalLen = 1024,
                    ),
                    tokens = "",
                    numThreads = 4,
                ),
            )
            return OfflineRecognizer(config = config)
        }
        if (engine == Settings.MODEL_DOLPHIN) {
            if (!models.installed(models.dolphin)) throw Transcriber.SttException("한국어 음성 인식 모델을 아직 받지 않았어요 (설정 탭)", retryable = false)
            val p = models.dolphin
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    dolphin = OfflineDolphinModelConfig(model = models.file(p, "model.int8.onnx").path),
                    tokens = models.file(p, "tokens.txt").path,
                    numThreads = 2,
                ),
            )
            return OfflineRecognizer(config = config)
        }
        if (!models.installed(models.asr)) throw Transcriber.SttException("한국어 음성 인식 모델을 아직 받지 않았어요 (설정 탭)", retryable = false)
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
            decodingMethod = "modified_beam_search",
            maxActivePaths = 4,
        )
        return OfflineRecognizer(config = config)
    }
}
