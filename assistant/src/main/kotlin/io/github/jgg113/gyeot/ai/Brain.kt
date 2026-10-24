package io.github.jgg113.gyeot.ai

import io.github.jgg113.gyeot.data.SavedEvent
import io.github.jgg113.gyeot.data.Utterance
import java.time.ZonedDateTime

/** 대화를 정리하는 쪽: 휴대폰 안 규칙(+작은 AI) 또는 Claude. */
interface Brain {
    fun analyze(context: List<Utterance>, fresh: List<Utterance>, known: List<SavedEvent>, now: ZonedDateTime): Analysis
    fun ask(question: String, material: String, now: ZonedDateTime): String
}

class AnalyzeException(message: String, val retryable: Boolean) : Exception(message)
