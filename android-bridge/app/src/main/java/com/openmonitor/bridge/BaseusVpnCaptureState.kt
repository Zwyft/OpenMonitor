package com.openmonitor.bridge

data class BaseusVpnCaptureState(
    val running: Boolean = false,
    val message: String = "VPN capture stopped",
    val packageName: String = "com.baseus.security.ipc",
    val targetIp: String = "192.168.4.25",
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

object BaseusVpnCaptureStateStore {
    private val lock = Any()
    @Volatile
    private var state = BaseusVpnCaptureState()

    fun snapshot(): BaseusVpnCaptureState = state

    fun update(transform: (BaseusVpnCaptureState) -> BaseusVpnCaptureState) {
        synchronized(lock) {
            state = transform(state.copy(updatedAtMillis = System.currentTimeMillis()))
        }
    }
}
