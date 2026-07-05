package com.openmonitor.bridge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BaseusProxyServer(
    private val port: Int = BridgeConfig.PROXY_PORT,
) {
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start(onEvent: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) {
            onEvent("Proxy capture already running")
            return
        }
        executor.execute {
            try {
                ServerSocket(port).use { socket ->
                    serverSocket = socket
                    onEvent("Proxy capture listening on 127.0.0.1:$port")
                    while (running.get()) {
                        val client = try {
                            socket.accept()
                        } catch (exception: SocketException) {
                            if (running.get()) {
                                onEvent("Proxy accept error: ${exception.message ?: "socket closed"}")
                            }
                            break
                        }
                        executor.execute {
                            handleClient(client, onEvent)
                        }
                    }
                }
            } catch (exception: Exception) {
                onEvent("Proxy capture failed: ${exception.message ?: "unknown error"}")
                BridgeLogStore.error("Proxy capture failed: ${exception.stackTraceToString()}")
            } finally {
                serverSocket = null
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        executor.shutdownNow()
    }

    private fun handleClient(client: Socket, onEvent: (String) -> Unit) {
        client.use { incoming ->
            val input = BufferedInputStream(incoming.getInputStream())
            val output = BufferedOutputStream(incoming.getOutputStream())
            try {
                val requestLine = input.readLineUtf8() ?: return
                if (requestLine.isBlank()) return
                val requestParts = requestLine.split(' ', limit = 3)
                if (requestParts.size < 3) {
                    return
                }
                val method = requestParts[0]
                val target = requestParts[1]
                val version = requestParts[2]
                val headers = readHeaders(input)
                val headerMap = headers.associate { header ->
                    val parts = header.split(':', limit = 2)
                    parts.first().trim().lowercase() to parts.getOrNull(1).orEmpty().trim()
                }

                val hostHeader = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                    ?.substringAfter(':')
                    ?.trim()
                    .orEmpty()
                val destination = resolveDestination(method, target, hostHeader)
                if (destination == null) {
                    onEvent("Proxy dropped $method $target (no destination)")
                    return
                }
                onEvent("Proxy $method ${destination.logLabel}")
                BridgeLogStore.info("Proxy $method ${destination.logLabel}")

                if (method.equals("CONNECT", ignoreCase = true)) {
                    forwardConnect(destination.host, destination.port, input, output)
                } else {
                    forwardHttp(method, target, version, headers, headerMap, destination.host, destination.port, input, output)
                }
            } catch (exception: Exception) {
                BridgeLogStore.error("Proxy client failed: ${exception.stackTraceToString()}")
            }
        }
    }

    private fun forwardConnect(
        host: String,
        port: Int,
        clientInput: BufferedInputStream,
        clientOutput: BufferedOutputStream,
    ) {
        Socket(host, port).use { upstream ->
            clientOutput.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: OpenMonitorBridge\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            clientOutput.flush()
            relayBidirectional(clientInput, clientOutput, upstream)
        }
    }

    private fun forwardHttp(
        method: String,
        target: String,
        version: String,
        headers: List<String>,
        headerMap: Map<String, String>,
        host: String,
        port: Int,
        clientInput: BufferedInputStream,
        clientOutput: BufferedOutputStream,
    ) {
        Socket(host, port).use { upstream ->
            val upstreamInput = BufferedInputStream(upstream.getInputStream())
            val upstreamOutput = BufferedOutputStream(upstream.getOutputStream())

            val path = normalizeHttpTarget(target)
            upstreamOutput.write("$method $path $version\r\n".toByteArray(StandardCharsets.UTF_8))
            var connectionHeaderWritten = false
            var hostHeaderWritten = false
            headers.forEach { header ->
                val lower = header.substringBefore(':').trim().lowercase()
                when (lower) {
                    "proxy-connection" -> return@forEach
                    "connection" -> {
                        upstreamOutput.write("Connection: close\r\n".toByteArray(StandardCharsets.UTF_8))
                        connectionHeaderWritten = true
                    }
                    "host" -> {
                        upstreamOutput.write("Host: $host\r\n".toByteArray(StandardCharsets.UTF_8))
                        hostHeaderWritten = true
                    }
                    else -> {
                        upstreamOutput.write(header.toByteArray(StandardCharsets.UTF_8))
                        upstreamOutput.write("\r\n".toByteArray(StandardCharsets.UTF_8))
                    }
                }
            }
            if (!hostHeaderWritten) {
                upstreamOutput.write("Host: $host\r\n".toByteArray(StandardCharsets.UTF_8))
            }
            if (!connectionHeaderWritten) {
                upstreamOutput.write("Connection: close\r\n".toByteArray(StandardCharsets.UTF_8))
            }
            upstreamOutput.write("\r\n".toByteArray(StandardCharsets.UTF_8))
            upstreamOutput.flush()

            val contentLength = headerMap["content-length"]?.toLongOrNull() ?: 0L
            if (contentLength > 0L) {
                val requestToUpstream = executor.submit {
                    copyExactly(clientInput, upstreamOutput, contentLength)
                    upstreamOutput.flush()
                    try {
                        upstream.shutdownOutput()
                    } catch (_: Exception) {
                    }
                }
                requestToUpstream.get()
            } else {
                try {
                    upstream.shutdownOutput()
                } catch (_: Exception) {
                }
            }
            val responseToClient = executor.submit {
                upstreamInput.copyTo(clientOutput)
                clientOutput.flush()
            }
            responseToClient.get()
        }
    }

    private fun relayBidirectional(
        clientInput: BufferedInputStream,
        clientOutput: BufferedOutputStream,
        upstream: Socket,
    ) {
        val upstreamInput = BufferedInputStream(upstream.getInputStream())
        val upstreamOutput = BufferedOutputStream(upstream.getOutputStream())
        val requestToUpstream = executor.submit {
            clientInput.copyTo(upstreamOutput)
            upstreamOutput.flush()
            try {
                upstream.shutdownOutput()
            } catch (_: Exception) {
            }
        }
        val responseToClient = executor.submit {
            upstreamInput.copyTo(clientOutput)
            clientOutput.flush()
        }
        requestToUpstream.get()
        responseToClient.get()
    }

    private fun readHeaders(input: BufferedInputStream): List<String> {
        val headers = mutableListOf<String>()
        while (true) {
            val line = input.readLineUtf8() ?: break
            if (line.isEmpty()) break
            headers += line
        }
        return headers
    }

    private fun resolveDestination(method: String, target: String, hostHeader: String): ProxyDestination? {
        return when {
            method.equals("CONNECT", ignoreCase = true) -> {
                val hostPort = target.split(':', limit = 2)
                val host = hostPort.firstOrNull()?.trim().orEmpty()
                val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
                if (host.isBlank()) null else ProxyDestination(host, port, "$host:$port")
            }
            target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true) -> {
                val url = runCatching { URL(target) }.getOrNull() ?: return null
                val host = url.host.orEmpty()
                val port = if (url.port > 0) url.port else if (url.protocol.equals("https", true)) 443 else 80
                if (host.isBlank()) null else ProxyDestination(host, port, "${url.protocol}://${url.host}:${port}${url.file}")
            }
            hostHeader.isNotBlank() -> {
                val hostPort = hostHeader.split(':', limit = 2)
                val host = hostPort.first().trim()
                val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 80
                ProxyDestination(host, port, "$host:$port")
            }
            else -> null
        }
    }

    private fun normalizeHttpTarget(target: String): String {
        return when {
            target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true) -> {
                runCatching {
                    val uri = URI(target)
                    val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
                    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
                    path + query
                }.getOrDefault(target)
            }
            target.isBlank() -> "/"
            else -> target
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

    private fun copyExactly(input: BufferedInputStream, output: BufferedOutputStream, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
    }

    private data class ProxyDestination(
        val host: String,
        val port: Int,
        val logLabel: String,
    )
}
