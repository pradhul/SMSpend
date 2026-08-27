package app.smspend

import app.smspend.data.CategoryRules
import app.smspend.data.SmsDebitParser
import app.smspend.data.TransactionEntity
import app.smspend.domain.SpendCategory
import app.smspend.util.paiseToCompactRupees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsDebitParserTest {
    @Test
    fun parsesTypicalUpiDebit() {
        val body =
            "Dear Customer, Rs.486.00 debited from A/c XX1234 on 08-08-26 to SWIGGY BANGALORE via UPI. Not you? Call 1800."
        val parsed = SmsDebitParser.parse(body, "VK-HDFCBK")
        assertNotNull(parsed)
        assertEquals(48600L, parsed!!.amountPaise)
        assertEquals("swiggy bangalore", parsed.merchantKey)
    }

    @Test
    fun ignoresCredits() {
        val body = "INR 500.00 credited to your account XX99 from AMAZON PAY."
        assertNull(SmsDebitParser.parse(body, "VK-SBIIN"))
    }

    @Test
    fun parsesRdInstallment() {
        val body =
            "UPDATE: INR 10,000.00 debited from HDFC Bank XX8002 on 07-AUG-26. Info: XXXXXXXXXX0741- RD Installment-Aug 2026. Avl bal:INR 44,298.50"
        val parsed = SmsDebitParser.parse(body, "VK-HDFCBK")
        assertNotNull(parsed)
        assertEquals(1_000_000L, parsed!!.amountPaise)
        assertEquals(
            SpendCategory.SAVINGS,
            CategoryRules.match(parsed.merchantKey, parsed.merchantRaw, body),
        )
    }

    @Test
    fun parsesLiquorUpi() {
        val body =
            """
            Sent Rs.480.00
            From HDFC Bank A/C *8002
            To ADCO LIQUORS
            On 07/08/26
            Ref 558062522164
            """.trimIndent()
        val parsed = SmsDebitParser.parse(body, "VK-HDFCBK")
        assertNotNull(parsed)
        assertEquals(48000L, parsed!!.amountPaise)
        assertEquals(
            SpendCategory.ENTERTAINMENT,
            CategoryRules.match(parsed.merchantKey, parsed.merchantRaw, body),
        )
    }

    @Test
    fun parsesAutopaySiAsCardBillPayment() {
        val body =
            "UPDATE: INR 8,528.00 debited from HDFC Bank XX8002 on 05-AUG-26. Info: DC 1019060032735517 Autopay SI-TAD. Avl bal:INR 71,152.50"
        val parsed = SmsDebitParser.parse(body, "VK-HDFCBK")
        assertNotNull(parsed)
        assertEquals(852_800L, parsed!!.amountPaise)
        assertTrue(SmsDebitParser.isCardBillPayment(body, parsed.merchantRaw))
        // Card autopay is excluded from EMI rules so it is not double-counted as spend.
        assertNull(CategoryRules.match(parsed.merchantKey, parsed.merchantRaw, body))
    }

    @Test
    fun parsesBharatConnectMandate() {
        val body =
            """
            UPI Mandate:
            Sent Rs.1065.00
            from HDFC Bank A/c 8002
            To AutoPay  Bharat Connec
            06/08/26
            """.trimIndent()
        val parsed = SmsDebitParser.parse(body, "VK-HDFCBK")
        assertNotNull(parsed)
        assertEquals(106_500L, parsed!!.amountPaise)
        assertEquals(
            SpendCategory.BILLS,
            CategoryRules.match(parsed.merchantKey, parsed.merchantRaw, body),
        )
    }

    @Test
    fun parsesGrowwDeducted() {
        val body =
            "PAYMENT ALERT! INR 40000.00 deducted from HDFC Bank A/C No 8002 towards Groww UMRN: HDFC7021201210008279"
        val parsed = SmsDebitParser.parse(body, "VK-HDFCBK")
        assertNotNull(parsed)
        assertEquals(4_000_000L, parsed!!.amountPaise)
        assertTrue(parsed.merchantRaw.contains("Groww", ignoreCase = true))
        assertEquals(
            SpendCategory.SAVINGS,
            CategoryRules.match(parsed.merchantKey, parsed.merchantRaw, body),
        )
    }

    @Test
    fun rejectsLoanOfferPromoFromDltSender() {
        val body =
            "Congratulations! You are eligible for a pre-approved Personal Loan of Rs 32,00,000. Apply now, T&C apply."
        assertNull(SmsDebitParser.parse(body, "VM-HDFCBK"))
    }

    @Test
    fun rejectsBalanceAlert() {
        val body = "Your A/c XX1234 balance is Rs 1,23,456.78 as on 08-AUG-26."
        assertNull(SmsDebitParser.parse(body, "AD-SBIINB"))
    }

    @Test
    fun rejectsUpiCollectRequest() {
        val body = "John Doe has requested Rs 500.00 from you. Approve in your UPI app before 10-08."
        assertNull(SmsDebitParser.parse(body, "VM-PAYTMB"))
    }

    @Test
    fun rejectsOtpMessage() {
        val body = "Use OTP 482913 to complete payment of Rs 4,999.00 at FLIPKART. Do not share."
        assertNull(SmsDebitParser.parse(body, "VM-HDFCBK"))
    }

    @Test
    fun rejectsCardStatement() {
        val body = "Your HDFC card statement: total due Rs 45,000.00, min due Rs 2,250.00 is due on 15-08-26."
        assertNull(SmsDebitParser.parse(body, "VM-HDFCBK"))
    }

    @Test
    fun rejectsFutureMandateDebit() {
        val body = "Rs 649.00 will be debited from your A/c on 12-08-26 towards NETFLIX mandate."
        assertNull(SmsDebitParser.parse(body, "VM-HDFCBK"))
    }

    @Test
    fun parsesIndianGroupedAmount() {
        val body = "Rs 1,20,000.00 debited from A/c XX8002 on 08-08-26 to ACME BUILDERS via NEFT."
        val parsed = SmsDebitParser.parse(body, "VK-HDFCBK")
        assertNotNull(parsed)
        assertEquals(12_000_000L, parsed!!.amountPaise)
    }

    @Test
    fun picksTxnAmountWhenBalanceComesFirst() {
        val body = "A/c XX1234: Avl Bal Rs 52,340.50. Rs 250.00 debited for UPI txn to CHAI POINT."
        val parsed = SmsDebitParser.parse(body, "VK-ICICIB")
        assertNotNull(parsed)
        assertEquals(25_000L, parsed!!.amountPaise)
    }

    @Test
    fun picksTxnAmountWhenBalanceComesLast() {
        val body = "Rs 250.00 debited from A/c XX1234 for UPI to CHAI POINT. Avl Bal Rs 52,340.50."
        val parsed = SmsDebitParser.parse(body, "VK-ICICIB")
        assertNotNull(parsed)
        assertEquals(25_000L, parsed!!.amountPaise)
    }

    @Test
    fun normalizesSenderKeys() {
        assertEquals("HDFCBK", SmsDebitParser.senderKey("VM-HDFCBK"))
        assertEquals("HDFCBK", SmsDebitParser.senderKey("JD-HDFCBK-S"))
        assertEquals(SmsDebitParser.senderKey("VM-HDFCBK"), SmsDebitParser.senderKey("AD-HDFCBK"))
    }

    @Test
    fun extractsTxnRefFromRefNumber() {
        val body =
            """
            Sent Rs.480.00
            From HDFC Bank A/C *8002
            To ADCO LIQUORS
            On 07/08/26
            Ref 558062522164
            """.trimIndent()
        assertEquals("558062522164", SmsDebitParser.extractTxnRef(body))
    }

    @Test
    fun extractsTxnRefFromUpiRefNo() {
        val body = "Rs 250.00 debited via UPI. UPI Ref No 412345678901 to CHAI POINT."
        assertEquals("412345678901", SmsDebitParser.extractTxnRef(body))
    }

    @Test
    fun doesNotTreatUmrnAsTxnRef() {
        val body =
            "PAYMENT ALERT! INR 40000.00 deducted from HDFC Bank A/C No 8002 towards Groww UMRN: HDFC7021201210008279"
        assertNull(SmsDebitParser.extractTxnRef(body))
        val key = SmsDebitParser.dedupKey(body, 4_000_000L, dateMs = 1_000_000L)
        assertTrue(key.startsWith("body:"))
    }

    @Test
    fun dedupKeySameForDuplicateBodies() {
        val body = "Rs 250.00 debited from A/c XX1234 for UPI to CHAI POINT. Ref 998877665544."
        val a = SmsDebitParser.dedupKey(body, 25_000L, dateMs = 1_000L)
        val b = SmsDebitParser.dedupKey("  $body  ", 25_000L, dateMs = 9_999_999L)
        assertEquals(a, b)
        assertTrue(a.startsWith("ref:998877665544:"))
    }

    @Test
    fun dedupKeyDiffersWhenRefDiffers() {
        val a = SmsDebitParser.dedupKey(
            "Rs 100.00 debited to FOO. Ref 111111111111",
            10_000L,
            dateMs = 1_000L,
        )
        val b = SmsDebitParser.dedupKey(
            "Rs 100.00 debited to FOO. Ref 222222222222",
            10_000L,
            dateMs = 1_000L,
        )
        assertTrue(a != b)
    }

    @Test
    fun dedupKeyNoRefSameMinuteCollapses() {
        val body =
            "Amt Deducted! Rs.5000 from your HDFC Bank A/c XX7660 for NEFT txn via HDFC Bank Online Banking"
        val t = 1_752_000_000_000L
        val a = SmsDebitParser.dedupKey(body, 500_000L, t)
        val b = SmsDebitParser.dedupKey(body, 500_000L, t + 30_000L)
        assertEquals(a, b)
        assertTrue(a.startsWith("body:"))
    }

    @Test
    fun dedupKeyNoRefDifferentMinutesDiffer() {
        val body =
            "Amt Deducted! Rs.5000 from your HDFC Bank A/c XX7660 for NEFT txn via HDFC Bank Online Banking"
        val day1 = 1_752_000_000_000L
        val dayLater = day1 + 24L * 60 * 60 * 1000
        val a = SmsDebitParser.dedupKey(body, 500_000L, day1)
        val b = SmsDebitParser.dedupKey(body, 500_000L, dayLater)
        assertTrue(a != b)
    }

    @Test
    fun parsesNeftCreditedToPayee() {
        val body =
            "HDFC Bank : NEFT money transfer Txn No HDFCH01164497754 for Rs INR 5,000.00 has been credited to AMMA SBI on 01-08-2026 at 23:01:31"
        val parsed = SmsDebitParser.parse(body, "VM-HDFCBK-S")
        assertNotNull(parsed)
        assertEquals(500_000L, parsed!!.amountPaise)
        assertEquals("AMMA SBI", parsed.merchantRaw)
        assertEquals("amma sbi", parsed.merchantKey)
        assertEquals("HDFCH01164497754", SmsDebitParser.extractTxnRef(body))
        val key = SmsDebitParser.dedupKey(body, parsed.amountPaise, dateMs = 1L)
        assertTrue(key.startsWith("ref:HDFCH01164497754:"))
        assertEquals(
            SpendCategory.TRANSFERS,
            CategoryRules.match(parsed.merchantKey, parsed.merchantRaw, body),
        )
    }

    @Test
    fun rejectsInboundCreditedToYourAccount() {
        val body =
            "HDFC Bank : NEFT money transfer Txn No HDFCH999 for Rs INR 1,000.00 has been credited to your A/c XX7660 on 01-08-2026"
        assertNull(SmsDebitParser.parse(body, "VM-HDFCBK-S"))
        assertNull(SmsDebitParser.parseNeftCreditedToPayee(body))
    }

    @Test
    fun opaqueNeftLegCollapsedWhenNamedExists() {
        val opaqueBody =
            "Amt Deducted! Rs.5000 from your HDFC Bank A/c XX7660 for NEFT txn via HDFC Bank Online Banking"
        val namedBody =
            "HDFC Bank : NEFT money transfer Txn No HDFCH01164497754 for Rs INR 5,000.00 has been credited to AMMA SBI on 01-08-2026 at 23:01:31"
        val t = 1_752_000_000_000L
        val opaque = app.smspend.data.CrossSourcePoint(
            id = 1,
            amountPaise = 500_000L,
            occurredAt = t,
            merchantKey = "bank transfer",
            isBank = true,
            bodySnippet = opaqueBody,
        )
        val named = app.smspend.data.CrossSourcePoint(
            id = 2,
            amountPaise = 500_000L,
            occurredAt = t + 60_000L,
            merchantKey = "amma sbi",
            isBank = true,
            bodySnippet = namedBody,
        )
        assertEquals(
            listOf(1L),
            SmsDebitParser.opaqueNeftLegDuplicateIds(listOf(opaque, named)),
        )
    }

    @Test
    fun opaqueNeftLegKeptWhenNoNamedMatch() {
        val opaqueBody =
            "Amt Deducted! Rs.15000 from your HDFC Bank A/c XX7660 for NEFT txn via HDFC Bank Online Banking"
        val opaque = app.smspend.data.CrossSourcePoint(
            id = 1,
            amountPaise = 1_500_000L,
            occurredAt = 1_752_000_000_000L,
            merchantKey = "bank transfer",
            isBank = true,
            bodySnippet = opaqueBody,
        )
        assertTrue(SmsDebitParser.opaqueNeftLegDuplicateIds(listOf(opaque)).isEmpty())
    }

    @Test
    fun indianCompactRupeeFormat() {
        assertEquals("₹1,20,000", 12_000_000L.paiseToCompactRupees())
        assertEquals("₹32,00,000", 320_000_000L.paiseToCompactRupees())
        assertEquals("₹486", 48_600L.paiseToCompactRupees())
    }

    @Test
    fun customCategoryStorageKey() {
        val key = SpendCategory.customStorageKey("Liquor nights")
        assertTrue(SpendCategory.isCustomKey(key))
        assertEquals("Liquor nights", SpendCategory.displayLabel(key))
        assertEquals(
            SpendCategory.SAVINGS.name,
            SpendCategory.resolveStorageKey("Savings & investments", null),
        )
        assertTrue(
            SpendCategory.resolveStorageKey("My pets", SpendCategory.OTHER)
                .startsWith("custom:"),
        )
    }

    @Test
    fun isBankDebit_bankSenderAndGrowwMerchant() {
        val bank =
            "PAYMENT ALERT! INR 40000.00 deducted from HDFC Bank A/C No 8002 towards Groww UMRN: HDFC7021201210008279"
        val merchant = "Rs 40000.00 paid successfully towards Groww. Thank you."
        assertTrue(SmsDebitParser.isBankDebit(bank, "VK-HDFCBK"))
        assertFalse(SmsDebitParser.isBankDebit(merchant, "JD-GROWWAPP"))
        // Account phrase must not flip an app sender into "bank".
        assertFalse(
            SmsDebitParser.isBankDebit(
                "Rs 100 paid from your A/c via PhonePe to STORE",
                "VM-PHONEPE",
            ),
        )
    }

    @Test
    fun isCardBillPayment_matchesAutopayAndRails() {
        assertTrue(
            SmsDebitParser.isCardBillPayment(
                "INR 12,345.00 debited from A/c XX7660 towards HDFC Bank CreditCard Autopay SI-TAD",
                "HDFC Bank CreditCard",
            ),
        )
        assertTrue(
            SmsDebitParser.isCardBillPayment(
                "Sent Rs.5000.00 From HDFC Bank A/C *7660 To CRED Club On 01/08/26",
                "CRED Club",
            ),
        )
        assertTrue(
            SmsDebitParser.isCardBillPayment(
                "Rs.8000 debited via BillDesk for credit card payment",
                "BillDesk",
            ),
        )
    }

    @Test
    fun isCardBillPayment_doesNotMatchCardSpendSms() {
        val spend =
            "Spent Rs.2250.73 On HDFC Bank Card 4062 At CURSOR, AI POWERED IDE On 2026-08-08:13:16:51.Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 4062 to 7308080808"
        assertFalse(SmsDebitParser.isCardBillPayment(spend, "CURSOR"))
        val parsed = SmsDebitParser.parse(spend, "AD-HDFCBK-S")
        assertNotNull(parsed)
        assertEquals("Card ·4062", SmsDebitParser.cardSpendTag(spend, "CURSOR"))
    }

    @Test
    fun cardSpendTag_fallbackWithoutLast4() {
        val body = "Spent Rs.500 on your card at AMAZON PAY India"
        assertEquals("Card spend", SmsDebitParser.cardSpendTag(body))
    }

    @Test
    fun cardSpendTag_nullForBillPayment() {
        assertEquals(
            null,
            SmsDebitParser.cardSpendTag("Rs.8000 debited via BillDesk for credit card payment"),
        )
    }

    @Test
    fun extractCardLast4_fromSpentSms() {
        val spend =
            "Spent Rs.2250.73 On HDFC Bank Card 4062 At CURSOR, AI POWERED IDE On 2026-08-08:13:16:51.Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 4062 to 7308080808"
        assertEquals("4062", SmsDebitParser.extractCardLast4(spend))
    }

    @Test
    fun extractCardLast4_unknownWhenLast4Missing() {
        val body = "Spent Rs.500 on your card at AMAZON PAY India"
        assertEquals(TransactionEntity.CARD_LAST4_UNKNOWN, SmsDebitParser.extractCardLast4(body))
    }

    @Test
    fun directionalDedupKey_separatesCreditAndDebit() {
        val body = "Rs 250.00 debited to FOO. Ref 998877665544."
        val debit = SmsDebitParser.directionalDedupKey(body, 25_000L, 1_000L, isCredit = false)
        val credit = SmsDebitParser.directionalDedupKey(body, 25_000L, 1_000L, isCredit = true)
        assertTrue(debit.startsWith("DEBIT:"))
        assertTrue(credit.startsWith("CREDIT:"))
        assertTrue(debit != credit)
    }

    @Test
    fun cardPaymentNoteExcludedFromTotals() {
        val tx = TransactionEntity(
            id = 1,
            smsId = 1,
            amountPaise = 10_000,
            merchantRaw = "HDFC CreditCard",
            merchantKey = "hdfc creditcard",
            category = "BILLS",
            occurredAt = 1L,
            bodySnippet = "credit card autopay",
            sender = "AD-HDFCBK-S",
            statusTag = SmsDebitParser.CARD_PAYMENT_NOTE,
        )
        assertTrue(tx.isCardBillPaymentNote())
        assertFalse(tx.countsTowardTotals())
    }

    @Test
    fun legacyFoodDeliveryMaps() {
        assertEquals(SpendCategory.FOOD_DINING, SpendCategory.fromStored("FOOD_DELIVERY"))
    }
}
