package io.github.jgg113.gyeot.audio

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** 보관한 녹음 다시 듣기. 여러 개를 고르면 차례로 이어서 들려준다. */
object Player {
    private var player: MediaPlayer? = null
    private var queue = ArrayDeque<Pair<String, File>>()

    private val _playing = MutableStateFlow<String?>(null)
    /** 지금 재생 중인 녹음의 키 (화면에서 ▶/■ 표시용). */
    val playing: StateFlow<String?> = _playing

    @Synchronized
    fun play(items: List<Pair<String, File>>) {
        stop()
        queue = ArrayDeque(items)
        next()
    }

    @Synchronized
    fun stop() {
        queue.clear()
        player?.runCatching { stop(); release() }
        player = null
        _playing.value = null
    }

    @Synchronized
    private fun next() {
        player?.runCatching { release() }
        player = null
        val (key, file) = queue.removeFirstOrNull() ?: run { _playing.value = null; return }
        _playing.value = key
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(file.path)
                setOnCompletionListener { next() }
                setOnErrorListener { _, _, _ -> next(); true }
                prepare()
                start()
            }
        }.getOrElse { next(); null }
    }
}
