package com.pnam.mymp3.player

import com.pnam.mymp3.data.Track

/** Everything the UI needs to draw the player, produced by [PlayerProvider]. */
data class PlaybackUiState(
    val isConnected: Boolean = false,
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
) {
    /** 0f–1f for the scrubber; 0f while the duration is still unknown. */
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val hasTrack: Boolean
        get() = currentTrack != null
}
