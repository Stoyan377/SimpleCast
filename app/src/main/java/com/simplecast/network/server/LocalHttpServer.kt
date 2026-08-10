package com.simplecast.network.server

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class LocalHttpServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val mediaMap = mutableMapOf<String, Uri>()

    fun registerMedia(id: String, uri: Uri) {
        mediaMap[id] = uri
    }

    fun clearMedia() {
        mediaMap.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        // Reverse proxy endpoint for web streams
        if (uri.startsWith("/proxy/")) {
            return serveProxy(session)
        }

        if (!uri.startsWith("/media/")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found")
        }

        val mediaId = uri.substringAfter("/media/")
        val contentUri = mediaMap[mediaId]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Media Not Found")

        return try {
            val contentResolver = context.contentResolver
            var mimeType = contentResolver.getType(contentUri)

            // Infer mimeType if ContentResolver returns null
            if (mimeType.isNullOrEmpty()) {
                val strUri = contentUri.toString().lowercase()
                mimeType = when {
                    strUri.contains("jpg") || strUri.contains("jpeg") -> "image/jpeg"
                    strUri.contains("png") -> "image/png"
                    strUri.contains("mp4") -> "video/mp4"
                    strUri.contains("mkv") -> "video/x-matroska"
                    else -> "video/mp4"
                }
            }

            val isImage = mimeType.startsWith("image/")

            // Determine DLNA features header for LG webOS
            val dlnaFeatures = if (isImage) {
                val pn = if (mimeType.contains("png")) "PNG_LRG" else "JPEG_LRG"
                "DLNA.ORG_PN=$pn;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=00D00000000000000000000000000000"
            } else {
                "DLNA.ORG_PN=MP4_MED;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            }
            val transferMode = if (isImage) "Interactive" else "Streaming"

            val assetFileDescriptor = try {
                contentResolver.openAssetFileDescriptor(contentUri, "r")
            } catch (e: Exception) {
                null
            }

            val fileLength = assetFileDescriptor?.length ?: -1L
            val rangeHeader = session.headers["range"] ?: session.headers["Range"]

            val response = if (rangeHeader != null && rangeHeader.startsWith("bytes=") && fileLength > 0) {
                var start: Long = 0
                var end: Long = fileLength - 1

                val rangeValue = rangeHeader.substring(6)
                val minusIndex = rangeValue.indexOf('-')
                if (minusIndex > 0) {
                    try {
                        start = rangeValue.substring(0, minusIndex).toLong()
                        if (minusIndex < rangeValue.length - 1) {
                            end = rangeValue.substring(minusIndex + 1).toLong()
                        }
                    } catch (e: NumberFormatException) {
                        e.printStackTrace()
                    }
                }

                if (start >= fileLength) {
                    val res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    res.addHeader("Content-Range", "bytes */$fileLength")
                    res
                } else {
                    val contentLength = end - start + 1
                    val inputStream = contentResolver.openInputStream(contentUri)
                    inputStream?.skip(start)

                    val res = newFixedLengthResponse(
                        Response.Status.PARTIAL_CONTENT,
                        mimeType,
                        inputStream,
                        contentLength
                    )
                    res.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                    res.addHeader("Content-Length", contentLength.toString())
                    res
                }
            } else {
                val inputStream: InputStream? = contentResolver.openInputStream(contentUri)
                val res = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    inputStream,
                    if (fileLength > 0) fileLength else inputStream?.available()?.toLong() ?: -1L
                )
                if (fileLength > 0) {
                    res.addHeader("Content-Length", fileLength.toString())
                }
                res
            }

            // Mandatory DLNA HTTP headers for LG webOS 4.5+ compatibility
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("transferMode.dlna.org", transferMode)
            response.addHeader("contentFeatures.dlna.org", dlnaFeatures)
            response.addHeader("Server", "Linux/2.6.0 UPnP/1.0 DLNADOC/1.50 SimpleCast/1.0")
            response.addHeader("Connection", "keep-alive")

            response
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error: ${e.message}")
        }
    }

    /**
     * Reverse proxy: fetches a remote URL (HTTPS or HTTP) and streams it to the DLNA TV
     * over plain HTTP. This solves the LG webOS HTTPS incompatibility.
     *
     * URL format: /proxy/<encoded_url>
     * For HLS .m3u8 manifests, rewrites internal URLs to also go through the proxy.
     */
    private fun serveProxy(session: IHTTPSession): Response {
        return try {
            val fullUri = session.uri.substringAfter("/proxy/")
            val rawUrl = fullUri.substringBefore("?")
            val remoteUrl = java.net.URLDecoder.decode(rawUrl, "UTF-8")

            // Parse optional query params passed to proxy (e.g. ?cookie=...)
            val queryString = session.queryParameterString ?: ""
            var queryCookie: String? = null
            if (queryString.contains("cookie=")) {
                val cookieVal = queryString.substringAfter("cookie=").substringBefore("&")
                queryCookie = java.net.URLDecoder.decode(cookieVal, "UTF-8")
            }

            if (remoteUrl.isBlank()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No URL provided")
            }

            val conn = URL(remoteUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true

            // Set browser User-Agent
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Encoding", "identity") // No compression - stream as-is

            // Forward session/captured cookies if present
            val sessionCookies = queryCookie ?: session.headers["x-proxy-cookie"] ?: session.headers["cookie"]
            if (!sessionCookies.isNullOrEmpty()) {
                conn.setRequestProperty("Cookie", sessionCookies)
            }

            // Forward Referer if present
            val referer = session.headers["x-proxy-referer"] ?: session.headers["referer"]
            if (!referer.isNullOrEmpty()) {
                conn.setRequestProperty("Referer", referer)
            } else {
                // Default Referer to origin domain of stream
                try {
                    val uriObj = URL(remoteUrl)
                    conn.setRequestProperty("Referer", "${uriObj.protocol}://${uriObj.host}/")
                } catch (e: Exception) {}
            }

            // Forward Range header if the TV requests it
            val rangeHeader = session.headers["range"]
            if (!rangeHeader.isNullOrEmpty()) {
                conn.setRequestProperty("Range", rangeHeader)
            }

            val responseCode = conn.responseCode
            val contentType = conn.contentType ?: guessContentType(remoteUrl)
            val contentLength = conn.contentLengthLong

            val isM3u8 = remoteUrl.lowercase().contains(".m3u8") || contentType.contains("mpegURL", ignoreCase = true)

            if (isM3u8) {
                // For HLS manifests: rewrite segment URLs to also proxy through us
                val manifestText = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val baseUrl = remoteUrl.substringBeforeLast("/") + "/"
                val localIp = getLocalIpForProxy()
                val rewrittenManifest = rewriteM3u8(manifestText, baseUrl, localIp)
                val manifestBytes = rewrittenManifest.toByteArray(Charsets.UTF_8)

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/vnd.apple.mpegurl",
                    rewrittenManifest.byteInputStream(),
                    manifestBytes.size.toLong()
                )
                addDlnaStreamingHeaders(response)
                response
            } else {
                // For video segments / direct files: stream through
                val inputStream = conn.inputStream
                val status = if (responseCode == 206) Response.Status.PARTIAL_CONTENT else Response.Status.OK

                val response = newFixedLengthResponse(
                    status,
                    contentType,
                    inputStream,
                    if (contentLength > 0) contentLength else -1L
                )

                // Forward content-range for partial content
                if (responseCode == 206) {
                    val contentRange = conn.getHeaderField("Content-Range")
                    if (!contentRange.isNullOrEmpty()) {
                        response.addHeader("Content-Range", contentRange)
                    }
                }

                if (contentLength > 0) {
                    response.addHeader("Content-Length", contentLength.toString())
                }

                addDlnaStreamingHeaders(response)
                response
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Proxy error: ${e.message}"
            )
        }
    }

    /**
     * Rewrite HLS manifest URLs so that relative & absolute URLs all go through our proxy.
     */
    private fun rewriteM3u8(manifest: String, baseUrl: String, localIp: String): String {
        val lines = manifest.lines()
        val sb = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                // For EXT-X-STREAM-INF with URI= or EXT-X-MAP with URI=, rewrite inline URIs
                if (trimmed.contains("URI=\"")) {
                    val rewritten = trimmed.replace(Regex("URI=\"([^\"]+)\"")) { match ->
                        val innerUrl = match.groupValues[1]
                        val fullUrl = resolveUrl(innerUrl, baseUrl)
                        val encoded = java.net.URLEncoder.encode(fullUrl, "UTF-8")
                        "URI=\"http://$localIp:8080/proxy/$encoded\""
                    }
                    sb.appendLine(rewritten)
                } else {
                    sb.appendLine(trimmed)
                }
            } else {
                // This is a URL line (segment or sub-playlist)
                val fullUrl = resolveUrl(trimmed, baseUrl)
                val encoded = java.net.URLEncoder.encode(fullUrl, "UTF-8")
                sb.appendLine("http://$localIp:8080/proxy/$encoded")
            }
        }

        return sb.toString()
    }

    private fun resolveUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> {
                // Absolute path - combine with origin
                val origin = try {
                    val u = URL(baseUrl)
                    "${u.protocol}://${u.host}" + (if (u.port > 0 && u.port != u.defaultPort) ":${u.port}" else "")
                } catch (e: Exception) { baseUrl }
                "$origin$url"
            }
            else -> "$baseUrl$url"
        }
    }

    private fun guessContentType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> "application/vnd.apple.mpegurl"
            lower.contains(".mpd") -> "application/dash+xml"
            lower.contains(".ts") -> "video/mp2t"
            lower.contains(".mp4") -> "video/mp4"
            lower.contains(".webm") -> "video/webm"
            lower.contains(".aac") -> "audio/aac"
            else -> "video/mp4"
        }
    }

    private fun addDlnaStreamingHeaders(response: Response) {
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("transferMode.dlna.org", "Streaming")
        response.addHeader("contentFeatures.dlna.org",
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=21700000000000000000000000000000")
        response.addHeader("Server", "Linux/2.6.0 UPnP/1.0 DLNADOC/1.50 SimpleCast/1.0")
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Access-Control-Allow-Origin", "*")
    }

    private fun getLocalIpForProxy(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }
}
