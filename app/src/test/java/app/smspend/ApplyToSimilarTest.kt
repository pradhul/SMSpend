package app.smspend

import app.smspend.data.CreditSmsParser
import app.smspend.data.SpendRepository
import app.smspend.data.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the mass-tagging bug: retagging one credit as "house rent" with
 * "apply to similar" checked recategorized every credit into that bank, because
 * credits fall back to a bank-label merchantKey when the payer can't be parsed.
 */
class ApplyToSimilarTest {
    @Test
    fun creditNeverAppliesToSimilar_evenWhenRequested() {
        assertFalse(SpendRepository.shouldApplyToSimilar(credit(), requested = true))
        assertFalse(SpendRepository.shouldApplyToSimilar(credit(), requested = false))
    }

    @Test
    fun debitAppliesToSimilarOnlyWhenRequested() {
        assertTrue(SpendRepository.shouldApplyToSimilar(debit(), requested = true))
        assertFalse(SpendRepository.shouldApplyToSimilar(debit(), requested = false))
    }

    /** Documents why credits must not bulk-retag: same bank → same merchantKey. */
    @Test
    fun creditsFromSameBank_shareBankLabelMerchantKey() {
        val a = CreditSmsParser.parseCredit(
            "Your A/c XX1234 is credited with Rs 5,000.00 on 01-08-26. Avl bal Rs 12,000.00 - Federal Bank",
            "AD-FEDBNK-S",
            1_000L,
        )
        val b = CreditSmsParser.parseCredit(
            "Your A/c XX1234 is credited with Rs 900.00 on 02-08-26. Avl bal Rs 12,900.00 - Federal Bank",
            "JD-FEDBNK-S",
            2_000L,
        )
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a!!.merchantKey, b!!.merchantKey)
    }

    private fun credit(id: Long = 1) = tx(id, TransactionEntity.TYPE_CREDIT)

    private fun debit(id: Long = 1) = tx(id, TransactionEntity.TYPE_DEBIT)

    private fun tx(id: Long, type: String) = TransactionEntity(
        id = id,
        smsId = id,
        amountPaise = 100_00,
        merchantRaw = "Federal Bank",
        merchantKey = "federal bank",
        occurredAt = id,
        bodySnippet = "credited",
        sender = "AD-FEDBNK-S",
        sourceKey = TransactionEntity.sourceKeyForSms(id),
        type = type,
    )
}
