# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Subwave Auto is an Android app that streams a single Icecast station. It shows
current artist/track/artwork (via ICY metadata + iTunes Search API lookup) and
supports Android Auto / Android Automotive OS. There is no multi-station
browsing — the app always plays one configured stream.

## Commands

Build and install (device/emulator must be connected via `adb`):

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Other useful `adb` commands for verifying behavior on-device (all pre-approved
in `.claude/settings.json`): `adb logcat`, `adb shell dumpsys`, `adb shell pm list`,
`adb shell getprop`, `adb pull`, `adb shell screencap`.

There are no unit/instrumented tests and no lint/CI configuration in this repo
currently — verification is done by building and running on a device, an
emulator, or the Android Auto Desktop Head Unit (launched from Android Studio's
SDK tools) to exercise the car UI.

## Architecture

Single Gradle module (`:app`), package `com.subwave.radio`, organized by
responsibility:

- `player/` — ExoPlayer/Media3 playback, `RadioPlaybackService`
  (`MediaLibraryService`), URL normalization, error mapping, driving-state
  detection.
- `data/` — `SharedPreferences` persistence (`ServerPrefs`) and iTunes
  metadata lookup (`TrackMetadataLookup`).
- `ui/` — Compose UI: `MainActivity`, `PlayerViewModel`, `PlayerScreen`.

### Playback service is the source of truth for now-playing metadata

`RadioPlaybackService` (player/RadioPlaybackService.kt) is a Media3
`MediaLibraryService` exposing a minimal single-item browse tree (Android Auto
requires a browse tree even for a one-station app). It owns the `ExoPlayer`
instance and is the **only** place that observes raw ICY `Player.Listener.onMetadata`
events — Media3 does not sync those to a connected `MediaController`, only the
aggregated `MediaMetadata` of the current item. So the service parses ICY
"Artist - Title" strings, looks up artwork/year via `TrackMetadataLookup`, and
republishes the result by replacing the current `MediaItem`'s `MediaMetadata`
(`replaceCurrentMetadata`). `MainActivity` connects a `MediaController` to this
service and hands it to `PlayerViewModel`, which listens for
`onMediaMetadataChanged` to update the UI — it never talks to ICY data
directly.

### Stopping playback on car disconnect

Two independent signals both stop playback when the phone is unplugged from
the car, because neither is reliable alone:

1. `MediaLibrarySession.Callback.onDisconnected` — fires when Android Auto
   (`com.google.android.projection.gearhead`) tears down its controller
   connection.
2. A `BroadcastReceiver` for `ACTION_POWER_DISCONNECTED` — the same USB cable
   carries both data and power in virtually every car setup, so a power-loss
   event is a faster/more hardware-level signal. It only acts while
   `isAndroidAutoConnected` is true, so unplugging a charger during ordinary
   phone-only playback doesn't stop the stream.

### Driving-state / parked-mode gating

Free-text input (the server address field) is only usable when the car does
not require distraction-optimized UI. `PlayerViewModel.requiresParkedMode` is
derived from two independent sources that are each only meaningful on one
side of the phone/Automotive split:

- `CarDrivingState` (player/CarDrivingState.kt) wraps `android.car`'s
  `CarUxRestrictionsManager` directly. It must only be constructed after
  checking `PackageManager.FEATURE_AUTOMOTIVE` — that class is otherwise
  never touched, and its classes never loaded, on a regular phone. Real UX
  restriction changes (vehicle moving) only come from here, and only on
  native Automotive OS.
- `CarConnection` (androidx.car.app) reports projection state on phones. A
  phone has no `CarUxRestrictionsManager` to query even while connected to
  Android Auto, so `PlayerViewModel` treats "connected via projection" itself
  as reason enough to require parked mode — more conservative than gating on
  actual motion, but the best signal available from the phone side.

`app/build.gradle.kts` depends on `android.car.jar` from the SDK's `optional/`
platform dir as `compileOnly` (never packaged; real classes come from the OS)
specifically to support `CarDrivingState`.

### Stream URL normalization

`buildIcecastStreamUrl` (player/IcecastUrlBuilder.kt) is the single place
that turns user-entered server addresses into a full stream URL: adds
`http://` if no scheme given, adds port `7700` if unspecified, and always
targets `/stream.mp3` regardless of what path the user typed. Used both from
`PlayerViewModel.playFromUserInput` (interactive connect) and
`RadioPlaybackService.onPlaybackResumption` (system-initiated resume, e.g.
"Hey Google, play Subwave" with no phone app session active).

### Server address persistence

`ServerPrefs` (data/ServerPrefs.kt) persists the raw (un-normalized) address
the user typed, and only after a successful connection — a failed connection
is never saved. This is what `onPlaybackResumption` and
`reconnectToLastServerIfAvailable` read on cold start/system resume.
