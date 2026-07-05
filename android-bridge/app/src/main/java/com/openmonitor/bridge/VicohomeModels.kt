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

enum class VicohomeRegionChoice(
    val displayName: String,
) {
    AUTO("Auto (US then EU)"),
    US("US"),
    EU("EU");
}

data class VicohomeRegion(
    val label: String,
    val apiBase: String,
    val countryNo: String,
)

object VicohomeRegionCatalog {
    val us = VicohomeRegion(
        label = "US",
        apiBase = "https://api-us.vicohome.io",
        countryNo = "US",
    )

    val eu = VicohomeRegion(
        label = "EU",
        apiBase = "https://api-eu.vicohome.io",
        countryNo = "EU",
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
