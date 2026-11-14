package io.github.jgg113.stocknote.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * safetensors 파일 읽기. 앞 8바이트가 머리말 길이, 이어서 JSON 머리말(이름·자료형·모양·위치), 그 뒤가 숫자 자료다.
 * 코드가 들어 있지 않은 순수 자료 형식이라 안전하게 읽을 수 있다.
 */
class SafeTensors private constructor(private val entries: Map<String, Entry>, private val data: ByteBuffer) {
    class Entry(val dtype: String, val shape: IntArray, val begin: Int, val end: Int)

    val names: Set<String> get() = entries.keys

    fun has(name: String) = name in entries

    fun shape(name: String): IntArray = entries[name]?.shape ?: throw DataException("모델 파일에 $name 이(가) 없습니다.", "MODEL")

    fun floats(name: String): FloatArray {
        val e = entries[name] ?: throw DataException("모델 파일에 $name 이(가) 없습니다.", "MODEL")
        if (e.dtype != "F32") throw DataException("모델 파일의 $name 자료형(${e.dtype})을 읽을 수 없습니다.", "MODEL")
        val out = FloatArray((e.end - e.begin) / 4)
        val view = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(e.begin)
        view.asFloatBuffer().get(out)
        return out
    }

    companion object {
        fun open(file: File): SafeTensors {
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                if (size < 16) throw DataException("모델 파일이 비어 있거나 깨졌습니다.", "MODEL")
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN)
                val headerLen = buf.getLong(0)
                if (headerLen <= 0 || headerLen > 50_000_000 || 8 + headerLen > size) throw DataException("모델 파일 머리말이 올바르지 않습니다.", "MODEL")
                val headerBytes = ByteArray(headerLen.toInt())
                buf.position(8)
                buf.get(headerBytes)
                val root = parseJson(headerBytes.toString(Charsets.UTF_8)).obj() ?: throw DataException("모델 파일 머리말을 읽지 못했습니다.", "MODEL")
                val base = 8 + headerLen.toInt()
                val entries = HashMap<String, Entry>()
                for ((name, v) in root) {
                    if (name == "__metadata__") continue
                    val o = v.obj() ?: continue
                    val shape = o["shape"].arr()?.map { it.toString().toInt() }?.toIntArray() ?: IntArray(0)
                    val offs = o["data_offsets"].arr()?.map { it.toString().toLong() } ?: continue
                    if (offs.size != 2 || base + offs[1] > size) throw DataException("모델 파일이 잘렸습니다 (다시 내려받으세요).", "MODEL")
                    entries[name] = Entry(o.str("dtype"), shape, base + offs[0].toInt(), base + offs[1].toInt())
                }
                return SafeTensors(entries, buf)
            }
        }
    }
}

/** y = W x + b (W 는 [nOut × nIn] 행 우선) */
internal class Lin(val w: FloatArray, val b: FloatArray?, val nOut: Int, val nIn: Int) {
    fun apply(x: FloatArray, y: FloatArray) {
        val w = w
        val nIn = nIn
        var base = 0
        for (o in 0 until nOut) {
            var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
            var i = 0
            val end4 = nIn - 3
            while (i < end4) {
                s0 += w[base + i] * x[i]
                s1 += w[base + i + 1] * x[i + 1]
                s2 += w[base + i + 2] * x[i + 2]
                s3 += w[base + i + 3] * x[i + 3]
                i += 4
            }
            while (i < nIn) { s0 += w[base + i] * x[i]; i++ }
            y[o] = s0 + s1 + s2 + s3 + (if (b != null) b[o] else 0f)
            base += nIn
        }
    }

    companion object {
        fun of(st: SafeTensors, name: String, bias: Boolean = true): Lin {
            val shape = st.shape("$name.weight")
            return Lin(st.floats("$name.weight"), if (bias) st.floats("$name.bias") else null, shape[0], shape[1])
        }
    }
}

