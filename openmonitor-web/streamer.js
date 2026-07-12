// Stream Transcoder Module
// Uses ffmpeg to transcode camera streams to MJPEG for iPad 2 Safari compatibility
// Serves frames via multipart/x-mixed-replace HTTP responses

const { spawn } = require('child_process');
const { EventEmitter } = require('events');
const config = require('./config');

class StreamTranscoder extends EventEmitter {
  constructor() {
    super();
    // Active transcoders: { [cameraId]: { process, clients, lastFrame, restartTimer } }
    this.activeStreams = new Map();
  }

  /**
   * Build ffmpeg arguments for transcoding to MJPEG
   */
  buildFFmpegArgs(camera) {
    const input = this.resolveInputURL(camera);
    const fps = config.stream.fps;
    const quality = config.stream.quality;
    const width = config.stream.width;

    const args = [
      // Input
      '-rtsp_transport', 'tcp',           // Use TCP for RTSP (more reliable)
      '-connect_timeout', String(config.stream.connectTimeout),
      '-timeout', String(config.stream.connectTimeout),  // Seconds
      '-i', input,
      // Video processing
      '-an',                                // No audio
      '-sn',                                // No subtitles
      '-vf', 'scale=' + width + ':-1:flags=fast_bilinear',  // Scale keeping aspect ratio
      '-f', 'image2pipe',                   // Output to pipe
      '-c:v', 'mjpeg',                      // MJPEG codec
      '-q:v', String(quality),              // Quality (2-31, lower = better)
      '-r', String(fps),                    // Frame rate
      '-pix_fmt', 'yuvj420p',               // Pixel format for MJPEG
      'pipe:1',                             // Output to stdout
    ];

    return args;
  }

  /**
   * Resolve the input URL, adding credentials if available
   */
  resolveInputURL(camera) {
    let url = camera.streamURL || '';

    if (!url && camera.transport === 'RTSP') {
      url = `rtsp://${camera.host}:${camera.port}/`;
      if (camera.rtspPaths && camera.rtspPaths.length > 0) {
        url = `rtsp://${camera.host}:${camera.port}${camera.rtspPaths[0]}`;
      }
    }

    if (camera.username && camera.password) {
      try {
        const parsed = new URL(url);
        parsed.username = camera.username;
        parsed.password = camera.password;
        url = parsed.href;
      } catch {
        // Keep original URL
      }
    }

    return url;
  }

  /**
   * Check if ffmpeg is available
   */
  static async checkFFmpeg() {
    return new Promise((resolve) => {
      const proc = spawn('ffmpeg', ['-version']);
      let output = '';
      proc.stdout.on('data', (d) => { output += d.toString(); });
      proc.on('close', (code) => {
        resolve(code === 0);
      });
      proc.on('error', () => resolve(false));
    });
  }

  /**
   * Start transcoding a camera stream
   */
  async startStream(camera) {
    const cameraId = camera.id || `${camera.host}:${camera.port}`;

    // If already running, just return
    if (this.activeStreams.has(cameraId)) {
      return cameraId;
    }

    const streamState = {
      camera,
      cameraId,
      process: null,
      clients: new Set(),
      lastFrame: null,
      frameBuffer: Buffer.alloc(0),
      readingFrame: false,
      restartTimer: null,
      stats: {
        framesGenerated: 0,
        startedAt: Date.now(),
        errors: 0,
      },
    };

    this.activeStreams.set(cameraId, streamState);
    this.launchFFmpeg(cameraId);

    return cameraId;
  }

