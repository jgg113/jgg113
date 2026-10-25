package io.github.jgg113.gyeot.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.audio.Gain
import io.github.jgg113.gyeot.audio.SpeechSegmenter
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 설정의 [받아쓰기 시험]: 몇 초 동안 말을 녹음해 지금 모델이 어떻게 받아쓰는지 바로 보여 준다.
 * 마이크로 들어온 소리 크기도 함께 알려 줘서, 휴대폰을 어디에 두면 잘 듣는지 알 수 있다.
 */
object DictationTest {
    data class Result(val text: String, val levelDb: Int, val gain: Double, val seconds: Double, val model: String)

    @SuppressLint("MissingPermission")
    fun run(g: Graph, seconds: Int = 6): Result {
        val rate = SpeechSegmenter.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "마이크를 열 수 없어요" }
        val pcm = ShortArray(rate * seconds)
        rec.startRecording()
        try {
            var read = 0
            while (read < pcm.size) {
                val n = rec.read(pcm, read, minOf(1600, pcm.size - read))
                if (n < 0) error("마이크 읽기 오류 $n")
                read += n
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        // 말한 부분의 크기 (조용한 앞뒤는 빼고, 큰 쪽 30% 의 평균).
        val frames = pcm.toList().chunked(320).map { f -> sqrt(f.sumOf { it.toDouble() * it } / f.size) }.sortedDescending()
        val loud = frames.take((frames.size * 0.3).toInt().coerceAtLeast(1)).average()
        val db = (20 * log10((loud + 1e-9) / 32768)).toInt()
        val started = System.currentTimeMillis()
        val text = g.transcribe(pcm)
        val took = (System.currentTimeMillis() - started) / 1000.0
        return Result(text, db, Gain.gainFor(pcm), took, if (g.settings.localStt) g.localTranscriber.engine() else "cloud")
    }

    fun levelAdvice(db: Int): String = when {
        db < -55 -> "아주 작게 들렸어요. 휴대폰을 몸 가까이(가슴 주머니·책상 위) 두면 훨씬 잘 알아들어요."
        db < -42 -> "작게 들렸어요. 자동으로 키워서 받아썼어요."
        db < -20 -> "알맞은 크기로 들렸어요."
        else -> "아주 크게 들렸어요."
    }
}
