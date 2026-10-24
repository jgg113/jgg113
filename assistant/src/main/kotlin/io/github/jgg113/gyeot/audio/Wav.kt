package io.github.jgg113.gyeot.audio

import java.io.ByteArrayOutputStream

/** 16비트 모노 PCM 을 WAV 파일 바이트로 만든다 (음성 인식 API 업로드용, 휴대폰에는 저장하지 않는다). */
object Wav {
    fun encode(pcm: ShortArray, sampleRate: Int = SpeechSegmenter.SAMPLE_RATE): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun int(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
        fun short(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        out.write("RIFF".toByteArray()); int(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1)
        int(sampleRate); int(sampleRate * 2); short(2); short(16)
        out.write("data".toByteArray()); int(dataSize)
        val bytes = ByteArray(dataSize)
        for (i in pcm.indices) {
            val s = pcm[i].toInt()
            bytes[i * 2] = (s and 0xff).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte()
        }
        out.write(bytes)
        return out.toByteArray()
    }

    /** 말 구간 여러 개를 짧은 쉼(0.3초)을 두고 하나로 잇는다. 인식 요청 횟수를 줄이려는 것. */
    fun join(segments: List<ShortArray>, gapMs: Int = 300, sampleRate: Int = SpeechSegmenter.SAMPLE_RATE): ShortArray {
        val gap = sampleRate * gapMs / 1000
        val total = segments.sumOf { it.size } + gap * (segments.size - 1).coerceAtLeast(0)
        val out = ShortArray(total)
        var i = 0
        segments.forEachIndexed { n, s ->
            if (n > 0) i += gap
            s.copyInto(out, i)
            i += s.size
        }
        return out
    }
}
