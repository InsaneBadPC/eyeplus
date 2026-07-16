package com.eyeplus.util

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Network utility functions for camera discovery and connectivity.
 */
object NetworkUtils {

    /**
     * Get the current device's local IP address.
     */
    fun getLocalIpAddress(): String? {
        try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.let { return it.hostAddress }
        } catch (e: Exception) {
            // Fall through
        }
        return null
    }

    /**
     * Acquire WiFi multicast lock (required for WS-Discovery).
     * Must be released after discovery completes.
     */
    fun acquireMulticastLock(context: Context, tag: String = "eyeplus-discovery"): WifiManager.MulticastLock? {
        return try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock(tag)
            lock.setReferenceCounted(true)
            lock.acquire()
            lock
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Release a previously acquired multicast lock.
     */
    fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        try {
            lock?.release()
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Check if WiFi is connected and active.
     */
    fun isWifiConnected(context: Context): Boolean {
        return try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.isWifiEnabled && wifi.connectionInfo?.ssid != null
        } catch (e: Exception) {
            false
        }
    }
}
