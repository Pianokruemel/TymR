package com.example.tymr.data

import java.text.SimpleDateFormat
import java.util.*

data class CalendarEvent(
    val uid: String,
    val summary: String,
    val description: String?,
    val location: String?,
    val startTime: Date,
    val endTime: Date
) {
    fun isOngoing(): Boolean {
        val now = Date()
        return now in startTime..endTime
    }

    fun isUpcoming(): Boolean {
        return Date() < startTime
    }

    fun timeUntilStart(): Long {
        return startTime.time - Date().time
    }

    fun timeUntilEnd(): Long {
        return endTime.time - Date().time
    }
}

class CalendarParser {
    private val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.GERMAN).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val icsDateFormatLocal = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.GERMAN)

    /**
     * Parse ICS file content and extract calendar events
     * @param icsContent The content of the ICS file
     * @return List of calendar events
     */
    fun parseIcsContent(icsContent: String): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()

        var inEvent = false
        var uid = ""
        var summary = ""
        var description: String? = null
        var location: String? = null
        var startTime: Date? = null
        var endTime: Date? = null

        val lines = icsContent.lines()

        for (line in lines) {
            when {
                line.startsWith("BEGIN:VEVENT") -> {
                    inEvent = true
                    uid = ""
                    summary = ""
                    description = null
                    location = null
                    startTime = null
                    endTime = null
                }

                line.startsWith("END:VEVENT") -> {
                    if (uid.isNotEmpty() && summary.isNotEmpty() && startTime != null && endTime != null) {
                        events.add(CalendarEvent(uid, summary, description, location, startTime, endTime))
                    }
                    inEvent = false
                }

                inEvent && line.startsWith("UID:") -> {
                    uid = line.substringAfter("UID:")
                }

                inEvent && line.startsWith("SUMMARY:") -> {
                    summary = line.substringAfter("SUMMARY:")
                }

                inEvent && line.startsWith("DESCRIPTION:") -> {
                    description = line.substringAfter("DESCRIPTION:")
                }

                inEvent && line.startsWith("LOCATION:") -> {
                    location = line.substringAfter("LOCATION:")
                }

                inEvent && line.startsWith("DTSTART") -> {
                    val dateStr = line.substringAfter(":")
                    startTime = parseIcsDate(dateStr)
                }

                inEvent && line.startsWith("DTEND") -> {
                    val dateStr = line.substringAfter(":")
                    endTime = parseIcsDate(dateStr)
                }
            }
        }

        return events.sortedBy { it.startTime }
    }

    private fun parseIcsDate(dateStr: String): Date? {
        return try {
            if (dateStr.endsWith("Z")) {
                icsDateFormat.parse(dateStr)
            } else {
                icsDateFormatLocal.parse(dateStr)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Find the next event or current ongoing event
     * @param events List of calendar events
     * @return The next upcoming event or current ongoing event, if any
     */
    fun findNextOrCurrentEvent(events: List<CalendarEvent>): CalendarEvent? {
        val now = Date()

        // First check for ongoing events
        val currentEvent = events.firstOrNull { now in it.startTime..it.endTime }
        if (currentEvent != null) {
            return currentEvent
        }

        // Otherwise find the next upcoming event
        return events.filter { it.startTime > now }.minByOrNull { it.startTime }
    }
}