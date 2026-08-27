package app.smspend.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.smspend.domain.SpendCategory
import java.util.Locale

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["smsId"], unique = true),
        Index(value = ["merchantKey"]),
        Index(value = ["occurredAt"]),
        Index(value = ["category"]),
        Index(value = ["dedupKey"]),
        Index(value = ["aiStatus"]),
        Index(value = ["type"]),
        Index(value = ["sourceKey"]),
        Index(value = ["statusTag"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val smsId: Long,
    val amountPaise: Long,
    val merchantRaw: String,
    val merchantKey: String,
    val category: String = SpendCategory.OTHER.name,
    val occurredAt: Long,
    val bodySnippet: String,
    val sender: String,
    val dedupKey: String = "",
    /** User or UPI-app tag only (e.g. "Bike taxi", "house rent"). Never a system status. */
    val note: String = "",
    /** Batched-Gemini categorization state; PENDING rows carry a provisional rules category. */
    val aiStatus: String = AI_DONE,
    /** DEBIT (default) or CREDIT — inbound credits are stored separately from spends. */
    val type: String = TYPE_DEBIT,
    /**
     * System status separate from [note]: Failed · reversed, Card bill payment,
     * Possible duplicate, Paired · …, Went to RD, From own account.
     */
    val statusTag: String = "",
    /** Last-4 of card for itemized card spends; empty when unknown / not a card spend. */
    val cardLast4: String = "",
    /** Immutable identity for learning: always `sms:<smsId>`. */
    val sourceKey: String = "",
) {
    fun isCredit(): Boolean = type.equals(TYPE_CREDIT, ignoreCase = true)

    fun isDebit(): Boolean = !isCredit()

    fun effectiveSourceKey(): String =
        sourceKey.ifBlank { "sms:$smsId" }

    /** Failed ATM reversals, card bill payments, and duplicates stay visible but skip totals. */
    fun countsTowardTotals(): Boolean {
        val s = statusTag.trim()
        if (s.isEmpty()) return true
        if (s.equals(SmsDebitParser.ATM_FAILED_NOTE, ignoreCase = true)) return false
        if (s.equals(SmsDebitParser.CARD_PAYMENT_NOTE, ignoreCase = true)) return false
        if (s.equals(GeminiCategorizer.DUPLICATE_NOTE, ignoreCase = true)) return false
        return true
    }

    fun isAtmFailed(): Boolean =
        statusTag.equals(SmsDebitParser.ATM_FAILED_NOTE, ignoreCase = true)

    fun isCardBillPaymentNote(): Boolean =
        statusTag.equals(SmsDebitParser.CARD_PAYMENT_NOTE, ignoreCase = true)

    fun isPossibleDuplicateNote(): Boolean =
        statusTag.equals(GeminiCategorizer.DUPLICATE_NOTE, ignoreCase = true)

    fun isCardSpend(): Boolean = cardLast4.isNotBlank()

    fun cardSpendLabel(): String? =
        when {
            cardLast4.isBlank() -> null
            cardLast4 == CARD_LAST4_UNKNOWN -> SmsDebitParser.CARD_SPEND_TAG_FALLBACK
            else -> "${SmsDebitParser.CARD_SPEND_TAG_PREFIX}$cardLast4"
        }

    /** User-authored tag for credit/debit netting. Status tags never participate. */
    fun userTagOrNull(): String? = userTags().firstOrNull()

    /**
     * All user-authored tags, stored comma-separated in [note].
     * System status values never appear here.
     */
    fun userTags(): List<String> {
        val n = note.trim()
        if (n.isEmpty()) return emptyList()
        if (isSystemStatusTag(n)) return emptyList()
        return n.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isSystemStatusTag(it) }
    }

    companion object {
        const val AI_PENDING = "PENDING"
        const val AI_DONE = "DONE"
        const val AI_SKIPPED = "SKIPPED"

        const val TYPE_DEBIT = "DEBIT"
        const val TYPE_CREDIT = "CREDIT"

        /** Sentinel when card spend is detected but last-4 is missing. */
        const val CARD_LAST4_UNKNOWN = "?"

        fun sourceKeyForSms(smsId: Long): String = "sms:$smsId"

        fun isSystemStatusTag(value: String): Boolean {
            val n = value.trim()
            if (n.isEmpty()) return false
            if (n.equals(SmsDebitParser.ATM_FAILED_NOTE, ignoreCase = true)) return true
            if (n.equals(SmsDebitParser.CARD_PAYMENT_NOTE, ignoreCase = true)) return true
            if (n.equals(GeminiCategorizer.DUPLICATE_NOTE, ignoreCase = true)) return true
            if (n.equals(SmsDebitParser.SELF_RD_WENT_NOTE, ignoreCase = true)) return true
            if (n.equals(SmsDebitParser.SELF_RD_FROM_NOTE, ignoreCase = true)) return true
            if (n.startsWith("Paired ·", ignoreCase = true)) return true
            if (n.equals(SmsDebitParser.CARD_SPEND_TAG_FALLBACK, ignoreCase = true)) return true
            if (n.startsWith(SmsDebitParser.CARD_SPEND_TAG_PREFIX, ignoreCase = true)) return true
            return false
        }

        fun normalizeUserTag(tag: String): String =
            tag.trim().lowercase(Locale.US).replace(Regex("""\s+"""), " ")

        /**
         * Split a legacy combined note into user tag / status / card last-4.
         * Used by Room migration and one-time backfill.
         */
        fun splitLegacyNote(legacy: String): Triple<String, String, String> {
            val n = legacy.trim()
            if (n.isEmpty()) return Triple("", "", "")
            if (n.equals(SmsDebitParser.ATM_FAILED_NOTE, ignoreCase = true) ||
                n.equals(SmsDebitParser.CARD_PAYMENT_NOTE, ignoreCase = true) ||
                n.equals(GeminiCategorizer.DUPLICATE_NOTE, ignoreCase = true) ||
                n.equals(SmsDebitParser.SELF_RD_WENT_NOTE, ignoreCase = true) ||
                n.equals(SmsDebitParser.SELF_RD_FROM_NOTE, ignoreCase = true) ||
                n.startsWith("Paired ·", ignoreCase = true)
            ) {
                return Triple("", n, "")
            }
            if (n.equals(SmsDebitParser.CARD_SPEND_TAG_FALLBACK, ignoreCase = true)) {
                return Triple("", "", CARD_LAST4_UNKNOWN)
            }
            if (n.startsWith(SmsDebitParser.CARD_SPEND_TAG_PREFIX, ignoreCase = true)) {
                val last4 = n.removePrefix(SmsDebitParser.CARD_SPEND_TAG_PREFIX)
                    .removePrefix(SmsDebitParser.CARD_SPEND_TAG_PREFIX.lowercase(Locale.US))
                    .trim()
                return Triple("", "", last4.ifBlank { CARD_LAST4_UNKNOWN })
            }
            return Triple(n, "", "")
        }
    }
}

