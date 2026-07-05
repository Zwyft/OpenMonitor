(function () {
  var state = { endpoints: [], status_text: "Ready", is_refreshing: false, last_refresh: null, error_message: null };
  var selectedId = null;
  var selectedEndpoint = null;
  var snapshotTimer = null;
  var pollTimer = null;
  var bridgeTimer = null;

  function byId(id) {
    return document.getElementById(id);
  }

  function requestJSON(method, url, body, callback) {
    var xhr = new XMLHttpRequest();
    xhr.open(method, url, true);
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      if (xhr.status >= 200 && xhr.status < 300) {
        var text = xhr.responseText || "{}";
        try {
          callback(null, JSON.parse(text));
        } catch (error) {
          callback(error);
        }
      } else {
        callback(new Error(xhr.responseText || xhr.statusText || ("HTTP " + xhr.status)));
      }
    };
    if (body) xhr.setRequestHeader("Content-Type", "application/json");
    xhr.send(body ? JSON.stringify(body) : null);
  }

  function loadState() {
    requestJSON("GET", "/api/state", null, function (error, response) {
      if (error) {
        byId("statusText").textContent = error.message;
        return;
      }
      applyState(response);
    });
  }

  function refresh(deep) {
    byId("refreshBadge").textContent = deep ? "Deep scan" : "Refreshing";
    requestJSON("GET", "/api/scan?deep=" + (deep ? "1" : "0"), null, function (error, response) {
      if (error) {
        byId("statusText").textContent = error.message;
        byId("refreshBadge").textContent = "Error";
        return;
      }
      applyState(response);
      byId("refreshBadge").textContent = "Updated";
      window.setTimeout(function () {
        byId("refreshBadge").textContent = state.is_refreshing ? "Scanning" : "Idle";
      }, 1200);
    });
  }

  function manualSubmit(event) {
    event.preventDefault();
    var payload = {
      mode: byId("modeSelect").value,
      host: byId("hostInput").value,
      port: byId("portInput").value,
      path: byId("pathInput").value,
      username: byId("userInput").value,
      password: byId("passwordInput").value
    };

    requestJSON("POST", "/api/manual", payload, function (error, response) {
      if (error) {
        byId("statusText").textContent = error.message;
        return;
      }
      applyState(response.state);
      selectEndpoint(response.endpoint.id);
      byId("manualForm").reset();
      byId("portInput").value = payload.mode === "rtsp" ? "554" : "80";
      byId("pathInput").value = payload.mode === "onvif" ? "/onvif/device_service" : "/stream";
    });
  }

  function applyState(nextState) {
    state = nextState || state;
    byId("statusText").textContent = state.status_text || "Ready";
    byId("refreshBadge").textContent = state.is_refreshing ? "Scanning" : "Idle";
    byId("cameraCount").textContent = String((state.endpoints || []).length);
    byId("lastRefresh").textContent = state.last_refresh ? formatTimestamp(state.last_refresh) : "—";
    renderCameraList();

    if (!selectedId && state.endpoints && state.endpoints.length) {
      selectEndpoint(state.endpoints[0].id);
    } else {
      renderPreview();
    }
  }

  function renderCameraList() {
    var container = byId("cameraList");
    container.innerHTML = "";

    if (!state.endpoints || !state.endpoints.length) {
      var empty = document.createElement("div");
      empty.className = "camera-card";
      empty.innerHTML = "<div class='camera-title'>No cameras yet</div><div class='camera-subtitle'>Run a scan or add a manual endpoint.</div>";
      container.appendChild(empty);
      return;
    }

    for (var i = 0; i < state.endpoints.length; i += 1) {
      (function (endpoint) {
        var card = document.createElement("div");
        card.className = "camera-card" + (selectedId === endpoint.id ? " selected" : "");
        card.onclick = function () {
          selectEndpoint(endpoint.id);
        };

        var source = endpoint.source || "Unknown";
        var subtitle = endpoint.transport + " • " + endpoint.host + (endpoint.port ? ":" + endpoint.port : "");
        var details = endpoint.details || "";
        if (endpoint.is_baseus_candidate) {
          details = details ? details + " • Baseus candidate" : "Baseus candidate";
        }

        card.innerHTML =
          "<div class='camera-title-row'><div class='camera-title'>" + escapeHtml(endpoint.name || endpoint.host) + "</div><div class='camera-source'>" + escapeHtml(source) + "</div></div>" +
          "<div class='camera-subtitle'>" + escapeHtml(subtitle) + "</div>" +
          "<div class='camera-details'>" + escapeHtml(details || endpoint.playback_strategy || "") + "</div>";
        container.appendChild(card);
      })(state.endpoints[i]);
    }
  }

  function selectEndpoint(id) {
    selectedId = id;
    selectedEndpoint = null;
    for (var i = 0; i < state.endpoints.length; i += 1) {
      if (state.endpoints[i].id === id) {
        selectedEndpoint = state.endpoints[i];
        break;
      }
    }
    renderCameraList();
    renderPreview();
  }

  function renderPreview() {
    clearSnapshotTimer();
    clearBridgeTimer();

    var stage = byId("previewStage");
    stage.innerHTML = "";

    if (!selectedEndpoint) {
      stage.innerHTML = "<div class='empty-state'><div class='empty-icon'>◌</div><h3>No camera selected</h3><p>Use the scan buttons on the left, then tap a camera card.</p></div>";
      byId("selectedTitle").textContent = "Select a camera";
      byId("selectedSubtitle").textContent = "The preview will appear here.";
      byId("selectedSource").textContent = "—";
      byId("streamUrl").textContent = "—";
      byId("snapshotUrl").textContent = "—";
      byId("selectedDetails").textContent = "—";
      return;
    }

    var endpoint = selectedEndpoint;
    byId("selectedTitle").textContent = endpoint.name || endpoint.host;
    byId("selectedSubtitle").textContent = endpoint.details || (endpoint.source + " endpoint");
    byId("selectedSource").textContent = endpoint.source + " / " + endpoint.transport;
    byId("streamUrl").textContent = endpoint.stream_url || "—";
    byId("snapshotUrl").textContent = endpoint.snapshot_url || "—";
    byId("selectedDetails").textContent = buildDetails(endpoint);

    var playbackMode = endpoint.playback_strategy || "stream";
    if (endpoint.transport === "RTSP" && playbackMode === "stream") {
      stage.innerHTML = "<div class='empty-state'><div class='empty-icon'>↻</div><h3>Starting RTSP bridge</h3><p>Transcoding the stream into HLS for Safari…</p></div>";
      startBridge(endpoint);
      return;
    }

    if (playbackMode === "hls" || /\.m3u8(?:\?|$)/i.test(endpoint.stream_url || "")) {
      var video = document.createElement("video");
      video.setAttribute("controls", "controls");
      video.setAttribute("autoplay", "autoplay");
      video.setAttribute("muted", "muted");
      video.setAttribute("playsinline", "playsinline");
      video.src = proxyUrl(endpoint.stream_url);
      stage.appendChild(video);
      return;
    }

    if (endpoint.snapshot_url || (endpoint.transport === "HTTP" && endpoint.playback_strategy !== "inspection")) {
      var image = document.createElement("img");
      var snapshotSource = endpoint.snapshot_url || endpoint.stream_url;
      image.alt = endpoint.name || "Camera preview";
      image.src = proxyUrl(snapshotSource) + "&t=" + new Date().getTime();
      image.onload = function () {
        stage.className = "preview-stage loaded";
      };
      stage.appendChild(image);
      startSnapshotRefresh(image, snapshotSource);
      return;
    }

    if (endpoint.playback_strategy === "inspection") {
      var frame = document.createElement("iframe");
      frame.setAttribute("title", endpoint.name || "Camera inspection");
      frame.src = proxyUrl(endpoint.stream_url);
      stage.appendChild(frame);
      return;
    }

    stage.innerHTML = "<div class='empty-state'><div class='empty-icon'>▶</div><h3>Browser playback not available</h3><p>This endpoint looks like RTSP only. If the camera exposes HLS or a snapshot URL, the dashboard can show it here.</p></div>";
  }

  function startBridge(endpoint) {
    requestJSON("POST", "/api/bridge", {
      mode: "rtsp",
      name: endpoint.name,
      host: endpoint.host,
      port: endpoint.port || 554,
      path: endpoint.path || "/",
      username: endpoint.username || "",
      password: endpoint.password || ""
    }, function (error, response) {
      if (error) {
        byId("statusText").textContent = error.message;
        var stage = byId("previewStage");
        stage.innerHTML = "<div class='empty-state'><div class='empty-icon'>!</div><h3>Bridge unavailable</h3><p>" + escapeHtml(error.message) + "</p></div>";
        return;
      }

      var bridge = response.bridge;
      var stage = byId("previewStage");
      stage.innerHTML = "";

      var video = document.createElement("video");
      video.setAttribute("controls", "controls");
      video.setAttribute("autoplay", "autoplay");
      video.setAttribute("muted", "muted");
      video.setAttribute("playsinline", "playsinline");
      video.src = bridge.playlist_url + "?t=" + new Date().getTime();
      stage.appendChild(video);

      byId("statusText").textContent = "RTSP bridge running";
      byId("streamUrl").textContent = bridge.source_url;
      byId("selectedDetails").textContent = "RTSP bridged to HLS for Safari playback.";
      bridgeTimer = window.setInterval(function () {
        probeBridge(bridge.id, video);
      }, 4000);
    });
  }

  function probeBridge(bridgeId, video) {
    requestJSON("GET", "/api/bridge?id=" + encodeURIComponent(bridgeId), null, function (error, response) {
      if (error) return;
      if (response.status === "error") {
        byId("statusText").textContent = "Bridge failed";
        clearBridgeTimer();
        return;
      }
      if (response.playlist_url && video && video.src.indexOf(response.playlist_url) === -1) {
        video.src = response.playlist_url + "?t=" + new Date().getTime();
      }
    });
  }

  function startSnapshotRefresh(image, sourceUrl) {
    snapshotTimer = window.setInterval(function () {
      image.src = proxyUrl(sourceUrl) + "&t=" + new Date().getTime();
    }, 1500);
  }

  function clearSnapshotTimer() {
    if (snapshotTimer) {
      window.clearInterval(snapshotTimer);
      snapshotTimer = null;
    }
  }

  function clearBridgeTimer() {
    if (bridgeTimer) {
      window.clearInterval(bridgeTimer);
      bridgeTimer = null;
    }
  }

  function proxyUrl(url) {
    return "/api/proxy?url=" + encodeURIComponent(url);
  }

  function buildDetails(endpoint) {
    var parts = [];
    if (endpoint.playback_strategy) parts.push("Playback: " + endpoint.playback_strategy);
    if (endpoint.confidence !== undefined) parts.push("Confidence: " + Math.round(endpoint.confidence * 100) + "%");
    if (endpoint.is_baseus_candidate) parts.push("Baseus fingerprint matched");
    if (endpoint.last_seen) parts.push("Last seen: " + formatTimestamp(endpoint.last_seen));
    return parts.join(" • ") || "—";
  }

  function formatTimestamp(value) {
    var date = new Date(value * 1000);
    return date.toLocaleString();
  }

  function escapeHtml(value) {
    return String(value || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function wireControls() {
    byId("refreshButton").onclick = function () {
      refresh(false);
    };
    byId("deepScanButton").onclick = function () {
      refresh(true);
    };
    byId("manualForm").onsubmit = manualSubmit;
    byId("modeSelect").onchange = function () {
      var mode = byId("modeSelect").value;
      byId("portInput").value = mode === "rtsp" ? "554" : "80";
      byId("pathInput").value = mode === "onvif" ? "/onvif/device_service" : (mode === "hls" ? "/stream.m3u8" : "/stream");
    };
  }

  function startPolling() {
    pollTimer = window.setInterval(function () {
      loadState();
    }, 5000);
  }

  wireControls();
  loadState();
  startPolling();
})();
