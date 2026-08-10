package com.simplecast.web

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Locale

data class SniffedMedia(
    val url: String,
    val mimeType: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

class MediaSnifferWebViewClient(
    private val onMediaDetected: (SniffedMedia) -> Unit
) : WebViewClient() {

    private val mediaExtensions = listOf(".mp4", ".m3u8", ".mpd", ".webm", ".mov", ".mkv", ".flv", ".ts")
    private val detectedUrls = HashSet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (!url.isNullOrEmpty()) {
            checkAndNotifyMedia(url)
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        if (!url.isNullOrEmpty()) {
            checkAndNotifyMedia(url)
        }
        super.onLoadResource(view, url)
    }

    private fun checkAndNotifyMedia(url: String) {
        val lowerUrl = url.lowercase(Locale.ROOT)

        // Filter out static web assets and analytics scripts
        if (lowerUrl.contains(".js") || lowerUrl.contains(".css") || lowerUrl.contains(".png") ||
            lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") || lowerUrl.contains(".svg") ||
            lowerUrl.contains("google-analytics") || lowerUrl.contains("facebook.com")
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

            val title = when {
                lowerUrl.contains("m3u8") -> "HLS Stream"
                lowerUrl.contains("mpd") -> "DASH Stream"
                else -> "Web Video File"
            }

            val sniffedMedia = SniffedMedia(
                url = url,
                mimeType = mimeType,
                title = title
            )

            // Safely post callback onto main UI looper without touching WebView object on IO thread
            mainHandler.post {
                onMediaDetected(sniffedMedia)
            }
        }
    }

    fun clearDetected() {
        detectedUrls.clear()
    }
}
