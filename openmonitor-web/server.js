// OpenMonitor Web Server
// Express-based web server that:
// 1. Discovers Baseus cameras on the local network
// 2. Transcodes streams to MJPEG for iPad 2 Safari compatibility
// 3. Serves a mobile-optimized web dashboard

const express = require('express');
const path = require('path');
const CameraDiscovery = require('./discovery');
const StreamTranscoder = require('./streamer');
const config = require('./config');

const app = express();
const discovery = new CameraDiscovery();
const transcoder = new StreamTranscoder();

// Store discovered cameras with their stream status
let discoveredCameras = [];
let lastScanTime = null;

// ======================
// Middleware
// ======================

app.use(express.json());

// Serve static files
app.use(express.static(path.join(__dirname, 'public')));

// ======================
// API Routes
// ======================

/**
 * GET /api/discover - Start a camera discovery scan
 */
app.get('/api/discover', async (req, res) => {
  try {
    const endpoints = await discovery.discover();
    discoveredCameras = endpoints;
    lastScanTime = new Date().toISOString();
    res.json({
      success: true,
      count: endpoints.length,
      baseusCount: endpoints.filter((e) => e.isBaseus).length,
      cameras: endpoints,
      scannedAt: lastScanTime,
    });
  } catch (err) {
    res.json({
      success: false,
      error: err.message,
    });
  }
});

/**
 * GET /api/cameras - List discovered cameras
 */
app.get('/api/cameras', (req, res) => {
  res.json({
    cameras: discoveredCameras,
    count: discoveredCameras.length,
    lastScan: lastScanTime,
  });
});

/**
 * GET /api/cameras/:id/status - Get stream status for a camera
 */
app.get('/api/cameras/:id/status', (req, res) => {
  const streamStatus = transcoder.getStatus();
  const status = streamStatus[req.params.id] || null;
  res.json({
    cameraId: req.params.id,
    streaming: !!status,
    details: status,
  });
});

/**
 * GET /api/streams - List all active streams
 */
app.get('/api/streams', (req, res) => {
  const status = transcoder.getStatus();
  res.json({
    activeStreams: Object.keys(status).length,
    streams: status,
  });
});

/**
 * POST /api/cameras/:id/start - Start streaming a camera
 */
app.post('/api/cameras/:id/start', async (req, res) => {
  const camera = discoveredCameras.find((c) => c.id === req.params.id);
  if (!camera) {
    return res.status(404).json({ error: 'Camera not found' });
  }

  try {
    await transcoder.startStream(camera);
    res.json({
      success: true,
      streamUrl: `/stream/${camera.id}`,
      cameraId: camera.id,
    });
  } catch (err) {
    res.json({
      success: false,
      error: err.message,
    });
  }
});

/**
 * POST /api/cameras/:id/stop - Stop streaming a camera
 */
app.post('/api/cameras/:id/stop', (req, res) => {
  transcoder.stopStream(req.params.id);
  res.json({ success: true });
});

/**
 * POST /api/cameras/:id/credentials - Set credentials for a camera
 */
app.post('/api/cameras/:id/credentials', (req, res) => {
  const { username, password } = req.body;
  const camera = discoveredCameras.find((c) => c.id === req.params.id);
  if (!camera) {
    return res.status(404).json({ error: 'Camera not found' });
  }

  camera.username = username;
  camera.password = password;

  // Restart stream if active
  if (transcoder.getStatus()[camera.id]) {
    transcoder.stopStream(camera.id);
    transcoder.startStream(camera);
  }

  res.json({ success: true });
});

/**
 * POST /api/cameras - Add a camera manually
 */
app.post('/api/cameras', (req, res) => {
  const { host, port, name, transport, username, password, rtspPath } = req.body;

  if (!host) {
    return res.status(400).json({ error: 'Host is required' });
  }

  const transportType = transport || (port === 554 || port === 8554 ? 'RTSP' : 'HTTP');
  const cameraId = `${host}:${port || 80}`;
  const streamURL = transportType === 'RTSP'
    ? `rtsp://${host}:${port || 554}/${rtspPath || ''}`
    : `http://${host}:${port || 80}/`;

  // Remove existing if present
  const existing = discoveredCameras.findIndex((c) => c.id === cameraId);
  if (existing >= 0) {
    discoveredCameras.splice(existing, 1);
  }

  const camera = {
    id: cameraId,
    name: name || `${host}:${port || (transportType === 'RTSP' ? 554 : 80)}`,
    host,
    port: port || (transportType === 'RTSP' ? 554 : 80),
    transport: transportType,
    streamURL,
    isBaseus: false,
    confidence: 1.0,
    details: 'Manually added',
    username: username || null,
    password: password || null,
    rtspPaths: rtspPath ? [rtspPath] : [],
    discoveredAt: new Date().toISOString(),
  };

  discoveredCameras.unshift(camera);
  res.json({ success: true, camera });
});

