package com.simplecast.ui.tabs

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.viewinterop.AndroidView
import com.simplecast.network.ssdp.DlnaDevice
import com.simplecast.ui.theme.LgRedAccent
import com.simplecast.ui.theme.NeonCyan
import com.simplecast.ui.theme.SurfaceDark
import com.simplecast.ui.theme.SurfaceVariantDark
import com.simplecast.web.MediaSnifferWebViewClient
import com.simplecast.web.SniffedMedia

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebBrowserTab(
    selectedDevice: DlnaDevice?,
    sniffedMediaList: List<SniffedMedia>,
    onMediaSniffed: (SniffedMedia) -> Unit,
    onCastWebMedia: (SniffedMedia) -> Unit,
    onClearSniffed: () -> Unit
) {
    var urlText by remember { mutableStateOf("https://m.youtube.com") }
    var currentWebUrl by remember { mutableStateOf("https://m.youtube.com") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var showSniffedSheet by remember { mutableStateOf(false) }

    val bookmarks = listOf(
        "YouTube" to "https://m.youtube.com",
        "Vimeo" to "https://vimeo.com/watch",
        "Twitch" to "https://m.twitch.tv",
        "Sample HLS" to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Address Bar & Controls
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(onClick = { webViewInstance?.goBack() }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = NeonCyan)
                    }

                    IconButton(onClick = { webViewInstance?.reload() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh", tint = NeonCyan)
                    }

                    TextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = SurfaceVariantDark,
                            unfocusedContainerColor = SurfaceVariantDark,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        placeholder = { Text("Enter URL...") }
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = {
                            val formatted = if (!urlText.startsWith("http://") && !urlText.startsWith("https://")) {
                                "https://$urlText"
                            } else urlText
                            currentWebUrl = formatted
                            webViewInstance?.loadUrl(formatted)
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(LgRedAccent)
                    ) {
                        Icon(imageVector = Icons.Default.ArrowForward, contentDescription = "Go", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Bookmarks Row
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(bookmarks) { (name, link) ->
                        FilterChip(
                            selected = currentWebUrl == link,
                            onClick = {
                                urlText = link
                                currentWebUrl = link
                                webViewInstance?.loadUrl(link)
                            },
                            label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LgRedAccent,
                                containerColor = SurfaceVariantDark
                            )
                        )
                    }
                }
            }
        }

        // WebView & Sniffer Floating Action Button Box
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = true

                        webViewClient = MediaSnifferWebViewClient { sniffedMedia ->
                            onMediaSniffed(sniffedMedia)
                        }

                        loadUrl(currentWebUrl)
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    // Keep instance reference updated
                },
                modifier = Modifier.fillMaxSize()
            )

            // Floating Media Sniffer Badge
            if (sniffedMediaList.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showSniffedSheet = true },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Cast,
                            contentDescription = null,
                            tint = Color.White
                        )
                    },
                    text = {
                        Text(
                            text = "${sniffedMediaList.size} Direct Videos Sniffed",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    },
                    containerColor = LgRedAccent,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                )
            }
        }
    }

    // Sniffed Media List Bottom Sheet
    if (showSniffedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSniffedSheet = false },
            containerColor = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Detected Video Streams",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tap any stream to cast to LG TV",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    TextButton(onClick = {
                        onClearSniffed()
                        showSniffedSheet = false
                    }) {
                        Text("Clear All", color = LgRedAccent)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 340.dp)
                ) {
                    items(sniffedMediaList) { media ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onCastWebMedia(media)
                                    showSniffedSheet = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = NeonCyan,
                                    modifier = Modifier.size(36.dp)
                                )

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = media.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${media.mimeType} • ${media.url.take(45)}...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Icon(
                                    imageVector = Icons.Default.Cast,
                                    contentDescription = "Cast",
                                    tint = LgRedAccent
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
