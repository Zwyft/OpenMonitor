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
