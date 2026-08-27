package app.smspend

import app.smspend.data.DuplicateDecisionEntity
import app.smspend.data.GeminiCategorizer
import app.smspend.data.SmsDebitParser
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
    fun parseIdCategories_mapsIdsSkipsOtherAndUnknownIds() {
        val raw = """
            [
              {"id":1,"category":"Food & dining"},
              {"id":2,"category":"Other"},
              {"id":3,"category":"Health"},
              {"id":99,"category":"Travel"}
            ]
        """.trimIndent()
        val mapped = gemini.parseIdCategories(raw, expectedIds = setOf(1L, 2L, 3L))
        assertEquals(SpendCategory.FOOD_DINING.name, mapped[1L])
        assertFalse(mapped.containsKey(2L)) // Other → caller keeps provisional category
        assertEquals("custom:health", mapped[3L])
        assertFalse(mapped.containsKey(99L)) // id not in the batch
    }

    @Test
    fun parseIdCategories_acceptsFencedJson() {
        val raw = """
            ```json
            [{"id":7,"category":"Travel"}]
            ```
        """.trimIndent()
        val mapped = gemini.parseIdCategories(raw, expectedIds = setOf(7L))
        assertEquals(SpendCategory.TRAVEL.name, mapped[7L])
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
        assertEquals("SWIGGY", pairs[0].earlierMerchant)
        assertEquals("SWIGGY", pairs[0].laterMerchant)
        assertEquals(1_000_000L, pairs[0].earlierAt)
        assertEquals(1_000_000L + hour, pairs[0].laterAt)
    }

    @Test
    fun buildDuplicatePairCandidates_skipsAlreadyFlaggedLater() {
        val txs = listOf(
            txn(1, 10_000, 1_000L, "VK-HDFCBK", "a"),
            txn(2, 10_000, 2_000L, "VM-APP", "b", statusTag = GeminiCategorizer.DUPLICATE_NOTE),
        )
        assertTrue(SpendRepository.buildDuplicatePairCandidates(txs).isEmpty())
    }

    @Test
    fun buildDuplicatePairCandidates_skipsExcludedPairKeys() {
        val txs = listOf(
            txn(1, 25_000, 1_000_000L, "VK-HDFCBK", "swiggy"),
            txn(2, 25_000, 1_000_000L + 60_000, "VM-SWIGGY", "swiggy"),
        )
        val key = DuplicateDecisionEntity.pairKeyFor(txs[0], txs[1])
        assertTrue(
            SpendRepository.buildDuplicatePairCandidates(
                txs,
                excludedPairKeys = setOf(key),
            ).isEmpty(),
        )
        assertEquals(1, SpendRepository.buildDuplicatePairCandidates(txs).size)
    }

    @Test
    fun applyTagNetting_offsetsDebitCategoryByMatchingCreditTag() {
        val txs = listOf(
            txn(
                id = 1,
                amount = 5_000_00,
                at = 1_000L,
                sender = "AD-HDFCBK",
                merchantKey = "rent",
                note = "house rent",
                category = "BILLS",
            ),
            txn(
                id = 2,
                amount = 2_000_00,
                at = 2_000L,
                sender = "JK-BOBSMS",
                merchantKey = "wife",
                note = "house rent",
                category = "CREDITS",
                type = TransactionEntity.TYPE_CREDIT,
            ),
            txn(
                id = 3,
                amount = 500_00,
                at = 3_000L,
                sender = "AD-HDFCBK",
                merchantKey = "coffee",
                note = "",
                category = "FOOD_DINING",
            ),
        )
        val result = SpendRepository.applyTagNetting(txs)
        val bills = result.spends.first { it.bucket.key == "BILLS" }
        assertEquals(3_000_00, bills.totalPaise)
        assertEquals(1, result.offsets.size)
        assertEquals("house rent", result.offsets[0].tag)
        assertTrue(result.spends.none { it.bucket.key == "CREDITS" })
    }

    @Test
    fun applyTagNetting_ignoresSystemStatusTags() {
        val txs = listOf(
            txn(
                id = 1,
                amount = 1_000_00,
                at = 1L,
                sender = "AD-HDFCBK",
                merchantKey = "x",
                statusTag = GeminiCategorizer.DUPLICATE_NOTE,
                category = "SHOPPING",
            ),
            txn(
                id = 2,
                amount = 1_000_00,
                at = 2L,
                sender = "JK-BOBSMS",
                merchantKey = "y",
                statusTag = GeminiCategorizer.DUPLICATE_NOTE,
                category = "CREDITS",
                type = TransactionEntity.TYPE_CREDIT,
            ),
        )
        val result = SpendRepository.applyTagNetting(txs)
        assertTrue(result.offsets.isEmpty())
        // Duplicate-noted rows don't count toward totals at all.
        assertTrue(result.spends.isEmpty())
    }

    @Test
    fun cardBillBalance_noticeWhenBillPresent() {
        val txs = listOf(
            txn(
                id = 1,
                amount = 10_000_00,
                at = 1L,
                sender = "AD-HDFCBK",
                merchantKey = "cc",
                statusTag = SmsDebitParser.CARD_PAYMENT_NOTE,
                category = "BILLS",
            ),
            txn(
                id = 2,
                amount = 2_250_00,
                at = 2L,
                sender = "AD-HDFCBK",
                merchantKey = "cursor",
                cardLast4 = "4062",
                category = "SHOPPING",
            ),
        )
        val bal = SpendRepository.cardBillBalance(txs)!!
        assertEquals(10_000_00, bal.billPaise)
        assertEquals(2_250_00, bal.cardSpendPaise)
        assertTrue(bal.notice.contains("Card bill"))
    }

    private fun txn(
        id: Long,
        amount: Long,
        at: Long,
        sender: String,
        merchantKey: String,
        note: String = "",
        statusTag: String = "",
        cardLast4: String = "",
        category: String = "OTHER",
        type: String = TransactionEntity.TYPE_DEBIT,
    ) = TransactionEntity(
        id = id,
        smsId = id,
        amountPaise = amount,
        merchantRaw = merchantKey.uppercase(),
        merchantKey = merchantKey,
        category = category,
        occurredAt = at,
        bodySnippet = "Rs ${amount / 100} debited",
        sender = sender,
        dedupKey = "body:$id:$amount",
        note = note,
        statusTag = statusTag,
        cardLast4 = cardLast4,
        sourceKey = TransactionEntity.sourceKeyForSms(id),
        type = type,
    )
}
