package com.beamdrop.app.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /** Best-effort LAN IPv4 address, preferring Wi-Fi/Ethernet over VPN or cellular. */
    fun localIpAddress(): String? {
        return runCatching {
            val candidates = mutableListOf<Pair<Int, String>>()
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { iface ->
                if (!iface.isUp || iface.isLoopback) return@forEach
                val name = iface.name.lowercase()
                val score = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("ap") || name.contains("softap") -> 2
                    name.contains("tun") || name.contains("ppp") -> 8
                    else -> 5
                }
                Collections.list(iface.inetAddresses).forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress ?: return@forEach
                        if (!host.startsWith("169.254.")) {
                            candidates += score to host
                        }
                    }
                }
            }
            candidates.minByOrNull { it.first }?.second
        }.getOrNull()
    }
}
