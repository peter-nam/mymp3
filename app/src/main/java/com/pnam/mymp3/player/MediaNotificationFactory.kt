package com.pnam.mymp3.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import com.pnam.mymp3.R

/**
 * The playback notification's action row.
 *
 * Media3 renders the notification from the session's command buttons, so the buttons are defined
 * here rather than in a notification builder: **-10s · play/pause · +30s**. The same
 * [CommandButton] list feeds both the legacy notification (`setCustomLayout`) and the Android 13+
 * system media controls (`setMediaButtonPreferences`), so the lock screen and the notification
 * offer exactly the actions the earbuds do.
 *
 * The play/pause icon is ours to draw, which is why [AudioService] rebuilds this list whenever the
 * playing state changes.
 */
@OptIn(UnstableApi::class)
object MediaNotificationFactory {

    fun commandButtons(context: Context, isPlaying: Boolean): List<CommandButton> = listOf(
        CommandButton.Builder()
            .setSessionCommand(COMMAND_SKIP_BACKWARD_10)
            .setIconResId(R.drawable.ic_skip_previous)
            .setDisplayName(context.getString(R.string.label_back_10))
            .build(),

        CommandButton.Builder()
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setIconResId(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            .setDisplayName(context.getString(if (isPlaying) R.string.pause else R.string.play))
            .build(),

        CommandButton.Builder()
            .setSessionCommand(COMMAND_SKIP_FORWARD_30)
            .setIconResId(R.drawable.ic_skip_next)
            .setDisplayName(context.getString(R.string.label_forward_30))
            .build(),
    )
}