/**
 * Persisted user decision about a duplicate pair, keyed by sorted immutable source keys.
 */
@Entity(tableName = "duplicate_decisions")
data class DuplicateDecisionEntity(
    @PrimaryKey val pairKey: String,
    /** NOT_DUPLICATE or DUPLICATE */
    val decision: String,
    val decidedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val NOT_DUPLICATE = "NOT_DUPLICATE"
        const val DUPLICATE = "DUPLICATE"

        fun pairKeyFor(a: TransactionEntity, b: TransactionEntity): String {
            val ka = a.effectiveSourceKey()
            val kb = b.effectiveSourceKey()
            return listOf(ka, kb).sorted().joinToString("|")
        }

        fun pairKeyForSources(a: String, b: String): String =
            listOf(a, b).sorted().joinToString("|")
    }
}

/**
 * Typed per-transaction learning. Multiple decision types can coexist for one source.
 */
@Entity(
    tableName = "learned_tx_decisions",
    primaryKeys = ["sourceKey", "decisionType"],
)
data class LearnedTxDecisionEntity(
    val sourceKey: String,
    /** CARD_BILL | SELF_TRANSFER | REMOVED_DUPLICATE */
    val decisionType: String,
    /** YES | NO */
    val decision: String,
    val decidedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val TYPE_CARD_BILL = "CARD_BILL"
        const val TYPE_SELF_TRANSFER = "SELF_TRANSFER"
        const val TYPE_REMOVED_DUPLICATE = "REMOVED_DUPLICATE"

        const val YES = "YES"
        const val NO = "NO"

        fun sourceKeyFor(tx: TransactionEntity): String = tx.effectiveSourceKey()
    }
}

/**
 * Exact debit↔credit self-transfer pair identity so unpairing is atomic and durable.
 */
@Entity(tableName = "self_transfer_pairs")
data class SelfTransferPairEntity(
    @PrimaryKey val pairKey: String,
    val debitSourceKey: String,
    val creditSourceKey: String,
    /** PAIRED or UNPAIRED */
    val decision: String,
    val decidedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val PAIRED = "PAIRED"
        const val UNPAIRED = "UNPAIRED"

        fun pairKeyFor(debitSource: String, creditSource: String): String =
            listOf(debitSource, creditSource).sorted().joinToString("|")
    }
}

@Entity(tableName = "merchant_memory")
data class MerchantMemoryEntity(
    @PrimaryKey val merchantKey: String,
    val category: String,
    val displayName: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val id: Int = 1,
    val lastSmsDateMs: Long = 0L,
    val lastSyncAtMs: Long = 0L,
)

@Entity(tableName = "category_icons")
data class CategoryIconEntity(
    @PrimaryKey val categoryKey: String,
    val iconName: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "recurring_merchants")
data class RecurringMerchantEntity(
    @PrimaryKey val merchantKey: String,
    val displayName: String,
    val medianPaise: Long,
    val cadenceDays: Int,
    val lastOccurredAt: Long,
    val nextExpectedAt: Long,
)

@Entity(
    tableName = "parse_rules",
    indices = [
        Index(value = ["senderKey"]),
    ],
)
data class ParseRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderKey: String,
    val kind: String,
    val pattern: String,
    val sampleSnippet: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_IGNORE_SMS = "IGNORE_SMS"
        const val KIND_IGNORE_REGEX = "IGNORE_REGEX"
        const val KIND_AMOUNT_REGEX = "AMOUNT_REGEX"
    }
}

@Entity(
    tableName = "category_notes",
    indices = [
        Index(value = ["categoryKey", "monthStartMs"]),
    ],
)
data class CategoryNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryKey: String,
    val monthStartMs: Long,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "category_corrections",
    indices = [
        Index(value = ["correctedAt"]),
        Index(value = ["merchantKey"]),
    ],
)
data class CategoryCorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantKey: String,
    val merchantRaw: String,
    val bodySnippet: String,
    val fromCategory: String,
    val toCategory: String,
    val correctedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "upi_events",
    indices = [
        Index(value = ["postedAt"]),
        Index(value = ["amountPaise", "postedAt"]),
    ],
)
data class UpiEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val amountPaise: Long,
    val payee: String,
    val note: String,
    val postedAt: Long,
)
