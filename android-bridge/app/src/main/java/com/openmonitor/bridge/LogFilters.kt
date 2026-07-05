package com.openmonitor.bridge

data class LogQuery(
    val preset: LogFilterPreset = LogFilterPreset.ALL,
    val searchText: String = "",
) {
    fun matches(entry: BridgeLogEntry): Boolean {
        if (!preset.matches(entry)) return false
        val needle = searchText.trim()
        if (needle.isBlank()) return true
        val haystack = buildString {
            append(entry.level)
            append(' ')
            append(entry.message)
        }.lowercase()
        return haystack.contains(needle.lowercase())
    }
}

enum class LogFilterPreset(val displayName: String) {
    ALL("Full"),
    ERRORS("Errors / warnings"),
    VPN("VPN capture"),
    TLS_SNI("TLS SNI"),
    DNS("DNS"),
    HTTP("HTTP"),
    TCP_UDP("TCP / UDP"),
    TOKENS("Token candidates"),
    HLS("HLS / bridge"),
    BASEUS("Baseus cloud"),
    SCAN("Scan / discovery"),
    PROXY("Proxy capture");

    fun matches(entry: BridgeLogEntry): Boolean {
        val message = entry.message.lowercase()
        return when (this) {
            ALL -> true
            ERRORS -> entry.level == "ERROR" || entry.level == "WARN"
            VPN -> message.contains("vpn")
            TLS_SNI -> message.contains("tls sni")
            DNS -> message.contains("dns")
            HTTP -> message.contains("http") || message.contains("request=")
            TCP_UDP -> message.startsWith("vpn tcp ") || message.startsWith("vpn tcp6 ") || message.startsWith("vpn udp ") || message.startsWith("vpn udp6 ")
            TOKENS -> message.contains("token") || message.contains("auth") || message.contains("bearer") || message.contains("xm session")
            HLS -> message.contains("hls") || message.contains("playlist") || message.contains("segment") || message.contains("bridge")
            BASEUS -> message.contains("baseus") || message.contains("vicohome") || message.contains("cloud")
            SCAN -> message.contains("scan") || message.contains("discover") || message.contains("candidate") || message.contains("onvif") || message.contains("rtsp")
            PROXY -> message.contains("proxy")
        }
    }
}
