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
 * - 한국어 음성 인식: sherpa-onnx Zipformer (KsponSpeech 한국어 대화로 학습, Apache-2.0) 약 85MB
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
        "ko-zipformer", "한국어 음성 인식 (85MB)",
        listOf(
            "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt",
        ).map { it to "https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-korean-2024-06-24/resolve/main/$it" },
        85,
    )
    val llm = Pack(
        "qwen2.5-1.5b", "요약 AI (1.6GB, 와이파이 권장)",
        listOf(LLM_FILE to "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/$LLM_FILE"),
        1_600,
    )

    private val states = mapOf(asr.id to MutableStateFlow(Progress()), llm.id to MutableStateFlow(Progress()))
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
                    val target = file(pack, name)
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
    }
}
