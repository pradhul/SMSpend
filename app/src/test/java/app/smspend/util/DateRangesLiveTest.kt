package app.smspend.util

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Current periods must end at the period end, not at "now" (review #1). */
class DateRangesLiveTest {
    private lateinit var savedTz: TimeZone

    @Before
    fun setUp() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedTz)
    }

    private fun at(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, min)
        }.timeInMillis

    @Test
    fun todayWindowEndsAtMidnight() {
        val now = at(2026, 11, 10, 10, 30)
        val (start, end) = DateRanges.todayWindow(now)
        assertEquals(at(2026, 11, 10), start)
        assertEquals(at(2026, 11, 11), end)
        // A transaction an hour from now is inside today's window.
        assertTrue(now + 3_600_000 in start until end)
    }

    @Test
    fun currentMonthAndWeekEndAtPeriodEnd() {
        val now = at(2026, 11, 10, 10, 30) // Tuesday
        val month = DateRanges.monthWindow(0, now)
        assertEquals(at(2026, 11, 1), month.startMs)
        assertEquals(at(2026, 12, 1), month.endExclusiveMs)
        val week = DateRanges.weekWindow(0, now)
        assertEquals(at(2026, 11, 9), week.startMs)
        assertEquals(at(2026, 11, 16), week.endExclusiveMs)
        val picked = DateRanges.monthWindowAt(2026, 11, now)
        assertEquals(at(2026, 12, 1), picked.endExclusiveMs)
    }

    @Test
    fun monthRollsOverAcrossYearEnd() {
        val now = at(2026, 12, 31, 23, 59)
        assertEquals(at(2027, 1, 1), DateRanges.startOfTomorrowMs(now))
        assertEquals(at(2027, 1, 1), DateRanges.monthWindow(0, now).endExclusiveMs)
    }

    @Test
    fun msUntilNextDayIsPositive() {
        val now = at(2026, 11, 10, 23, 59)
        assertEquals(60_000L, DateRanges.msUntilNextDay(now))
        assertEquals(1L, DateRanges.msUntilNextDay(at(2026, 11, 11) - 1).coerceAtMost(1L))
    }

    @Test
    fun midnightTicksEmitEachNewDay() = runBlocking {
        var clock = at(2026, 11, 10, 22, 0)
        val ticks = DayTicker.midnightTicks(
            now = { clock },
            sleep = { ms -> clock += ms },
        ).take(3).toList()
        assertEquals(listOf(at(2026, 11, 10), at(2026, 11, 11), at(2026, 11, 12)), ticks)
    }
}
