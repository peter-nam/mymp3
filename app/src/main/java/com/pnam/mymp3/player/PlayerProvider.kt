package com.pnam.mymp3.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.pnam.mymp3.R
import com.pnam.mymp3.data.AudioMetadataReader
import com.pnam.mymp3.data.RecentTracksStore
import com.pnam.mymp3.data.Track
import com.pnam.mymp3.data.WaveformExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's window onto playback.
 *
 * The UI never touches [androidx.media3.exoplayer.ExoPlayer] — it observes this [StateFlow] and
 * calls back into the [MediaController], which is the same controller the notification and the
 * earbuds drive. One session, one state, no sync bugs.
 *
 * The controller is cached here (in the Application) rather than in the Activity so that
 * configuration changes and "open with" relaunches don't tear the connection down.
 */
@OptIn(UnstableApi::class)
class PlayerProvider(
    context: Context,
    private val recentTracks: RecentTracksStore,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val directExecutor = MoreExecutors.directExecutor()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var connectInFlight = false
    private var pendingUri: Uri? = null
    private var positionTickJob: Job? = null

    /** Binds a [MediaController] to [AudioService]. Safe to call as often as you like. */
    fun connect() {
        if (controller != null || connectInFlight) return
        connectInFlight = true

        val token = SessionToken(appContext, ComponentName(appContext, AudioService::class.java))
        val future: ListenableFuture<MediaController> =
            MediaController.Builder(appContext, token)
                .setListener(controllerListener)
                .buildAsync()
        future.addListener({ onControllerBuilt(future) }, directExecutor)
    }

    /** Loads [uri], resumes it where it left off, and starts playing. */
    fun play(uri: Uri) {
        val active = controller ?: run {
            // Intent arrived before the controller finished connecting.
            pendingUri = uri
            connect()
            return
        }

        // Only now — with something to play — does the service go into the foreground. Starting it
        // as a foreground service while the player is idle leaves Media3 with no notification to
        // post, and Android ANRs the app 5s later with "did not then call Service.startForeground()".
        runCatching {
            ContextCompat.startForegroundService(appContext, Intent(appContext, AudioService::class.java))
        }

        // Warms the waveform cache for the player screen without gating playback on a decode.
        scope.launch { WaveformExtractor.load(appContext, uri) }

        scope.launch {
            // Tags are decoration, and read() always answers; a genuinely unreadable file is
            // reported by ExoPlayer through onPlayerError rather than by playback silently
            // not starting.
            val existing = recentTracks.find(uri.toString())
            val metadata = withContext(Dispatchers.IO) { AudioMetadataReader.read(appContext, uri) }
            val track = Track(
                uri = uri.toString(),
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                durationMs = metadata.durationMs,
                lastPositionMs = existing?.lastPositionMs ?: 0L,
                lastOpenedAtMs = System.currentTimeMillis(),
                artworkPath = metadata.artworkPath ?: existing?.artworkPath,
            )
            val resumeAt = track.lastPositionMs.takeIf { it > 0L && it < track.durationMs } ?: 0L

            active.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(uri.toString()).build())
            active.prepare()
            if (resumeAt > 0L) active.seekTo(resumeAt)
            active.play()

            recentTracks.record(track)
            _state.update { it.copy(currentTrack = track, errorMessage = null, positionMs = resumeAt) }
        }
    }

    fun playPause() {
        val active = controller ?: return
        when {
            active.isPlaying -> active.pause()
            active.playbackState == Player.STATE_IDLE -> active.prepare()
            else -> active.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val active = controller ?: return
        val duration = active.duration
        val target = if (duration > 0L) {
            positionMs.coerceIn(0L, duration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        active.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    /** Same distance the "next" earbud button produces. */
    fun skipForward() {
        controller?.let { seekTo(it.currentPosition + SKIP_FORWARD_MS) }
    }

    /** Same distance the "previous" earbud button produces. */
    fun skipBackward() {
        controller?.let { seekTo(it.currentPosition - SKIP_BACKWARD_MS) }
    }

    fun stop() {
        val active = controller ?: return
        persistPositionNow()
        if (active.playbackState != Player.STATE_IDLE) active.stop()
        active.clearMediaItems()
        _state.update { it.copy(currentTrack = null, isPlaying = false, positionMs = 0L, durationMs = 0L) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }

    private fun onControllerBuilt(future: ListenableFuture<MediaController>) {
        connectInFlight = false
        val built = runCatching { future.get() }.getOrElse { error ->
            Log.w(TAG, "Could not connect to AudioService", error)
            _state.update {
                it.copy(errorMessage = appContext.getString(R.string.error_playback, error.localizedMessage.orEmpty()))
            }
            return
        }

        controller = built
        built.addListener(playerListener)
        _state.update { it.copy(isConnected = true) }
        syncFromPlayer(built)
        startPositionTicking(built)

        pendingUri?.let { uri ->
            pendingUri = null
            play(uri)
        }
    }

    /** Controller lifecycle — the only way the connection can disappear from under us. */
    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@PlayerProvider.controller !== controller) return
            positionTickJob?.cancel()
            this@PlayerProvider.controller = null
            _state.value = PlaybackUiState()
        }
    }

    /** Player state — everything that feeds [PlaybackUiState]. */
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            val reason = error.localizedMessage ?: error.toString()
            _state.update { it.copy(errorMessage = appContext.getString(R.string.error_playback, reason)) }
        }
    }

    private fun syncFromPlayer(player: Player) {
        _state.update { current ->
            current.copy(
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                durationMs = if (player.duration > 0L) player.duration else current.durationMs,
                positionMs = player.currentPosition,
            )
        }
    }

    /**
     * Polls the position once a second for the scrubber, and writes it to the store every few
     * ticks so a killed app still resumes close to where the listener stopped.
     */
    private fun startPositionTicking(active: MediaController) {
        positionTickJob?.cancel()
        positionTickJob = scope.launch {
            var ticks = 0
            while (isActive) {
                if (controller === active) {
                    syncFromPlayer(active)
                    if (++ticks % PERSIST_EVERY_TICKS == 0 && active.isPlaying) persistPositionNow()
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /** Saving 0 means "finished", so a track that ended starts from the top next time. */
    private fun persistPositionNow() {
        val active = controller ?: return
        val track = _state.value.currentTrack ?: return
        val duration = active.duration
        val position = active.currentPosition
        val finished = duration > 0L && position >= duration - FINISH_TOLERANCE_MS
        val positionToSave = if (finished) 0L else position
        if (positionToSave == track.lastPositionMs) return

        scope.launch {
            if (_state.value.currentTrack?.uri == track.uri) {
                recentTracks.savePosition(track.uri, positionToSave)
            }
        }
    }

    private companion object {
        const val TAG = "PlayerProvider"
        const val TICK_INTERVAL_MS = 1_000L
        const val PERSIST_EVERY_TICKS = 5
        const val FINISH_TOLERANCE_MS = 1_000L
    }
}