/** 인과(causal) 주의집중의 key·value 저장소. [parent] 가 있으면 앞부분(공통 과거)은 부모 것을 그대로 본다. */
internal class KvCache(val layers: Int, val d: Int, capacity: Int, val parent: KvCache? = null) {
    val start: Int = parent?.len ?: 0
    val k = Array(layers) { FloatArray((capacity - start).coerceAtLeast(0) * d) }
    val v = Array(layers) { FloatArray((capacity - start).coerceAtLeast(0) * d) }
    var len: Int = start
}

/** 스레드마다 하나씩 쓰는 임시 배열 */
internal class Scratch(d: Int, ff: Int, capacity: Int) {
    val h = FloatArray(d); val q = FloatArray(d); val k = FloatArray(d); val v = FloatArray(d); val o = FloatArray(d); val a = FloatArray(d)
    val f1 = FloatArray(ff); val f3 = FloatArray(ff)
    val scores = FloatArray(capacity)
    val x = FloatArray(d); val x2 = FloatArray(d); val e2 = FloatArray(d * 2)
    val logits = FloatArray(1 shl 12)
    val bits = FloatArray(64); val out6 = FloatArray(16)
}

internal fun rmsNorm(x: FloatArray, weight: FloatArray, out: FloatArray, eps: Float = 1e-5f) {
    var s = 0f
    for (i in weight.indices) s += x[i] * x[i]
    val r = (1.0 / sqrt((s / weight.size + eps).toDouble())).toFloat()
    for (i in weight.indices) out[i] = x[i] * r * weight[i]
}

/** RMSNorm → 회전 위치 임베딩을 쓴 인과 자기주의집중 → RMSNorm → SwiGLU 피드포워드 */
internal class Block(st: SafeTensors, p: String) {
    val norm1 = st.floats("$p.norm1.weight")
    val norm2 = st.floats("$p.norm2.weight")
    val q = Lin.of(st, "$p.self_attn.q_proj"); val k = Lin.of(st, "$p.self_attn.k_proj")
    val v = Lin.of(st, "$p.self_attn.v_proj"); val o = Lin.of(st, "$p.self_attn.out_proj")
    val w1 = Lin.of(st, "$p.ffn.w1", false); val w2 = Lin.of(st, "$p.ffn.w2", false); val w3 = Lin.of(st, "$p.ffn.w3", false)
    val invFreq = st.floats("$p.self_attn.rotary.inv_freq")
    val d = norm1.size
    val headDim = invFreq.size * 2
    val heads = d / headDim
    val ff = w1.nOut

    private fun rotate(vec: FloatArray, pos: Int) {
        val half = headDim / 2
        for (i in 0 until half) {
            val ang = pos * invFreq[i].toDouble()
            val c = cos(ang).toFloat(); val s = sin(ang).toFloat()
            for (h in 0 until heads) {
                val b = h * headDim
                val x1 = vec[b + i]; val x2 = vec[b + half + i]
                vec[b + i] = x1 * c - x2 * s
                vec[b + half + i] = x2 * c + x1 * s
            }
        }
    }

    /** [x] 를 그 자리에서 바꾼다. [pos] 위치의 key·value 를 [cache] 에 넣는다. */
    fun forward(x: FloatArray, pos: Int, cache: KvCache, layer: Int, t: Scratch) {
        rmsNorm(x, norm1, t.h)
        q.apply(t.h, t.q); k.apply(t.h, t.k); v.apply(t.h, t.v)
        rotate(t.q, pos); rotate(t.k, pos)
        val own = pos - cache.start
        System.arraycopy(t.k, 0, cache.k[layer], own * d, d)
        System.arraycopy(t.v, 0, cache.v[layer], own * d, d)
        attend(t.q, cache, layer, pos + 1, t, heads, headDim)
        o.apply(t.o, t.a)
        for (i in 0 until d) x[i] += t.a[i]
        rmsNorm(x, norm2, t.h)
        w1.apply(t.h, t.f1); w3.apply(t.h, t.f3)
        for (i in 0 until ff) { val z = t.f1[i]; t.f1[i] = z / (1f + exp(-z)) * t.f3[i] }
        w2.apply(t.f1, t.a)
        for (i in 0 until d) x[i] += t.a[i]
    }

}

