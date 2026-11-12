package com.pnam.mymp3.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.pnam.mymp3.MainActivity

/**
 * The single owner of playback.
 *
 * One [ExoPlayer] + one [MediaSession] serve the in-app UI, the notification, the lock screen and
 * Bluetooth AVRCP, so every control surface reports the same state. Declared with
 * `foregroundServiceType="mediaPlayback"` so it survives the screen being off or locked on
 * Android 14+; Media3 promotes it to the foreground while audio is playing.
 */
@OptIn(UnstableApi::class)
class AudioService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            // Pauses for ACTION_AUDIO_BECOMING_NOISY, i.e. when Bluetooth audio disconnects.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val mediaSession = MediaSession.Builder(this, exoPlayer)
            .setCallback(SessionCallback(exoPlayer))
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    SESSION_ACTIVITY_REQUEST_CODE,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setCustomLayout(MediaNotificationFactory.commandButtons(this, isPlaying = false))
            .setMediaButtonPreferences(MediaNotificationFactory.commandButtons(this, isPlaying = false))
            .build()

        player = exoPlayer
        session = mediaSession

        // We draw the notification's play/pause icon, so it has to be refreshed with the state.
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshCommandButtons(isPlaying)
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        requireNotNull(session)

    /**
     * If the app is swiped away while paused there is nothing left to do, so let the service go.
     * While playing, the service stays alive through its foreground notification.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun refreshCommandButtons(isPlaying: Boolean) {
        val buttons = MediaNotificationFactory.commandButtons(this, isPlaying)
        session?.setCustomLayout(buttons)
        session?.setMediaButtonPreferences(buttons)
    }

    private companion object {
        const val SESSION_ACTIVITY_REQUEST_CODE = 1001
    }
}
