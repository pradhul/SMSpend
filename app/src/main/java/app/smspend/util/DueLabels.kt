package app.smspend.util

import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Human-readable due labels for recurring "likely due soon" rows. */
object DueLabels {
    fun formatDue(
        nextExpectedAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val startToday = startOfDayMs(nowMs)
        val startDue = startOfDayMs(nextExpectedAtMs)
        val days = ((startDue - startToday) / dayMs).toInt()
        return when {
            days < 0 -> "overdue"
            days == 0 -> "due today"
            days == 1 -> "due tomorrow"
            days in 2..6 -> "due ${weekdayShort(nextExpectedAtMs)}"
            else -> "in $days days"
        }
    }

    fun formatExpectedRow(
        displayName: String,
        medianPaise: Long,
        nextExpectedAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val name = displayName.trim()
        if (name.isBlank() || name.equals("unknown", ignoreCase = true)) return null
        return "$name · ~${medianPaise.paiseToCompactRupees()} · ${formatDue(nextExpectedAtMs, nowMs)}"
    }

    private fun startOfDayMs(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun weekdayShort(ms: Long): String =
        java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(ms)
}
