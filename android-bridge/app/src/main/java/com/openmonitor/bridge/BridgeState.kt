package com.openmonitor.bridge

data class BridgeState(
    val serverUrl: String = "",
    val bridgeId: String = "",
    val rtspUrl: String = "",
    val playlistUrl: String = "",
    val status: String = "idle",
    val message: String = "Ready",
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

object BridgeStateStore {
    private val lock = Any()
    @Volatile
    private var state = BridgeState()

    fun snapshot(): BridgeState = state

    fun update(transform: (BridgeState) -> BridgeState) {
        synchronized(lock) {
            state = transform(state.copy(updatedAtMillis = System.currentTimeMillis()))
        }
    }
}
