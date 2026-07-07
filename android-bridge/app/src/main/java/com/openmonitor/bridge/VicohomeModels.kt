package com.openmonitor.bridge

data class VicohomeDevice(
    val serialNumber: String,
    val modelNo: String,
    val deviceName: String,
    val networkName: String,
    val ip: String,
    val batteryLevel: Int,
    val locationName: String,
    val signalStrength: Int,
    val wifiChannel: Int,
    val isCharging: Int,
    val chargingMode: Int,
    val macAddress: String,
)

data class VicohomeEvent(
    val traceId: String,
    val timestamp: String,
    val deviceName: String,
    val serialNumber: String,
    val adminName: String,
    val period: String,
    val birdName: String,
    val birdLatin: String,
    val birdConfidence: Double,
    val keyShotUrl: String,
    val imageUrl: String,
    val videoUrl: String,
)

data class VicohomeSession(
    val email: String,
    val accountAuthToken: String,
    val xmToken: String,
    val region: VicohomeRegion,
    val privacyConsentUpdated: Boolean = false,
    val serviceCatalogEntries: List<VicohomeServiceCatalog> = emptyList(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

data class VicohomeAccountLogin(
    val accountInfo: VicohomeAccountInfo,
    val authToken: String,
    val xmTokenHint: String = "",
    val pwd: String,
)

data class VicohomeAccountInfo(
    val account: String,
    val accountId: Long,
    val nickname: String,
)

object VicohomeSessionStore {
    private val lock = Any()
    @Volatile
    private var session: VicohomeSession? = null

    fun snapshot(): VicohomeSession? = session

    fun update(value: VicohomeSession?) {
        synchronized(lock) {
            session = value
        }
    }
}

enum class VicohomeRegionChoice(
    val displayName: String,
) {
    AUTO("Auto (US then EU)"),
    US("US"),
    EU("EU");
}

data class VicohomeRegion(
    val label: String,
    val shortName: String,
    val apiBase: String,
    val apiBaseCandidates: List<String>,
    val authBaseCandidates: List<String>,
    val consentBaseCandidates: List<String>,
    val webrtcApiBase: String,
    val webrtcApiBaseCandidates: List<String>,
    val countryNo: String,
    val loginCountryCode: String,
    val userVisitServerCandidates: List<String>,
    val serverCode: String? = null,
)

object VicohomeRegionCatalog {
    val us = VicohomeRegion(
        label = "US",
        shortName = "US",
        apiBase = "https://ipc-bu-us-gw.baseussecurity.com",
        apiBaseCandidates = listOf(
            "https://ipc-bu-us-gw.baseussecurity.com",
            "https://api-us.vicohome.io",
        ),
        authBaseCandidates = listOf(
            "https://baseus-us-auth-gw.baseussecurity.com",
        ),
        consentBaseCandidates = listOf(
            "https://baseus-us-auth-gw.baseussecurity.com",
            "https://ipc-bu-us-gw.baseussecurity.com",
            "https://api-us.vicohome.io",
        ),
        webrtcApiBase = "https://api-us.vicoo.tech",
        webrtcApiBaseCandidates = listOf(
            "https://api-us.vicoo.tech",
            "https://api-us.vicohome.io",
        ),
        countryNo = "US",
        loginCountryCode = "1",
        userVisitServerCandidates = listOf("US", "us", "baseus-us", "baseus_us"),
    )

    val eu = VicohomeRegion(
        label = "EU",
        shortName = "EU",
        apiBase = "https://ipc-bu-eu-gw.baseussecurity.com",
        apiBaseCandidates = listOf(
            "https://ipc-bu-eu-gw.baseussecurity.com",
            "https://api-eu.vicohome.io",
        ),
        authBaseCandidates = listOf(
            "https://baseus-eu-auth-gw.baseussecurity.com",
        ),
        consentBaseCandidates = listOf(
            "https://baseus-eu-auth-gw.baseussecurity.com",
            "https://ipc-bu-eu-gw.baseussecurity.com",
            "https://api-eu.vicohome.io",
        ),
        webrtcApiBase = "https://api-eu.vicoo.tech",
        webrtcApiBaseCandidates = listOf(
            "https://api-eu.vicoo.tech",
            "https://api-eu.vicohome.io",
        ),
        countryNo = "EU",
        loginCountryCode = "44",
        userVisitServerCandidates = listOf("EU", "eu", "baseus-eu", "baseus_eu"),
    )

    fun choicesFor(choice: VicohomeRegionChoice): List<VicohomeRegion> {
        return when (choice) {
            VicohomeRegionChoice.AUTO -> listOf(us, eu)
            VicohomeRegionChoice.US -> listOf(us)
            VicohomeRegionChoice.EU -> listOf(eu)
        }
    }
}

data class VicohomeSyncResult(
    val devices: List<VicohomeDevice>,
    val events: List<VicohomeEvent>,
    val message: String,
    val session: VicohomeSession? = null,
)

data class VicohomeServiceCatalog(
    val label: String,
    val value: String,
    val bsServer: String,
    val oauthServer: String,
    val globalServer: String,
)

data class VicohomeIceServer(
    val url: String,
    val username: String,
    val credential: String,
    val ipAddress: String,
)

data class VicohomeLiveTicket(
    val traceId: String,
    val groupId: String,
    val role: String,
    val id: String,
    val iceServer: List<VicohomeIceServer>,
    val signalServer: String,
    val signalServerIpAddress: String,
    val sign: String,
    val signalPingInterval: Int,
    val maxAllocationLimit: Int,
    val appStopLiveTimeout: Int,
    val deviceSleepTimeout: Int,
    val time: Long,
    val expirationTime: Long,
    val websocketPath: String,
    val accessToken: String,
    val realCxSerialNumber: String?,
    val countryNo: String?,
)

data class ThingRtcProbeAttempt(
    val apiName: String,
    val baseUrl: String,
    val requestUrl: String,
    val requestMethod: String,
    val requestEnvelope: Map<String, String>,
    val requestHeaders: Map<String, String>,
    val responseCode: Int,
    val responseMessage: String,
    val responseBody: String,
    val parsedSummary: String,
    val parsedFields: Map<String, String>,
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    fun summaryLine(): String {
        return buildString {
            append(apiName)
            append(" @ ")
            append(baseUrl)
            append(" • ")
            append(requestMethod)
            append(" • HTTP ")
            append(responseCode)
            if (responseMessage.isNotBlank()) {
                append(" ")
                append(responseMessage)
            }
            if (parsedSummary.isNotBlank()) {
                append(" • ")
                append(parsedSummary)
            }
        }
    }
}

data class ThingRtcProbeResult(
    val targetSerialNumber: String,
    val targetIp: String,
    val targetName: String,
    val region: VicohomeRegion,
    val tokenSource: String,
    val attempts: List<ThingRtcProbeAttempt>,
    val message: String,
    val bestParsedSummary: String,
    val bestParsedFields: Map<String, String>,
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    fun summaryLine(): String {
        return buildString {
            append("Thing RTC probe")
            append(" • ")
            append(region.label)
            append(" • ")
            append(targetName.ifBlank { targetSerialNumber.ifBlank { targetIp.ifBlank { "unknown target" } } })
            if (bestParsedSummary.isNotBlank()) {
                append(" • ")
                append(bestParsedSummary)
            } else if (message.isNotBlank()) {
                append(" • ")
                append(message)
            }
        }
    }

    fun previewText(maxAttempts: Int = 3): String {
        return buildString {
            appendLine(summaryLine())
            appendLine("Target serial: ${targetSerialNumber.ifBlank { "—" }}")
            appendLine("Target IP: ${targetIp.ifBlank { "—" }}")
            appendLine("Region: ${region.label}")
            appendLine("Token source: ${tokenSource.ifBlank { "—" }}")
            if (bestParsedFields.isNotEmpty()) {
                appendLine("Parsed fields:")
                bestParsedFields.forEach { (key, value) ->
                    appendLine("  $key = $value")
                }
            }
            if (attempts.isNotEmpty()) {
                appendLine("Attempts:")
                attempts.take(maxAttempts).forEachIndexed { index, attempt ->
                    appendLine("  ${index + 1}. ${attempt.summaryLine()}")
                }
                if (attempts.size > maxAttempts) {
                    appendLine("  … +${attempts.size - maxAttempts} more")
                }
            }
            if (message.isNotBlank()) {
                appendLine("Message: $message")
            }
        }.trimEnd()
    }

    fun toText(): String {
        return buildString {
            appendLine(summaryLine())
            appendLine("Updated: $updatedAtMillis")
            appendLine("Target serial: ${targetSerialNumber.ifBlank { "—" }}")
            appendLine("Target IP: ${targetIp.ifBlank { "—" }}")
            appendLine("Target name: ${targetName.ifBlank { "—" }}")
            appendLine("Region: ${region.label}")
            appendLine("Token source: ${tokenSource.ifBlank { "—" }}")
            appendLine("Message: ${message.ifBlank { "—" }}")
            if (bestParsedFields.isNotEmpty()) {
                appendLine("Best parsed fields:")
                bestParsedFields.forEach { (key, value) ->
                    appendLine("  $key = $value")
                }
            }
            if (attempts.isNotEmpty()) {
                appendLine("Attempts:")
                attempts.forEachIndexed { index, attempt ->
                    appendLine("---- Attempt ${index + 1} ----")
                    appendLine(attempt.summaryLine())
                    appendLine("API: ${attempt.apiName}")
                    appendLine("Request URL: ${attempt.requestUrl}")
                    appendLine("Request method: ${attempt.requestMethod}")
                    appendLine("Request envelope:")
                    attempt.requestEnvelope.forEach { (key, value) ->
                        appendLine("  $key = $value")
                    }
                    if (attempt.requestHeaders.isNotEmpty()) {
                        appendLine("Request headers:")
                        attempt.requestHeaders.forEach { (key, value) ->
                            appendLine("  $key = $value")
                        }
                    }
                    appendLine("Response code: ${attempt.responseCode}")
                    appendLine("Response message: ${attempt.responseMessage}")
                    if (attempt.parsedFields.isNotEmpty()) {
                        appendLine("Parsed fields:")
                        attempt.parsedFields.forEach { (key, value) ->
                            appendLine("  $key = $value")
                        }
                    }
                    appendLine("Response body:")
                    appendLine(attempt.responseBody.ifBlank { "—" })
                }
            }
        }.trimEnd()
    }
}

object ThingRtcProbeStore {
    private val lock = Any()
    @Volatile
    private var result: ThingRtcProbeResult? = null

    fun snapshot(): ThingRtcProbeResult? = result

    fun summary(): String {
        return snapshot()?.summaryLine() ?: "No Thing RTC probe yet."
    }

    fun exportText(): String {
        return snapshot()?.toText() ?: "No Thing RTC probe yet."
    }

    fun update(value: ThingRtcProbeResult?) {
        synchronized(lock) {
            result = value
        }
    }
}

object VicohomeDataStore {
    private val lock = Any()
    @Volatile
    private var devices = emptyList<VicohomeDevice>()
    @Volatile
    private var events = emptyList<VicohomeEvent>()
    @Volatile
    private var message = ""
    @Volatile
    private var updatedAtMillis = System.currentTimeMillis()

    fun snapshotDevices(): List<VicohomeDevice> = devices
    fun snapshotEvents(): List<VicohomeEvent> = events
    fun snapshotMessage(): String = message
    fun snapshotUpdatedAtMillis(): Long = updatedAtMillis

    fun update(result: VicohomeSyncResult) {
        synchronized(lock) {
            devices = result.devices
            events = result.events
            message = result.message
            updatedAtMillis = System.currentTimeMillis()
        }
    }
}
