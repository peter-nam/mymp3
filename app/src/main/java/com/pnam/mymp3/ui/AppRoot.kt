package com.pnam.mymp3.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pnam.mymp3.MyMp3Application
import com.pnam.mymp3.ui.player.PlayerScreen
import com.pnam.mymp3.ui.recent.RecentScreen
import kotlinx.coroutines.launch

/**
 * Decides between the two screens and wires them to the singletons in [MyMp3Application].
 *
 * Deliberately not using Navigation: two screens with one back step don't earn a nav graph, and
 * the back behaviour is clearer written out than configured.
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as MyMp3Application
    val scope = rememberCoroutineScope()

    val playback by app.players.state.collectAsStateWithLifecycle()
    val recents by app.recentTracks.tracks.collectAsStateWithLifecycle(initialValue = emptyList())

    var showPlayer by rememberSaveable { mutableStateOf(false) }

    // Loading a file — by share, open, or tapping a recent entry — always brings up the player.
    LaunchedEffect(playback.currentTrack?.uri) {
        if (playback.currentTrack != null) showPlayer = true
    }

    val onPlayerScreen = showPlayer && playback.currentTrack != null
    BackHandler(enabled = onPlayerScreen) { showPlayer = false }

    Crossfade(targetState = onPlayerScreen, animationSpec = tween(180), label = "screen") { showPlayerNow ->
        if (showPlayerNow) {
            PlayerScreen(
                state = playback,
                onBack = { showPlayer = false },
                onPlayPause = app.players::playPause,
                onSeek = app.players::seekTo,
                onSkipForward = app.players::skipForward,
                onSkipBackward = app.players::skipBackward,
                onDismissError = app.players::dismissError,
            )
        } else {
            RecentScreen(
                recents = recents,
                currentUri = playback.currentTrack?.uri,
                errorMessage = playback.errorMessage,
                onPlay = { uri -> app.players.play(uri) },
                onRemove = { uri -> scope.launch { app.recentTracks.remove(uri.toString()) } },
                onDismissError = app.players::dismissError,
            )
        }
    }
}
