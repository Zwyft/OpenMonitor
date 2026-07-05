package com.openmonitor.bridge

fun baseusCloudLivePage(serverUrl: String): String {
    return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Baseus Live Viewer</title>
          <style>
            :root {
              color-scheme: dark;
            }
            body {
              margin: 0;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
              background: #071014;
              color: #e8f2f3;
            }
            .shell {
              max-width: 1080px;
              margin: 0 auto;
              padding: 20px;
            }
            .card {
              background: rgba(11, 19, 24, 0.92);
              border: 1px solid rgba(171, 189, 196, 0.16);
              border-radius: 20px;
              padding: 18px;
              box-shadow: 0 24px 80px rgba(0, 0, 0, 0.42);
            }
            h1, h2, h3 {
              margin: 0;
              font-weight: 650;
            }
            h1 { font-size: 30px; }
            .muted {
              color: #97abb1;
            }
            .row {
              display: flex;
              flex-wrap: wrap;
              gap: 12px;
            }
            .field {
              display: grid;
              gap: 8px;
              margin: 14px 0;
            }
            label {
              color: #97abb1;
              text-transform: uppercase;
              letter-spacing: 0.08em;
              font-size: 11px;
              font-family: "SFMono-Regular", "Menlo", monospace;
            }
            select, button {
              border-radius: 14px;
              border: 1px solid rgba(255, 255, 255, 0.12);
              background: rgba(255, 255, 255, 0.04);
              color: #e8f2f3;
              font: inherit;
              padding: 12px 14px;
            }
            button {
              cursor: pointer;
              background: linear-gradient(180deg, rgba(225, 184, 79, 0.28), rgba(225, 184, 79, 0.12));
              color: #ffe3a1;
            }
            button.secondary {
              background: rgba(255, 255, 255, 0.05);
              color: #e8f2f3;
            }
            button:disabled {
              opacity: 0.5;
              cursor: not-allowed;
            }
            .pill {
              display: inline-flex;
              align-items: center;
              justify-content: center;
              padding: 8px 12px;
              border-radius: 999px;
              background: rgba(110, 212, 199, 0.08);
              border: 1px solid rgba(110, 212, 199, 0.3);
              color: #6ed4c7;
              font-size: 12px;
              font-family: "SFMono-Regular", "Menlo", monospace;
            }
            .video-wrap {
              margin-top: 16px;
              border-radius: 20px;
              overflow: hidden;
              border: 1px solid rgba(255, 255, 255, 0.08);
              background: #000;
              min-height: 280px;
            }
            video {
              width: 100%;
              display: block;
              background: #000;
            }
            pre {
              white-space: pre-wrap;
              word-break: break-word;
              background: rgba(11, 18, 32, 0.88);
              border-radius: 16px;
              padding: 14px;
              border: 1px solid rgba(255, 255, 255, 0.08);
              max-height: 260px;
              overflow: auto;
            }
            a { color: #7dd3fc; }
          </style>
        </head>
        <body>
          <div class="shell">
            <div class="card">
              <div class="row" style="justify-content: space-between; align-items: center;">
                <div>
                  <div class="muted">OpenMonitor / Baseus cloud live</div>
                  <h1>Live camera viewer</h1>
                </div>
                <div class="pill" id="sessionBadge">Checking session…</div>
              </div>
              <p class="muted">This page uses the Baseus/VicoHome cloud WebRTC path. Sync the cloud account in the Android app first, then select a device below.</p>
              <p class="muted">Phone server: <code>${escapeHtml(serverUrl)}</code> · <a href="${escapeHtml(serverUrl)}">Back to dashboard</a></p>

              <div class="field">
                <label for="deviceSelect">Camera</label>
                <select id="deviceSelect"></select>
              </div>

              <div class="field">
                <label for="manualTarget">Manual serial or IP</label>
                <input id="manualTarget" placeholder="Enter device serial or 192.168.4.25">
              </div>

              <div class="row">
                <button id="refreshButton" class="secondary">Refresh devices</button>
                <button id="useManualButton" class="secondary">Use manual target</button>
                <button id="startButton">Start live</button>
                <button id="stopButton" class="secondary" disabled>Stop</button>
              </div>

              <div class="video-wrap">
                <video id="liveVideo" autoplay muted playsinline controls></video>
              </div>

              <div class="field">
                <label>Status</label>
                <pre id="statusLog">Idle.</pre>
              </div>
            </div>
          </div>

          <script>
          (function () {
            var deviceSelect = document.getElementById("deviceSelect");
            var refreshButton = document.getElementById("refreshButton");
            var useManualButton = document.getElementById("useManualButton");
            var startButton = document.getElementById("startButton");
            var stopButton = document.getElementById("stopButton");
            var statusLog = document.getElementById("statusLog");
            var sessionBadge = document.getElementById("sessionBadge");
            var liveVideo = document.getElementById("liveVideo");
            var manualTargetInput = document.getElementById("manualTarget");
            var signalSocket = null;
            var peerConnection = null;
            var dataChannel = null;
            var signalQueue = [];
            var currentTicket = null;
            var currentSerial = "";
            var sessionId = "";
            var keepaliveTimer = null;

            function log(message) {
              statusLog.textContent = "[" + new Date().toLocaleTimeString() + "] " + message + "\n" + statusLog.textContent;
            }

            function requestJSON(method, url, body, callback) {
              var xhr = new XMLHttpRequest();
              xhr.open(method, url, true);
              xhr.onreadystatechange = function () {
                if (xhr.readyState !== 4) return;
                if (xhr.status >= 200 && xhr.status < 300) {
                  try {
                    callback(null, JSON.parse(xhr.responseText || "{}"));
                  } catch (error) {
                    callback(error);
                  }
                } else {
                  var message = xhr.responseText || xhr.statusText || ("HTTP " + xhr.status);
                  try {
                    var parsedError = JSON.parse(xhr.responseText || "{}");
                    message = parsedError.message || message;
                  } catch (error) {
                  }
                  callback(new Error(message));
                }
              };
              if (body) xhr.setRequestHeader("Content-Type", "application/json");
              xhr.send(body ? JSON.stringify(body) : null);
            }

            function setControlsRunning(running) {
              startButton.disabled = running;
              stopButton.disabled = !running;
              refreshButton.disabled = running;
              deviceSelect.disabled = running;
            }

            function normalizeSignalUrl(signalServer, websocketPath) {
              var url;
              try {
                url = new URL(signalServer);
              } catch (error) {
                return signalServer;
              }
              if (url.protocol === "https:") {
                url.protocol = "wss:";
              } else if (url.protocol === "http:") {
                url.protocol = "ws:";
              }
              if (websocketPath && websocketPath.length) {
                url.pathname = websocketPath.charAt(0) === "/" ? websocketPath : "/" + websocketPath;
              }
              return url.toString();
            }

            function mapIceServers(iceServerList) {
              var mapped = [];
              for (var i = 0; i < iceServerList.length; i += 1) {
                var entry = iceServerList[i];
                if (!entry || !entry.url) continue;
                mapped.push({
                  urls: entry.url,
                  username: entry.username || "",
                  credential: entry.credential || ""
                });
              }
              return mapped;
            }

            function base64Encode(text) {
              return btoa(text);
            }

            function sessionLabel(response) {
              if (!response || !response.available) {
                return "Cloud session missing";
              }
              return "Session ready • " + (response.region || "unknown region");
            }

            function loadSession() {
              requestJSON("GET", "/api/vicohome/session", null, function (error, response) {
                if (error) {
                  sessionBadge.textContent = "Session error";
                  log("Session check failed: " + error.message);
                  return;
                }
                sessionBadge.textContent = sessionLabel(response);
              });
            }

            function loadDevices(selectDefaultSerial, selectDefaultIp) {
              requestJSON("GET", "/api/vicohome/devices", null, function (error, response) {
                if (error) {
                  log("Device load failed: " + error.message);
                  return;
                }
                var devices = response.entries || [];
                deviceSelect.innerHTML = "";
                if (!devices.length) {
                  var emptyOption = document.createElement("option");
                  emptyOption.value = "";
                  emptyOption.textContent = "No cloud devices loaded yet";
                  deviceSelect.appendChild(emptyOption);
                  return;
                }
                for (var i = 0; i < devices.length; i += 1) {
                  var device = devices[i];
                  var option = document.createElement("option");
                  option.value = device.serialNumber || "";
                  option.textContent = (device.deviceName || device.serialNumber || "Camera") + " • " + (device.modelNo || "unknown model");
                  if (selectDefaultIp && device.ip && device.ip === selectDefaultIp) {
                    option.selected = true;
                  }
                  deviceSelect.appendChild(option);
                }
                if (!deviceSelect.value && selectDefaultSerial) {
                  deviceSelect.value = selectDefaultSerial;
                }
                if (selectDefaultSerial && selectDefaultIp && !deviceSelect.value) {
                  for (var j = 0; j < devices.length; j += 1) {
                    if ((devices[j].ip || "") === selectDefaultIp) {
                      deviceSelect.value = devices[j].serialNumber || "";
                      break;
                    }
                  }
                }
                if (!deviceSelect.value && (selectDefaultSerial || selectDefaultIp)) {
                  var fallbackValue = selectDefaultSerial || selectDefaultIp;
                  var fallbackOption = document.createElement("option");
                  fallbackOption.value = fallbackValue;
                  fallbackOption.textContent = "Manual target • " + fallbackValue;
                  fallbackOption.selected = true;
                  deviceSelect.insertBefore(fallbackOption, deviceSelect.firstChild);
                  log("No cloud device matched; using manual target " + fallbackValue);
                }
                manualTargetInput.value = selectDefaultSerial || selectDefaultIp || "";
              });
            }

            function stopLive() {
              if (keepaliveTimer) {
                window.clearInterval(keepaliveTimer);
                keepaliveTimer = null;
              }
              if (signalSocket) {
                try { signalSocket.close(); } catch (error) {}
              }
              if (dataChannel) {
                try { dataChannel.close(); } catch (error) {}
              }
              if (peerConnection) {
                try { peerConnection.close(); } catch (error) {}
              }
              signalSocket = null;
              peerConnection = null;
              dataChannel = null;
              signalQueue = [];
              currentTicket = null;
              currentSerial = "";
              sessionId = "";
              liveVideo.srcObject = null;
              setControlsRunning(false);
              log("Live session stopped");
            }

            function flushSignalQueue() {
              if (!signalSocket || signalSocket.readyState !== WebSocket.OPEN) return;
              while (signalQueue.length) {
                signalSocket.send(signalQueue.shift());
              }
            }

            function startKeepalive() {
              if (keepaliveTimer) {
                window.clearInterval(keepaliveTimer);
                keepaliveTimer = null;
              }
              var intervalSeconds = currentTicket && currentTicket.signalPingInterval ? currentTicket.signalPingInterval : 5;
              keepaliveTimer = window.setInterval(function () {
                sendSignal({
                  method: "PING",
                  timestamp: Date.now()
                });
              }, Math.max(2, intervalSeconds) * 1000);
            }

            function sendSignal(message) {
              var payload = JSON.stringify(message);
              if (!signalSocket || signalSocket.readyState !== WebSocket.OPEN) {
                signalQueue.push(payload);
                return;
              }
              signalSocket.send(payload);
            }

            function sendAuth() {
              sendSignal({
                method: "AUTH",
                clientType: "app",
                status: "normal",
                accessToken: currentTicket.accessToken || currentTicket.id,
                id: currentTicket.id
              });
            }

            function sendJoinLive() {
              sendSignal({
                method: "JOIN_LIVE",
                role: "viewer",
                name: currentTicket.id,
                group: currentTicket.groupId,
                traceId: currentTicket.traceId,
                recipientClientId: currentSerial
              });
            }

            function sendOffer(offer) {
              var payload = {
                type: "offer",
                sdp: offer.sdp
              };
              sendSignal({
                method: "TRANSMIT",
                messageType: "SDP_OFFER",
                messagePayload: base64Encode(JSON.stringify(payload)),
                mode: "vicoo",
                recipientClientId: currentSerial,
                senderClientId: currentTicket.id,
                sessionId: sessionId,
                viewerType: "a4x_sdk",
                resolution: "1280x720",
                version: "0.0.1"
              });
            }

            function sendIceCandidate(candidate) {
              if (!candidate) return;
              sendSignal({
                method: "TRANSMIT",
                messageType: "ICE_CANDIDATE",
                messagePayload: base64Encode(JSON.stringify({
                  sdpMid: candidate.sdpMid || "",
                  sdpMLineIndex: typeof candidate.sdpMLineIndex === "number" ? candidate.sdpMLineIndex : 0,
                  candidate: candidate.candidate || ""
                })),
                recipientClientId: currentSerial,
                senderClientId: currentTicket.id,
                sessionId: sessionId,
                version: "0.0.1"
              });
            }

            function setRemoteAnswer(answer) {
              return peerConnection.setRemoteDescription({
                type: "answer",
                sdp: answer.sdp
              });
            }

            function handleSignalMessage(raw) {
              var message = raw || {};
              if (message.method === "AUTH_RESPONSE") {
                if (message.code == null || message.code === 0 || message.code === "0") {
                  log("Authenticated with cloud signal server");
                  startKeepalive();
                  sendJoinLive();
                } else {
                  log("Auth failed: " + (message.message || ("code " + message.code)));
                  stopLive();
                }
                return;
              }

              if (message.method === "JOIN_LIVE_RESPONSE") {
                log("Join live response: " + (message.message || "ok"));
                return;
              }

              if (message.method === "PEER_IN") {
                log("Camera peer connected; creating offer");
                peerConnection.createOffer().then(function (offer) {
                  return peerConnection.setLocalDescription(offer).then(function () {
                    sendOffer(offer);
                  });
                }).catch(function (error) {
                  log("Offer error: " + error.message);
                });
                return;
              }

              if (message.method === "PEER_OUT") {
                log("Camera peer left the session");
                stopLive();
                return;
              }

              if (message.method === "TRANSMIT" && message.messageType === "SDP_ANSWER") {
                try {
                  var decoded = JSON.parse(atob(message.messagePayload || ""));
                  setRemoteAnswer(decoded).then(function () {
                    log("Remote SDP answer applied");
                  }).catch(function (error) {
                    log("Failed to apply SDP answer: " + error.message);
                  });
                } catch (error) {
                  log("Failed to decode SDP answer: " + error.message);
                }
                return;
              }

              if (message.method === "TRANSMIT" && message.messageType === "ICE_CANDIDATE") {
                try {
                  var candidate = JSON.parse(atob(message.messagePayload || ""));
                  peerConnection.addIceCandidate(new RTCIceCandidate({
                    sdpMid: candidate.sdpMid || "",
                    sdpMLineIndex: candidate.sdpMLineIndex || 0,
                    candidate: candidate.candidate || ""
                  })).then(function () {
                    log("Added remote ICE candidate");
                  }).catch(function (error) {
                    log("Remote ICE error: " + error.message);
                  });
                } catch (error) {
                  log("Failed to decode ICE candidate: " + error.message);
                }
              }
            }

            function startLive() {
              var serial = deviceSelect.value || manualTargetInput.value.trim() || "";
              if (!serial) {
                log("Select a camera first");
                return;
              }
              stopLive();
              setControlsRunning(true);
              currentSerial = serial;
              currentTicket = null;
              sessionId = "Browser-" + Date.now();
              log("Requesting cloud live ticket for " + serial);
              requestJSON("GET", "/api/vicohome/live-ticket?serial=" + encodeURIComponent(serial), null, function (error, response) {
                if (error) {
                  log("Live ticket error: " + error.message);
                  setControlsRunning(false);
                  return;
                }

                currentTicket = response.ticket || response;
                if (!currentTicket || !currentTicket.signalServer) {
                  log((response && response.message) ? response.message : "Live ticket missing signal server");
                  setControlsRunning(false);
                  return;
                }

                log("Ticket loaded for " + serial + " via " + (response.region || "unknown region"));
                peerConnection = new RTCPeerConnection({
                  iceServers: mapIceServers(currentTicket.iceServer || [])
                });

                peerConnection.onicecandidate = function (event) {
                  if (event.candidate) sendIceCandidate(event.candidate);
                };
                peerConnection.oniceconnectionstatechange = function () {
                  log("ICE state: " + peerConnection.iceConnectionState);
                };
                peerConnection.onconnectionstatechange = function () {
                  log("Connection state: " + peerConnection.connectionState);
                };
                peerConnection.ontrack = function (event) {
                  if (!event.track || event.track.kind !== "video") {
                    log("Remote " + (event.track ? event.track.kind : "unknown") + " track received");
                    return;
                  }
                  var stream = event.streams && event.streams.length ? event.streams[0] : new MediaStream([event.track]);
                  liveVideo.srcObject = stream;
                  liveVideo.play().catch(function (error) {
                    log("Video autoplay error: " + error.message);
                  });
                  log("Remote video track received");
                };

                try {
                  dataChannel = peerConnection.createDataChannel(serial);
                  dataChannel.onopen = function () {
                    log("Data channel open; requesting live start");
                    var command = {
                      action: "startLive",
                      requestID: String(Date.now()),
                      connectionID: "",
                      timeStamp: String(Date.now()),
                      size: "medium",
                      resolution: "1280x720"
                    };
                    dataChannel.send(JSON.stringify(command));
                  };
                  dataChannel.onclose = function () {
                    log("Data channel closed");
                  };
                } catch (error) {
                  log("Data channel error: " + error.message);
                }

                try {
                  peerConnection.addTransceiver("audio", { direction: "sendrecv" });
                  peerConnection.addTransceiver("video", { direction: "recvonly" });
                } catch (error) {
                  log("Transceiver setup warning: " + error.message);
                }

                signalSocket = new WebSocket(normalizeSignalUrl(currentTicket.signalServer, currentTicket.websocketPath));
                signalSocket.onopen = function () {
                  log("Signal socket open");
                  sendAuth();
                  flushSignalQueue();
                };
                signalSocket.onmessage = function (event) {
                  try {
                    handleSignalMessage(JSON.parse(event.data));
                  } catch (error) {
                    log("Signal parse error: " + error.message);
                  }
                };
                signalSocket.onclose = function () {
                  log("Signal socket closed");
                  if (keepaliveTimer) {
                    window.clearInterval(keepaliveTimer);
                    keepaliveTimer = null;
                  }
                  if (!liveVideo.srcObject) {
                    setControlsRunning(false);
                  }
                };
                signalSocket.onerror = function () {
                  log("Signal socket error");
                };
              });
            }

            refreshButton.onclick = function () {
              loadDevices(currentSerial, preferredIp);
            };
            useManualButton.onclick = function () {
              var manualTarget = manualTargetInput.value.trim();
              if (!manualTarget) {
                log("Enter a serial number or IP first");
                return;
              }
              var existing = false;
              for (var i = 0; i < deviceSelect.options.length; i += 1) {
                if ((deviceSelect.options[i].value || "") === manualTarget) {
                  deviceSelect.selectedIndex = i;
                  existing = true;
                  break;
                }
              }
              if (!existing) {
                var option = document.createElement("option");
                option.value = manualTarget;
                option.textContent = "Manual target • " + manualTarget;
                option.selected = true;
                deviceSelect.appendChild(option);
              }
              deviceSelect.value = manualTarget;
              log("Using manual target " + manualTarget);
            };
            startButton.onclick = startLive;
            stopButton.onclick = stopLive;
            deviceSelect.onchange = function () {
              log("Selected " + (deviceSelect.options[deviceSelect.selectedIndex] ? deviceSelect.options[deviceSelect.selectedIndex].text : "camera"));
            };

            var urlParams = new URLSearchParams(window.location.search);
            var preferredSerial = urlParams.get("serial") || "";
            var preferredIp = urlParams.get("ip") || "";
            var autoStart = urlParams.get("autostart") === "1";

            loadSession();
            loadDevices(preferredSerial, preferredIp);
            if (autoStart && (preferredSerial || preferredIp)) {
              window.setTimeout(function () {
                if (preferredSerial) {
                  deviceSelect.value = preferredSerial;
                }
                if (!deviceSelect.value && preferredIp) {
                  for (var i = 0; i < deviceSelect.options.length; i += 1) {
                    var text = deviceSelect.options[i].text || "";
                    if (text.indexOf(preferredIp) !== -1) {
                      deviceSelect.selectedIndex = i;
                      break;
                    }
                  }
                }
                startLive();
              }, 500);
            }
            setControlsRunning(false);
          })();
          </script>
        </body>
        </html>
    """.trimIndent()
}

private fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
