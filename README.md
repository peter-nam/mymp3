# MyMP3

A simple Android audio player: share an MP3 to it (or open one with it) and it plays — with the
screen off, with lock-screen and notification controls, and with working Bluetooth earbud buttons.

Android only. See [PLAN.md](PLAN.md) for the design and the reasoning behind it.

## What it does

- **Plays any audio Android supports** — MP3, AAC/M4A, OGG, FLAC, WAV.
- **Two ways in:** the Android **Share** sheet ("Play in MyMP3") and **Open with…**, plus an
  in-app file picker for anything that was never shared.
- **Background playback** via a `mediaPlayback` foreground service, so audio keeps going when the
  screen is off or locked.
- **Earbud controls**, including the two the standard media keys don't expose:
  | Earbud action | Result |
  | --- | --- |
  | Play/pause | play / pause |
  | Next (often double-tap) | **skip forward 30s** |
  | Previous (often triple-tap) | **rewind 10s** |
  | Volume | system media volume (Android's Bluetooth absolute volume) |

  The same distances are on the app's own `-10s` / `+30s` buttons and on the notification's
  next/previous actions.
- **Rich notification and lock-screen card** with ID3 artwork, title and artist.
- **Recent files list** that remembers where each file stopped, so reopening one resumes it.

## Requirements

| | |
| --- | --- |
| Android | 8.0 (API 26) and up — target SDK 36 |
| JDK | 17 or newer (Android Studio's bundled JBR is fine) |
| Gradle | 8.13 via the wrapper |
| AGP | 8.13.1, with Kotlin 2.2.21, Compose BOM 2025.12.01 and Media3 1.11.1 |

## Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A signed release build (debug-signed, so it installs without a keystore):

```bash
./gradlew assembleRelease
```

## Testing on a phone

1. **Playback** — open the app, tap *Open file*, pick an audio file. Play/pause and drag the
   scrubber.
2. **Share** — in Files (or any app), share an MP3 → *Play in MyMP3*. It starts playing and the
   app opens on the player screen.
3. **Background** — press Home, then lock the phone. Audio should continue; the notification and
   the lock-screen card should show the track and working controls.
4. **Earbuds** — connect them and try play/pause, then next (should jump **+30s**) and previous
   (should jump **−10s**), then the volume buttons.
5. **Resume** — play ~30s, stop, reopen the same file from the recents list → it starts at ~0:30.

**Samsung/One UI note:** if playback still stops when the screen locks, put the app on the battery
exemption list — *Settings ▸ Battery ▸ Background usage limits ▸ Never sleeping apps* — or accept
the in-app prompt. One UI's battery management is the usual cause, not the app.

## Layout

```
app/src/main/java/com/pnam/mymp3/
├── MyMp3Application.kt        # app-scoped singletons
├── MainActivity.kt            # Compose host + ACTION_VIEW / ACTION_SEND entry points
├── player/
│   ├── AudioService.kt        # MediaSessionService — owns the one ExoPlayer + MediaSession
│   ├── PlayerProvider.kt      # MediaController wrapper; the UI's only window onto playback
│   ├── SessionCallback.kt     # maps next/previous (and FF/RW) to +30s / −10s
│   ├── MediaNotificationFactory.kt  # the notification's -10s / play / +30s command buttons
│   └── PlaybackUiState.kt     # the single state object the UI renders
├── data/
│   ├── Track.kt               # a file the user opened, + where it stopped
│   ├── RecentTracksStore.kt   # DataStore-backed recents list
│   └── AudioMetadataReader.kt # ID3 tags + embedded artwork
└── ui/
    ├── AppRoot.kt             # switches between the two screens
    ├── player/PlayerScreen.kt
    ├── recent/RecentScreen.kt
    ├── common/RememberImageBitmap.kt
    └── theme/                 # Material 3, Material You on Android 12+
```

The important rule in this codebase: **the UI never touches `ExoPlayer`.** It renders
`PlaybackUiState` and calls `PlayerProvider`, which forwards to the `MediaController` that the
notification and the earbuds also drive. That is what keeps the app, the notification and the
Bluetooth controls from ever disagreeing.

## Not in v1

Playlists, full storage browsing, playback speed, sleep timer, importing files into app storage,
home-screen widget, Android Auto.
