package com.example.tymr.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.tymr.MainActivity
import com.example.tymr.R
import com.example.tymr.data.CalendarEvent
import com.example.tymr.data.CalendarFetcher
import com.example.tymr.data.CalendarParser
import com.example.tymr.ui.CalendarWidgetProvider
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import androidx.core.content.edit
import android.appwidget.AppWidgetManager
import android.content.ComponentName

class ForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val calendarFetcher = CalendarFetcher()
    private val calendarParser = CalendarParser()
    private var updateJob: Job? = null

    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "CalendarServiceChannel"
        private const val UPDATE_INTERVAL = 60_000L // 1 minute
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.loading_events)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start the periodic update job
        startUpdateJob()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        updateJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val name = getString(R.string.notification_channel_name)
        val descriptionText = getString(R.string.notification_channel_description)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun startUpdateJob() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            while (isActive) {
                updateCalendarEvents()
                delay(UPDATE_INTERVAL)
            }
        }
    }

    private suspend fun updateCalendarEvents() {
        val prefs = getSharedPreferences("SourcePrefs", Context.MODE_PRIVATE)
        val sourceUrls = prefs.getStringSet("source_urls", emptySet()) ?: emptySet()
        val activeUrls = sourceUrls.filter { url ->
            prefs.getBoolean("active_$url", false)
        }

        if (activeUrls.isEmpty()) {
            updateNotificationAndWidget(null)
            return
        }

        val allEvents = mutableListOf<CalendarEvent>()

        for (url in activeUrls) {
            try {
                val result = calendarFetcher.fetchCalendarData(url)
                if (result.isSuccess) {
                    val icsContent = result.getOrThrow()
                    val events = calendarParser.parseIcsContent(icsContent)
                    allEvents.addAll(events)
                }
            } catch (e: Exception) {
                // Log error
            }
        }

        val nextOrCurrentEvent = if (allEvents.isNotEmpty()) {
            calendarParser.findNextOrCurrentEvent(allEvents)
        } else {
            null
        }

        updateNotificationAndWidget(nextOrCurrentEvent)
    }

    private fun updateNotificationAndWidget(event: CalendarEvent?) {
        val eventPrefs = getSharedPreferences("EventPrefs", Context.MODE_PRIVATE)
        val showDetails = eventPrefs.getBoolean("show_details", true)
        val showLocation = eventPrefs.getBoolean("show_location", true)

        if (event == null) {
            eventPrefs.edit() {
                putString("current_title", getString(R.string.no_events))
                    .putString("current_time", "")
                    .putString("event_status", "")
                    .putLong("time_remaining", -1)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(getString(R.string.no_events)))
        } else {
            val now = Date()
            val isOngoing = event.isOngoing()

            val timeRemaining = if (isOngoing) {
                event.timeUntilEnd()
            } else {
                event.timeUntilStart()
            }

            val status = if (isOngoing) {
                getString(R.string.ends_in)
            } else {
                getString(R.string.starts_in)
            }

            val startTimeStr = dateFormat.format(event.startTime)
            val endTimeStr = dateFormat.format(event.endTime)
            val timeStr = "$startTimeStr - $endTimeStr"

            eventPrefs.edit() {
                putString("current_title", event.summary)
                    .putString("current_time", timeStr)
                    .putString("event_status", status)
                    .putLong("time_remaining", timeRemaining)
            }

            // Format time remaining for notification
            val hours = TimeUnit.MILLISECONDS.toHours(timeRemaining)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(timeRemaining) % 60
            val timeRemainingStr = when {
                hours > 0 -> "$hours h $minutes min"
                else -> "$minutes min"
            }

            var notificationText = "${event.summary} - $status $timeRemainingStr"
            if (showDetails) {
                notificationText += "\n$timeStr"
                if (showLocation && !event.location.isNullOrEmpty()) {
                    notificationText += " @ ${event.location}"
                }
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(notificationText))
        }

        // Update widget
        val widgetIntent = Intent(this, CalendarWidgetProvider::class.java)
        widgetIntent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        val widgetManager = AppWidgetManager.getInstance(this)
        val widgetIds = widgetManager.getAppWidgetIds(
            ComponentName(this, CalendarWidgetProvider::class.java)
        )
        widgetIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
        sendBroadcast(widgetIntent)
    }
}