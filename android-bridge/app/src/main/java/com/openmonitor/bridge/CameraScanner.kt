package com.openmonitor.bridge

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class CameraScanner(
    private val username: String = "",
    private val password: String = "",
) {
    private val rtspPorts = listOf(554, 8554)
    private val commonRtspPaths = listOf(
        "/",
        "/live",
        "/live0",
        "/live1",
        "/stream",
        "/stream1",
        "/h264",
        "/onvif1",
        "/videoMain",
        "/videoSub",
        "/0",
        "/1",
        "/axis-media/media.amp",
        "/cam/realmonitor?channel=1&subtype=0",
        "/cam/realmonitor?channel=1&subtype=1",
    )

    fun scan(onProgress: (String) -> Unit): List<CameraDiscovery> {
        val discoveries = linkedMapOf<String, CameraDiscovery>()
        val localIp = NetworkUtils.localIpv4Address()
        if (localIp == null) {
            onProgress("No local IPv4 address found")
            return emptyList()
        }

        onProgress("Discovering ONVIF devices")
        discoverOnvifDevices(onProgress).forEach { device ->
            resolveOnvifStream(device, onProgress)?.let { discovery ->
                addDiscovery(discoveries, discovery)
            }
        }

        onProgress("Scanning RTSP hosts on the LAN")
        scanRtspCandidates(localIp, onProgress).forEach { discovery ->
            addDiscovery(discoveries, discovery)
        }

        return discoveries.values.toList()
    }

    private fun discoverOnvifDevices(onProgress: (String) -> Unit): List<OnvifDevice> {
        val requestId = "uuid:${UUID.randomUUID()}"
        val payload = """
            <?xml version="1.0" encoding="UTF-8"?>
            <e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                        xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                        xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
              <e:Header>
                <w:MessageID>$requestId</w:MessageID>
                <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
                <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
              </e:Header>
              <e:Body>
                <d:Probe>
                  <d:Types>dn:NetworkVideoTransmitter</d:Types>
                </d:Probe>
              </e:Body>
            </e:Envelope>
        """.trimIndent()

        val socket = DatagramSocket()
        socket.soTimeout = 1500
        val probe = DatagramPacket(
            payload.toByteArray(StandardCharsets.UTF_8),
            payload.toByteArray(StandardCharsets.UTF_8).size,
            InetSocketAddress("239.255.255.250", 3702),
        )
        socket.send(probe)

        val responses = mutableListOf<OnvifDevice>()
        val buffer = ByteArray(64 * 1024)
        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline) {
            try {
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val xml = String(response.data, 0, response.length, StandardCharsets.UTF_8)
                parseOnvifDiscovery(xml)?.let { device ->
                    responses += device
                    onProgress("Found ONVIF device at ${device.xaddr}")
                }
            } catch (_: Exception) {
                break
            }
        }
        socket.close()
        return responses
    }

    private fun parseOnvifDiscovery(xml: String): OnvifDevice? {
        val doc = parseXml(xml) ?: return null
        val xaddr = firstText(doc, "XAddrs")?.split("\\s+".toRegex())?.firstOrNull()?.trim().orEmpty()
        if (xaddr.isBlank()) return null
        val scopes = firstText(doc, "Scopes").orEmpty()
        val name = scopes.split(" ").firstOrNull { it.startsWith("onvif://") }
            ?.substringAfterLast("/")
            ?.replace('_', ' ')
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { URI(xaddr).host }.getOrNull()
            ?: "ONVIF camera"
        return OnvifDevice(name = name, xaddr = xaddr, scopes = scopes)
    }

    private fun resolveOnvifStream(device: OnvifDevice, onProgress: (String) -> Unit): CameraDiscovery? {
        val capabilitiesXml = onvifSoapPost(
            device.xaddr,
            "http://www.onvif.org/ver10/device/wsdl/GetCapabilities",
            """
                <tds:GetCapabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
                  <tds:Category>All</tds:Category>
                </tds:GetCapabilities>
            """.trimIndent(),
        ) ?: return null

        val mediaXaddr = parseMediaXaddr(capabilitiesXml)
        if (mediaXaddr.isNullOrBlank()) {
            onProgress("No ONVIF media service for ${device.name}")
            return null
        }

        val profilesXml = onvifSoapPost(
            mediaXaddr,
            "http://www.onvif.org/ver10/media/wsdl/GetProfiles",
            "<trt:GetProfiles xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"/>",
        ) ?: return null

        val profiles = parseProfiles(profilesXml)
        for (profile in profiles) {
            val streamXml = onvifSoapPost(
                mediaXaddr,
                "http://www.onvif.org/ver10/media/wsdl/GetStreamUri",
                """
                    <trt:GetStreamUri xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
                      <trt:StreamSetup>
                        <tt:Stream xmlns:tt="http://www.onvif.org/ver10/schema">RTP-Unicast</tt:Stream>
                        <tt:Transport xmlns:tt="http://www.onvif.org/ver10/schema">
                          <tt:Protocol>RTSP</tt:Protocol>
                        </tt:Transport>
                      </trt:StreamSetup>
                      <trt:ProfileToken>${profile.token}</trt:ProfileToken>
                    </trt:GetStreamUri>
                """.trimIndent(),
            ) ?: continue

            val streamUrl = parseStreamUri(streamXml) ?: continue
            val streamWithAuth = applyCredentials(streamUrl)
            return CameraDiscovery(
                label = profile.name.ifBlank { device.name },
                streamUrl = streamWithAuth,
                source = "ONVIF",
                details = device.xaddr,
                needsAuth = streamUrl != streamWithAuth,
            )
        }

        return null
    }

    private fun scanRtspCandidates(localIp: String, onProgress: (String) -> Unit): List<CameraDiscovery> {
        val prefix = localIp.substringBeforeLast(".")
        val hosts = (1..254)
            .map { "$prefix.$it" }
            .filter { it != localIp }

        val executor = Executors.newFixedThreadPool(16)
        try {
            val tasks = hosts.flatMap { host ->
                rtspPorts.map { port ->
                    Callable<CameraDiscovery?> {
                        probeHostForRtsp(host, port, onProgress)
                    }
                }
            }
            val futures = executor.invokeAll(tasks, 45, TimeUnit.SECONDS)
            return futures.mapNotNull { future ->
                try {
                    future.get()
                } catch (_: Exception) {
                    null
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probeHostForRtsp(host: String, port: Int, onProgress: (String) -> Unit): CameraDiscovery? {
        val socketAddress = InetSocketAddress(host, port)
        Socket().use { socket ->
            try {
                socket.connect(socketAddress, 350)
            } catch (_: Exception) {
                return null
            }
        }

        for (path in commonRtspPaths) {
            val candidate = buildRtspUrl(host, port, path)
            onProgress("Testing $candidate")
            val outcome = probeRtsp(candidate)
            when (outcome) {
                RtspProbeResult.Valid -> {
                    return CameraDiscovery(
                        label = "$host:$port",
                        streamUrl = candidate,
                        source = "RTSP probe",
                        details = path,
                    )
                }
                RtspProbeResult.AuthRequired -> {
                    return CameraDiscovery(
                        label = "$host:$port",
                        streamUrl = candidate,
                        source = "RTSP probe",
                        details = "$path (auth required)",
                        needsAuth = true,
                    )
                }
                RtspProbeResult.Invalid -> {
                }
            }
        }

        return null
    }

    private fun probeRtsp(uri: String): RtspProbeResult {
        val parsed = URI(uri)
        val port = if (parsed.port > 0) parsed.port else 554
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(parsed.host, port), 1000)
            socket.soTimeout = 1000
            val output = socket.getOutputStream()
            val input = BufferedInputStream(socket.getInputStream())
            val request = buildString {
                append("DESCRIBE ")
                append(uri)
                append(" RTSP/1.0\r\n")
                append("CSeq: 1\r\n")
                append("User-Agent: OpenMonitorBridge/1.0\r\n")
                append("Accept: application/sdp\r\n")
                append("\r\n")
            }
            output.write(request.toByteArray(StandardCharsets.UTF_8))
            output.flush()

            val response = readResponse(input) ?: return RtspProbeResult.Invalid
            return when {
                response.statusLine.contains("200") && response.headers["content-type"]?.contains("application/sdp", ignoreCase = true) == true -> RtspProbeResult.Valid
                response.statusLine.contains("401") -> RtspProbeResult.AuthRequired
                response.statusLine.contains("200") -> RtspProbeResult.Valid
                else -> RtspProbeResult.Invalid
            }
        } catch (_: Exception) {
            return RtspProbeResult.Invalid
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private data class RtspResponse(val statusLine: String, val headers: Map<String, String>, val body: String)

    private enum class RtspProbeResult {
        Valid,
        AuthRequired,
        Invalid,
    }

    private data class OnvifDevice(val name: String, val xaddr: String, val scopes: String)
    private data class OnvifProfile(val token: String, val name: String)

    private fun parseProfiles(xml: String): List<OnvifProfile> {
        val doc = parseXml(xml) ?: return emptyList()
        val nodes = doc.getElementsByTagNameNS("*", "Profiles")
        val profiles = mutableListOf<OnvifProfile>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index) as? Element ?: continue
            val token = node.getAttribute("token").orEmpty()
            val name = textOf(node, "Name").orEmpty()
            if (token.isNotBlank()) {
                profiles += OnvifProfile(token, name)
            }
        }
        return profiles
    }

    private fun parseMediaXaddr(xml: String): String? {
        val doc = parseXml(xml) ?: return null
        val mediaNodes = doc.getElementsByTagNameNS("*", "Media")
        for (index in 0 until mediaNodes.length) {
            val node = mediaNodes.item(index) as? Element ?: continue
            val xaddr = textOf(node, "XAddr")
            if (!xaddr.isNullOrBlank()) return xaddr.trim()
        }
        return firstText(doc, "XAddr")
    }

    private fun parseStreamUri(xml: String): String? {
        val doc = parseXml(xml) ?: return null
        return firstText(doc, "Uri")?.trim()
    }

    private fun firstText(doc: org.w3c.dom.Document, tag: String): String? {
        val nodes = doc.getElementsByTagNameNS("*", tag)
        if (nodes.length == 0) return null
        return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun textOf(node: Element, tag: String): String? {
        val children = node.getElementsByTagNameNS("*", tag)
        if (children.length == 0) return null
        return children.item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun onvifSoapPost(endpointUrl: String, soapAction: String, bodyXml: String): String? {
        val requestUrl = applyCredentials(endpointUrl)
        val connection = try {
            (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 2000
                readTimeout = 2000
                setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                setRequestProperty("SOAPAction", "\"$soapAction\"")
                setRequestProperty("User-Agent", "OpenMonitorBridge/1.0")
            }
        } catch (_: Exception) {
            return null
        }

        return try {
            connection.outputStream.use { output ->
                output.write(
                    """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                                       xmlns:tds="http://www.onvif.org/ver10/device/wsdl"
                                       xmlns:trt="http://www.onvif.org/ver10/media/wsdl"
                                       xmlns:tt="http://www.onvif.org/ver10/schema">
                          <soap:Body>
                            $bodyXml
                          </soap:Body>
                        </soap:Envelope>
                    """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                )
            }
            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: return null
            responseStream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(input: BufferedInputStream): RtspResponse? {
        val statusLine = input.readLineUtf8() ?: return null
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = input.readLineUtf8() ?: break
            if (line.isBlank()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }
        return RtspResponse(statusLine = statusLine, headers = headers, body = "")
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
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        val rawBytes = ByteArray(bytes.size)
        for (index in bytes.indices) {
            rawBytes[index] = bytes[index]
        }
        return String(rawBytes, StandardCharsets.UTF_8)
    }

    private fun parseXml(xml: String): org.w3c.dom.Document? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
        } catch (_: Exception) {
            null
        }
    }

    private fun addDiscovery(map: MutableMap<String, CameraDiscovery>, discovery: CameraDiscovery) {
        val key = discovery.streamUrl
        if (key.isNotBlank() && !map.containsKey(key)) {
            map[key] = discovery
        }
    }

    private fun buildRtspUrl(host: String, port: Int, path: String): String {
        return "rtsp://$host:$port$path"
    }

    private fun applyCredentials(url: String): String {
        if (username.isBlank()) return url
        return try {
            val uri = URI(url)
            val userInfo = if (password.isBlank()) username else "$username:$password"
            URI(
                uri.scheme,
                userInfo,
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment,
            ).toString()
        } catch (_: Exception) {
            url
        }
    }

}
