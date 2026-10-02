package app.smspend

import app.smspend.data.AiSmsTriage
import app.smspend.data.GeminiCategorizer
import app.smspend.data.SmsDebitParser
import app.smspend.data.SmsTriageVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSmsTriageTest {
    private val canaraDr =
        "Dear Customer, Acct XXXX0755 Dr. INR 65.00 on 02/10/26 to Dream Tool R; UPI: 130598832435; Bal INR 5,470.71.Not you?SMS BLOCKUPI to 9901771222-CanaraBank"
    private val canaraDrZepto =
        "Dear Customer, Acct XXXX0755 Dr. INR 235.00 on 29/09/26 to ZEPTO MARKET; UPI: 130394991254; Bal INR 3,869.61.Not you?SMS BLOCKUPI to 9901771222-CanaraBank"
    private val canaraBalance =
        "Dear Customer, Acct XXXX0755 balance is INR 5,470.71 as on 02/10/26. Not you?SMS BLOCKUPI to 9901771222-CanaraBank"
    private val viPromo =
        "Get 2GB extra data on Rs.299 recharge. Recharge now on Vi app: vi.app/rc"
    private val otp =
        "123456 is your OTP for txn of Rs.500.00 at AMAZON on card XX1234. Do not share. -HDFC Bank"
    private val missedCall =
        "You have a missed call from +919876543210 at 10:42 AM on 02-Oct. Call back now."

    private val canaraTemplate = """Dr\. INR (?<amount>[\d,.]+) on .*? to (?<payee>[^;]+);"""

    @Test
    fun candidateFilter_keepsBankSmsAndDropsNoise() {
        assertTrue(AiSmsTriage.isCandidate(canaraDr, "AD-CANBNK-S"))
        assertFalse(AiSmsTriage.isCandidate(viPromo, "VM-VIINFO-P"))
        assertFalse(AiSmsTriage.isCandidate(otp, "AD-HDFCBK-T"))
        assertFalse(AiSmsTriage.isCandidate(missedCall, "VK-MISSED"))
        assertFalse(AiSmsTriage.isCandidate("Rs 50 due on 5th", "AD-CANBNK-S"))
        assertFalse(
            AiSmsTriage.isCandidate("Rs.1,200 will be debited from a/c XX0755 on 05-10 for SIP", "AD-CANBNK-S"),
        )
    }

    @Test
    fun dltSuffix_readsLastSingleLetterSegment() {
        assertEquals("P", AiSmsTriage.dltSuffix("VM-VIINFO-P"))
        assertEquals("S", AiSmsTriage.dltSuffix("AD-CANBNK-S"))
        assertNull(AiSmsTriage.dltSuffix("AD-CANBNK"))
    }

    @Test
    fun parseTriageVerdicts_keepsKnownIdsAndDropsJunk() {
        val raw = """
            [
              {"id": 1, "kind": "debit", "amount_rupees": 65.0, "payee": "Dream Tool R",
               "account_suffix": "0755", "ref": "130598832435", "template_regex": "$canaraTemplate"},
              {"id": 2, "kind": "NOT_TXN", "amount_rupees": null, "payee": null,
               "account_suffix": null, "ref": null, "template_regex": null},
              {"id": 1, "kind": "CREDIT", "amount_rupees": 10},
              {"id": 3, "kind": "REFUND", "amount_rupees": 10},
              {"id": 99, "kind": "DEBIT", "amount_rupees": 10}
            ]
        """.trimIndent().replace("\\", "\\\\")
        val verdicts = GeminiCategorizer().parseTriageVerdicts(raw, setOf(1L, 2L, 3L))
        assertEquals(2, verdicts.size)
        val debit = verdicts.first { it.id == 1L }
        assertEquals(SmsTriageVerdict.KIND_DEBIT, debit.kind)
        assertEquals(6_500L, debit.amountPaise)
        assertEquals("Dream Tool R", debit.payee)
        assertEquals("0755", debit.accountSuffix)
        assertEquals(canaraTemplate, debit.templateRegex)
        val notTxn = verdicts.first { it.id == 2L }
        assertEquals(SmsTriageVerdict.KIND_NOT_TXN, notTxn.kind)
        assertNull(notTxn.amountPaise)
        assertNull(notTxn.templateRegex)
    }

    @Test
    fun validateTemplate_acceptsAnchoredFormatThatReproducesAmount() {
        assertTrue(AiSmsTriage.validateTemplate(canaraTemplate, canaraDr, 6_500L, listOf(canaraBalance)))
    }

    @Test
    fun validateTemplate_rejectsGenericWrongOrOverlappingPatterns() {
        // No literal anchor: matches any number anywhere.
        assertFalse(AiSmsTriage.validateTemplate("""(?<amount>[\d,.]+)""", canaraDr, 6_500L))
        assertFalse(AiSmsTriage.validateTemplate("""INR (?<amount>[\d,.]+)""", canaraDr, 6_500L))
        // Picks the balance, not the spend.
        assertFalse(
            AiSmsTriage.validateTemplate("""Bal INR (?<amount>[\d,.]+)\.Not you""", canaraDr, 6_500L),
        )
        // No amount group / does not compile.
        assertFalse(AiSmsTriage.validateTemplate("""Dr\. INR [\d,.]+ on""", canaraDr, 6_500L))
        assertFalse(AiSmsTriage.validateTemplate("""Dr\. INR (?<amount>[\d,.]+""", canaraDr, 6_500L))
        // Also matches a body the AI called NOT_TXN for this sender.
        assertFalse(
            AiSmsTriage.validateTemplate(
                """Acct XXXX0755 .*?INR (?<amount>[\d,.]+)""",
                canaraDr,
                6_500L,
                listOf(canaraBalance),
            ),
        )
    }

    @Test
    fun template_matchesBuiltInCanaraParser() {
        for (body in listOf(canaraDr, canaraDrZepto)) {
            val builtIn = SmsDebitParser.parse(body, "AD-CANBNK-S")!!
            val learned = AiSmsTriage.applyTemplate(canaraTemplate, body)
            assertNotNull(learned)
            assertEquals(builtIn.amountPaise, learned!!.amountPaise)
            assertEquals(builtIn.merchantRaw, learned.payee)
        }
        assertNull(AiSmsTriage.applyTemplate(canaraTemplate, canaraBalance))
    }

    @Test
    fun amountAppearsIn_guardsInventedAmounts() {
        assertTrue(AiSmsTriage.amountAppearsIn(canaraDr, 6_500L))
        assertTrue(AiSmsTriage.amountAppearsIn(canaraDr, 547_071L))
        assertTrue(AiSmsTriage.amountAppearsIn("Rs.3000.00 debited", 300_000L))
        assertFalse(AiSmsTriage.amountAppearsIn(canaraDr, 6_000L))
    }

    @Test
    fun longestLiteralRun_countsOnlyRequiredLiterals() {
        assertEquals(0, AiSmsTriage.longestLiteralRun("""(?<amount>[\d,.]+)"""))
        assertTrue(AiSmsTriage.longestLiteralRun(canaraTemplate) >= AiSmsTriage.MIN_LITERAL_ANCHOR)
    }
}
