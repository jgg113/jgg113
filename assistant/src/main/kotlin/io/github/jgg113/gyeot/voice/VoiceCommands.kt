package io.github.jgg113.gyeot.voice

import io.github.jgg113.gyeot.ai.KoreanTime
import io.github.jgg113.gyeot.ai.ScheduleSummary
import java.time.ZonedDateTime

/** 수빈이와 통화할 때 내가 한 말을 무슨 부탁인지로 바꾼다 (규칙 기반, 인터넷 필요 없음). */
object VoiceCommands {
    sealed class Cmd {
        data object End : Cmd()
        data object Repeat : Cmd()
        data class Snooze(val minutes: Int) : Cmd()
        data object Detailed : Cmd()
        data object Brief : Cmd()
        data object Yes : Cmd()          // 물어본 일정 → 캘린더에 넣기
        data object RemindOnly : Cmd()
        data object No : Cmd()           // 물어본 일정 → 넣지 않기
        data class NewEvent(val text: String) : Cmd()
        data class Schedule(val text: String) : Cmd()
        data class Ask(val text: String) : Cmd()
        data object Nothing : Cmd()
    }

    private val SNOOZE = Regex("""(\d{1,3}|[일이삼사오육칠팔구십]{1,3}|한|두|세)\s*(분|시간)\s*(뒤|후|있다가|이따)""")
    private val LATER = Regex("나중에\\s*다시|이따가?\\s*다시|조금\\s*있다가|좀\\s*있다가")
    private val REPEAT = Regex("다시\\s*말|한\\s*번\\s*더|뭐라고|다시\\s*알려|못\\s*들었|다시\\s*읽")
    private val DETAIL = Regex("자세히|자세하게|더\\s*알려|길게")
    private val BRIEF = Regex("짧게|간단히|간단하게|요약")
    private val REMIND_ONLY = Regex("알림만|알람만|알려만")
    private val YES = Regex("^(응|어|네|예|그래|좋아|좋지|그렇게|오케이|ㅇㅋ|당연)|넣어|추가해|등록해|캘린더에|저장해|부탁해")
    private val NO = Regex("^(아니|아냐|노|싫어)|필요\\s*없|안\\s*넣|넣지\\s*마|빼\\s*줘|됐어|괜찮아")
    private val ADD = Regex("넣어|추가|잡아|등록|알람|깨워|기억해")
    private val REMIND = Regex("알려\\s*줘|알려\\s*주세요|말해\\s*줘")
    private val END = Regex("^(응|어|네|예|그래|알겠|알았|고마워|고맙|됐어|괜찮아|끊|그만|끝|오케이|ㅇㅋ|바이|수고|ㄱㅅ|감사)")

    /**
     * @param asking 수빈이가 방금 "넣어 드릴까요?" 하고 물었는지 — 그때의 "응/아니"는 그 대답이다.
     */
    fun parse(raw: String, asking: Boolean, now: ZonedDateTime): Cmd {
        val t = raw.trim().replace(Regex("[.?!,~]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return Cmd.Nothing
        SNOOZE.find(t)?.let { m ->
            // "10분 뒤에 다시 알려 줘" 는 미루기, "30분 뒤에 빨래 꺼내라고 알려 줘" 는 새 알림.
            val rest = t.replace(m.value, "").replace(Regex("다시|알려\\s*줘|알려\\s*주세요|전화해\\s*줘|에|요|좀"), "").trim()
            if (rest.length <= 2) {
                val n = KoreanTime.number(m.groupValues[1]) ?: 10
                return Cmd.Snooze(if (m.groupValues[2] == "시간") n * 60 else n)
            }
        }
        if (LATER.containsMatchIn(t)) return Cmd.Snooze(10)
        if (REPEAT.containsMatchIn(t)) return Cmd.Repeat
        if (DETAIL.containsMatchIn(t)) return Cmd.Detailed
        if (BRIEF.containsMatchIn(t)) return Cmd.Brief
        if (asking) {
            if (REMIND_ONLY.containsMatchIn(t)) return Cmd.RemindOnly
            if (NO.containsMatchIn(t)) return Cmd.No
            if (YES.containsMatchIn(t) && !KoreanTime.parse(t, now).dateExplicit) return Cmd.Yes
        }
        val add = ADD.containsMatchIn(t)
        if (ScheduleSummary.isScheduleQuestion(t) && !add) return Cmd.Schedule(t)
        val time = KoreanTime.parse(t, now)
        // "내일 세 시에 치과 일정 넣어 줘", "삼십 분 뒤에 알려 줘" → 새 일정·알림
        if (time.any && (time.dateExplicit || time.time != null) && (add || (REMIND.containsMatchIn(t) && time.time != null))) return Cmd.NewEvent(t)
        if (t.length <= 12 && END.containsMatchIn(t)) return Cmd.End
        return Cmd.Ask(t)
    }
}
