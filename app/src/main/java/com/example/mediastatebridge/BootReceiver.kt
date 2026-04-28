package com.example.mediastatebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The NotificationListenerService is auto-rebound by the OS after boot if
 * Notification Access was granted. This receiver just nudges it so config is
 * re-read and the MQTT connection is re-established promptly.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MediaListenerService.requestRestart(context)
    }
}
