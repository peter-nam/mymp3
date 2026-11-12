# MyMP3 — Android MP3 player

**Package:** `com.pnam.mymp3` · **Platform:** Android only · **Test device:** Samsung Galaxy S25 FE

## 1. What we're building

An Android app that plays an audio file you **share to it** or **open with it**. Standard playback
controls, keeps playing with the screen off/locked, shows a rich media notification and lock-screen
controls, and responds to Bluetooth earbud buttons — including **skip forward 30s** and
**rewind 10s**, which normal media keys don't expose.

### Confirmed decisions

| Area | Decision |
|---|---|
| Stack | Kotlin + Jetpack Compose (Material 3) + **Media3** (`androidx.media3`) |
| Min / target SDK | minSdk **26** (Android 8.0) · targetSdk **36** · compileSdk **36** |
| Scope | Player screen **+** a "Recent files" list of audio opened before |
| Earbud mapping | **Next → +30s**, **Previous → −10s** (seek, not track change) |
| Extras | Rich notification + lock-screen controls with ID3 artwork; resume position per file |
| Formats | MP3 + AAC/M4A, OGG, FLAC, WAV (everything ExoPlayer supports) |
| Build | Debug APK installed via `adb` |
| Out of scope (v1) | iOS, playlists, library scanning, equalizer, sleep timer, playback speed, widgets, Android Auto |

## 2. Architecture

```
┌─────────────────────────────────────────────────────────┐
│ MainActivity (single Activity, Compose)                 │
│   • intent filters: ACTION_VIEW / ACTION_SEND (audio/*) │
│   • UI = pure MediaController client, owns no player    │
└───────────────┬─────────────────────────────────────────┘
                │ binds via MediaController
┌───────────────▼─────────────────────────────────────────┐
│ AudioService : MediaSessionService                      │
│   • owns the one ExoPlayer instance                     │
│   • publishes MediaSession (notification + lockscreen +  │
│     Bluetooth AVRCP all come from this one session)      │
│   • foregroundServiceType = "mediaPlayback"             │
└───────────────┬─────────────────────────────────────────┘
                │
   ┌────────────┴─────────────┬────────────────────────┐
   ▼                          ▼                        ▼
ExoPlayer              DefaultMediaNotification   MediaSession.Callback
(MediaSource,         Provider (artwork,          → remap NEXT/PREVIOUS
 gapless)              title, actions)             to seek ±30s / −10s
```

**The key architectural choice:** the UI never talks to `ExoPlayer` directly. It connects a
`MediaController` to `AudioService`. That means the in-app buttons, the notification, the lock
screen, the Android 13+ quick-settings media card, and the Bluetooth earbuds are all driving the
*same* session — so there is exactly one source of truth for playback state, and no state-sync bugs
between "the app says paused" and "the earbuds say playing".

### Why Media3 rather than MediaPlayer + a hand-rolled service

- `MediaSessionService` gives the notification, lock screen, and Bluetooth AVRCP routing for free
  and keeps the process alive under Android 12+ background-start rules.
- `ExoPlayer` handles MP3/AAC/OGG/FLAC/WAV uniformly, emits ID3 metadata as it plays, and supports
  precise seeking + gapless, which matters for the ±10s/30s feature.
- A hand-rolled `Service` + `MediaPlayer` + `MediaSession` works but you re-implement notification
  styling, session callbacks, and Android 14 foreground-service-type rules by hand.

## 3. How each requirement is met

### 3.1 Getting audio into the app

Intent filters on `MainActivity`:

- `ACTION_VIEW` for `audio/mpeg`, `audio/*`, `application/ogg` (schemes `content`, `file`) →
  "Open with" from Files, WhatsApp, email attachments.
- `ACTION_SEND` for the same types → the **Share** sheet ("MyMP3 ▸ Play").
- `android:launchMode="singleTask"` so opening a file while music is playing doesn't restart the
  app and kill the service.

URI handling:
- `ACTION_VIEW` content URIs → `takePersistableUriPermission()` so the file stays readable after the
  original app's temporary grant expires.
- `ACTION_SEND` grants are often non-persistable → we try to persist, and if it fails we cache the
  file's metadata immediately and surface a clear "file no longer available, re-share it" state in
  the recents list rather than a crash.
- Files are **referenced, not copied** (no import step). "Import copies into app storage" was not
  selected for v1.

### 3.2 Background playback with screen off / locked

