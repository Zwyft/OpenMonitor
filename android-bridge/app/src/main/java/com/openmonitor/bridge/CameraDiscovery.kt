package com.openmonitor.bridge

data class CameraDiscovery(
    val label: String,
    val streamUrl: String,
    val source: String,
    val details: String = "",
    val needsAuth: Boolean = false,
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    fun displayLine(): String {
        return buildString {
            append(label)
            append(" • ")
            append(source)
            if (details.isNotBlank()) {
                append(" • ")
                append(details)
            }
            if (needsAuth) {
                append(" • auth required")
            }
            append("\n")
            append(streamUrl)
        }
    }
}

object CameraDiscoveryStore {
    private val lock = Any()
    @Volatile
    private var discoveries = emptyList<CameraDiscovery>()

    fun snapshot(): List<CameraDiscovery> = discoveries

    fun update(items: List<CameraDiscovery>) {
        synchronized(lock) {
            discoveries = items
        }
    }
}
