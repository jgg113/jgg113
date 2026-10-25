package io.github.jgg113.gyeot.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 받아쓰기 전에 작은 목소리를 키운다.
 *
 * 받아쓰기용 마이크 경로(VOICE_RECOGNITION)는 자동 음량을 쓰지 않아, 1~2m 떨어진 말소리는 아주 작게(-50dB 안팎) 들어온다.
 * 휴대폰 안 한국어 모델은 이렇게 작은 소리를 거의 받아쓰지 못하는데(시험해 보니 아무 글도 안 나옴), 키워 주면 제대로 받아쓴다.
 * 목표 크기(RMS 0.06)까지 최대 30배 키우되, 가장 큰 소리가 찌그러지지 않게(0.95) 맞춘다. 이미 큰 소리는 그대로 둔다.
 */
object Gain {
    private const val TARGET_RMS = 0.06 * 32768
    private const val MAX_PEAK = 0.95 * 32767
    private const val MAX_GAIN = 30.0

    fun gainFor(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 1.0
        var sum = 0.0
        var peak = 0
        for (s in pcm) { sum += s.toDouble() * s; val a = abs(s.toInt()); if (a > peak) peak = a }
        val rms = sqrt(sum / pcm.size) + 1e-9
        return minOf(TARGET_RMS / rms, MAX_PEAK / (peak + 1e-9), MAX_GAIN)
    }

    fun normalize(pcm: ShortArray): ShortArray {
        val g = gainFor(pcm)
        if (g <= 1.05) return pcm
        return ShortArray(pcm.size) { (pcm[it] * g).toInt().coerceIn(-32768, 32767).toShort() }
    }

    fun toFloat(pcm: ShortArray, normalize: Boolean = true): FloatArray {
        val g = if (normalize) gainFor(pcm).coerceAtLeast(1.0) else 1.0
        return FloatArray(pcm.size) { (pcm[it] * g / 32768.0).toFloat().coerceIn(-1f, 1f) }
    }
}
