package com.eyeplus.data.onvif

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID

/**
 * WS-Discovery (Web Services Dynamic Discovery) for finding ONVIF cameras
 * on the local network via UDP multicast.
 *
 * Sends a WS-Discovery Probe message to 239.255.255.250:3702
 * and collects responses from ONVIF-compatible devices.
 */
class OnvifDiscovery {

    companion object {
        private const val TAG = "OnvifDiscovery"
        private const val MULTICAST_ADDRESS = "239.255.255.250"
        private const val MULTICAST_PORT = 3702
        private const val DEFAULT_TIMEOUT_MS = 5000L
        private const val BUFFER_SIZE = 65536

        // WS-Discovery Probe message
        private val PROBE_MESSAGE = """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope
    xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
    xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
    xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery"
    xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
  <soap:Header>
    <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</wsa:Action>
    <wsa:MessageID>urn:uuid:__UUID__</wsa:MessageID>
    <wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>
  </soap:Header>
  <soap:Body>
    <wsd:Probe>
      <wsd:Types>dn:NetworkVideoTransmitter</wsd:Types>
    </wsd:Probe>
  </soap:Body>
</soap:Envelope>"""
    }

    data class DiscoveredDevice(
        val ip: String,
        val port: Int = 80,
        val xaddrs: List<String> = emptyList(),
        val scopes: List<String> = emptyList(),
        val types: List<String> = emptyList(),
        val model: String = "",
        val manufacturer: String = ""
    )

    /**
     * Discover ONVIF cameras on the local network.
     * Must be called with a MulticastLock already acquired!
     */
    suspend fun discover(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<DiscoveredDevice> {
        return withContext(Dispatchers.IO) {
            val devices = mutableListOf<DiscoveredDevice>()
            val socket = DatagramSocket(null)

            try {
                socket.reuseAddress = true
                socket.soTimeout = timeoutMs.toInt()
                socket.bind(InetSocketAddress(0))

                // Send Probe message
                val probeXml = PROBE_MESSAGE.replace("__UUID__", UUID.randomUUID().toString())
                val probeBytes = probeXml.toByteArray(Charsets.UTF_8)
                val probePacket = DatagramPacket(
                    probeBytes, probeBytes.size,
                    InetAddress.getByName(MULTICAST_ADDRESS), MULTICAST_PORT
                )
                socket.send(probePacket)
                Log.d(TAG, "WS-Discovery probe sent to $MULTICAST_ADDRESS:$MULTICAST_PORT")

                // Collect responses
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    try {
                        val responsePacket = DatagramPacket(buffer, buffer.size)
                        socket.receive(responsePacket)
                        val xml = String(
                            responsePacket.data, responsePacket.offset,
                            responsePacket.length, Charsets.UTF_8
                        )
                        val senderIp = responsePacket.address.hostAddress ?: "unknown"
                        Log.d(TAG, "Discovery response from $senderIp")

                        val device = parseProbeMatch(xml, senderIp)
                        if (device != null && devices.none { it.ip == device.ip }) {
                            devices.add(device)
                            Log.d(TAG, "Discovered ONVIF device: ${device.ip}:${device.port}")
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.d(TAG, "Discovery timeout reached after ${devices.size} devices")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error: ${e.message}", e)
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) { }
            }

            devices
        }
    }

    /**
     * Parse a WS-Discovery ProbeMatch response.
     */
    private fun parseProbeMatch(xml: String, senderIp: String): DiscoveredDevice? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var xaddrs = mutableListOf<String>()
            var scopes = mutableListOf<String>()
            var types = mutableListOf<String>()
            var ip = senderIp
            var port = 80
            var model = ""
            var manufacturer = ""
            var inXAddrs = false
            var inScopes = false
            var inTypes = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "XAddrs" -> inXAddrs = true
                            "Scopes" -> inScopes = true
                            "Types" -> inTypes = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        when {
                            inXAddrs -> {
                                val addr = parser.text.trim()
                                xaddrs.add(addr)
                                // Extract IP and port from http://IP:port/...
                                val match = Regex("http://([^:/]+)(?::(\\d+))?(/.*)?").find(addr)
                                if (match != null) {
                                    ip = match.groupValues[1]
                                    port = match.groupValues[2].toIntOrNull() ?: 80
                                }
                            }
                            inScopes -> {
                                scopes.addAll(parser.text.trim().split("\\s+".toRegex()))
                                // Try to extract model/manufacturer from scopes
                                scopes.forEach { scope ->
                                    if (scope.contains("hardware/")) {
                                        model = scope.substringAfter("hardware/")
                                    }
                                }
                            }
                            inTypes -> {
                                types.addAll(parser.text.trim().split("\\s+".toRegex()))
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "XAddrs" -> inXAddrs = false
                            "Scopes" -> inScopes = false
                            "Types" -> inTypes = false
                        }
                    }
                }
                parser.next()
            }

            // Only return if it has ONVIF types
            if (types.any { it.contains("NetworkVideoTransmitter") || it.contains("onvif") }) {
                DiscoveredDevice(
                    ip = ip,
                    port = port,
                    xaddrs = xaddrs,
                    scopes = scopes,
                    types = types,
                    model = model,
                    manufacturer = manufacturer
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ProbeMatch: ${e.message}", e)
            null
        }
    }
}
