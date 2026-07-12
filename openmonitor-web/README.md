# OpenMonitor Web

Web-based Baseus security camera viewer for legacy devices like the **iPad 2**.

Converts your iPad 2 into a dedicated security monitor by running a local web server that discovers and transcodes camera feeds for viewing in Safari.

## How it Works

1. **Camera Discovery** — Scans your local network for Baseus cameras (and other RTSP/IP cameras) by probing common ports and fingerprinting HTTP services
2. **Stream Transcoding** — Uses `ffmpeg` to convert camera streams into MJPEG format compatible with old browsers
3. **Web Dashboard** — Serves a lightweight, iPad-optimized dashboard using `multipart/x-mixed-replace` MJPEG streams

## Requirements (Raspberry Pi)

- **Raspberry Pi** (3, 4, or 5) with **Raspberry Pi OS** (or any Linux distro)
- **Node.js** 18+
- **ffmpeg** (for stream transcoding)
- Network connection to the same LAN as your Baseus cameras

## Quick Start

### 1. Install Node.js and ffmpeg

```bash
# Install Node.js (if not already installed)
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs

# Install ffmpeg
sudo apt-get install -y ffmpeg

# Verify installations
node --version
ffmpeg -version
```

### 2. Download and Setup

```bash
# Clone or copy the project to your Pi
# (copy from your computer via USB/SD card, or clone from a repo)

# If copying via SCP from your computer:
# scp -r openmonitor-web/ pi@raspberrypi.local:/home/pi/

# Then on the Pi:
cd /home/pi/openmonitor-web

# Install dependencies
npm install
```

### 3. Run the Server

```bash
npm start
```

You should see:
```
[Server] ffmpeg detected ✓
[Server] Local network: 192.168.1.42 (prefix: 192.168.1)
[Server] Starting initial camera discovery...
...
║  Dashboard:  http://192.168.1.42:8080/  ║
```

### 4. Open on iPad 2

1. Connect your iPad 2 to the same Wi-Fi network as the Raspberry Pi
2. Open Safari
3. Navigate to: `http://<raspberry-pi-ip>:8080/`
   (e.g., `http://192.168.1.42:8080/`)
4. Tap **Scan** to discover cameras
5. Tap **▶** on any camera to start streaming

## Configuration

Edit `config.js` to customize:

| Setting | Default | Description |
|---------|---------|-------------|
| `server.port` | `8080` | Web server port |
| `discovery.ports` | `[80, 443, 554, 8554, 8000, 8080, 8899, 5000, 7000]` | Ports to scan |
| `discovery.subnetPrefix` | `null` (auto-detect) | Force a subnet, e.g. `'192.168.1'` |
| `stream.fps` | `10` | MJPEG frame rate |
| `stream.quality` | `5` | Image quality (2=best, 31=worst) |
| `stream.width` | `512` | Output width in pixels |

Override via environment variables:
```bash
PORT=3000 npm start          # Use port 3000
```

## Manual Camera Setup

If auto-discovery doesn't find your cameras, add them manually:

1. Click **+ Add Manual** on the dashboard
2. Enter the camera IP and port
3. Select **RTSP** or **HTTP** transport
4. For RTSP: try paths like `/live`, `/stream`, `/h264`, or `/video`
5. Add credentials if needed
6. Click **Add Camera**

### Common RTSP paths to try:
- `/stream`
- `/live`
- `/live/ch00` (or `/ch0`)
- `/h264_stream`
- `/video`
- `/cam/realmonitor?channel=1&subtype=0`
- `/axis-media/media.amp`

## iPad 2 Tips

- **Add to Home Screen**: Tap the Share button → "Add to Home Screen" for a fullscreen app-like experience
- **Orientation**: The dashboard works in both portrait and landscape
- **Fullscreen mode**: Tap the **Fullscreen** button to expand camera cards to full width
- **Auto-refresh**: The dashboard auto-refreshes camera status every 10 seconds

## Auto-start on Boot (Raspberry Pi)

```bash
# Install PM2 process manager
sudo npm install -g pm2

# Start OpenMonitor with PM2
pm2 start /home/pi/openmonitor-web/server.js --name openmonitor

# Save the PM2 config
pm2 save

# Enable PM2 to start on boot
sudo pm2 startup
```

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Dashboard UI |
| `/api/status` | GET | Server health & stats |
| `/api/discover` | GET | Trigger camera discovery |
| `/api/cameras` | GET | List discovered cameras |
| `/api/cameras` | POST | Add a camera manually |
| `/api/cameras/:id/start` | POST | Start streaming |
| `/api/cameras/:id/stop` | POST | Stop streaming |
| `/api/cameras/:id/status` | GET | Stream status |
| `/api/cameras/:id` | DELETE | Remove a camera |
| `/api/streams` | GET | List active streams |
| `/stream/:id` | GET | MJPEG stream URL |

## Troubleshooting

### No cameras found
- Verify cameras are on the same network
- Try adding manually with known IP and RTSP path
- Run `sudo nmap -p 554,80,8000,8080 192.168.1.0/24` to check for open ports
- Some Baseus cameras only expose streams through the cloud app — these won't be discoverable locally

### Black / blank video
- The camera may use authentication: add credentials in the manual form
- Try different RTSP paths
- Check that ffmpeg is installed: `ffmpeg -version`
- View server logs for ffmpeg error messages

### Dashboard not loading
- Check the iPad can reach the Pi: `ping <pi-ip>` from another device
- Ensure port 8080 is not blocked by a firewall
- Check server is running: `ps aux | grep node`
