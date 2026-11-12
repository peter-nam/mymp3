package com.pnam.mymp3

import android.app.Application
import com.pnam.mymp3.data.RecentTracksStore
import com.pnam.mymp3.player.PlayerProvider

/**
 * Holds the two long-lived singletons.
 *
 * They live here rather than in the Activity so that "open with" / "share to" — which can
 * recreate the Activity — never drops the connection to the playback service.
 */
class MyMp3Application : Application() {

    val recentTracks: RecentTracksStore by lazy { RecentTracksStore(this) }

    val players: PlayerProvider by lazy { PlayerProvider(this, recentTracks) }
}
