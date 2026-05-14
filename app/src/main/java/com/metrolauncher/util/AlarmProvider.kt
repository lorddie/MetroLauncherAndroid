package com.metrolauncher.util

import android.app.AlarmManager
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Reading the next system alarm via [AlarmManager.getNextAlarmClock].
 *
 * Does not require permissions — it's public information that Android exposes to any app 
 * (the same one that appears as an icon in the status bar when an alarm is active). 
 * Works from API 21, compatible with the project's minSdk 24.
 *
 * NOTE: this API returns ONLY alarms set with the AlarmClock API, i.e., those 
 * that appear as an "alarm" to the user. Simple system `alarms` (BLE pings, 
 * background jobs, etc.) do not appear here.
 */
object AlarmProvider {

    /** Next alarm timestamp in ms, or null if none is set. */
    fun nextAlarmTriggerMs(context: Context): Long? {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return null
        val info = am.nextAlarmClock ?: return null
        return info.triggerTime
    }

    /**
     * Formats the alarm timestamp in a "human" and compact way, suitable for the tile.
     *   - Today         → "HH:mm"
     *   - Tomorrow      → "tomorrow HH:mm"
     *   - Within 7 days → "thu HH:mm"   (3 letters of the day of the week)
     *   - Beyond        → "dd/MM HH:mm"
     *
     * Uses system locale for weekdays and time separators.
     */
    fun formatNextAlarm(triggerMs: Long, now: Long = System.currentTimeMillis()): String {
        val loc = Locale.getDefault()
        val timeFmt = SimpleDateFormat("HH:mm", loc)
        val hhmm = timeFmt.format(Date(triggerMs))

        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val target = Calendar.getInstance().apply {
            timeInMillis = triggerMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val daysDiff = ((target.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()

        return when {
            daysDiff <= 0 -> hhmm
            daysDiff in 1..6 -> {
                val dow = SimpleDateFormat("EEE", loc).format(Date(triggerMs))
                    .lowercase(loc).trimEnd('.')
                "$dow $hhmm"
            }
            else -> SimpleDateFormat("dd/MM HH:mm", loc).format(Date(triggerMs))
        }
    }

    /** Recommended interval for clock tile refresh. Every minute is enough. */
    const val CLOCK_REFRESH_INTERVAL_MS = 60_000L
}