- `AudioService` declares `android:foregroundServiceType="mediaPlayback"` and the
  `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions (the latter is required
  from Android 14+; the S25 FE runs Android 16).
- Media3 promotes the service to foreground automatically when playback starts and demotes it on
  pause, so we don't hand-manage `startForeground()`.
- `POST_NOTIFICATIONS` requested at runtime on Android 13+ — without it the media notification is
  suppressed and Android can tear the service down sooner.
- **Samsung-specific:** One UI's aggressive battery management is the #1 cause of "music stopped
  when I locked the screen". The plan includes a one-time in-app check that calls
  `PowerManager.isIgnoringBatteryOptimizations()` and deep-links to the settings page if MyMP3 isn't
  exempted, plus a documented "Settings ▸ Battery ▸ Unmonitored apps" step for One UI.

### 3.3 Bluetooth earbuds + their controls

- Android routes AVRCP (Bluetooth remote-control) commands into the app's active `MediaSession`, so
  connecting any A2DP device and pressing play/pause "just works" once the session exists.
- `MediaSession.Callback.onCommand()` remaps the commands we care about:

| Earbud / system command | What we do |
|---|---|
| `PLAY_PAUSE` | toggle play/pause |
| `SKIP_TO_NEXT` (next track / double-tap on most buds) | **`seekTo(position + 30s)`** |
| `SKIP_TO_PREVIOUS` (prev track / triple-tap) | **`seekTo(position − 10s)`** |
| `FAST_FORWARD` / `REWIND` | same +30s / −10s (some buds send these instead) |
| `SEEK_TO` | `seekTo(...)` from the notification scrubber |

- The session advertises `COMMAND_SEEK_TO`, `COMMAND_PLAY_PAUSE`, `COMMAND_SKIP_TO_NEXT`,
  `COMMAND_SKIP_TO_PREVIOUS`, `COMMAND_FAST_FORWARD`, `COMMAND_REWIND` so controllers know what's
  legal.
- **Volume** is intentionally *not* app-controlled: Bluetooth absolute volume is owned by Android's
  audio framework, and ExoPlayer follows the system media stream. Implementing our own volume slider
  would fight the earbuds' own volume buttons. (Noted as a deliberate non-feature.)
- No `BLUETOOTH_CONNECT` permission needed — we're not pairing or enumerating devices, Android
  delivers the key events to the session.

### 3.4 Notification and lock screen

- `DefaultMediaNotificationProvider` with our small icon → Media3 builds the standard MediaStyle
  notification, pulls **ID3 artwork/title/artist** from the playing item's `MediaMetadata`, and
  feeds the Android 13+ system media controls and the lock-screen card.
- The default provider's prev/next buttons would be labelled as track changes, which is now wrong.
  We swap in a custom `Notification.Factory` so the action row reads exactly:
  **`−10s` · `play/pause` · `+30s` · `stop`**, with the artwork as the large icon and the
  `MediaStyle` set to the session token so it still renders as a media notification.
- Tapping the notification opens the player screen (`setSessionActivity`).

### 3.5 Recent files + resume position

- `RecentTracksStore` backed by **DataStore Preferences + kotlinx.serialization** (a JSON list,
  capped at 100 entries). Chosen over Room to avoid a KSP dependency for one small list; Room is the
  swap-in path if the list grows or we add playlists.
- Per entry: `uri`, `title`, `artist`, `album`, cached artwork file, `durationMs`,
  `lastPositionMs`, `lastOpenedAt`.
- Title/artist/artwork come from `MediaMetadataRetriever` at open time; the artwork bitmap is
  downscaled and written to `cacheDir` so list rendering is instant.
- A `PositionTracker` coroutine samples `controller.currentPosition` every second while playing and
  writes it (debounced), plus saves on pause/stop/track-change. Reopening a track resumes where you
  left off; finishing a track clears its saved position.

### 3.6 UI

Two screens, no navigation library (a `Boolean` + `AnimatedContent` + back handling keeps the
dependency count honest for an app this size):

- **Recents** — artwork thumbnail, title, artist, duration, and a "Resume 12:34" subtitle; tap to
  play, swipe or long-press to remove. An "Open file…" action launches the SAF audio picker for the
  case where a file isn't shareable.
- **Player** — large artwork, title/artist, `Slider` scrubber with elapsed/total, and the control
  row: `−10s` · `play/pause` · `+30s`. Material 3 with dynamic color on Android 12+ (One UI's
  Material You palette), light + dark themes.

## 4. Project layout

```
mymp3/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/                      # icons, strings, themes
│       └── java/com/pnam/mymp3/
│           ├── MyMp3Application.kt
│           ├── MainActivity.kt
│           ├── player/
│           │   ├── AudioService.kt           # MediaSessionService: owns the one ExoPlayer + session
│           │   ├── PlayerProvider.kt         # MediaController wrapper; the UI's window onto playback
│           │   ├── SessionCallback.kt        # the ±30s / −10s remap (earbuds, notification, custom cmds)
│           │   ├── MediaNotificationFactory.kt  # the notification's -10s / play / +30s buttons
│           │   └── PlaybackUiState.kt        # the single state object the UI renders
│           ├── data/
│           │   ├── RecentTracksStore.kt
│           │   ├── Track.kt
│           │   └── AudioMetadataReader.kt
│           └── ui/
│               ├── AppRoot.kt
│               ├── player/PlayerScreen.kt
│               ├── recent/RecentScreen.kt
│               └── theme/{Color,Type,Theme}.kt
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/                 # Gradle 8.13 wrapper
└── PLAN.md
```

### Pinned toolchain

| Tool | Version | Why |
|---|---|---|
| Gradle wrapper | 8.13 | Matches AGP 8.13.1; boring and known-good |
| Android Gradle Plugin | 8.13.1 | Stable line, supports compileSdk 36 and JDK 21 (the JDK Android Studio ships here) |
| Kotlin | 2.2.21 | + `org.jetbrains.kotlin.plugin.compose` for the Compose compiler |
| Compose BOM | 2025.12.01 | The last Compose train that builds on AGP 8.13.1 / compileSdk 36. 2026.09.00 (Compose 1.12.1) demands AGP 9.1+ and compileSdk 37 |
| Media3 | 1.11.1 | Latest **stable** (1.12.x is still beta) |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | 26 ≈ 98% device coverage without legacy shims |

Two version traps worth knowing about, both hit during the first build:

1. **Compose BOM 2026.09.00 needs AGP 9.1+ and compileSdk 37.** Staying on AGP 8.13.1 means
   pinning the BOM to 2025.12.01. Moving to AGP 9.x + compileSdk 37 unlocks the current BOM and is
   listed as a follow-up.
2. **Media3's session API was refactored after 1.4.** `Notification.Factory`, `CommandRequest` and
   `CommandResult` no longer exist; the current shape is `MediaNotification.Provider`,
   `SessionCommand`/`SessionCommands`, `Player.Commands` and `MediaSession.ConnectionResult.accept`.
   `SessionCallback.kt` is written against the real 1.11.1 surface — which turned out to be a
   better fit for this app, because `setMediaButtonPreferences` is exactly the mechanism for
   putting explicit −10s / +30s buttons on the notification and the Android 13+ media controls.

Deliberately *not* using AGP 9.x yet: it also changes how the Kotlin plugin is applied (built-in
Kotlin), which is a migration worth doing on its own rather than as a side effect of scaffolding a
new app.

## 5. Build phases

Each phase ends in something verifiable.

1. **Scaffold** — Gradle wrapper, `settings.gradle.kts`, `app/build.gradle.kts`, manifest, theme,
   empty Compose screen. *Verify: `./gradlew assembleDebug` succeeds.*
2. **Player core** — `AudioService` + `PlayerProvider` + `MediaController` binding, `PlaybackState`
   as a `StateFlow`, Player screen wired to play/pause/seek. *Verify: audio plays, notification
   appears, controls work in-app.*
3. **Open & share** — intent filters, `onNewIntent` handling, persistable URI permissions, SAF
   "Open file…" picker, format list. *Verify: share an mp3 from Files → it plays.*
4. **Recents + resume** — `Track`, `RecentTracksStore`, `AudioMetadataReader` (ID3 tags + cached
   artwork), `PositionTracker`, Recents screen. *Verify: reopening a track resumes at the saved
   position.*
5. **Earbud controls + notification polish** — `SessionCommands` remap (Next=+30s, Previous=−10s,
   FF/RW), custom `MediaNotificationFactory` with −10s/+30s actions and artwork,
   `POST_NOTIFICATIONS` prompt, battery-optimisation check for One UI. *Verify with real earbuds.*
6. **Hardening** — audio-focus handling (pause on a phone call, duck on notifications), error states
   (missing/corrupt file), release build sanity pass, README with build/install/test steps.

## 6. Risks and mitigations

| Risk | Mitigation |
|---|---|
| One UI kills the service despite the foreground service | Runtime battery-optimisation check + documented "Unmonitored apps" step; verify on the real device in Phase 5 |
| Earbuds that send double-tap as `NEXT` vs some that send `FAST_FORWARD` | Handle both commands identically, so either mapping gives +30s |
| `ACTION_SEND` URI grant expires → track in recents becomes unplayable | Try `takePersistableUriPermission`; degrade to a clear "re-share this file" state, never a crash |
| Compose/Media3 version drift breaking the build | Pin exact versions above; Media3 on stable 1.11.1, not the 1.12 beta line |
| Notification prev/next icons misleading after remap | Custom `Notification.Factory` with explicit −10s/+30s labels (Phase 5) |
| Corrupt/unsupported file crashes ExoPlayer | `Player.Listener.onPlayerError` → surface message in UI, drop the entry from recents |

## 7. How we verify it

- `./gradlew assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- **In-app:** play/pause/seek from the Player screen.
- **Background:** press home, turn the screen off, confirm audio continues; unlock and confirm the
  notification and lock-screen card show the right state.
- **Earbuds:** connect buds, then exercise play/pause, next (+30s), previous (−10s) and volume.
- **Open/share:** from Samsung Files, share an mp3 → MyMP3; and open an mp3 from another app.
- **Resume:** play 30s, stop, reopen the same file → starts at ~0:30.

## 8. Later ideas (not v1)

Playlists · full storage browser · playback speed · sleep timer · import-to-app-storage · home-screen
widget · Android Auto · AGP 9.x + Gradle 9.5 upgrade · signed release + Play internal track.
