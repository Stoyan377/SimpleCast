package com.simplecast.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simplecast.network.dlna.DlnaController
import com.simplecast.network.dlna.MediaType
import com.simplecast.network.server.LocalHttpServer
import com.simplecast.network.ssdp.DlnaDevice
import com.simplecast.network.ssdp.SsdpDiscoveryManager
import com.simplecast.network.utils.NetworkUtils
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
        httpServer.registerMedia(mediaId, uri)

        val localStreamUrl = "http://$ip:8080/media/$mediaId"
        val mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE
        val mimeType = if (isVideo) "video/mp4" else "image/jpeg"

        viewModelScope.launch {
            val success = dlnaController.setAvTransportUri(
                controlUrl = device.controlUrl,
                mediaUrl = localStreamUrl,
                title = title,
                mediaType = mediaType,
                mimeType = mimeType
            )
            if (success) {
                dlnaController.play(device.controlUrl)
                _playbackState.value = PlaybackState(
                    isPlaying = true,
                    mediaTitle = title,
                    mediaUrl = localStreamUrl,
                    mediaType = mediaType
                )
            }
        }
    }

    fun castWebMedia(sniffedMedia: SniffedMedia) {
        val device = selectedDevice.value
        if (device == null) {
            _showDevicePicker.value = true
            return
        }

        viewModelScope.launch {
            val success = dlnaController.setAvTransportUri(
                controlUrl = device.controlUrl,
                mediaUrl = sniffedMedia.url,
                title = sniffedMedia.title,
                mediaType = MediaType.VIDEO,
                mimeType = sniffedMedia.mimeType
            )
            if (success) {
                dlnaController.play(device.controlUrl)
                _playbackState.value = PlaybackState(
                    isPlaying = true,
                    mediaTitle = sniffedMedia.title,
                    mediaUrl = sniffedMedia.url,
                    mediaType = MediaType.VIDEO
                )
            }
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
                }
            }
        }
    }

    fun stopPlayback() {
        val device = selectedDevice.value ?: return
        viewModelScope.launch {
            if (dlnaController.stop(device.controlUrl)) {
                _playbackState.value = PlaybackState()
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

    override fun onCleared() {
        super.onCleared()
        httpServer.stop()
    }
}
