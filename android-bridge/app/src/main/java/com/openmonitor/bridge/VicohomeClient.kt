package com.openmonitor.bridge

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

private const val BASEUS_APP_VERSION_NAME = "1.0"
private const val BASEUS_APP_VERSION_CODE = "1"

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
                val events = try {
                    onProgress("Loading Baseus cloud events (${region.label})")
                    listRecentEvents(token, region, onProgress)
                } catch (exception: Exception) {
                    onProgress("Baseus event history unavailable (${region.label}): ${exception.message ?: "unknown error"}")
                    emptyList()
                }
                return VicohomeSyncResult(
                    devices = devices,
                    events = events,
                    message = if (events.isEmpty()) {
                        "Loaded ${devices.size} device(s) from Baseus ${region.label}"
                    } else {
                        "Loaded ${devices.size} device(s) and ${events.size} event(s) from Baseus ${region.label}"
                    },
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
                    session.region,
                    session.token,
                )
                val responseObject = JSONObject(response)
                val resultCode = responseObject.optInt("code", responseObject.optInt("result", -1))
                if (resultCode != 0) {
                    val message = responseObject.optString("msg", responseObject.optString("message", "unknown error"))
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
        val payload = JSONObject()
            .put("type", 0)
            .put("account", email)
            .put("password", BaseusCrypto.encryptLoginPassword(password))

        var lastFailure: Exception? = null
        for (baseUrl in region.authBaseCandidates) {
            try {
                onProgress("Trying Baseus auth host $baseUrl")
                val response = postJson(
                    baseUrl = baseUrl,
                    path = "/api/auth/account/login",
                    payload = payload,
                    region = region,
                    queryParams = mapOf("countryCode" to region.loginCountryCode),
                )
                val responseObject = JSONObject(response)
                val code = responseObject.optInt("code", responseObject.optInt("result", -1))
                if (code != 0) {
                    val message = responseObject.optString("msg", responseObject.optString("message", "unknown error"))
                    throw IllegalStateException("Login failed (${region.label} @ $baseUrl): $message")
                }
                val data = responseObject.optJSONObject("data") ?: throw IllegalStateException("Login failed (${region.label} @ $baseUrl): data missing")
                val auth = data.optString("auth").orEmpty()
                require(auth.isNotBlank()) { "Login failed (${region.label} @ $baseUrl): auth token missing" }
                return auth
            } catch (exception: Exception) {
                lastFailure = exception
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
                    region,
                    token,
                )
                val responseObject = JSONObject(response)
                if (responseObject.optInt("code", responseObject.optInt("result", 0)) != 0) {
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
                    region,
                    token,
                )
                val responseObject = JSONObject(response)
                if (responseObject.optInt("code", responseObject.optInt("result", 0)) != 0) {
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
        region: VicohomeRegion,
        token: String? = null,
        queryParams: Map<String, String> = emptyMap(),
    ): String {
        val requestBody = payload.toString()
        val connection = (URL(buildRequestUrl(baseUrl, path, queryParams)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 7000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OpenMonitorBridge/1.0")
            setRequestHeaders(this, region, token, requestBody)
            if (requestBody.isNotBlank()) {
                val timestamp = System.currentTimeMillis().toString()
                setRequestProperty("timestamp", timestamp)
                setRequestProperty("sign", buildRequestSign(requestBody, timestamp))
            }
        }

        return try {
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(StandardCharsets.UTF_8))
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

    private fun buildRequestUrl(baseUrl: String, path: String, queryParams: Map<String, String>): String {
        val builder = StringBuilder(baseUrl.trimEnd('/')).append(path)
        if (queryParams.isNotEmpty()) {
            builder.append('?')
            queryParams.entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    builder.append('&')
                }
                builder.append(entry.key)
                builder.append('=')
                builder.append(java.net.URLEncoder.encode(entry.value, StandardCharsets.UTF_8.name()))
            }
        }
        return builder.toString()
    }

    private fun setRequestHeaders(
        connection: HttpURLConnection,
        region: VicohomeRegion,
        token: String?,
        requestBody: String,
    ) {
        connection.setRequestProperty("platform", "1")
        connection.setRequestProperty("auth", token.orEmpty())
        connection.setRequestProperty("osVersion", android.os.Build.VERSION.RELEASE.orEmpty())
        connection.setRequestProperty("brand", sanitizeHeaderValue(android.os.Build.BRAND))
        connection.setRequestProperty("model", sanitizeHeaderValue(android.os.Build.MODEL))
        connection.setRequestProperty("appLang", "en")
        connection.setRequestProperty("appVersion", BASEUS_APP_VERSION_NAME)
        connection.setRequestProperty("versionCode", BASEUS_APP_VERSION_CODE)
        connection.setRequestProperty("channel", "")
        connection.setRequestProperty("region", region.shortName)
        connection.setRequestProperty("appType", "40")
        if (requestBody.isNotBlank()) {
            connection.setRequestProperty("Content-Length", requestBody.toByteArray(StandardCharsets.UTF_8).size.toString())
        }
    }

    private fun buildRequestSign(requestBody: String, timestamp: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(requestBody.toByteArray(StandardCharsets.UTF_8))
        val md5Hex = md5.joinToString(separator = "") { byte ->
            byte.toInt().and(0xff).toString(16).padStart(2, '0')
        }.uppercase(Locale.US)
        val sha1 = MessageDigest.getInstance("SHA1").digest("GSiPpcmX${md5Hex}${timestamp}".toByteArray(StandardCharsets.UTF_8))
        return "ipc#CElYvAkK#" + sha1.joinToString(separator = "") { byte ->
            byte.toInt().and(0xff).toString(16).padStart(2, '0')
        }
    }

    private fun sanitizeHeaderValue(value: String?): String {
        if (value.isNullOrBlank()) {
            return ""
        }
        val builder = StringBuilder(value.length)
        for (character in value) {
            if (character.code <= 31 && character != '\t' || character.code >= 127) {
                builder.append("unknow")
            } else {
                builder.append(character)
            }
        }
        return builder.toString()
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
