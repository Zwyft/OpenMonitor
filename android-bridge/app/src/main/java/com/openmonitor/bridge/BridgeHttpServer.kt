package com.openmonitor.bridge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class BridgeHttpServer(
    private val cacheRoot: File,
    private val port: Int = BridgeConfig.HTTP_PORT,
) {
    private val running = AtomicBoolean(false)
    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "BridgeHttpServer", isDaemon = true) {
            ServerSocket(port).use { socket ->
                serverSocket = socket
                while (running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    thread(name = "BridgeHttpClient", isDaemon = true) {
                        handleClient(client)
                    }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }

    fun baseUrl(): String = NetworkUtils.serverUrl(port)

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val requestLine = input.readLineUtf8() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?")
            val query = rawPath.substringAfter("?", "")

            consumeHeaders(input)

            when {
                method == "GET" && path == "/" -> respondText(output, 200, "text/html; charset=utf-8", rootPage())
                method == "GET" && path == "/live" -> respondText(output, 200, "text/html; charset=utf-8", baseusCloudLivePage(baseUrl()))
                method == "GET" && path == "/api/state" -> respondText(output, 200, "application/json; charset=utf-8", BridgeStateStore.snapshot().toJson())
                method == "GET" && path == "/api/logs" -> respondText(output, 200, "application/json; charset=utf-8", logsJson())
                method == "GET" && path == "/api/logs.txt" -> respondDownload(output, "text/plain; charset=utf-8", "openmonitor-bridge.log", logsText(1000))
                method == "GET" && path == "/api/vpn/state" -> respondText(output, 200, "application/json; charset=utf-8", vpnStateJson())
                method == "GET" && path == "/api/vicohome/session" -> respondText(output, 200, "application/json; charset=utf-8", vicohomeSessionJson())
                method == "GET" && path == "/api/cameras" -> respondText(output, 200, "application/json; charset=utf-8", camerasJson())
                method == "GET" && path == "/api/vicohome/devices" -> respondText(output, 200, "application/json; charset=utf-8", vicohomeDevicesJson())
                method == "GET" && path == "/api/vicohome/events" -> respondText(output, 200, "application/json; charset=utf-8", vicohomeEventsJson())
                method == "GET" && path == "/api/vicohome/live-ticket" -> respondText(output, liveTicketStatus(query), "application/json; charset=utf-8", liveTicketJson(query))
                method == "GET" && path.startsWith("/hls/") -> serveFile(output, path.removePrefix("/hls/"))
                else -> respondText(output, 404, "text/plain; charset=utf-8", "Not found")
            }
        }
    }

    private fun rootPage(): String {
        val state = BridgeStateStore.snapshot()
        val serverUrl = baseUrl()
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>OpenMonitor Bridge</title>
              <style>
                body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; margin: 24px; background: #0f172a; color: #e2e8f0; }
                .card { max-width: 720px; margin: 0 auto; background: #111827; border: 1px solid #334155; border-radius: 18px; padding: 20px; }
                code, input { width: 100%; box-sizing: border-box; }
                code { display: block; padding: 12px; background: #0b1220; border-radius: 12px; overflow-x: auto; }
                a { color: #7dd3fc; }
                .muted { color: #94a3b8; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>OpenMonitor Bridge</h1>
                <p class="muted">Phone-hosted RTSP bridge and Baseus cloud live viewer.</p>
                <p>Server: <code>${escapeHtml(serverUrl)}</code></p>
                <p>Status: <strong>${escapeHtml(state.status)}</strong></p>
                <p>Message: ${escapeHtml(state.message)}</p>
                <p>RTSP source: <code>${escapeHtml(state.rtspUrl.ifBlank { "none" })}</code></p>
                <p>HLS: ${if (state.playlistUrl.isBlank()) "<code>none</code>" else """<a href="${escapeHtml("$serverUrl${state.playlistUrl}")}">${escapeHtml("$serverUrl${state.playlistUrl}")}</a>"""}</p>
                <h2>Discovered cameras</h2>
                <div style="background:#0b1220; border-radius:12px; padding:12px; max-height:240px; overflow:auto;">
                  ${camerasListHtml()}
                </div>
                <h2>Baseus cloud data</h2>
                <div style="background:#0b1220; border-radius:12px; padding:12px; max-height:320px; overflow:auto;">
                  ${vicohomeListHtml(serverUrl)}
                </div>
                <p><a href="${escapeHtml("$serverUrl/live")}">Open Baseus cloud live viewer</a></p>
                <h2>Baseus proxy capture</h2>
                <div style="background:#0b1220; border-radius:12px; padding:12px; max-height:180px; overflow:auto;">
                  ${proxyCaptureHtml()}
                </div>
                <h2>Baseus VPN capture</h2>
                <div style="background:#0b1220; border-radius:12px; padding:12px; max-height:180px; overflow:auto;">
                  ${vpnCaptureHtml(serverUrl)}
                </div>
                <h2>Logs</h2>
                <pre style="white-space: pre-wrap; word-break: break-word; background:#0b1220; border-radius:12px; padding:12px; max-height:320px; overflow:auto;">${escapeHtml(logsText(40))}</pre>
                <p><a href="${escapeHtml("$serverUrl/api/logs.txt")}">Download full log file</a></p>
                <p class="muted">Use the Android app to start or stop the bridge. Open the HLS URL for RTSP streams or the /live page for Baseus cloud live video from another device on the same Wi‑Fi.</p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun serveFile(output: BufferedOutputStream, relativePath: String) {
        val file = File(cacheRoot, "hls/$relativePath").canonicalFile
        val baseDir = File(cacheRoot, "hls").canonicalFile
        if (!file.toPath().startsWith(baseDir.toPath()) || !file.exists() || !file.isFile) {
            respondText(output, 404, "text/plain; charset=utf-8", "Not found")
            return
        }
        val contentType = when {
            file.name.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            file.name.endsWith(".ts") -> "video/mp2t"
            else -> "application/octet-stream"
        }
        val data = file.readBytes()
        respondBytes(output, 200, contentType, data)
    }

    private fun respondText(output: BufferedOutputStream, status: Int, contentType: String, body: String) {
        respondBytes(output, status, contentType, body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun respondDownload(output: BufferedOutputStream, contentType: String, filename: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ")
            append(contentType)
            append("\r\n")
            append("Content-Disposition: attachment; filename=\"")
            append(filename)
            append("\"\r\n")
            append("Content-Length: ")
            append(bytes.size)
            append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun respondBytes(output: BufferedOutputStream, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 ")
            append(status)
            append(' ')
            append(reason)
            append("\r\n")
            append("Content-Type: ")
            append(contentType)
            append("\r\n")
            append("Content-Length: ")
            append(body.size)
            append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun consumeHeaders(input: BufferedInputStream) {
        while (true) {
            val line = input.readLineUtf8() ?: break
            if (line.isEmpty()) break
        }
    }

    private fun BufferedInputStream.readLineUtf8(): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = read()
            if (value == -1) {
                if (bytes.isEmpty()) return null
                break
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) {
                bytes.add(value.toByte())
            }
        }
        val rawBytes = ByteArray(bytes.size)
        for (index in bytes.indices) {
            rawBytes[index] = bytes[index]
        }
        return String(rawBytes, StandardCharsets.UTF_8)
    }

    private fun escapeHtml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun logsText(limit: Int): String {
        return BridgeLogStore.snapshot(limit)
            .joinToString("\n") { it.formatLine() }
            .ifBlank { "No logs yet." }
    }

    private fun logsJson(): String {
        val logs = BridgeLogStore.snapshot()
        return buildString {
            append('{')
            append("\"entries\":[")
            logs.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append('{')
                append("\"timestampMillis\":")
                append(entry.timestampMillis)
                append(",\"level\":\"")
                append(entry.level.jsonEscape())
                append("\",\"message\":\"")
                append(entry.message.jsonEscape())
                append("\"}")
            }
            append("]}")
        }
    }

    private fun vpnStateJson(): String {
        val state = BaseusVpnCaptureStateStore.snapshot()
        return buildString {
            append('{')
            append("\"running\":")
            append(state.running)
            append(",\"message\":\"")
            append(state.message.jsonEscape())
            append("\",\"packageName\":\"")
            append(state.packageName.jsonEscape())
            append("\",\"targetIp\":\"")
            append(state.targetIp.jsonEscape())
            append("\",\"updatedAtMillis\":")
            append(state.updatedAtMillis)
            append('}')
        }
    }

    private fun vicohomeSessionJson(): String {
        val session = VicohomeSessionStore.snapshot()
        return buildString {
            append('{')
            append("\"available\":")
            append(session != null)
            append(",\"message\":\"")
            append(
                when (session) {
                    null -> "No Baseus cloud session loaded yet."
                    else -> "Baseus cloud session ready for ${session.region.label}"
                }.jsonEscape(),
            )
            append("\",\"email\":\"")
            append(session?.email.orEmpty().jsonEscape())
            append("\",\"region\":\"")
            append(session?.region?.label.orEmpty().jsonEscape())
            append("\",\"apiBase\":\"")
            append(session?.region?.apiBase.orEmpty().jsonEscape())
            append("\",\"webrtcApiBase\":\"")
            append(session?.region?.webrtcApiBase.orEmpty().jsonEscape())
            append("\",\"updatedAtMillis\":")
            append(session?.updatedAtMillis ?: 0L)
            append('}')
        }
    }

    private fun liveTicketStatus(query: String): Int {
        val session = VicohomeSessionStore.snapshot()
        return when {
            session == null -> 409
            extractQueryParam(query, "serial").isBlank() -> 400
            else -> 200
        }
    }

    private fun liveTicketJson(query: String): String {
        val session = VicohomeSessionStore.snapshot()
            ?: return errorJson("Baseus cloud session not loaded yet. Run Vicohome sync first.")
        val serial = extractQueryParam(query, "serial")
        if (serial.isBlank()) {
            return errorJson("Missing serial parameter.")
        }
        return try {
            val ticket = VicohomeClient("", "").fetchLiveTicket(session, serial)
            buildString {
                append('{')
                append("\"message\":\"Live ticket ready\",")
                append("\"serialNumber\":\"")
                append(serial.jsonEscape())
                append("\",\"region\":\"")
                append(session.region.label.jsonEscape())
                append("\",\"ticket\":")
                append(ticket.toJson())
                append('}')
            }
        } catch (exception: Exception) {
            BridgeLogStore.error("Live ticket request failed: ${exception.stackTraceToString()}")
            errorJson(exception.message ?: "Live ticket request failed")
        }
    }

    private fun camerasListHtml(): String {
        val cameras = CameraDiscoveryStore.snapshot()
        if (cameras.isEmpty()) {
            return "<div class=\"muted\">No cameras discovered yet.</div>"
        }
        return buildString {
            append("<ul style=\"margin:0; padding-left:20px;\">")
            cameras.forEach { camera ->
                append("<li style=\"margin-bottom:10px;\">")
                append("<div><strong>")
                append(escapeHtml(camera.label))
                append("</strong> — ")
                append(escapeHtml(camera.source))
                if (camera.needsAuth) {
                    append(" — auth required")
                }
                append("</div>")
                if (camera.details.isNotBlank()) {
                    append("<div class=\"muted\">")
                    append(escapeHtml(camera.details))
                    append("</div>")
                }
                append("<div><code>")
                append(escapeHtml(camera.streamUrl))
                append("</code></div>")
                append("</li>")
            }
            append("</ul>")
        }
    }

    private fun camerasJson(): String {
        val cameras = CameraDiscoveryStore.snapshot()
        return buildString {
            append('{')
            append("\"entries\":[")
            cameras.forEachIndexed { index, camera ->
                if (index > 0) append(',')
                append('{')
                append("\"label\":\"")
                append(camera.label.jsonEscape())
                append("\",\"streamUrl\":\"")
                append(camera.streamUrl.jsonEscape())
                append("\",\"source\":\"")
                append(camera.source.jsonEscape())
                append("\",\"details\":\"")
                append(camera.details.jsonEscape())
                append("\",\"needsAuth\":")
                append(camera.needsAuth)
                append("}")
            }
            append("]}")
        }
    }

    private fun vicohomeListHtml(serverUrl: String): String {
        val devices = VicohomeDataStore.snapshotDevices()
        val events = VicohomeDataStore.snapshotEvents()
        val message = VicohomeDataStore.snapshotMessage().ifBlank { "No Baseus cloud data loaded yet." }
        val session = VicohomeSessionStore.snapshot()
        return buildString {
            append("<div class=\"muted\">")
            append(escapeHtml(message))
            append("</div>")
            append("<div class=\"muted\">")
            append(
                if (session == null) {
                    "Live viewer is unavailable until you sync the Baseus cloud account in the Android app."
                } else {
                    "Live viewer ready for ${session.region.label}. Open <a href=\"${escapeHtml("$serverUrl/live")}\">/live</a> from Safari."
                },
            )
            append("</div>")
            if (devices.isNotEmpty()) {
                append("<h3>Devices</h3>")
                append("<ul style=\"margin:0; padding-left:20px;\">")
                devices.forEach { device ->
                    val liveLink = buildString {
                        append(serverUrl)
                        append("/live?serial=")
                        append(java.net.URLEncoder.encode(device.serialNumber, "UTF-8"))
                        if (device.ip.isNotBlank()) {
                            append("&ip=")
                            append(java.net.URLEncoder.encode(device.ip, "UTF-8"))
                        }
                    }
                    append("<li style=\"margin-bottom:8px;\">")
                    append("<div><strong>")
                    append(escapeHtml(device.deviceName.ifBlank { device.serialNumber }))
                    append("</strong> — ")
                    append(escapeHtml(device.modelNo.ifBlank { "unknown model" }))
                    if (device.ip.isNotBlank()) {
                        append(" — ")
                        append(escapeHtml(device.ip))
                    }
                    append(" — <a href=\"")
                    append(escapeHtml(liveLink))
                    append("\">Open live</a>")
                    append("</div>")
                    if (device.locationName.isNotBlank()) {
                        append("<div class=\"muted\">")
                        append(escapeHtml(device.locationName))
                        append("</div>")
                    }
                    append("</li>")
                }
                append("</ul>")
            }
            if (events.isNotEmpty()) {
                append("<h3>Recent clips</h3>")
                append("<ul style=\"margin:0; padding-left:20px;\">")
                events.take(20).forEach { event ->
                    append("<li style=\"margin-bottom:8px;\">")
                    append("<div><strong>")
                    append(escapeHtml(event.deviceName.ifBlank { event.traceId }))
                    append("</strong> — ")
                    append(escapeHtml(event.timestamp))
                    if (event.birdName.isNotBlank()) {
                        append(" — ")
                        append(escapeHtml(event.birdName))
                    }
                    append("</div>")
                    if (event.videoUrl.isNotBlank()) {
                        append("<div><a href=\"")
                        append(escapeHtml(event.videoUrl))
                        append("\">")
                        append(escapeHtml(event.videoUrl))
                        append("</a></div>")
                    }
                    append("</li>")
                }
                append("</ul>")
            }
        }
    }

    private fun vicohomeDevicesJson(): String {
        val devices = VicohomeDataStore.snapshotDevices()
        return buildString {
            append('{')
            append("\"message\":\"")
            append(VicohomeDataStore.snapshotMessage().jsonEscape())
            append("\",\"entries\":[")
            devices.forEachIndexed { index, device ->
                if (index > 0) append(',')
                append('{')
                append("\"serialNumber\":\"")
                append(device.serialNumber.jsonEscape())
                append("\",\"modelNo\":\"")
                append(device.modelNo.jsonEscape())
                append("\",\"deviceName\":\"")
                append(device.deviceName.jsonEscape())
                append("\",\"networkName\":\"")
                append(device.networkName.jsonEscape())
                append("\",\"ip\":\"")
                append(device.ip.jsonEscape())
                append("\",\"batteryLevel\":")
                append(device.batteryLevel)
                append(",\"locationName\":\"")
                append(device.locationName.jsonEscape())
                append("\",\"signalStrength\":")
                append(device.signalStrength)
                append(",\"wifiChannel\":")
                append(device.wifiChannel)
                append(",\"isCharging\":")
                append(device.isCharging)
                append(",\"chargingMode\":")
                append(device.chargingMode)
                append(",\"macAddress\":\"")
                append(device.macAddress.jsonEscape())
                append("\"}")
            }
            append("]}")
        }
    }

    private fun vicohomeEventsJson(): String {
        val events = VicohomeDataStore.snapshotEvents()
        return buildString {
            append('{')
            append("\"message\":\"")
            append(VicohomeDataStore.snapshotMessage().jsonEscape())
            append("\",\"entries\":[")
            events.forEachIndexed { index, event ->
                if (index > 0) append(',')
                append('{')
                append("\"traceId\":\"")
                append(event.traceId.jsonEscape())
                append("\",\"timestamp\":\"")
                append(event.timestamp.jsonEscape())
                append("\",\"deviceName\":\"")
                append(event.deviceName.jsonEscape())
                append("\",\"serialNumber\":\"")
                append(event.serialNumber.jsonEscape())
                append("\",\"adminName\":\"")
                append(event.adminName.jsonEscape())
                append("\",\"period\":\"")
                append(event.period.jsonEscape())
                append("\",\"birdName\":\"")
                append(event.birdName.jsonEscape())
                append("\",\"birdLatin\":\"")
                append(event.birdLatin.jsonEscape())
                append("\",\"birdConfidence\":")
                append(event.birdConfidence)
                append(",\"keyShotUrl\":\"")
                append(event.keyShotUrl.jsonEscape())
                append("\",\"imageUrl\":\"")
                append(event.imageUrl.jsonEscape())
                append("\",\"videoUrl\":\"")
                append(event.videoUrl.jsonEscape())
                append("\"}")
            }
            append("]}")
        }
    }

    private fun errorJson(message: String): String {
        return buildString {
            append('{')
            append("\"message\":\"")
            append(message.jsonEscape())
            append("\"}")
        }
    }

    private fun extractQueryParam(query: String, key: String): String {
        if (query.isBlank()) return ""
        return query.split('&')
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.let { java.net.URLDecoder.decode(it, StandardCharsets.UTF_8) }
            .orEmpty()
    }

    private fun proxyCaptureHtml(): String {
        val state = ProxyCaptureStateStore.snapshot()
        return buildString {
            append("<div><strong>")
            append(if (state.running) "Running" else "Stopped")
            append("</strong> — ")
            append(escapeHtml(state.message))
            append("</div>")
            append("<div class=\"muted\">")
            append("Set the Android phone Wi‑Fi proxy to <code>127.0.0.1:")
            append(state.port)
            append("</code> before launching the Baseus app.")
            append("</div>")
        }
    }

    private fun vpnCaptureHtml(serverUrl: String): String {
        val state = BaseusVpnCaptureStateStore.snapshot()
        return buildString {
            append("<div><strong>")
            append(if (state.running) "Running" else "Stopped")
            append("</strong> — ")
            append(escapeHtml(state.message))
            append("</div>")
            append("<div class=\"muted\">")
            append("VPN state: <code>")
            append(escapeHtml("$serverUrl/api/vpn/state"))
            append("</code><br>")
            append("VPN logs: <code>")
            append(escapeHtml("$serverUrl/api/logs"))
            append("</code>")
            append("</div>")
            append("<div class=\"muted\">")
            append("Target package: <code>")
            append(escapeHtml(state.packageName))
            append("</code><br>")
            append("Target camera IP: <code>")
            append(escapeHtml(state.targetIp))
            append("</code>")
            append("</div>")
        }
    }
}

private fun BridgeState.toJson(): String {
    return buildString {
        append('{')
        append("\"serverUrl\":\"")
        append(serverUrl.jsonEscape())
        append("\",\"bridgeId\":\"")
        append(bridgeId.jsonEscape())
        append("\",\"rtspUrl\":\"")
        append(rtspUrl.jsonEscape())
        append("\",\"playlistUrl\":\"")
        append(playlistUrl.jsonEscape())
        append("\",\"status\":\"")
        append(status.jsonEscape())
        append("\",\"message\":\"")
        append(message.jsonEscape())
        append("\",\"updatedAtMillis\":")
        append(updatedAtMillis)
        append('}')
    }
}

private fun VicohomeLiveTicket.toJson(): String {
    return buildString {
        append('{')
        append("\"traceId\":\"")
        append(traceId.jsonEscape())
        append("\",\"groupId\":\"")
        append(groupId.jsonEscape())
        append("\",\"role\":\"")
        append(role.jsonEscape())
        append("\",\"id\":\"")
        append(id.jsonEscape())
        append("\",\"iceServer\":[")
        iceServer.forEachIndexed { index, server ->
            if (index > 0) append(',')
            append('{')
            append("\"url\":\"")
            append(server.url.jsonEscape())
            append("\",\"username\":\"")
            append(server.username.jsonEscape())
            append("\",\"credential\":\"")
            append(server.credential.jsonEscape())
            append("\",\"ipAddress\":\"")
            append(server.ipAddress.jsonEscape())
            append("\"}")
        }
        append("]")
        append(",\"signalServer\":\"")
        append(signalServer.jsonEscape())
        append("\",\"signalServerIpAddress\":\"")
        append(signalServerIpAddress.jsonEscape())
        append("\",\"sign\":\"")
        append(sign.jsonEscape())
        append("\",\"signalPingInterval\":")
        append(signalPingInterval)
        append(",\"maxAllocationLimit\":")
        append(maxAllocationLimit)
        append(",\"appStopLiveTimeout\":")
        append(appStopLiveTimeout)
        append(",\"deviceSleepTimeout\":")
        append(deviceSleepTimeout)
        append(",\"time\":")
        append(time)
        append(",\"expirationTime\":")
        append(expirationTime)
        append(",\"websocketPath\":\"")
        append(websocketPath.jsonEscape())
        append("\",\"accessToken\":\"")
        append(accessToken.jsonEscape())
        append("\",\"realCxSerialNumber\":\"")
        append(realCxSerialNumber.orEmpty().jsonEscape())
        append("\",\"countryNo\":\"")
        append(countryNo.orEmpty().jsonEscape())
        append("\"}")
    }
}

private fun String.jsonEscape(): String {
    return replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
