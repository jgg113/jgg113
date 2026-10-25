package io.github.jgg113.gyeot.voice

import kotlin.math.sqrt

/**
 * 통화 녹음을 두 사람 목소리로 나누는 계산 (안드로이드와 상관없는 부분이라 단위 시험을 한다).
 *
 * 통화 녹음은 두 사람 소리가 한 줄(모노)에 섞여 있고, 말이 빨리 오가서 말 구간 하나에 두 사람이 다 들어 있곤 하다.
 * 그래서 1.5초 창을 0.5초씩 옮겨 가며 목소리 특징을 뽑아 둘로 묶고, 말한 사람이 바뀌는 곳에서 구간을 자른다.
 * 실제 통화(2분 17초)에서 말이 오간 순서가 내용과 맞게 나뉘었다.
 */
object SpeakerSplit {
    /** 구간 [seg] 안의 [start, end) 표본에서 뽑은 목소리 특징 (길이 1). */
    class Window(val seg: Int, val start: Int, val end: Int, val v: FloatArray)

    /** 서로 가장 다른 두 창에서 시작하는 코사인 2-평균. @return 창마다 묶음 번호(0/1), 두 묶음 중심 */
    fun twoClusters(vs: List<FloatArray>): Pair<IntArray, List<FloatArray>> {
        if (vs.size < 2) return IntArray(vs.size) to vs.take(1)
        var a = 0; var b = 1; var worst = Float.MAX_VALUE
        // 창이 아주 많으면 전부 짝짓지 않고 일부만 본다 (30분 통화도 빠르게).
        val step = maxOf(1, vs.size / 400)
        for (i in vs.indices step step) for (j in i + 1 until vs.size step step) {
            val d = dot(vs[i], vs[j])
            if (d < worst) { worst = d; a = i; b = j }
        }
        var c = listOf(vs[a].copyOf(), vs[b].copyOf())
        val label = IntArray(vs.size)
        repeat(20) {
            for (i in vs.indices) label[i] = if (dot(vs[i], c[0]) >= dot(vs[i], c[1])) 0 else 1
            c = (0..1).map { k ->
                val members = vs.filterIndexed { i, _ -> label[i] == k }
                if (members.isEmpty()) c[k] else mean(members)
            }
        }
        return label to c
    }

    /**
     * 구간 하나(길이 [len] 표본)를 말한 사람이 바뀌는 곳에서 자른다.
     * [windows]: (창 범위, 묶음 번호). [hop] 칸마다 그 칸을 덮는 창들의 다수결로 정하고, [minRun] 칸보다 짧은 토막은 앞 토막에 붙인다.
     * @return (범위, 묶음 번호) — 묶음을 모르는 구간은 -1
     */
    fun splitSegment(len: Int, windows: List<Pair<IntRange, Int>>, hop: Int, minRun: Int = 2): List<Pair<IntRange, Int>> {
        val cells = maxOf(1, (len + hop - 1) / hop)
        val lab = IntArray(cells) { cell ->
            val mid = cell * hop + hop / 2
            val votes = windows.filter { mid in it.first }.groupingBy { it.second }.eachCount()
            if (votes.isEmpty()) -1 else votes.maxByOrNull { it.value }!!.key
        }
        // 덮는 창이 없는 칸은 가까운 칸을 따른다.
        for (i in lab.indices) if (lab[i] < 0) lab[i] = (1 until cells).asSequence().flatMap { d -> sequenceOf(i - d, i + d) }
            .firstOrNull { it in lab.indices && lab[it] >= 0 }?.let { lab[it] } ?: -1
        // 토막으로 묶고, 짧은 토막은 앞(없으면 뒤) 토막에 붙인다.
        val runs = mutableListOf<IntArray>() // [시작 칸, 끝 칸(포함 안 함), 묶음]
        for (i in lab.indices) {
            val last = runs.lastOrNull()
            if (last != null && last[2] == lab[i]) last[1] = i + 1 else runs += intArrayOf(i, i + 1, lab[i])
        }
        var changed = true
        while (changed && runs.size > 1) {
            changed = false
            val i = runs.indexOfFirst { it[1] - it[0] < minRun }
            if (i >= 0) {
                if (i > 0) runs[i - 1][1] = runs[i][1] else runs[1][0] = runs[0][0]
                runs.removeAt(i)
                // 붙인 뒤 같은 묶음이 이웃하면 하나로
                var k = 0
                while (k < runs.size - 1) {
                    if (runs[k][2] == runs[k + 1][2]) { runs[k][1] = runs[k + 1][1]; runs.removeAt(k + 1) } else k++
                }
                changed = true
            }
        }
        return runs.mapIndexed { i, r ->
            val from = r[0] * hop
            val to = if (i == runs.lastIndex) len else r[1] * hop
            (from until to) to r[2]
        }
    }

    /** 자를 자리 [at] 를 앞뒤 [radius] 표본 안에서 가장 조용한 20ms 로 옮긴다 (낱말 가운데를 자르지 않게). */
    fun quietestNear(pcm: ShortArray, at: Int, radius: Int, frame: Int = 320): Int {
        var best = at; var bestE = Double.MAX_VALUE
        var p = maxOf(0, at - radius)
        while (p + frame <= minOf(pcm.size, at + radius)) {
            var e = 0.0
            for (i in p until p + frame) e += pcm[i].toDouble() * pcm[i]
            if (e < bestE) { bestE = e; best = p + frame / 2 }
            p += frame / 2
        }
        return best
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }

    fun mean(vs: List<FloatArray>): FloatArray {
        val d = vs.first().size
        val sum = FloatArray(d)
        vs.forEach { v -> for (i in 0 until d) sum[i] += v[i] }
        val n = sqrt(sum.sumOf { it.toDouble() * it }).toFloat().takeIf { it > 0 } ?: return sum
        return FloatArray(d) { sum[it] / n }
    }
}
