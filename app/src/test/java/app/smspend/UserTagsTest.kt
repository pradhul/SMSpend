package app.smspend

import app.smspend.data.SelfTransferMatcher
import app.smspend.data.SmsDebitParser
import app.smspend.data.TransactionEntity
import app.smspend.domain.SpendCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserTagsTest {
    @Test
    fun userTags_splitsCommaSeparatedNote() {
        val tx = txn(note = "Bike taxi, rahul,  trip ")
        assertEquals(listOf("Bike taxi", "rahul", "trip"), tx.userTags())
        assertEquals("Bike taxi", tx.userTagOrNull())
    }

    @Test
    fun userTags_emptyAndBlankNotes() {
        assertTrue(txn(note = "").userTags().isEmpty())
        assertTrue(txn(note = " , , ").userTags().isEmpty())
        assertNull(txn(note = "").userTagOrNull())
    }

    @Test
    fun userTags_systemStatusValuesNeverAppear() {
        assertTrue(txn(note = SmsDebitParser.SELF_RD_FROM_NOTE).userTags().isEmpty())
        assertTrue(txn(note = "Paired · Baroda").userTags().isEmpty())
        // A system status slipped in among user tags is dropped, the rest survive.
        val mixed = txn(note = "trip, ${SmsDebitParser.CARD_PAYMENT_NOTE}")
        assertEquals(listOf("trip"), mixed.userTags())
    }

    @Test
    fun selfNameMatch_flagsCreditFromOwnAccount() {
        val aliases = "pradhul, swathi"
        assertTrue(SelfTransferMatcher.matches("PRADHUL DEV K P", aliases))
        assertTrue(SelfTransferMatcher.matches("PRADHULDEV K P", aliases))
        assertFalse(SelfTransferMatcher.matches("PRADHUL TRADERS", aliases))
        assertFalse(SelfTransferMatcher.matches("Some Merchant", aliases))
        assertFalse(SelfTransferMatcher.matches("PRADHUL DEV K P", ""))
    }

    private fun txn(note: String) = TransactionEntity(
        id = 1,
        smsId = 1,
        amountPaise = 100_00,
        merchantRaw = "MERCHANT",
        merchantKey = "merchant",
        category = SpendCategory.CREDITS.name,
        occurredAt = 1_000L,
        bodySnippet = "Rs 100",
        sender = "AD-HDFCBK-S",
        note = note,
        sourceKey = TransactionEntity.sourceKeyForSms(1),
        type = TransactionEntity.TYPE_CREDIT,
    )
}
