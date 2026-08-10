package com.simplecast.web

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import java.util.Locale

data class SniffedMedia(
    val url: String,
    val mimeType: String,
    val title: String,
    val quality: String,
    val domain: String,
    val isRecommended: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class MediaSnifferJSBridge(
    private val onVideoFound: (String, String) -> Unit
) {
    @JavascriptInterface
    fun onVideoFound(url: String, pageTitle: String) {
        onVideoFound.invoke(url, pageTitle)
    }
}

class MediaSnifferWebViewClient(
    private val onMediaDetected: (SniffedMedia) -> Unit
) : WebViewClient() {

    private val mediaExtensions = listOf(".mp4", ".m3u8", ".mpd", ".webm", ".mov", ".mkv", ".flv")
    private val detectedUrls = HashSet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (!url.isNullOrEmpty()) {
            checkAndNotifyMedia(url, "Web Video")
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        if (!url.isNullOrEmpty()) {
            checkAndNotifyMedia(url, "Web Video")
        }
        super.onLoadResource(view, url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // Inject HTML5 Video Inspector JS Script
        val jsScript = """
            (function() {
                function scanVideos() {
                    try {
                        var vids = document.getElementsByTagName('video');
                        for (var i = 0; i < vids.length; i++) {
                            var v = vids[i];
                            var src = v.currentSrc || v.src;
                            if (src && src.indexOf('blob:') !== 0) {
                                if (window.AndroidMediaSniffer) {
                                    window.AndroidMediaSniffer.onVideoFound(src, document.title || 'HTML5 Video');
                                }
                            }
                            var sources = v.getElementsByTagName('source');
                            for (var j = 0; j < sources.length; j++) {
                                var s = sources[j];
                                if (s.src && s.src.indexOf('blob:') !== 0) {
                                    if (window.AndroidMediaSniffer) {
                                        window.AndroidMediaSniffer.onVideoFound(s.src, document.title || 'HTML5 Video');
                                    }
                                }
                            }
                        }
                    } catch(e) {}
                }
                scanVideos();
                setTimeout(scanVideos, 1500);
                setTimeout(scanVideos, 3500);
            })();
        """.trimIndent()

        view?.evaluateJavascript(jsScript, null)
    }

    fun checkAndNotifyMedia(url: String, pageTitle: String) {
        val lowerUrl = url.lowercase(Locale.ROOT)

        // Ignore blob URLs, static assets, analytics, and fragmented audio/video range chunks
        if (lowerUrl.startsWith("blob:") || lowerUrl.contains(".js") || lowerUrl.contains(".css") ||
            lowerUrl.contains(".png") || lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") ||
            lowerUrl.contains(".svg") || lowerUrl.contains(".gif") || lowerUrl.contains("google-analytics") ||
            lowerUrl.contains("facebook.com") || lowerUrl.contains("doubleclick") ||
            lowerUrl.contains("range=") || lowerUrl.contains("&sq=") || lowerUrl.contains("init-") ||
            lowerUrl.contains("index_") || lowerUrl.contains("segment") || lowerUrl.contains("fragment") ||
            lowerUrl.contains("preview") || lowerUrl.contains("thumbnail")
        ) {
            return
        }

        val isVideoExtension = mediaExtensions.any { ext -> lowerUrl.contains(ext) }
        val isStream = lowerUrl.contains("m3u8") || lowerUrl.contains("mpd") || lowerUrl.contains("videoplayback")

        if ((isVideoExtension || isStream) && !detectedUrls.contains(url)) {
            detectedUrls.add(url)

            val mimeType = when {
                lowerUrl.contains("m3u8") -> "application/x-mpegURL"
                lowerUrl.contains("mpd") -> "application/dash+xml"
                lowerUrl.contains("webm") -> "video/webm"
                else -> "video/mp4"
            }

            val quality = when {
                lowerUrl.contains("m3u8") || lowerUrl.contains("master") -> "HLS Master"
                lowerUrl.contains("mpd") -> "DASH Stream"
                lowerUrl.contains("1080") -> "1080p HD"
                lowerUrl.contains("720") -> "720p HD"
                lowerUrl.contains("480") -> "480p SD"
                lowerUrl.contains("360") -> "360p"
                else -> "MP4 Video"
            }

            val domain = try {
                URI(url).host?.replace("www.", "") ?: "Web Video"
            } catch (e: Exception) {
                "Web Video"
            }

            val title = if (pageTitle.isNotEmpty() && pageTitle != "Web Video") {
                pageTitle.take(35)
            } else {
                "$domain Stream"
            }

            val isRecommended = lowerUrl.contains("m3u8") || lowerUrl.contains("master") || lowerUrl.contains("1080") || lowerUrl.contains("720")

            val sniffedMedia = SniffedMedia(
                url = url,
                mimeType = mimeType,
                title = title,
                quality = quality,
                domain = domain,
                isRecommended = isRecommended
            )

            mainHandler.post {
                onMediaDetected(sniffedMedia)
            }
        }
    }

    fun clearDetected() {
        detectedUrls.clear()
    }
}
