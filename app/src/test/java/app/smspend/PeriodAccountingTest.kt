package app.smspend

import app.smspend.data.GeminiCategorizer
import app.smspend.data.SmsDebitParser
import app.smspend.data.TransactionEntity
import app.smspend.domain.PeriodAccounting
import app.smspend.domain.SpendCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodAccountingTest {
    @Test
    fun partialCreditNetting_leavesResidualCredit() {
        val txs = listOf(
            debit(id = 1, amount = 200_00, note = "house rent"),
            credit(id = 2, amount = 500_00, note = "house rent"),
        )
        val net = PeriodAccounting.netPeriod(txs)
        assertEquals(0L, net.netSpendPaise)
        assertEquals(1, net.residualCredits.size)
        assertEquals(300_00, net.residualCredits.single().amountPaise)
        assertEquals(200_00, net.nettedPaise)
    }

    @Test
    fun manualCreditCategory_doesNotInflateCategoryTotals() {
        val txs = listOf(
            debit(id = 1, amount = 1_000_00, category = SpendCategory.FOOD_DINING.name),
            credit(
                id = 2,
                amount = 500_00,
                category = SpendCategory.FOOD_DINING.name,
            ),
        )
        val totals = PeriodAccounting.categoryTotals(txs)
        assertEquals(1_000_00L, totals[SpendCategory.FOOD_DINING.name])
        assertFalse(totals.containsKey(SpendCategory.CREDITS.name))
    }

    @Test
    fun pairedSelfTransfer_countsOnlyOnce() {
        val txs = listOf(
            debit(
                id = 1,
                amount = 10_000_00,
                statusTag = "Paired · Baroda",
                category = SpendCategory.TRANSFERS.name,
            ),
            credit(
                id = 2,
                amount = 10_000_00,
                statusTag = "Paired · Baroda",
                category = SpendCategory.CREDITS.name,
            ),
        )
        val net = PeriodAccounting.netPeriod(txs)
        assertEquals(0L, net.netSpendPaise)
        assertTrue(net.countableDebits.isEmpty())
        assertTrue(net.residualCredits.isEmpty())
        assertTrue(PeriodAccounting.isPairedSelfTransferLeg(txs[0]))
        assertTrue(PeriodAccounting.isPairedSelfTransferLeg(txs[1]))
    }

    @Test
    fun statusTags_doNotParticipateInTagNetting() {
        val txs = listOf(
            debit(id = 1, amount = 500_00, note = "house rent"),
            credit(
                id = 2,
                amount = 500_00,
                note = "",
                statusTag = SmsDebitParser.SELF_RD_FROM_NOTE,
            ),
        )
        val net = PeriodAccounting.netPeriod(txs)
        assertEquals(500_00, net.netSpendPaise)
        assertTrue(net.residualCredits.isEmpty())
        assertEquals(0L, net.nettedPaise)

        val legacyStatusInNote = listOf(
            debit(id = 3, amount = 300_00, note = "refund"),
            credit(
                id = 4,
                amount = 300_00,
                note = GeminiCategorizer.DUPLICATE_NOTE,
            ),
        )
        val legacyNet = PeriodAccounting.netPeriod(legacyStatusInNote)
        assertEquals(300_00, legacyNet.netSpendPaise)
        assertEquals(1, legacyNet.residualCredits.size)
        assertEquals(0L, legacyNet.nettedPaise)
    }

    @Test
    fun multiTagRows_netOnAnySharedTag() {
        val txs = listOf(
            debit(id = 1, amount = 400_00, note = "trip, rahul"),
            credit(id = 2, amount = 400_00, note = "Rahul"),
        )
        val net = PeriodAccounting.netPeriod(txs)
        assertEquals(0L, net.netSpendPaise)
        assertTrue(net.residualCredits.isEmpty())
        assertEquals(400_00, net.nettedPaise)
    }

    @Test
    fun multiTagCredit_netsAgainstSingleTagDebit() {
        val txs = listOf(
            debit(id = 1, amount = 300_00, note = "dinner"),
            credit(id = 2, amount = 500_00, note = "dinner, split"),
        )
        val net = PeriodAccounting.netPeriod(txs)
        assertEquals(0L, net.netSpendPaise)
        assertEquals(1, net.residualCredits.size)
        assertEquals(200_00, net.residualCredits.single().amountPaise)
        assertEquals(300_00, net.nettedPaise)
    }

    @Test
    fun disjointTags_doNotNet() {
        val txs = listOf(
            debit(id = 1, amount = 300_00, note = "dinner, trip"),
            credit(id = 2, amount = 300_00, note = "rent"),
        )
        val net = PeriodAccounting.netPeriod(txs)
        assertEquals(300_00, net.netSpendPaise)
        assertEquals(1, net.residualCredits.size)
        assertEquals(0L, net.nettedPaise)
    }

    private fun debit(
        id: Long,
        amount: Long,
        note: String = "",
        statusTag: String = "",
        category: String = SpendCategory.OTHER.name,
    ) = txn(id, amount, note, statusTag, category, TransactionEntity.TYPE_DEBIT)

    private fun credit(
        id: Long,
        amount: Long,
        note: String = "",
        statusTag: String = "",
        category: String = SpendCategory.CREDITS.name,
    ) = txn(id, amount, note, statusTag, category, TransactionEntity.TYPE_CREDIT)

    private fun txn(
        id: Long,
        amount: Long,
        note: String,
        statusTag: String,
        category: String,
        type: String,
    ) = TransactionEntity(
        id = id,
        smsId = id,
        amountPaise = amount,
        merchantRaw = "MERCHANT",
        merchantKey = "merchant",
        category = category,
        occurredAt = id * 1_000L,
        bodySnippet = "Rs ${amount / 100}",
        sender = "AD-HDFCBK-S",
        note = note,
        statusTag = statusTag,
        sourceKey = TransactionEntity.sourceKeyForSms(id),
        type = type,
    )
}
