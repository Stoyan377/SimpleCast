package com.simplecast.ui.tabs

import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.simplecast.network.ssdp.DlnaDevice
import com.simplecast.ui.theme.LgRedAccent
import com.simplecast.ui.theme.NeonCyan
import com.simplecast.ui.theme.SurfaceDark
import com.simplecast.ui.theme.SurfaceVariantDark
import com.simplecast.web.MediaSnifferJSBridge
import com.simplecast.web.MediaSnifferWebViewClient
import com.simplecast.web.SniffedMedia
import java.io.ByteArrayInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebBrowserTab(
    selectedDevice: DlnaDevice?,
    sniffedMediaList: List<SniffedMedia>,
    onMediaSniffed: (SniffedMedia) -> Unit,
    onCastWebMedia: (SniffedMedia) -> Unit,
    onClearSniffed: () -> Unit
) {
    val defaultUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    var urlFieldValue by remember { mutableStateOf(TextFieldValue(defaultUrl)) }
    var currentWebUrl by remember { mutableStateOf(defaultUrl) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var showSniffedSheet by remember { mutableStateOf(false) }

    val bookmarks = listOf(
        "Sample HLS" to "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    )

    // Ad-blocking domains
    val adHosts = remember {
        setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "facebook.net",
            "facebook.com/tr", "adservice.google.com", "pagead2.googlesyndication.com",
            "ads.yahoo.com", "ad.doubleclick.net", "amazon-adsystem.com",
            "moatads.com", "scorecardresearch.com", "quantserve.com",
            "adsrvr.org", "adnxs.com", "rubiconproject.com", "pubmatic.com",
            "casalemedia.com", "taboola.com", "outbrain.com", "criteo.com",
            "serving-sys.com", "smartadserver.com", "smaato.net",
            "advertising.com", "adcolony.com", "unity3d.com/ads",
            "popads.net", "popcash.net", "propellerads.com",
            "adf.ly", "exoclick.com", "trafficjunky.com",
            "zedo.com", "revcontent.com", "mgid.com",
            "bidswitch.net", "openx.net", "indexexchange.com",
            "sharethrough.com", "media.net", "inmobi.com",
            "imasdk.googleapis.com", "tpc.googlesyndication.com",
            "securepubads.g.doubleclick.net"
        )
    }

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
                        value = urlFieldValue,
                        onValueChange = { urlFieldValue = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    // Select all text when the field gains focus
                                    urlFieldValue = urlFieldValue.copy(
                                        selection = TextRange(0, urlFieldValue.text.length)
                                    )
                                }
                            },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = SurfaceVariantDark,
                            unfocusedContainerColor = SurfaceVariantDark,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        placeholder = { Text("Enter URL or paste M3U8 link...") }
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = {
                            val text = urlFieldValue.text
                            val formatted = if (!text.startsWith("http://") && !text.startsWith("https://")) {
                                "https://$text"
                            } else text
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
                                urlFieldValue = TextFieldValue(link)
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
                    val snifferClient = MediaSnifferWebViewClient { sniffedMedia ->
                        onMediaSniffed(sniffedMedia)
                    }

                    // Create an ad-blocking wrapper that delegates to the sniffer client
                    val adBlockClient = object : android.webkit.WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: ""
                            val reqHost = request?.url?.host ?: ""

                            // Block known ad domains
                            if (adHosts.any { adHost -> reqHost.contains(adHost) }) {
                                return WebResourceResponse(
                                    "text/plain", "utf-8",
                                    ByteArrayInputStream("".toByteArray())
                                )
                            }

                            // Also block common ad URL patterns
                            val lowerUrl = reqUrl.lowercase()
                            if (lowerUrl.contains("/ads/") || lowerUrl.contains("/ad/") ||
                                lowerUrl.contains("banner") || lowerUrl.contains("pop-up") ||
                                lowerUrl.contains("interstitial") || lowerUrl.contains("prebid") ||
                                lowerUrl.contains("adserver")) {
                                return WebResourceResponse(
                                    "text/plain", "utf-8",
                                    ByteArrayInputStream("".toByteArray())
                                )
                            }

                            // Delegate to sniffer client to detect media
                            if (request != null) {
                                snifferClient.checkAndNotifyMedia(reqUrl, "Web Video", request.requestHeaders ?: emptyMap())
                            }
                            return snifferClient.shouldInterceptRequest(view, request)
                        }

                        override fun onLoadResource(view: WebView?, url: String?) {
                            snifferClient.onLoadResource(view, url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            snifferClient.onPageFinished(view, url)
                            // Update the address bar with the actual loaded URL
                            if (url != null) {
                                urlFieldValue = TextFieldValue(url)
                                currentWebUrl = url
                            }
                        }
                    }

                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = true
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        addJavascriptInterface(
                            MediaSnifferJSBridge { jsUrl, jsTitle ->
                                snifferClient.checkAndNotifyMedia(jsUrl, jsTitle)
                            },
                            "AndroidMediaSniffer"
                        )

                        webViewClient = adBlockClient
                        loadUrl(currentWebUrl)
                        webViewInstance = this
                    }
                },
                update = { _ -> },
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
                            text = "${sniffedMediaList.size} Video Streams Found",
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
                            text = "Tap the recommended stream to cast to LG TV",
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

                // Sort streams: recommended first
                val sortedList = remember(sniffedMediaList) {
                    sniffedMediaList.sortedByDescending { it.isRecommended }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    items(sortedList) { media ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (media.isRecommended) SurfaceVariantDark else SurfaceDark
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onCastWebMedia(media)
                                    showSniffedSheet = false
                                }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                if (media.isRecommended) {
                                    Surface(
                                        color = LgRedAccent,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    ) {
                                        Text(
                                            text = "RECOMMENDED STREAM",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayCircle,
                                        contentDescription = null,
                                        tint = if (media.isRecommended) LgRedAccent else NeonCyan,
                                        modifier = Modifier.size(36.dp)
                                    )

                                    Spacer(modifier = Modifier.width(14.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = media.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )

                                        Spacer(modifier = Modifier.height(2.dp))

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Surface(
                                                color = SurfaceVariantDark,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = media.quality,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = NeonCyan,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "${media.domain} • ${media.mimeType}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1
                                            )
                                        }
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
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
