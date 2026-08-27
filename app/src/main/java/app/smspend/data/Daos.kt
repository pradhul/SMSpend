package app.smspend.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tx: TransactionEntity): Long

    @Query("SELECT * FROM transactions WHERE occurredAt >= :fromMs AND occurredAt < :toMs ORDER BY occurredAt DESC")
    fun observeInRange(fromMs: Long, toMs: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE occurredAt >= :fromMs AND occurredAt < :toMs ORDER BY occurredAt DESC")
    suspend fun listInRange(fromMs: Long, toMs: Long): List<TransactionEntity>

    @Query(
        "SELECT * FROM transactions WHERE category = :category AND occurredAt >= :fromMs AND occurredAt < :toMs ORDER BY occurredAt DESC",
    )
    fun observeCategory(category: String, fromMs: Long, toMs: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun getBySourceKey(sourceKey: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE category = :category ORDER BY occurredAt DESC")
    suspend fun listByCategory(category: String): List<TransactionEntity>

    @Query("UPDATE transactions SET category = :category, aiStatus = 'DONE' WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String)

    /**
     * Apply-to-similar scoped by direction: retagging a debit must never touch
     * credits that share the merchantKey (credits often fall back to a bank-label key).
     */
    @Query(
        """
        UPDATE transactions SET category = :category, aiStatus = 'DONE'
        WHERE merchantKey = :merchantKey AND type = :type
        """,
    )
    suspend fun updateCategoryForMerchantAndType(
        merchantKey: String,
        category: String,
        type: String,
    ): Int

    @Query("SELECT * FROM transactions WHERE aiStatus = :status ORDER BY occurredAt DESC")
    suspend fun listByAiStatus(status: String): List<TransactionEntity>

    @Query("UPDATE transactions SET aiStatus = :status WHERE id IN (:ids)")
    suspend fun updateAiStatusForIds(ids: List<Long>, status: String)

    /**
     * Guarded transition used inside the batch pass: only touches rows still PENDING,
     * so a user retag made while a Gemini call was in flight is never overwritten.
     */
    @Query(
        """
        UPDATE transactions SET category = :category, aiStatus = 'DONE'
        WHERE id = :id AND aiStatus = 'PENDING'
        """,
    )
    suspend fun updateCategoryIfPending(id: Long, category: String): Int

    @Query("UPDATE transactions SET aiStatus = :status WHERE id IN (:ids) AND aiStatus = 'PENDING'")
    suspend fun updateAiStatusForIdsIfPending(ids: List<Long>, status: String)

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    suspend fun listAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id > :afterId ORDER BY id ASC")
    suspend fun listAfterId(afterId: Long): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM transactions
        WHERE merchantRaw LIKE '%' || :q || '%'
           OR bodySnippet LIKE '%' || :q || '%'
           OR category LIKE '%' || :q || '%'
           OR merchantKey LIKE '%' || :q || '%'
           OR note LIKE '%' || :q || '%'
        ORDER BY occurredAt DESC
        LIMIT 200
        """,
    )
    fun search(q: String): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT COALESCE(SUM(amountPaise), 0) FROM transactions
        WHERE category = :category AND occurredAt >= :fromMs AND occurredAt < :toMs
        """,
    )
    suspend fun sumCategoryInRange(category: String, fromMs: Long, toMs: Long): Long

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE transactions SET amountPaise = :amountPaise WHERE id = :id")
    suspend fun updateAmount(id: Long, amountPaise: Long)

    @Query(
        """
        UPDATE transactions
        SET amountPaise = :amountPaise, merchantRaw = :merchantRaw, merchantKey = :merchantKey
        WHERE id = :id
        """,
    )
    suspend fun updateParsedFields(id: Long, amountPaise: Long, merchantRaw: String, merchantKey: String)

    @Query("SELECT dedupKey FROM transactions WHERE dedupKey != ''")
    suspend fun listDedupKeys(): List<String>

    @Query("UPDATE transactions SET dedupKey = :dedupKey WHERE id = :id")
    suspend fun updateDedupKey(id: Long, dedupKey: String)

    @Query(
        """
        UPDATE transactions
        SET bodySnippet = :bodySnippet, sender = :sender
        WHERE id = :id
        """,
    )
    suspend fun updateBodyAndSender(id: Long, bodySnippet: String, sender: String)

    @Query("UPDATE transactions SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String)

    @Query("UPDATE transactions SET statusTag = :statusTag WHERE id = :id")
    suspend fun updateStatusTag(id: Long, statusTag: String)

    @Query("UPDATE transactions SET cardLast4 = :cardLast4 WHERE id = :id")
    suspend fun updateCardLast4(id: Long, cardLast4: String)

    @Query(
        """
        UPDATE transactions
        SET note = :note, statusTag = :statusTag, cardLast4 = :cardLast4
        WHERE id = :id
        """,
    )
    suspend fun updateNoteFields(
        id: Long,
        note: String,
        statusTag: String,
        cardLast4: String,
    )

    @Query("UPDATE transactions SET sourceKey = :sourceKey WHERE id = :id")
    suspend fun updateSourceKey(id: Long, sourceKey: String)

    @Query("UPDATE transactions SET type = :type WHERE id = :id")
    suspend fun updateType(id: Long, type: String)

    @Query("UPDATE transactions SET dedupKey = :dedupKey, type = :type WHERE id = :id")
    suspend fun updateDedupKeyAndType(id: Long, dedupKey: String, type: String)

    @Query(
        """
        SELECT note FROM transactions
        WHERE note != ''
        GROUP BY note
        ORDER BY MAX(occurredAt) DESC
        LIMIT :limit
        """,
    )
    suspend fun recentNotes(limit: Int): List<String>

    @Query(
        """
        SELECT * FROM transactions
        WHERE amountPaise = :amountPaise
          AND occurredAt >= :fromMs AND occurredAt < :toMs
          AND note = ''
          AND statusTag = ''
          AND type = 'DEBIT'
        ORDER BY occurredAt DESC
        LIMIT 1
        """,
    )
    suspend fun findRecentUntaggedByAmount(
        amountPaise: Long,
        fromMs: Long,
        toMs: Long,
    ): TransactionEntity?

    /**
     * CAS: apply AI category only when category and aiStatus are still the expected values.
     */
    @Query(
        """
        UPDATE transactions SET category = :category, aiStatus = 'DONE'
        WHERE id = :id AND aiStatus = :expectedAiStatus AND category = :expectedCategory
        """,
    )
    suspend fun updateCategoryIfUnchanged(
        id: Long,
        category: String,
        expectedAiStatus: String,
        expectedCategory: String,
    ): Int
}

@Dao
interface MerchantMemoryDao {
    @Query("SELECT * FROM merchant_memory WHERE merchantKey = :key LIMIT 1")
    suspend fun get(key: String): MerchantMemoryEntity?

    @Query("SELECT * FROM merchant_memory ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<MerchantMemoryEntity>

    @Upsert
    suspend fun upsert(entity: MerchantMemoryEntity)

    @Query("DELETE FROM merchant_memory")
    suspend fun clearAll()

    @Query("DELETE FROM merchant_memory WHERE category = :category")
    suspend fun deleteByCategory(category: String): Int

    @Query("DELETE FROM merchant_memory WHERE merchantKey = :key")
    suspend fun deleteKey(key: String): Int
}

@Dao
interface SyncMetaDao {
    @Query("SELECT * FROM sync_meta WHERE id = 1 LIMIT 1")
    suspend fun get(): SyncMetaEntity?

    @Upsert
    suspend fun upsert(meta: SyncMetaEntity)
}

@Dao
interface CategoryIconDao {
    @Query("SELECT * FROM category_icons")
    fun observeAll(): Flow<List<CategoryIconEntity>>

    @Query("SELECT * FROM category_icons WHERE categoryKey = :key LIMIT 1")
    suspend fun get(key: String): CategoryIconEntity?

    @Query("SELECT * FROM category_icons WHERE categoryKey = :key LIMIT 1")
    fun observe(key: String): Flow<CategoryIconEntity?>

    @Upsert
    suspend fun upsert(entity: CategoryIconEntity)
}

@Dao
interface RecurringMerchantDao {
    @Query("SELECT * FROM recurring_merchants ORDER BY nextExpectedAt ASC")
    fun observeAll(): Flow<List<RecurringMerchantEntity>>

    @Query("SELECT * FROM recurring_merchants WHERE nextExpectedAt <= :untilMs ORDER BY nextExpectedAt ASC")
    fun observeDueBy(untilMs: Long): Flow<List<RecurringMerchantEntity>>

    @Query("DELETE FROM recurring_merchants")
    suspend fun clearAll()

    @Upsert
    suspend fun upsertAll(entities: List<RecurringMerchantEntity>)
}

@Dao
interface ParseRuleDao {
    @Query("SELECT * FROM parse_rules")
    suspend fun listAll(): List<ParseRuleEntity>

    @Insert
    suspend fun insert(rule: ParseRuleEntity): Long

    @Query("DELETE FROM parse_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CategoryNoteDao {
    @Query(
        """
        SELECT * FROM category_notes
        WHERE categoryKey = :categoryKey AND monthStartMs = :monthStartMs
        ORDER BY createdAt DESC
        """,
    )
    fun observeForCategoryMonth(categoryKey: String, monthStartMs: Long): Flow<List<CategoryNoteEntity>>

    @Insert
    suspend fun insert(note: CategoryNoteEntity): Long

    @Query("DELETE FROM category_notes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CategoryCorrectionDao {
    @Insert
    suspend fun insert(correction: CategoryCorrectionEntity): Long

    @Query("SELECT * FROM category_corrections ORDER BY correctedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<CategoryCorrectionEntity>

    @Query("SELECT DISTINCT merchantKey FROM category_corrections")
    suspend fun listMerchantKeys(): List<String>

    @Query(
        """
        DELETE FROM category_corrections
        WHERE id NOT IN (
            SELECT id FROM category_corrections ORDER BY correctedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun prune(keep: Int)
}

@Dao
interface UpiEventDao {
    @Insert
    suspend fun insert(event: UpiEventEntity): Long

    @Query(
        """
        SELECT * FROM upi_events
        WHERE amountPaise = :amountPaise
          AND postedAt >= :fromMs AND postedAt < :toMs
        ORDER BY postedAt DESC
        LIMIT 1
        """,
    )
    suspend fun findRecentByAmount(
        amountPaise: Long,
        fromMs: Long,
        toMs: Long,
    ): UpiEventEntity?

    @Query("DELETE FROM upi_events WHERE postedAt < :beforeMs")
    suspend fun pruneOlderThan(beforeMs: Long): Int
}

@Dao
interface DuplicateDecisionDao {
    @Upsert
    suspend fun upsert(entity: DuplicateDecisionEntity)

    @Query("SELECT * FROM duplicate_decisions WHERE pairKey = :pairKey LIMIT 1")
    suspend fun get(pairKey: String): DuplicateDecisionEntity?

    @Query("SELECT * FROM duplicate_decisions")
    suspend fun listAll(): List<DuplicateDecisionEntity>

    @Query("SELECT pairKey FROM duplicate_decisions WHERE decision = :decision")
    suspend fun listPairKeysByDecision(decision: String): List<String>

    @Query("DELETE FROM duplicate_decisions WHERE pairKey = :pairKey")
    suspend fun delete(pairKey: String)
}

@Dao
interface LearnedTxDecisionDao {
    @Upsert
    suspend fun upsert(entity: LearnedTxDecisionEntity)

    @Query(
        """
        SELECT * FROM learned_tx_decisions
        WHERE sourceKey = :sourceKey AND decisionType = :decisionType
        LIMIT 1
        """,
    )
    suspend fun get(sourceKey: String, decisionType: String): LearnedTxDecisionEntity?

    @Query(
        """
        SELECT sourceKey FROM learned_tx_decisions
        WHERE decisionType = :decisionType AND decision = :decision
        """,
    )
    suspend fun listKeys(decisionType: String, decision: String): List<String>

    @Query("SELECT * FROM learned_tx_decisions WHERE sourceKey = :sourceKey")
    suspend fun listForSource(sourceKey: String): List<LearnedTxDecisionEntity>

    @Query(
        """
        DELETE FROM learned_tx_decisions
        WHERE sourceKey = :sourceKey AND decisionType = :decisionType
        """,
    )
    suspend fun delete(sourceKey: String, decisionType: String)
}

@Dao
interface SelfTransferPairDao {
    @Upsert
    suspend fun upsert(entity: SelfTransferPairEntity)

    @Query("SELECT * FROM self_transfer_pairs WHERE pairKey = :pairKey LIMIT 1")
    suspend fun get(pairKey: String): SelfTransferPairEntity?

    @Query("SELECT * FROM self_transfer_pairs WHERE decision = :decision")
    suspend fun listByDecision(decision: String): List<SelfTransferPairEntity>

    @Query(
        """
        SELECT * FROM self_transfer_pairs
        WHERE debitSourceKey = :sourceKey OR creditSourceKey = :sourceKey
        """,
    )
    suspend fun listForSource(sourceKey: String): List<SelfTransferPairEntity>

    @Query("DELETE FROM self_transfer_pairs WHERE pairKey = :pairKey")
    suspend fun delete(pairKey: String)
}
