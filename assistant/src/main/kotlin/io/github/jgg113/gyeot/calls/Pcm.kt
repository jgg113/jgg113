package io.github.jgg113.gyeot.calls

/** 녹음 파일에서 풀어낸 PCM 을 받아쓰기용 16kHz 모노로 바꾼다 (선형 보간, 여러 조각에 걸쳐 이어짐). */
class MonoResampler(private val inRate: Int, private val channels: Int, private val outRate: Int = 16_000) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0          // 다음에 뽑을 입력 위치 (이번 조각 기준)
    private var last = 0f          // 직전 조각의 마지막 샘플 (조각 경계 보간용)

    fun feed(interleaved: ShortArray, frames: Int = interleaved.size / channels): ShortArray {
        if (frames <= 0) return ShortArray(0)
        val mono = FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[f * channels + c]
            sum / channels
        }
        val out = ArrayList<Short>(((frames - pos) / step).toInt() + 2)
        while (pos < frames) {
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            val a = if (i - 1 < 0) last else mono[i - 1]
            val b = mono[i]
            // pos 는 "직전 샘플 a 와 이번 샘플 b 사이" 를 가리키도록 1칸 늦게 센다.
            out += (a + (b - a) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pos += step
        }
        pos -= frames
        last = mono[frames - 1]
        return out.toShortArray()
    }
}
