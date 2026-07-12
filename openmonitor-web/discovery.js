// Camera Discovery Module
// Ports OpenMonitor's iOS discovery logic to Node.js
// Scans subnet, probes HTTP services for Baseus fingerprints,
// detects RTSP streams

const net = require('net');
const http = require('http');
const { networkInterfaces } = require('os');
const config = require('./config');

class CameraDiscovery {
  constructor() {
    this.endpoints = [];
    this.isScanning = false;
  }

  /**
   * Get the local IPv4 address and subnet prefix
   */
  getLocalNetworkInfo() {
    const interfaces = networkInterfaces();
    for (const name of Object.keys(interfaces)) {
      for (const iface of interfaces[name]) {
        if (iface.family === 'IPv4' && !iface.internal && name !== 'lo0') {
          const parts = iface.address.split('.');
          const subnetPrefix = parts.slice(0, 3).join('.');
          return {
            address: iface.address,
            subnetPrefix,
            netmask: iface.netmask,
          };
        }
      }
    }
    // Fallback
    return {
      address: '127.0.0.1',
      subnetPrefix: '192.168.1',
      netmask: '255.255.255.0',
    };
  }

  /**
   * Scan a single host:port pair to check if it's open
   */
  probePort(host, port, timeout) {
    return new Promise((resolve) => {
      const socket = new net.Socket();
      socket.setTimeout(timeout);

      socket.on('connect', () => {
        socket.destroy();
        resolve(true);
      });

      socket.on('error', () => {
        socket.destroy();
        resolve(false);
      });

      socket.on('timeout', () => {
        socket.destroy();
        resolve(false);
      });

      socket.connect(port, host);
    });
  }

  /**
   * Probe an HTTP service for Baseus fingerprints and stream URLs
   */
  async probeHTTPService(host, port) {
    return new Promise((resolve) => {
      const options = {
        hostname: host,
        port: port,
        path: '/',
        method: 'GET',
        timeout: config.discovery.probeTimeout,
        headers: {
          'User-Agent': config.baseus.userAgent,
        },
      };

      const req = http.request(options, (res) => {
        let body = '';
        res.on('data', (chunk) => {
          body += chunk.toString();
          // Limit body size for analysis
          if (body.length > 128000) {
            req.destroy();
          }
        });

        res.on('end', () => {
          resolve(this.analyzeHTTPResponse(host, port, body, res.headers));
        });

        res.on('error', () => {
          resolve(null);
        });
      });

      req.on('error', () => {
        resolve(null);
      });

      req.on('timeout', () => {
        req.destroy();
        resolve(null);
      });

      req.end();
    });
  }

  /**
   * Analyze HTTP response for Baseus fingerprints and stream URLs
   */
  analyzeHTTPResponse(host, port, body, headers) {
    const bodyLower = body.toLowerCase();
    const serverHeader = headers['server'] || '';

    // Check for Baseus fingerprints
    const isBaseus = config.baseus.fingerprints.some(
      (fp) => bodyLower.includes(fp) || serverHeader.toLowerCase().includes(fp)
    );

    // Extract streaming URLs from response body
    const streamURLs = this.extractStreamURLs(body);

    // Extract title for display name
    const titleMatch = body.match(/<title[^>]*>([^<]*)<\/title>/i);
    const title = titleMatch ? titleMatch[1].trim() : null;

    const result = {
      host,
      port,
      isBaseus,
      title: title || serverHeader || host,
      confidence: isBaseus ? 0.75 : 0.3,
      streamURLs,
      details: isBaseus ? 'Baseus/HomeStation device detected' : 'HTTP service responded',
    };

    return result;
  }

