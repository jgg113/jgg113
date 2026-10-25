package io.github.jgg113.gyeot.audio

/** 마이크 소리에서 말 구간을 잘라 내는 쪽 (20ms 프레임씩 넣는다). */
interface Segmenter {
    val frameSamples: Int
    /** 지금 말소리 구간 안인지 (상태 표시용). */
    val inSpeech: Boolean
    /** 프레임 하나를 넣는다. 말 구간 하나가 끝나면 그 PCM, 아니면 null. */
    fun push(frame: ShortArray): ShortArray?
    /** 듣던 구간을 강제로 닫는다. */
    fun flush(): ShortArray?
    fun release() {}
}
