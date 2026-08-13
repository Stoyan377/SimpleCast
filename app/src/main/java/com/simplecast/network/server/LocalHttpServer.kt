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

    private val mediaMap = java.util.concurrent.ConcurrentHashMap<String, Uri>()

    fun registerMedia(id: String, uri: Uri) {
        mediaMap[id] = uri
    }

    fun clearMedia() {
        mediaMap.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        // IPTV Web Portal endpoint
        if (uri.startsWith("/iptv")) {
            return serveIptvPortal()
        }

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
                    strUri.contains("webm") -> "video/webm"
                    strUri.contains("mp3") -> "audio/mpeg"
                    strUri.contains("aac") -> "audio/aac"
                    else -> "video/mp4"
                }
            }

            // Normalize mime type: HEVC content is served in an MP4 container
            if (mimeType.contains("hevc", ignoreCase = true) || mimeType.contains("h265", ignoreCase = true)) {
                mimeType = "video/mp4"
            }

            val isImage = mimeType.startsWith("image/")
            val isVideo = mimeType.startsWith("video/")

            // Determine DLNA features header
            val dlnaFeatures = if (isImage) {
                val pn = if (mimeType.contains("png")) "PNG_LRG" else "JPEG_LRG"
                "DLNA.ORG_PN=$pn;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=00D00000000000000000000000000000"
            } else if (isVideo && mimeType.contains("mp4")) {
                // AVC_MP4_MP_HD profile tells Android TV / Philips DLNA renderer that this is
                // an H.264 Main Profile MP4 file, preventing "format not supported" errors
                "DLNA.ORG_PN=AVC_MP4_MP_HD;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            } else {
                "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
            }
            val transferMode = if (isImage) "Interactive" else "Streaming"

            // Check if URI is a direct file or ContentResolver URI
            val isFileScheme = contentUri.scheme == "file" || contentUri.path?.startsWith("/") == true
            val fileObj = if (isFileScheme && !contentUri.path.isNullOrEmpty()) java.io.File(contentUri.path!!) else null

            val fileLength: Long = if (fileObj != null && fileObj.exists()) {
                fileObj.length()
            } else {
                val afd = try {
                    contentResolver.openAssetFileDescriptor(contentUri, "r")
                } catch (e: Exception) {
                    null
                }
                val len = afd?.length ?: -1L
                try { afd?.close() } catch (e: Exception) {}
                len
            }

            fun openFreshStream(): InputStream? {
                return if (fileObj != null && fileObj.exists()) {
                    java.io.FileInputStream(fileObj)
                } else {
                    contentResolver.openInputStream(contentUri)
                }
            }

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
                    val inputStream = openFreshStream()
                    if (inputStream != null) {
                        var remaining = start
                        while (remaining > 0) {
                            val skipped = inputStream.skip(remaining)
                            if (skipped <= 0) break
                            remaining -= skipped
                        }
                    }

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
                val inputStream: InputStream? = openFreshStream()
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

            // Universal DLNA HTTP headers for LG webOS, Philips, Android TV & Google TV
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("transferMode.dlna.org", transferMode)
            response.addHeader("contentFeatures.dlna.org", dlnaFeatures)
            response.addHeader("Server", "Linux/2.6.0 UPnP/1.0 DLNADOC/1.50 SimpleCast/1.0")
            response.addHeader("Connection", "keep-alive")
            response.addHeader("Access-Control-Allow-Origin", "*")

            response
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error: ${e.message}")
        }
    }

    /**
     * Reverse proxy: fetches a remote URL (HTTPS or HTTP) and streams it to the DLNA TV
     * over plain HTTP. This solves the LG webOS HTTPS incompatibility and provides
     * compatible HLS manifests with explicit .m3u8 and .ts endpoints for Android TV.
     *
     * URL formats supported:
     * - /proxy/stream.m3u8?url=<encoded_url>
     * - /proxy/video.mp4?url=<encoded_url>
     * - /proxy/<encoded_url>
     */
    private fun serveProxy(session: IHTTPSession): Response {
        return try {
            val fullUri = session.uri.substringAfter("/proxy/")
            val queryString = session.queryParameterString ?: ""

            var queryCookie: String? = null
            if (queryString.contains("cookie=")) {
                val cookieVal = queryString.substringAfter("cookie=").substringBefore("&")
                queryCookie = java.net.URLDecoder.decode(cookieVal, "UTF-8")
            }

            var remoteUrl = ""
            if (queryString.contains("url=")) {
                val urlParam = queryString.substringAfter("url=").substringBefore("&")
                remoteUrl = java.net.URLDecoder.decode(urlParam, "UTF-8")
            } else {
                val rawUrl = fullUri.substringBefore("?")
                remoteUrl = java.net.URLDecoder.decode(rawUrl, "UTF-8")
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

            val isM3u8 = remoteUrl.lowercase().contains(".m3u8") ||
                         session.uri.lowercase().contains(".m3u8") ||
                         contentType.contains("mpegURL", ignoreCase = true) ||
                         contentType.contains("x-mpegurl", ignoreCase = true)

            if (isM3u8) {
                // For HLS manifests: rewrite segment URLs to also proxy through us with proper extensions
                val manifestText = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val baseUrl = remoteUrl.substringBeforeLast("/") + "/"
                val localIp = getLocalIpForProxy()
                val rewrittenManifest = rewriteM3u8(manifestText, baseUrl, localIp)
                val manifestBytes = rewrittenManifest.toByteArray(Charsets.UTF_8)

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/x-mpegURL",
                    rewrittenManifest.byteInputStream(),
                    manifestBytes.size.toLong()
                )
                addDlnaStreamingHeaders(response, isHls = true)
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

                if (responseCode == 206) {
                    val contentRange = conn.getHeaderField("Content-Range")
                    if (!contentRange.isNullOrEmpty()) {
                        response.addHeader("Content-Range", contentRange)
                    }
                }

                if (contentLength > 0) {
                    response.addHeader("Content-Length", contentLength.toString())
                }

                addDlnaStreamingHeaders(response, isHls = false)
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
                if (trimmed.contains("URI=\"")) {
                    val rewritten = trimmed.replace(Regex("URI=\"([^\"]+)\"")) { match ->
                        val innerUrl = match.groupValues[1]
                        val fullUrl = resolveUrl(innerUrl, baseUrl)
                        val encoded = java.net.URLEncoder.encode(fullUrl, "UTF-8")
                        val ext = if (fullUrl.lowercase().contains(".m3u8")) "playlist.m3u8" else "segment.ts"
                        "URI=\"http://$localIp:8080/proxy/$ext?url=$encoded\""
                    }
                    sb.appendLine(rewritten)
                } else {
                    sb.appendLine(trimmed)
                }
            } else {
                val fullUrl = resolveUrl(trimmed, baseUrl)
                val encoded = java.net.URLEncoder.encode(fullUrl, "UTF-8")
                val ext = if (fullUrl.lowercase().contains(".m3u8")) "playlist.m3u8" else "segment.ts"
                sb.appendLine("http://$localIp:8080/proxy/$ext?url=$encoded")
            }
        }

        return sb.toString()
    }

    private fun resolveUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> {
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
            lower.contains(".m3u8") -> "application/x-mpegURL"
            lower.contains(".mpd") -> "application/dash+xml"
            lower.contains(".ts") -> "video/mp2t"
            lower.contains(".mp4") -> "video/mp4"
            lower.contains(".webm") -> "video/webm"
            lower.contains(".mkv") -> "video/x-matroska"
            lower.contains(".aac") -> "audio/aac"
            lower.contains(".mp3") -> "audio/mpeg"
            else -> "video/mp4"
        }
    }

    private fun addDlnaStreamingHeaders(response: Response, isHls: Boolean = false) {
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("transferMode.dlna.org", "Streaming")
        val flags = if (isHls) {
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=21700000000000000000000000000000"
        } else {
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        }
        response.addHeader("contentFeatures.dlna.org", flags)
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

    private var cachedIptvHtml: String? = null
    private var lastIptvFetchTime: Long = 0

    private fun serveIptvPortal(): Response {
        val currentTime = System.currentTimeMillis()
        if (cachedIptvHtml == null || (currentTime - lastIptvFetchTime) > 15 * 60 * 1000L) {
            try {
                val url = URL("https://iptv.org.ua/iptv/avto-full.m3u")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")

                val lines = conn.inputStream.bufferedReader(Charsets.UTF_8).readLines()
                conn.disconnect()

                val itemsList = ArrayList<Triple<String, String, String>>()
                var currentTitle = ""
                var currentGroup = "General"

                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#EXTINF:")) {
                        currentGroup = if (trimmed.contains("group-title=\"")) {
                            trimmed.substringAfter("group-title=\"").substringBefore("\"")
                        } else {
                            "General"
                        }
                        currentTitle = trimmed.substringAfter(",").trim()
                        if (currentTitle.isEmpty()) currentTitle = "Stream ${itemsList.size + 1}"
                    } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                        val titleToUse = if (currentTitle.isNotEmpty()) currentTitle else "Stream ${itemsList.size + 1}"
                        itemsList.add(Triple(titleToUse, trimmed, currentGroup))
                        currentTitle = ""
                    }
                }

                if (itemsList.isNotEmpty()) {
                    cachedIptvHtml = generateIptvHtml(itemsList)
                    lastIptvFetchTime = currentTime
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val htmlContent = cachedIptvHtml ?: "<html><body style='background:#12121c;color:white;font-family:sans-serif;padding:20dp;'><h2>IPTV Playlist Loading Failed</h2><p>Could not connect to iptv.org.ua list. Please try again.</p></body></html>"
        val htmlBytes = htmlContent.toByteArray(Charsets.UTF_8)
        val res = newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            htmlContent.byteInputStream(),
            htmlBytes.size.toLong()
        )
        res.addHeader("Cache-Control", "no-cache")
        return res
    }

    private fun generateIptvHtml(items: List<Triple<String, String, String>>): String {
        val jsonArray = org.json.JSONArray()
        for ((index, item) in items.withIndex()) {
            val obj = org.json.JSONObject()
            obj.put("id", index)
            obj.put("title", item.first)
            obj.put("url", item.second)
            obj.put("group", item.third)
            jsonArray.put(obj)
        }
        val jsonString = jsonArray.toString()

        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Free IPTV Stream List</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { background-color: #0e0e17; color: #ffffff; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; padding: 12px; }
        .header { text-align: center; padding: 12px 0 16px; border-bottom: 1px solid #1a1a2e; margin-bottom: 16px; }
        .title { font-size: 22px; font-weight: 800; color: #ff3b47; margin-bottom: 4px; display: flex; align-items: center; justify-content: center; gap: 8px; }
        .subtitle { font-size: 13px; color: #8a8aa3; }
        .search-box { width: 100%; padding: 12px 16px; border-radius: 12px; border: 1px solid #2a2a40; background: #1a1a2e; color: #fff; font-size: 15px; outline: none; margin-bottom: 16px; }
        .search-box:focus { border-color: #ff3b47; }
        .count-badge { font-size: 12px; color: #00f2fe; margin-bottom: 12px; display: inline-block; font-weight: 600; }
        .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; }
        .card { background: #181828; border-radius: 12px; padding: 12px 14px; border: 1px solid #252538; display: flex; flex-direction: column; justify-content: space-between; transition: transform 0.15s ease; }
        .card:active { transform: scale(0.98); }
        .card-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
        .card-title { font-size: 14px; font-weight: 600; color: #e2e2ee; word-break: break-word; line-height: 1.3; }
        .card-group { font-size: 10px; background: #2b2b45; color: #00f2fe; padding: 3px 6px; border-radius: 6px; text-transform: uppercase; font-weight: 700; white-space: nowrap; }
        .play-btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; background: #ff3b47; color: #ffffff; text-decoration: none; padding: 8px 12px; border-radius: 8px; font-size: 13px; font-weight: 700; margin-top: 6px; border: none; cursor: pointer; }
        .play-btn:hover { background: #e02d38; }
    </style>
</head>
<body>
    <div class="header">
        <div class="title">📺 IPTV Streams & Movies</div>
        <div class="subtitle">Tap any stream to load & cast directly to your Smart TV</div>
    </div>
    <input type="text" id="searchInput" class="search-box" placeholder="🔍 Search channels, movies, series..." oninput="filterChannels()">
    <div class="count-badge" id="countBadge">Loading channels...</div>
    <div class="grid" id="channelGrid"></div>

    <script>
        let channels = [];
        try {
            channels = $jsonString;
        } catch(e) {
            console.error("Parse error", e);
        }

        const grid = document.getElementById('channelGrid');
        const countBadge = document.getElementById('countBadge');
        const searchInput = document.getElementById('searchInput');

        function escapeHtml(str) {
            if (!str) return '';
            return String(str).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
        }

        function escapeJs(str) {
            if (!str) return '';
            return String(str).replace(/\\/g, "\\\\").replace(/'/g, "\\'").replace(/"/g, '\\"');
        }

        function renderList(items) {
            if (!items || items.length === 0) {
                countBadge.innerText = 'No channels found';
                grid.innerHTML = '<div style="color:#8a8aa3; padding:20px; text-align:center;">No matching streams found</div>';
                return;
            }
            countBadge.innerText = 'Showing ' + items.length + ' channels & streams';
            grid.innerHTML = items.map(function(ch) {
                return '<div class="card" id="card-' + ch.id + '">' +
                    '<div class="card-header">' +
                        '<div class="card-title">' + escapeHtml(ch.title) + '</div>' +
                        '<div class="card-group">' + escapeHtml(ch.group) + '</div>' +
                    '</div>' +
                    '<div id="player-box-' + ch.id + '"></div>' +
                    '<button class="play-btn" onclick="playAndCast(' + ch.id + ', \'' + escapeJs(ch.url) + '\', \'' + escapeJs(ch.title) + '\')">▶ Play & Cast Stream</button>' +
                '</div>';
            }).join('');
        }

        function playAndCast(id, url, title) {
            if (window.AndroidMediaSniffer) {
                window.AndroidMediaSniffer.onVideoFound(url, title);
            }
            const playerBox = document.getElementById('player-box-' + id);
            if (playerBox) {
                playerBox.innerHTML = '<video controls autoplay style="width:100%; border-radius:8px; margin:8px 0; background:#000;" src="' + url + '"></video>';
            }
        }

        function filterChannels() {
            const query = searchInput.value.toLowerCase().trim();
            if (!query) {
                renderList(channels.slice(0, 300));
                return;
            }
            const filtered = channels.filter(function(ch) {
                return (ch.title && ch.title.toLowerCase().indexOf(query) !== -1) ||
                       (ch.group && ch.group.toLowerCase().indexOf(query) !== -1);
            });
            renderList(filtered.slice(0, 300));
        }

        // Initial render
        renderList(channels.slice(0, 300));
    </script>
</body>
</html>"""
    }
}
