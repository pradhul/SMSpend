package app.smspend

import app.smspend.data.SpendRules
import app.smspend.data.SpendRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Peer resolution for the duplicate compare sheet: a recorded DUPLICATE decision
 * (pairKey = "sourceA|sourceB") wins; the caller falls back to the nearest
 * same-amount transaction only when no recorded pair involves the flagged one.
 */
class DuplicatePeerResolutionTest {
    @Test
    fun resolvesPeerWhenSelfIsFirstHalf() {
        val keys = listOf("sms:10|sms:20")
        assertEquals("sms:20", SpendRules.resolvePeerSourceKey(keys, "sms:10"))
    }

    @Test
    fun resolvesPeerWhenSelfIsSecondHalf() {
        val keys = listOf("sms:10|sms:20")
        assertEquals("sms:10", SpendRules.resolvePeerSourceKey(keys, "sms:20"))
    }

    @Test
    fun nullWhenNoRecordedPairInvolvesSelf() {
        val keys = listOf("sms:10|sms:20", "sms:30|sms:40")
        assertNull(SpendRules.resolvePeerSourceKey(keys, "sms:99"))
    }

    @Test
    fun nullForBlankSelfOrEmptyKeys() {
        assertNull(SpendRules.resolvePeerSourceKey(listOf("sms:1|sms:2"), ""))
        assertNull(SpendRules.resolvePeerSourceKey(emptyList(), "sms:1"))
    }

    @Test
    fun skipsMalformedKeys() {
        val keys = listOf("garbage", "sms:1|sms:2|sms:3", "sms:5|sms:6")
        assertEquals("sms:6", SpendRules.resolvePeerSourceKey(keys, "sms:5"))
    }

    @Test
    fun firstMatchingPairWins() {
        val keys = listOf("sms:5|sms:6", "sms:5|sms:7")
        assertEquals("sms:6", SpendRules.resolvePeerSourceKey(keys, "sms:5"))
    }
}
