# OpenMonitor Bridge for Android

This is a phone-hosted RTSP-to-HLS bridge app.

## What it does
- Runs a foreground service on the Android phone.
- Accepts an RTSP stream URL.
- Uses LibVLC to convert RTSP into HLS in app cache.
- Serves the HLS playlist and segments over HTTP so another device can open them in a browser.
- Scans the LAN for ONVIF and RTSP cameras and tries to resolve a usable stream URI automatically.

## What it does not do yet
- Camera discovery on Android.
- ONVIF probing.
- Baseus-specific reverse engineering.

## Build notes
- Open `android-bridge/` in Android Studio or build it with Gradle on a machine with the Android SDK installed.
- Grant the app notification permission on Android 13+.
- Tap `Scan cameras` first, then choose a discovered camera or paste a stream URL manually.
- Tapping a discovered camera will immediately start the bridge with that URI.
- Enter optional credentials if the camera requires them.
- Start the bridge, then open `http://<phone-ip>:18480/` from your iPad or another browser.
- Copy the HLS URL from the bridge screen if you want to load the stream directly in Safari.
- If it fails, open `http://<phone-ip>:18480/api/logs` or read the log tail in the phone app.

## Network notes
- The bridge listens on port `18480` by default.
- Keep the phone and iPad on the same Wi‑Fi network.
- Do not expose the port to the public internet; a different port is not a substitute for real security.

## Troubleshooting
- If you see `Timed out waiting for HLS output`, check the log tail for a VLC event or a startup exception.
- If the HLS URL returns `Not found`, make sure you are using the current phone IP shown on the bridge screen.

## GitHub Actions
- A workflow at `.github/workflows/build-android-bridge-apk.yml` builds a debug APK and uploads it as an artifact.
- The workflow runs on pushes to `main` and `codex/build-ipa-ci`, and it can also be started manually.

## Recommended next step
- If this version works for your cameras, I can add LAN discovery and a cleaner iPad dashboard later.
