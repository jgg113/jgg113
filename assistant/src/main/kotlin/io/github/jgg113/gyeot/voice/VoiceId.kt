package io.github.jgg113.gyeot.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import io.github.jgg113.gyeot.audio.Gain
import io.github.jgg113.gyeot.audio.SpeechSegmenter
import io.github.jgg113.gyeot.data.ModelManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.sqrt

/**
 * 내 목소리 알아보기: 목소리를 몇 문장 등록해 두면, 들은 말마다 "나"인지 "다른 사람"인지 가린다.
 * 3D-Speaker CAM++ (Apache-2.0, 28MB) 로 목소리 특징(192개 숫자)을 뽑아 등록한 목소리와 비교한다.
 * 한국어 화자 10명으로 시험해 보니 조용한 곳·먼 거리+소음 모두 사람을 틀리지 않았다 (비슷함 0.55 기준).
 */
class VoiceId(context: Context, private val models: ModelManager) {
    @Serializable
    private data class Print(val vectors: List<List<Float>>)

    private val file = File(context.filesDir, "gyeot/voiceprint.json")
    private val json = Json { ignoreUnknownKeys = true }
    private var extractor: SpeakerEmbeddingExtractor? = null
    @Volatile private var mine: FloatArray? = load()

    val enrolled: Boolean get() = mine != null
    val modelReady: Boolean get() = models.installed(models.speaker)
    val samples: Int get() = runCatching { json.decodeFromString<Print>(file.readText()).vectors.size }.getOrDefault(0)

    /** 소리 한 조각의 목소리 특징 (길이 1로 맞춤). [minSeconds] 보다 짧으면 null. */
    @Synchronized
    fun embed(pcm: ShortArray, minSeconds: Double = 1.2): FloatArray? {
        if (pcm.size < SpeechSegmenter.SAMPLE_RATE * minSeconds || !modelReady) return null
        val ex = extractor ?: SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(model = models.file(models.speaker, SPEAKER_MODEL).path, numThreads = 1),
        ).also { extractor = it }
        val s = ex.createStream()
        try {
            s.acceptWaveform(Gain.toFloat(pcm), SpeechSegmenter.SAMPLE_RATE)
            s.inputFinished()
            if (!ex.isReady(s)) return null
            return unit(ex.compute(s))
        } finally {
            s.release()
        }
    }

    /** 등록한 내 목소리와 비슷한 정도 (0~1). 등록 안 했거나 너무 짧으면 null. */
    fun score(pcm: ShortArray): Float? {
        val me = mine ?: return null
        val v = embed(pcm) ?: return null
        return dot(me, v).coerceIn(0f, 1f)
    }

    /** 목소리 특징 [v] 가 등록한 내 목소리와 비슷한 정도 (-1~1). 등록 안 했으면 null. */
    fun similarity(v: FloatArray): Float? = mine?.let { dot(it, v) }

    /** "me" · "other" · "" (모름) */
    fun label(score: Float?): String = when {
        score == null -> ""
        score >= ME -> "me"
        score < OTHER -> "other"
        else -> ""
    }

    /** 등록: 녹음한 문장들의 목소리 특징을 평균 내 저장한다. @return 저장한 문장 수 */
    @Synchronized
    fun enroll(recordings: List<ShortArray>, append: Boolean = false): Int {
        val vs = recordings.mapNotNull { embed(it) }
        val old = if (append) runCatching { json.decodeFromString<Print>(file.readText()).vectors }.getOrDefault(emptyList()) else emptyList()
        val all = old + vs.map { it.toList() }
        if (all.isEmpty()) return 0
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Print(all)))
        mine = average(all)
        return vs.size
    }

    fun forget() {
        file.delete()
        mine = null
    }

    private fun load(): FloatArray? = runCatching { average(json.decodeFromString<Print>(file.readText()).vectors) }.getOrNull()

    companion object {
        const val SPEAKER_MODEL = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
        const val ME = 0.5f
        const val OTHER = 0.35f

        fun unit(v: FloatArray): FloatArray {
            val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat().takeIf { it > 0 } ?: return v
            return FloatArray(v.size) { v[it] / n }
        }

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
            return s
        }

        fun average(vs: List<List<Float>>): FloatArray? {
            if (vs.isEmpty()) return null
            val d = vs.first().size
            val sum = FloatArray(d)
            vs.forEach { v -> for (i in 0 until d) sum[i] += v[i] }
            return unit(sum)
        }
    }
}
