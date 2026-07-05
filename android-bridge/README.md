# OpenMonitor Bridge for Android

This is a phone-hosted RTSP-to-HLS bridge app.

## What it does
- Runs a foreground service on the Android phone.
- Accepts an RTSP stream URL.
- Uses LibVLC to convert RTSP into HLS in app cache.
- Serves the HLS playlist and segments over HTTP so another device can open them in a browser.
- Scans the LAN for ONVIF and RTSP cameras and tries to resolve a usable stream URI automatically.
- Probes common vendor RTSP path patterns including Hikvision, Dahua/Amcrest, Axis, Reolink, Foscam, and Uniview-style paths.

## What it does not do yet
- Full packet-level Wireshark-style sniffing of other apps.
- Direct Baseus protocol reverse engineering.

## Build notes
- Open `android-bridge/` in Android Studio or build it with Gradle on a machine with the Android SDK installed.
- Grant the app notification permission on Android 13+.
- Tap `Scan cameras` first, then choose a discovered camera or paste a stream URL manually.
- For Baseus X1 Pro specifically, use `Capture Baseus` with the camera IP, such as `192.168.4.25`.
- Capture mode aggressively probes the camera itself for Baseus/VicoHome-style HTTP and RTSP endpoints and logs every candidate it finds.
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
- If the scanner finds the camera IP but no stream, the camera may use a vendor path outside the current probe list and we can add it next.
- If Baseus capture mode still finds nothing, the camera may be exposing only a hub/cloud relay path or a vendor-locked stream that needs traffic capture from the phone app itself.

## GitHub Actions
- A workflow at `.github/workflows/build-android-bridge-apk.yml` builds a debug APK and uploads it as an artifact.
- The workflow runs on pushes to `main` and `codex/build-ipa-ci`, and it can also be started manually.

## Recommended next step
- If you still need more camera compatibility, the next pass is vendor-specific path probing and auth handling for the Baseus cameras.
- If you still need more camera compatibility, the next pass is app-traffic capture or camera/hub firmware analysis for the Baseus cameras.
