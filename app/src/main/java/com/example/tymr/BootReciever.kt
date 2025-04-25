package com.example.tymr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.tymr.service.UpdateWorker

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Check if notifications are enabled
            val prefs = context.getSharedPreferences("EventPrefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("enable_notification", true)) {
                // Re-schedule our daily updates
                UpdateWorker.schedulePeriodicWork(context)
            }
        }
    }
}