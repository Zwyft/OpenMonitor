package com.openmonitor.bridge

data class ProxyCaptureState(
    val running: Boolean = false,
    val port: Int = BridgeConfig.PROXY_PORT,
    val message: String = "Proxy capture stopped",
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

object ProxyCaptureStateStore {
    private val lock = Any()
    @Volatile
    private var state = ProxyCaptureState()

    fun snapshot(): ProxyCaptureState = state

    fun update(transform: (ProxyCaptureState) -> ProxyCaptureState) {
        synchronized(lock) {
            state = transform(state.copy(updatedAtMillis = System.currentTimeMillis()))
        }
    }
}
