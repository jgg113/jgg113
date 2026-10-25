package io.github.jgg113.gyeot

import io.github.jgg113.gyeot.voice.SpeakerSplit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeakerSplitTest {
    private fun unit(vararg x: Float): FloatArray { val n = kotlin.math.sqrt(x.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(x.size) { x[it] / n } }

    @Test
    fun twoVoicesFallIntoTwoGroups() {
        val a = listOf(unit(1f, 0.1f, 0f), unit(0.9f, 0.2f, 0.1f), unit(1f, 0f, 0.1f))
        val b = listOf(unit(0f, 1f, 0.1f), unit(0.1f, 0.9f, 0f))
        val (labels, centroids) = SpeakerSplit.twoClusters(listOf(a[0], b[0], a[1], b[1], a[2]))
        assertEquals(2, centroids.size)
        assertEquals(labels[0], labels[2]); assertEquals(labels[0], labels[4])
        assertEquals(labels[1], labels[3])
        assertTrue(labels[0] != labels[1])
    }

    @Test
    fun cutsWhereTheSpeakerChanges() {
        val hop = 8_000 // 0.5초
        // 0~2초 0번 목소리, 2~4초 1번 목소리 (창 1.5초, 0.5초씩)
        val windows = listOf(0 until 24_000 to 0, 8_000 until 32_000 to 0, 16_000 until 40_000 to 1, 24_000 until 48_000 to 1, 32_000 until 56_000 to 1, 40_000 until 64_000 to 1)
        val runs = SpeakerSplit.splitSegment(64_000, windows, hop)
        assertEquals(listOf(0, 1), runs.map { it.second })
        assertTrue(runs[0].first.last + 1 in 24_000..40_000, runs.toString())
        assertEquals(64_000, runs.last().first.last + 1)
    }

    @Test
    fun shortFlickersAreAbsorbed() {
        val hop = 8_000
        val windows = (0 until 10).map { i -> (i * hop until i * hop + 24_000) to (if (i == 4) 1 else 0) }
        val runs = SpeakerSplit.splitSegment(10 * hop + 16_000, windows, hop)
        assertEquals(1, runs.size, runs.toString())
    }

    @Test
    fun cutsAtTheQuietestMoment() {
        val pcm = ShortArray(16_000) { if (it in 9_000 until 9_600) 0 else 3_000 }
        val at = SpeakerSplit.quietestNear(pcm, 8_000, 4_800)
        assertTrue(at in 9_000..9_600, "$at")
    }
}
