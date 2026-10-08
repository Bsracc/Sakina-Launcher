package app.sakinalauncher.helper

import android.content.Context
import app.sakinalauncher.data.Prefs
import app.sakinalauncher.helper.usageStats.EventLogWrapper
import java.util.Calendar

/**
 * Daily screen-time goal (B2).
 *
 * A target in minutes stored in [Prefs.dailyScreenTimeGoalMinutes] (0 = disabled).
 * Progress is computed from the same EventLogWrapper pipeline used by the home
 * screen screen-time readout, so no extra permission is needed.
 */
object ScreenTimeGoal {

    /** Today's elapsed foreground time in milliseconds, or null when usage stats are unavailable. */
    fun getTodaysUsageMillis(context: Context): Long? {
        if (context.appUsagePermissionGranted().not()) return null
        return try {
            val eventLogWrapper = EventLogWrapper(context)
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val endTime = System.currentTimeMillis()
            eventLogWrapper.aggregateSimpleUsageStats(
                eventLogWrapper.aggregateForegroundStats(
                    eventLogWrapper.getForegroundStatsByTimestamps(calendar.timeInMillis, endTime)
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Whether a goal is configured. */
    fun isEnabled(prefs: Prefs): Boolean = prefs.dailyScreenTimeGoalMinutes > 0

    /** Progress 0f..1f toward today's goal; 1f when reached. 0f when disabled. */
    fun progress(prefs: Prefs, usedMillis: Long): Float {
        if (!isEnabled(prefs)) return 0f
        val goalMillis = prefs.dailyScreenTimeGoalMinutes * 60_000L
        if (goalMillis <= 0L) return 0f
        return (usedMillis.toFloat() / goalMillis.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Returns one of: "reached" (goal hit for the first time today), "missed"
     * (goal configured, day is over, goal not hit, first notice today), or null.
     * Dedupes per day-of-year via [Prefs.screenTimeGoalReachedNotifiedDay] /
     * [Prefs.screenTimeGoalMissedNotifiedDay].
     */
    fun checkNotice(prefs: Prefs, usedMillis: Long): String? {
        if (!isEnabled(prefs)) return null
        val dayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val goalMillis = prefs.dailyScreenTimeGoalMinutes * 60_000L
        if (usedMillis >= goalMillis) {
            if (prefs.screenTimeGoalReachedNotifiedDay == dayOfYear) return null
            prefs.screenTimeGoalReachedNotifiedDay = dayOfYear
            return "reached"
        }
        // End-of-day check: it is after midnight (or the day rolled over) and the
        // goal was not reached.
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour >= 23) {
            if (prefs.screenTimeGoalMissedNotifiedDay == dayOfYear) return null
            prefs.screenTimeGoalMissedNotifiedDay = dayOfYear
            return "missed"
        }
        return null
    }

    /** Human-readable goal summary, e.g. "2h 0m". */
    fun formatGoal(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    /** Today's usage formatted like the home screen readout, e.g. "2h 11m". */
    fun formatUsed(context: Context, usedMillis: Long): String =
        context.formattedTimeSpent(usedMillis)
}
