package app.smspend.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TransactionEntity::class,
        MerchantMemoryEntity::class,
        SyncMetaEntity::class,
        CategoryIconEntity::class,
        RecurringMerchantEntity::class,
        CategoryNoteEntity::class,
        ParseRuleEntity::class,
        UpiEventEntity::class,
        CategoryCorrectionEntity::class,
        DuplicateDecisionEntity::class,
        LearnedTxDecisionEntity::class,
        SelfTransferPairEntity::class,
        AiSmsVerdictEntity::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class SmSpendDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun merchantMemory(): MerchantMemoryDao
    abstract fun syncMeta(): SyncMetaDao
    abstract fun categoryIcons(): CategoryIconDao
    abstract fun recurringMerchants(): RecurringMerchantDao
    abstract fun categoryNotes(): CategoryNoteDao
    abstract fun parseRules(): ParseRuleDao
    abstract fun upiEvents(): UpiEventDao
    abstract fun categoryCorrections(): CategoryCorrectionDao
    abstract fun duplicateDecisions(): DuplicateDecisionDao
    abstract fun learnedTxDecisions(): LearnedTxDecisionDao
    abstract fun selfTransferPairs(): SelfTransferPairDao
    abstract fun aiSmsVerdicts(): AiSmsVerdictDao

    companion object {
        @Volatile private var instance: SmSpendDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS category_icons (
                        categoryKey TEXT NOT NULL PRIMARY KEY,
                        iconName TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recurring_merchants (
                        merchantKey TEXT NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        medianPaise INTEGER NOT NULL,
                        cadenceDays INTEGER NOT NULL,
                        lastOccurredAt INTEGER NOT NULL,
                        nextExpectedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS category_notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        categoryKey TEXT NOT NULL,
                        monthStartMs INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_category_notes_categoryKey_monthStartMs ON category_notes(categoryKey, monthStartMs)",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS parse_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        senderKey TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        pattern TEXT NOT NULL,
                        sampleSnippet TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_parse_rules_senderKey ON parse_rules(senderKey)",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN dedupKey TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_dedupKey ON transactions(dedupKey)",
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN note TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS upi_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        amountPaise INTEGER NOT NULL,
                        payee TEXT NOT NULL,
                        note TEXT NOT NULL,
                        postedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_upi_events_postedAt ON upi_events(postedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_upi_events_amountPaise_postedAt ON upi_events(amountPaise, postedAt)",
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN aiStatus TEXT NOT NULL DEFAULT 'DONE'",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_aiStatus ON transactions(aiStatus)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS category_corrections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        merchantKey TEXT NOT NULL,
                        merchantRaw TEXT NOT NULL,
                        bodySnippet TEXT NOT NULL,
                        fromCategory TEXT NOT NULL,
                        toCategory TEXT NOT NULL,
                        correctedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_category_corrections_correctedAt ON category_corrections(correctedAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_category_corrections_merchantKey ON category_corrections(merchantKey)",
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN type TEXT NOT NULL DEFAULT 'DEBIT'",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_type ON transactions(type)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS duplicate_decisions (
                        pairKey TEXT NOT NULL PRIMARY KEY,
                        decision TEXT NOT NULL,
                        decidedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS learned_tx_decisions (
                        subjectKey TEXT NOT NULL PRIMARY KEY,
                        decision TEXT NOT NULL,
                        decidedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Split legacy `note` into user note / statusTag / cardLast4; add sourceKey;
         * rebuild learned_tx_decisions as typed composite keys; add self_transfer_pairs;
         * prefix directional dedup keys where possible.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN statusTag TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN cardLast4 TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN sourceKey TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_sourceKey ON transactions(sourceKey)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_statusTag ON transactions(statusTag)",
                )

                // Backfill sourceKey + split notes via cursor.
                db.query("SELECT id, smsId, note, type, dedupKey FROM transactions").use { c ->
                    val idIdx = c.getColumnIndex("id")
                    val smsIdx = c.getColumnIndex("smsId")
                    val noteIdx = c.getColumnIndex("note")
                    val typeIdx = c.getColumnIndex("type")
                    val dedupIdx = c.getColumnIndex("dedupKey")
                    while (c.moveToNext()) {
                        val id = c.getLong(idIdx)
                        val smsId = c.getLong(smsIdx)
                        val legacyNote = c.getString(noteIdx) ?: ""
                        val type = c.getString(typeIdx) ?: "DEBIT"
                        val dedup = c.getString(dedupIdx) ?: ""
                        val (userNote, status, cardLast4) = TransactionEntity.splitLegacyNote(legacyNote)
                        val sourceKey = "sms:$smsId"
                        val dirPrefix = if (type.equals("CREDIT", ignoreCase = true)) "CREDIT:" else "DEBIT:"
                        val newDedup = when {
                            dedup.isBlank() -> ""
                            dedup.startsWith("DEBIT:") || dedup.startsWith("CREDIT:") -> dedup
                            else -> dirPrefix + dedup
                        }
                        db.execSQL(
                            """
                            UPDATE transactions
                            SET note = ?, statusTag = ?, cardLast4 = ?, sourceKey = ?, dedupKey = ?
                            WHERE id = ?
                            """.trimIndent(),
                            arrayOf(userNote, status, cardLast4, sourceKey, newDedup, id),
                        )
                    }
                }

                // Rebuild learned decisions: subjectKey+decision → sourceKey+decisionType+YES/NO
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS learned_tx_decisions_new (
                        sourceKey TEXT NOT NULL,
                        decisionType TEXT NOT NULL,
                        decision TEXT NOT NULL,
                        decidedAt INTEGER NOT NULL,
                        PRIMARY KEY(sourceKey, decisionType)
                    )
                    """.trimIndent(),
                )
                db.query("SELECT subjectKey, decision, decidedAt FROM learned_tx_decisions").use { c ->
                    val sk = c.getColumnIndex("subjectKey")
                    val dec = c.getColumnIndex("decision")
                    val at = c.getColumnIndex("decidedAt")
                    while (c.moveToNext()) {
                        val subject = c.getString(sk) ?: continue
                        val decision = c.getString(dec) ?: continue
                        val decidedAt = c.getLong(at)
                        // Old keys were often dedupKey; map via transactions when possible.
                        val sourceKey = resolveSourceKey(db, subject)
                        val (dtype, dval) = when (decision) {
                            "CARD_BILL" -> "CARD_BILL" to "YES"
                            "NOT_CARD_BILL" -> "CARD_BILL" to "NO"
                            "NOT_SELF_TRANSFER" -> "SELF_TRANSFER" to "NO"
                            else -> continue
                        }
                        db.execSQL(
                            """
                            INSERT OR REPLACE INTO learned_tx_decisions_new
                            (sourceKey, decisionType, decision, decidedAt)
                            VALUES (?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf(sourceKey, dtype, dval, decidedAt),
                        )
                    }
                }
                db.execSQL("DROP TABLE learned_tx_decisions")
                db.execSQL("ALTER TABLE learned_tx_decisions_new RENAME TO learned_tx_decisions")

                // Remap duplicate_decisions pair keys from dedupKey|dedupKey → sourceKey|sourceKey
                db.query("SELECT pairKey, decision, decidedAt FROM duplicate_decisions").use { c ->
                    val pk = c.getColumnIndex("pairKey")
                    val dec = c.getColumnIndex("decision")
                    val at = c.getColumnIndex("decidedAt")
                    val remapped = mutableListOf<Triple<String, String, Long>>()
                    while (c.moveToNext()) {
                        val pairKey = c.getString(pk) ?: continue
                        val decision = c.getString(dec) ?: continue
                        val decidedAt = c.getLong(at)
                        val parts = pairKey.split("|")
                        if (parts.size != 2) continue
                        val a = resolveSourceKey(db, parts[0])
                        val b = resolveSourceKey(db, parts[1])
                        val newKey = listOf(a, b).sorted().joinToString("|")
                        remapped += Triple(newKey, decision, decidedAt)
                    }
                    db.execSQL("DELETE FROM duplicate_decisions")
                    for ((key, decision, decidedAt) in remapped) {
                        db.execSQL(
                            """
                            INSERT OR REPLACE INTO duplicate_decisions (pairKey, decision, decidedAt)
                            VALUES (?, ?, ?)
                            """.trimIndent(),
                            arrayOf(key, decision, decidedAt),
                        )
                    }
                }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS self_transfer_pairs (
                        pairKey TEXT NOT NULL PRIMARY KEY,
                        debitSourceKey TEXT NOT NULL,
                        creditSourceKey TEXT NOT NULL,
                        decision TEXT NOT NULL,
                        decidedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }

            private fun resolveSourceKey(db: SupportSQLiteDatabase, oldKey: String): String {
                if (oldKey.startsWith("sms:")) return oldKey
                // Prefer match on current (possibly already-prefixed) dedupKey, then legacy.
                db.query(
                    "SELECT sourceKey FROM transactions WHERE dedupKey = ? OR dedupKey = ? OR dedupKey = ? LIMIT 1",
                    arrayOf(oldKey, "DEBIT:$oldKey", "CREDIT:$oldKey"),
                ).use { c ->
                    if (c.moveToFirst()) {
                        val sk = c.getString(0)
                        if (!sk.isNullOrBlank()) return sk
                    }
                }
                // Fallback: keep as-is (may be orphan until next decision rewrite).
                return oldKey
            }
        }

        /** AI fallback: parseSource on transactions, template provenance on parse_rules, verdict cache. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN parseSource TEXT NOT NULL DEFAULT 'RULE'",
                )
                db.execSQL(
                    "ALTER TABLE parse_rules ADD COLUMN sourceSmsId INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_sms_verdicts (
                        smsId INTEGER NOT NULL PRIMARY KEY,
                        senderKey TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        amountPaise INTEGER NOT NULL,
                        model TEXT NOT NULL,
                        bodySnippet TEXT NOT NULL,
                        at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ai_sms_verdicts_senderKey ON ai_sms_verdicts(senderKey)",
                )
            }
        }

        fun get(context: Context): SmSpendDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SmSpendDatabase::class.java,
                    "smspend.db",
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
