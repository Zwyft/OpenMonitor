# OpenMonitor Bridge for Android

This is a phone-hosted RTSP-to-HLS bridge app.

## What it does
- Runs a foreground service on the Android phone.
- Accepts an RTSP stream URL.
- Uses LibVLC to convert RTSP into HLS in app cache.
- Serves the HLS playlist and segments over HTTP so another device can open them in a browser.
- Scans the LAN for ONVIF and RTSP cameras and tries to resolve a usable stream URI automatically.
- Probes common vendor RTSP path patterns including Hikvision, Dahua/Amcrest, Axis, Reolink, Foscam, and Uniview-style paths.
- Logs into Vicohome/Baseus cloud accounts and pulls recent device/event clips from the cloud API.
- Tries Vicohome cloud regions automatically first, then lets you force US or EU if your account is region-bound.
- Stores the active cloud session in memory so the phone-hosted `/live` page can request a WebRTC ticket and play live video directly in Safari.
- Starts a local HTTP proxy on the phone so you can route the Baseus app through it and capture its cloud endpoints.
- Starts a VPN-based packet capture mode for the Baseus app when proxy capture is ignored.

## What it does not do yet
- Full packet-level Wireshark-style sniffing of other apps.
- Direct Baseus protocol reverse engineering.

## Build notes
- Open `android-bridge/` in Android Studio or build it with Gradle on a machine with the Android SDK installed.
- Grant the app notification permission on Android 13+.
- Tap `Scan cameras` first, then choose a discovered camera or paste a stream URL manually.
- For Baseus X1 Pro specifically, use `Capture Baseus` with the camera IP, such as `192.168.4.25`.
- Capture mode aggressively probes the camera itself for Baseus/VicoHome-style HTTP and RTSP endpoints and logs every candidate it finds.
- For app traffic capture, start proxy capture, then set the phone's Wi‑Fi proxy to `127.0.0.1:18481` before opening the Baseus app.
- The proxy logs hostnames and request targets; HTTPS bodies still depend on whether the app honors a local proxy and trusts a user CA.
- If the app ignores the proxy, start VPN capture instead. Use `DNS only (safe)` if you want to keep the app connected; use `Deep capture` only when you are willing to interrupt traffic for more aggressive logging.
- VPN capture logs DNS and TCP/UDP destinations for the Baseus app package `com.baseus.security.ipc`.
- Use the `Log filter` spinner and `Search` field in the phone app to narrow the log view.
- Tap `Copy filtered logs` or `Copy full logs` in the phone app to put the log text on the clipboard so you can paste it here.
- If you still want a file export, open `http://<phone-ip>:18480/api/logs.txt` in a browser, but the app copy buttons are the reliable path when the web page is unavailable.
- If local probing fails, enter your Vicohome account email/password, pick a region, and tap `Vicohome sync` to load cloud devices and recent clips.
- Leave the region picker on `Auto (US then EU)` if you are not sure which backend your Baseus account uses.
- Recent clips are HLS URLs from Vicohome’s cloud API; tap one to start playback on the phone or open the URL from the iPad browser.
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
- If the Baseus app ignores the proxy or pins TLS, we will need a VPN capture path next.
- If VPN capture shows only local IPs or nothing useful, the app may be using certificate pinning, QUIC, or another backend path that needs deeper inspection.
- If VPN capture disconnects the camera, switch the mode to `DNS only (safe)`; `Deep capture` intentionally intercepts more traffic and can interrupt the app.
- If you need the logs after a crash, download `openmonitor-bridge.log` from the phone-hosted server before restarting the app.
- If you need the logs after a crash and the page does not load, use `Copy full logs` in the app before restarting it.
- If Vicohome sync fails with `account not registered`, switch the region picker from `Auto` to `US` or `EU` and retry.
- If Vicohome sync succeeds but clips still fail to play, the cloud URL may be expiring too quickly or the account may require a different region/API host.
- If the cloud account sync succeeds, open `http://<phone-ip>:18480/live` from the iPad to start the Baseus cloud live viewer.
- If the live viewer says the cloud session is missing, re-run `Vicohome sync` in the Android app first.
- If the live viewer opens but never shows video, the cloud service may be rejecting browser-only signaling on your account or region and we will need a relay step next.

## GitHub Actions
- A workflow at `.github/workflows/build-android-bridge-apk.yml` builds a debug APK and publishes a release asset named `OpenMonitorBridge.apk`.
- The workflow runs on pushes to `main` and `codex/build-ipa-ci`, and it can also be started manually.
- The direct download URL is `https://github.com/Zwyft/OpenMonitor/releases/download/android-bridge-latest/OpenMonitorBridge.apk`.

## Recommended next step
- If you need Baseus live video, open `http://<phone-ip>:18480/live` after cloud sync and use the browser WebRTC viewer.
- If live video still fails, the next pass is cloud-session debugging or a signaling relay on the phone.
- If you still need more camera compatibility after that, the fallback path is app-traffic capture or vendor-specific camera/hub analysis.
