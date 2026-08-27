package app.smspend

import app.smspend.data.CategoryRules
import app.smspend.data.GeminiCategorizer
import app.smspend.data.SelfTransferMatcher
import app.smspend.data.SmsDebitParser
import app.smspend.data.TransactionEntity
import app.smspend.domain.SpendCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpCategorizationTest {
    @Test
    fun atmWithdrawal_extractsPlaceNotHelpline() {
        val body =
            "Withdrawn Rs.20000 From HDFC Bank Card x1868 At +KUDLU MAIN ROAD on 01-08-26. SMS BLOCK DC 1868 to 7308080808"
        val parsed = SmsDebitParser.parse(body, "VK-HDFCBK")
        assertNotNull(parsed)
        assertEquals(2_000_000L, parsed!!.amountPaise)
        assertTrue(parsed.merchantRaw.startsWith("ATM ", ignoreCase = true))
        assertTrue(parsed.merchantRaw.contains("KUDLU", ignoreCase = true))
        assertFalse(parsed.merchantRaw.contains("7308080808"))
        assertEquals(
            SpendCategory.WITHDRAWALS,
            CategoryRules.match(parsed.merchantKey, parsed.merchantRaw, body),
        )
    }

    @Test
    fun yesBankUpi_prefersVpaOverInrFragment() {
        val body =
            "Your A/c XX1234 is debited for INR 2,450.00 on 08-08-26. UPI:123456789012 To:9986981757@ybl. Avl Bal INR 10,000.00"
        val parsed = SmsDebitParser.parse(body, "VM-YESBNK")
        assertNotNull(parsed)
        assertEquals(245_000L, parsed!!.amountPaise)
        assertEquals("9986981757@ybl", parsed.merchantRaw)
        assertFalse(parsed.merchantRaw.startsWith("INR", ignoreCase = true))
    }

    @Test
    fun dumpRules_entertainmentGroceryTravelSavings() {
        assertEquals(
            SpendCategory.ENTERTAINMENT,
            CategoryRules.match("spotify", "SPOTIFY", "Rs 119 debited to SPOTIFY"),
        )
        assertEquals(
            SpendCategory.GROCERY,
            CategoryRules.match("licious", "LICIOUS", "paid to LICIOUS"),
        )
        assertEquals(
            SpendCategory.TRAVEL,
            CategoryRules.match("fastag", "FASTAG", "FASTag toll paid"),
        )
        assertEquals(
            SpendCategory.SAVINGS,
            CategoryRules.match("rd", "RD", "transfer to rd account"),
        )
        assertEquals(
            SpendCategory.TRANSFERS,
            CategoryRules.match(
                "money transfer",
                "Money Transfer",
                "Amt Deducted! for Money Transfer via HDFC Bank Online Banking",
            ),
        )
        assertEquals(
            SpendCategory.TRANSFERS,
            CategoryRules.match(
                "neft txn",
                "NEFT txn",
                "Amt Deducted! Rs.20000 from your HDFC Bank A/c XX7660 for NEFT txn via HDFC Bank Online Banking",
            ),
        )
    }

    @Test
    fun selfTransferMatcher_fuzzyPartialAndConcatenated() {
        assertTrue(SelfTransferMatcher.matches("PRADHUL DEV K P", "pradhul"))
        assertTrue(SelfTransferMatcher.matches("PRADHULDEV K P", "Pradhul Dev K P, PRADHULDEV"))
        assertTrue(SelfTransferMatcher.matches("PRADHULDEV K P", "pradhul"))
        assertFalse(SelfTransferMatcher.matches("PRADHUL DEV K P", "dev")) // too short
        assertFalse(SelfTransferMatcher.matches("PRADHUL TRADERS", "pradhul"))
        assertFalse(SelfTransferMatcher.matches("RAHUL SHARMA", "pradhul"))
    }

    @Test
    fun selfTransfer_notCountedAsSpend() {
        assertFalse(SpendCategory.SELF_TRANSFER.countsAsSpend)
        assertFalse(SpendCategory.countsAsSpend(SpendCategory.SELF_TRANSFER.name))
        assertTrue(SpendCategory.TRANSFERS.countsAsSpend)
    }

    @Test
    fun savedTotal_onlySavingsNotSelfTransfer() {
        assertTrue(SpendCategory.SAVINGS.countsAsSaved)
        assertTrue(SpendCategory.countsAsSaved(SpendCategory.SAVINGS.name))
        assertFalse(SpendCategory.SELF_TRANSFER.countsAsSaved)
        assertFalse(SpendCategory.countsAsSaved(SpendCategory.SELF_TRANSFER.name))
        assertFalse(SpendCategory.countsAsSaved("custom:health"))
        assertTrue(SpendCategory.isSelfTransfer(SpendCategory.SELF_TRANSFER.name))
        assertFalse(SpendCategory.isSelfTransfer(SpendCategory.SAVINGS.name))
    }

    @Test
    fun precedence_rdWithOwnName_staysSavings() {
        val body =
            "INR 20,000.00 debited from YES BANK Acc XX2028 on 06-AUG-2026. Transfer To RD XX0715 SWATHI P V. Avl bal-INR 1,22,449.48."
        val merchantRaw = "RD XX0715 SWATHI P V"
        val merchantKey = SmsDebitParser.normalizeMerchantKey(merchantRaw)
        assertEquals(
            SpendCategory.SAVINGS,
            SelfTransferMatcher.resolveWithRules(
                merchantKey = merchantKey,
                merchantRaw = merchantRaw,
                body = body,
                aliasesCsv = "Swathi, SWATHI P V",
            ),
        )
    }

    @Test
    fun precedence_plainUpiToOwnName_isSelfTransfer() {
        val body =
            """
            Sent Rs.20000.00
            From HDFC Bank A/C *7660
            To SWATHI P  V
            On 01/08/26
            Ref 230630774391
            """.trimIndent()
        val merchantRaw = "SWATHI P  V"
        val merchantKey = SmsDebitParser.normalizeMerchantKey(merchantRaw)
        assertEquals(
            SpendCategory.SELF_TRANSFER,
            SelfTransferMatcher.resolveWithRules(
                merchantKey = merchantKey,
                merchantRaw = merchantRaw,
                body = body,
                aliasesCsv = "Swathi",
            ),
        )
    }

    @Test
    fun geminiCustomHealthEducation_resolvesStorageKeys() {
        val gemini = GeminiCategorizer()
        val mapped = gemini.parseBatchCategoryKeys(
            """
            {
              "apollo pharmacy": "Health",
              "gsm education trust": "Education",
              "rahul": "Transfers",
              "mystery": "Other"
            }
            """.trimIndent(),
            listOf("apollo pharmacy", "gsm education trust", "rahul", "mystery"),
        )
        assertEquals("custom:health", mapped["apollo pharmacy"])
        assertEquals("custom:education", mapped["gsm education trust"])
        assertEquals(SpendCategory.TRANSFERS.name, mapped["rahul"])
        assertFalse(mapped.containsKey("mystery"))
        assertEquals("Health", SpendCategory.displayLabel("custom:health"))
    }

    @Test
    fun highConfidence_beatsMemory_atmAndRdAndNeftAndJewellery() {
        val atmBody =
            "Withdrawn Rs.20000 From HDFC Bank Card x1868 At +KUDLU MAIN ROAD On 2026-08-04"
        assertTrue(
            CategoryRules.beatsMemory(
                SpendCategory.WITHDRAWALS,
                "atm kudlu main road",
                "ATM KUDLU MAIN ROAD",
                atmBody,
            ),
        )
        val rdBody = "Transfer To RD XX0715 SWATHI P V. Avl bal-INR 1,22,449.48."
        assertTrue(
            CategoryRules.beatsMemory(
                SpendCategory.SAVINGS,
                "rd xx0715 swathi p v",
                "RD XX0715 SWATHI P V",
                rdBody,
            ),
        )
        val neftBody =
            "Amt Deducted! Rs.20000 from your HDFC Bank A/c XX7660 for NEFT txn via HDFC Bank Online Banking"
        assertTrue(
            CategoryRules.beatsMemory(
                SpendCategory.TRANSFERS,
                "bank transfer",
                "Bank transfer",
                neftBody,
            ),
        )
        assertTrue(
            CategoryRules.beatsMemory(
                SpendCategory.SHOPPING,
                "khazana jewellery pvt ltd",
                "Khazana Jewellery Pvt Ltd",
                "Sent Rs.15000 To Khazana Jewellery Pvt Ltd",
            ),
        )
        assertTrue(
            CategoryRules.beatsMemory(
                SpendCategory.SHOPPING,
                "malabar gold pvt ltd",
                "Malabar Gold Pvt Ltd",
                "Sent Rs.20000 To Malabar Gold Pvt Ltd",
            ),
        )
        // Ordinary UPI transfer label must not override a user retag/memory.
        assertFalse(
            CategoryRules.beatsMemory(
                SpendCategory.TRANSFERS,
                "rakshitha p m",
                "Rakshitha P M",
                "Sent Rs.1000 To Rakshitha P M",
            ),
        )
    }

    @Test
    fun jewelleryAndMalabar_rulesToShopping() {
        assertEquals(
            SpendCategory.SHOPPING,
            CategoryRules.match(
                "khazana jewellery pvt ltd",
                "Khazana Jewellery Pvt Ltd",
                "Sent Rs.15000 To Khazana Jewellery Pvt Ltd",
            ),
        )
        assertEquals(
            SpendCategory.SHOPPING,
            CategoryRules.match(
                "malabar gold pvt ltd",
                "Malabar Gold Pvt Ltd",
                "Sent Rs.20000 To Malabar Gold Pvt Ltd",
            ),
        )
    }

    @Test
    fun opaqueNeft_amtDeductedWithoutOnlineBankingPhrase_stillBankTransfer() {
        val body =
            "Amt Deducted! Rs.20000 from your HDFC Bank A/c XX7660 for NEFT txn. Not you? Call 18002586161"
        assertEquals("Bank transfer", SmsDebitParser.opaqueBankTransferMerchant(body))
        val parsed = SmsDebitParser.parse(body, "VM-HDFCBK-S")
        assertNotNull(parsed)
        assertEquals("Bank transfer", parsed!!.merchantRaw)
        assertEquals(
            SpendCategory.TRANSFERS,
            CategoryRules.match(parsed.merchantKey, parsed.merchantRaw, body),
        )
    }

    @Test
    fun aug1SwathiUpi_isNotNeft() {
        val body =
            """
            Sent Rs.20000.00
            From HDFC Bank A/C *7660
            To SWATHI P  V
            On 01/08/26
            Ref 230630774391
            """.trimIndent()
        assertEquals(null, SmsDebitParser.opaqueBankTransferMerchant(body))
        val parsed = SmsDebitParser.parse(body, "JM-HDFCBK-T")
        assertNotNull(parsed)
        assertEquals("SWATHI P  V", parsed!!.merchantRaw)
        assertEquals(
            SpendCategory.SELF_TRANSFER,
            SelfTransferMatcher.resolveWithRules(
                parsed.merchantKey,
                parsed.merchantRaw,
                body,
                "Swathi",
            ),
        )
    }

    @Test
    fun duplicateAndFailedNotes_excludedFromTotals() {
        val base = TransactionEntity(
            id = 1,
            smsId = 1,
            amountPaise = 100_00,
            merchantRaw = "X",
            merchantKey = "x",
            category = SpendCategory.SAVINGS.name,
            occurredAt = 1L,
            bodySnippet = "x",
            sender = "AD-HDFCBK-S",
        )
        assertTrue(base.countsTowardTotals())
        assertFalse(base.copy(statusTag = GeminiCategorizer.DUPLICATE_NOTE).countsTowardTotals())
        assertFalse(base.copy(statusTag = SmsDebitParser.ATM_FAILED_NOTE).countsTowardTotals())
        assertFalse(base.copy(statusTag = SmsDebitParser.CARD_PAYMENT_NOTE).countsTowardTotals())
    }
}
