package com.pnam.mymp3.data

import kotlinx.serialization.Serializable

/** One audio file the user has opened, plus where they stopped listening. */
@Serializable
data class Track(
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val lastPositionMs: Long = 0L,
    val lastOpenedAtMs: Long = 0L,
    val artworkPath: String? = null,
) {
    /** Fills in whatever [metadata] learned without discarding anything we already knew. */
    fun mergedWith(metadata: AudioMetadata, openedAtMs: Long): Track = copy(
        title = metadata.title.ifBlank { title },
        artist = metadata.artist.ifBlank { artist },
        album = metadata.album.ifBlank { album },
        durationMs = if (metadata.durationMs > 0L) metadata.durationMs else durationMs,
        lastOpenedAtMs = openedAtMs,
        artworkPath = metadata.artworkPath ?: artworkPath,
    )
}
