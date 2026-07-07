package com.openmonitor.bridge

object VicohomeSyncCoordinator {
    fun sync(
        email: String,
        password: String,
        regionChoice: VicohomeRegionChoice = VicohomeRegionChoice.AUTO,
        onProgress: (String) -> Unit = {},
    ): VicohomeSyncResult {
        val client = VicohomeClient(email, password, regionChoice)
        val result = client.syncRecentData(onProgress)
        VicohomeDataStore.update(result)
        result.session?.let { VicohomeSessionStore.update(it) }
        return result
    }
}
