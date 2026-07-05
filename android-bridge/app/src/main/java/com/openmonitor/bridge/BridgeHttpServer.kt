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

            consumeHeaders(input)

            when {
                method == "GET" && path == "/" -> respondText(output, 200, "text/html; charset=utf-8", rootPage())
                method == "GET" && path == "/api/state" -> respondText(output, 200, "application/json; charset=utf-8", BridgeStateStore.snapshot().toJson())
                method == "GET" && path == "/api/logs" -> respondText(output, 200, "application/json; charset=utf-8", logsJson())
                method == "GET" && path == "/api/cameras" -> respondText(output, 200, "application/json; charset=utf-8", camerasJson())
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
                <p class="muted">Phone-hosted RTSP to HLS bridge.</p>
                <p>Server: <code>${escapeHtml(serverUrl)}</code></p>
                <p>Status: <strong>${escapeHtml(state.status)}</strong></p>
                <p>Message: ${escapeHtml(state.message)}</p>
                <p>RTSP source: <code>${escapeHtml(state.rtspUrl.ifBlank { "none" })}</code></p>
                <p>HLS: ${if (state.playlistUrl.isBlank()) "<code>none</code>" else """<a href="${escapeHtml("$serverUrl${state.playlistUrl}")}">${escapeHtml("$serverUrl${state.playlistUrl}")}</a>"""}</p>
                <h2>Discovered cameras</h2>
                <div style="background:#0b1220; border-radius:12px; padding:12px; max-height:240px; overflow:auto;">
                  ${camerasListHtml()}
                </div>
                <h2>Logs</h2>
                <pre style="white-space: pre-wrap; word-break: break-word; background:#0b1220; border-radius:12px; padding:12px; max-height:320px; overflow:auto;">${escapeHtml(logsText(40))}</pre>
                <p class="muted">Use the Android app to start or stop the bridge. Open the HLS URL from another device on the same Wi‑Fi.</p>
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

    private fun respondBytes(output: BufferedOutputStream, status: Int, contentType: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            404 -> "Not Found"
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

private fun String.jsonEscape(): String {
    return replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
