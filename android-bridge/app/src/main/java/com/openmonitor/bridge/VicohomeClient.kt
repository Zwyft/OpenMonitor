package com.openmonitor.bridge

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

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
                onProgress("Logging into Vicohome (${region.label})")
                val token = login(region)
                onProgress("Loading Vicohome devices (${region.label})")
                val devices = listDevices(token, region)
                onProgress("Loading Vicohome events (${region.label})")
                val events = listRecentEvents(token, region)
                return VicohomeSyncResult(
                    devices = devices,
                    events = events,
                    message = "Loaded ${devices.size} device(s) and ${events.size} event(s) from ${region.label}",
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

    private fun login(region: VicohomeRegion): String {
        val payload = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("loginType", 0)
            .put("countryNo", region.countryNo)
            .put("language", "en")

        val response = postJson(region, "/account/login", payload)
        val responseObject = JSONObject(response)
        val resultCode = responseObject.optInt("result", -1)
        if (resultCode != 0) {
            val message = responseObject.optString("msg", "unknown error")
            throw IllegalStateException("Login failed (${region.label}): $message")
        }

        return responseObject
            .optJSONObject("data")
            ?.optJSONObject("token")
            ?.optString("token")
            .orEmpty()
            .also { token ->
                require(token.isNotBlank()) { "Login failed: token missing" }
            }
    }

    private fun listDevices(token: String, region: VicohomeRegion): List<VicohomeDevice> {
        val payload = JSONObject()
            .put("language", "en")
            .put("countryNo", region.countryNo)

        val response = postJson(
            region,
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
    }

    private fun listRecentEvents(token: String, region: VicohomeRegion): List<VicohomeEvent> {
        val end = System.currentTimeMillis() / 1000L
        val start = end - 24 * 60 * 60
        val payload = JSONObject()
            .put("startTimestamp", start.toString())
            .put("endTimestamp", end.toString())
            .put("language", "en")
            .put("countryNo", region.countryNo)

        val response = postJson(
            region,
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
    }

    private fun postJson(region: VicohomeRegion, path: String, payload: JSONObject, token: String? = null): String {
        val connection = (URL(region.apiBase.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 7000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OpenMonitorBridge/1.0 (${region.label})")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", token)
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
