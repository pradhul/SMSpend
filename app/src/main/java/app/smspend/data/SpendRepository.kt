package app.smspend.data

import android.content.Context
import app.smspend.domain.CategoryBucket
import app.smspend.domain.RecurringDetector
import app.smspend.domain.SpendCategory
import app.smspend.notify.SmSpendNotifications
import app.smspend.util.DateRanges
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.regex.Pattern
import kotlin.math.abs
import app.smspend.notify.UpiNotificationParser

data class CategorySpend(
    val bucket: CategoryBucket,
    val totalPaise: Long,
    val txnCount: Int,
    val merchants: List<String>,
)

data class ScanResult(
    val scannedSms: Int,
    val newTransactions: Int,
)

data class RecategorizeResult(
    val rulesUpdated: Int,
    val geminiUpdated: Int,
    val failed: Int = 0,
    val firstError: String? = null,
)

data class DuplicateFlagResult(
    val flagged: Int,
    val candidates: Int,
    val error: String? = null,
)

data class OtherExportResult(
    val count: Int,
    val fileName: String?,
    val error: String? = null,
)

data class RepairResult(
    val removed: Int,
    val fixed: Int,
    /** Merchant confirmations + same-payee bank doubles removed by cross-source logic. */
    val crossSourceRemoved: Int = 0,
)

data class SyncPeriodResult(
    val scannedSms: Int,
    val newTransactions: Int,
    val repaired: Int,
    val recategorized: Int,
    val selfTransfersPaired: Int,
    val duplicatePairs: List<DuplicatePairCandidate>,
)

sealed class WrongParseResult {
    /** Transaction deleted; it won't be re-imported on rescans. */
    data class Removed(val ruleSaved: Boolean) : WrongParseResult()

    /** Gemini confirmed a real debit and corrected the amount/merchant. */
    data class Fixed(val newAmountPaise: Long, val ruleSaved: Boolean) : WrongParseResult()

    data object NotFound : WrongParseResult()
}

data class WidgetSnapshot(
    val monthSpendPaise: Long,
    val budgetPaise: Long?,
    val topCategories: List<Pair<String, Long>>,
)

data class CategoryYtdTrend(
    val bucket: CategoryBucket,
    val monthTotals: List<Long>,
    val ytdTotalPaise: Long,
)

