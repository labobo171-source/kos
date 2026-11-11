# KOS — Native Android Music Player

KOS is being converted from the supplied Persian RTL music-player concept into a native Kotlin Android application. It does not use a WebView.

## Current native implementation
- Reads audio tracks from the device MediaStore after the Android audio permission is granted.
- Plays local audio using AndroidX Media3.
- Uses a MediaSessionService for background playback and system media controls.
- Provides play/pause, previous/next, shuffle, repeat, favorites, a driving-mode screen, and an equalizer entry point.
- Stores favorite track IDs locally.

## Build an APK
A GitHub Actions workflow runs on pushes to `main` and can also be started manually:
1. Open the repository's **Actions** tab.
2. Select **Android APK**.
3. Wait for the build to complete.
4. Download the `KOS-debug-apk` artifact and extract `app-debug.apk`.

The APK is a debug build for testing. A release build and final visual/functional verification are still pending.

## Project
- Package: `com.kos.player`
- Language: Kotlin
- Minimum Android: API 23
