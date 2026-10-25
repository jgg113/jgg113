package io.github.jgg113.gyeot.stt

import io.github.jgg113.gyeot.Graph
import io.github.jgg113.gyeot.audio.Gain

/**
 * 설정의 [받아쓰기 시험]: 몇 초 동안 말을 녹음해 지금 모델이 어떻게 받아쓰는지 바로 보여 준다.
 * 마이크로 들어온 소리 크기도 함께 알려 줘서, 휴대폰을 어디에 두면 잘 듣는지 알 수 있다.
 */
object DictationTest {
    data class Result(val text: String, val levelDb: Int, val gain: Double, val seconds: Double, val model: String, val voice: Float? = null)

    fun run(g: Graph, seconds: Int = 6, onLevel: (Int) -> Unit = {}): Result =
        check(g, io.github.jgg113.gyeot.audio.Mic.record(seconds.toDouble(), onLevel))

    /** 녹음해 둔 소리를 받아써 본다. */
    fun check(g: Graph, pcm: ShortArray): Result {
        val db = io.github.jgg113.gyeot.audio.Mic.speechLevelDb(pcm)
        val started = System.currentTimeMillis()
        val text = g.transcribe(pcm)
        val took = (System.currentTimeMillis() - started) / 1000.0
        val who = if (g.voiceId.enrolled) g.voiceId.score(pcm) else null
        return Result(text, db, Gain.gainFor(pcm), took, if (g.settings.localStt) g.localTranscriber.engine() else "cloud", who)
    }

    fun levelAdvice(db: Int): String = when {
        db < -55 -> "아주 작게 들렸어요. 휴대폰을 몸 가까이(가슴 주머니·책상 위) 두면 훨씬 잘 알아들어요."
        db < -42 -> "작게 들렸어요. 자동으로 키워서 받아썼어요."
        db < -20 -> "알맞은 크기로 들렸어요."
        else -> "아주 크게 들렸어요."
    }
}
