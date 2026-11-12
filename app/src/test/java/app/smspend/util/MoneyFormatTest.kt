package app.smspend.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyFormatTest {
    @Test
    fun parsesEveryWrittenShape() {
        assertEquals(12_345_678L, rupeesToPaise("1,23,456.78"))
        assertEquals(200_000L, rupeesToPaise("2,000"))
        assertEquals(5_000L, rupeesToPaise("50.0"))
        assertEquals(9_900L, rupeesToPaise("₹ 99"))
        assertEquals(1_200L, rupeesToPaise("Rs.12"))
        assertEquals(50_000L, rupeesToPaise("INR500"))
        // 0.29 * 100 is 28.999… in floating point; decimal parsing must stay exact.
        assertEquals(29L, rupeesToPaise("0.29"))
        assertNull(rupeesToPaise("0"))
        assertNull(rupeesToPaise("-5"))
        assertNull(rupeesToPaise("1.234"))
        assertNull(rupeesToPaise("abc"))
        assertNull(rupeesToPaise(""))
        assertNull(rupeesToPaise(null))
    }

    @Test
    fun formattersUseIndianGrouping() {
        assertEquals("₹1,20,000", 12_000_000L.paiseToRupeeString())
        assertEquals("₹486.50", 48_650L.paiseToRupeeString())
        assertEquals("₹0", 0L.paiseToRupeeString())
        assertEquals("-₹1,250", (-125_000L).paiseToRupeeString())
        assertEquals("12,34,568", 123_456_789L.paiseToPlainRupees())
        assertEquals("999.50", 99_950L.paiseToPlainRupees())
        assertEquals("486", 48_600L.paiseToPlainRupees())
    }
}
