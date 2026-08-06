package com.simplecast.mirroring

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

object MiracastLauncher {

    /**
     * Opens system Wireless Display / Cast settings dialog to pair with LG Screen Share directly.
     */
    fun openCastSettings(context: Context) {
        val intentsToTry = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            Intent("com.samsung.wfd.LAUNCH_WFD_PICKER"),
            Intent("com.lg.intent.action.SCREENSHARE"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )

        var launched = false
        for (intent in intentsToTry) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                launched = true
                break
            } catch (e: ActivityNotFoundException) {
                // Try next system intent
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!launched) {
            Toast.makeText(context, "Could not open Wireless Display settings automatically. Please open Cast/Screen Share in phone Settings.", Toast.LENGTH_LONG).show()
        }
    }
}
