package com.pnam.mymp3.player

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The seek distances the whole app agrees on.
 *
 * Bluetooth remotes only expose play/pause, next and previous, so "next" and "previous" get a
 * per-file meaning instead of a queue meaning. The app's buttons and the notification's buttons
 * use these same two constants.
 */
const val SKIP_FORWARD_MS = 30_000L
const val SKIP_BACKWARD_MS = 10_000L

/** Custom session commands behind the notification's "-10s" and "+30s" buttons. */
val COMMAND_SKIP_FORWARD_30 = SessionCommand("com.pnam.mymp3.SKIP_FORWARD_30", Bundle.EMPTY)
val COMMAND_SKIP_BACKWARD_10 = SessionCommand("com.pnam.mymp3.SKIP_BACKWARD_10", Bundle.EMPTY)

/**
 * Rewires what the session does with remote-control input.
 *
 * Left to itself, "next" calls `player.seekToNext()` — with one item loaded that jumps to the end
 * of the track. Three surfaces are remapped here:
 *
 *  - [onMediaButtonEvent] — the actual Bluetooth AVRCP key events from the earbuds.
 *  - [onCustomCommand] — taps on the notification's custom -10s / +30s buttons.
 *  - [onConnect] — advertises the custom commands to every controller that connects, so the
 *    system media controls and the earbuds know they exist.
 */
@OptIn(UnstableApi::class)
class SessionCallback(
    private val player: Player,
) : MediaSession.Callback {

    override fun onConnect(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult = MediaSession.ConnectionResult.accept(
        SessionCommands.Builder()
            .addSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.commands)
            .add(COMMAND_SKIP_FORWARD_30)
            .add(COMMAND_SKIP_BACKWARD_10)
            .build(),
        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
    )

    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent,
    ): Boolean {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) {
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }
        val keyEvent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            ?: return super.onMediaButtonEvent(session, controllerInfo, intent)
        // Each press arrives as a down and an up event; acting on both would double the seek.
        if (keyEvent.action != KeyEvent.ACTION_DOWN) return true

        return when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> {
                seekBy(SKIP_FORWARD_MS)
                true
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            -> {
                seekBy(-SKIP_BACKWARD_MS)
                true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            -> {
                if (player.isPlaying) player.pause() else player.play()
                true
            }

            else -> super.onMediaButtonEvent(session, controllerInfo, intent)
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val recognised = when (customCommand) {
            COMMAND_SKIP_FORWARD_30 -> {
                seekBy(SKIP_FORWARD_MS)
                true
            }

            COMMAND_SKIP_BACKWARD_10 -> {
                seekBy(-SKIP_BACKWARD_MS)
                true
            }

            else -> false
        }

        return if (recognised) {
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        } else {
            super.onCustomCommand(session, controllerInfo, customCommand, args)
        }
    }

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration
        val target = player.currentPosition + deltaMs
        player.seekTo(if (duration > 0L) target.coerceIn(0L, duration) else target.coerceAtLeast(0L))
    }
}
