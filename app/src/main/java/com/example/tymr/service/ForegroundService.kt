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
        private const val UPDATE_INTERVAL = 60_000L // 1 minute for UI updates
        private const val ONE_DAY_MILLIS = 24 * 60 * 60 * 1000L // 24 hours in milliseconds
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            createNotification(
                title = getString(R.string.app_name),
                content = getString(R.string.loading_events)
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle the intent action
        if (intent?.action == "FORCE_UPDATE") {
            val url = intent.getStringExtra("url")
            if (url != null) {
                serviceScope.launch {
                    // Force update for a specific URL
                    forceUpdateCalendar(url)
                }
            } else {
                // Force update for all active URLs
                startUpdateJob(true)
            }
        } else {
            // Regular update
            startUpdateJob(false)
        }
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

    private fun createNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun startUpdateJob(forceUpdate: Boolean) {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            // First update
            updateCalendarEvents(forceUpdate)

            // Then schedule regular UI updates
            while (isActive) {
                // This will only update the UI with existing data, not fetch new data
                updateNotificationAndWidgetFromCache()
                delay(UPDATE_INTERVAL)
            }
        }
    }

    private suspend fun updateCalendarEvents(forceUpdate: Boolean) {
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
        val lastUpdatePrefs = getSharedPreferences("LastUpdatePrefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        for (url in activeUrls) {
            try {
                val lastUpdateTime = lastUpdatePrefs.getLong("last_update_$url", 0)
                val shouldUpdate = forceUpdate ||
                        (now - lastUpdateTime > ONE_DAY_MILLIS) ||
                        (lastUpdateTime == 0L)

                if (shouldUpdate) {
                    // Fetch new data
                    val result = calendarFetcher.fetchCalendarData(url)
                    if (result.isSuccess) {
                        val icsContent = result.getOrThrow()
                        val events = calendarParser.parseIcsContent(icsContent)
                        allEvents.addAll(events)

                        // Save to cache
                        saveCalendarCache(url, icsContent)
                        // Update last fetch time
                        lastUpdatePrefs.edit {
                            putLong("last_update_$url", now)
                        }
                    }
                } else {
                    // Use cached data
                    val cachedData = loadCalendarCache(url)
                    if (cachedData != null) {
                        val events = calendarParser.parseIcsContent(cachedData)
                        allEvents.addAll(events)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val nextOrCurrentEvent = if (allEvents.isNotEmpty()) {
            calendarParser.findNextOrCurrentEvent(allEvents)
        } else {
            null
        }

        updateNotificationAndWidget(nextOrCurrentEvent)
    }

    private suspend fun forceUpdateCalendar(url: String) {
        try {
            val prefs = getSharedPreferences("SourcePrefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("active_$url", false)) {
                val result = calendarFetcher.fetchCalendarData(url)
                if (result.isSuccess) {
                    val icsContent = result.getOrThrow()

                    // Save to cache
                    saveCalendarCache(url, icsContent)

                    // Update last fetch time
                    val lastUpdatePrefs = getSharedPreferences("LastUpdatePrefs", Context.MODE_PRIVATE)
                    lastUpdatePrefs.edit {
                        putLong("last_update_$url", System.currentTimeMillis())
                    }

                    // Update UI
                    updateCalendarEvents(false)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCalendarCache(url: String, data: String) {
        val cachePrefs = getSharedPreferences("CalendarCachePrefs", Context.MODE_PRIVATE)
        cachePrefs.edit {
            putString("cache_$url", data)
        }
    }

    private fun loadCalendarCache(url: String): String? {
        val cachePrefs = getSharedPreferences("CalendarCachePrefs", Context.MODE_PRIVATE)
        return cachePrefs.getString("cache_$url", null)
    }

    private fun updateNotificationAndWidgetFromCache() {
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
                // Use cached data
                val cachedData = loadCalendarCache(url)
                if (cachedData != null) {
                    val events = calendarParser.parseIcsContent(cachedData)
                    allEvents.addAll(events)
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
            eventPrefs.edit {
                putString("current_title", getString(R.string.no_events))
                    .putString("current_time", "")
                    .putString("event_status", "")
                    .putString("event_location", "")
                    .putLong("time_remaining", -1)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(title = getString(R.string.app_name), content = getString(R.string.no_events)))
        } else {
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
            val location = event.location ?: ""
            val timeStr = "$startTimeStr - $endTimeStr"

            eventPrefs.edit {
                putString("current_title", event.summary)
                    .putString("current_time", timeStr)
                    .putString("event_status", status)
                    .putString("event_location", location)
                    .putLong("time_remaining", timeRemaining)
            }

            // Format time remaining for notification
            val hours = TimeUnit.MILLISECONDS.toHours(timeRemaining)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(timeRemaining) % 60
            val timeRemainingStr = when {
                hours > 0 -> "$hours h $minutes min"
                else -> "$minutes min"
            }

            var notificationText = "$status $timeRemainingStr"
            if (showDetails) {
                notificationText += "\n$timeStr"
                if (showLocation && !event.location.isNullOrEmpty()) {
                    notificationText += " @ ${event.location}"
                }
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(
                NOTIFICATION_ID,
                createNotification(
                    title = event.summary,
                    content = notificationText
                )
            )
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