package io.github.jgg113.gyeot.data

import android.content.Context

/** 사용자 설정. 키는 휴대폰 안(SharedPreferences)에만 저장한다. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("gyeot", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = p.getString(key, def) ?: def
    private fun put(key: String, v: String) = p.edit().putString(key, v).apply()
    private fun bool(key: String, def: Boolean) = p.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()
    private fun int(key: String, def: Int) = p.getInt(key, def)
    private fun putInt(key: String, v: Int) = p.edit().putInt(key, v).apply()

    /** 받아쓰기 엔진: "local"(휴대폰 안, 무료·무제한) 또는 "cloud"(OpenAI 호환 API). */
    var sttEngine: String
        get() = str("stt_engine", ENGINE_LOCAL)
        set(v) = put("stt_engine", v)
    /** 정리 엔진: "local"(휴대폰 안 규칙 + 선택적 작은 AI) 또는 "claude". */
    var brainEngine: String
        get() = str("brain_engine", ENGINE_LOCAL)
        set(v) = put("brain_engine", v)
    /** 휴대폰 안 AI 모델(Qwen2.5 1.5B)로 요약 다듬기. 모델을 받아 둔 경우에만 쓴다. */
    var useLocalLlm: Boolean
        get() = bool("local_llm", true)
        set(v) = putBool("local_llm", v)

    /** 음성 인식(받아쓰기) — OpenAI 호환 /audio/transcriptions 주소. 기본은 Groq(무료 한도) Whisper. */
    var sttBaseUrl: String
        get() = str("stt_base", DEFAULT_STT_BASE)
        set(v) = put("stt_base", v.trim().trimEnd('/'))
    var sttApiKey: String
        get() = str("stt_key", "")
        set(v) = put("stt_key", v.trim())
    var sttModel: String
        get() = str("stt_model", DEFAULT_STT_MODEL)
        set(v) = put("stt_model", v.trim())

    /** 정리·일정 추출 — Claude API 키 (console.anthropic.com). */
    var anthropicApiKey: String
        get() = str("anthropic_key", "")
        set(v) = put("anthropic_key", v.trim())
    var claudeModel: String
        get() = str("claude_model", DEFAULT_CLAUDE_MODEL)
        set(v) = put("claude_model", v.trim().ifEmpty { DEFAULT_CLAUDE_MODEL })

    /** 내 이름·호칭 — 누가 한 약속인지 가리는 데 쓴다. */
    var myName: String
        get() = str("my_name", "")
        set(v) = put("my_name", v.trim())
    /** 자주 나오는 사람·장소·회사 이름 (쉼표로) — 받아쓰기 정확도를 높인다. */
    var vocabulary: String
        get() = str("vocab", "")
        set(v) = put("vocab", v.trim())

    /** 일정을 넣을 캘린더 id. -1 이면 삼성 계정 캘린더를 자동으로 고른다. */
    var calendarId: Long
        get() = p.getLong("calendar_id", -1L)
        set(v) = p.edit().putLong("calendar_id", v).apply()
    /**
     * 일정 추가 방식: smart(확실한 건 바로 넣고 애매한 건 물어보기) · ask(늘 "추가할까요?" 묻기) · auto(웬만하면 바로).
     * 예전 '바로 넣기' 스위치를 껐던 사람은 ask 로 시작한다.
     */
    var addMode: String
        get() = str("add_mode", if (bool("auto_register", true)) "smart" else "ask")
        set(v) = put("add_mode", v)
    /** 아침 일정 브리핑 시각 (0~23시, -1 끔). */
    var briefingHour: Int
        get() = int("briefing_hour", 8)
        set(v) = putInt("briefing_hour", v.coerceIn(-1, 23))
    var reminderMinutes: Int
        get() = int("reminder_min", 30)
        set(v) = putInt("reminder_min", v.coerceIn(0, 24 * 60))
    /** 캘린더 알림과 별도로 이 앱이 일정 시각에 직접 알리기. */
    var ownReminders: Boolean
        get() = bool("own_reminders", true)
        set(v) = putBool("own_reminders", v)
    /** 하루 요약 알림 시각 (0~23시, -1 끔). */
    var digestHour: Int
        get() = int("digest_hour", 21)
        set(v) = putInt("digest_hour", v.coerceIn(-1, 23))
    /** 받아쓴 글 보관 일수. */
    var retentionDays: Int
        get() = int("retention_days", 30)
        set(v) = putInt("retention_days", v.coerceIn(1, 365))

    /** 삼성 자동 통화 녹음 파일을 받아써서 일정·할 일에 넣기. */
    var importCalls: Boolean
        get() = bool("import_calls", false)
        set(v) = putBool("import_calls", v)
    /** 일정 미리 알림을 전화처럼 걸어 음성으로 알려 주기. */
    var callForReminders: Boolean
        get() = bool("call_reminders", true)
        set(v) = putBool("call_reminders", v)
    /** 아침 브리핑을 전화처럼 걸어 읽어 주기. */
    var callForBriefing: Boolean
        get() = bool("call_briefing", true)
        set(v) = putBool("call_briefing", v)

    /** 사용자가 듣기를 켜 두었는지 (재부팅·앱 업데이트 뒤 다시 켜는 데 쓴다). */
    var listeningWanted: Boolean
        get() = bool("listening", false)
        set(v) = putBool("listening", v)

    val localStt get() = sttEngine == ENGINE_LOCAL
    val localBrain get() = brainEngine == ENGINE_LOCAL

    companion object {
        const val ENGINE_LOCAL = "local"
        const val ENGINE_CLOUD = "cloud"
        const val ENGINE_CLAUDE = "claude"
        const val DEFAULT_STT_BASE = "https://api.groq.com/openai/v1"
        const val DEFAULT_STT_MODEL = "whisper-large-v3"
        const val DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"
    }
}
