package app.smspend

import app.smspend.domain.SpendCategory
import app.smspend.util.DueLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DueLabelsAndPieTest {
    private fun dayStart(daysFromToday: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_MONTH, daysFromToday)
        return cal.timeInMillis
    }

    @Test
    fun formatDue_todayTomorrowOverdue() {
        val now = dayStart(0) + TimeUnit.HOURS.toMillis(10)
        assertEquals("due today", DueLabels.formatDue(dayStart(0) + TimeUnit.HOURS.toMillis(15), now))
        assertEquals("due tomorrow", DueLabels.formatDue(dayStart(1) + TimeUnit.HOURS.toMillis(9), now))
        assertEquals("overdue", DueLabels.formatDue(dayStart(-2), now))
    }

    @Test
    fun formatExpectedRow_skipsBlankUnknown() {
        assertNull(DueLabels.formatExpectedRow("", 100_00, dayStart(1)))
        assertNull(DueLabels.formatExpectedRow("Unknown", 100_00, dayStart(1)))
        val line = DueLabels.formatExpectedRow("Netflix", 649_00, dayStart(0))!!
        assertTrue(line.contains("Netflix"))
        assertTrue(line.contains("due today"))
        assertTrue(line.contains("~"))
    }

    @Test
    fun selfTransfer_notCountedAsSpend_forPie() {
        assertFalse(SpendCategory.SELF_TRANSFER.countsAsSpend)
        assertTrue(SpendCategory.WITHDRAWALS.countsAsSpend)
        assertTrue(SpendCategory.FOOD_DINING.countsAsSpend)
        assertFalse(SpendCategory.SAVINGS.countsAsSpend)
    }
}