/** 질의 [qv] 로 위치 0 until [n] 의 key·value 를 본 결과(머리별 가중합)를 t.o 에 쓴다. */
internal fun attend(qv: FloatArray, cache: KvCache, layer: Int, n: Int, t: Scratch, heads: Int, headDim: Int) {
    val d = cache.d
    val parent = cache.parent
    val start = cache.start
    val scale = (1.0 / sqrt(headDim.toDouble())).toFloat()
    for (h in 0 until heads) {
        val hb = h * headDim
        var maxS = Float.NEGATIVE_INFINITY
        for (j in 0 until n) {
            val arr = if (j < start) parent!!.k[layer] else cache.k[layer]
            val off = (if (j < start) j - parent!!.start else j - start) * d + hb
            var s = 0f
            for (i in 0 until headDim) s += qv[hb + i] * arr[off + i]
            s *= scale
            t.scores[j] = s
            if (s > maxS) maxS = s
        }
        var sum = 0f
        for (j in 0 until n) { val e = exp(t.scores[j] - maxS); t.scores[j] = e; sum += e }
        for (i in 0 until headDim) t.o[hb + i] = 0f
        for (j in 0 until n) {
            val p = t.scores[j] / sum
            if (p < 1e-9f) continue
            val arr = if (j < start) parent!!.v[layer] else cache.v[layer]
            val off = (if (j < start) j - parent!!.start else j - start) * d + hb
            for (i in 0 until headDim) t.o[hb + i] += p * arr[off + i]
        }
    }
}

/** 예측 결과 */
class KronosResult(
    val contextBars: Int,
    val steps: Int,
    /** 표본 경로 [표본][단계] = (시가, 고가, 저가, 종가, 거래량, 거래대금) */
    val samples: Array<Array<FloatArray>>,
    val lastClose: Double,
    val futureTimes: LongArray,
    val seconds: Double,
) {
    /** 표본 평균으로 만든 예상 봉 */
    val candles: List<Candle> = (0 until steps).map { i ->
        fun avg(c: Int) = samples.sumOf { it[i][c].toDouble() } / samples.size
        val o = avg(0); val cl = avg(3)
        Candle(futureTimes[i], o, maxOf(avg(1), o, cl), minOf(avg(2), o, cl), cl, avg(4).coerceAtLeast(0.0), avg(5).coerceAtLeast(0.0))
    }

    private val finals: List<Double> = samples.map { it[steps - 1][3].toDouble() }.sorted()

    /** 마지막 날 종가가 지금보다 높은 표본의 비율 */
    val upProb: Double get() = finals.count { it > lastClose }.toDouble() / finals.size
    val expectedReturn: Double get() = finals.average() / lastClose - 1
    val medianReturn: Double get() = Metrics.quantile(finals, 0.5) / lastClose - 1

    /** 단계별 종가의 분위수 (띠 그리기용) */
    fun closeQuantile(step: Int, q: Double): Double = Metrics.quantile(samples.map { it[step][3].toDouble() }.sorted(), q)
    val path: DoubleArray get() = DoubleArray(steps) { candles[it].close }
}

/**
 * Kronos (금융 캔들 전용 사전학습 모델, Shi 외 2025, MIT 라이선스)의 추론 부분을 이 앱에서 직접 구현한 것.
 * 공식 배포 가중치(safetensors)를 읽어, 과거 봉을 토큰으로 바꾸고(토크나이저) 다음 봉 토큰을 하나씩 뽑아(트랜스포머) 다시 봉으로 되돌린다.
 * 인과 주의집중이라 과거 부분 계산을 한 번만 하고 표본 경로들이 함께 쓴다.
 */
class KronosModel(tok: SafeTensors, model: SafeTensors) {
    // 토크나이저
    private val tEmbed = Lin.of(tok, "embed")
    private val tEnc = blocks(tok, "encoder")
    private val tQuant = Lin.of(tok, "quant_embed")
    private val tPost = Lin.of(tok, "post_quant_embed")
    private val tDec = blocks(tok, "decoder")
    private val tHead = Lin.of(tok, "head")
    private val bitsTotal = tQuant.nOut
    private val s1Bits = bitsTotal / 2
    private val qScale = (1.0 / sqrt(bitsTotal.toDouble())).toFloat()

