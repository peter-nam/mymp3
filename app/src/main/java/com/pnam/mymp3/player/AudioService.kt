package com.pnam.mymp3.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.pnam.mymp3.MainActivity
import com.pnam.mymp3.R

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

        // 1. Channel + provider, set before anything can ask for a notification.
        //    MediaSessionService caches its MediaNotificationManager on first use and keeps the
        //    provider that manager was built with, and DefaultMediaNotificationProvider's own channel
        //    is IMPORTANCE_MIN — which One UI files under "Silent" and hides. Claiming that id
        //    ourselves at IMPORTANCE_DEFAULT makes the notification show whichever provider ends up in
        //    charge; see NOTIFICATION_CHANNEL_ID.
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.notification_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )

        // 2. Keep a notification even while the player is idle. Media3's default only shows one
        //    after a stop or an error, and a foreground service with no notification to post is
        //    exactly what triggers the "did not then call Service.startForeground()" ANR.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)

        // 3. The one player and the one session that every control surface drives.
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

        val buttons = MediaNotificationFactory.commandButtons(this, isPlaying = false)
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
            .setCustomLayout(buttons)
            .setMediaButtonPreferences(buttons)
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

        /**
         * Media3's own channel id, claimed by this app instead of left to the library.
         *
         * MediaSessionService caches its notification manager the first time it is asked for one and
         * keeps the provider that manager was built with, so a later setMediaNotificationProvider()
         * can be ignored and the notification still lands on DefaultMediaNotificationProvider's own
         * channel — IMPORTANCE_MIN, which One UI files under "Silent" and hides. Declaring that same
         * id ourselves at IMPORTANCE_DEFAULT makes the notification visible whichever provider wins.
         *
         * Android fixes a channel's importance when it is first created and never lets an app raise
         * it afterwards, so an install that already has the MIN version needs its data cleared (or a
         * reinstall) to pick this up.
         */
        val NOTIFICATION_CHANNEL_ID: String = DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
    }
}
