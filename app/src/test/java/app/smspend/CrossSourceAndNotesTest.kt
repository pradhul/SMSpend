package app.smspend

import app.smspend.data.AtmWithdrawalPoint
import app.smspend.data.CrossSourcePoint
import app.smspend.data.SmsDebitParser
import app.smspend.data.TransactionEntity
import app.smspend.notify.UpiNotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossSourceAndNotesTest {
    private val hour = 60L * 60 * 1000
    private val window = SmsDebitParser.CROSS_SOURCE_WINDOW_MS

    private val hdfcGrowwBody =
        "PAYMENT ALERT! INR 40000.00 deducted from HDFC Bank A/C No 8002 towards Groww UMRN: HDFC7021201210008279"
    private val growwConfirmBody =
        "Rs 40000.00 paid successfully to Groww Mutual Fund. Ref GW9988776655"

    @Test
    fun classifiesBankSmsAsBankDebit() {
        assertTrue(SmsDebitParser.isBankDebit(hdfcGrowwBody, "VK-HDFCBK"))
        assertTrue(SmsDebitParser.isBankDebit(
            "Rs 250.00 debited from A/c XX1234 for UPI to CHAI POINT. Avl Bal Rs 52,340.50.",
            "AD-SBIINB",
        ))
        assertTrue(SmsDebitParser.isBankDebit(
            "Spent Rs 100.00 on card ending 1234 at STORE",
            "VM-UNKNOWN",
        ))
    }

    @Test
    fun classifiesMerchantConfirmationAsNonBank() {
        assertFalse(SmsDebitParser.isBankDebit(growwConfirmBody, "VM-GROWW"))
        assertNotNull(SmsDebitParser.parse(growwConfirmBody, "VM-GROWW"))
        assertNotNull(SmsDebitParser.parse(hdfcGrowwBody, "VK-HDFCBK"))
    }

    @Test
    fun merchantAppSenderWithAccountPhraseStillMerchant() {
        val body =
            "Payment of Rs 500.00 paid successfully from your account A/c XX12 to MERCHANT via PhonePe."
        assertFalse(SmsDebitParser.isBankDebit(body, "VM-PHONEPE"))
        assertFalse(SmsDebitParser.isBankDebit(body, "JD-GPAY"))
        assertTrue(SmsDebitParser.isMerchantAppSender("VM-PHONEPE"))
    }

    @Test
    fun suppressesMerchantWhenBankWithinWindow() {
        val banks = listOf(4_000_000L to 1_000_000L)
        assertTrue(
            SmsDebitParser.hasMatchingBankDebit(
                amountPaise = 4_000_000L,
                occurredAt = 1_000_000L + 2 * hour,
                bankDebits = banks,
                windowMs = window,
            ),
        )
        assertTrue(
            SmsDebitParser.hasMatchingBankDebit(
                amountPaise = 4_000_000L,
                occurredAt = 1_000_000L + window,
                bankDebits = banks,
                windowMs = window,
            ),
        )
    }

    @Test
    fun doesNotSuppressMerchantAcrossWindow() {
        val banks = listOf(4_000_000L to 1_000_000L)
        assertFalse(
            SmsDebitParser.hasMatchingBankDebit(
                amountPaise = 4_000_000L,
                occurredAt = 1_000_000L + window + hour,
                bankDebits = banks,
                windowMs = window,
            ),
        )
    }

    @Test
    fun merchantIdsShadowedByBankWithinWindow() {
        val t0 = 1_000_000L
        val txs = listOf(
            CrossSourcePoint(1, 4_000_000L, t0, "groww", isBank = true),
            CrossSourcePoint(2, 4_000_000L, t0 + 2 * hour, "groww", isBank = false),
            CrossSourcePoint(3, 4_000_000L, t0 + window + hour, "groww", isBank = false),
        )
        assertEquals(listOf(2L), SmsDebitParser.merchantIdsShadowedByBank(txs, window))
    }

    @Test
    fun bankSameMerchantDuplicateKeepsEarliest() {
        val t0 = 1_000_000L
        val banks = listOf(
            CrossSourcePoint(10, 25_000L, t0, "swiggy", isBank = true),
            CrossSourcePoint(11, 25_000L, t0 + hour, "swiggy", isBank = true),
            CrossSourcePoint(12, 25_000L, t0 + hour, "netflix", isBank = true),
        )
        assertEquals(listOf(11L), SmsDebitParser.bankSameMerchantDuplicateIds(banks, window))
    }

    @Test
    fun twoBanksDifferentMerchantsNotCollapsed() {
        val t0 = 1_000_000L
        val banks = listOf(
            CrossSourcePoint(1, 25_000L, t0, "foo", isBank = true),
            CrossSourcePoint(2, 25_000L, t0 + hour, "bar", isBank = true),
        )
        assertTrue(SmsDebitParser.bankSameMerchantDuplicateIds(banks, window).isEmpty())
    }

    @Test
    fun growwUmrmAlertShadowedByAch_keepsBothDistinctAch() {
        val t0 = 1_000_000L
        val achA =
            "UPDATE: INR 75,000.00 debited from HDFC Bank XX7660 on 01-AUG-26. Info: ACH D- Groww Pay Services P-0000QZU8E39L. Avl bal:INR 2,10,976.63"
        val achB =
            "UPDATE: INR 75,000.00 debited from HDFC Bank XX7660 on 01-AUG-26. Info: ACH D- Groww Pay Services P-00005ZRW5OPR. Avl bal:INR 2,85,976.63"
        val umrn =
            "PAYMENT ALERT! INR 75000.00 deducted from HDFC Bank A/C No 7660 towards Groww Pay Services Pvt Ltd UMRN: HDFC7010801220004625"
        val txs = listOf(
            CrossSourcePoint(
                id = 785,
                amountPaise = 7_500_000L,
                occurredAt = t0,
                merchantKey = "ach d groww pay services p 0000qzu8e39l",
                isBank = true,
                bodySnippet = achA,
            ),
            CrossSourcePoint(
                id = 787,
                amountPaise = 7_500_000L,
                occurredAt = t0 + 11_000,
                merchantKey = "ach d groww pay services p 00005zrw5opr",
                isBank = true,
                bodySnippet = achB,
            ),
            CrossSourcePoint(
                id = 791,
                amountPaise = 7_500_000L,
                occurredAt = t0 + 2 * hour,
                merchantKey = "groww pay services pvt ltd",
                isBank = true,
                bodySnippet = umrn,
            ),
        )
        assertTrue(SmsDebitParser.isAchInvestmentDebit(achA))
        assertTrue(SmsDebitParser.isAchInvestmentDebit(achB))
        assertTrue(SmsDebitParser.isInvestmentMandateAlert(umrn))
        assertEquals(
            listOf(791L),
            SmsDebitParser.investmentMandateAlertDuplicateIds(txs, window),
        )
        // Distinct ACH refs must not collapse via same-merchantKey dedup.
        assertTrue(SmsDebitParser.bankSameMerchantDuplicateIds(txs, window).isEmpty())
    }

    @Test
    fun atmReversal_parsesHdfcBounceAndCancelsMatchingWithdrawal() {
        val withdrawn =
            "Withdrawn Rs.20000 From HDFC Bank Card x1868 At +AECS LAYOUT On 2026-08-02:18:35:12 Bal Rs.105392.81 Not You? Call 18002586161/SMS BLOCK DC  1868 to 7308080808"
        val reversed =
            "ATM Txn Reversed:Rs.20000 reversed to your HDFC Bank Card 1868 At +AECS LAYOUT On 2026-08-02:18:35:35.Inconvenience regretted"
        assertNull(SmsDebitParser.parse(reversed, "AD-HDFCBK-S"))
        val rev = SmsDebitParser.parseAtmReversal(reversed)
        assertNotNull(rev)
        assertEquals(2_000_000L, rev!!.amountPaise)
        assertEquals("atm aecs layout", rev.placeKey)
        assertEquals("1868", rev.cardLast4)

        val withdrawnParsed = SmsDebitParser.parse(withdrawn, "AD-HDFCBK-S")
        assertNotNull(withdrawnParsed)
        assertTrue(SmsDebitParser.isAtmWithdrawal(withdrawn, withdrawnParsed!!.merchantKey))

        val t0 = 1_000_000L
        val ids = SmsDebitParser.atmWithdrawalIdsReversedBy(
            reversals = listOf(rev to (t0 + 23_000)),
            withdrawals = listOf(
                AtmWithdrawalPoint(
                    id = 10,
                    amountPaise = 2_000_000L,
                    occurredAt = t0,
                    merchantKey = withdrawnParsed.merchantKey,
                    bodySnippet = withdrawn,
                ),
                AtmWithdrawalPoint(
                    id = 11,
                    amountPaise = 2_000_000L,
                    occurredAt = t0 - 2 * dayMs,
                    merchantKey = "atm kudlu main road",
                    bodySnippet = "Withdrawn Rs.20000 At +KUDLU MAIN ROAD",
                ),
            ),
        )
        assertEquals(listOf(10L), ids)
    }

    @Test
    fun insertPathDoesNotSkipBankWhenAnotherBankMatchesAmount() {
        val banks = listOf(25_000L to 1_000_000L)
        assertTrue(
            SmsDebitParser.isBankDebit(
                "Rs 250.00 debited from A/c XX1234 to FOO. Avl Bal Rs 1,000.",
                "VK-HDFCBK",
            ),
        )
        assertTrue(
            SmsDebitParser.hasMatchingBankDebit(25_000L, 1_000_000L + hour, banks, window),
        )
    }

    @Test
    fun parsesPhonePePaidToFormat() {
        val parsed = UpiNotificationParser.parse(
            title = "Paid ₹120",
            text = "Paid to Rahul Sharma",
            bigText = null,
        )
        assertNotNull(parsed)
        assertEquals(12_000L, parsed!!.amountPaise)
        assertEquals("Rahul Sharma", parsed.payee)
    }

    @Test
    fun parsesPhonePeSentToWithNote() {
        val parsed = UpiNotificationParser.parse(
            title = "Payment successful",
            text = "Sent to Cafe Coffee Day",
            bigText = "Sent Rs.350.00 to Cafe Coffee Day for Team lunch",
        )
        assertNotNull(parsed)
        assertEquals(35_000L, parsed!!.amountPaise)
        assertEquals("Cafe Coffee Day", parsed.payee)
        assertTrue(parsed.candidateNote.contains("team lunch", ignoreCase = true))
        assertEquals(
            "Cafe Coffee Day · ${parsed.candidateNote}",
            UpiNotificationParser.formatNote(parsed.payee, parsed.candidateNote),
        )
    }

    @Test
    fun noteIncludedInSearchMatch() {
        val tx = TransactionEntity(
            id = 1,
            smsId = 10,
            amountPaise = 5000,
            merchantRaw = "UPI",
            merchantKey = "upi",
            category = "OTHER",
            occurredAt = 1L,
            bodySnippet = "Rs 50 debited",
            sender = "VK-HDFCBK",
            note = "Bike taxi",
        )
        assertTrue(matchesSearch(tx, "bike"))
        assertTrue(matchesSearch(tx, "Taxi"))
        assertFalse(matchesSearch(tx, "grocery"))
    }

    /** Mirrors the Room search() LIKE fields, including note. */
    private fun matchesSearch(tx: TransactionEntity, q: String): Boolean {
        val needle = q.trim()
        if (needle.isEmpty()) return false
        return listOf(tx.merchantRaw, tx.bodySnippet, tx.category, tx.merchantKey, tx.note)
            .any { it.contains(needle, ignoreCase = true) }
    }

    private val dayMs = 24L * 60 * 60 * 1000
}