    // 예측 모델
    private val embS1 = model.floats("embedding.emb_s1.weight")
    private val embS2 = model.floats("embedding.emb_s2.weight")
    private val fusion = Lin.of(model, "embedding.fusion_proj")
    private val teMinute = model.floats("time_emb.minute_embed.weight"); private val teHour = model.floats("time_emb.hour_embed.weight")
    private val teWeekday = model.floats("time_emb.weekday_embed.weight"); private val teDay = model.floats("time_emb.day_embed.weight")
    private val teMonth = model.floats("time_emb.month_embed.weight")
    private val mBlocks = blocks(model, "transformer")
    private val mNorm = model.floats("norm.weight")
    private val depQ = Lin.of(model, "dep_layer.cross_attn.q_proj"); private val depK = Lin.of(model, "dep_layer.cross_attn.k_proj")
    private val depV = Lin.of(model, "dep_layer.cross_attn.v_proj"); private val depO = Lin.of(model, "dep_layer.cross_attn.out_proj")
    private val depNorm = model.floats("dep_layer.norm.weight")
    private val projS1 = Lin.of(model, "head.proj_s1"); private val projS2 = Lin.of(model, "head.proj_s2")
    private val d = mNorm.size
    private val ff = mBlocks.first().ff
    private val vocab1 = projS1.nOut
    private val vocab2 = projS2.nOut
    private val sqrtD = sqrt(d.toDouble()).toFloat()
    /** 교차 주의집중의 머리 크기 (모델 블록과 다를 수 있다) */
    private val depHeadDim = model.floats("dep_layer.cross_attn.rotary.inv_freq").size * 2
    private val depHeads = d / depHeadDim

    val layers: Int get() = mBlocks.size
    val dModel: Int get() = d

    private fun blocks(st: SafeTensors, prefix: String): List<Block> {
        val out = ArrayList<Block>()
        var i = 0
        while (st.has("$prefix.$i.norm1.weight")) { out += Block(st, "$prefix.$i"); i++ }
        if (out.isEmpty()) throw DataException("모델 파일 구조가 예상과 다릅니다 ($prefix).", "MODEL")
        return out
    }

    private fun scratch(capacity: Int) = Scratch(d, ff, capacity)

    /** 봉을 모델 입력 6열(시·고·저·종·거래량·거래대금)로 */
    private fun row(c: Candle): FloatArray {
        val amount = if (c.value > 0) c.value else c.volume * (c.open + c.high + c.low + c.close) / 4
        return floatArrayOf(c.open.toFloat(), c.high.toFloat(), c.low.toFloat(), c.close.toFloat(), c.volume.toFloat(), amount.toFloat())
    }

    private class Normalized(val x: Array<FloatArray>, val mean: DoubleArray, val std: DoubleArray)

    private fun normalize(candles: List<Candle>, clip: Float = 5f): Normalized {
        val rows = candles.map { row(it) }
        val mean = DoubleArray(6) { j -> rows.sumOf { it[j].toDouble() } / rows.size }
        val std = DoubleArray(6) { j -> sqrt(rows.sumOf { (it[j] - mean[j]).let { v -> v * v } } / rows.size) }
        val x = Array(rows.size) { i -> FloatArray(6) { j -> (((rows[i][j] - mean[j]) / (std[j] + 1e-5)).toFloat()).coerceIn(-clip, clip) } }
        return Normalized(x, mean, std)
    }

    /** (분, 시, 요일(월=0), 일, 월) */
    private fun stamp(time: Long, intraday: Boolean): IntArray {
        val t = java.time.Instant.ofEpochSecond(time).atZone(KST)
        return intArrayOf(if (intraday) t.minute else 0, if (intraday) t.hour else 0, t.dayOfWeek.value - 1, t.dayOfMonth, t.monthValue)
    }

    // ───── 토크나이저 ─────

