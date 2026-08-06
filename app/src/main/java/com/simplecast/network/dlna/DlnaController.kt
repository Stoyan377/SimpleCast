package com.simplecast.network.dlna

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

enum class MediaType {
    VIDEO, IMAGE, AUDIO
}

class DlnaController {

    suspend fun setAvTransportUri(
        controlUrl: String,
        mediaUrl: String,
        title: String = "Simple Cast Stream",
        mediaType: MediaType = MediaType.VIDEO,
        mimeType: String = "video/mp4"
    ): Boolean = withContext(Dispatchers.IO) {
        val didlMetadata = createDidlMetadata(mediaUrl, title, mediaType, mimeType)
        val soapBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
                        <CurrentURIMetaData>${escapeXml(didlMetadata)}</CurrentURIMetaData>
                    </u:SetAVTransportURI>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    suspend fun play(controlUrl: String): Boolean = withContext(Dispatchers.IO) {
        val soapBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <Speed>1</Speed>
                    </u:Play>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    suspend fun pause(controlUrl: String): Boolean = withContext(Dispatchers.IO) {
        val soapBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                    </u:Pause>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#Pause\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    suspend fun stop(controlUrl: String): Boolean = withContext(Dispatchers.IO) {
        val soapBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                    </u:Stop>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    suspend fun seek(controlUrl: String, targetTimeString: String): Boolean = withContext(Dispatchers.IO) {
        val soapBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                        <InstanceID>0</InstanceID>
                        <Unit>REL_TIME</Unit>
                        <Target>$targetTimeString</Target>
                    </u:Seek>
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#Seek\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    private fun sendSoapRequest(controlUrl: String, soapAction: String, body: String): Boolean {
        return try {
            val url = URL(controlUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true

            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", soapAction)
            conn.setRequestProperty("Connection", "Close")

            val out = OutputStreamWriter(conn.outputStream, "UTF-8")
            out.write(body)
            out.flush()
            out.close()

            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun createDidlMetadata(mediaUrl: String, title: String, mediaType: MediaType, mimeType: String): String {
        val upnpClass = when (mediaType) {
            MediaType.VIDEO -> "object.item.videoItem"
            MediaType.IMAGE -> "object.item.imageItem.photo"
            MediaType.AUDIO -> "object.item.audioItem.musicTrack"
        }

        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                <item id="1" parentID="0" restricted="1">
                    <dc:title>${escapeXml(title)}</dc:title>
                    <upnp:class>$upnpClass</upnp:class>
                    <res protocolInfo="http-get:*:$mimeType:*">$mediaUrl</res>
                </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
