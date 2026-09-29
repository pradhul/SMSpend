package app.smspend

import app.smspend.util.InboxScanWindow
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxScanWindowTest {
    private val oldest = 1_000_000L
    private val hour = 60L * 60 * 1000

    @Test
    fun firstScanStartsAtOldestHomeMonth() {
        assertEquals(oldest, InboxScanWindow.sinceMs(0L, oldest))
    }

    @Test
    fun incrementalScanOverlapsTheWatermark() {
        val watermark = oldest + 10 * 24 * hour
        assertEquals(
            watermark - InboxScanWindow.OVERLAP_MS,
            InboxScanWindow.sinceMs(watermark, oldest),
        )
    }

    @Test
    fun narrowOverrideDoesNotHideMessagesInsideTheOverlap() {
        val watermark = oldest + 10 * 24 * hour
        val today = watermark - hour
        assertEquals(
            watermark - InboxScanWindow.OVERLAP_MS,
            InboxScanWindow.sinceMs(watermark, oldest, sinceOverrideMs = today),
        )
    }

    @Test
    fun olderOverrideStillReadsThatFarBack() {
        val watermark = oldest + 40 * 24 * hour
        val lastMonth = oldest + 5 * 24 * hour
        assertEquals(
            lastMonth,
            InboxScanWindow.sinceMs(watermark, oldest, sinceOverrideMs = lastMonth),
        )
    }

    @Test
    fun forceFullMonthUsesTheHomeWindow() {
        val watermark = oldest + 10 * 24 * hour
        assertEquals(
            oldest,
            InboxScanWindow.sinceMs(watermark, oldest, forceFullMonth = true),
        )
    }
}
