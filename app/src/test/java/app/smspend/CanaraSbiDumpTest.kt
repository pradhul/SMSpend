package app.smspend

import app.smspend.data.CategoryRules
import app.smspend.data.CreditSmsParser
import app.smspend.data.SmsDebitParser
import app.smspend.data.TransactionEntity
import app.smspend.domain.SpendCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bodies taken verbatim from the V2427 inbox dump (Oct 2026). */
class CanaraSbiDumpTest {
    private val drToday =
        "Dear Customer, Acct XXXX0755 Dr. INR 65.00 on 02/10/26 to Dream Tool R; UPI: 130598832435; Bal INR 5,470.71.Not you?SMS BLOCKUPI to 9901771222-CanaraBank"
    private val drZepto =
        "Dear Customer, Acct XXXX0755 Dr. INR 235.00 on 29/09/26 to ZEPTO MARKET; UPI: 130394991254; Bal INR 3,869.61.Not you?SMS BLOCKUPI to 9901771222-CanaraBank"
    private val drGrouped =
        "Dear Customer, Acct XXXX0755 Dr. INR 2,000.00 on 25/09/26 to JALMAHAL RES; UPI: 130219711480; Bal INR 2,924.15.Not you?SMS BLOCKUPI to 9901771222-CanaraBank"
    private val crImps =
        "A/c XX755 Cr INR 3,000.00 on 23/09/26 from ARYA DEVI K; IMPS: 626616662833; Bal INR 6,163.91-CanaraBank"
    private val sbiPsg =
        "Dear Customer, Your a/c no. XXXXXXXX3296 is debited for Rs.3000.00 on 23-09-26 and a/c XXXXXXX755 credited (IMPS Ref no 626616662833). -SBI"
    private val sbiPsgOct =
        "Dear Customer, Your a/c no. XXXXXXXX3296 is debited for Rs.5000.00 on 01-10-26 and a/c XXXXXXX755 credited (IMPS Ref no 627411629601). -SBI"
    private val yono =
        "Your Ac Xx3296 debited Rs.5,000.00 for transfer to Aryabd Ac Xx0755 dt 01.10.26 Ref 627411629601. If not done by you, call 1800111109. YONO SBI"
    private val interest =
        "INR 263.00 credited to A/c XXXX0755 on 28/09/2026 towards Interest. Total Avail. Bal. INR 4,104.61. -Canara Bank"

    @Test
    fun canaraDr_parsesAmountAndPayeeNotBalance() {
        val today = SmsDebitParser.parse(drToday, "AD-CANBNK-S")
        assertNotNull(today)
        assertEquals(6_500L, today!!.amountPaise)
        assertEquals("Dream Tool R", today.merchantRaw)

        val zepto = SmsDebitParser.parse(drZepto, "AD-CANBNK-S")!!
        assertEquals(23_500L, zepto.amountPaise)
        assertEquals("ZEPTO MARKET", zepto.merchantRaw)
        assertEquals(SpendCategory.GROCERY, CategoryRules.match(zepto.merchantKey, zepto.merchantRaw, drZepto))

        assertEquals(200_000L, SmsDebitParser.parse(drGrouped, "AD-CANBNK-S")!!.amountPaise)
        assertTrue(SmsDebitParser.isBankDebit(drToday, "AD-CANBNK-S"))
    }

    @Test
    fun canaraDr_upiRefDedupsRepeatAlert() {
        assertEquals("130598832435", SmsDebitParser.extractTxnRef(drToday))
        assertEquals(
            SmsDebitParser.dedupKey(drToday, 6_500L, 1_000L),
            SmsDebitParser.dedupKey(drToday, 6_500L, 999_999_999L),
        )
    }

    @Test
    fun canaraDr_isNotACredit() {
        assertNull(CreditSmsParser.parseCredit(drToday, "AD-CANBNK-S", 0L))
    }

    @Test
    fun canaraCr_parsesAsCreditAndSharesImpsRefWithSbiDebit() {
        val credit = CreditSmsParser.parseCredit(crImps, "AD-CANBNK-S", 0L)
        assertNotNull(credit)
        assertEquals(300_000L, credit!!.amountPaise)
        assertEquals("ARYA DEVI K", credit.merchantRaw)
        assertEquals("canara", credit.bankId)
        assertEquals("626616662833", credit.upiRef)
        assertEquals("626616662833", CreditSmsParser.extractUpiRef(sbiPsg))
        assertNull(SmsDebitParser.parse(crImps, "AD-CANBNK-S"))
    }

    @Test
    fun canaraCr_pairsWithSbiDebitAsSelfTransfer() {
        val t0 = 1_000_000L
        val debit = TransactionEntity(
            id = 7,
            smsId = 7,
            amountPaise = 300_000L,
            merchantRaw = "JX-SBIPSG-T",
            merchantKey = "jx sbipsg t",
            category = SpendCategory.TRANSFERS.name,
            occurredAt = t0,
            bodySnippet = sbiPsg,
            sender = "JX-SBIPSG-T",
            dedupKey = "x",
        )
        val credit = CreditSmsParser.parseCredit(crImps, "AD-CANBNK-S", t0 + 60_000)!!
        val pairs = CreditSmsParser.selfTransferDebitIds(listOf(debit), listOf(credit), setOf("canara"))
        assertEquals(1, pairs.size)
        assertEquals(7L, pairs[0].first)
    }

    @Test
    fun otpAndMandate_stillIgnored() {
        val otp =
            "OTP is 731662 for fund transfer of Rs.5,000.00 from Ac x3296 to Aryabde Ac x0755. Not done by you?Call 1800111109. Don't share OTP with anyone.YONO, SBI"
        val mandate =
            "For the upcoming mandate set for 13/04/2026 ,your account will be debited with Rs.599.00 towards Adobe Systems Software Ireland Limited for the UPI Mandate."
        assertNull(SmsDebitParser.parse(otp, "JM-SBYONO-S"))
        assertNull(SmsDebitParser.parse(mandate, "AD-CANBNK-S"))
    }

    @Test
    fun ownAccountImps_destinationMatchesKnownCanaraAccount() {
        val own = setOfNotNull(
            SmsDebitParser.extractCreditedAccount(interest),
            SmsDebitParser.extractDebitedAccount(drToday),
            SmsDebitParser.extractCreditedAccount(crImps),
        )
        assertEquals(setOf("0755", "755"), own)

        val dest = SmsDebitParser.extractDestinationAccount(sbiPsgOct)
        assertEquals("755", dest)
        assertEquals("3296", SmsDebitParser.extractDebitedAccount(sbiPsgOct))
        assertTrue(own.any { SmsDebitParser.sameAccountSuffix(it, dest!!) })
        assertFalse(SmsDebitParser.sameAccountSuffix("3296", dest!!))

        assertEquals("0755", SmsDebitParser.extractDestinationAccount(yono))
        assertNull(SmsDebitParser.extractDestinationAccount(drToday))
    }

    @Test
    fun sbiPsg_merchantIsSenderId_yonoNamesPayee() {
        val psg = SmsDebitParser.parse(sbiPsgOct, "JD-SBIPSG-T")!!
        assertTrue(SmsDebitParser.isSenderIdMerchant(psg.merchantRaw, "JD-SBIPSG-T"))
        val named = SmsDebitParser.parse(yono, "JD-SBYONO-S")!!
        assertEquals("Aryabd", named.merchantRaw)
        assertEquals(500_000L, named.amountPaise)
        assertEquals(
            SmsDebitParser.dedupKey(sbiPsgOct, 500_000L, 1L),
            SmsDebitParser.dedupKey(yono, 500_000L, 2L),
        )
    }
}
