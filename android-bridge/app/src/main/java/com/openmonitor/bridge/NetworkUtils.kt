package com.openmonitor.bridge

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun localIpv4Addresses(): List<String> {
        val discovered = linkedSetOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val interfaceAddresses = networkInterface.inetAddresses
            for (address in interfaceAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    val hostAddress = address.hostAddress ?: continue
                    if (!hostAddress.startsWith("169.254.")) {
                        discovered += hostAddress
                    }
                }
            }
        }
        return discovered.toList()
    }

    fun localIpv4Address(): String? {
        val addresses = localIpv4Addresses()
        return addresses.firstOrNull()
    }

    fun serverUrl(port: Int): String {
        val address = localIpv4Address() ?: "127.0.0.1"
        return "http://$address:$port"
    }
}