/**
 * DELETE /api/cameras/:id - Remove a camera from the list
 */
app.delete('/api/cameras/:id', (req, res) => {
  transcoder.stopStream(req.params.id);
  discoveredCameras = discoveredCameras.filter((c) => c.id !== req.params.id);
  res.json({ success: true });
});

/**
 * GET /stream/:id - MJPEG stream endpoint for a camera
 */
app.get('/stream/:id', (req, res) => {
  const cameraId = req.params.id;
  const camera = discoveredCameras.find((c) => c.id === cameraId);

  if (!camera) {
    return res.status(404).send('Camera not found');
  }

  // Auto-start stream if not already running
  const status = transcoder.getStatus();
  if (!status[cameraId]) {
    // Start the stream synchronously (it'll be ready in a moment)
    transcoder.startStream(camera);
  }

  // Handle the MJPEG client
  transcoder.handleClient(req, res, cameraId);
});

/**
 * GET /api/status - Server health and info
 */
app.get('/api/status', (req, res) => {
  const streamStatus = transcoder.getStatus();
  res.json({
    server: 'OpenMonitor Web',
    version: '1.0.0',
    uptime: Math.floor(process.uptime()),
    cameras: {
      discovered: discoveredCameras.length,
      streaming: Object.keys(streamStatus).length,
    },
    network: discovery.getLocalNetworkInfo(),
    activeStreams: streamStatus,
  });
});

// ======================
// Dashboard Page
// ======================

/**
 * GET / - Serve the web dashboard
 */
app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'views', 'dashboard.html'));
});

// ======================
// Server Startup
// ======================

async function start() {
  // Check ffmpeg availability
  const ffmpegAvailable = await StreamTranscoder.checkFFmpeg();
  if (!ffmpegAvailable) {
    console.warn('[Server] WARNING: ffmpeg not found. Stream transcoding will not work.');
    console.warn('[Server] Install ffmpeg: sudo apt-get install ffmpeg');
  } else {
    console.log('[Server] ffmpeg detected ✓');
  }

  // Get network info
  const netInfo = discovery.getLocalNetworkInfo();
  console.log(`[Server] Local network: ${netInfo.address} (prefix: ${netInfo.subnetPrefix})`);

  // Auto-discovery on startup
  console.log('[Server] Starting initial camera discovery...');
  discovery.discover().then(endpoints => {
    discoveredCameras = endpoints;
    lastScanTime = new Date().toISOString();
    console.log(`[Server] Discovery complete: ${endpoints.length} cameras found`);
  }).catch(err => {
    console.error('[Server] Initial discovery error:', err.message);
  });

  // Start web server
  app.listen(config.server.port, config.server.host, () => {
    console.log('');
    console.log('╔════════════════════════════════════════════════╗');
    console.log('║          OpenMonitor Web Server               ║');
    console.log('╠════════════════════════════════════════════════╣');
    console.log(`║  Dashboard:  http://${netInfo.address}:${config.server.port}/  ║`);
    console.log(`║  API:        http://${netInfo.address}:${config.server.port}/api ║`);
    console.log('╚════════════════════════════════════════════════╝');
    console.log('');
  });
}

// Handle graceful shutdown
process.on('SIGINT', () => {
  console.log('\n[Server] Shutting down...');
  transcoder.stopAll();
  process.exit(0);
});

process.on('SIGTERM', () => {
  console.log('\n[Server] Shutting down...');
  transcoder.stopAll();
  process.exit(0);
});

// Emergency cleanup on exit (synchronous - for SIGKILL scenarios)
process.on('exit', () => {
  transcoder.emergencyStopAll();
});

// Catch uncaught exceptions to prevent zombie processes
process.on('uncaughtException', (err) => {
  console.error('\n[Server] Uncaught exception:', err.message);
  transcoder.stopAll();
  process.exit(1);
});

start();

module.exports = app;