  /**
   * Launch ffmpeg process for a camera
   */
  launchFFmpeg(cameraId) {
    const state = this.activeStreams.get(cameraId);
    if (!state) return;

    const camera = state.camera;
    const input = this.resolveInputURL(camera);
    const args = this.buildFFmpegArgs(camera);

    console.log(`[Streamer] Launching ffmpeg for ${cameraId}: ${input}`);

    try {
      const proc = spawn('ffmpeg', args, {
        stdio: ['ignore', 'pipe', 'pipe'],
      });

      state.process = proc;

      // Buffer for accumulating JPEG frame data
      // MJPEG from ffmpeg pipe outputs continuous JPEG frames
      // We detect frame boundaries by looking for JPEG SOI (0xFF 0xD8) markers

      let buffer = Buffer.alloc(0);
      let frameStart = -1;

      proc.stdout.on('data', (chunk) => {
        // Append new data to buffer
        buffer = Buffer.concat([buffer, chunk]);

        // Process complete frames from buffer
        while (true) {
          if (frameStart < 0) {
            // Look for JPEG start marker (FF D8)
            frameStart = buffer.indexOf(Buffer.from([0xFF, 0xD8]));
            if (frameStart < 0) {
              // No start marker found, keep buffering
              if (buffer.length > 1024 * 1024) {
                // Safety: clear buffer if it grows too large without finding start
                buffer = buffer.slice(-1024 * 512);
              }
              break;
            }
          }

          // Look for JPEG end marker (FF D9)
          const frameEnd = buffer.indexOf(Buffer.from([0xFF, 0xD9]), frameStart + 2);
          if (frameEnd < 0) {
            // Incomplete frame, keep buffering
            break;
          }

          // We have a complete frame
          const frame = buffer.slice(frameStart, frameEnd + 2);
          state.lastFrame = frame;
          state.stats.framesGenerated++;

          // Broadcast to all connected clients
          for (const client of state.clients) {
            if (client.writable) {
              client.write(this.buildMJPEGPart(frame));
            } else {
              state.clients.delete(client);
            }
          }

          // Remove processed data from buffer
          buffer = buffer.slice(frameEnd + 2);
          frameStart = -1;

          // Limit buffer size
          if (buffer.length > 1024 * 1024) {
            buffer = Buffer.alloc(0);
            frameStart = -1;
          }
        }
      });

      proc.stderr.on('data', (data) => {
        // ffmpeg logs to stderr - only log non-routine messages
        const msg = data.toString();
        if (msg.includes('error') || msg.includes('Error') || msg.includes('Invalid')) {
          console.warn(`[Streamer:${cameraId}] ${msg.trim()}`);
        }
      });

      proc.on('close', (code) => {
        console.log(`[Streamer:${cameraId}] ffmpeg exited with code ${code}`);
        state.stats.errors++;

        // Schedule restart
        if (state.restartTimer) {
          clearTimeout(state.restartTimer);
        }
        state.restartTimer = setTimeout(() => {
          // Only restart if this stream is still active (hasn't been stopped)
          if (this.activeStreams.has(cameraId)) {
            console.log(`[Streamer:${cameraId}] Restarting stream...`);
            // Send a blank frame to notify clients
            for (const client of state.clients) {
              if (client.writable) {
                client.write(this.buildMJPEGPart(null, 'restarting'));
              }
            }
            this.launchFFmpeg(cameraId);
          }
        }, config.stream.restartDelay * 1000);
      });

      proc.on('error', (err) => {
        console.error(`[Streamer:${cameraId}] Failed to launch ffmpeg:`, err.message);
      });

    } catch (err) {
      console.error(`[Streamer:${cameraId}] Error launching ffmpeg:`, err.message);
    }
  }

  /**
   * Build a single MJPEG part for multipart/x-mixed-replace
   */
  buildMJPEGPart(frame, status) {
    if (frame) {
      const header = [
        '--FRAMEBOUNDARY',
        'Content-Type: image/jpeg',
        `Content-Length: ${frame.length}`,
        '',
        '',
      ].join('\r\n');
      return Buffer.concat([Buffer.from(header), frame, Buffer.from('\r\n')]);
    } else {
      // Status update frame (blank JPEG or text)
      let msg = status || 'disconnected';
      return Buffer.from(`--FRAMEBOUNDARY\r\nContent-Type: text/plain\r\n\r\n${msg}\r\n`);
    }
  }

  /**
   * Handle an HTTP client connection for MJPEG streaming
   */
  handleClient(req, res, cameraId) {
    const state = this.activeStreams.get(cameraId);

    if (!state) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Stream not found');
      return;
    }

