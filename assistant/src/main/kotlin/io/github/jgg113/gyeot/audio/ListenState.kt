package io.github.jgg113.gyeot.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 화면·알림에 보여 줄 듣기 상태. */
object ListenState {
    data class State(
        val running: Boolean = false,
        val paused: Boolean = false,
        val hearing: Boolean = false,
        val lastHeardAt: Long = 0,
        val lastText: String = "",
        val queued: Int = 0,
        val lastAnalysisAt: Long = 0,
        val error: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun update(f: (State) -> State) {
        _state.value = f(_state.value)
    }
}
