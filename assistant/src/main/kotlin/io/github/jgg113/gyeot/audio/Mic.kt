package io.github.jgg113.gyeot.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.sqrt

/** 몇 초 동안 마이크 소리를 그대로 녹음한다 (받아쓰기 시험·목소리 등록용). */
object Mic {
    @SuppressLint("MissingPermission")
    fun record(seconds: Double, onLevel: (Int) -> Unit = {}): ShortArray {
        val rate = SpeechSegmenter.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "마이크를 열 수 없어요" }
        val pcm = ShortArray((rate * seconds).toInt())
        rec.startRecording()
        try {
            var read = 0
            while (read < pcm.size) {
                val n = rec.read(pcm, read, minOf(1600, pcm.size - read))
                if (n < 0) error("마이크 읽기 오류 $n")
                onLevel(levelDb(pcm, read, n))
                read += n
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        return pcm
    }

    /** 앞뒤의 조용한 부분을 잘라 낸다 (목소리 등록에 말한 부분만 쓰려고). */
    fun trimSilence(pcm: ShortArray): ShortArray {
        val frame = 320
        val rms = (0 until pcm.size / frame).map { i ->
            var s = 0.0
            for (j in i * frame until (i + 1) * frame) s += pcm[j].toDouble() * pcm[j]
            sqrt(s / frame)
        }
        if (rms.isEmpty()) return pcm
        val floor = maxOf(rms.max() * 0.08, 60.0)
        val first = rms.indexOfFirst { it >= floor }
        val last = rms.indexOfLast { it >= floor }
        if (first < 0) return pcm
        val from = ((first - 10).coerceAtLeast(0)) * frame
        val to = ((last + 11) * frame).coerceAtMost(pcm.size)
        return pcm.copyOfRange(from, to)
    }

    /** 말한 부분의 크기 (dBFS): 조용한 앞뒤는 빼고 큰 쪽 30% 의 평균. */
    fun speechLevelDb(pcm: ShortArray): Int {
        val frames = pcm.toList().chunked(320).map { f -> sqrt(f.sumOf { it.toDouble() * it } / f.size) }.sortedDescending()
        val loud = frames.take((frames.size * 0.3).toInt().coerceAtLeast(1)).average()
        return (20 * log10((loud + 1e-9) / 32768)).toInt()
    }

    private fun levelDb(pcm: ShortArray, from: Int, n: Int): Int {
        if (n <= 0) return -90
        var sum = 0.0
        for (i in from until from + n) sum += pcm[i].toDouble() * pcm[i]
        return (20 * log10((sqrt(sum / n) + 1e-9) / 32768)).toInt()
    }
}
