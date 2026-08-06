package com.simplecast.network.utils

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Obtains the local IPv4 address of the Android device connected to Wi-Fi.
     */
    fun getLocalIpAddress(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiInfo = wifiManager?.connectionInfo
        val ipInt = wifiInfo?.ipAddress ?: 0
        if (ipInt != 0) {
            return String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        }

        // Fallback to searching network interfaces
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val hostAddr = addr.hostAddress
                        val isIPv4 = hostAddr.indexOf(':') < 0
                        if (isIPv4 && hostAddr != "127.0.0.1") {
                            return hostAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Acquire MulticastLock to allow receiving SSDP multicast responses on Wi-Fi.
     */
    fun createMulticastLock(context: Context): WifiManager.MulticastLock? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifiManager?.createMulticastLock("SimpleCastMulticastLock")
        lock?.setReferenceCounted(true)
        return lock
    }
}
