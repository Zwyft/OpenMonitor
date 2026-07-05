# OpenMonitor

OpenMonitor now has two paths:
- a browser dashboard you can run on a laptop and open from an old iPad, and
- the original native iOS app path that you can keep for newer devices later.

## What it does
- Browses the local Wi‑Fi network for cameras with ONVIF WS-Discovery and subnet probing.
- Proxies snapshot, HTTP, and HLS endpoints through a laptop-hosted web dashboard.
- Keeps the native Swift/iOS project in the repo for later use on a newer iPad.

## Current constraints
- The iPad 2 should use the browser dashboard; it is the realistic no-signing path.
- If a camera only exposes RTSP, the browser can still list it, but playback needs HLS or a snapshot endpoint.
- Baseus X1 Pro does not advertise RTSP/ONVIF publicly, so the dashboard probes for local endpoints instead of assuming a vendor protocol.

## Browser setup
- Run `scripts/run-web.sh` on your laptop.
- Install `ffmpeg` on that laptop if you want RTSP-only cameras to play in Safari through the bridge.
- Open the printed URL from Safari on the iPad.
- Use **Rescan** for quick ONVIF discovery, or **Deep Scan** to probe the subnet.
- Add a manual RTSP, HLS, or ONVIF endpoint if discovery misses the camera.

## Android phone bridge
- The repo now includes an installable Android bridge app in `android-bridge/`.
- It is the better path if you want the phone itself to host the RTSP-to-HLS bridge.
- Open `android-bridge/` in Android Studio, build an APK, install it on the phone, and start a bridge from the app.
- The app serves the HLS URL over HTTP so the iPad can open it on the same Wi‑Fi.

## Native app path
- Open `OpenMonitor.xcodeproj` in Xcode.
- Let Xcode resolve the `VLCKitSPM` Swift package dependency.
- The native app remains available for a later iPad build.

## Build an IPA
- Use `scripts/build-ipa.sh` on a Mac with Xcode installed.
- The script archives the `OpenMonitor` scheme and exports a development IPA by default.
- Override `METHOD=ad-hoc` if you want a distribution IPA for provisioned devices.
- For manual signing, set `SIGNING_STYLE=manual`, `PROVISIONING_PROFILE_SPECIFIER=<profile name>`, and `CODE_SIGN_IDENTITY=<signing identity>`.

## GitHub Actions IPA
- The repo includes `.github/workflows/build-ipa.yml`, which exports an IPA on push to `main` and on manual dispatch.
- Configure these repository secrets before relying on the workflow:
  - `IOS_SIGNING_KEYCHAIN_PASSWORD`
  - `IOS_SIGNING_CERT_BASE64`
  - `IOS_SIGNING_CERT_PASSWORD`
  - `IOS_SIGNING_PROFILE_BASE64`
- The workflow uploads the result as the `OpenMonitor-ipa` artifact.

## Tips
- Use **Rescan** for quick ONVIF discovery.
- Use **Deep Scan** when you want the dashboard to inspect the subnet for hidden RTSP/HTTP endpoints.
- If a camera is found but does not play immediately, add it manually and the dashboard will try RTSP-to-HLS bridging automatically.
