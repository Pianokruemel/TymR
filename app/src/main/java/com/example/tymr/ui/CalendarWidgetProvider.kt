package com.example.tymr.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.tymr.MainActivity
import com.example.tymr.R
import com.example.tymr.service.UpdateWorker
import java.util.concurrent.TimeUnit

class CalendarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        // Register for updates when the first widget is added
        UpdateWorker.schedulePeriodicWork(context)
    }

    override fun onDisabled(context: Context) {
        // Cancel updates when the last widget is removed
        UpdateWorker.cancelPeriodicWork(context)
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.calendar_widget)

            // Set up a click intent for the widget
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            // Retrieve the current event info from SharedPreferences
            val prefs = context.getSharedPreferences("EventPrefs", Context.MODE_PRIVATE)
            val eventTitle = prefs.getString("current_title", context.getString(R.string.no_events))
            val eventTime = prefs.getString("current_time", "")
            val eventStatus = prefs.getString("event_status", "")
            val timeRemaining = prefs.getLong("time_remaining", -1)

            views.setTextViewText(R.id.widget_title, eventTitle)

            if (timeRemaining > 0) {
                val remainingText = formatTimeRemaining(timeRemaining)
                views.setTextViewText(R.id.widget_countdown, remainingText)
            } else {
                views.setTextViewText(R.id.widget_countdown, "")
            }

            // Show additional info based on user preferences
            val showDetails = prefs.getBoolean("show_details", true)
            if (showDetails && eventTime?.isNotEmpty() == true) {
                views.setTextViewText(R.id.widget_details, "$eventStatus\n$eventTime")
                views.setViewVisibility(R.id.widget_details, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_details, android.view.View.GONE)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun formatTimeRemaining(milliseconds: Long): String {
            if (milliseconds <= 0) return ""

            val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds) % 60

            return when {
                hours > 0 -> "$hours h $minutes min"
                else -> "$minutes min"
            }
        }
    }
}