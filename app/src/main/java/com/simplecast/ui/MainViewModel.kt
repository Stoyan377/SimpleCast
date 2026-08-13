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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
        val inferredMime = contentResolver.getType(uri) ?: if (isVideo) "video/mp4" else "image/jpeg"

        viewModelScope.launch {
            var finalUri = uri

            // Android TV / Google TV renderers often ignore the MP4 rotation flag and play the
            // video sideways. Transcode once so the rotation is baked into the pixels.
            if (isVideo && !device.isLgWebOs) {
                val rotation = getVideoRotation(uri)
                if (rotation != 0) {
                    showToast("Rotating video for ${device.friendlyName}...")
                    val result = VideoRotationTranscoder().transcode(
                        context = getApplication(),
                        contentUri = uri,
                        rotationDegrees = rotation
                    )
                    if (result != null) {
                        finalUri = Uri.fromFile(result.file)
                    } else {
                        showToast("Rotation failed – casting original")
                    }
                }
            }

            httpServer.registerMedia(mediaId, finalUri)
            val localStreamUrl = "http://$ip:8080/media/$mediaId"

            val success = castUrl(device, localStreamUrl, title, mediaType, inferredMime)

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
                else -> sniffedMedia.mimeType
            }

            // Device-aware strategy ordering. Android TV / Google TV reject the strict
            // LG DLNA profile (AVC_MP4_MP_SD...) with "Format Not Supported", so they
            // get the simple universal protocolInfo first.
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
        mimeType: String
    ): Boolean {
        val lowerMime = mimeType.lowercase()
        val protocolInfo = when {
            lowerMime.contains("mpegurl") || lowerMime.contains("m3u8") ->
                "http-get:*:application/vnd.apple.mpegurl:*"
            lowerMime.contains("mpd") || lowerMime.contains("dash") ->
                "http-get:*:application/dash+xml:*"
            lowerMime.contains("webm") -> "http-get:*:video/webm:*"
            lowerMime.contains("mp2t") || lowerMime.contains("mpeg") ->
                "http-get:*:video/mpeg:*"
            lowerMime.contains("audio") -> "http-get:*:audio/mpeg:*"
            lowerMime.contains("png") -> "http-get:*:image/png:*"
            lowerMime.contains("jpeg") || lowerMime.contains("jpg") ->
                "http-get:*:image/jpeg:*"
            else -> "http-get:*:video/mp4:*"
        }

        val lgStrategies: List<suspend () -> Boolean> = listOf(
            {
                dlnaController.setAvTransportUri(
                    controlUrl = device.controlUrl,
                    mediaUrl = mediaUrl,
                    title = title,
                    mediaType = mediaType,
                    mimeType = mimeType
                )
            },
            {
                dlnaController.setAvTransportUriUniversal(
                    controlUrl = device.controlUrl,
                    mediaUrl = mediaUrl,
                    title = title,
                    mimeType = mimeType
                )
            },
            { dlnaController.setAvTransportUriRaw(device.controlUrl, mediaUrl) }
        )

        val androidStrategies: List<suspend () -> Boolean> = listOf(
            {
                dlnaController.setAvTransportUriCustom(
                    controlUrl = device.controlUrl,
                    mediaUrl = mediaUrl,
                    title = title,
                    protocolInfo = protocolInfo
                )
            },
            {
                dlnaController.setAvTransportUriUniversal(
                    controlUrl = device.controlUrl,
                    mediaUrl = mediaUrl,
                    title = title,
                    mimeType = mimeType
                )
            },
            { dlnaController.setAvTransportUriRaw(device.controlUrl, mediaUrl) }
        )

        val strategies = if (device.isLgWebOs) lgStrategies else androidStrategies
        for (strategy in strategies) {
            if (strategy()) return true
        }
        return false
    }

    private fun getVideoRotation(uri: Uri): Int {
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(getApplication(), uri)
            val value = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )
            retriever.release()
            value?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
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