  /**
   * Extract RTSP, HLS, and MP4 URLs from HTML body
   */
  extractStreamURLs(body) {
    const patterns = [
      /rtsp:\/\/[^\s"'<>]+/gi,
      /https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/gi,
      /https?:\/\/[^\s"'<>]+\.mp4[^\s"'<>]*/gi,
      /https?:\/\/[^\s"'<>]+stream[^\s"'<>]*/gi,
      /https?:\/\/[^\s"'<>]+video[^\s"'<>]*/gi,
      /https?:\/\/[^\s"'<>]+live[^\s"'<>]*/gi,
      /https?:\/\/[^\s"'<>]+camera[^\s"'<>]*/gi,
      /https?:\/\/[^\s"'<>]+mjpeg[^\s"'<>]*/gi,
      /https?:\/\/[^\s"'<>]+snapshot[^\s"'<>]*/gi,
    ];

    const urls = [];
    for (const pattern of patterns) {
      const matches = body.match(pattern);
      if (matches) {
        for (const match of matches) {
          // Clean up trailing punctuation
          const cleaned = match.replace(/["'<>),.;]+$/, '');
          try {
            const url = new URL(cleaned);
            if (!urls.some((u) => u.href === url.href)) {
              urls.push(url);
            }
          } catch {
            // Ignore invalid URLs
          }
        }
      }
    }

    return urls;
  }

  /**
   * Probe an RTSP port for stream availability
   */
  async probeRTSP(host, port) {
    return new Promise((resolve) => {
      const socket = new net.Socket();
      socket.setTimeout(2000);

      socket.on('connect', () => {
        // Send an RTSP DESCRIBE request
        const describe = [
          `DESCRIBE rtsp://${host}:${port}/ RTSP/1.0`,
          `CSeq: 1`,
          `User-Agent: ${config.baseus.userAgent}`,
          '',
          '',
        ].join('\r\n');

        socket.write(describe);
      });

      let response = '';
      socket.on('data', (data) => {
        response += data.toString();
      });

      socket.on('close', () => {
        const isRTSP = response.includes('RTSP/') || response.includes('200 OK');
        resolve({
          host,
          port,
          isRTSP,
          response: response.substring(0, 500),
        });
      });

      socket.on('error', () => {
        socket.destroy();
        resolve({ host, port, isRTSP: false });
      });

      socket.on('timeout', () => {
        socket.destroy();
        resolve({ host, port, isRTSP: false });
      });
    });
  }

  /**
   * Scan the subnet for open ports and identify cameras
   */
  async scanSubnet() {
    const netInfo = this.getLocalNetworkInfo();
    const subnetPrefix =
      config.discovery.subnetPrefix || netInfo.subnetPrefix;
    const ports = config.discovery.ports;
    const timeout = config.discovery.probeTimeout;
    const parallelism = config.discovery.parallelism;

    console.log(`[Discovery] Scanning ${subnetPrefix}.1-${config.discovery.ipRangeEnd}...`);
    console.log(`[Discovery] Ports: ${ports.join(', ')}`);

    // Build list of all host:port combinations to probe
    const probes = [];
    for (let octet = config.discovery.ipRangeStart; octet <= config.discovery.ipRangeEnd; octet++) {
      const host = `${subnetPrefix}.${octet}`;
      for (const port of ports) {
        probes.push({ host, port });
      }
    }

    // Scan in parallel batches
    const openPorts = [];
    for (let i = 0; i < probes.length; i += parallelism) {
      const batch = probes.slice(i, i + parallelism);
      const results = await Promise.all(
        batch.map((p) => this.probePort(p.host, p.port, timeout))
      );
      for (let j = 0; j < results.length; j++) {
        if (results[j]) {
          openPorts.push(batch[j]);
        }
      }
    }

    console.log('[Discovery] Found ' + openPorts.length + ' open ports');

    // Separate HTTP and RTSP ports
    var httpPorts = [];
    var rtspPorts = [];
    for (var pi = 0; pi < openPorts.length; pi++) {
      var p = openPorts[pi];
      if ([80, 443, 8000, 8080, 8899, 5000, 7000].indexOf(p.port) >= 0) {
        httpPorts.push(p);
      } else if ([554, 8554].indexOf(p.port) >= 0) {
        rtspPorts.push(p);
      }
    }

    console.log('[Discovery] Probing ' + httpPorts.length + ' HTTP services...');

    // Probe HTTP ports in parallel batches
    var self = this;
    var cameraCandidates = [];
    var httpBatchSize = 20;
    for (var bi = 0; bi < httpPorts.length; bi += httpBatchSize) {
      var batch = httpPorts.slice(bi, bi + httpBatchSize);
      var httpResults = await Promise.all(
        batch.map((p) => self.probeHTTPService(p.host, p.port))
      );
      for (var hi = 0; hi < httpResults.length; hi++) {
        var httpResult = httpResults[hi];
        if (httpResult) {
          var httpCandidate = {
            id: httpResult.host + ':' + httpResult.port,
            name: httpResult.title || (httpResult.host + ':' + httpResult.port),
            host: httpResult.host,
            port: httpResult.port,
            transport: 'HTTP',
            streamURLs: httpResult.streamURLs,
            isBaseus: httpResult.isBaseus,
            confidence: httpResult.confidence,
            details: httpResult.details,
            discoveredAt: new Date().toISOString(),
          };
          cameraCandidates.push(httpCandidate);
          if (httpResult.isBaseus) {
            console.log('[Discovery] Found Baseus device at ' + httpResult.host + ':' + httpResult.port + ' - ' + httpResult.title);
          }
        }
      }
    }

    // Probe RTSP ports sequentially (they're typically few)
    console.log('[Discovery] Probing ' + rtspPorts.length + ' RTSP services...');
    for (var rti = 0; rti < rtspPorts.length; rti++) {
      var rp = rtspPorts[rti];
      var rtspResult = await this.probeRTSP(rp.host, rp.port);
      if (rtspResult.isRTSP) {
        var streamURL = 'rtsp://' + rp.host + ':' + rp.port + '/';
        cameraCandidates.push({
          id: rp.host + ':' + rp.port,
          name: 'RTSP Camera @ ' + rp.host,
          host: rp.host,
          port: rp.port,
          transport: 'RTSP',
          streamURLs: [{ href: streamURL, protocol: 'rtsp:', hostname: rp.host, port: rp.port, pathname: '/' }],
          isBaseus: false,
          confidence: 0.65,
          details: 'Open RTSP port discovered during scan',
          discoveredAt: new Date().toISOString(),
        });
        console.log('[Discovery] Found RTSP stream at ' + rp.host + ':' + rp.port);
      }
    }

    this.endpoints = cameraCandidates.sort((a, b) => {
      if (a.isBaseus !== b.isBaseus) return a.isBaseus ? -1 : 1;
      return b.confidence - a.confidence;
    });

    console.log(`[Discovery] Found ${this.endpoints.length} camera candidates (${this.endpoints.filter(e => e.isBaseus).length} Baseus)`);
    return this.endpoints;
  }

  /**
   * Full discovery: scan subnet + identify cameras
   */
  async discover() {
    if (this.isScanning) {
      console.log('[Discovery] Scan already in progress');
      return this.endpoints;
    }

    this.isScanning = true;
    try {
      await this.scanSubnet();
    } catch (err) {
      console.error('[Discovery] Error:', err.message);
    } finally {
      this.isScanning = false;
    }

    return this.endpoints;
  }

  /**
   * Get the current list of discovered endpoints
   */
  getEndpoints() {
    return this.endpoints;
  }
}

// CLI support: run this module directly to perform a scan
if (require.main === module && process.argv.includes('--scan')) {
  const discovery = new CameraDiscovery();
  discovery.discover().then((endpoints) => {
    console.log('\n=== Discovered Cameras ===');
    if (endpoints.length === 0) {
      console.log('No cameras found.');
    }
    endpoints.forEach((ep, i) => {
      console.log(`\n[${i + 1}] ${ep.name}`);
      console.log(`    Address: ${ep.host}:${ep.port}`);
      console.log(`    Transport: ${ep.transport}`);
      console.log(`    Confidence: ${(ep.confidence * 100).toFixed(0)}%`);
      console.log(`    Baseus: ${ep.isBaseus ? 'Yes' : 'No'}`);
      console.log(`    Details: ${ep.details}`);
      if (ep.streamURLs.length > 0) {
        console.log(`    Stream URLs:`);
        ep.streamURLs.forEach((url) => console.log(`      - ${url.href}`));
      }
    });
    console.log(`\nTotal: ${endpoints.length} candidates`);
  });
}

module.exports = CameraDiscovery;
