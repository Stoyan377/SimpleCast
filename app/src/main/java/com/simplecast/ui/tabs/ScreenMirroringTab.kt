package com.simplecast.ui.tabs

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.simplecast.mirroring.MiracastLauncher
import com.simplecast.mirroring.ScreenCaptureService
import com.simplecast.ui.theme.LgRedAccent
import com.simplecast.ui.theme.NeonCyan
import com.simplecast.ui.theme.SurfaceDark
import com.simplecast.ui.theme.SurfaceVariantDark

@Composable
fun ScreenMirroringTab() {
    val context = LocalContext.current
    var isMirroringActive by remember { mutableStateOf(false) }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            isMirroringActive = true
            ScreenCaptureService.startService(context)
            MiracastLauncher.openCastSettings(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main Screen Mirroring Control Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                if (isMirroringActive) listOf(LgRedAccent, Color(0xFFFF5252))
                                else listOf(SurfaceVariantDark, NeonCyan.copy(alpha = 0.3f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isMirroringActive) Icons.Default.CastConnected else Icons.Default.ScreenShare,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp),
                        tint = if (isMirroringActive) Color.White else NeonCyan
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = if (isMirroringActive) "Screen Mirroring Active" else "Duplicate Screen to TV",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isMirroringActive)
                        "Your screen capture service is active and streaming live audio/video to TV Screen Share."
                    else
                        "Stream your entire phone screen and audio in real-time using native Miracast / MediaProjection.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (isMirroringActive) {
                    Button(
                        onClick = {
                            isMirroringActive = false
                            ScreenCaptureService.stopService(context)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3D00)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Icon(imageVector = Icons.Default.StopScreenShare, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Stop Mirroring", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = {
                            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LgRedAccent),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Icon(imageVector = Icons.Default.ScreenShare, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Start Screen Mirroring", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { MiracastLauncher.openCastSettings(context) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(imageVector = Icons.Default.CastConnected, contentDescription = null, tint = NeonCyan)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Open Wireless Display Settings", color = NeonCyan)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Guide Instructions Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = NeonCyan
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Wireless Display Setup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                val steps = listOf(
                    "1. Turn on your TV and make sure it is connected to the same Wi-Fi network.",
                    "2. On your TV, launch 'Screen Share' / 'Wireless Display' / 'Miracast'.",
                    "3. Click 'Start Screen Mirroring' above to grant Android MediaProjection permission.",
                    "4. Select your TV from the system wireless display dialog."
                )

                for (step in steps) {
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}
