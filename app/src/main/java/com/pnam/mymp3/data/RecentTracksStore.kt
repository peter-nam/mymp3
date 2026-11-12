package com.pnam.mymp3.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
private val Context.preferencesStore: DataStore<Preferences> by preferencesDataStore(name = "mymp3")

/**
 * The "Recent files" list, plus each file's saved playback position.
 *
 * A single JSON blob in Preferences rather than Room: the list is capped at [MAX_ENTRIES] and is
 * always read whole, so a database and its annotation processor would be more machinery than the
 * feature needs. Swapping in Room later only touches this file.
 */
class RecentTracksStore(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val store: DataStore<Preferences> get() = appContext.preferencesStore

    val tracks: Flow<List<Track>> = store.data.map { preferences -> decode(preferences[KEY_TRACKS]) }

    suspend fun find(uri: String): Track? = read().firstOrNull { it.uri == uri }

    suspend fun positionOf(uri: String): Long = find(uri)?.lastPositionMs ?: 0L

    /** Inserts [track], or moves it to the front if it is already known, capped at [MAX_ENTRIES]. */
    suspend fun record(track: Track) {
        write(
            read()
                .filterNot { it.uri == track.uri }
                .let { existing -> listOf(track) + existing }
                .take(MAX_ENTRIES),
        )
    }

    suspend fun savePosition(uri: String, positionMs: Long) {
        write(read().map { if (it.uri == uri) it.copy(lastPositionMs = positionMs) else it })
    }

    suspend fun remove(uri: String) {
        write(read().filterNot { it.uri == uri })
    }

    suspend fun clear() = write(emptyList())

    private suspend fun read(): List<Track> = decode(store.data.first()[KEY_TRACKS])

    private suspend fun write(tracks: List<Track>) {
        store.edit { preferences -> preferences[KEY_TRACKS] = json.encodeToString(tracks) }
    }

    private fun decode(raw: String?): List<Track> =
        raw?.let { runCatching { json.decodeFromString<List<Track>>(it) }.getOrNull() } ?: emptyList()

    private companion object {
        const val MAX_ENTRIES = 100
        val KEY_TRACKS = stringPreferencesKey("recent_tracks")
    }
}