    /** 정규화한 봉들을 (s1, s2) 토큰으로 */
    private fun encode(x: Array<FloatArray>, t: Scratch): Pair<IntArray, IntArray> {
        val n = x.size
        val cache = KvCache(tEnc.size, d, n)
        val s1 = IntArray(n); val s2 = IntArray(n)
        val z = FloatArray(bitsTotal)
        for (i in 0 until n) {
            tEmbed.apply(x[i], t.x)
            for ((l, b) in tEnc.withIndex()) b.forward(t.x, i, cache, l, t)
            cache.len = i + 1
            tQuant.apply(t.x, z)
            var a = 0; var b2 = 0
            for (j in 0 until s1Bits) { if (z[j] > 0) a = a or (1 shl j); if (z[s1Bits + j] > 0) b2 = b2 or (1 shl j) }
            s1[i] = a; s2[i] = b2
        }
        return s1 to s2
    }

    /** 토큰 하나를 [pos] 위치에서 되돌린다 (결과 6개 값은 t.out6). 캐시에 그 위치를 쌓는다. */
    private fun decodeToken(s1: Int, s2: Int, pos: Int, cache: KvCache, t: Scratch) {
        for (j in 0 until s1Bits) {
            t.bits[j] = if (s1 and (1 shl j) != 0) qScale else -qScale
            t.bits[s1Bits + j] = if (s2 and (1 shl j) != 0) qScale else -qScale
        }
        tPost.apply(t.bits, t.x)
        for ((l, b) in tDec.withIndex()) b.forward(t.x, pos, cache, l, t)
        cache.len = pos + 1
        tHead.apply(t.x, t.out6)
    }

    // ───── 예측 모델 ─────

    private fun embed(s1: Int, s2: Int, st: IntArray, out: FloatArray, t: Scratch) {
        for (i in 0 until d) { t.e2[i] = embS1[s1 * d + i] * sqrtD; t.e2[d + i] = embS2[s2 * d + i] * sqrtD }
        fusion.apply(t.e2, out)
        val mi = st[0] * d; val h = st[1] * d; val w = st[2] * d; val dy = st[3] * d; val mo = st[4] * d
        for (i in 0 until d) out[i] += teMinute[mi + i] + teHour[h + i] + teWeekday[w + i] + teDay[dy + i] + teMonth[mo + i]
    }

    /** 토큰 하나를 예측 모델에 넣어 문맥 벡터(t.x2)를 얻고, 교차 주의집중용 key·value 를 [cross] 에 쌓는다. */
    private fun step(s1: Int, s2: Int, st: IntArray, pos: Int, cache: KvCache, cross: KvCache, t: Scratch) {
        embed(s1, s2, st, t.x, t)
        for ((l, b) in mBlocks.withIndex()) b.forward(t.x, pos, cache, l, t)
        cache.len = pos + 1
        rmsNorm(t.x, mNorm, t.x2)
        depK.apply(t.x2, t.k); depV.apply(t.x2, t.v)
        val own = pos - cross.start
        System.arraycopy(t.k, 0, cross.k[0], own * d, d)
        System.arraycopy(t.v, 0, cross.v[0], own * d, d)
        cross.len = pos + 1
    }

    /** 문맥 [ctx] 와 뽑은 s1 로 s2 로짓을 만든다 (t.logits 앞 vocab2 칸). */
    private fun s2Logits(ctx: FloatArray, s1: Int, cross: KvCache, t: Scratch) {
        for (i in 0 until d) t.h[i] = embS1[s1 * d + i]
        depQ.apply(t.h, t.q)
        attend(t.q, cross, 0, cross.len, t, depHeads, depHeadDim)
        depO.apply(t.o, t.a)
        for (i in 0 until d) t.h[i] = ctx[i] + t.a[i]
        rmsNorm(t.h, depNorm, t.a)
        projS2.apply(t.a, t.logits)
    }

