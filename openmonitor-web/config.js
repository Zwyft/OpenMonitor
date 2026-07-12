// Configuration for OpenMonitor Web
const config = {
  // Web server settings
  server: {
    port: process.env.PORT || 8080,
    host: '0.0.0.0',
  },

  // Network discovery settings
  discovery: {
    // Ports commonly used by cameras (Baseus + general)
    ports: [80, 443, 554, 8554, 8000, 8080, 8899, 5000, 7000],
    // Socket connection timeout (ms) per port probe
    probeTimeout: 750,
    // Max parallel connections during subnet scan
    parallelism: 50,
    // Local subnet prefix (auto-detected from network interface)
    // Override if needed: '192.168.1'
    subnetPrefix: null,
    // IP range to scan (.1 to .254)
    ipRangeStart: 1,
    ipRangeEnd: 254,
  },

  // Baseus camera fingerprinting
  baseus: {
    // Keywords in HTTP response body that identify Baseus cameras/hubs
    fingerprints: [
      'baseus',
      'homestation',
      'x1 pro',
      'x1pro',
      'baseus security',
      'baseus_camera',
      'baseus_cloud',
      'bcamera',
      '/baseus',
      'baseus_hub',
    ],
    // User-Agent header for HTTP probes
    userAgent: 'OpenMonitor-Web/1.0',
  },

  // Stream transcoding settings (ffmpeg to MJPEG for iPad 2)
  stream: {
    // MJPEG frame rate (fps)
    fps: 10,
    // MJPEG quality (2-31, lower = better quality)
    quality: 5,
    // Output width (iPad 2 is 1024x768, half-width for split view)
    width: 512,
    // ffmpeg timeout for initial connection (seconds)
    connectTimeout: 10,
    // Auto-restart stream on failure (seconds to wait before retry)
    restartDelay: 5,
  },

  // Dashboard settings
  dashboard: {
    // Refresh interval for discovery status (ms)
    refreshInterval: 30000,
  },
};

module.exports = config;
