package io.github.jgg113.gyeot.stt

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 한국어 띄어쓰기 되살리기. 한국어 대화 모델(Zipformer)은 글자는 잘 맞히지만 띄어쓰기를 거의 안 해서
 * ("알았어나도화요일끝나고바로연락할게"), 글자 사이마다 띄울지를 작은 모델로 정한다.
 *
 * 모델: 글자 n-gram 특징 19개를 해시한 칸(2^21)마다 가중치 하나 (로지스틱 회귀, int8, 2MB).
 * 한국어 영화 자막(OpenSubtitles)·TED 강연 글 650만 문장으로 학습했다 (띄어쓰기를 지운 문장에서 되살리기 F1 0.956) — 학습할 때와 똑같은 해시를 써야 한다.
 */
class KoreanSpacing private constructor(private val bits: Int, private val scale: Float, private val bias: Float, private val weights: ByteArray) {

    /** 띄어쓰기 없는 글(띄어쓴 곳이 있어도 다시 정한다)에 띄어쓰기를 넣는다. */
    fun apply(text: String): String {
        val raw = text.filter { !it.isWhitespace() }
        if (raw.length < 2) return raw
        val norm = CharArray(raw.length) { norm(raw[it]) }
        val padded = CharArray(norm.size + PAD_L + PAD_R) { i ->
            when {
                i < PAD_L -> '^'
                i >= PAD_L + norm.size -> '$'
                else -> norm[i - PAD_L]
            }
        }
        val mask = (1 shl bits) - 1
        val sb = StringBuilder(raw.length + raw.length / 3)
        for (i in raw.indices) {
            sb.append(raw[i])
            if (i == raw.lastIndex) break
            if (norm[i] == '.') { sb.append(' '); continue }
            if (norm[i + 1] == '.') continue
            val at = i + PAD_L
            var z = 0
            for (t in TEMPLATES.indices) z += weights[fnv(t, TEMPLATES[t], padded, at) and mask].toInt()
            if (z * scale + bias > 0f) sb.append(' ')
        }
        return sb.toString()
    }

    companion object {
        private const val PAD_L = 3
        private const val PAD_R = 4

        // 경계(글자 i 뒤)를 기준으로 본 글자 위치 묶음. 학습 스크립트의 TEMPLATES 와 순서·내용이 같아야 한다.
        private val TEMPLATES: Array<IntArray> = arrayOf(
            intArrayOf(-2), intArrayOf(-1), intArrayOf(0), intArrayOf(1), intArrayOf(2),
            intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(-2, -1),
            intArrayOf(-2, -1, 0), intArrayOf(-1, 0, 1), intArrayOf(0, 1, 2), intArrayOf(1, 2, 3), intArrayOf(-1, 0, 1, 2),
            intArrayOf(-3, -2, -1, 0), intArrayOf(0, 1, 2, 3), intArrayOf(-2, -1, 0, 1), intArrayOf(-3), intArrayOf(3),
        )

        /** FNV-1a 32비트: (묶음 번호 + 1), 1, 글자 코드들 순서로. */
        private fun fnv(t: Int, offsets: IntArray, chars: CharArray, at: Int): Int {
            var h = 0x811C9DC5.toInt()
            h = (h xor (t + 1)) * 16777619
            h = (h xor 1) * 16777619
            for (o in offsets) h = (h xor chars[at + o].code) * 16777619
            return h
        }

        fun norm(c: Char): Char = when {
            c in '가'..'힣' -> c
            c.isDigit() -> '0'
            c in 'a'..'z' || c in 'A'..'Z' -> 'a'
            c in ".?!,~" -> '.'
            else -> '#'
        }

        /** "KSP1" + bits(int) + scale(float) + bias(float) + 가중치(int8 × 2^bits), 리틀 엔디언. */
        fun load(input: InputStream): KoreanSpacing {
            val bytes = input.use { it.readBytes() }
            val head = ByteBuffer.wrap(bytes, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
            val magic = String(bytes, 0, 4, Charsets.US_ASCII)
            require(magic == "KSP1") { "띄어쓰기 모델 형식이 아니에요" }
            head.position(4)
            val bits = head.int
            val scale = head.float
            val bias = head.float
            require(bytes.size == 16 + (1 shl bits)) { "띄어쓰기 모델 크기가 맞지 않아요" }
            return KoreanSpacing(bits, scale, bias, bytes.copyOfRange(16, bytes.size))
        }
    }
}
