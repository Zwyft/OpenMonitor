package com.openmonitor.bridge

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import android.os.Build

class VicohomeClient(
    private val email: String,
    private val password: String,
    private val regionChoice: VicohomeRegionChoice = VicohomeRegionChoice.AUTO,
) {
    fun syncRecentData(onProgress: (String) -> Unit): VicohomeSyncResult {
        var lastFailure: Exception? = null
        val regions = VicohomeRegionCatalog.choicesFor(regionChoice)
        for ((index, region) in regions.withIndex()) {
            try {
                onProgress("Logging into Baseus cloud (${region.label})")
                val token = login(region, onProgress)
                onProgress("Loading Baseus cloud devices (${region.label})")
                val devices = listDevices(token, region, onProgress)
                onProgress("Loading Baseus cloud events (${region.label})")
                val events = listRecentEvents(token, region, onProgress)
                return VicohomeSyncResult(
                    devices = devices,
                    events = events,
                    message = "Loaded ${devices.size} device(s) and ${events.size} event(s) from Baseus ${region.label}",
                    session = VicohomeSession(
                        email = email,
                        token = token,
                        region = region,
                    ),
                )
            } catch (exception: Exception) {
                lastFailure = exception
                val attemptLabel = "${region.label} attempt ${index + 1}/${regions.size}"
                onProgress("$attemptLabel failed: ${exception.message ?: "unknown error"}")
                if (regionChoice != VicohomeRegionChoice.AUTO || index == regions.lastIndex) {
                    throw exception
                }
            }
        }
        throw lastFailure ?: IllegalStateException("Vicohome sync failed")
    }

    fun fetchLiveTicket(
        session: VicohomeSession,
        serialNumber: String,
        onProgress: (String) -> Unit = {},
    ): VicohomeLiveTicket {
        val payload = JSONObject()
            .put("serialNumber", serialNumber)
            .put("countryNo", session.region.countryNo)
            .put("requestId", generateRequestID())
            .put("language", "en")
            .put("supportUnlimitedWebsocket", true)
            .put("list", org.json.JSONArray())
            .put(
                "app",
                JSONObject()
                    .put("versionName", "3.50.0(2f68e2)")
                    .put("bundle", "addx.ai.vicoo")
                    .put("timeZone", java.util.TimeZone.getDefault().id)
                    .put("appName", "VicoHome")
                    .put("tenantId", "vicoo")
                    .put("env", "prod-k8s")
                    .put("version", 14148)
                    .put("appType", "iOS"),
            )

        var lastFailure: Exception? = null
        for (baseUrl in session.region.webrtcApiBaseCandidates) {
            try {
                onProgress("Trying Baseus live host $baseUrl")
                val response = postJson(
                    baseUrl,
                    "/device/getWebrtcTicket",
                    payload,
                    session.token,
                    bearerToken = true,
                )
                val responseObject = JSONObject(response)
                val resultCode = responseObject.optInt("result", -1)
                if (resultCode != 0) {
                    val message = responseObject.optString("msg", "unknown error")
                    throw IllegalStateException("Live ticket request failed (${session.region.label} @ $baseUrl): $message")
                }
                val data = responseObject.optJSONObject("data") ?: throw IllegalStateException("Live ticket response missing data")
                val ticket = parseLiveTicket(data)
                return ticket.copy(
                    accessToken = ticket.accessToken.ifBlank { session.token },
                )
            } catch (exception: Exception) {
                lastFailure = exception
            }
        }
        throw lastFailure ?: IllegalStateException("Live ticket request failed (${session.region.label})")
    }

    private fun generateRequestID(): String {
        return "uuid:${UUID.randomUUID()}"
    }

    private fun login(region: VicohomeRegion, onProgress: (String) -> Unit = {}): String {
        val basePayload = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("loginType", 0)
            .put("countryNo", region.countryNo)
            .put("language", "en")

        val appMetadata = JSONObject()
            .put("versionName", "1.9.0")
            .put("bundle", "com.baseus.security.ipc")
            .put("timeZone", java.util.TimeZone.getDefault().id)
            .put("appName", "baseus Security")
            .put("tenantId", "baseus")
            .put("env", "prod")
            .put("version", 50)
            .put("appType", "Android")

        val deviceMetadata = JSONObject()
            .put("appPackageName", "com.baseus.security.ipc")
            .put("packageName", "com.baseus.security.ipc")
            .put("brand", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("board", Build.BOARD)
            .put("hardware", Build.HARDWARE)
            .put("osVersion", Build.VERSION.RELEASE ?: "")
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("display", Build.DISPLAY)
            .put("product", Build.PRODUCT)

        val payloadVariants = listOf(
            basePayload,
            JSONObject(basePayload.toString()).put("app", appMetadata),
            JSONObject(basePayload.toString()).put("device", deviceMetadata),
            JSONObject(basePayload.toString()).put("app", appMetadata).put("device", deviceMetadata),
        )

        var lastFailure: Exception? = null
        for (baseUrl in region.authBaseCandidates) {
            for ((variantIndex, payload) in payloadVariants.withIndex()) {
                try {
                    onProgress("Trying Baseus auth host $baseUrl (variant ${variantIndex + 1}/${payloadVariants.size})")
                    val response = postJson(baseUrl, "/account/login", payload)
                    val responseObject = JSONObject(response)
                    val resultCode = responseObject.optInt("result", -1)
                    if (resultCode != 0) {
                        val message = responseObject.optString("msg", "unknown error")
                        throw IllegalStateException("Login failed (${region.label} @ $baseUrl, variant ${variantIndex + 1}): $message")
                    }

                    return responseObject
                        .optJSONObject("data")
                        ?.optJSONObject("token")
                        ?.optString("token")
                        .orEmpty()
                        .also { token ->
                            require(token.isNotBlank()) { "Login failed: token missing" }
                        }
                } catch (exception: Exception) {
                    lastFailure = exception
                }
            }
        }
        throw lastFailure ?: IllegalStateException("Login failed (${region.label})")
    }

    private fun listDevices(
        token: String,
        region: VicohomeRegion,
        onProgress: (String) -> Unit = {},
    ): List<VicohomeDevice> {
        val payload = JSONObject()
            .put("language", "en")
            .put("countryNo", region.countryNo)

        var lastFailure: Exception? = null
        for (baseUrl in region.apiBaseCandidates) {
            try {
                onProgress("Trying Baseus device host $baseUrl")
                val response = postJson(
                    baseUrl,
                    "/device/listuserdevices",
                    payload,
                    token,
                )
                val responseObject = JSONObject(response)
                if (responseObject.optInt("code", 0) != 0 && responseObject.optInt("result", 0) != 0) {
                    return emptyList()
                }

                val list = responseObject
                    .optJSONObject("data")
                    ?.optJSONArray("list")
                    ?: return emptyList()

                val devices = mutableListOf<VicohomeDevice>()
                for (index in 0 until list.length()) {
                    val device = list.optJSONObject(index) ?: continue
                    devices += VicohomeDevice(
                        serialNumber = device.optString("serialNumber"),
                        modelNo = device.optString("modelNo"),
                        deviceName = device.optString("deviceName"),
                        networkName = device.optString("networkName"),
                        ip = device.optString("ip"),
                        batteryLevel = device.optInt("batteryLevel"),
                        locationName = device.optString("locationName"),
                        signalStrength = device.optInt("signalStrength"),
                        wifiChannel = device.optInt("wifiChannel"),
                        isCharging = device.optInt("isCharging"),
                        chargingMode = device.optInt("chargingMode"),
                        macAddress = device.optString("macAddress"),
                    )
                }
                return devices
            } catch (exception: Exception) {
                lastFailure = exception
            }
        }
        throw lastFailure ?: IllegalStateException("Device list request failed (${region.label})")
    }

    private fun listRecentEvents(
        token: String,
        region: VicohomeRegion,
        onProgress: (String) -> Unit = {},
    ): List<VicohomeEvent> {
        val end = System.currentTimeMillis() / 1000L
        val start = end - 24 * 60 * 60
        val payload = JSONObject()
            .put("startTimestamp", start.toString())
            .put("endTimestamp", end.toString())
            .put("language", "en")
            .put("countryNo", region.countryNo)

        var lastFailure: Exception? = null
        for (baseUrl in region.apiBaseCandidates) {
            try {
                onProgress("Trying Baseus event host $baseUrl")
                val response = postJson(
                    baseUrl,
                    "/library/newselectlibrary",
                    payload,
                    token,
                )
                val responseObject = JSONObject(response)
                if (responseObject.optInt("code", 0) != 0 && responseObject.optInt("result", 0) != 0) {
                    return emptyList()
                }

                val list = responseObject
                    .optJSONObject("data")
                    ?.optJSONArray("list")
                    ?: return emptyList()

                val events = mutableListOf<VicohomeEvent>()
                for (index in 0 until list.length()) {
                    val event = list.optJSONObject(index) ?: continue
                    events += VicohomeEvent(
                        traceId = event.optString("traceId"),
                        timestamp = normalizeTimestamp(event.opt("timestamp")),
                        deviceName = event.optString("deviceName"),
                        serialNumber = event.optString("serialNumber"),
                        adminName = event.optString("adminName"),
                        period = normalizePeriod(event.opt("period")),
                        birdName = event.optString("birdName").ifBlank { "Unidentified" },
                        birdLatin = event.optString("birdLatin"),
                        birdConfidence = event.optDouble("birdConfidence"),
                        keyShotUrl = event.optString("keyShotUrl"),
                        imageUrl = event.optString("imageUrl"),
                        videoUrl = event.optString("videoUrl"),
                    )
                }
                return events
            } catch (exception: Exception) {
                lastFailure = exception
            }
        }
        throw lastFailure ?: IllegalStateException("Event list request failed (${region.label})")
    }

    private fun postJson(
        baseUrl: String,
        path: String,
        payload: JSONObject,
        token: String? = null,
        bearerToken: Boolean = false,
    ): String {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 7000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OpenMonitorBridge/1.0")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", if (bearerToken) "Bearer $token" else token)
            }
        }

        return try {
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val responseStream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: throw IllegalStateException("Vicohome request failed with HTTP ${connection.responseCode}")
            readAll(responseStream)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLiveTicket(data: JSONObject): VicohomeLiveTicket {
        val iceServers = mutableListOf<VicohomeIceServer>()
        val servers = data.optJSONArray("iceServer")
        if (servers != null) {
            for (index in 0 until servers.length()) {
                val server = servers.optJSONObject(index) ?: continue
                iceServers += VicohomeIceServer(
                    url = server.optString("url"),
                    username = server.optString("username"),
                    credential = server.optString("credential"),
                    ipAddress = server.optString("ipAddress"),
                )
            }
        }
        return VicohomeLiveTicket(
            traceId = data.optString("traceId"),
            groupId = data.optString("groupId"),
            role = data.optString("role"),
            id = data.optString("id"),
            iceServer = iceServers,
            signalServer = data.optString("signalServer"),
            signalServerIpAddress = data.optString("signalServerIpAddress"),
            sign = data.optString("sign"),
            signalPingInterval = data.optInt("signalPingInterval"),
            maxAllocationLimit = data.optInt("maxAllocationLimit"),
            appStopLiveTimeout = data.optInt("appStopLiveTimeout"),
            deviceSleepTimeout = data.optInt("deviceSleepTimeout"),
            time = data.optLong("time"),
            expirationTime = data.optLong("expirationTime"),
            websocketPath = data.optString("websocketPath"),
            accessToken = data.optString("accessToken"),
            realCxSerialNumber = data.optString("realCxSerialNumber").takeIf { it.isNotBlank() },
            countryNo = data.optString("countryNo").takeIf { it.isNotBlank() },
        )
    }

    private fun readAll(stream: java.io.InputStream): String {
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            buildString {
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line)
                }
            }
        }
    }

    private fun normalizeTimestamp(value: Any?): String {
        return when (value) {
            is Number -> value.toLong().let { epochSeconds ->
                java.time.Instant.ofEpochSecond(epochSeconds).toString()
            }
            is String -> value
            else -> ""
        }
    }

    private fun normalizePeriod(value: Any?): String {
        return when (value) {
            is Number -> String.format("%.2fs", value.toDouble())
            is String -> value
            else -> ""
        }
    }
}
