package io.github.jgg113.gyeot.calls

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * 통화 녹음(m4a/AAC, 3gp/AMR 등)을 휴대폰의 기본 디코더로 풀어 16kHz 모노 PCM 조각으로 넘긴다.
 * 30분 통화도 메모리에 다 올리지 않고 조각마다 [onPcm] 을 부른다.
 */
object AudioDecoder {
    fun decode(context: Context, uri: Uri, onPcm: (ShortArray) -> Unit) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("소리 트랙이 없는 파일")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var resampler: MonoResampler? = null
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> resampler = null
                        o >= 0 -> {
                            if (info.size > 0) {
                                val out = codec.getOutputBuffer(o)!!
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val shorts = ShortArray(info.size / 2)
                                out.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                                val r = resampler ?: codec.outputFormat.let {
                                    MonoResampler(it.getInteger(MediaFormat.KEY_SAMPLE_RATE), it.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                                }.also { resampler = it }
                                onPcm(r.feed(shorts))
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }
}