    // Set headers for MJPEG stream
    res.writeHead(200, {
      'Cache-Control': 'no-cache, no-store, must-revalidate',
      'Pragma': 'no-cache',
      'Expires': '0',
      'Connection': 'close',
      'Content-Type': 'multipart/x-mixed-replace; boundary=FRAMEBOUNDARY',
      'Access-Control-Allow-Origin': '*',
    });

    // Send initial boundary
    res.write(`--FRAMEBOUNDARY\r\n`);

    // If we have a last frame, send it immediately
    if (state.lastFrame) {
      res.write(this.buildMJPEGPart(state.lastFrame));
    } else {
      res.write(`Content-Type: text/plain\r\n\r\nConnecting to stream...\r\n`);
      res.write(`--FRAMEBOUNDARY\r\n`);
    }

    // Add to active clients
    state.clients.add(res);

    // Handle client disconnect
    req.on('close', () => {
      state.clients.delete(res);
      this.cleanupIfIdle(cameraId);
    });

    // Keep-alive: send periodic boundary to maintain connection
    const keepAlive = setInterval(() => {
      if (res.writable) {
        res.write(`--FRAMEBOUNDARY\r\n`);
      } else {
        clearInterval(keepAlive);
      }
    }, 5000);

    req.on('close', () => {
      clearInterval(keepAlive);
    });
  }

  /**
   * Stop transcoding a specific camera stream
   */
  stopStream(cameraId) {
    const state = this.activeStreams.get(cameraId);
    if (!state) return;

    if (state.restartTimer) {
      clearTimeout(state.restartTimer);
    }

    if (state.process) {
      state.process.kill('SIGTERM');
      setTimeout(() => {
        try { state.process.kill('SIGKILL'); } catch {}
      }, 2000);
    }

    // Close client connections
    for (const client of state.clients) {
      try { client.end(); } catch {}
    }

    this.activeStreams.delete(cameraId);
    console.log(`[Streamer] Stopped stream ${cameraId}`);
  }

  /**
   * Clean up idle streams (no clients)
   */
  cleanupIfIdle(cameraId) {
    const state = this.activeStreams.get(cameraId);
    if (state && state.clients.size === 0) {
      // Keep the stream running for a while in case someone reconnects
      if (state.idleTimeout) clearTimeout(state.idleTimeout);
      state.idleTimeout = setTimeout(() => {
        const current = this.activeStreams.get(cameraId);
        if (current && current.clients.size === 0) {
          this.stopStream(cameraId);
        }
      }, 30000); // 30 second idle timeout
    }
  }

  /**
   * Stop all streams (async - allows timeout-based SIGKILL fallback)
   */
  stopAll() {
    const ids = Array.from(this.activeStreams.keys());
    ids.forEach((id) => this.stopStream(id));
  }

  /**
   * Emergency stop all streams (synchronous, no setTimeout - for process.exit handlers)
   * Sends SIGKILL immediately to all ffmpeg processes
   */
  emergencyStopAll() {
    const ids = Array.from(this.activeStreams.keys());
    for (const id of ids) {
      const state = this.activeStreams.get(id);
      if (!state) continue;

      if (state.restartTimer) {
        clearTimeout(state.restartTimer);
      }

      if (state.process) {
        try { state.process.kill('SIGKILL'); } catch {}
      }

      for (const client of state.clients) {
        try { client.end(); } catch {}
      }

      this.activeStreams.delete(id);
    }
  }

  /**
   * Get status of all active streams
   */
  getStatus() {
    const status = {};
    for (const [id, state] of this.activeStreams) {
      status[id] = {
        camera: state.camera,
        clients: state.clients.size,
        framesGenerated: state.stats.framesGenerated,
        runningFor: Math.floor((Date.now() - state.stats.startedAt) / 1000),
        errors: state.stats.errors,
        isRunning: state.process !== null && !state.process.killed,
      };
    }
    return status;
  }
}

module.exports = StreamTranscoder;
