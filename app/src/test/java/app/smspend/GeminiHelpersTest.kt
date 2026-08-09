package app.smspend

import app.smspend.data.GeminiCategorizer
import app.smspend.data.SpendRepository
import app.smspend.data.TransactionEntity
import app.smspend.domain.SpendCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiHelpersTest {
    private val gemini = GeminiCategorizer()

    @Test
    fun parseCategoryLabel_stripsMarkdownAndPunctuation() {
        assertEquals(SpendCategory.FOOD_DINING, gemini.parseCategoryLabel("**Food & dining**"))
        assertEquals(SpendCategory.TRANSFERS, gemini.parseCategoryLabel("`Transfers`."))
        assertEquals(SpendCategory.SAVINGS, gemini.parseCategoryLabel("Savings & investments\nextra"))
    }

    @Test
    fun parseBatchCategories_mapsKeysAndSkipsOther() {
        val raw = """
            {
              "swiggy bangalore": "Food & dining",
              "rahul sharma": "Transfers",
              "mystery": "Other",
              "GROWW": "Savings & investments"
            }
        """.trimIndent()
        val mapped = gemini.parseBatchCategories(
            raw,
            listOf("swiggy bangalore", "rahul sharma", "mystery", "groww"),
        )
        assertEquals(SpendCategory.FOOD_DINING, mapped["swiggy bangalore"])
        assertEquals(SpendCategory.TRANSFERS, mapped["rahul sharma"])
        assertFalse(mapped.containsKey("mystery"))
        assertEquals(SpendCategory.SAVINGS, mapped["groww"])
    }

    @Test
    fun labelToStorageKey_presetsAndCustom() {
        assertEquals(SpendCategory.FOOD_DINING.name, gemini.labelToStorageKey("Food & dining"))
        assertEquals(SpendCategory.SELF_TRANSFER.name, gemini.labelToStorageKey("Self transfer"))
        assertEquals("custom:health", gemini.labelToStorageKey("Health"))
        assertEquals(null, gemini.labelToStorageKey("Other"))
    }

    @Test
    fun parseBatchCategories_acceptsFencedJson() {
        val raw = """
            ```json
            {"uber trip":"Travel"}
            ```
        """.trimIndent()
        val mapped = gemini.parseBatchCategories(raw, listOf("uber trip"))
        assertEquals(SpendCategory.TRAVEL, mapped["uber trip"])
    }

    @Test
    fun parseDuplicateFlags_collectsSamePaymentIds() {
        val raw = """
            [
              {"pair_id":"1-2","same_payment":true},
              {"pair_id":"3-4","same_payment":false},
              {"pair_id":"5-6","same_payment":true}
            ]
        """.trimIndent()
        val ids = gemini.parseDuplicateFlags(raw)
        assertEquals(setOf("1-2", "5-6"), ids)
    }

    @Test
    fun buildDuplicatePairCandidates_sameAmountWithinWindow() {
        val hour = 60L * 60 * 1000
        val txs = listOf(
            txn(1, 25_000, 1_000_000L, "VK-HDFCBK", "swiggy"),
            txn(2, 25_000, 1_000_000L + hour, "VM-SWIGGY", "swiggy"),
            txn(3, 25_000, 1_000_000L + 30 * hour, "VK-HDFCBK", "other"), // outside 24h
            txn(4, 50_000, 1_000_000L + hour, "VK-HDFCBK", "fuel"), // different amount
        )
        val pairs = SpendRepository.buildDuplicatePairCandidates(txs, windowMs = 24 * hour)
        assertEquals(1, pairs.size)
        assertEquals(1L, pairs[0].earlierId)
        assertEquals(2L, pairs[0].laterId)
    }

    @Test
    fun buildDuplicatePairCandidates_skipsAlreadyFlaggedLater() {
        val txs = listOf(
            txn(1, 10_000, 1_000L, "VK-HDFCBK", "a"),
            txn(2, 10_000, 2_000L, "VM-APP", "b", note = GeminiCategorizer.DUPLICATE_NOTE),
        )
        assertTrue(SpendRepository.buildDuplicatePairCandidates(txs).isEmpty())
    }

    private fun txn(
        id: Long,
        amount: Long,
        at: Long,
        sender: String,
        merchantKey: String,
        note: String = "",
    ) = TransactionEntity(
        id = id,
        smsId = id,
        amountPaise = amount,
        merchantRaw = merchantKey.uppercase(),
        merchantKey = merchantKey,
        category = "OTHER",
        occurredAt = at,
        bodySnippet = "Rs ${amount / 100} debited",
        sender = sender,
        dedupKey = "body:$id:$amount",
        note = note,
    )
}
