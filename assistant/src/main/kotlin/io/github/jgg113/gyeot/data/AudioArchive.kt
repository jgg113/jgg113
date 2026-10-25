package io.github.jgg113.gyeot.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import io.github.jgg113.gyeot.ai.EventRules
import io.github.jgg113.gyeot.audio.Gain
import io.github.jgg113.gyeot.audio.SpeechSegmenter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate

/**
 * 말소리 녹음 보관함: 받아쓴 말마다 그때 소리를 AAC(m4a, 32kbps)로 휴대폰 안에 저장하고, 정한 날짜(기본 3일)가 지나면 지운다.
 * 조용한 시간은 저장하지 않으므로 하루 대화 3시간이면 40MB 안팎이다. 들을 때 잘 들리게 작은 소리는 키워서 저장한다.
 */
class AudioArchive(context: Context) {
    private val root = File(context.filesDir, "gyeot/audio").apply { mkdirs() }

    /** @return 보관함 안의 상대 경로 ("2026-10-25/1729834567890.m4a"), 실패하면 빈 문자열 */
    fun save(at: Long, pcm: ShortArray): String = runCatching {
        val day = Store.dayOf(at)
        val rel = "$day/$at.m4a"
        val file = File(root, rel).apply { parentFile?.mkdirs() }
        encode(Gain.normalize(pcm), file)
        rel
    }.getOrDefault("")

    fun file(rel: String): File? = rel.takeIf { it.isNotBlank() }?.let { File(root, it) }?.takeIf { it.exists() }

    /** [days] 일보다 오래된 녹음을 지운다. 0 이면 모두 지운다. */
    fun purge(days: Int) {
        val cutoff = LocalDate.now(EventRules.SEOUL).minusDays((days - 1).coerceAtLeast(0).toLong())
        root.listFiles().orEmpty().forEach { dir ->
            val d = runCatching { LocalDate.parse(dir.name) }.getOrNull() ?: return@forEach
            if (days <= 0 || d.isBefore(cutoff)) dir.deleteRecursively()
        }
    }

    /** 보관 중인 녹음 크기 (MB). */
    fun sizeMb(): Double = root.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_000_000.0

    private fun encode(pcm: ShortArray, out: File) {
        val rate = SpeechSegmenter.SAMPLE_RATE
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(pcm) }.array()
        var offset = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val n = minOf(buf.remaining(), bytes.size - offset, 8_192)
                        val timeUs = offset / 2 * 1_000_000L / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(bytes, offset, n)
                            codec.queueInputBuffer(i, 0, n, timeUs, 0)
                            offset += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxing) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (muxing) muxer.stop() }
            muxer.release()
        }
    }
}
