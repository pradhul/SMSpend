package app.smspend.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upgrades must never lose spends. Uses the exported schema JSON in app/schemas
 * (12.json = the schema shipped in 1.2.0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SmSpendDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate12To13_keepsRowsAndAddsIndexes() {
        helper.createDatabase(dbName, 12).use { db ->
            db.execSQL(
                """
                INSERT INTO transactions (id, smsId, amountPaise, merchantRaw, merchantKey, category,
                    occurredAt, bodySnippet, sender, dedupKey, note, aiStatus, type, statusTag,
                    cardLast4, sourceKey, parseSource)
                VALUES (7, 42, 12345, 'Swiggy', 'swiggy', 'FOOD_DINING', 1700000000000,
                    'Rs 123.45 debited', 'AX-HDFCBK', 'DEBIT:k', 'lunch', 'DONE', 'DEBIT', '',
                    '', 'sms:42', 'RULE')
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO learned_tx_decisions (sourceKey, decisionType, decision, decidedAt) " +
                    "VALUES ('sms:42', 'CARD_BILL', 'NO', 1)",
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 13, true, SmSpendDatabase.MIGRATION_12_13)
        db.query("SELECT amountPaise, merchantRaw, note FROM transactions WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(12345L, c.getLong(0))
            assertEquals("Swiggy", c.getString(1))
            assertEquals("lunch", c.getString(2))
        }
        val indexes = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { c ->
            while (c.moveToNext()) indexes += c.getString(0)
        }
        assertTrue("index_transactions_category_occurredAt" in indexes)
        assertTrue("index_transactions_type_occurredAt" in indexes)
        assertTrue("index_transactions_amountPaise_occurredAt" in indexes)
        assertTrue("index_transactions_merchantKey_type" in indexes)
        assertTrue("index_learned_tx_decisions_decisionType_decision" in indexes)
        db.close()
    }

    @Test
    fun roomOpensMigratedDatabase_withAllMigrations() {
        helper.createDatabase(dbName, 12).use { db ->
            db.execSQL(
                """
                INSERT INTO transactions (smsId, amountPaise, merchantRaw, merchantKey, category,
                    occurredAt, bodySnippet, sender, dedupKey, note, aiStatus, type, statusTag,
                    cardLast4, sourceKey, parseSource)
                VALUES (1, 5000, 'Uber', 'uber', 'TRAVEL', 1700000000000, 'x', 'S', '', '', 'DONE',
                    'DEBIT', '', '', 'sms:1', 'RULE')
                """.trimIndent(),
            )
        }
        val room = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SmSpendDatabase::class.java,
            dbName,
        ).addMigrations(*SmSpendDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = runBlocking { room.transactions().listInRange(0, Long.MAX_VALUE) }
            assertEquals(1, rows.size)
            assertEquals(5000L, rows.single().amountPaise)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrationChainIsContiguous() {
        val all = SmSpendDatabase.ALL_MIGRATIONS
        for (i in all.indices) {
            assertEquals(i + 1, all[i].startVersion)
            assertEquals(i + 2, all[i].endVersion)
        }
        assertEquals(13, all.last().endVersion)
    }
}
