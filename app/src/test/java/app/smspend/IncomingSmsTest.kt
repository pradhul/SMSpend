package app.smspend

import app.smspend.data.RawSms
import app.smspend.util.IncomingSms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingSmsTest {
    @Test
    fun joinsMultipartPartsInOrder() {
        val joined = IncomingSms.join(
            listOf(
                IncomingSms.Part("VM-HDFCBK", "Sent Rs.250.00 From HDFC Bank A/C *1234 "),
                IncomingSms.Part("VM-HDFCBK", "To SWIGGY On 09/11/26 Ref 123456789012"),
            ),
        )
        assertEquals(1, joined.size)
        assertEquals("VM-HDFCBK", joined[0].sender)
        assertEquals(
            "Sent Rs.250.00 From HDFC Bank A/C *1234 To SWIGGY On 09/11/26 Ref 123456789012",
            joined[0].body,
        )
    }

    @Test
    fun joinKeepsSendersSeparateAndSkipsEmptyParts() {
        val joined = IncomingSms.join(
            listOf(
                IncomingSms.Part("AD-SBIUPI", "Dear UPI user A/C X1234 debited by 50.0"),
                IncomingSms.Part("AD-SBIUPI", null),
                IncomingSms.Part(null, "hello"),
                IncomingSms.Part("JM-ICICIT", ""),
            ),
        )
        assertEquals(listOf("AD-SBIUPI", ""), joined.map { it.sender })
        assertEquals("Dear UPI user A/C X1234 debited by 50.0", joined[0].body)
    }

    @Test
    fun joinOfNothingIsEmpty() {
        assertTrue(IncomingSms.join(emptyList()).isEmpty())
    }

    private fun raw(id: Long, sender: String, body: String) =
        RawSms(id = id, body = body, sender = sender, dateMs = 1_000L * id)

    @Test
    fun findsBroadcastMessageInInboxIgnoringWhitespaceAndSenderFormatting() {
        val inbox = listOf(
            raw(1, "AD-SBIUPI", "Some other message"),
            raw(2, "ad-sbiupi", "Dear UPI user A/C X1234\ndebited by 50.0  on date 09Nov26"),
        )
        assertTrue(
            IncomingSms.isInInbox(inbox, "AD-SBIUPI", "Dear UPI user A/C X1234 debited by 50.0 on date 09Nov26"),
        )
    }

    @Test
    fun notInInboxUntilTheSmsAppSavesIt() {
        val inbox = listOf(raw(1, "AD-SBIUPI", "Dear UPI user A/C X1234 debited by 50.0"))
        assertFalse(IncomingSms.isInInbox(inbox, "AD-SBIUPI", "Dear UPI user A/C X1234 debited by 99.0"))
        assertFalse(IncomingSms.isInInbox(inbox, "VM-HDFCBK", "Dear UPI user A/C X1234 debited by 50.0"))
        assertFalse(IncomingSms.isInInbox(emptyList(), "AD-SBIUPI", "anything"))
    }

    @Test
    fun truncatedBodyMatchesByPrefix() {
        val longBody = "Rs.1,050.00 spent on HDFC Bank Card x1234 at AMAZON " + "x".repeat(3_000)
        val inbox = listOf(raw(7, "+91 98765-43210", longBody))
        val sent = longBody.take(1_500)
        assertTrue(IncomingSms.isInInbox(inbox, "+919876543210", sent))
    }

    @Test
    fun unknownSenderOrBodyDoesNotBlockTheScan() {
        val inbox = listOf(raw(1, "AD-SBIUPI", "debited by 50.0"))
        assertTrue(IncomingSms.isInInbox(inbox, "", "debited by 50.0"))
        // Nothing to wait for: treat as present so the worker scans immediately.
        assertTrue(IncomingSms.isInInbox(emptyList(), "AD-SBIUPI", "   "))
    }
}
