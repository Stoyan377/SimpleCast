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
        mimeType: String = "video/mp4",
        resolution: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val didlMetadata = createDidlMetadata(mediaUrl, title, mediaType, mimeType, resolution)
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
      <CurrentURIMetaData>${escapeXml(didlMetadata)}</CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>"""

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    /**
     * Universal setAvTransportUri for Android TV, Google TV, Sony, Samsung, etc.
     * Uses simplified protocolInfo "http-get:*:video/mp4:*" or "http-get:*:*:*"
     */
    suspend fun setAvTransportUriUniversal(
        controlUrl: String,
        mediaUrl: String,
        title: String = "Simple Cast Stream",
        mimeType: String = "video/mp4",
        resolution: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val resAttr = if (!resolution.isNullOrBlank()) " resolution=\"$resolution\"" else ""
        val lowerMime = mimeType.lowercase()
        val upnpClass = when {
            lowerMime.contains("audio") -> "object.item.audioItem.musicTrack"
            lowerMime.contains("image") -> "object.item.imageItem.photo"
            else -> "object.item.videoItem"
        }
        val didlMetadata = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>${escapeXml(title)}</dc:title><upnp:class>$upnpClass</upnp:class><res protocolInfo="http-get:*:video/mp4:*"$resAttr>${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:application/x-mpegURL:*">${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:application/vnd.apple.mpegurl:*">${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:video/vnd.dlna.mpeg-tts:*">${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:*:*"$resAttr>${escapeXml(mediaUrl)}</res></item></DIDL-Lite>"""

        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
      <CurrentURIMetaData>${escapeXml(didlMetadata)}</CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>"""

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    /**
     * Fully custom SetAVTransportURI – lets the caller supply the exact DIDL-Lite
     * protocolInfo string that matches what the proxy actually serves.
     */
    suspend fun setAvTransportUriCustom(
        controlUrl: String,
        mediaUrl: String,
        title: String = "Simple Cast Stream",
        upnpClass: String = "object.item.videoItem",
        protocolInfo: String = "http-get:*:video/mp4:*",
        resolution: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val resAttr = if (!resolution.isNullOrBlank()) " resolution=\"$resolution\"" else ""
        val didlMetadata = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>${escapeXml(title)}</dc:title><upnp:class>$upnpClass</upnp:class><res protocolInfo="$protocolInfo"$resAttr>${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:application/x-mpegURL:*">${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:application/vnd.apple.mpegurl:*">${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:video/vnd.dlna.mpeg-tts:*">${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:video/mp4:*"$resAttr>${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:*:*"$resAttr>${escapeXml(mediaUrl)}</res></item></DIDL-Lite>"""

        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
      <CurrentURIMetaData>${escapeXml(didlMetadata)}</CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>"""

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    /**
     * SetAVTransportURI without metadata – some LG TVs respond better
     * when handed a raw URL without DIDL-Lite metadata for web streams.
     */
    suspend fun setAvTransportUriRaw(
        controlUrl: String,
        mediaUrl: String
    ): Boolean = withContext(Dispatchers.IO) {
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
      <CurrentURIMetaData></CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>"""

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    suspend fun play(controlUrl: String): Boolean = withContext(Dispatchers.IO) {
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <Speed>1</Speed>
    </u:Play>
  </s:Body>
</s:Envelope>"""

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    suspend fun pause(controlUrl: String): Boolean = withContext(Dispatchers.IO) {
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Pause xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:Pause>
  </s:Body>
</s:Envelope>"""

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#Pause\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    suspend fun stop(controlUrl: String): Boolean = withContext(Dispatchers.IO) {
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:Stop>
  </s:Body>
</s:Envelope>"""

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    suspend fun seek(controlUrl: String, targetTimeString: String): Boolean = withContext(Dispatchers.IO) {
        val soapBody = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Seek xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <Unit>REL_TIME</Unit>
      <Target>$targetTimeString</Target>
    </u:Seek>
  </s:Body>
</s:Envelope>"""

        val action = "\"urn:schemas-upnp-org:service:AVTransport:1#Seek\""
        sendSoapRequest(controlUrl, action, soapBody)
    }

    private fun sendSoapRequest(controlUrl: String, soapAction: String, body: String): Boolean {
        return try {
            val url = URL(controlUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true

            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", soapAction)
            conn.setRequestProperty("User-Agent", "SimpleCast/1.0 UPnP/1.0 DLNADOC/1.50")
            conn.setRequestProperty("Connection", "Close")

            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("Content-Length", bodyBytes.size.toString())

            val out = conn.outputStream
            out.write(bodyBytes)
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

    private fun createDidlMetadata(
        mediaUrl: String,
        title: String,
        mediaType: MediaType,
        mimeType: String,
        resolution: String? = null
    ): String {
        val resAttr = if (!resolution.isNullOrBlank()) " resolution=\"$resolution\"" else ""
        val lowerMime = mimeType.lowercase()

        val (upnpClass, primaryPi) = when (mediaType) {
            MediaType.VIDEO -> {
                val pi = when {
                    lowerMime.contains("mpegurl") || lowerMime.contains("m3u8") ->
                        "http-get:*:application/x-mpegURL:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=21700000000000000000000000000000"
                    lowerMime.contains("dash") || lowerMime.contains("mpd") ->
                        "http-get:*:application/dash+xml:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=21700000000000000000000000000000"
                    lowerMime.contains("webm") ->
                        "http-get:*:video/webm:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"
                    lowerMime.contains("mp2t") || lowerMime.contains("mpeg-ts") || lowerMime.contains("mpeg-tts") ->
                        "http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=MPEG_TS_SD_EU_ISO;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"
                    else ->
                        "http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_HD;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"
                }
                Pair("object.item.videoItem", pi)
            }
            MediaType.IMAGE -> {
                val pn = if (lowerMime.contains("png")) "PNG_LRG" else "JPEG_LRG"
                Pair(
                    "object.item.imageItem.photo",
                    "http-get:*:$mimeType:DLNA.ORG_PN=$pn;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=00D00000000000000000000000000000"
                )
            }
            MediaType.AUDIO -> Pair(
                "object.item.audioItem.musicTrack",
                "http-get:*:$mimeType:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01500000000000000000000000000000"
            )
        }

        val aspectTag = calculateAspectRatio(resolution)?.let { "<upnp:aspectRatio>$it</upnp:aspectRatio>" } ?: ""

        // Multi-res fallback elements in DIDL-Lite:
        // For HLS/streaming mimes, include HLS + MPEG-TS + MP4 fallbacks for maximum TV compatibility.
        // For plain video/mp4 (local files), only include MP4 profiles to avoid confusing
        // Android TV into trying to parse the file as HLS.
        val isStreamingMime = lowerMime.contains("mpegurl") || lowerMime.contains("m3u8") ||
            lowerMime.contains("mp2t") || lowerMime.contains("mpeg-ts") || lowerMime.contains("mpeg-tts") ||
            lowerMime.contains("dash") || lowerMime.contains("mpd")

        val multiRes = if (mediaType == MediaType.VIDEO) {
            if (isStreamingMime) {
                """<res protocolInfo="$primaryPi"$resAttr>${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_HD;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"$resAttr>${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:application/vnd.apple.mpegurl:*">${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:application/x-mpegURL:*">${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:video/vnd.dlna.mpeg-tts:*">${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:video/mp4:*">${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:*:*">${escapeXml(mediaUrl)}</res>"""
            } else {
                // Local video/mp4: only MP4 profiles, no HLS/MPEG-TS noise
                """<res protocolInfo="$primaryPi"$resAttr>${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"$resAttr>${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:video/mp4:*"$resAttr>${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:*:*"$resAttr>${escapeXml(mediaUrl)}</res>"""
            }
        } else {
            """<res protocolInfo="$primaryPi"$resAttr>${escapeXml(mediaUrl)}</res><res protocolInfo="http-get:*:$mimeType:*">${escapeXml(mediaUrl)}</res>"""
        }

        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="1" parentID="0" restricted="1"><dc:title>${escapeXml(title)}</dc:title><upnp:class>$upnpClass</upnp:class>$aspectTag$multiRes</item></DIDL-Lite>"""
    }

    private fun calculateAspectRatio(resolution: String?): String? {
        if (resolution.isNullOrBlank() || !resolution.contains("x")) return null
        return try {
            val parts = resolution.split("x")
            val w = parts[0].toInt()
            val h = parts[1].toInt()
            if (w <= 0 || h <= 0) return null
            if (w == h) return "1:1"
            if (w * 9 == h * 16) return "16:9"
            if (w * 16 == h * 9) return "9:16"
            if (w * 3 == h * 4) return "4:3"
            if (w * 4 == h * 3) return "3:4"
            val gcdVal = gcd(w, h)
            "${w / gcdVal}:${h / gcdVal}"
        } catch (e: Exception) {
            null
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        var n1 = a
        var n2 = b
        while (n2 != 0) {
            val temp = n2
            n2 = n1 % n2
            n1 = temp
        }
        return n1
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
