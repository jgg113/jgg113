package io.github.jgg113.gyeot.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import io.github.jgg113.gyeot.data.Settings
import java.util.Locale

/**
 * 수빈이 목소리: 휴대폰의 한국어 음성 엔진(삼성·Google TTS)에서 가장 자연스러운 목소리를 골라
 * 조금 높고 경쾌하게(기본 높이 1.15, 빠르기 1.05) 읽는다. 설정에서 목소리·높이·빠르기를 바꿀 수 있다.
 */
class Speaker(context: Context, private val settings: Settings, private val onReady: (Boolean) -> Unit = {}) {
    private var tts: TextToSpeech? = null
    var ready = false
        private set
    private val done = HashMap<String, () -> Unit>()
    private var counter = 0

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val t = tts
            ready = status == TextToSpeech.SUCCESS && t != null && configure(t)
            onReady(ready)
        }
    }

    private fun configure(t: TextToSpeech): Boolean {
        val r = t.setLanguage(Locale.KOREAN)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return false
        val voices = koreanVoices(t)
        val chosen = voices.firstOrNull { it.name == settings.ttsVoice } ?: voices.firstOrNull()
        chosen?.let { runCatching { t.voice = it } }
        t.setPitch(settings.ttsPitch)
        t.setSpeechRate(settings.ttsRate)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { synchronized(done) { done.remove(it) } }?.invoke() }
            @Deprecated("old API") override fun onError(id: String?) { onDone(id) }
            override fun onError(id: String?, code: Int) { onDone(id) }
        })
        return true
    }

    /** 설정을 바꾼 뒤 다시 적용 (미리 듣기용). */
    fun reload() { tts?.let { if (ready) configure(it) } }

    fun voices(): List<Voice> = tts?.let { koreanVoices(it) }.orEmpty()

    /** 다 읽으면 [then] 을 부른다 (다른 스레드에서 불릴 수 있다). */
    fun speak(text: String, then: () -> Unit = {}) {
        val t = tts
        if (t == null || !ready) { then(); return }
        val id = "subin-${counter++}"
        synchronized(done) { done[id] = then }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    fun stop() { tts?.stop(); synchronized(done) { done.clear() } }

    fun shutdown() { tts?.shutdown(); tts = null; ready = false }

    companion object {
        private val FEMALE_HINT = Regex("ism|kob|female|f0|-f-|sma|fem|여", RegexOption.IGNORE_CASE)
        private val MALE_HINT = Regex("jun|koc|kod|male|m0|-m-|남", RegexOption.IGNORE_CASE)

        /** 한국어 목소리, 좋은 순서: 음질 높은 것 → 여성으로 보이는 이름 → 인터넷 없이 되는 것. */
        fun koreanVoices(t: TextToSpeech): List<Voice> =
            runCatching { t.voices }.getOrNull().orEmpty()
                .filter { it.locale.language == "ko" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(
                    compareByDescending<Voice> { it.quality }
                        .thenByDescending { FEMALE_HINT.containsMatchIn(it.name) && !MALE_HINT.containsMatchIn(it.name.replace("female", "", true)) }
                        .thenBy { it.isNetworkConnectionRequired }
                        .thenBy { it.name },
                )

        /** 설정 화면에 보일 이름: "목소리 1 · 아주 좋음 · 인터넷" */
        fun label(v: Voice, index: Int): String {
            val q = when {
                v.quality >= Voice.QUALITY_VERY_HIGH -> "아주 좋음"
                v.quality >= Voice.QUALITY_HIGH -> "좋음"
                else -> "보통"
            }
            val sex = when {
                MALE_HINT.containsMatchIn(v.name.replace("female", "", true)) -> " · 남성"
                FEMALE_HINT.containsMatchIn(v.name) -> " · 여성"
                else -> ""
            }
            return "목소리 ${index + 1} · $q$sex${if (v.isNetworkConnectionRequired) " · 인터넷" else ""}"
        }
    }
}