class SpendRepository(
    private val db: SmSpendDatabase,
    private val smsReader: SmsInboxReader,
    private val prefs: UserPreferences,
    private val gemini: GeminiCategorizer = GeminiCategorizer(),
) {
    private val txDao get() = db.transactions()
    private val memDao get() = db.merchantMemory()
    private val syncDao get() = db.syncMeta()
    private val iconDao get() = db.categoryIcons()
    private val recurringDao get() = db.recurringMerchants()
    private val notesDao get() = db.categoryNotes()
    private val ruleDao get() = db.parseRules()
    private val upiDao get() = db.upiEvents()

    companion object {
        /** ± window to merge a UPI notification onto a bank SMS transaction. */
        const val UPI_MERGE_WINDOW_MS = 30L * 60 * 1000
        private const val UPI_EVENT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        /**
         * Build candidate pairs: same amount, within [windowMs], not already noted as duplicate.
         * Prefer pairing earliest with each later match (one pair per later row per amount cluster).
         */
        fun buildDuplicatePairCandidates(
            txs: List<TransactionEntity>,
            windowMs: Long = 24L * 60 * 60 * 1000,
        ): List<DuplicatePairCandidate> {
            val sorted = txs.sortedWith(compareBy({ it.occurredAt }, { it.id }))
            val out = mutableListOf<DuplicatePairCandidate>()
            val usedLater = mutableSetOf<Long>()
            for (i in sorted.indices) {
                val a = sorted[i]
                if (a.note.equals(GeminiCategorizer.DUPLICATE_NOTE, ignoreCase = true)) continue
                for (j in (i + 1) until sorted.size) {
                    val b = sorted[j]
                    if (b.id in usedLater) continue
                    if (b.note.equals(GeminiCategorizer.DUPLICATE_NOTE, ignoreCase = true)) continue
                    if (b.amountPaise != a.amountPaise) continue
                    val dt = abs(b.occurredAt - a.occurredAt)
                    if (dt > windowMs) break // sorted by time — further ones are farther
                    // Skip identical dedupKey (already collapsed path) — still rare residual.
                    if (a.dedupKey.isNotBlank() && a.dedupKey == b.dedupKey) continue
                    val earlier = if (a.occurredAt <= b.occurredAt) a else b
                    val later = if (a.occurredAt <= b.occurredAt) b else a
                    out += DuplicatePairCandidate(
                        pairId = "${earlier.id}-${later.id}",
                        earlierId = earlier.id,
                        laterId = later.id,
                        amountPaise = earlier.amountPaise,
                        earlierSender = earlier.sender,
                        laterSender = later.sender,
                        earlierSnippet = earlier.bodySnippet,
                        laterSnippet = later.bodySnippet,
                    )
                    usedLater += later.id
                }
            }
            return out
        }
    }

    fun observeRangeByCategory(
        startMs: Long,
        endMs: Long = DateRanges.endExclusiveNow(),
    ): Flow<List<CategorySpend>> =
        txDao.observeInRange(startMs, endMs).map { list ->
            list.groupBy { normalizeCategoryKey(it.category) }
                .map { (key, txs) ->
                    CategorySpend(
                        bucket = CategoryBucket.fromStored(key),
                        totalPaise = txs.sumOf { it.amountPaise },
                        txnCount = txs.size,
                        merchants = txs.map { it.merchantRaw }.distinct().take(3),
                    )
                }
                .sortedByDescending { it.totalPaise }
        }

    @Deprecated("Use observeRangeByCategory", ReplaceWith("observeRangeByCategory(weekStartMs, weekEndMs)"))
    fun observeWeekByCategory(
        weekStartMs: Long = DateRanges.startOfThisWeekMs(),
        weekEndMs: Long = DateRanges.endExclusiveNow(),
    ): Flow<List<CategorySpend>> = observeRangeByCategory(weekStartMs, weekEndMs)

    fun observeCategoryIconNames(): Flow<Map<String, String>> =
        iconDao.observeAll().map { rows ->
            rows.associate { it.categoryKey to it.iconName }
        }

    fun observeCategoryIconName(categoryKey: String): Flow<String?> =
        iconDao.observe(categoryKey).map { it?.iconName }

    suspend fun getCategoryIconName(categoryKey: String): String? =
        iconDao.get(categoryKey)?.iconName

    suspend fun ensureCustomCategoryIcon(categoryKey: String) {
        if (!SpendCategory.isCustomKey(categoryKey)) return
        if (iconDao.get(categoryKey) != null) return
        val apiKey = prefs.geminiApiKey.first()
        if (apiKey.isBlank()) return
        val label = SpendCategory.displayLabel(categoryKey)
        val name = runCatching { gemini.suggestIconName(apiKey, label) }.getOrNull() ?: return
        iconDao.upsert(
            CategoryIconEntity(
                categoryKey = categoryKey,
                iconName = name,
            ),
        )
    }

    suspend fun ensureIconsForCategories(categoryKeys: Collection<String>) {
        categoryKeys
            .filter { SpendCategory.isCustomKey(it) }
            .forEach { ensureCustomCategoryIcon(it) }
    }

    fun observeCategoryTxns(
        categoryKey: String,
        weekStartMs: Long = DateRanges.startOfThisWeekMs(),
        weekEndMs: Long = DateRanges.endExclusiveNow(),
    ): Flow<List<TransactionEntity>> =
        txDao.observeInRange(weekStartMs, weekEndMs).map { list ->
            list.filter { normalizeCategoryKey(it.category) == categoryKey }
        }

    fun observeExpectedSoon(withinDays: Int = 7): Flow<List<RecurringMerchantEntity>> {
        val until = System.currentTimeMillis() + withinDays * 24L * 60 * 60 * 1000
        return recurringDao.observeDueBy(until)
    }

    fun observeAllRecurring(): Flow<List<RecurringMerchantEntity>> = recurringDao.observeAll()

    fun searchTransactions(query: String): Flow<List<TransactionEntity>> {
        val q = query.trim()
        if (q.isEmpty()) return flowOf(emptyList())
        return txDao.search(q)
    }

    suspend fun categoryMonthTotalsYtd(categoryKey: String): List<Long> {
        val months = DateRanges.yearToDateMonths()
        return months.map { month ->
            txDao.listInRange(month.startMs, month.endExclusiveMs)
                .filter { normalizeCategoryKey(it.category) == categoryKey }
                .sumOf { it.amountPaise }
        }
    }

    suspend fun categoryMonthlyTotalsThisYear(): List<CategoryYtdTrend> {
        val months = DateRanges.yearToDateMonths()
        val from = months.firstOrNull()?.startMs ?: return emptyList()
        val to = DateRanges.endExclusiveNow()
        val txs = txDao.listInRange(from, to)
        val byCat = txs.groupBy { normalizeCategoryKey(it.category) }
        return byCat.map { (key, catTxs) ->
            val monthTotals = months.map { month ->
                catTxs.filter { it.occurredAt >= month.startMs && it.occurredAt < month.endExclusiveMs }
                    .sumOf { it.amountPaise }
            }
            CategoryYtdTrend(
                bucket = CategoryBucket.fromStored(key),
                monthTotals = monthTotals,
                ytdTotalPaise = monthTotals.sum(),
            )
        }.sortedByDescending { it.ytdTotalPaise }
    }

    suspend fun widgetSnapshot(): WidgetSnapshot {
        val month = DateRanges.monthWindow(0)
        val cats = txDao.listInRange(month.startMs, DateRanges.endExclusiveNow())
            .groupBy { normalizeCategoryKey(it.category) }
            .map { (key, txs) ->
                CategorySpend(
                    bucket = CategoryBucket.fromStored(key),
                    totalPaise = txs.sumOf { it.amountPaise },
                    txnCount = txs.size,
                    merchants = emptyList(),
                )
            }
        val spending = cats.filter { SpendCategory.countsAsSpend(it.bucket.key) }
            .sortedByDescending { it.totalPaise }
        return WidgetSnapshot(
            monthSpendPaise = spending.sumOf { it.totalPaise },
            budgetPaise = prefs.monthlyBudgetPaise.first(),
            topCategories = spending.take(2).map { it.bucket.label to it.totalPaise },
        )
    }

    fun observeCategoryNotes(categoryKey: String, monthStartMs: Long): Flow<List<CategoryNoteEntity>> =
        notesDao.observeForCategoryMonth(categoryKey, monthStartMs)

    suspend fun addCategoryNote(categoryKey: String, monthStartMs: Long, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        notesDao.insert(
            CategoryNoteEntity(
                categoryKey = categoryKey,
                monthStartMs = monthStartMs,
                text = trimmed,
            ),
        )
    }

    suspend fun deleteCategoryNote(id: Long) {
        notesDao.delete(id)
    }

    suspend fun refreshRecurringMerchants() {
        val from = DateRanges.startOfWeekMs(11)
        val txs = txDao.listInRange(from, DateRanges.endExclusiveNow())
        val detected = RecurringDetector.detect(txs)
        recurringDao.clearAll()
        if (detected.isNotEmpty()) {
            recurringDao.upsertAll(
                detected.map {
                    RecurringMerchantEntity(
                        merchantKey = it.merchantKey,
                        displayName = it.displayName,
                        medianPaise = it.medianPaise,
                        cadenceDays = it.cadenceDays,
                        lastOccurredAt = it.lastOccurredAt,
                        nextExpectedAt = it.nextExpectedAt,
                    )
                },
            )
        }
    }

    suspend fun processPostScanAlerts(context: Context) {
        val threshold = prefs.largeDebitThresholdPaise.first()
        val lastId = prefs.lastAlertedTxnId.first()
        val newOnes = txDao.listAfterId(lastId)
        var maxId = lastId
        for (tx in newOnes) {
            maxId = maxOf(maxId, tx.id)
            if (tx.amountPaise >= threshold) {
                SmSpendNotifications.notifyLargeDebit(context, tx.merchantRaw, tx.amountPaise)
            }
        }
        if (maxId > lastId) prefs.setLastAlertedTxnId(maxId)
    }

    private fun normalizeCategoryKey(stored: String): String =
        when (stored) {
            "FOOD_DELIVERY" -> SpendCategory.FOOD_DINING.name
            else -> stored
        }

    suspend fun getTransaction(id: Long): TransactionEntity? = txDao.getById(id)

    suspend fun lastSyncAt(): Long = syncDao.get()?.lastSyncAtMs ?: 0L

    suspend fun validateApiKey(apiKey: String): GeminiKeyCheck = gemini.validateApiKey(apiKey)

    suspend fun scanInbox(
        forceFullMonth: Boolean = false,
        sinceOverrideMs: Long? = null,
    ): ScanResult {
        val meta = syncDao.get()
        val oldestHomeMonth = DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1)
        val since = when {
            sinceOverrideMs != null -> sinceOverrideMs
            forceFullMonth || meta == null || meta.lastSmsDateMs <= 0L -> oldestHomeMonth
            else -> meta.lastSmsDateMs
        }

        val messages = smsReader.readSince(since)
        var inserted = 0
        var maxDate = meta?.lastSmsDateMs ?: 0L
        val apiKey = prefs.geminiApiKey.first()
        val rulesBySender = ruleDao.listAll().groupBy { it.senderKey }
        val seenDedupKeys = txDao.listDedupKeys().toMutableSet()

        val windowMs = SmsDebitParser.CROSS_SOURCE_WINDOW_MS
        val lookbackFrom = since - windowMs
        val lookbackTo = System.currentTimeMillis() + windowMs
        // Bank-is-truth working set: classify from body+sender (no schema flag).
        val recent = txDao.listInRange(lookbackFrom, lookbackTo).toMutableList()
        val batchBankDebits = mutableListOf<Pair<Long, Long>>() // amountPaise to occurredAt

        for (sms in messages) {
            maxDate = maxOf(maxDate, sms.dateMs)
            val senderRules = rulesBySender[SmsDebitParser.senderKey(sms.sender)].orEmpty()
            if (isIgnoredByRules(senderRules, sms.id, sms.body)) continue
            val parsed = SmsDebitParser.parse(sms.body, sms.sender)
            if (parsed == null) {
                SmsDebitParser.parseAtmReversal(sms.body)?.let { rev ->
                    applyAtmReversal(rev, sms.dateMs, recent)
                }
                continue
            }
            val amountPaise = amountFromRules(senderRules, sms.body) ?: parsed.amountPaise
            val key = SmsDebitParser.dedupKey(sms.body, amountPaise)
            if (key in seenDedupKeys) continue

            val isBank = SmsDebitParser.isBankDebit(sms.body, sms.sender)
            if (!isBank) {
                // Merchant confirmation — skip when a bank debit already covers this amount.
                val bankPoints = recent
                    .filter { SmsDebitParser.isBankDebit(it.bodySnippet, it.sender) }
                    .map { it.amountPaise to it.occurredAt } + batchBankDebits
                if (SmsDebitParser.hasMatchingBankDebit(amountPaise, sms.dateMs, bankPoints, windowMs)) {
                    continue
                }
            } else {
                // Bank wins: drop merchant-confirmation rows with the same amount in-window.
                val merchants = recent.filter { tx ->
                    !SmsDebitParser.isBankDebit(tx.bodySnippet, tx.sender) &&
                        tx.amountPaise == amountPaise &&
                        abs(tx.occurredAt - sms.dateMs) <= windowMs
                }
                for (m in merchants) {
                    txDao.deleteById(m.id)
                    recent.removeAll { it.id == m.id }
                }
            }

            val categoryKey = resolveCategoryKey(parsed.merchantKey, parsed.merchantRaw, sms.body, apiKey)
            val row = TransactionEntity(
                smsId = sms.id,
                amountPaise = amountPaise,
                merchantRaw = parsed.merchantRaw,
                merchantKey = parsed.merchantKey,
                category = categoryKey,
                occurredAt = sms.dateMs,
                bodySnippet = sms.body.take(180),
                sender = sms.sender,
                dedupKey = key,
            )
            val id = txDao.insertIgnore(row)
            if (id != -1L) {
                inserted++
                seenDedupKeys += key
                recent += row.copy(id = id)
                if (isBank) {
                    batchBankDebits += amountPaise to sms.dateMs
                    applyUpiEventNote(id, amountPaise, sms.dateMs)
                }
            }
        }

        syncDao.upsert(
            SyncMetaEntity(
                id = 1,
                lastSmsDateMs = maxDate,
                lastSyncAtMs = System.currentTimeMillis(),
            ),
        )
        // Clean already-stored merchant confirmations / same-payee bank doubles.
        purgeCrossSourceDuplicates()
        // Reversal SMS may arrive after the withdrawal was already stored from a prior scan.
        purgeAtmReversalsFromInbox(oldestHomeMonth)
        applySelfRdCrossNotes()
        applyCreditPairSelfTransfers(since)
        return ScanResult(scannedSms = messages.size, newTransactions = inserted)
    }

    /**
     * Period Sync: scan → repair → recategorize Other → return remaining duplicate pairs for UI.
     */
    suspend fun syncPeriod(startMs: Long, endMs: Long = DateRanges.endExclusiveNow()): SyncPeriodResult {
        val scan = scanInbox(forceFullMonth = true, sinceOverrideMs = startMs)
        val repair = repairTransactions()
        val apiKey = prefs.geminiApiKey.first()
        val recat = recategorizeOther(useGemini = apiKey.isNotBlank())
        val pairs = findDuplicatePairCandidates()
            .filter { pair ->
                val earlier = txDao.getById(pair.earlierId) ?: return@filter false
                earlier.occurredAt in startMs until endMs ||
                    (txDao.getById(pair.laterId)?.occurredAt ?: 0L) in startMs until endMs
            }
        return SyncPeriodResult(
            scannedSms = scan.scannedSms,
            newTransactions = scan.newTransactions,
            repaired = repair.removed + repair.fixed,
            recategorized = recat.rulesUpdated + recat.geminiUpdated,
            selfTransfersPaired = 0,
            duplicatePairs = pairs,
        )
    }

    /** Detect banks from inbox senders for My banks pre-selection. */
    suspend fun detectMyBanksFromInbox(sinceMs: Long = DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1)): Set<String> {
        val messages = runCatching { smsReader.readSince(sinceMs) }.getOrDefault(emptyList())
        return app.smspend.domain.IndianBanks.detectFromSenders(messages.map { it.sender })
    }

    suspend fun listCustomCategoryKeys(): List<String> =
        txDao.listAll()
            .map { normalizeCategoryKey(it.category) }
            .filter { SpendCategory.isCustomKey(it) }
            .distinct()
            .sorted()

    /** Delete later rows of duplicate pairs; returns how many were removed. */
    suspend fun removeDuplicateLaters(laterIds: Collection<Long>): Int {
        var removed = 0
        for (id in laterIds.distinct()) {
            if (txDao.getById(id) == null) continue
            txDao.deleteById(id)
            removed++
        }
        return removed
    }

    private fun isIgnoredByRules(rules: List<ParseRuleEntity>, smsId: Long, body: String): Boolean =
        rules.any { rule ->
            when (rule.kind) {
                ParseRuleEntity.KIND_IGNORE_SMS -> rule.pattern == smsId.toString()
                ParseRuleEntity.KIND_IGNORE_REGEX -> safeRegexMatch(rule.pattern, body)
                else -> false
            }
        }

    private fun amountFromRules(rules: List<ParseRuleEntity>, body: String): Long? =
        rules.asSequence()
            .filter { it.kind == ParseRuleEntity.KIND_AMOUNT_REGEX }
            .mapNotNull { extractPaiseWithRegex(it.pattern, body) }
            .firstOrNull()

    private fun safeRegexMatch(pattern: String, body: String): Boolean =
        runCatching {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(body).find()
        }.getOrDefault(false)

    private fun extractPaiseWithRegex(pattern: String, body: String): Long? =
        runCatching {
            val m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(body)
            if (m.find() && m.groupCount() >= 1) {
                m.group(1)?.replace(",", "")?.toDoubleOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { Math.round(it * 100.0) }
            } else {
                null
            }
        }.getOrNull()

    /**
     * Re-runs the (current) parser over every stored transaction: rows the parser now
     * rejects are deleted, rows whose amount was mis-read are corrected. Also backfills
     * dedupKey, removes duplicate transactions (same key), and purges cross-source
     * merchant confirmations / same-payee bank doubles.
     */
    suspend fun repairTransactions(): RepairResult {
        val all = txDao.listAll()
        val fullSmsById = runCatching { smsReader.readByIds(all.map { it.smsId }) }
            .getOrDefault(emptyMap())
        val rulesBySender = ruleDao.listAll().groupBy { it.senderKey }
        var removed = 0
        var fixed = 0
        for (tx in all) {
            val full = fullSmsById[tx.smsId]
            val body = full?.body?.takeIf { it.isNotBlank() } ?: tx.bodySnippet
            val sender = full?.sender?.takeIf { it.isNotBlank() } ?: tx.sender
            if (full != null) {
                val snippet = body.take(180)
                if (snippet != tx.bodySnippet || sender != tx.sender) {
                    txDao.updateBodyAndSender(tx.id, snippet, sender)
                }
            }

            val senderRules = rulesBySender[SmsDebitParser.senderKey(sender)].orEmpty()
            if (isIgnoredByRules(senderRules, tx.smsId, body)) {
                txDao.deleteById(tx.id)
                removed++
                continue
            }
            val parsed = SmsDebitParser.parse(body, sender)
            if (parsed == null) {
                txDao.deleteById(tx.id)
                removed++
                continue
            }
            val amount = amountFromRules(senderRules, body) ?: parsed.amountPaise
            if (amount != tx.amountPaise ||
                parsed.merchantRaw != tx.merchantRaw ||
                parsed.merchantKey != tx.merchantKey
            ) {
                if (amount != tx.amountPaise) fixed++
                txDao.updateParsedFields(tx.id, amount, parsed.merchantRaw, parsed.merchantKey)
            }
            val key = SmsDebitParser.dedupKey(body, amount)
            if (tx.dedupKey != key) {
                txDao.updateDedupKey(tx.id, key)
            }
        }

        // Collapse duplicates: same dedupKey → keep the earliest row (lowest id).
        val remaining = txDao.listAll()
            .filter { it.dedupKey.isNotBlank() }
            .groupBy { it.dedupKey }
        for ((_, group) in remaining) {
            if (group.size <= 1) continue
            val keep = group.minByOrNull { it.id } ?: continue
            for (dup in group) {
                if (dup.id == keep.id) continue
                txDao.deleteById(dup.id)
                removed++
            }
        }

        val crossSourceRemoved = purgeCrossSourceDuplicates()
        removed += crossSourceRemoved
        removed += purgeAtmReversalsFromInbox(DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1))
        fixed += repairSelfTransferPrecedence()
        applySelfRdCrossNotes()
        applyCreditPairSelfTransfers(DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1))
        return RepairResult(
            removed = removed,
            fixed = fixed,
            crossSourceRemoved = crossSourceRemoved,
        )
    }

    /**
     * Marks ATM Withdrawn rows that have a matching "ATM Txn Reversed" SMS as failed withdrawals
     * (keeps the row with note) instead of deleting them.
     */
    private suspend fun purgeAtmReversalsFromInbox(sinceMs: Long): Int {
        val messages = runCatching { smsReader.readSince(sinceMs) }.getOrDefault(emptyList())
        val reversals = messages.mapNotNull { sms ->
            SmsDebitParser.parseAtmReversal(sms.body)?.let { it to sms.dateMs }
        }
        if (reversals.isEmpty()) return 0
        val withdrawals = txDao.listAll()
            .filter { SmsDebitParser.isAtmWithdrawal(it.bodySnippet, it.merchantKey) }
            .filter { !it.note.equals(SmsDebitParser.ATM_FAILED_NOTE, ignoreCase = true) }
            .map {
                AtmWithdrawalPoint(
                    id = it.id,
                    amountPaise = it.amountPaise,
                    occurredAt = it.occurredAt,
                    merchantKey = it.merchantKey,
                    bodySnippet = it.bodySnippet,
                )
            }
        val ids = SmsDebitParser.atmWithdrawalIdsReversedBy(reversals, withdrawals)
        for (id in ids) {
            markAtmWithdrawalFailed(id)
        }
        return ids.size
    }

    /** Apply one reversal against in-memory [recent] working set + DB. */
    private suspend fun applyAtmReversal(
        rev: AtmReversal,
        occurredAt: Long,
        recent: MutableList<TransactionEntity>,
    ): Int {
        val candidates = recent
            .filter { SmsDebitParser.isAtmWithdrawal(it.bodySnippet, it.merchantKey) }
            .filter { !it.note.equals(SmsDebitParser.ATM_FAILED_NOTE, ignoreCase = true) }
            .map {
                AtmWithdrawalPoint(
                    id = it.id,
                    amountPaise = it.amountPaise,
                    occurredAt = it.occurredAt,
                    merchantKey = it.merchantKey,
                    bodySnippet = it.bodySnippet,
                )
            }
        val ids = SmsDebitParser.atmWithdrawalIdsReversedBy(
            listOf(rev to occurredAt),
            candidates,
        )
        for (id in ids) {
            markAtmWithdrawalFailed(id)
            val idx = recent.indexOfFirst { it.id == id }
            if (idx >= 0) {
                recent[idx] = recent[idx].copy(
                    category = SpendCategory.WITHDRAWALS.name,
                    note = SmsDebitParser.ATM_FAILED_NOTE,
                )
            }
        }
        return ids.size
    }

    private suspend fun markAtmWithdrawalFailed(id: Long) {
        txDao.updateCategory(id, SpendCategory.WITHDRAWALS.name)
        txDao.updateNote(id, SmsDebitParser.ATM_FAILED_NOTE)
    }

    /**
     * Same-amount self transfer ↔ savings within 14 days: cross-tag notes.
     */
    suspend fun applySelfRdCrossNotes(
        windowMs: Long = SmsDebitParser.SELF_RD_CROSS_NOTE_WINDOW_MS,
    ): Int {
        val all = txDao.listAll()
        val selfs = all.filter { SpendCategory.isSelfTransfer(it.category) }
        val savings = all.filter {
            !SpendCategory.isCustomKey(it.category) &&
                SpendCategory.fromStored(it.category) == SpendCategory.SAVINGS
        }
        var tagged = 0
        val usedSavings = mutableSetOf<Long>()
        for (self in selfs.sortedBy { it.occurredAt }) {
            val match = savings.firstOrNull { sav ->
                sav.id !in usedSavings &&
                    sav.amountPaise == self.amountPaise &&
                    abs(sav.occurredAt - self.occurredAt) <= windowMs
            } ?: continue
            usedSavings += match.id
            if (self.note.isBlank()) {
                txDao.updateNote(self.id, SmsDebitParser.SELF_RD_WENT_NOTE)
                tagged++
            }
            if (match.note.isBlank()) {
                txDao.updateNote(match.id, SmsDebitParser.SELF_RD_FROM_NOTE)
                tagged++
            }
        }
        return tagged
    }

    /**
     * Pair own-bank credits with debits to mark Self transfer (requires My banks).
     */
    suspend fun applyCreditPairSelfTransfers(sinceMs: Long): Int {
        val bankIds = app.smspend.domain.IndianBanks.parseIdsCsv(prefs.myBankIds.first())
        if (bankIds.isEmpty()) return 0
        val messages = runCatching { smsReader.readSince(sinceMs) }.getOrDefault(emptyList())
        val credits = messages.mapNotNull { sms ->
            CreditSmsParser.parseCredit(sms.body, sms.sender, sms.dateMs)
        }
        if (credits.isEmpty()) return 0
        val debits = txDao.listInRange(sinceMs, DateRanges.endExclusiveNow())
            .filter { !SpendCategory.isSelfTransfer(it.category) }
            .filter {
                val cat = SpendCategory.fromStored(it.category)
                cat == SpendCategory.TRANSFERS || cat == SpendCategory.OTHER ||
                    SpendCategory.isCustomKey(it.category).not() && cat == SpendCategory.WITHDRAWALS
            }
        // Prefer transfers/other — don't reclassify withdrawals as self.
        val candidates = debits.filter {
            val cat = SpendCategory.fromStored(it.category)
            cat == SpendCategory.TRANSFERS || cat == SpendCategory.OTHER
        }
        val pairs = CreditSmsParser.selfTransferDebitIds(candidates, credits, bankIds)
        var paired = 0
        for ((id, note) in pairs) {
            val tx = txDao.getById(id) ?: continue
            // Never override savings.
            if (SpendCategory.fromStored(tx.category) == SpendCategory.SAVINGS) continue
            txDao.updateCategory(id, SpendCategory.SELF_TRANSFER.name)
            if (tx.note.isBlank()) txDao.updateNote(id, note)
            paired++
        }
        return paired
    }

    /**
     * Deletes merchant confirmations shadowed by a bank debit (same amount ±6h),
     * collapses bank+bank rows that share amount + non-unknown merchantKey within ±6h
     * (keep earliest), and drops Groww/Zerodha UMRN payment alerts when a matching ACH
     * ledger debit exists. Returns how many rows were removed.
     */
    suspend fun purgeCrossSourceDuplicates(): Int {
        val all = txDao.listAll()
        val points = all.map { tx ->
            CrossSourcePoint(
                id = tx.id,
                amountPaise = tx.amountPaise,
                occurredAt = tx.occurredAt,
                merchantKey = tx.merchantKey,
                isBank = SmsDebitParser.isBankDebit(tx.bodySnippet, tx.sender),
                bodySnippet = tx.bodySnippet,
            )
        }
        var removed = 0
        val gone = mutableSetOf<Long>()
        for (id in SmsDebitParser.merchantIdsShadowedByBank(points)) {
            if (gone.add(id)) {
                txDao.deleteById(id)
                removed++
            }
        }
        val afterMerchants = points.filter { it.id !in gone }
        for (id in SmsDebitParser.bankSameMerchantDuplicateIds(afterMerchants)) {
            if (gone.add(id)) {
                txDao.deleteById(id)
                removed++
            }
        }
        val afterBank = afterMerchants.filter { it.id !in gone }
        for (id in SmsDebitParser.investmentMandateAlertDuplicateIds(afterBank)) {
            if (gone.add(id)) {
                txDao.deleteById(id)
                removed++
            }
        }
        return removed
    }

    /**
     * Re-apply rules/self-name precedence to SELF_TRANSFER rows so RD installments
     * previously locked as self-transfers become Savings.
     */
    private suspend fun repairSelfTransferPrecedence(): Int {
        val aliases = prefs.selfNameAliases.first()
        val selfs = txDao.listByCategory(SpendCategory.SELF_TRANSFER.name)
        var fixed = 0
        val done = mutableSetOf<String>()
        for (tx in selfs) {
            if (!done.add(tx.merchantKey)) continue
            val resolved = SelfTransferMatcher.resolveWithRules(
                merchantKey = tx.merchantKey,
                merchantRaw = tx.merchantRaw,
                body = tx.bodySnippet,
                aliasesCsv = aliases,
            ) ?: continue
            if (resolved == SpendCategory.SELF_TRANSFER) continue
            retag(tx.id, resolved.name, applyToSimilar = true)
            fixed++
        }
        return fixed
    }

    /**
     * User flagged a transaction as wrongly parsed. If a Gemini key is available, the
     * AI re-reads the SMS: a confirmed debit gets its amount/merchant corrected (and a
     * sender-scoped amount regex saved for future scans); a non-debit is deleted with
     * an ignore rule so rescans skip it. Without a key the transaction is just removed
     * and remembered as ignored.
     */
    suspend fun reportWrongParse(transactionId: Long): WrongParseResult {
        val tx = txDao.getById(transactionId) ?: return WrongParseResult.NotFound
        val senderKey = SmsDebitParser.senderKey(tx.sender)
        val apiKey = prefs.geminiApiKey.first()
        val ai = if (apiKey.isBlank()) {
            null
        } else {
            runCatching { gemini.reparse(apiKey, tx.bodySnippet, tx.sender) }.getOrNull()
        }

        if (ai != null && ai.isDebit && ai.amountPaise != null) {
            val merchantRaw = (ai.merchant ?: tx.merchantRaw).take(80)
            val merchantKey = SmsDebitParser.normalizeMerchantKey(merchantRaw)
            txDao.updateParsedFields(tx.id, ai.amountPaise, merchantRaw, merchantKey)
            txDao.updateDedupKey(tx.id, SmsDebitParser.dedupKey(tx.bodySnippet, ai.amountPaise))
            var ruleSaved = false
            val regex = ai.amountRegex
            if (regex != null && extractPaiseWithRegex(regex, tx.bodySnippet) == ai.amountPaise) {
                ruleDao.insert(
                    ParseRuleEntity(
                        senderKey = senderKey,
                        kind = ParseRuleEntity.KIND_AMOUNT_REGEX,
                        pattern = regex,
                        sampleSnippet = tx.bodySnippet,
                    ),
                )
                ruleSaved = true
            }
            return WrongParseResult.Fixed(newAmountPaise = ai.amountPaise, ruleSaved = ruleSaved)
        }

        txDao.deleteById(tx.id)
        ruleDao.insert(
            ParseRuleEntity(
                senderKey = senderKey,
                kind = ParseRuleEntity.KIND_IGNORE_SMS,
                pattern = tx.smsId.toString(),
                sampleSnippet = tx.bodySnippet,
            ),
        )
        var ruleSaved = false
        val ignoreRegex = ai?.ignoreRegex
        if (ignoreRegex != null && safeRegexMatch(ignoreRegex, tx.bodySnippet)) {
            ruleDao.insert(
                ParseRuleEntity(
                    senderKey = senderKey,
                    kind = ParseRuleEntity.KIND_IGNORE_REGEX,
                    pattern = ignoreRegex,
                    sampleSnippet = tx.bodySnippet,
                ),
            )
            ruleSaved = true
        }
        return WrongParseResult.Removed(ruleSaved = ruleSaved)
    }

    private suspend fun resolveCategoryKey(
        merchantKey: String,
        merchantRaw: String,
        body: String,
        apiKey: String,
    ): String {
        memDao.get(merchantKey)?.let { return it.category }
        val aliases = prefs.selfNameAliases.first()
        SelfTransferMatcher.resolveWithRules(merchantKey, merchantRaw, body, aliases)?.let { cat ->
            remember(merchantKey, merchantRaw, cat.name)
            return cat.name
        }
        if (apiKey.isBlank()) return SpendCategory.OTHER.name
        val ai = runCatching { gemini.categorize(apiKey, merchantRaw, body) }.getOrNull()
        if (ai != null && ai != SpendCategory.OTHER) {
            remember(merchantKey, merchantRaw, ai.name)
            return ai.name
        }
        return SpendCategory.OTHER.name
    }

    private suspend fun remember(merchantKey: String, merchantRaw: String, categoryKey: String) {
        memDao.upsert(
            MerchantMemoryEntity(
                merchantKey = merchantKey,
                category = categoryKey,
                displayName = merchantRaw,
            ),
        )
    }

    suspend fun retag(
        transactionId: Long,
        categoryKey: String,
        applyToSimilar: Boolean,
    ) {
        val tx = txDao.getById(transactionId) ?: return
        if (applyToSimilar) {
            txDao.updateCategoryForMerchant(tx.merchantKey, categoryKey)
        } else {
            txDao.updateCategory(transactionId, categoryKey)
        }
        remember(tx.merchantKey, tx.merchantRaw, categoryKey)
        ensureCustomCategoryIcon(categoryKey)
    }

    suspend fun setTransactionNote(id: Long, note: String) {
        txDao.updateNote(id, note.trim())
    }

    suspend fun recentTags(limit: Int = 12): List<String> = txDao.recentNotes(limit)

    /**
     * Stores a UPI app notification and, if a matching untagged transaction exists
     * within ±30 minutes, fills its note with "payee · note".
     */
    suspend fun captureUpiNotification(
        packageName: String,
        amountPaise: Long,
        payee: String,
        note: String,
        postedAt: Long,
    ) {
        upiDao.pruneOlderThan(postedAt - UPI_EVENT_RETENTION_MS)
        upiDao.insert(
            UpiEventEntity(
                packageName = packageName,
                amountPaise = amountPaise,
                payee = payee,
                note = note,
                postedAt = postedAt,
            ),
        )
        mergeUpiOntoTransaction(amountPaise, payee, note, postedAt)
    }

    /** After inserting a bank SMS, pull a waiting UPI notification note if any. */
    private suspend fun applyUpiEventNote(transactionId: Long, amountPaise: Long, occurredAt: Long) {
        val existing = txDao.getById(transactionId) ?: return
        if (existing.note.isNotBlank()) return
        val event = upiDao.findRecentByAmount(
            amountPaise = amountPaise,
            fromMs = occurredAt - UPI_MERGE_WINDOW_MS,
            toMs = occurredAt + UPI_MERGE_WINDOW_MS,
        ) ?: return
        val formatted = UpiNotificationParser.formatNote(event.payee, event.note)
        if (formatted.isNotBlank()) {
            txDao.updateNote(transactionId, formatted)
        }
    }

    private suspend fun mergeUpiOntoTransaction(
        amountPaise: Long,
        payee: String,
        note: String,
        postedAt: Long,
    ) {
        val formatted = UpiNotificationParser.formatNote(payee, note)
        if (formatted.isBlank()) return
        val tx = txDao.findRecentUntaggedByAmount(
            amountPaise = amountPaise,
            fromMs = postedAt - UPI_MERGE_WINDOW_MS,
            toMs = postedAt + UPI_MERGE_WINDOW_MS,
        ) ?: return
        txDao.updateNote(tx.id, formatted)
    }

    suspend fun askGeminiForTransaction(
        transactionId: Long,
        applyToSimilar: Boolean,
    ): String? {
        val tx = txDao.getById(transactionId) ?: return null
        val apiKey = prefs.geminiApiKey.first()
        if (apiKey.isBlank()) return null
        val ai = runCatching {
            gemini.categorize(apiKey, tx.merchantRaw, tx.bodySnippet)
        }.getOrNull() ?: return null
        if (ai == SpendCategory.OTHER) return null
        retag(transactionId, ai.name, applyToSimilar)
        return ai.label
    }

    suspend fun recategorizeOther(useGemini: Boolean): RecategorizeResult {
        memDao.deleteByCategory(SpendCategory.OTHER.name)
        val others = txDao.listByCategory(SpendCategory.OTHER.name)
        var rulesUpdated = 0
        var geminiUpdated = 0
        var failed = 0
        var firstError: String? = null
        val apiKey = if (useGemini) prefs.geminiApiKey.first() else ""
        val aliases = prefs.selfNameAliases.first()
        val doneMerchants = mutableSetOf<String>()
        val needsGemini = mutableListOf<Pair<TransactionEntity, MerchantForCategorize>>()

        for (tx in others) {
            if (!doneMerchants.add(tx.merchantKey)) continue
            val resolved = SelfTransferMatcher.resolveWithRules(
                merchantKey = tx.merchantKey,
                merchantRaw = tx.merchantRaw,
                body = tx.bodySnippet,
                aliasesCsv = aliases,
            )
            if (resolved != null) {
                retag(tx.id, resolved.name, applyToSimilar = true)
                rulesUpdated++
                continue
            }
            if (apiKey.isBlank()) continue
            needsGemini += tx to MerchantForCategorize(
                merchantKey = tx.merchantKey,
                merchantRaw = tx.merchantRaw,
                bodySnippet = tx.bodySnippet,
            )
        }

        if (apiKey.isNotBlank() && needsGemini.isNotEmpty()) {
            for (chunk in needsGemini.chunked(GeminiCategorizer.BATCH_SIZE)) {
                val (mapped, error) = gemini.categorizeBatch(apiKey, chunk.map { it.second })
                if (error != null) {
                    failed += chunk.size
                    if (firstError == null) {
                        firstError = "Gemini failed (${error.code}): ${error.message}"
                    }
                    continue
                }
                for ((tx, merchant) in chunk) {
                    val storageKey = mapped[merchant.merchantKey] ?: continue
                    retag(tx.id, storageKey, applyToSimilar = true)
                    geminiUpdated++
                }
            }
        }

        return RecategorizeResult(
            rulesUpdated = rulesUpdated,
            geminiUpdated = geminiUpdated,
            failed = failed,
            firstError = firstError,
        )
    }

    /**
     * Ask Gemini which same-amount pairs within 24h are likely the same payment.
     * Flags the later row with note "Possible duplicate" when its note is empty.
     * Never deletes.
     */
    suspend fun flagPossibleDuplicatesWithGemini(): DuplicateFlagResult {
        val apiKey = prefs.geminiApiKey.first()
        if (apiKey.isBlank()) {
            return DuplicateFlagResult(flagged = 0, candidates = 0, error = "No API key")
        }
        val pairs = findDuplicatePairCandidates()
        if (pairs.isEmpty()) {
            return DuplicateFlagResult(flagged = 0, candidates = 0)
        }
        var flagged = 0
        var firstError: String? = null
        for (chunk in pairs.chunked(GeminiCategorizer.BATCH_SIZE)) {
            val (confirmed, error) = gemini.flagDuplicatePairs(apiKey, chunk)
            if (error != null && firstError == null) {
                firstError = "Gemini failed (${error.code}): ${error.message}"
            }
            val byId = chunk.associateBy { it.pairId }
            for (pairId in confirmed) {
                val pair = byId[pairId] ?: continue
                val later = txDao.getById(pair.laterId) ?: continue
                if (later.note.isNotBlank()) continue
                txDao.updateNote(later.id, GeminiCategorizer.DUPLICATE_NOTE)
                flagged++
            }
        }
        return DuplicateFlagResult(
            flagged = flagged,
            candidates = pairs.size,
            error = firstError,
        )
    }

    /**
     * Same amount within 24h, distinct rows not already flagged.
     * Exposed for unit tests via companion heuristics where possible.
     */
    suspend fun findDuplicatePairCandidates(
        windowMs: Long = 24L * 60 * 60 * 1000,
    ): List<DuplicatePairCandidate> {
        val all = txDao.listAll().sortedBy { it.occurredAt }
        return buildDuplicatePairCandidates(all, windowMs)
    }

    suspend fun clearMerchantMemory() {
        memDao.clearAll()
    }

    suspend fun clearOtherMemory() {
        memDao.deleteByCategory(SpendCategory.OTHER.name)
    }
}