    private fun sample(logits: FloatArray, n: Int, temperature: Double, topP: Double, rnd: java.util.Random, idx: Array<Int>, probs: DoubleArray): Int {
        var maxL = Float.NEGATIVE_INFINITY
        for (i in 0 until n) if (logits[i] > maxL) maxL = logits[i]
        var sum = 0.0
        for (i in 0 until n) { val p = exp((logits[i] - maxL) / temperature); probs[i] = p; sum += p }
        for (i in 0 until n) probs[i] /= sum
        if (topP >= 1.0) {
            var r = rnd.nextDouble(); for (i in 0 until n) { r -= probs[i]; if (r <= 0) return i }
            return n - 1
        }
        java.util.Arrays.sort(idx, 0, n) { a, b -> java.lang.Double.compare(probs[b], probs[a]) }
        // 누적 확률이 topP 를 처음 넘는 토큰까지 남긴다.
        var cum = 0.0; var keep = 0
        while (keep < n) { cum += probs[idx[keep]]; keep++; if (cum > topP) break }
        var r = rnd.nextDouble() * cum
        for (i in 0 until keep) { r -= probs[idx[i]]; if (r <= 0) return idx[i] }
        return idx[keep - 1]
    }

    private fun logSoftmaxAt(logits: FloatArray, n: Int, target: Int): Double {
        var maxL = Float.NEGATIVE_INFINITY
        for (i in 0 until n) if (logits[i] > maxL) maxL = logits[i]
        var sum = 0.0
        for (i in 0 until n) sum += exp((logits[i] - maxL).toDouble())
        return (logits[target] - maxL) - ln(sum)
    }

    /** 점검용: (토크나이저 복원 오차, 다음 s1 토큰 평균 음의 로그우도, 다음 s2 토큰 평균 음의 로그우도) */
    fun diagnose(candles: List<Candle>, intraday: Boolean = false): Triple<Double, Double, Double> {
        val n = candles.size
        val norm = normalize(candles)
        val t = scratch(n + 1)
        val (s1, s2) = encode(norm.x, t)
        val dec = KvCache(tDec.size, d, n)
        var err = 0.0
        for (i in 0 until n) {
            decodeToken(s1[i], s2[i], i, dec, t)
            for (j in 0 until 4) err += (t.out6[j] - norm.x[i][j]).let { it * it.toDouble() }
        }
        val cache = KvCache(mBlocks.size, d, n); val cross = KvCache(1, d, n)
        var nll1 = 0.0; var nll2 = 0.0
        val ctx = FloatArray(d)
        for (i in 0 until n - 1) {
            step(s1[i], s2[i], stamp(candles[i].time, intraday), i, cache, cross, t)
            System.arraycopy(t.x2, 0, ctx, 0, d)
            projS1.apply(ctx, t.logits)
            nll1 -= logSoftmaxAt(t.logits, vocab1, s1[i + 1])
            s2Logits(ctx, s1[i + 1], cross, t)
            nll2 -= logSoftmaxAt(t.logits, vocab2, s2[i + 1])
        }
        return Triple(sqrt(err / (n * 4)), nll1 / (n - 1), nll2 / (n - 1))
    }

