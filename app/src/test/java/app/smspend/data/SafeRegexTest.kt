package app.smspend.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeRegexTest {
    @Test
    fun normalTemplateIsSafeAndMatches() {
        val p = """debited by Rs\.?\s*(?<amount>[\d,]+(?:\.\d{1,2})?) """
        assertTrue(SafeRegex.isSafe(p))
        assertTrue(SafeRegex.find(p, "A/c XX12 debited by Rs.1,250.00 on 01-Jan"))
    }

    @Test
    fun catastrophicBacktrackingIsRejectedAndTimesOut() {
        val evil = """(.*a){10}x"""
        assertFalse(SafeRegex.isSafe(evil))
        val start = System.nanoTime()
        assertFalse(SafeRegex.find(evil, "a".repeat(64) + "!"))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 2_000)
    }

    @Test
    fun invalidAndOverlongPatternsAreRejected() {
        assertNull(SafeRegex.compile("([unclosed"))
        assertNull(SafeRegex.compile("a".repeat(SafeRegex.MAX_PATTERN_LENGTH + 1)))
        assertFalse(SafeRegex.find("([unclosed", "anything"))
    }

    @Test
    fun compiledPatternIsCached() {
        val p = """Rs\.?(\d+)"""
        assertTrue(SafeRegex.compile(p) === SafeRegex.compile(p))
    }

    @Test
    fun diagSnippetsMaskDigits() {
        val s = DiagLog.redactSms("Rs 1,250.00 debited from A/c XX1234 OTP 998877\nRef 123")
        assertEquals("Rs #,###.## debited from A/c XX#### OTP ###### Ref ###", s)
    }
}
