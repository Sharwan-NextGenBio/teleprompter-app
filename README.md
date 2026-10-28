# Teleprompter (Kotlin + Jetpack Compose + Material 3)

Target: Android 14/15 (compileSdk/targetSdk 35, minSdk 26). AndroidX is enabled in the root `gradle.properties` file, as required by the dependencies in this project.

## Run it
1. Android Studio (latest stable) → Open → select this folder → wait for Gradle sync. GitHub Actions builds with Gradle 8.9 and Java 17.
2. Run on a real phone (camera, overlay and speech don't work properly in the emulator).
3. Allow Camera, Mic, Notifications. Tap **Overlay** → grant "Display over other apps" → tap again.

## Architecture (MVVM + Clean)
```
domain/   Script, ScriptRepository (pure Kotlin)
data/     Room (ScriptEntity, ScriptDao, AppDatabase), ScriptRepositoryImpl, ScriptImporter (.txt/.pdf/.docx)
engine/   TeleprompterSession (shared StateFlows), VoiceFollowController (SpeechRecognizer)
camera/   CameraRecorderManager (CameraX + MediaStore)
ui/       AutoScrollTeleprompter (Compose), RecordingScreen + RecordingViewModel
overlay/  OverlayService (floating window, foreground service, MediaSession for clickers)
```

## How the pieces work
- **Scroll speed (WPM):** px/sec = textHeight / wordCount x WPM / 60, applied per frame with sub-pixel carry.
- **Voice follow:** on-device recognizer (API 33+ when available). Mic level decides speaking/silent (scroll pauses after 800 ms silence). Partial results are matched word-by-word against the script, and the scroller re-syncs if you drift.
- **Eye-line:** the active line sits at `style.eyeLine` (12% from top). The recording screen puts the prompter in the top third, under the selfie lens.
- **Mirror:** `graphicsLayer` scaleX/scaleY flip.
- **Highlights:** wrap text in `==double equals==`.
- **Recording:** CameraX Recorder, 1080p default (4K toggle, auto-fallback), saved to `Movies/Teleprompter` through MediaStore (no storage permission needed on Android 10+). Waveform from `audioStats.audioAmplitude`.
- **Remote keys:** Space/Enter/Play-Pause = start/stop, Up/PageUp = faster, Down/PageDown = slower (in-app). Over other apps, only media keys (play/pause, next/previous) arrive via MediaSession.

## Known limits (important)
1. **Voice follow while another app records:** Android gives the microphone to the foreground app, so your overlay's recognizer may get silenced during TikTok/Instagram/Camera recording. Use WPM mode for overlay; voice follow works best inside the built-in Studio.
2. **Bluetooth keyboards over other apps:** key events go to the focused app, not your overlay. Clickers that send media keys work; ones that send Page Up/Down only work in-app.
3. **Play Store:** `specialUse` foreground service needs a declaration in Play Console, and overlay apps get extra review.
4. Still to build: scripts list/editor screens using the Room repository, a settings screen (font, colours, margins, mirror), navigation.
