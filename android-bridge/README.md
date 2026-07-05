# OpenMonitor Bridge for Android

This is a phone-hosted RTSP-to-HLS bridge app.

## What it does
- Runs a foreground service on the Android phone.
- Accepts an RTSP stream URL.
- Uses LibVLC to convert RTSP into HLS in app cache.
- Serves the HLS playlist and segments over HTTP so another device can open them in a browser.

## What it does not do yet
- Camera discovery on Android.
- ONVIF probing.
- Baseus-specific reverse engineering.

## Build notes
- Open `android-bridge/` in Android Studio or build it with Gradle on a machine with the Android SDK installed.
- Grant the app local network and notification permissions on Android 16.
- Enter a full RTSP URL, start the bridge, and open the printed HLS URL from your iPad or another browser.

## GitHub Actions
- A workflow at `.github/workflows/build-android-bridge-apk.yml` builds a debug APK and uploads it as an artifact.
- The workflow runs on pushes to `main` and `codex/build-ipa-ci`, and it can also be started manually.

## Recommended next step
- If this version works for your cameras, I can add LAN discovery and a cleaner iPad dashboard later.
