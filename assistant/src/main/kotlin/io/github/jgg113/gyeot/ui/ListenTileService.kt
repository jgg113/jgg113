package io.github.jgg113.gyeot.ui

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.jgg113.gyeot.audio.ListenState
import io.github.jgg113.gyeot.audio.ListeningService

/** 빠른 설정 타일: 누르면 듣기 잠시 멈춤 ↔ 다시 듣기 (회의·사적인 대화 때 바로 끄기). */
class ListenTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val s = ListenState.state.value
        if (s.running) ListeningService.pause(this, !s.paused)
        refresh(!s.paused)
    }

    private fun refresh(pausedOverride: Boolean? = null) {
        val tile = qsTile ?: return
        val s = ListenState.state.value
        val paused = pausedOverride ?: s.paused
        tile.state = when {
            !s.running -> Tile.STATE_UNAVAILABLE
            paused -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !s.running -> "꺼짐"
                paused -> "멈춤"
                else -> "듣는 중"
            }
        }
        tile.updateTile()
    }
}
