# OpenMonitor

OpenMonitor started as a local camera monitor and now includes a network camera viewer path.

## What it does
- Browses the local Wi‑Fi network for cameras using Bonjour and ONVIF WS-Discovery.
- Deep-scans the local subnet for RTSP/HTTP camera endpoints and Baseus hub fingerprints.
- Plays RTSP and ONVIF-derived streams with VLC-backed playback.
- Keeps the original local camera preview as a separate tab.

## Current constraints
- Baseus X1 Pro does not advertise RTSP/ONVIF publicly, so the app probes for local endpoints and stream URLs instead of assuming a vendor protocol.
- If the camera only exposes a cloud/hub-controlled feed, the app will show the discovered hub or web endpoint but may still require a manual stream path.

## Setup
- Open `OpenMonitor.xcodeproj` in Xcode.
- Let Xcode resolve the `VLCKitSPM` Swift package dependency.
- Run on a physical iPhone or iPad so local network discovery can prompt for permission.

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
- Use **Rescan** for quick Bonjour/ONVIF discovery.
- Use **Deep Scan** when you want the app to inspect the subnet for hidden RTSP/HTTP endpoints.
- If a camera is found but does not play immediately, open it manually and adjust the path or credentials.
