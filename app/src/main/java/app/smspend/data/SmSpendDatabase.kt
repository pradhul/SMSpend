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
    ],
    version = 7,
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
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