    /**
     * [candles] 의 마지막 [context] 봉을 보고 앞으로 [steps] 봉을 [samples] 가지 경로로 뽑는다.
     * [futureTimes] 는 앞으로 올 봉들의 시각(요일·날짜 정보로 쓴다).
     */
    fun forecast(
        candles: List<Candle>, steps: Int, futureTimes: LongArray, context: Int = 256, samples: Int = 16,
        temperature: Double = 1.0, topP: Double = 0.9, seed: Long = 7, threads: Int = 4, intraday: Boolean = false,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): KronosResult {
        require(futureTimes.size >= steps) { "앞으로 올 봉 시각이 모자랍니다." }
        val t0 = System.nanoTime()
        val hist = candles.takeLast(context)
        val n = hist.size
        if (n < 32) throw DataException("Kronos 예측에는 봉이 32개 이상 필요합니다.", "SHORT")
        val capacity = n + steps
        val norm = normalize(hist)
        val t = scratch(capacity)
        val (s1, s2) = encode(norm.x, t)
        progress(1, 4)
        // 과거 부분: 예측 모델과 복원기의 key·value 를 한 번만 계산한다.
        val cache = KvCache(mBlocks.size, d, n); val cross = KvCache(1, d, n); val dec = KvCache(tDec.size, d, n)
        val ctxLast = FloatArray(d)
        for (i in 0 until n) {
            step(s1[i], s2[i], stamp(hist[i].time, intraday), i, cache, cross, t)
            decodeToken(s1[i], s2[i], i, dec, t)
        }
        System.arraycopy(t.x2, 0, ctxLast, 0, d)
        progress(2, 4)
        val stamps = Array(steps) { stamp(futureTimes[it], intraday) }
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, samples))
        try {
            val jobs = (0 until samples).map { sIdx ->
                Callable {
                    val ts = scratch(capacity)
                    val c = KvCache(mBlocks.size, d, capacity, cache); val cr = KvCache(1, d, capacity, cross); val dc = KvCache(tDec.size, d, capacity, dec)
                    val rnd = java.util.Random(seed * 1_000_003 + sIdx)
                    val idx = Array(maxOf(vocab1, vocab2)) { it }
                    val probs = DoubleArray(maxOf(vocab1, vocab2))
                    val ctx = ctxLast.copyOf()
                    Array(steps) { i ->
                        projS1.apply(ctx, ts.logits)
                        for (k in idx.indices) idx[k] = k
                        val a = sample(ts.logits, vocab1, temperature, topP, rnd, idx, probs)
                        s2Logits(ctx, a, cr, ts)
                        for (k in idx.indices) idx[k] = k
                        val b = sample(ts.logits, vocab2, temperature, topP, rnd, idx, probs)
                        decodeToken(a, b, n + i, dc, ts)
                        val out = FloatArray(6) { j -> (ts.out6[j] * (norm.std[j] + 1e-5) + norm.mean[j]).toFloat() }
                        if (i < steps - 1) {
                            step(a, b, stamps[i], n + i, c, cr, ts)
                            System.arraycopy(ts.x2, 0, ctx, 0, d)
                        }
                        out
                    }
                }
            }
            val results = pool.invokeAll(jobs).map { it.get() }.toTypedArray()
            progress(4, 4)
            return KronosResult(n, steps, results, hist.last().close, futureTimes.copyOf(steps), (System.nanoTime() - t0) / 1e9)
        } finally {
            pool.shutdown()
        }
    }

    companion object {
        const val MODEL_REPO = "NeoQuasar/Kronos-mini"
        const val TOKENIZER_REPO = "NeoQuasar/Kronos-Tokenizer-2k"
        const val MODEL_URL = "https://huggingface.co/$MODEL_REPO/resolve/main/model.safetensors"
        const val TOKENIZER_URL = "https://huggingface.co/$TOKENIZER_REPO/resolve/main/model.safetensors"
        const val MODEL_FILE = "kronos-mini.safetensors"
        const val TOKENIZER_FILE = "kronos-tokenizer-2k.safetensors"
        /** 내려받을 파일 크기 (바이트) — 받다 만 파일을 가려내는 데 쓴다 */
        const val MODEL_BYTES = 16_440_776L
        const val TOKENIZER_BYTES = 15_842_376L
        const val PAGE = "https://github.com/shiyu-coder/Kronos"

        fun load(dir: File): KronosModel = KronosModel(SafeTensors.open(File(dir, TOKENIZER_FILE)), SafeTensors.open(File(dir, MODEL_FILE)))

        fun installed(dir: File): Boolean =
            File(dir, MODEL_FILE).let { it.exists() && it.length() == MODEL_BYTES } && File(dir, TOKENIZER_FILE).let { it.exists() && it.length() == TOKENIZER_BYTES }

        /** 앞으로 올 거래일 시각. 주식은 주말을 건너뛰고, 코인은 매일. */
        fun futureDays(last: Long, steps: Int, market: Market): LongArray {
            val out = LongArray(steps)
            var d = java.time.Instant.ofEpochSecond(last).atZone(KST).toLocalDate()
            for (i in 0 until steps) {
                d = d.plusDays(1)
                if (market != Market.CRYPTO) while (d.dayOfWeek == java.time.DayOfWeek.SATURDAY || d.dayOfWeek == java.time.DayOfWeek.SUNDAY) d = d.plusDays(1)
                out[i] = d.epochKst()
            }
            return out
        }
    }
}
