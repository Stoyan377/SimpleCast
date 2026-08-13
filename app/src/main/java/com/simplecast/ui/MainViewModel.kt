package com.simplecast.ui

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplecast.network.dlna.DlnaController
import com.simplecast.network.dlna.MediaType
import com.simplecast.network.server.LocalHttpServer
import com.simplecast.network.ssdp.DlnaDevice
import com.simplecast.network.ssdp.SsdpDiscoveryManager
import com.simplecast.network.utils.NetworkUtils
import com.simplecast.media.VideoRotationTranscoder
import com.simplecast.service.MediaPlaybackService
import com.simplecast.web.SniffedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlaybackState(
    val isPlaying: Boolean = false,
    val mediaTitle: String = "",
    val mediaUrl: String = "",
    val mediaType: MediaType = MediaType.VIDEO
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val ssdpManager = SsdpDiscoveryManager(application)
    private val dlnaController = DlnaController()
    private val httpServer = LocalHttpServer(application, port = 8080)

    val discoveredDevices: StateFlow<List<DlnaDevice>> = ssdpManager.discoveredDevices
    val isScanning: StateFlow<Boolean> = ssdpManager.isScanning

    private val _selectedDevice = MutableStateFlow<DlnaDevice?>(null)
    val selectedDevice: StateFlow<DlnaDevice?> = _selectedDevice.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _sniffedMediaList = MutableStateFlow<List<SniffedMedia>>(emptyList())
    val sniffedMediaList: StateFlow<List<SniffedMedia>> = _sniffedMediaList.asStateFlow()

    private val _showDevicePicker = MutableStateFlow(false)
    val showDevicePicker: StateFlow<Boolean> = _showDevicePicker.asStateFlow()

    val localIpAddress: String?
        get() = NetworkUtils.getLocalIpAddress(getApplication())

    init {
        try {
            httpServer.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        startDeviceScan()
    }

    fun startDeviceScan() {
        viewModelScope.launch {
            ssdpManager.discoverDevices()
        }
    }

    fun selectDevice(device: DlnaDevice) {
        _selectedDevice.value = device
        _showDevicePicker.value = false
    }

    fun disconnectDevice() {
        val device = _selectedDevice.value
        viewModelScope.launch {
            if (device != null) {
                dlnaController.stop(device.controlUrl)
            }
            _selectedDevice.value = null
            _playbackState.value = PlaybackState()
            httpServer.clearMedia()
            _showDevicePicker.value = false
            MediaPlaybackService.stopService(getApplication())
        }
    }

    fun toggleDevicePicker(show: Boolean) {
        _showDevicePicker.value = show
    }

    fun castLocalMedia(uri: Uri, title: String, isVideo: Boolean) {
        val device = selectedDevice.value
        if (device == null) {
            _showDevicePicker.value = true
            return
        }

        val ip = localIpAddress ?: return
        val mediaId = System.currentTimeMillis().toString()
        val mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE
        val contentResolver = getApplication<Application>().contentResolver
        var inferredMime = contentResolver.getType(uri) ?: if (isVideo) "video/mp4" else "image/jpeg"

        viewModelScope.launch {
            var finalUri = uri
            var resolution: String? = null

            // Android TV / Google TV renderers are picky:
            //  - they ignore the MP4 rotation flag → play videos sideways
            //  - they can't decode HEVC over DLNA → audio only
            // So for Android TV we transcode HEVC and any rotated video to AVC MP4 and
            // bake the rotation into the pixels. Run on Dispatchers.IO – the transcode
            // pipeline is heavy and would freeze the UI otherwise.
            if (isVideo && device.isAndroidTv) {
                val videoInfo = withContext(Dispatchers.IO) { getVideoInfo(uri) }
                if (videoInfo != null) {
                    val needsTranscode = videoInfo.rotation != 0 || videoInfo.isHevc
                    if (needsTranscode) {
                        showToast("Preparing video for ${device.friendlyName}...")
                        val result = withContext(Dispatchers.IO) {
                            VideoRotationTranscoder().transcode(
                                context = getApplication(),
                                contentUri = uri,
                                rotationDegrees = videoInfo.rotation
                            )
                        }
                        if (result != null) {
                            finalUri = Uri.fromFile(result.file)
                            inferredMime = "video/mp4"
                            resolution = "${result.width}x${result.height}"
                        } else {
                            showToast("Conversion failed – casting original")
                            resolution = "${videoInfo.width}x${videoInfo.height}"
                        }
                    } else {
                        resolution = "${videoInfo.width}x${videoInfo.height}"
                    }
                }
            }

            httpServer.registerMedia(mediaId, finalUri)
            val localStreamUrl = "http://$ip:8080/media/$mediaId"

            val success = castUrl(
                device, localStreamUrl, title, mediaType, inferredMime,
                resolution = resolution
            )

            if (success) {
                dlnaController.play(device.controlUrl)
                _playbackState.value = PlaybackState(
                    isPlaying = true,
                    mediaTitle = title,
                    mediaUrl = localStreamUrl,
                    mediaType = mediaType
                )
                MediaPlaybackService.startService(getApplication(), title)
            } else {
                showToast("Failed to send media to TV")
            }
        }
    }

    fun castWebMedia(sniffedMedia: SniffedMedia) {
        val device = selectedDevice.value
        if (device == null) {
            _showDevicePicker.value = true
            return
        }

        val ip = localIpAddress
        if (ip == null) {
            showToast("Cannot determine local IP address")
            return
        }

        viewModelScope.launch {
            showToast("Casting to ${device.friendlyName}...")

            // First stop any existing playback
            dlnaController.stop(device.controlUrl)

            // Proxy the web stream through our local HTTP server
            // This converts HTTPS → HTTP which webOS / Android TV DLNA can handle
            val encodedUrl = java.net.URLEncoder.encode(sniffedMedia.url, "UTF-8")
            var proxyUrl = "http://$ip:8080/proxy/$encodedUrl"

            // Attach cookies if captured
            if (!sniffedMedia.cookies.isNullOrEmpty()) {
                val encodedCookies = java.net.URLEncoder.encode(sniffedMedia.cookies, "UTF-8")
                proxyUrl += "?cookie=$encodedCookies"
            }

            // Normalize the mime the proxy actually serves so the DIDL protocolInfo matches.
            val dlnaMime = when {
                sniffedMedia.mimeType.contains("mpegURL") || sniffedMedia.mimeType.contains("m3u8") ->
                    "application/vnd.apple.mpegurl"
                sniffedMedia.mimeType.contains("dash") || sniffedMedia.mimeType.contains("mpd") ->
                    "application/dash+xml"
                sniffedMedia.mimeType.contains("mp2t") || sniffedMedia.mimeType.contains("mpeg-ts") ->
                    "video/vnd.dlna.mpeg-tts"
                else -> sniffedMedia.mimeType
            }

            // Device-aware strategy ordering. Android TV / Google TV reject the strict
            // LG DLNA profile (AVC_MP4_MP_SD...) with "Format Not Supported", so they
            // get the mime-aware protocolInfo first.
            var success = castUrl(device, proxyUrl, sniffedMedia.title, MediaType.VIDEO, dlnaMime)

            // Last resort – try the original URL directly (only if it's plain HTTP)
            if (!success && !sniffedMedia.url.startsWith("https")) {
                success = castUrl(device, sniffedMedia.url, sniffedMedia.title, MediaType.VIDEO, dlnaMime)
            }

            if (success) {
                val playSuccess = dlnaController.play(device.controlUrl)
                if (playSuccess) {
                    _playbackState.value = PlaybackState(
                        isPlaying = true,
                        mediaTitle = sniffedMedia.title,
                        mediaUrl = proxyUrl,
                        mediaType = MediaType.VIDEO
                    )
                    showToast("Playing on ${device.friendlyName}")
                    MediaPlaybackService.startService(getApplication(), sniffedMedia.title)
                } else {
                    showToast("URL accepted but playback failed – stream may be DRM-protected")
                }
            } else {
                showToast("TV rejected this stream – try another URL or a direct MP4/M3U8 link")
            }
        }
    }

    /**
     * Picks the DLNA SetAVTransportURI strategy based on the device type.
     *
     * LG webOS tolerates the strict LG DIDL metadata (AVC_MP4_MP_SD_AAC_MULT5) which makes it
     * play smoothly. Android TV / Google TV / Sony / Samsung reject that strict profile with
     * "Format Not Supported", so for them we first try the universal simplified protocolInfo
     * that matches what the local server actually streams.
     */
    private suspend fun castUrl(
        device: DlnaDevice,
        mediaUrl: String,
        title: String,
        mediaType: MediaType,
        mimeType: String,
        resolution: String? = null
    ): Boolean {
        val lowerMime = mimeType.lowercase()

        // Match the upnp:class to the actual content. Defaulting to videoItem
        // (as before) made image casts fail on Android TV with "unsupported file".
        val upnpClass = when {
            mediaType == MediaType.IMAGE || lowerMime.contains("image") ->
                "object.item.imageItem.photo"
            mediaType == MediaType.AUDIO || lowerMime.contains("audio") ->
                "object.item.audioItem.musicTrack"
            else -> "object.item.videoItem"
        }

        // protocolInfo must reflect what the local server / proxy really serves,
        // otherwise the renderer answers "Format Not Supported".
        val protocolInfo = when {
            lowerMime.contains("mpegurl") || lowerMime.contains("m3u8") ->
                "http-get:*:application/vnd.apple.mpegurl:*"
            lowerMime.contains("mpd") || lowerMime.contains("dash") ->
                "http-get:*:application/dash+xml:*"
            lowerMime.contains("webm") -> "http-get:*:video/webm:*"
            lowerMime.contains("mp2t") || lowerMime.contains("mpeg-ts") ||
                lowerMime.contains("mpeg-tts") ->
                "http-get:*:video/vnd.dlna.mpeg-tts:*"
            lowerMime.contains("mpeg") ->
                "http-get:*:video/mpeg:*"
            lowerMime.contains("audio") -> "http-get:*:audio/mpeg:*"
            lowerMime.contains("png") -> "http-get:*:image/png:*"
            lowerMime.contains("jpeg") || lowerMime.contains("jpg") ->
                "http-get:*:image/jpeg:*"
            else -> "http-get:*:video/mp4:*"
        }

        val strict: suspend () -> Boolean = {
            dlnaController.setAvTransportUri(
                controlUrl = device.controlUrl,
                mediaUrl = mediaUrl,
                title = title,
                mediaType = mediaType,
                mimeType = mimeType
            )
        }
        val custom: suspend () -> Boolean = {
            dlnaController.setAvTransportUriCustom(
                controlUrl = device.controlUrl,
                mediaUrl = mediaUrl,
                title = title,
                upnpClass = upnpClass,
                protocolInfo = protocolInfo,
                resolution = resolution
            )
        }
        val universal: suspend () -> Boolean = {
            dlnaController.setAvTransportUriUniversal(
                controlUrl = device.controlUrl,
                mediaUrl = mediaUrl,
                title = title,
                mimeType = mimeType
            )
        }
        val raw: suspend () -> Boolean = {
            dlnaController.setAvTransportUriRaw(device.controlUrl, mediaUrl)
        }

        val isHlsOrDash = lowerMime.contains("mpegurl") || lowerMime.contains("m3u8") ||
            lowerMime.contains("mpd") || lowerMime.contains("dash")

        // HLS/DASH web streams must be advertised with their exact mime or the
        // renderer answers "Format Not Supported", so try the mime-aware custom
        // strategy first. For Android TV / Google TV the strict LG profile
        // (AVC_MP4_MP_SD_AAC_MULT5) forces SD scaling – that is what stretched
        // videos and HEVC audio-only on the Philips – so they also get the
        // mime-aware custom strategy first. LG webOS keeps the strict profile
        // which plays full-screen there.
        val strategies = if (isHlsOrDash || device.isAndroidTv) {
            listOf(custom, universal, strict, raw)
        } else {
            listOf(strict, custom, universal, raw)
        }

        for (strategy in strategies) {
            if (strategy()) return true
        }
        return false
    }

    private data class VideoInfo(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val isHevc: Boolean
    )

    private fun getVideoInfo(uri: Uri): VideoInfo? {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(getApplication(), uri)
            val rotation = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )?.toIntOrNull() ?: 0
            val width = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            val mime = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE
            )?.lowercase() ?: ""
            retriever.release()
            val isHevc = mime.contains("hevc") || mime.contains("h265")
            VideoInfo(width, height, rotation, isHevc)
        } catch (e: Exception) {
            null
        }
    }

    fun togglePlayPause() {
        val device = selectedDevice.value ?: return
        val currentState = playbackState.value

        viewModelScope.launch {
            if (currentState.isPlaying) {
                if (dlnaController.pause(device.controlUrl)) {
                    _playbackState.value = currentState.copy(isPlaying = false)
                }
            } else {
                if (dlnaController.play(device.controlUrl)) {
                    _playbackState.value = currentState.copy(isPlaying = true)
                    MediaPlaybackService.startService(getApplication(), currentState.mediaTitle)
                }
            }
        }
    }

    fun stopPlayback() {
        val device = selectedDevice.value ?: return
        viewModelScope.launch {
            if (dlnaController.stop(device.controlUrl)) {
                _playbackState.value = PlaybackState()
                MediaPlaybackService.stopService(getApplication())
            }
        }
    }

    fun onMediaSniffed(media: SniffedMedia) {
        val currentList = _sniffedMediaList.value.toMutableList()
        if (currentList.none { it.url == media.url }) {
            currentList.add(0, media)
            _sniffedMediaList.value = currentList
        }
    }

    fun clearSniffedMedia() {
        _sniffedMediaList.value = emptyList()
    }

    private fun showToast(message: String) {
        val app = getApplication<Application>()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCleared() {
        super.onCleared()
        httpServer.stop()
    }
}
