package app.smspend

import app.smspend.data.TransactionEntity
import app.smspend.domain.RecurringDetector
import app.smspend.util.DateRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RoadmapHelpersTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun monthOverMonthPct_upAndDown() {
        assertEquals(50, RecurringDetector.weekOverWeekPct(150_00, 100_00))
        assertEquals(-20, RecurringDetector.weekOverWeekPct(80_00, 100_00))
        assertEquals(0, RecurringDetector.weekOverWeekPct(100_00, 100_00))
        assertNull(RecurringDetector.weekOverWeekPct(50_00, 0))
    }

    @Test
    fun homeMonths_areOldestToCurrent() {
        val months = DateRanges.homeMonths()
        assertEquals(2, months.size)
        assertTrue(months.first().startMs < months.last().startMs)
        assertEquals(0, months.last().monthsAgo)
        assertEquals("This month", months.last().title())
    }

    @Test
    fun monthWindowAt_buildsCorrectRange() {
        val m = DateRanges.monthWindowAt(2025, 3)
        val cal = Calendar.getInstance().apply { timeInMillis = m.startMs }
        assertEquals(2025, cal.get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, cal.get(Calendar.MONTH))
        assertEquals(1, cal.get(Calendar.DAY_OF_MONTH))
        assertTrue(m.endExclusiveMs > m.startMs)
    }

    @Test
    fun yearToDateMonths_startsInJanuary() {
        val months = DateRanges.yearToDateMonths()
        assertTrue(months.isNotEmpty())
        val cal = Calendar.getInstance().apply { timeInMillis = months.first().startMs }
        assertEquals(Calendar.JANUARY, cal.get(Calendar.MONTH))
        assertEquals(0, months.last().monthsAgo)
    }

    @Test
    fun recurringDetector_findsMonthlyMerchant() {
        val base = 1_700_000_000_000L
        val txs = listOf(
            txn("netflix", base),
            txn("netflix", base + 30 * day),
            txn("netflix", base + 60 * day),
            txn("oneoff", base + 10 * day),
        )
        val found = RecurringDetector.detect(txs, nowMs = base + 61 * day)
        assertEquals(1, found.size)
        assertEquals("netflix", found[0].merchantKey)
        assertTrue(found[0].cadenceDays in 25..35)
    }

    private fun txn(key: String, at: Long, id: Long = at) = TransactionEntity(
        id = id,
        smsId = id,
        amountPaise = 49900,
        merchantRaw = key.uppercase(),
        merchantKey = key,
        category = "subscriptions",
        occurredAt = at,
        bodySnippet = "debited",
        sender = "VM-BANK",
    )
}
