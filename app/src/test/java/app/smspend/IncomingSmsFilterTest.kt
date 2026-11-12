package app.smspend

import app.smspend.data.RawSms
import app.smspend.util.IncomingSms
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Receiver pre-filter (review #2) and the text-free inbox match (review #7). */
class IncomingSmsFilterTest {
    @Test
    fun bankDebitsPass() {
        assertTrue(IncomingSms.isLikelyFinancial("AX-HDFCBK", "Rs.250.00 debited from a/c **1234 on 10-11-26 to VPA swiggy@icici"))
        assertTrue(IncomingSms.isLikelyFinancial("VM-SBIUPI", "Dear UPI user A/C X1234 debited by 120.0 on date 10Nov26 trf to ZOMATO"))
        assertTrue(IncomingSms.isLikelyFinancial("JD-ICICIT", "INR500 spent on ICICI Bank Card XX9876 at AMAZON"))
    }

    @Test
    fun otpsAreSkipped() {
        assertFalse(IncomingSms.isLikelyFinancial("AX-HDFCBK", "123456 is your OTP for txn of Rs 500 at AMAZON. Do not share."))
        assertFalse(IncomingSms.isLikelyFinancial("VM-ICICIB", "Your one time password for card ending 1234 is 998877"))
    }

    @Test
    fun otpLikeTextWithCompletedDebitPasses() {
        assertTrue(
            IncomingSms.isLikelyFinancial(
                "AX-HDFCBK",
                "Rs 500 debited from a/c 1234. Not you? Never share your OTP with anyone.",
            ),
        )
    }

    @Test
    fun personalAndNonFinancialSkipped() {
        assertFalse(IncomingSms.isLikelyFinancial("+919876543210", "I paid Rs 500 for dinner, send me your share"))
        assertFalse(IncomingSms.isLikelyFinancial("AX-SWIGGY", "Your order is out for delivery!"))
        assertFalse(IncomingSms.isLikelyFinancial("AX-HDFCBK", "   "))
    }

    @Test
    fun fingerprintMatchesInboxRowIgnoringWhitespace() {
        val body = "Rs 250 debited from a/c 1234\nto swiggy"
        val fp = IncomingSms.bodyFingerprint(body)
        val inbox = listOf(RawSms(id = 1, body = "Rs 250 debited from a/c 1234 to swiggy ", sender = "AX-HDFCBK", dateMs = 0))
        assertTrue(IncomingSms.isInInbox(inbox, "AXHDFCBK", fp))
        assertFalse(IncomingSms.isInInbox(inbox, "AX-SBIINB", fp))
        assertFalse(IncomingSms.isInInbox(inbox, "AX-HDFCBK", IncomingSms.bodyFingerprint("other")))
    }
}
