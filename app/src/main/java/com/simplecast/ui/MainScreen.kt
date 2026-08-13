package com.simplecast.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplecast.ui.components.DeviceDiscoveryBottomSheet
import com.simplecast.ui.tabs.GalleryTab
import com.simplecast.ui.tabs.ScreenMirroringTab
import com.simplecast.ui.tabs.WebBrowserTab
import com.simplecast.ui.theme.LgRedAccent
import com.simplecast.ui.theme.NeonCyan
import com.simplecast.ui.theme.SurfaceDark
import com.simplecast.ui.theme.SurfaceVariantDark

enum class MainTab(val title: String) {
    GALLERY("Gallery"),
    WEB("Web Cast"),
    MIRROR("Screen Share")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    var selectedTab by remember { mutableStateOf(MainTab.GALLERY) }

    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val sniffedMediaList by viewModel.sniffedMediaList.collectAsState()
    val showDevicePicker by viewModel.showDevicePicker.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Simple Cast",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "IP: ${viewModel.localIpAddress ?: "Connecting..."}",
                            style = MaterialTheme.typography.labelMedium,
                            color = NeonCyan
                        )
                    }
                },
                actions = {
                    // Device Connection Badge
                    Surface(
                        onClick = { viewModel.toggleDevicePicker(true) },
                        shape = RoundedCornerShape(20.dp),
                        color = if (selectedDevice != null) LgRedAccent.copy(alpha = 0.2f) else SurfaceVariantDark,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (selectedDevice != null) LgRedAccent else Color.Gray)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = selectedDevice?.friendlyName ?: "Connect TV",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedDevice != null) LgRedAccent else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = null,
                                tint = if (selectedDevice != null) LgRedAccent else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        bottomBar = {
            Column {
                // Persistent DLNA Playback Controls Bar
                AnimatedVisibility(visible = playbackState.mediaTitle.isNotEmpty()) {
                    Surface(
                        color = SurfaceDark,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = LgRedAccent
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = playbackState.mediaTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = if (playbackState.isPlaying) "Playing on ${selectedDevice?.friendlyName ?: "Smart TV"}" else "Paused",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }

                            Row {
                                IconButton(onClick = { viewModel.togglePlayPause() }) {
                                    Icon(
                                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = NeonCyan
                                    )
                                }
                                IconButton(onClick = { viewModel.stopPlayback() }) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Stop",
                                        tint = Color(0xFFFF5252)
                                    )
                                }
                            }
                        }
                    }
                }

                // Main Navigation Bar
                NavigationBar(containerColor = SurfaceDark) {
                    NavigationBarItem(
                        selected = selectedTab == MainTab.GALLERY,
                        onClick = { selectedTab = MainTab.GALLERY },
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                        label = { Text("Gallery") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = LgRedAccent,
                            selectedTextColor = LgRedAccent,
                            indicatorColor = LgRedAccent.copy(alpha = 0.15f)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == MainTab.WEB,
                        onClick = { selectedTab = MainTab.WEB },
                        icon = { Icon(Icons.Default.Language, contentDescription = null) },
                        label = { Text("Web Cast") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonCyan,
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan.copy(alpha = 0.15f)
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == MainTab.MIRROR,
                        onClick = { selectedTab = MainTab.MIRROR },
                        icon = { Icon(Icons.Default.ScreenShare, contentDescription = null) },
                        label = { Text("Screen Share") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = LgRedAccent,
                            selectedTextColor = LgRedAccent,
                            indicatorColor = LgRedAccent.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                MainTab.GALLERY -> GalleryTab(
                    selectedDevice = selectedDevice,
                    localIpAddress = viewModel.localIpAddress,
                    onCastMedia = { uri, title, isVideo ->
                        viewModel.castLocalMedia(uri, title, isVideo)
                    },
                    onRequestDevicePicker = { viewModel.toggleDevicePicker(true) }
                )
                MainTab.WEB -> WebBrowserTab(
                    selectedDevice = selectedDevice,
                    sniffedMediaList = sniffedMediaList,
                    onMediaSniffed = { viewModel.onMediaSniffed(it) },
                    onCastWebMedia = { viewModel.castWebMedia(it) },
                    onClearSniffed = { viewModel.clearSniffedMedia() }
                )
                MainTab.MIRROR -> ScreenMirroringTab()
            }
        }
    }

    // Device Discovery Dialog / Bottom Sheet
    if (showDevicePicker) {
        DeviceDiscoveryBottomSheet(
            discoveredDevices = discoveredDevices,
            selectedDevice = selectedDevice,
            isScanning = isScanning,
            onScanRequested = { viewModel.startDeviceScan() },
            onDeviceSelected = { device -> viewModel.selectDevice(device) },
            onDisconnectRequested = { viewModel.disconnectDevice() },
            onDismiss = { viewModel.toggleDevicePicker(false) }
        )
    }
}
