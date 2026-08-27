package app.smspend.util

import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class WeekWindow(
    val weeksAgo: Int,
    val startMs: Long,
    val endExclusiveMs: Long,
) {
    val isCurrent: Boolean get() = weeksAgo == 0

    fun title(): String = when (weeksAgo) {
        0 -> "This week"
        1 -> "Last week"
        else -> DateRanges.formatWeekRange(startMs, endExclusiveMs)
    }

    fun subtitle(): String = DateRanges.formatWeekRange(startMs, endExclusiveMs)
}

data class MonthWindow(
    val monthsAgo: Int,
    val startMs: Long,
    val endExclusiveMs: Long,
) {
    val isCurrent: Boolean get() = monthsAgo == 0

    fun title(): String = when (monthsAgo) {
        0 -> "This month"
        1 -> "Last month"
        else -> DateRanges.formatMonthTitle(startMs)
    }

    fun subtitle(): String = DateRanges.formatMonthSubtitle(startMs, endExclusiveMs)

    fun shortLabel(): String = DateRanges.formatMonthShort(startMs)
}

object DateRanges {
    const val HOME_WEEK_COUNT = 8
    /** Current + previous month on the Months pager (plus Pick). */
    const val HOME_MONTH_COUNT = 2
    /** Months shown on Trends (oldest → newest, including this month). */
    const val TRENDS_MONTH_COUNT = 6
    const val PICKER_MONTH_COUNT = 24

    fun startOfTodayMs(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun todayWindow(now: Long = System.currentTimeMillis()): Pair<Long, Long> =
        startOfTodayMs(now) to endExclusiveNow(now)

    fun formatTodaySubtitle(now: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("EEE · MMM d", Locale.getDefault()).format(now)

    fun startOfThisMonthMs(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun startOfNextMonthMs(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfThisMonthMs(now)
        cal.add(Calendar.MONTH, 1)
        return cal.timeInMillis
    }

    fun startOfMonthMs(monthsAgo: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfThisMonthMs(now)
        cal.add(Calendar.MONTH, -monthsAgo)
        return cal.timeInMillis
    }

    fun endExclusiveOfMonthMs(monthsAgo: Int, now: Long = System.currentTimeMillis()): Long {
        if (monthsAgo == 0) return endExclusiveNow(now)
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfMonthMs(monthsAgo, now)
        cal.add(Calendar.MONTH, 1)
        return cal.timeInMillis
    }

    fun monthWindow(monthsAgo: Int, now: Long = System.currentTimeMillis()): MonthWindow =
        MonthWindow(
            monthsAgo = monthsAgo,
            startMs = startOfMonthMs(monthsAgo, now),
            endExclusiveMs = endExclusiveOfMonthMs(monthsAgo, now),
        )

    /** Oldest → newest (left → right); this month is last. */
    fun homeMonths(now: Long = System.currentTimeMillis()): List<MonthWindow> =
        ((HOME_MONTH_COUNT - 1) downTo 0).map { monthWindow(it, now) }

    /** Oldest → newest for Trends stacked chart. */
    fun trendsMonths(now: Long = System.currentTimeMillis()): List<MonthWindow> =
        ((TRENDS_MONTH_COUNT - 1) downTo 0).map { monthWindow(it, now) }

    fun monthWindowAt(year: Int, month1to12: Int, now: Long = System.currentTimeMillis()): MonthWindow {
        val cal = Calendar.getInstance()
        cal.clear()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month1to12 - 1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis
        val thisStart = startOfThisMonthMs(now)
        val monthsAgo = if (start >= thisStart) {
            0
        } else {
            val a = Calendar.getInstance().apply { timeInMillis = start }
            val b = Calendar.getInstance().apply { timeInMillis = thisStart }
            (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 12 +
                (b.get(Calendar.MONTH) - a.get(Calendar.MONTH))
        }
        val endExclusive = if (monthsAgo == 0) endExclusiveNow(now) else end
        return MonthWindow(monthsAgo = monthsAgo, startMs = start, endExclusiveMs = endExclusive)
    }

    /** Last N months including this month, newest first (for picker UI). */
    fun pickerMonths(count: Int = PICKER_MONTH_COUNT, now: Long = System.currentTimeMillis()): List<MonthWindow> =
        (0 until count).map { monthWindow(it, now) }

    fun startOfThisYearMs(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Jan → current month of this calendar year. */
    fun yearToDateMonths(now: Long = System.currentTimeMillis()): List<MonthWindow> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val currentMonthIndex = cal.get(Calendar.MONTH) // 0-based
        return (0..currentMonthIndex).map { monthIndex ->
            monthWindowAt(cal.get(Calendar.YEAR), monthIndex + 1, now)
        }
    }

    fun formatMonthTitle(startMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = startMs }
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        return if (cal.get(Calendar.YEAR) == thisYear) {
            java.text.SimpleDateFormat("MMMM", Locale.getDefault()).format(startMs)
        } else {
            java.text.SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(startMs)
        }
    }

    fun formatMonthShort(startMs: Long): String =
        java.text.SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(startMs)

    fun formatMonthSubtitle(startMs: Long, endExclusiveMs: Long): String {
        val fmt = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
        val end = (endExclusiveMs - 1).coerceAtLeast(startMs)
        return "${fmt.format(startMs)} – ${fmt.format(end)}"
    }

    /** Week starting Monday 00:00 local. */
    fun startOfThisWeekMs(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val diff = when (day) {
            Calendar.MONDAY -> 0
            Calendar.SUNDAY -> -6
            else -> Calendar.MONDAY - day
        }
        cal.add(Calendar.DAY_OF_MONTH, diff)
        return cal.timeInMillis
    }

    fun startOfWeekMs(weeksAgo: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfThisWeekMs(now)
        cal.add(Calendar.WEEK_OF_YEAR, -weeksAgo)
        return cal.timeInMillis
    }

    fun endExclusiveOfWeekMs(weeksAgo: Int, now: Long = System.currentTimeMillis()): Long {
        if (weeksAgo == 0) return endExclusiveNow(now)
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfWeekMs(weeksAgo, now)
        cal.add(Calendar.WEEK_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun weekWindow(weeksAgo: Int, now: Long = System.currentTimeMillis()): WeekWindow =
        WeekWindow(
            weeksAgo = weeksAgo,
            startMs = startOfWeekMs(weeksAgo, now),
            endExclusiveMs = endExclusiveOfWeekMs(weeksAgo, now),
        )

    fun homeWeeks(now: Long = System.currentTimeMillis()): List<WeekWindow> =
        ((HOME_WEEK_COUNT - 1) downTo 0).map { weekWindow(it, now) }

    fun endExclusiveNow(now: Long = System.currentTimeMillis()): Long = now + 1

    fun formatWeekRange(fromMs: Long, toMs: Long): String {
        val fmt = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
        val end = (toMs - 1).coerceAtLeast(fromMs)
        return "${fmt.format(fromMs)} – ${fmt.format(end)}"
    }

    fun formatTxnTime(ms: Long): String {
        val fmt = java.text.SimpleDateFormat("EEE · h:mm a", Locale.getDefault())
        return fmt.format(ms)
    }

    fun formatSyncTime(ms: Long): String {
        if (ms <= 0) return "Never"
        val fmt = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
        return "today ${fmt.format(ms)}"
    }

    fun daysBetween(fromMs: Long, toMs: Long): Long =
        TimeUnit.MILLISECONDS.toDays(toMs - fromMs)
}
