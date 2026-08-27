package app.smspend

import app.smspend.data.CreditSmsParser
import app.smspend.data.CrossSourcePoint
import app.smspend.data.SmsDebitParser
import app.smspend.data.TransactionEntity
import app.smspend.domain.IndianBanks
import app.smspend.domain.SpendCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyBanksAndPairingTest {
    @Test
    fun detectBanks_requiresMinSms() {
        val senders = buildList {
            repeat(25) { add("VM-HDFCBK-S") }
            repeat(25) { add("AD-KOTAKB-S") }
            repeat(5) { add("AD-AXISBK-S") }
            repeat(30) { add("JK-BOBSMS-S") }
        }
        val detected = IndianBanks.detectFromSenders(senders)
        assertTrue(detected.contains("hdfc"))
        assertTrue(detected.contains("kotak"))
        assertTrue(detected.contains("bob"))
        assertTrue(!detected.contains("axis"))
    }

    @Test
    fun opaqueOnlineBanking_merchantIsBankTransfer() {
        val neft =
            "Amt Deducted! Rs.20000 from your HDFC Bank A/c XX7660 for NEFT txn via HDFC Bank Online Banking Not you?Call 18002586161"
        val mt =
            "Amt Deducted! Rs.4000 from your HDFC Bank A/c XX7660 for Money Transfer via HDFC Bank Online Banking. Not you?Call 18002586161"
        assertEquals("Bank transfer", SmsDebitParser.parse(neft, "VM-HDFCBK-S")!!.merchantRaw)
        assertEquals("Bank transfer", SmsDebitParser.parse(mt, "JX-HDFCBK-S")!!.merchantRaw)
    }

    @Test
    fun creditPair_sharedUpiRefMarksDebit() {
        val debitBody =
            "Sent Rs.17000.00\nFrom HDFC Bank A/C *8002\nTo PRADHUL DEV K P\nOn 02/08/26\nRef 093346753350\n"
        val creditBody =
            "Dear BOB UPI User: Your account is credited with INR 17000.00 on 2026-08-02 10:14:20 AM by UPI Ref No 093346753350; AvlBal: Rs22038.01 - BOB"
        val t0 = 1_000_000L
        val debit = TransactionEntity(
            id = 1,
            smsId = 1,
            amountPaise = 1_700_000L,
            merchantRaw = "PRADHUL DEV K P",
            merchantKey = "pradhul dev k p",
            category = SpendCategory.TRANSFERS.name,
            occurredAt = t0,
            bodySnippet = debitBody,
            sender = "AD-HDFCBK-T",
            dedupKey = "x",
        )
        val credit = CreditSmsParser.parseCredit(creditBody, "JK-BOBSMS-S", t0 + 1_000)!!
        assertTrue(credit.merchantRaw.isNotBlank())
        val pairs = CreditSmsParser.selfTransferDebitIds(
            debits = listOf(debit),
            credits = listOf(credit),
            declaredBankIds = setOf("hdfc", "bob"),
        )
        assertEquals(1, pairs.size)
        assertEquals(1L, pairs[0].first)
        assertTrue(pairs[0].second.contains("Baroda", ignoreCase = true))
    }

    @Test
    fun federalCredit_amountTimePairWithoutRef() {
        val debitBody =
            "Sent Rs.20000.00\nFrom HDFC Bank A/C *8002\nTo PRADHUL DEV K P\nOn 02/08/26\nRef 307875364154\n"
        val creditBody =
            "Dear Customer, Rs.20000 credited to your A/c XX4528 on 02AUG2026 10:14:33. BAL-Rs.22801.10-Federal Bank"
        val t0 = 2_000_000L
        val debit = TransactionEntity(
            id = 2,
            smsId = 2,
            amountPaise = 2_000_000L,
            merchantRaw = "PRADHUL DEV K P",
            merchantKey = "pradhul dev k p",
            category = SpendCategory.TRANSFERS.name,
            occurredAt = t0,
            bodySnippet = debitBody,
            sender = "AD-HDFCBK-T",
            dedupKey = "y",
        )
        val credit = CreditSmsParser.parseCredit(creditBody, "AD-FEDBNK-S", t0 + 4_000)!!
        assertEquals(null, credit.upiRef)
        val pairs = CreditSmsParser.selfTransferDebitIds(
            debits = listOf(debit),
            credits = listOf(credit),
            declaredBankIds = setOf("hdfc", "federal"),
        )
        assertEquals(1, pairs.size)
    }

    @Test
    fun bankSameMerchant_doesNotCollapseOpaqueBankTransfers() {
        val t0 = 1_000_000L
        val a = CrossSourcePoint(
            id = 1,
            amountPaise = 2_000_000L,
            occurredAt = t0,
            merchantKey = "bank transfer",
            isBank = true,
            bodySnippet = "NEFT A",
        )
        val b = CrossSourcePoint(
            id = 2,
            amountPaise = 2_000_000L,
            occurredAt = t0 + 60_000,
            merchantKey = "bank transfer",
            isBank = true,
            bodySnippet = "NEFT B",
        )
        assertTrue(
            SmsDebitParser.bankSameMerchantDuplicateIds(listOf(a, b)).isEmpty(),
        )
    }
}
