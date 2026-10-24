package io.github.jgg113.gyeot.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * 휴대폰 안에서 도는 작은 AI 모델 (MediaPipe LLM Inference + Qwen2.5 1.5B). 인터넷·요금 없음.
 * 메모리를 1.5GB 넘게 쓰므로 정리할 때만 열고, 일이 끝나면 [close] 로 바로 닫는다.
 */
class LocalLlm private constructor(private val engine: LlmInference) : AutoCloseable {

    data class Summary(val summary: String, val keyPoints: List<String>)

    fun summarize(dialogue: String): Summary {
        val out = generate(
            "너는 한국어 비서다. 휴대폰 마이크로 받아쓴 대화라 오타가 있다. 지어내지 마라.",
            "아래 대화를 읽고 첫 줄에 한두 문장으로 요약해라. 그 다음 줄부터 나중에 꼭 기억해야 할 사실(숫자, 금액, 날짜, 약속, 부탁, 결정)만 '- '로 시작해 최대 5줄 적어라. 없으면 요약만 써라.\n\n대화:\n${dialogue.takeLast(3_000)}",
        )
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val points = lines.filter { it.startsWith("-") || it.startsWith("•") }.map { it.trimStart('-', '•', ' ') }.filter { it.length > 2 }
        val summary = lines.firstOrNull { !it.startsWith("-") && !it.startsWith("•") }?.removePrefix("요약:")?.trim().orEmpty()
        return Summary(summary, points.take(5))
    }

    fun answer(question: String, lines: String): String = generate(
        "너는 한국어 비서다. 주어진 기록만 근거로 짧게 답하고, 기록에 없으면 모른다고 말해라. 언제 나온 말인지 함께 말해라.",
        "기록:\n${lines.takeLast(3_000)}\n\n질문: $question",
    )

    private fun generate(system: String, user: String): String =
        engine.generateResponse("<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n")
            .substringBefore("<|im_end|>").trim()

    override fun close() = engine.close()

    companion object {
        fun open(context: Context, model: File): LlmInference? {
            if (!model.exists()) return null
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(model.absolutePath)
                .setMaxTokens(4096)
                .build()
            return LlmInference.createFromOptions(context, options)
        }

        /** 열어서 일을 시키고 바로 닫는다. 모델이 없거나 열지 못하면 null. */
        fun <T> with(context: Context, model: File, block: (LocalLlm) -> T): T? {
            val engine = runCatching { open(context, model) }.getOrNull() ?: return null
            return LocalLlm(engine).use(block)
        }
    }
}
