package com.simplecast.web

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

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        request?.url?.toString()?.let { url ->
            checkAndNotifyMedia(url, view?.title ?: "Web Video")
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        url?.let {
            checkAndNotifyMedia(it, view?.title ?: "Web Video")
        }
        super.onLoadResource(view, url)
    }

    private fun checkAndNotifyMedia(url: String, currentTitle: String) {
        val lowerUrl = url.lowercase(Locale.ROOT)

        // Ignore common static analytics or non-video assets
        if (lowerUrl.contains(".js") || lowerUrl.contains(".css") || lowerUrl.contains(".png") || lowerUrl.contains(".jpg")) {
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

            val sniffedMedia = SniffedMedia(
                url = url,
                mimeType = mimeType,
                title = currentTitle
            )

            viewPost {
                onMediaDetected(sniffedMedia)
            }
        }
    }

    private fun viewPost(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            action()
        }
    }

    fun clearDetected() {
        detectedUrls.clear()
    }
}
