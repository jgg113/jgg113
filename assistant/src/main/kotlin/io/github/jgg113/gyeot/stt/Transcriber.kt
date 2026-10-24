package io.github.jgg113.gyeot.stt

import io.github.jgg113.gyeot.data.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 말 구간(WAV)을 한국어 글로 받아쓴다. OpenAI 호환 `/audio/transcriptions` 를 쓰므로
 * Groq(whisper-large-v3, 무료 한도)·OpenAI(gpt-4o-transcribe) 등 주소와 키만 바꿔 쓸 수 있다.
 */
class Transcriber(private val settings: Settings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    class SttException(message: String, val retryable: Boolean) : IOException(message)

    fun transcribe(wav: ByteArray): String {
        val key = settings.sttApiKey
        if (key.isBlank()) throw SttException("음성 인식 API 키가 없습니다 (설정 탭)", retryable = false)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", settings.sttModel)
            .addFormDataPart("language", "ko")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("prompt", prompt())
            .build()
        val request = Request.Builder()
            .url(settings.sttBaseUrl + "/audio/transcriptions")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        http.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val retryable = res.code == 429 || res.code >= 500
                throw SttException("음성 인식 실패 ${res.code}: ${text.take(200)}", retryable)
            }
            return json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
        }
    }

    /** 인식기에 주는 힌트: 한국어 일상 대화이고, 이런 이름들이 나온다. */
    private fun prompt(): String {
        val names = (listOf(settings.myName) + settings.vocabulary.split(',', '、', '\n'))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val base = "한국어 일상 대화입니다. 약속, 일정, 회의, 시간과 날짜를 정확히 받아 적습니다."
        return if (names.isEmpty()) base else "$base 등장하는 이름: ${names.joinToString(", ")}."
    }
}
