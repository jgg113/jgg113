package io.github.jgg113.gyeot.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 휴대폰 안에서 돌릴 모델 파일을 처음 한 번 내려받아 앱 저장공간에 둔다. 끊겨도 받은 데까지 이어 받는다.
 * - 한국어 음성 인식: 한국어 대화 전용 Zipformer 174M (KsponSpeech+AIHub 6,500시간, Apache-2.0) 약 160MB
 * - 요약 다듬기(선택): Qwen2.5-1.5B-Instruct (Apache-2.0, MediaPipe 용 8비트) 약 1.6GB
 */
class ModelManager(context: Context) {
    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    data class Pack(val id: String, val label: String, val files: List<Pair<String, String>>, val approxMb: Int)

    data class Progress(val running: Boolean = false, val doneBytes: Long = 0, val totalBytes: Long = 0, val error: String = "") {
        val percent get() = if (totalBytes > 0) (doneBytes * 100 / totalBytes).toInt() else 0
    }

    val asr = Pack(
        "ko-zipformer", "예전 한국어 음성 인식 (85MB)",
        listOf(
            "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt",
        ).map { it to "https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-korean-2024-06-24/resolve/main/$it" },
        85,
    )
    /**
     * 기본 한국어 음성 인식: Dolphin base (DataoceanAI, Apache-2.0, 동아시아 40개 언어) 약 105MB.
     * 공개 한국어 평가 음성으로 재 보니 예전 기본 모델(Zipformer)보다 오류가 절반이고, 시끄럽고 먼 소리에 훨씬 강했다.
     */
    val dolphin = Pack(
        "dolphin-base", "한국어 음성 인식 (105MB)",
        listOf("model.int8.onnx", "tokens.txt")
            .map { it to "https://huggingface.co/csukuangfj/sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02/resolve/main/$it" },
        105,
    )

    /**
     * 한국어 대화 전용 음성 인식 (새 기본): icefall Zipformer 174M (kangkyu, Apache-2.0), KsponSpeech+AIHub 한국어 6,500시간으로 학습.
     * 일상 대화체 한국어(KsponSpeech) 150문장에서 오류 8.1% (Dolphin 14.8%, Qwen3 0.6B 12.7%),
     * 전화 음질로 바꿔도 12.6% (Dolphin 17.9%, Qwen3 17.0%). 사용자의 실제 통화 녹음에서도 가장 잘 알아들었다. 띄어쓰기는 [KoreanSpacing] 이 넣는다.
     */
    val kozip = Pack(
        "ko-conv-zipformer-174m", "한국어 대화 모델 (160MB)",
        listOf(
            KOZIP_ENCODER, KOZIP_DECODER, KOZIP_JOINER, "tokens.txt",
        ).map { it to "https://huggingface.co/kangkyu/icefall-asr-ko-streaming-zipformer-174m/resolve/main/$it" },
        160,
    )

    /** 내 목소리 알아보기: 3D-Speaker CAM++ (Apache-2.0) 약 28MB. */
    val speaker = Pack(
        "speaker-campplus", "내 목소리 알아보기 (28MB)",
        listOf(io.github.jgg113.gyeot.voice.VoiceId.SPEAKER_MODEL to "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/${io.github.jgg113.gyeot.voice.VoiceId.SPEAKER_MODEL}"),
        28,
    )

    /** 사람 목소리 감지 (Silero VAD, MIT) — 0.6MB, 음성 인식 모델과 함께 받는다. */
    val vad = Pack(
        "silero-vad", "말소리 감지 (0.6MB)",
        listOf("silero_vad.onnx" to "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"),
        1,
    )

    /**
     * 고정밀 한국어 음성 인식: Qwen3-ASR 0.6B (Apache-2.0, sherpa-onnx 8비트) 약 1GB.
     * 작은 모델보다 훨씬 정확하지만 받아쓰는 데 시간이 더 들고 메모리를 많이 쓴다 (최신 갤럭시 S 권장).
     */
    val qwen3 = Pack(
        "qwen3-asr-0.6b", "고정밀 한국어 음성 인식 (약 1GB, 와이파이)",
        listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx", "tokenizer/merges.txt", "tokenizer/tokenizer_config.json", "tokenizer/vocab.json")
            .map { it to "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main/$it" },
        990,
    )

    val llm = Pack(
        "qwen2.5-1.5b", "요약 AI (1.6GB, 와이파이 권장)",
        listOf(LLM_FILE to "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/$LLM_FILE"),
        1_600,
    )

    private val states = listOf(asr, dolphin, kozip, vad, qwen3, llm, speaker).associate { it.id to MutableStateFlow(Progress()) }
    private val jobs = mutableMapOf<String, Job>()

    fun progress(pack: Pack): StateFlow<Progress> = states.getValue(pack.id)
    fun dir(pack: Pack) = File(root, pack.id)
    fun file(pack: Pack, name: String) = File(dir(pack), name)
    fun installed(pack: Pack) = pack.files.all { (name, _) -> file(pack, name).let { it.exists() && it.length() > 0 } }
    val llmFile get() = file(llm, LLM_FILE)

    @Synchronized
    fun download(pack: Pack) {
        if (jobs[pack.id]?.isActive == true) return
        val state = states.getValue(pack.id)
        jobs[pack.id] = scope.launch {
            state.value = Progress(running = true)
            try {
                dir(pack).mkdirs()
                val sizes = pack.files.map { (_, url) -> contentLength(url) }
                val total = sizes.sum()
                var before = 0L
                pack.files.forEachIndexed { i, (name, url) ->
                    val target = file(pack, name).also { it.parentFile?.mkdirs() }
                    if (target.exists() && (sizes[i] <= 0 || target.length() == sizes[i])) {
                        before += target.length()
                        state.value = Progress(true, before, total)
                        return@forEachIndexed
                    }
                    fetch(url, target) { got -> state.value = Progress(true, before + got, total) }
                    before += target.length()
                }
                state.value = Progress(false, total, total)
            } catch (e: Exception) {
                state.value = state.value.copy(running = false, error = "내려받기 실패: ${e.message} — 다시 누르면 이어 받아요")
            }
        }
    }

    fun delete(pack: Pack) {
        jobs[pack.id]?.cancel()
        dir(pack).deleteRecursively()
        states.getValue(pack.id).value = Progress()
    }

    private fun contentLength(url: String): Long =
        http.newCall(Request.Builder().url(url).head().build()).execute().use { it.header("Content-Length")?.toLongOrNull() ?: -1 }

    private fun fetch(url: String, target: File, onProgress: (Long) -> Unit) {
        val part = File(target.path + ".part")
        val have = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            val append = have > 0 && res.code == 206
            val body = res.body ?: throw IllegalStateException("빈 응답")
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(1 shl 16)
                var got = if (append) have else 0L
                body.byteStream().use { input ->
                    while (scope.isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        onProgress(got)
                    }
                }
            }
        }
        if (!part.renameTo(target)) throw IllegalStateException("파일 저장 실패")
    }

    companion object {
        const val LLM_FILE = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
        const val KOZIP_ENCODER = "encoder-epoch-99-avg-1-chunk-64-left-128.int8.onnx"
        const val KOZIP_DECODER = "decoder-epoch-99-avg-1-chunk-64-left-128.int8.onnx"
        const val KOZIP_JOINER = "joiner-epoch-99-avg-1-chunk-64-left-128.int8.onnx"
    }
}
