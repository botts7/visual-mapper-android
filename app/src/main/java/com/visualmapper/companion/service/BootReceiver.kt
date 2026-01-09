package com.visualmapper.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.visualmapper.companion.security.SecurePreferences

/**
 * Boot Receiver
 *
 * Starts MQTT service when device boots (if configured).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.i(TAG, "Device boot completed")

        // Check if MQTT is configured
        val prefs = SecurePreferences(context)
        val broker = prefs.mqttBroker

        if (broker != null) {
            Log.i(TAG, "Starting MQTT service on boot")
            // Note: The MqttService would need to be started here
            // This is a placeholder for the actual implementation
        } else {
            Log.d(TAG, "MQTT not configured, skipping auto-start")
        }
    }
}
