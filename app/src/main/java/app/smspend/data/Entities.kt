package app.smspend.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.smspend.domain.SpendCategory

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["smsId"], unique = true),
        Index(value = ["merchantKey"]),
        Index(value = ["occurredAt"]),
        Index(value = ["category"]),
        Index(value = ["dedupKey"]),
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
    /** User or UPI-app tag (e.g. "Bike taxi", "Rahul · lunch"). */
    val note: String = "",
)

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

/**
 * User feedback about wrong parses.
 * kind = IGNORE_SMS: pattern is the smsId — never re-import this exact message.
 * kind = IGNORE_REGEX: pattern is a regex — skip matching messages from this sender.
 * kind = AMOUNT_REGEX: pattern is a regex with one capture group extracting the rupee
 * amount — overrides default amount extraction for this sender.
 */
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

/**
 * Captured from UPI app notifications (PhonePe, later GPay/Paytm) so payee/note can
 * be merged onto the matching bank SMS transaction.
 */
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
