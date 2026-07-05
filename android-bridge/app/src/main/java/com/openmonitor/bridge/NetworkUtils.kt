package com.openmonitor.bridge

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun localIpv4Address(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val addresses = networkInterface.inetAddresses
            for (address in addresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    val hostAddress = address.hostAddress ?: continue
                    if (!hostAddress.startsWith("169.254.")) {
                        return hostAddress
                    }
                }
            }
        }
        return null
    }

    fun serverUrl(port: Int): String {
        val address = localIpv4Address() ?: "127.0.0.1"
        return "http://$address:$port"
    }
}
