package com.metrolauncher.util

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * Reads upcoming events from the system calendar.
 * Requires READ_CALENDAR permission (runtime).
 *
 * Returns max N events in the next 24 hours (already started or future).
 * If permission is not granted, returns an empty list without crashing.
 */
object CalendarProvider {

    data class Event(
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val allDay: Boolean,
        val location: String?
    )

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * @param limit maximum number of events to return
     * @param windowHours time window (in hours from current time) to search in
     */
    fun upcomingEvents(ctx: Context, limit: Int = 3, windowHours: Long = 24): List<Event> {
        if (!hasPermission(ctx)) return emptyList()

        val now = System.currentTimeMillis()
        val end = now + TimeUnit.HOURS.toMillis(windowHours)

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now)
        ContentUris.appendId(builder, end)

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION
        )
        val sort = "${CalendarContract.Instances.BEGIN} ASC"

        val events = mutableListOf<Event>()
        runCatching {
            ctx.contentResolver.query(builder.build(), projection, null, null, sort)
                ?.use { c ->
                    while (c.moveToNext() && events.size < limit) {
                        val title = c.getString(0) ?: continue
                        if (title.isBlank()) continue
                        events.add(Event(
                            title = title,
                            startMs = c.getLong(1),
                            endMs = c.getLong(2),
                            allDay = c.getInt(3) == 1,
                            location = c.getString(4)
                        ))
                    }
                }
        }
        return events
    }
}
