package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.stt.KoreanSpacing
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 앱에 넣은 띄어쓰기 모델이 학습 스크립트(Python)와 같은 결과를 내는지 — 해시가 하나라도 다르면 엉망이 된다. */
class SpacingTest {
    private val spacing = KoreanSpacing.load(File("src/main/assets/ko_spacing.bin").inputStream())

    @Test
    fun sameAsTrainingScript() {
        val expected = mapOf(
            "강남역에서세시에보자" to "강남역에서 세시에 보자",
            "내일오후세시에치과가야돼" to "내일 오후 세 시에 치과 가야돼",
            "다음주화요일에팀장님이랑회의있어" to "다음 주 화요일에 팀장님이랑 회의 있어",
            "알았어나도화요일끝나고바로연락할게" to "알았어 나도 화요일 끝나고 바로 연락할게",
            "그러니까내일도웬만하면쉬어" to "그러니까 내일도 웬만하면 쉬어",
            "오늘3시에강남역에서만나" to "오늘 3시에 강남역에서 만나",
            "여보세요.왜이렇게컨디션이안좋아" to "여보세요. 왜 이렇게 컨디션이 안 좋아",
        )
        expected.forEach { (plain, spaced) -> assertEquals(spaced, spacing.apply(plain)) }
    }

    @Test
    fun respacesTextThatAlreadyHasSomeSpaces() {
        // 한국어 대화 모델은 문장부호 뒤에만 띄운다: "응. 말하면큰일나."
        val out = spacing.apply("응. 말하면큰일나.")
        assertEquals(out, spacing.apply(out.replace(" ", "")))
        assertTrue(out.startsWith("응. 말하면 "), out)
        assertEquals("", spacing.apply(""))
        assertEquals("응", spacing.apply("응"))
    }
}
