package com.simplecast.network.ssdp

import android.content.Context
import com.simplecast.network.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

data class DlnaDevice(
    val usn: String,
    val friendlyName: String,
    val manufacturer: String = "Unknown",
    val modelName: String = "Smart TV",
    val locationUrl: String,
    val controlUrl: String,
    val ipAddress: String,
    val iconUrl: String? = null
) {
    val isLgWebOs: Boolean
        get() {
            val m = "$manufacturer $modelName $friendlyName".lowercase()
            return m.contains("lg") || m.contains("webos") || m.contains("netcast")
        }

    val isAndroidTv: Boolean
        get() {
            val m = "$manufacturer $modelName $friendlyName".lowercase()
            return m.contains("android") || m.contains("google") ||
                m.contains("chromecast")
        }

    /** True for any non-LG TV (Samsung Tizen, Hisense Vidaa, Android TV, etc.) */
    val isNonLgTv: Boolean
        get() = !isLgWebOs
}

class SsdpDiscoveryManager(private val context: Context) {

    private val _discoveredDevices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DlnaDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    suspend fun discoverDevices(timeoutMs: Int = 5000) = withContext(Dispatchers.IO) {
        if (_isScanning.value) return@withContext
        _isScanning.value = true

        val multicastLock = NetworkUtils.createMulticastLock(context)
        multicastLock?.acquire()

        val devicesList = mutableMapOf<String, DlnaDevice>()

        try {
            val ssdpQuery = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n"

            val sendData = ssdpQuery.toByteArray()
            val socket = DatagramSocket()
            socket.soTimeout = 1000

            val group = InetAddress.getByName("239.255.255.250")
            val sendPacket = DatagramPacket(sendData, sendData.size, group, 1900)
            socket.send(sendPacket)

            // Also search for general MediaRenderer devices
            val secondaryQuery = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 3\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
            val secondaryData = secondaryQuery.toByteArray()
            socket.send(DatagramPacket(secondaryData, secondaryData.size, group, 1900))

            val startTime = System.currentTimeMillis()
            val receiveData = ByteArray(2048)

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val receivePacket = DatagramPacket(receiveData, receiveData.size)
                    socket.receive(receivePacket)

                    val response = String(receivePacket.data, 0, receivePacket.length)
                    val headers = parseHeaders(response)
                    val location = headers["LOCATION"] ?: headers["location"]

                    if (!location.isNullOrEmpty() && !devicesList.containsKey(location)) {
                        val device = parseDeviceXml(location)
                        if (device != null) {
                            devicesList[location] = device
                            _discoveredDevices.value = devicesList.values.toList()
                        }
                    }
                } catch (e: Exception) {
                    // Socket timeout - continue listening until deadline
                }
            }

            socket.close()

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
            _isScanning.value = false
        }
    }

    private fun parseHeaders(response: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val lines = response.split("\r\n", "\n")
        for (line in lines) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim().uppercase()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    private fun parseDeviceXml(xmlLocation: String): DlnaDevice? {
        return try {
            val url = URL(xmlLocation)
            val connection = url.openConnection()
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(connection.getInputStream())

            doc.documentElement.normalize()

            val friendlyName = doc.getElementsByTagName("friendlyName").item(0)?.textContent ?: "LG TV"
            val manufacturer = doc.getElementsByTagName("manufacturer").item(0)?.textContent ?: "LG Electronics"
            val modelName = doc.getElementsByTagName("modelName").item(0)?.textContent ?: "webOS TV"
            val udn = doc.getElementsByTagName("UDN").item(0)?.textContent ?: xmlLocation

            var controlUrl: String? = null

            // Find AVTransport service controlURL
            val serviceList = doc.getElementsByTagName("service")
            for (i in 0 until serviceList.length) {
                val serviceNode = serviceList.item(i)
                val serviceType = (serviceNode as? org.w3c.dom.Element)?.getElementsByTagName("serviceType")?.item(0)?.textContent
                if (serviceType != null && serviceType.contains("AVTransport")) {
                    val relativeControlUrl = serviceNode.getElementsByTagName("controlURL").item(0)?.textContent
                    if (!relativeControlUrl.isNullOrEmpty()) {
                        controlUrl = if (relativeControlUrl.startsWith("http")) {
                            relativeControlUrl
                        } else {
                            val baseUrl = "${url.protocol}://${url.host}:${if (url.port != -1) url.port else url.defaultPort}"
                            if (relativeControlUrl.startsWith("/")) baseUrl + relativeControlUrl else "$baseUrl/$relativeControlUrl"
                        }
                        break
                    }
                }
            }

            if (controlUrl != null) {
                DlnaDevice(
                    usn = udn,
                    friendlyName = friendlyName,
                    manufacturer = manufacturer,
                    modelName = modelName,
                    locationUrl = xmlLocation,
                    controlUrl = controlUrl,
                    ipAddress = url.host
                )
            } else null

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
