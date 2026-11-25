package com.bromide.app

import java.net.Inet4Address
import java.net.NetworkInterface

/** An IPv4 address the camera could reach the phone on. */
data class LocalAddress(val ip: String, val iface: String, val likelyHotspot: Boolean)

object Network {
    private val mobileData = listOf("rmnet", "ccmni", "pdp", "v4-", "clat", "dummy", "tun", "ppp", "lo")
    private val hotspotNames = listOf("ap", "swlan", "softap", "wlan1", "wigig")

    /** Candidate addresses, the phone's own hotspot first. */
    fun addresses(): List<LocalAddress> {
        val out = mutableListOf<LocalAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (mobileData.any { name.startsWith(it) }) continue
                for (addr in ni.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    val hotspot = hotspotNames.any { name.startsWith(it) } || ip.endsWith(".1")
                    out += LocalAddress(ip, ni.name, hotspot)
                }
            }
        } catch (_: Exception) {
        }
        return out.sortedByDescending { it.likelyHotspot }
    }
}
