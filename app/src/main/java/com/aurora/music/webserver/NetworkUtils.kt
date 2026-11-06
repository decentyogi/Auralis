package com.aurora.music.webserver

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Retrieves the IPv4 address of the device on the local network (preferably Wi-Fi or hotspot).
     */
    fun getLocalIpAddress(context: Context): String {
        try {
            // First attempt: WifiManager IP (fast, zero permission on older Android)
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val wifiIp = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (wifiIp != 0) {
                val ipString = "%d.%d.%d.%d".format(
                    wifiIp and 0xff,
                    wifiIp shr 8 and 0xff,
                    wifiIp shr 16 and 0xff,
                    wifiIp shr 24 and 0xff
                )
                if (ipString != "0.0.0.0") return ipString
            }
        } catch (_: Exception) {}

        // Second attempt: NetworkInterface traversal
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // First look for wlan / ap interfaces (Wi-Fi and Hotspot)
            val wifiIfaces = interfaces.filter {
                it.isUp && !it.isLoopback && (it.name.contains("wlan", ignoreCase = true) || it.name.contains("ap", ignoreCase = true))
            }
            for (iface in wifiIfaces) {
                val addresses = Collections.list(iface.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (host.isNotEmpty() && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }

            // Fallback: any active non-loopback IPv4 interface (e.g. eth0, tethering)
            for (iface in interfaces) {
                if (iface.isUp && !iface.isLoopback) {
                    val addresses = Collections.list(iface.inetAddresses)
                    for (addr in addresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: ""
                            if (host.isNotEmpty() && !host.startsWith("127.")) {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return "127.0.0.1"
    }

    /**
     * Checks if the device is currently connected to a Wi-Fi network.
     */
    fun isWifiConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Exception) {
            false
        }
    }
}
