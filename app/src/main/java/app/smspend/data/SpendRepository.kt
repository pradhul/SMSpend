package app.smspend.data

import app.smspend.domain.CategoryBucket
import app.smspend.domain.PeriodAccounting
import app.smspend.domain.RecurringDetector
import app.smspend.domain.SpendCategory
import app.smspend.util.DateRanges
import app.smspend.util.InboxScanWindow
import app.smspend.util.MonthWindow
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.regex.Pattern
import kotlin.math.abs
import app.smspend.notify.UpiNotificationParser
import app.smspend.util.runCatchingCancellable

data class CategorySpend(
    val bucket: CategoryBucket,
    val totalPaise: Long,
    val txnCount: Int,
    val merchants: List<String>,
)

/** One period's category spends and the notices shown under them. */
data class RangeSummary(
    val spends: List<CategorySpend>,
    val notices: List<String>,
) {
    companion object {
        val EMPTY = RangeSummary(emptyList(), emptyList())
    }
}

data class ScanResult(
    val scannedSms: Int,
    val newTransactions: Int,
    val newDebitTransactions: Int = newTransactions,
)

data class AiPassResult(
    /** PENDING transactions considered by this pass. */
    val pending: Int,
    /** Transactions whose category Gemini set (or changed). */
    val applied: Int,
    /** Transactions resolved from merchant_memory without a Gemini call. */
    val fromMemory: Int,
    /** Transactions left PENDING because a Gemini call failed (retried next scan). */
    val failed: Int,
    val error: String? = null,
)

data class AiTriageResult(
    /** Rejected SMS that passed the local candidate filter and had no cached verdict. */
    val candidates: Int,
    /** Candidates sent to Gemini this pass. */
    val sent: Int,
    /** Transactions inserted from AI verdicts. */
    val inserted: Int,
    /** New per-sender templates saved. */
    val templatesLearned: Int,
    val error: String? = null,
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
    val todaySpendPaise: Long,
    val monthSpendPaise: Long,
    val budgetPaise: Long?,
    val topCategories: List<Pair<String, Long>>,
)

data class CategoryYtdTrend(
    val bucket: CategoryBucket,
    val monthTotals: List<Long>,
    val ytdTotalPaise: Long,
)

data class CategoryMonthRank(
    val bucket: CategoryBucket,
    /** Sum across the Trends window (for ranking). */
    val windowTotalPaise: Long,
    val thisMonthPaise: Long,
    val lastMonthPaise: Long,
)

data class MonthlySpendTrend(
    val months: List<MonthWindow>,
    val topKeys: List<String>,
    val series: Map<String, List<Long>>,
    val otherByMonth: List<Long>,
    val ranks: List<CategoryMonthRank>,
) {
    val windowTotalPaise: Long
        get() = ranks.sumOf { it.windowTotalPaise }

    val thisMonthTotalPaise: Long
        get() = ranks.sumOf { it.thisMonthPaise }
}

data class TrendsMerchantShare(
    val merchantRaw: String,
    val amountPaise: Long,
)

data class TrendsInsightHint(
    val categoryLabel: String,
    val thisMonthPaise: Long,
    val lastMonthPaise: Long,
    val topMerchants: List<TrendsMerchantShare>,
    val thisMonthTxnCount: Int = 0,
    val lastMonthTxnCount: Int = 0,
)

/** One spend row of the month report on the Exports page. */
data class SpendReportRow(
    val txId: Long,
    val occurredAt: Long,
    val merchant: String,
    val categoryLabel: String,
    val tags: List<String>,
    val amountPaise: Long,
) {
    /** "What is this spending about" — category plus the user's own tags. */
    val aboutText: String
        get() = if (tags.isEmpty()) categoryLabel else "$categoryLabel · ${tags.joinToString(", ")}"
}

data class MonthSpendReport(
    val month: MonthWindow,
    val rows: List<SpendReportRow>,
) {
    val totalPaise: Long get() = rows.sumOf { it.amountPaise }
}

data class TagNetOffset(
    val tag: String,
    val creditPaise: Long,
    val appliedPaise: Long,
)

data class TagNetResult(
    val spends: List<CategorySpend>,
    val offsets: List<TagNetOffset>,
) {
    val noticeLines: List<String>
        get() = offsets.map { off ->
            val amt = formatPaiseShort(off.appliedPaise)
            "₹$amt credit tagged '${off.tag}' offset against spends"
        }
}

data class CardBillBalance(
    val billPaise: Long,
    val cardSpendPaise: Long,
) {
    val notice: String
        get() {
            val bill = formatPaiseShort(billPaise)
            val spend = formatPaiseShort(cardSpendPaise)
            return "Card bill ₹$bill paid · ₹$spend card spends tracked this period"
        }
}

private fun formatPaiseShort(paise: Long): String {
    val rupees = paise / 100.0
    return if (rupees >= 1000) {
        String.format(java.util.Locale.US, "%,.0f", rupees)
    } else if (rupees == rupees.toLong().toDouble()) {
        rupees.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", rupees)
    }
}

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
    private val correctionDao get() = db.categoryCorrections()
    private val decisionDao get() = db.duplicateDecisions()
    private val learnedDao get() = db.learnedTxDecisions()
    private val pairDao get() = db.selfTransferPairs()
    private val verdictDao get() = db.aiSmsVerdicts()
    private val scanMutex = Mutex()
    private val triageMutex = Mutex()

    companion object {
        /** ± window to merge a UPI notification onto a bank SMS transaction. */
        const val UPI_MERGE_WINDOW_MS = 30L * 60 * 1000
        private const val UPI_EVENT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        /** Retag-history cap: enough few-shot signal without unbounded growth. */
        private const val MAX_CORRECTIONS = 200

        /** Corrections included as few-shot examples in the batch prompt. */
        private const val EXAMPLE_CORRECTIONS = 30

        /** Overall cap on few-shot examples (corrections + memory + month). */
        private const val EXAMPLE_TOTAL = 90

        /** SQLite bind-variable safety for id-list updates. */
        private const val ID_CHUNK = 500

        /**
         * How far behind an incremental scan's window the maintenance passes look.
         * Covers the widest pairing window (self/RD cross notes, 14 days) with margin,
         * so incremental scans no longer walk the whole table.
         */
        const val MAINTENANCE_LOOKBACK_MS = 31L * 24 * 60 * 60 * 1000

        /** Incremental AI triage re-reads this far behind its watermark. */
        const val TRIAGE_OVERLAP_MS = 10L * 60 * 1000

        /**
         * Build candidate pairs: same amount, within [windowMs], not already noted as duplicate.
         * Prefer pairing earliest with each later match (one pair per later row per amount cluster).
         * [excludedPairKeys] skips pairs the user marked as not duplicates.
         */
        fun buildDuplicatePairCandidates(
            txs: List<TransactionEntity>,
            windowMs: Long = 24L * 60 * 60 * 1000,
            excludedPairKeys: Set<String> = emptySet(),
        ): List<DuplicatePairCandidate> {
            val sorted = txs
                .filter { it.isDebit() }
                .sortedWith(compareBy({ it.occurredAt }, { it.id }))
            val out = mutableListOf<DuplicatePairCandidate>()
            val usedLater = mutableSetOf<Long>()
            for (i in sorted.indices) {
                val a = sorted[i]
                if (a.isPossibleDuplicateNote()) continue
                for (j in (i + 1) until sorted.size) {
                    val b = sorted[j]
                    if (b.id in usedLater) continue
                    if (b.isPossibleDuplicateNote()) continue
                    if (b.amountPaise != a.amountPaise) continue
                    val dt = abs(b.occurredAt - a.occurredAt)
                    if (dt > windowMs) break // sorted by time — further ones are farther
                    // Skip identical dedupKey (already collapsed path) — still rare residual.
                    if (a.dedupKey.isNotBlank() && a.dedupKey == b.dedupKey) continue
                    val earlier = if (a.occurredAt <= b.occurredAt) a else b
                    val later = if (a.occurredAt <= b.occurredAt) b else a
                    val pairKey = DuplicateDecisionEntity.pairKeyFor(earlier, later)
                    if (pairKey in excludedPairKeys) continue
                    out += DuplicatePairCandidate(
                        pairId = "${earlier.id}-${later.id}",
                        earlierId = earlier.id,
                        laterId = later.id,
                        amountPaise = earlier.amountPaise,
                        earlierSender = earlier.sender,
                        laterSender = later.sender,
                        earlierSnippet = earlier.bodySnippet,
                        laterSnippet = later.bodySnippet,
                        earlierMerchant = earlier.merchantRaw,
                        laterMerchant = later.merchantRaw,
                        earlierAt = earlier.occurredAt,
                        laterAt = later.occurredAt,
                        earlierNote = earlier.statusTag.trim().ifBlank { earlier.note },
                        laterNote = later.statusTag.trim().ifBlank { later.note },
                    )
                    usedLater += later.id
                }
            }
            return out
        }

        /**
         * Net tagged credits against debits that share the same user tag within a range.
         * System status tags never participate. Untagged credits stay in CREDITS.
         */
        fun applyTagNetting(
            txs: List<TransactionEntity>,
            normalizeCategory: (String) -> String = { it },
        ): TagNetResult {
            val net = PeriodAccounting.netPeriod(txs)
            val spends = mutableListOf<CategorySpend>()

            net.countableDebits
                .groupBy { normalizeCategory(it.category) }
                .forEach { (key, rows) ->
                    if (key == SpendCategory.CREDITS.name) return@forEach
                    val total = rows.sumOf { it.amountPaise }
                    if (total <= 0L) return@forEach
                    spends += CategorySpend(
                        bucket = CategoryBucket.fromStored(key),
                        totalPaise = total,
                        txnCount = rows.size,
                        merchants = rows.map { it.merchantRaw }.distinct().take(3),
                    )
                }

            val residual = net.residualCredits
            val residualTotal = residual.sumOf { it.amountPaise }
            if (residualTotal > 0L) {
                spends += CategorySpend(
                    bucket = CategoryBucket.fromStored(SpendCategory.CREDITS.name),
                    totalPaise = residualTotal,
                    txnCount = residual.size,
                    merchants = residual.map { it.merchantRaw }.distinct().take(3),
                )
            }

            val offsets = mutableListOf<TagNetOffset>()
            if (net.nettedPaise > 0L) {
                val eligible = txs.filter { it.countsTowardTotals() }
                val creditByTag = eligible
                    .filter { it.isCredit() }
                    .mapNotNull { tx ->
                        tx.userTagOrNull()?.let { TransactionEntity.normalizeUserTag(it) to tx }
                    }
                    .groupBy({ it.first }, { it.second })
                val debitByTag = net.countableDebits
                    .mapNotNull { tx ->
                        tx.userTagOrNull()?.let { TransactionEntity.normalizeUserTag(it) to tx }
                    }
                    .groupBy({ it.first }, { it.second })

                for ((tagKey, creditRows) in creditByTag) {
                    if (debitByTag[tagKey].orEmpty().isEmpty()) continue
                    val creditTotal = creditRows.sumOf { it.amountPaise }
                    val residualForTag = residual.filter { tx ->
                        tx.userTagOrNull()?.let { TransactionEntity.normalizeUserTag(it) } == tagKey
                    }.sumOf { it.amountPaise }
                    val applied = creditTotal - residualForTag
                    if (applied > 0L) {
                        offsets += TagNetOffset(
                            tag = creditRows.first().userTagOrNull() ?: tagKey,
                            creditPaise = creditTotal,
                            appliedPaise = applied,
                        )
                    }
                }
                if (offsets.isEmpty()) {
                    offsets += TagNetOffset(
                        tag = "credits",
                        creditPaise = net.nettedPaise,
                        appliedPaise = net.nettedPaise,
                    )
                }
            }

            return TagNetResult(
                spends = spends.sortedByDescending { it.totalPaise },
                offsets = offsets,
            )
        }

        fun cardBillBalance(txs: List<TransactionEntity>): CardBillBalance? {
            val billPaise = txs
                .filter { it.isDebit() && it.isCardBillPaymentNote() }
                .sumOf { it.amountPaise }
            if (billPaise <= 0L) return null
            val cardSpendPaise = txs
                .filter { it.isDebit() && it.countsTowardTotals() && it.isCardSpend() }
                .sumOf { it.amountPaise }
            return CardBillBalance(billPaise = billPaise, cardSpendPaise = cardSpendPaise)
        }

        /**
         * Bulk "apply to similar" is debit-only. Credits share a bank-label merchantKey
         * when the payer can't be parsed, so bulk retag would hit every credit into that
         * bank — and writing merchant memory for the bank key would keep re-applying it.
         * Credits are always retagged one row at a time with no memory write.
         */
        fun shouldApplyToSimilar(tx: TransactionEntity, requested: Boolean): Boolean =
            requested && tx.isDebit()

        /**
         * Given recorded duplicate pair keys (`"sourceA|sourceB"`, sorted halves), return the
         * source key paired with [selfSourceKey], or null when no recorded pair involves it.
         */
        fun resolvePeerSourceKey(pairKeys: Collection<String>, selfSourceKey: String): String? {
            if (selfSourceKey.isBlank()) return null
            for (key in pairKeys) {
                val parts = key.split("|")
                if (parts.size != 2) continue
                when (selfSourceKey) {
                    parts[0] -> return parts[1]
                    parts[1] -> return parts[0]
                }
            }
            return null
        }

        /** Netted total for one category in a raw txn list (same rules as [applyTagNetting]). */
        fun nettedCategoryTotal(
            txs: List<TransactionEntity>,
            categoryKey: String,
            normalizeCategory: (String) -> String = { it },
        ): Long {
            val result = applyTagNetting(txs, normalizeCategory)
            return result.spends.firstOrNull { it.bucket.key == categoryKey }?.totalPaise
                ?: 0L
        }
    }

    /**
     * Spends + notices for one period from a single netting pass. Mapping runs on
     * [Dispatchers.Default] so large months don't jank the UI thread.
     */
    fun observeRangeSummary(
        startMs: Long,
        endMs: Long = DateRanges.OPEN_END_MS,
    ): Flow<RangeSummary> =
        txDao.observeInRange(startMs, endMs).map { list ->
            val net = applyTagNetting(list) { normalizeCategoryKey(it) }
            RangeSummary(
                spends = net.spends,
                notices = buildList {
                    addAll(net.noticeLines)
                    cardBillBalance(list)?.let { add(it.notice) }
                },
            )
        }.flowOn(Dispatchers.Default)

    fun observeRangeByCategory(
        startMs: Long,
        endMs: Long = DateRanges.OPEN_END_MS,
    ): Flow<List<CategorySpend>> = observeRangeSummary(startMs, endMs).map { it.spends }

    fun observeRangeNotices(
        startMs: Long,
        endMs: Long = DateRanges.OPEN_END_MS,
    ): Flow<List<String>> = observeRangeSummary(startMs, endMs).map { it.notices }

    /** Netted total for a category in range (matches Home category cards). */
    fun observeCategoryNettedTotal(
        categoryKey: String,
        startMs: Long,
        endMs: Long,
    ): Flow<Long> =
        txDao.observeInRange(startMs, endMs).map { list ->
            nettedCategoryTotal(list, categoryKey) { normalizeCategoryKey(it) }
        }.flowOn(Dispatchers.Default)

    fun observeCategoryNettingNotice(
        categoryKey: String,
        startMs: Long,
        endMs: Long,
    ): Flow<String?> =
        txDao.observeInRange(startMs, endMs).map { list ->
            val catTxs = list.filter { normalizeCategoryKey(it.category) == categoryKey }
            val tags = catTxs.mapNotNull { it.userTagOrNull() }
                .map { TransactionEntity.normalizeUserTag(it) }
                .toSet()
            if (tags.isEmpty()) return@map null
            val net = applyTagNetting(list) { normalizeCategoryKey(it) }
            val relevant = net.offsets.filter {
                TransactionEntity.normalizeUserTag(it.tag) in tags
            }
            if (relevant.isEmpty()) null else relevant.joinToString(" · ") {
                "₹${formatPaiseShort(it.appliedPaise)} credit tagged '${it.tag}' offset"
            }
        }.flowOn(Dispatchers.Default)

    @Deprecated("Use observeRangeByCategory", ReplaceWith("observeRangeByCategory(weekStartMs, weekEndMs)"))
    fun observeWeekByCategory(
        weekStartMs: Long = DateRanges.startOfThisWeekMs(),
        weekEndMs: Long = DateRanges.OPEN_END_MS,
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
        val name = runCatchingCancellable { gemini.suggestIconName(apiKey, label) }.getOrNull() ?: return
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
        weekEndMs: Long = DateRanges.OPEN_END_MS,
    ): Flow<List<TransactionEntity>> =
        txDao.observeInRange(weekStartMs, weekEndMs).map { list ->
            list.filter { normalizeCategoryKey(it.category) == categoryKey }
        }.flowOn(Dispatchers.Default)

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
            val monthTxs = txDao.listInRange(month.startMs, month.endExclusiveMs)
            if (categoryKey == SpendCategory.CREDITS.name) {
                PeriodAccounting.residualCreditTotal(monthTxs)
            } else {
                PeriodAccounting.categoryTotals(monthTxs)
                    .entries
                    .filter { normalizeCategoryKey(it.key) == categoryKey }
                    .sumOf { it.value }
            }
        }
    }

    suspend fun categoryMonthlyTotalsThisYear(): List<CategoryYtdTrend> {
        val months = DateRanges.yearToDateMonths()
        if (months.isEmpty()) return emptyList()
        val from = months.first().startMs
        val to = DateRanges.endExclusiveNow()
        val allTxs = txDao.listInRange(from, to)
        val keys = mutableSetOf<String>()
        for (month in months) {
            val monthTxs = allTxs.filter {
                it.occurredAt >= month.startMs && it.occurredAt < month.endExclusiveMs
            }
            keys += PeriodAccounting.categoryTotals(monthTxs).keys.map { normalizeCategoryKey(it) }
            if (PeriodAccounting.residualCreditTotal(monthTxs) > 0L) {
                keys += SpendCategory.CREDITS.name
            }
        }
        return keys.map { key ->
            val monthTotals = months.map { month ->
                val monthTxs = allTxs.filter {
                    it.occurredAt >= month.startMs && it.occurredAt < month.endExclusiveMs
                }
                if (key == SpendCategory.CREDITS.name) {
                    PeriodAccounting.residualCreditTotal(monthTxs)
                } else {
                    PeriodAccounting.categoryTotals(monthTxs)
                        .entries
                        .filter { normalizeCategoryKey(it.key) == key }
                        .sumOf { it.value }
                }
            }
            CategoryYtdTrend(
                bucket = CategoryBucket.fromStored(key),
                monthTotals = monthTotals,
                ytdTotalPaise = monthTotals.sum(),
            )
        }.sortedByDescending { it.ytdTotalPaise }
    }

    /**
     * Last [DateRanges.TRENDS_MONTH_COUNT] months of spend-only totals for Trends:
     * top 4 categories (+ Other) series for a stacked chart, and ranked cards with MoM.
     */
    suspend fun monthlySpendTrend(): MonthlySpendTrend {
        val months = DateRanges.trendsMonths()
        if (months.isEmpty()) {
            return MonthlySpendTrend(
                months = emptyList(),
                topKeys = emptyList(),
                series = emptyMap(),
                otherByMonth = emptyList(),
                ranks = emptyList(),
            )
        }
        val monthCount = months.size
        val byCatMonth = mutableMapOf<String, LongArray>()
        for (month in months) {
            val monthTxs = txDao.listInRange(month.startMs, month.endExclusiveMs)
            val totals = PeriodAccounting.categoryTotals(monthTxs)
            val monthIndex = months.indexOf(month)
            for ((cat, amount) in totals) {
                val key = normalizeCategoryKey(cat)
                if (!SpendCategory.countsAsSpend(key)) continue
                val arr = byCatMonth.getOrPut(key) { LongArray(monthCount) }
                arr[monthIndex] += amount
            }
        }

        val rankedKeys = byCatMonth.entries
            .map { (key, arr) -> key to arr.sum() }
            .sortedByDescending { it.second }
            .map { it.first }

        val topKeys = rankedKeys.take(4)
        val topSet = topKeys.toSet()
        val series = topKeys.associateWith { key ->
            byCatMonth[key]?.toList() ?: List(monthCount) { 0L }
        }
        val otherByMonth = LongArray(monthCount)
        for ((key, arr) in byCatMonth) {
            if (key in topSet) continue
            for (i in 0 until monthCount) otherByMonth[i] += arr[i]
        }

        val thisIdx = monthCount - 1
        val lastIdx = (monthCount - 2).coerceAtLeast(0)
        val ranks = rankedKeys.map { key ->
            val arr = byCatMonth[key]!!
            CategoryMonthRank(
                bucket = CategoryBucket.fromStored(key),
                windowTotalPaise = arr.sum(),
                thisMonthPaise = arr.getOrElse(thisIdx) { 0L },
                lastMonthPaise = if (monthCount >= 2) arr.getOrElse(lastIdx) { 0L } else 0L,
            )
        }

        return MonthlySpendTrend(
            months = months,
            topKeys = topKeys,
            series = series,
            otherByMonth = otherByMonth.toList(),
            ranks = ranks,
        )
    }

    /**
     * Compact MoM + merchant facts for Gemini Trends commentary.
     * Uses this month / last month only; top merchants per top categories.
     */
    suspend fun buildTrendsInsightHints(limitCategories: Int = 6): List<TrendsInsightHint> {
        val thisMonth = DateRanges.monthWindow(0)
        val lastMonth = DateRanges.monthWindow(1)
        val from = lastMonth.startMs
        val to = thisMonth.endExclusiveMs
        val spendTxs = txDao.listInRange(from, to)
            .filter { it.isDebit() }
            .filter { it.countsTowardTotals() }
            .filter { SpendCategory.countsAsSpend(normalizeCategoryKey(it.category)) }
        val byCat = spendTxs.groupBy { normalizeCategoryKey(it.category) }
        return byCat.entries
            .map { (key, txs) ->
                val thisTxs = txs.filter {
                    it.occurredAt >= thisMonth.startMs && it.occurredAt < thisMonth.endExclusiveMs
                }
                val lastTxs = txs.filter {
                    it.occurredAt >= lastMonth.startMs && it.occurredAt < lastMonth.endExclusiveMs
                }
                val topMerchants = thisTxs
                    .groupBy { it.merchantRaw.ifBlank { it.merchantKey } }
                    .map { (name, list) -> TrendsMerchantShare(name, list.sumOf { it.amountPaise }) }
                    .sortedByDescending { it.amountPaise }
                    .take(3)
                TrendsInsightHint(
                    categoryLabel = SpendCategory.displayLabel(key),
                    thisMonthPaise = thisTxs.sumOf { it.amountPaise },
                    lastMonthPaise = lastTxs.sumOf { it.amountPaise },
                    topMerchants = topMerchants,
                    thisMonthTxnCount = thisTxs.size,
                    lastMonthTxnCount = lastTxs.size,
                )
            }
            .sortedByDescending { it.thisMonthPaise }
            .take(limitCategories)
            .filter { it.thisMonthPaise > 0 || it.lastMonthPaise > 0 }
    }

    suspend fun hasGeminiKey(): Boolean = prefs.geminiApiKey.first().isNotBlank()

    /**
     * Recurring merchants whose expected date has passed with no new payment recorded —
     * candidates for the orange "looks unpaid" Trends warning.
     */
    suspend fun listOverdueRecurring(
        nowMs: Long = System.currentTimeMillis(),
    ): List<RecurringMerchantEntity> =
        recurringDao.listDueBy(nowMs).filter {
            it.displayName.isNotBlank() && !it.displayName.equals("unknown", ignoreCase = true)
        }

    /**
     * Gemini bullet summary of what changed this month vs last (amounts, frequency,
     * missed recurring payments). Throws with a user-facing message on failure.
     */
    suspend fun trendsSummaryBullets(): List<TrendsBullet> {
        val apiKey = prefs.geminiApiKey.first()
        if (apiKey.isBlank()) {
            throw IllegalStateException("Add a Gemini API key in Settings first")
        }
        val hints = buildTrendsInsightHints()
        if (hints.isEmpty()) {
            throw IllegalStateException("Not enough spend yet for a summary")
        }
        val overdue = listOverdueRecurring().map {
            UpcomingSpendHint(
                displayName = it.displayName,
                medianPaise = it.medianPaise,
                cadenceDays = it.cadenceDays,
                lastOccurredAt = it.lastOccurredAt,
                nextExpectedAt = it.nextExpectedAt,
            )
        }
        val result = gemini.summarizeTrends(
            apiKey,
            hints.map {
                TrendsExplainHint(
                    categoryLabel = it.categoryLabel,
                    thisMonthPaise = it.thisMonthPaise,
                    lastMonthPaise = it.lastMonthPaise,
                    topMerchants = it.topMerchants.map { m -> m.merchantRaw to m.amountPaise },
                    thisMonthTxnCount = it.thisMonthTxnCount,
                    lastMonthTxnCount = it.lastMonthTxnCount,
                )
            },
            overdue,
        )
        return when (result) {
            is GeminiResult.Success ->
                gemini.parseTrendsBullets(result.text).ifEmpty {
                    throw IllegalStateException("Could not read the Gemini summary")
                }
            is GeminiResult.Failure -> throw IllegalStateException(result.message)
        }
    }

    /** All recorded spendings of the current month, oldest first, for the Exports report. */
    suspend fun monthSpendReport(): MonthSpendReport {
        val month = DateRanges.monthWindow(0)
        val rows = txDao.listInRange(month.startMs, month.endExclusiveMs)
            .filter { it.isDebit() && it.countsTowardTotals() }
            .filter { SpendCategory.countsAsSpend(normalizeCategoryKey(it.category)) }
            .sortedBy { it.occurredAt }
            .map { tx ->
                SpendReportRow(
                    txId = tx.id,
                    occurredAt = tx.occurredAt,
                    merchant = tx.merchantRaw.ifBlank { tx.merchantKey }.ifBlank { "Unknown" },
                    categoryLabel = CategoryBucket.fromStored(normalizeCategoryKey(tx.category)).label,
                    tags = tx.userTags(),
                    amountPaise = tx.amountPaise,
                )
            }
        return MonthSpendReport(month = month, rows = rows)
    }

    /**
     * Gemini labels for notable spend dates in the report (holiday/festival/landmark).
     * Returns day-start millis → short label; ordinary days are omitted.
     */
    suspend fun annotateReportDates(rows: List<SpendReportRow>): Map<Long, String> {
        val apiKey = prefs.geminiApiKey.first()
        if (apiKey.isBlank()) {
            throw IllegalStateException("Add a Gemini API key in Settings first")
        }
        val dayStarts = rows.map { DateRanges.startOfTodayMs(it.occurredAt) }.distinct().sorted()
        if (dayStarts.isEmpty()) {
            throw IllegalStateException("No spends recorded this month yet")
        }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val isoByDay = dayStarts.associateWith { fmt.format(java.util.Date(it)) }
        return when (val result = gemini.annotateSpendDates(apiKey, isoByDay.values.toList())) {
            is GeminiResult.Success -> {
                val labels = gemini.parseDateLabels(result.text, isoByDay.values.toSet())
                isoByDay.mapNotNull { (day, iso) -> labels[iso]?.let { day to it } }.toMap()
            }
            is GeminiResult.Failure -> throw IllegalStateException(result.message)
        }
    }

    suspend fun widgetSnapshot(): WidgetSnapshot {
        val month = DateRanges.monthWindow(0)
        val (todayStart, todayEnd) = DateRanges.todayWindow()
        val nowEnd = DateRanges.endExclusiveNow()
        val monthTxs = txDao.listInRange(month.startMs, nowEnd)
        val todayTxs = monthTxs.filter { it.occurredAt in todayStart until todayEnd }
        fun spendTotal(txs: List<TransactionEntity>): Long {
            val net = PeriodAccounting.netPeriod(txs)
            return net.countableDebits
                .filter { SpendCategory.countsAsSpend(normalizeCategoryKey(it.category)) }
                .sumOf { it.amountPaise }
        }
        val monthNet = PeriodAccounting.netPeriod(monthTxs)
        val cats = monthNet.countableDebits
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
            todaySpendPaise = spendTotal(todayTxs),
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
            .filter { it.isDebit() }
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

    private fun normalizeCategoryKey(stored: String): String =
        when (stored) {
            "FOOD_DELIVERY" -> SpendCategory.FOOD_DINING.name
            else -> stored
        }

    suspend fun getTransaction(id: Long): TransactionEntity? = txDao.getById(id)

    suspend fun lastSyncAt(): Long = syncDao.get()?.lastSyncAtMs ?: 0L

    suspend fun validateApiKey(apiKey: String): GeminiKeyCheck = gemini.validateApiKey(apiKey)

    suspend fun explainUpcomingSpends(
        items: List<RecurringMerchantEntity>,
    ): String {
        val apiKey = prefs.geminiApiKey.first()
        if (apiKey.isBlank()) {
            throw IllegalStateException("Add a Gemini API key in Settings first")
        }
        val hints = items.map {
            UpcomingSpendHint(
                displayName = it.displayName,
                medianPaise = it.medianPaise,
                cadenceDays = it.cadenceDays,
                lastOccurredAt = it.lastOccurredAt,
                nextExpectedAt = it.nextExpectedAt,
            )
        }
        return when (val result = gemini.explainUpcomingSpends(apiKey, hints)) {
            is GeminiResult.Success -> result.text.trim()
            is GeminiResult.Failure -> throw IllegalStateException(result.message)
        }
    }

    suspend fun scanInbox(
        forceFullMonth: Boolean = false,
        sinceOverrideMs: Long? = null,
    ): ScanResult = scanMutex.withLock {
        scanInboxLocked(forceFullMonth, sinceOverrideMs)
    }

    private suspend fun scanInboxLocked(
        forceFullMonth: Boolean = false,
        sinceOverrideMs: Long? = null,
    ): ScanResult {
        val meta = syncDao.get()
        val oldestHomeMonth = DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1)
        val since = InboxScanWindow.sinceMs(
            lastSmsDateMs = meta?.lastSmsDateMs ?: 0L,
            oldestHomeMonthMs = oldestHomeMonth,
            sinceOverrideMs = sinceOverrideMs,
            forceFullMonth = forceFullMonth,
        )

        val messages = smsReader.readSince(since)
        var inserted = 0
        var newDebits = 0
        var parseSkipped = 0
        var ignoredByRules = 0
        var merchantConfirmSkipped = 0
        var maxDate = meta?.lastSmsDateMs ?: 0L
        val rulesBySender = ruleDao.listAll().groupBy { it.senderKey }
        val state = newInsertState(since)

        DiagLog.i(
            "scan",
            "scan_start",
            mapOf(
                "sinceMs" to since,
                "forceFullMonth" to forceFullMonth,
                "messageCount" to messages.size,
            ),
        )

        // One write transaction for the whole batch: atomic, and Room flows (Home) re-emit
        // once per scan instead of once per inserted row.
        db.withTransaction {
        for (sms in messages) {
            maxDate = maxOf(maxDate, sms.dateMs)
            val senderRules = rulesBySender[SmsDebitParser.senderKey(sms.sender)].orEmpty()
            if (isIgnoredByRules(senderRules, sms.id, sms.body)) {
                ignoredByRules++
                DiagLog.i(
                    "scan",
                    "parse_skip",
                    mapOf(
                        "reason" to "ignore_rule",
                        "sender" to sms.sender,
                        "smsId" to sms.id,
                    ),
                )
                continue
            }
            val parsed = SmsDebitParser.parse(sms.body, sms.sender)
            if (parsed == null) {
                parseSkipped++
                val atmRev = SmsDebitParser.parseAtmReversal(sms.body)
                if (atmRev != null) {
                    applyAtmReversal(atmRev, sms.dateMs, state.recent)
                    DiagLog.i(
                        "scan",
                        "parse_skip",
                        mapOf(
                            "reason" to "atm_reversal",
                            "sender" to sms.sender,
                            "amountPaise" to atmRev.amountPaise,
                        ),
                    )
                    continue
                }
                if (CreditSmsParser.parseCredit(sms.body, sms.sender, sms.dateMs) == null) {
                    val learned = templateDebit(senderRules, sms)
                    if (learned != null) {
                        when (insertParsedDebit(state, sms, learned, learned.amountPaise, TransactionEntity.SOURCE_TEMPLATE)) {
                            DebitInsertOutcome.INSERTED -> {
                                inserted++
                                newDebits++
                            }
                            DebitInsertOutcome.MERCHANT_COVERED -> merchantConfirmSkipped++
                            DebitInsertOutcome.SKIPPED -> Unit
                        }
                    }
                }
                continue
            }
            val amountPaise = amountFromRules(senderRules, sms.body) ?: parsed.amountPaise
            when (insertParsedDebit(state, sms, parsed, amountPaise, TransactionEntity.SOURCE_RULE)) {
                DebitInsertOutcome.INSERTED -> {
                    inserted++
                    newDebits++
                }
                DebitInsertOutcome.MERCHANT_COVERED -> merchantConfirmSkipped++
                DebitInsertOutcome.SKIPPED -> Unit
            }
        }

        // Persist inbound credits *to* My banks as CREDITS (separate from spends).
        // From there they may stay Credits, get paired to Self transfer, or be manually tagged.
        val myBankIds = app.smspend.domain.IndianBanks.parseIdsCsv(prefs.myBankIds.first())
        if (myBankIds.isNotEmpty()) {
            for (sms in messages) {
                val senderRules = rulesBySender[SmsDebitParser.senderKey(sms.sender)].orEmpty()
                if (isIgnoredByRules(senderRules, sms.id, sms.body)) continue
                val builtin = CreditSmsParser.parseCredit(sms.body, sms.sender, sms.dateMs)
                val credit = builtin
                    ?: (if (SmsDebitParser.parse(sms.body, sms.sender) == null) templateCredit(senderRules, sms) else null)
                    ?: continue
                val source = if (builtin != null) TransactionEntity.SOURCE_RULE else TransactionEntity.SOURCE_TEMPLATE
                if (insertParsedCredit(state, sms, credit, myBankIds, source)) inserted++
            }
        }

        syncDao.upsert(
            SyncMetaEntity(
                id = 1,
                lastSmsDateMs = maxDate,
                lastSyncAtMs = System.currentTimeMillis(),
            ),
        )
        // Reversals don't insert a row, so this has to run even when nothing was stored.
        purgeAtmReversals(messages)
        }
        // Skip maintenance when this pass only re-read the overlap and stored nothing —
        // those rows were already cleaned. Incremental scans (one new SMS) only clean a
        // recent window; first/forced/period scans clean the whole table.
        val fullPass = forceFullMonth || sinceOverrideMs != null || meta == null || meta.lastSmsDateMs <= 0L
        val deepClean = fullPass || inserted > 0
        if (deepClean) {
            val windowStart = if (fullPass) null else since - MAINTENANCE_LOOKBACK_MS
            db.withTransaction {
                // Clean already-stored merchant confirmations / same-payee bank doubles.
                purgeCrossSourceDuplicatesLocked(windowStart)
                repairHighConfidenceCategories(windowStart)
                applySelfRdCrossNotesLocked(windowStart = windowStart)
                applyCreditPairSelfTransfersLocked(since)
            }
        }
        DiagLog.i(
            "scan",
            "scan_end",
            mapOf(
                "scannedSms" to messages.size,
                "newTransactions" to inserted,
                "newDebitTransactions" to newDebits,
                "parseSkipped" to parseSkipped,
                "ignoredByRules" to ignoredByRules,
                "merchantConfirmSkipped" to merchantConfirmSkipped,
            ),
        )
        return ScanResult(
            scannedSms = messages.size,
            newTransactions = inserted,
            newDebitTransactions = newDebits,
        )
    }

    /** Working set shared by every insert in one pass (scan or AI triage). */
    private class InsertState(
        val seenDedupKeys: MutableSet<String>,
        /** Bank-is-truth working set: classify from body+sender (no schema flag). */
        val recent: MutableList<TransactionEntity>,
        /** amountPaise to occurredAt of bank debits inserted in this pass. */
        val batchBankDebits: MutableList<Pair<Long, Long>> = mutableListOf(),
    )

    private enum class DebitInsertOutcome { INSERTED, MERCHANT_COVERED, SKIPPED }

    private suspend fun newInsertState(sinceMs: Long): InsertState {
        val windowMs = SmsDebitParser.CROSS_SOURCE_WINDOW_MS
        return InsertState(
            seenDedupKeys = txDao.listDedupKeys().toMutableSet(),
            recent = txDao.listInRange(sinceMs - windowMs, System.currentTimeMillis() + windowMs)
                .toMutableList(),
        )
    }

    /**
     * Dedup (bank replaces a merchant confirmation), merchant-confirmation suppression,
     * card-bill detection, category resolution, insert. Shared by rule, template, and AI rows.
     */
    private suspend fun insertParsedDebit(
        state: InsertState,
        sms: RawSms,
        parsed: ParsedDebit,
        amountPaise: Long,
        parseSource: String,
    ): DebitInsertOutcome {
        val windowMs = SmsDebitParser.CROSS_SOURCE_WINDOW_MS
        val sourceKey = TransactionEntity.sourceKeyForSms(sms.id)
        if (learnedDao.get(sourceKey, LearnedTxDecisionEntity.TYPE_REMOVED_DUPLICATE)?.decision ==
            LearnedTxDecisionEntity.YES
        ) {
            return DebitInsertOutcome.SKIPPED
        }
        val key = SmsDebitParser.directionalDedupKey(sms.body, amountPaise, sms.dateMs, isCredit = false)
        val isBank = SmsDebitParser.isBankDebit(sms.body, sms.sender)
        if (key in state.seenDedupKeys) {
            val existing = txDao.getByDedupKey(key)
            val holderIsBank = existing != null &&
                existing.isDebit() &&
                SmsDebitParser.isBankDebit(existing.bodySnippet, existing.sender)
            if (existing != null &&
                SmsDebitParser.incomingBankReplacesDedupHolder(isBank, holderIsBank)
            ) {
                // Merchant confirmation claimed this ref first. The bank SMS is the ledger.
                txDao.deleteById(existing.id)
                state.recent.removeAll { it.id == existing.id }
                state.seenDedupKeys.remove(key)
            } else if (existing == null) {
                state.seenDedupKeys.remove(key)
            } else {
                if (isBank && holderIsBank) adoptNamedPayee(existing, parsed, sms.sender)
                return DebitInsertOutcome.SKIPPED
            }
        }

        if (!isBank) {
            // Merchant confirmation — skip when a bank debit already covers this amount.
            val bankPoints = state.recent
                .filter { SmsDebitParser.isBankDebit(it.bodySnippet, it.sender) }
                .map { it.amountPaise to it.occurredAt } + state.batchBankDebits
            if (SmsDebitParser.hasMatchingBankDebit(amountPaise, sms.dateMs, bankPoints, windowMs)) {
                DiagLog.i(
                    "scan",
                    "parse_skip",
                    mapOf(
                        "reason" to "merchant_confirm_covered",
                        "sender" to sms.sender,
                        "amountPaise" to amountPaise,
                        "merchantKey" to parsed.merchantKey,
                    ),
                )
                return DebitInsertOutcome.MERCHANT_COVERED
            }
        } else {
            // Bank wins: drop merchant-confirmation rows with the same amount in-window.
            val merchants = state.recent.filter { tx ->
                tx.isDebit() &&
                    !SmsDebitParser.isBankDebit(tx.bodySnippet, tx.sender) &&
                    tx.amountPaise == amountPaise &&
                    abs(tx.occurredAt - sms.dateMs) <= windowMs
            }
            for (m in merchants) {
                txDao.deleteById(m.id)
                state.recent.removeAll { it.id == m.id }
            }
        }

        val resolved = resolveCategoryKey(parsed.merchantKey, parsed.merchantRaw, sms.body)
        val learnedCard = learnedDao.get(sourceKey, LearnedTxDecisionEntity.TYPE_CARD_BILL)
        val cardPayment = isBank &&
            learnedCard?.decision != LearnedTxDecisionEntity.NO &&
            (
                learnedCard?.decision == LearnedTxDecisionEntity.YES ||
                    SmsDebitParser.isCardBillPayment(sms.body, parsed.merchantRaw)
                )
        val cardLast4 = if (!cardPayment) {
            SmsDebitParser.extractCardLast4(sms.body).orEmpty()
        } else {
            ""
        }
        val statusTag = if (cardPayment) SmsDebitParser.CARD_PAYMENT_NOTE else ""
        val row = TransactionEntity(
            smsId = sms.id,
            amountPaise = amountPaise,
            merchantRaw = parsed.merchantRaw,
            merchantKey = parsed.merchantKey,
            category = if (cardPayment) SpendCategory.BILLS.name else resolved.key,
            occurredAt = sms.dateMs,
            bodySnippet = sms.body.take(180),
            sender = sms.sender,
            dedupKey = key,
            note = "",
            statusTag = statusTag,
            cardLast4 = cardLast4,
            sourceKey = sourceKey,
            aiStatus = if (cardPayment) TransactionEntity.AI_DONE else resolved.aiStatus,
            type = TransactionEntity.TYPE_DEBIT,
            parseSource = parseSource,
        )
        val id = txDao.insertIgnore(row)
        if (id == -1L) return DebitInsertOutcome.SKIPPED
        state.seenDedupKeys += key
        state.recent += row.copy(id = id)
        if (isBank) {
            state.batchBankDebits += amountPaise to sms.dateMs
            applyUpiEventNote(id, amountPaise, sms.dateMs)
        }
        DiagLog.i(
            "scan",
            "insert",
            mapOf(
                "txId" to id,
                "merchantKey" to parsed.merchantKey,
                "sender" to sms.sender,
                "amountPaise" to amountPaise,
                "category" to row.category,
                "source" to if (cardPayment) "card_payment" else resolved.source,
                "parseSource" to parseSource,
                "aiStatus" to row.aiStatus,
                "snippet" to DiagLog.truncateSnippet(sms.body),
            ),
        )
        return DebitInsertOutcome.INSERTED
    }

    /** Inbound credit to one of My banks → CREDITS row. Returns true when a row was inserted. */
    private suspend fun insertParsedCredit(
        state: InsertState,
        sms: RawSms,
        credit: ParsedCredit,
        myBankIds: Set<String>,
        parseSource: String,
    ): Boolean {
        val sourceKey = TransactionEntity.sourceKeyForSms(sms.id)
        if (learnedDao.get(sourceKey, LearnedTxDecisionEntity.TYPE_REMOVED_DUPLICATE)?.decision ==
            LearnedTxDecisionEntity.YES
        ) {
            return false
        }
        // bankId is the bank that credited the account — i.e. money *to* that bank.
        if (credit.bankId == null || credit.bankId !in myBankIds) return false
        val key = SmsDebitParser.directionalDedupKey(sms.body, credit.amountPaise, sms.dateMs, isCredit = true)
        if (key in state.seenDedupKeys) return false
        val row = TransactionEntity(
            smsId = sms.id,
            amountPaise = credit.amountPaise,
            merchantRaw = credit.merchantRaw,
            merchantKey = credit.merchantKey,
            category = SpendCategory.CREDITS.name,
            occurredAt = sms.dateMs,
            bodySnippet = credit.bodySnippet,
            sender = sms.sender,
            dedupKey = key,
            note = "",
            sourceKey = sourceKey,
            aiStatus = TransactionEntity.AI_DONE,
            type = TransactionEntity.TYPE_CREDIT,
            parseSource = parseSource,
        )
        val id = txDao.insertIgnore(row)
        if (id == -1L) return false
        state.seenDedupKeys += key
        state.recent += row.copy(id = id)
        DiagLog.i(
            "scan",
            "insert_credit",
            mapOf(
                "txId" to id,
                "merchantKey" to credit.merchantKey,
                "sender" to sms.sender,
                "amountPaise" to credit.amountPaise,
                "bankId" to (credit.bankId ?: ""),
                "parseSource" to parseSource,
                "snippet" to DiagLog.truncateSnippet(sms.body),
            ),
        )
        return true
    }

    private fun templateMatch(rules: List<ParseRuleEntity>, kind: String, body: String): TemplateMatch? =
        rules.asSequence()
            .filter { it.kind == kind }
            .mapNotNull { AiSmsTriage.applyTemplate(it.pattern, body) }
            .firstOrNull()

    private fun templateDebit(rules: List<ParseRuleEntity>, sms: RawSms): ParsedDebit? =
        templateMatch(rules, ParseRuleEntity.KIND_TEMPLATE_DEBIT, sms.body)?.let { m ->
            debitFrom(sms, m.amountPaise, m.payee)
        }

    private fun templateCredit(rules: List<ParseRuleEntity>, sms: RawSms): ParsedCredit? =
        templateMatch(rules, ParseRuleEntity.KIND_TEMPLATE_CREDIT, sms.body)?.let { m ->
            creditFrom(sms, m.amountPaise, m.payee)
        }

    private fun debitFrom(sms: RawSms, amountPaise: Long, payee: String?): ParsedDebit {
        val raw = (payee ?: SmsDebitParser.extractMerchant(sms.body) ?: sms.sender.ifBlank { "Unknown" })
            .trim()
            .take(80)
        return ParsedDebit(amountPaise = amountPaise, merchantRaw = raw, merchantKey = SmsDebitParser.normalizeMerchantKey(raw))
    }

    private fun creditFrom(sms: RawSms, amountPaise: Long, payee: String?): ParsedCredit {
        val bank = app.smspend.domain.IndianBanks.matchSender(sms.sender)
        val raw = (payee ?: bank?.label ?: sms.sender.ifBlank { "Credit" }).trim().take(80)
        return ParsedCredit(
            amountPaise = amountPaise,
            upiRef = CreditSmsParser.extractUpiRef(sms.body),
            bankId = bank?.id,
            merchantRaw = raw,
            merchantKey = SmsDebitParser.normalizeMerchantKey(raw),
            bodySnippet = sms.body.take(180),
            sender = sms.sender,
            occurredAt = sms.dateMs,
        )
    }

    /**
     * Two bank SMS for the same ref (SBI "a/c … credited" + YONO "transfer to Aryabd"):
     * when the kept row only has the sender id as merchant, take the payee from the other.
     */
    private suspend fun adoptNamedPayee(existing: TransactionEntity, parsed: ParsedDebit, sender: String) {
        if (!SmsDebitParser.isSenderIdMerchant(existing.merchantRaw, existing.sender)) return
        if (SmsDebitParser.isSenderIdMerchant(parsed.merchantRaw, sender)) return
        if (parsed.amountPaise != existing.amountPaise) return
        if (existing.merchantKey in correctionDao.listMerchantKeys().toSet()) return
        txDao.updateParsedFields(existing.id, existing.amountPaise, parsed.merchantRaw, parsed.merchantKey)
    }

    /**
     * Period Sync: scan → repair → batched Gemini pass → return remaining duplicate pairs for UI.
     */
    suspend fun syncPeriod(startMs: Long, endMs: Long = DateRanges.endExclusiveNow()): SyncPeriodResult {
        val scan = scanInbox(forceFullMonth = true, sinceOverrideMs = startMs)
        val repair = repairTransactions()
        val triage = runCatchingCancellable { runAiTriagePass() }.getOrNull()
        val ai = runAiCategorizationPass()
        val pairs = findDuplicatePairCandidates()
            .filter { pair ->
                val earlier = txDao.getById(pair.earlierId) ?: return@filter false
                earlier.occurredAt in startMs until endMs ||
                    (txDao.getById(pair.laterId)?.occurredAt ?: 0L) in startMs until endMs
            }
        return SyncPeriodResult(
            scannedSms = scan.scannedSms,
            newTransactions = scan.newTransactions + (triage?.inserted ?: 0),
            repaired = repair.removed + repair.fixed,
            recategorized = ai.applied + ai.fromMemory,
            selfTransfersPaired = 0,
            duplicatePairs = pairs,
        )
    }

    /** Detect banks from inbox senders for My banks pre-selection. */
    suspend fun detectMyBanksFromInbox(sinceMs: Long = DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1)): Set<String> {
        val messages = runCatchingCancellable { smsReader.readSince(sinceMs) }.getOrDefault(emptyList())
        return app.smspend.domain.IndianBanks.detectFromSenders(messages.map { it.sender })
    }

    /**
     * Backfill inbound credits to My banks that were missed before banks were configured.
     */
    suspend fun backfillCreditsForMyBanks(
        sinceMs: Long = DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1),
    ): Int = scanMutex.withLock { backfillCreditsForMyBanksLocked(sinceMs) }

    private suspend fun backfillCreditsForMyBanksLocked(sinceMs: Long): Int {
        val myBankIds = app.smspend.domain.IndianBanks.parseIdsCsv(prefs.myBankIds.first())
        if (myBankIds.isEmpty()) return 0
        val messages = smsReader.readSince(sinceMs)
        val rulesBySender = ruleDao.listAll().groupBy { it.senderKey }
        val seenDedupKeys = txDao.listDedupKeys().toMutableSet()
        var inserted = 0
        db.withTransaction {
        for (sms in messages) {
            val sourceKey = TransactionEntity.sourceKeyForSms(sms.id)
            if (learnedDao.get(sourceKey, LearnedTxDecisionEntity.TYPE_REMOVED_DUPLICATE)?.decision ==
                LearnedTxDecisionEntity.YES
            ) {
                continue
            }
            val senderRules = rulesBySender[SmsDebitParser.senderKey(sms.sender)].orEmpty()
            if (isIgnoredByRules(senderRules, sms.id, sms.body)) continue
            val credit = CreditSmsParser.parseCredit(sms.body, sms.sender, sms.dateMs) ?: continue
            if (credit.bankId == null || credit.bankId !in myBankIds) continue
            val key = SmsDebitParser.directionalDedupKey(
                sms.body,
                credit.amountPaise,
                sms.dateMs,
                isCredit = true,
            )
            if (key in seenDedupKeys) continue
            val row = TransactionEntity(
                smsId = sms.id,
                amountPaise = credit.amountPaise,
                merchantRaw = credit.merchantRaw,
                merchantKey = credit.merchantKey,
                category = SpendCategory.CREDITS.name,
                occurredAt = sms.dateMs,
                bodySnippet = credit.bodySnippet,
                sender = sms.sender,
                dedupKey = key,
                note = "",
                sourceKey = sourceKey,
                aiStatus = TransactionEntity.AI_DONE,
                type = TransactionEntity.TYPE_CREDIT,
            )
            val id = txDao.insertIgnore(row)
            if (id != -1L) {
                inserted++
                seenDedupKeys += key
            }
        }
        }
        return inserted
    }

    /** Called after the user saves My banks — backfill credits and re-run pairing. */
    suspend fun onMyBanksSaved() = scanMutex.withLock {
        val since = DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1)
        backfillCreditsForMyBanksLocked(since)
        db.withTransaction { applyCreditPairSelfTransfersLocked(since) }
    }

    suspend fun listCustomCategoryKeys(): List<String> =
        txDao.listDistinctCategories()
            .map { normalizeCategoryKey(it) }
            .filter { SpendCategory.isCustomKey(it) }
            .distinct()
            .sorted()

    /** Delete later rows of duplicate pairs; returns how many were removed. */
    suspend fun removeDuplicateLaters(laterIds: Collection<Long>): Int = scanMutex.withLock {
        var removed = 0
        db.withTransaction {
        for (id in laterIds.distinct()) {
            val later = txDao.getById(id) ?: continue
            findNearbyDuplicatePeers(later).firstOrNull()?.let { earlier ->
                decisionDao.upsert(
                    DuplicateDecisionEntity(
                        pairKey = DuplicateDecisionEntity.pairKeyFor(earlier, later),
                        decision = DuplicateDecisionEntity.DUPLICATE,
                    ),
                )
            }
            learnedDao.upsert(
                LearnedTxDecisionEntity(
                    sourceKey = LearnedTxDecisionEntity.sourceKeyFor(later),
                    decisionType = LearnedTxDecisionEntity.TYPE_REMOVED_DUPLICATE,
                    decision = LearnedTxDecisionEntity.YES,
                ),
            )
            txDao.deleteById(id)
            removed++
        }
        }
        removed
    }

    /** Record that a review-sheet pair is not a duplicate (learn for next time). */
    suspend fun rememberNotDuplicatePair(earlierId: Long, laterId: Long) {
        val a = txDao.getById(earlierId) ?: return
        val b = txDao.getById(laterId) ?: return
        decisionDao.upsert(
            DuplicateDecisionEntity(
                pairKey = DuplicateDecisionEntity.pairKeyFor(a, b),
                decision = DuplicateDecisionEntity.NOT_DUPLICATE,
            ),
        )
    }

    /**
     * Clear the "Possible duplicate" status tag and remember NOT_DUPLICATE against
     * nearby same-amount peers so Gemini/rescan won't reflag this pair.
     */
    suspend fun markNotDuplicate(txId: Long) {
        val tx = txDao.getById(txId) ?: return
        if (tx.isPossibleDuplicateNote()) {
            txDao.updateStatusTag(txId, "")
        }
        for (peer in findNearbyDuplicatePeers(tx)) {
            decisionDao.upsert(
                DuplicateDecisionEntity(
                    pairKey = DuplicateDecisionEntity.pairKeyFor(tx, peer),
                    decision = DuplicateDecisionEntity.NOT_DUPLICATE,
                ),
            )
        }
    }

    /**
     * Mark [txId] (or the later of the pair) as a possible duplicate of [otherId]
     * and remember the DUPLICATE decision.
     */
    suspend fun markAsDuplicate(txId: Long, otherId: Long) {
        val a = txDao.getById(txId) ?: return
        val b = txDao.getById(otherId) ?: return
        val earlier = if (a.occurredAt <= b.occurredAt) a else b
        val later = if (a.occurredAt <= b.occurredAt) b else a
        decisionDao.upsert(
            DuplicateDecisionEntity(
                pairKey = DuplicateDecisionEntity.pairKeyFor(earlier, later),
                decision = DuplicateDecisionEntity.DUPLICATE,
            ),
        )
        if (later.statusTag.isBlank() || later.isPossibleDuplicateNote()) {
            txDao.updateStatusTag(later.id, GeminiCategorizer.DUPLICATE_NOTE)
        }
    }

    /**
     * The transaction [txId] was (or could be) flagged as a duplicate of — resolved from the
     * recorded DUPLICATE decision when one exists, else the nearest same-amount peer within 24h.
     */
    suspend fun findDuplicatePeer(txId: Long): TransactionEntity? {
        val tx = txDao.getById(txId) ?: return null
        val recordedKeys = decisionDao.listPairKeysByDecision(DuplicateDecisionEntity.DUPLICATE)
        resolvePeerSourceKey(recordedKeys, tx.effectiveSourceKey())?.let { peerKey ->
            txDao.getBySourceKey(peerKey)?.let { return it }
        }
        return findNearbyDuplicatePeers(tx).firstOrNull()
    }

    /** Same-amount debit peers within 24h, excluding self. */
    suspend fun findNearbyDuplicatePeers(
        tx: TransactionEntity,
        windowMs: Long = 24L * 60 * 60 * 1000,
    ): List<TransactionEntity> {
        return txDao.listInRange(tx.occurredAt - windowMs, tx.occurredAt + windowMs + 1)
            .filter { it.id != tx.id }
            .filter { it.isDebit() }
            .filter { it.amountPaise == tx.amountPaise }
            .filter { abs(it.occurredAt - tx.occurredAt) <= windowMs }
            .sortedBy { abs(it.occurredAt - tx.occurredAt) }
    }

    suspend fun markAsCardBillPayment(txId: Long) {
        val tx = txDao.getById(txId) ?: return
        learnedDao.upsert(
            LearnedTxDecisionEntity(
                sourceKey = LearnedTxDecisionEntity.sourceKeyFor(tx),
                decisionType = LearnedTxDecisionEntity.TYPE_CARD_BILL,
                decision = LearnedTxDecisionEntity.YES,
            ),
        )
        txDao.updateStatusTag(txId, SmsDebitParser.CARD_PAYMENT_NOTE)
        if (normalizeCategoryKey(tx.category) != SpendCategory.BILLS.name) {
            txDao.updateCategory(txId, SpendCategory.BILLS.name)
        }
    }

    suspend fun clearCardBillPayment(txId: Long) {
        val tx = txDao.getById(txId) ?: return
        if (!tx.isCardBillPaymentNote()) return
        learnedDao.upsert(
            LearnedTxDecisionEntity(
                sourceKey = LearnedTxDecisionEntity.sourceKeyFor(tx),
                decisionType = LearnedTxDecisionEntity.TYPE_CARD_BILL,
                decision = LearnedTxDecisionEntity.NO,
            ),
        )
        txDao.updateStatusTag(txId, "")
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
        runCatchingCancellable {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(body).find()
        }.getOrDefault(false)

    private fun extractPaiseWithRegex(pattern: String, body: String): Long? =
        runCatchingCancellable {
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
    suspend fun repairTransactions(): RepairResult = scanMutex.withLock { repairTransactionsLocked() }

    private suspend fun repairTransactionsLocked(): RepairResult {
        // Full re-parse is the one pass that genuinely needs every row.
        val all = txDao.listAll()
        val fullSmsById = runCatchingCancellable { smsReader.readByIds(all.map { it.smsId }) }
            .getOrDefault(emptyMap())
        // Inbox reads happen before the write transaction so it isn't held during IO.
        val atmMessages = runCatchingCancellable {
            smsReader.readSince(DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1))
        }.getOrDefault(emptyList())
        val rulesBySender = ruleDao.listAll().groupBy { it.senderKey }
        val myBankIds = app.smspend.domain.IndianBanks.parseIdsCsv(prefs.myBankIds.first())
        var removed = 0
        var fixed = 0
        var crossSourceRemoved = 0
        db.withTransaction {
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

            val asSms = RawSms(id = tx.smsId, body = body, sender = sender, dateMs = tx.occurredAt)
            if (tx.isCredit()) {
                val credit = CreditSmsParser.parseCredit(body, sender, tx.occurredAt)
                    ?: templateCredit(senderRules, asSms)
                if (credit == null) {
                    // AI-found rows have no local parser by definition; the user undoes them explicitly.
                    if (tx.isLearnedParse()) continue
                    txDao.deleteById(tx.id)
                    removed++
                    continue
                }
                if (credit.amountPaise != tx.amountPaise ||
                    credit.merchantRaw != tx.merchantRaw ||
                    credit.merchantKey != tx.merchantKey
                ) {
                    if (credit.amountPaise != tx.amountPaise) fixed++
                    txDao.updateParsedFields(
                        tx.id,
                        credit.amountPaise,
                        credit.merchantRaw,
                        credit.merchantKey,
                    )
                }
                val key = SmsDebitParser.directionalDedupKey(
                    body,
                    credit.amountPaise,
                    tx.occurredAt,
                    isCredit = true,
                )
                if (tx.dedupKey != key) {
                    txDao.updateDedupKey(tx.id, key)
                }
                if (tx.sourceKey.isBlank()) {
                    txDao.updateSourceKey(tx.id, TransactionEntity.sourceKeyForSms(tx.smsId))
                }
                continue
            }

            val parsed = SmsDebitParser.parse(body, sender)
                ?: templateDebit(senderRules, asSms).takeIf {
                    CreditSmsParser.parseCredit(body, sender, tx.occurredAt) == null
                }
            if (parsed == null) {
                if (tx.isLearnedParse()) continue
                val credit = CreditSmsParser.parseCredit(body, sender, tx.occurredAt)
                // Only convert when My banks is configured and the credit is to one of them.
                val bankOk = myBankIds.isNotEmpty() &&
                    credit?.bankId != null &&
                    credit.bankId in myBankIds
                if (credit != null && bankOk) {
                    txDao.updateParsedFields(
                        tx.id,
                        credit.amountPaise,
                        credit.merchantRaw,
                        credit.merchantKey,
                    )
                    txDao.updateType(tx.id, TransactionEntity.TYPE_CREDIT)
                    txDao.updateCategory(tx.id, SpendCategory.CREDITS.name)
                    val key = SmsDebitParser.directionalDedupKey(
                        body,
                        credit.amountPaise,
                        tx.occurredAt,
                        isCredit = true,
                    )
                    txDao.updateDedupKeyAndType(tx.id, key, TransactionEntity.TYPE_CREDIT)
                    if (tx.sourceKey.isBlank()) {
                        txDao.updateSourceKey(tx.id, TransactionEntity.sourceKeyForSms(tx.smsId))
                    }
                    fixed++
                } else {
                    txDao.deleteById(tx.id)
                    removed++
                }
                continue
            }
            val amount = amountFromRules(senderRules, body) ?: parsed.amountPaise
            // Keep a payee adopted from a sibling bank SMS instead of reverting to the sender id.
            val keepNamed = SmsDebitParser.isSenderIdMerchant(parsed.merchantRaw, sender) &&
                !SmsDebitParser.isSenderIdMerchant(tx.merchantRaw, tx.sender)
            val merchantRaw = if (keepNamed) tx.merchantRaw else parsed.merchantRaw
            val merchantKey = if (keepNamed) tx.merchantKey else parsed.merchantKey
            if (amount != tx.amountPaise ||
                merchantRaw != tx.merchantRaw ||
                merchantKey != tx.merchantKey
            ) {
                if (amount != tx.amountPaise) fixed++
                txDao.updateParsedFields(tx.id, amount, merchantRaw, merchantKey)
            }
            val key = SmsDebitParser.directionalDedupKey(body, amount, tx.occurredAt, isCredit = false)
            if (tx.dedupKey != key) {
                txDao.updateDedupKey(tx.id, key)
            }
            if (tx.sourceKey.isBlank()) {
                txDao.updateSourceKey(tx.id, TransactionEntity.sourceKeyForSms(tx.smsId))
            }
        }

        // Collapse duplicates: same dedupKey → keep the earliest row (lowest id).
        val remaining = txDao.listRowsWithSharedDedupKey()
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

        crossSourceRemoved = purgeCrossSourceDuplicatesLocked(null)
        removed += crossSourceRemoved
        removed += purgeAtmReversals(atmMessages)
        fixed += repairHighConfidenceCategories(null)
        applySelfRdCrossNotesLocked(windowStart = null)
        applyCreditPairSelfTransfersLocked(DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1))
        }
        return RepairResult(
            removed = removed,
            fixed = fixed,
            crossSourceRemoved = crossSourceRemoved,
        )
    }

    /**
     * Marks ATM Withdrawn rows that have a matching "ATM Txn Reversed" SMS as failed withdrawals
     * (keeps the row with note) instead of deleting them. Only withdrawals near the reversal
     * dates are loaded.
     */
    private suspend fun purgeAtmReversals(messages: List<RawSms>): Int {
        val reversals = messages.mapNotNull { sms ->
            SmsDebitParser.parseAtmReversal(sms.body)?.let { it to sms.dateMs }
        }
        if (reversals.isEmpty()) return 0
        val window = SmsDebitParser.ATM_REVERSAL_WINDOW_MS
        val from = reversals.minOf { it.second } - window
        val to = reversals.maxOf { it.second } + window + 1
        val withdrawals = txDao.listInRange(from, to)
            .filter { SmsDebitParser.isAtmWithdrawal(it.bodySnippet, it.merchantKey) }
            .filter { !it.isAtmFailed() }
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
            .filter { !it.isAtmFailed() }
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
                    statusTag = SmsDebitParser.ATM_FAILED_NOTE,
                )
            }
        }
        return ids.size
    }

    private suspend fun markAtmWithdrawalFailed(id: Long) {
        txDao.updateCategory(id, SpendCategory.WITHDRAWALS.name)
        txDao.updateStatusTag(id, SmsDebitParser.ATM_FAILED_NOTE)
    }

    /**
     * Same-amount self transfer ↔ savings within 14 days: cross-tag notes.
     */
    suspend fun applySelfRdCrossNotes(
        windowMs: Long = SmsDebitParser.SELF_RD_CROSS_NOTE_WINDOW_MS,
    ): Int = scanMutex.withLock {
        db.withTransaction { applySelfRdCrossNotesLocked(windowMs, windowStart = null) }
    }

    /** [windowStart] null = whole table; otherwise only rows from that time on. */
    private suspend fun applySelfRdCrossNotesLocked(
        windowMs: Long = SmsDebitParser.SELF_RD_CROSS_NOTE_WINDOW_MS,
        windowStart: Long?,
    ): Int {
        val all = if (windowStart == null) txDao.listAll() else txDao.listInRange(windowStart, DateRanges.OPEN_END_MS)
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
            if (self.statusTag.isBlank()) {
                txDao.updateStatusTag(self.id, SmsDebitParser.SELF_RD_WENT_NOTE)
                tagged++
            }
            if (match.statusTag.isBlank()) {
                txDao.updateStatusTag(match.id, SmsDebitParser.SELF_RD_FROM_NOTE)
                tagged++
            }
        }
        return tagged
    }

    /**
     * Pair credits *to* My banks with debits to mark Self transfer.
     * Only untouched CREDITS rows are auto-paired; user retags / TYPE_SELF_TRANSFER NO decisions win.
     */
    suspend fun applyCreditPairSelfTransfers(sinceMs: Long): Int = scanMutex.withLock {
        db.withTransaction { applyCreditPairSelfTransfersLocked(sinceMs) }
    }

    private suspend fun applyCreditPairSelfTransfersLocked(sinceMs: Long): Int {
        val bankIds = app.smspend.domain.IndianBanks.parseIdsCsv(prefs.myBankIds.first())
        if (bankIds.isEmpty()) return 0
        val blockedSelf = learnedDao
            .listKeys(LearnedTxDecisionEntity.TYPE_SELF_TRANSFER, LearnedTxDecisionEntity.NO)
            .toSet()
        val paired = pairCreditsToDebits(sinceMs, bankIds, blockedSelf)
        return paired + markOwnAccountTransfers(sinceMs, bankIds, blockedSelf)
    }

    private suspend fun pairCreditsToDebits(
        sinceMs: Long,
        bankIds: Set<String>,
        blockedSelf: Set<String>,
    ): Int {
        val range = txDao.listInRange(sinceMs, DateRanges.OPEN_END_MS)
        val creditTxs = range.filter {
            it.isCredit() &&
                SpendCategory.isCredits(it.category) &&
                LearnedTxDecisionEntity.sourceKeyFor(it) !in blockedSelf &&
                it.userTagOrNull() == null
        }
        if (creditTxs.isEmpty()) {
            // Fallback: parse from inbox when credits are not yet stored (legacy scans).
            val messages = runCatchingCancellable { smsReader.readSince(sinceMs) }.getOrDefault(emptyList())
            val credits = messages.mapNotNull { sms ->
                CreditSmsParser.parseCredit(sms.body, sms.sender, sms.dateMs)
            }
            if (credits.isEmpty()) return 0
            return pairSelfTransfersFromParsed(range, credits, bankIds, creditTxs = emptyList(), blockedSelf)
        }
        val credits = creditTxs.map { tx ->
            ParsedCredit(
                amountPaise = tx.amountPaise,
                upiRef = CreditSmsParser.extractUpiRef(tx.bodySnippet),
                bankId = app.smspend.domain.IndianBanks.matchSender(tx.sender)?.id,
                merchantRaw = tx.merchantRaw,
                merchantKey = tx.merchantKey,
                bodySnippet = tx.bodySnippet,
                sender = tx.sender,
                occurredAt = tx.occurredAt,
            )
        }
        val paired = pairSelfTransfersFromParsed(range, credits, bankIds, creditTxs, blockedSelf)
        val named = markSelfNameCredits(creditTxs, blockedSelf)
        return paired + named
    }

    /**
     * Outbound transfer whose destination account (e.g. "a/c XXX755 credited") is one the user
     * owns — seen as the credited or debited account on My-bank SMS — becomes Self transfer even
     * when the receiving bank never sent a credit SMS. Learned NO, user tags, and retags win.
     */
    private suspend fun markOwnAccountTransfers(
        sinceMs: Long,
        bankIds: Set<String>,
        blockedSelf: Set<String>,
    ): Int {
        // Light projection (sender/snippet/type) instead of loading every full row.
        val ownAccounts = txDao.listAccountEvidence().mapNotNull { ev ->
            val bank = app.smspend.domain.IndianBanks.matchSender(ev.sender)?.id
            if (bank == null || bank !in bankIds) return@mapNotNull null
            if (ev.type.equals(TransactionEntity.TYPE_CREDIT, ignoreCase = true)) {
                SmsDebitParser.extractCreditedAccount(ev.bodySnippet)
            } else {
                SmsDebitParser.extractDebitedAccount(ev.bodySnippet)
            }
        }.toSet()
        if (ownAccounts.isEmpty()) return 0
        val correctedKeys = correctionDao.listMerchantKeys().toSet()
        var tagged = 0
        for (tx in txDao.listInRange(sinceMs, DateRanges.OPEN_END_MS)) {
            if (!tx.isDebit()) continue
            if (SpendCategory.isCustomKey(tx.category)) continue
            val cat = SpendCategory.fromStored(tx.category)
            if (cat != SpendCategory.TRANSFERS && cat != SpendCategory.OTHER) continue
            if (tx.userTagOrNull() != null) continue
            if (tx.merchantKey in correctedKeys) continue
            if (LearnedTxDecisionEntity.sourceKeyFor(tx) in blockedSelf) continue
            val dest = SmsDebitParser.extractDestinationAccount(tx.bodySnippet) ?: continue
            val source = SmsDebitParser.extractDebitedAccount(tx.bodySnippet)
            if (source != null && SmsDebitParser.sameAccountSuffix(source, dest)) continue
            if (ownAccounts.none { SmsDebitParser.sameAccountSuffix(it, dest) }) continue
            txDao.updateCategory(tx.id, SpendCategory.SELF_TRANSFER.name)
            tagged++
        }
        return tagged
    }

    /**
     * Credits whose payer matches the user's self-name aliases become Self transfer
     * even without a matching debit SMS (e.g. sent from an account we don't scan).
     * Only untouched CREDITS rows move; learned NO decisions and user tags win.
     */
    private suspend fun markSelfNameCredits(
        creditTxs: List<TransactionEntity>,
        blockedSelf: Set<String>,
    ): Int {
        val aliases = prefs.selfNameAliases.first()
        if (SelfTransferMatcher.parseAliases(aliases).isEmpty()) return 0
        var tagged = 0
        for (candidate in creditTxs) {
            // Re-read: pairing above may already have moved this row.
            val tx = txDao.getById(candidate.id) ?: continue
            if (!tx.isCredit()) continue
            if (!SpendCategory.isCredits(tx.category)) continue
            if (tx.userTagOrNull() != null) continue
            if (LearnedTxDecisionEntity.sourceKeyFor(tx) in blockedSelf) continue
            val self = SelfTransferMatcher.matches(tx.merchantRaw, aliases) ||
                SelfTransferMatcher.matches(tx.merchantKey, aliases)
            if (!self) continue
            txDao.updateCategory(tx.id, SpendCategory.SELF_TRANSFER.name)
            if (tx.statusTag.isBlank()) {
                txDao.updateStatusTag(tx.id, SmsDebitParser.SELF_RD_FROM_NOTE)
            }
            tagged++
        }
        return tagged
    }

    private suspend fun pairSelfTransfersFromParsed(
        range: List<TransactionEntity>,
        credits: List<ParsedCredit>,
        bankIds: Set<String>,
        creditTxs: List<TransactionEntity>,
        blockedSelf: Set<String>,
    ): Int {
        val debits = range
            .filter { it.isDebit() }
            .filter { !SpendCategory.isSelfTransfer(it.category) }
            .filter { LearnedTxDecisionEntity.sourceKeyFor(it) !in blockedSelf }
            .filter { it.userTagOrNull() == null }
            .filter {
                val cat = SpendCategory.fromStored(it.category)
                cat == SpendCategory.TRANSFERS || cat == SpendCategory.OTHER ||
                    SpendCategory.isCustomKey(it.category).not() && cat == SpendCategory.WITHDRAWALS
            }
        val correctedKeys = correctionDao.listMerchantKeys().toSet()
        val candidates = debits.filter {
            if (it.merchantKey in correctedKeys) return@filter false
            val cat = SpendCategory.fromStored(it.category)
            cat == SpendCategory.TRANSFERS || cat == SpendCategory.OTHER
        }
        val pairs = CreditSmsParser.selfTransferPairs(candidates, credits, bankIds)
        var paired = 0
        for (pair in pairs) {
            val tx = txDao.getById(pair.debitId) ?: continue
            if (SpendCategory.fromStored(tx.category) == SpendCategory.SAVINGS) continue
            if (LearnedTxDecisionEntity.sourceKeyFor(tx) in blockedSelf) continue
            txDao.updateCategory(pair.debitId, SpendCategory.SELF_TRANSFER.name)
            if (tx.statusTag.isBlank()) txDao.updateStatusTag(pair.debitId, pair.note)
            val creditTx = creditTxs.getOrNull(pair.creditIndex)
            creditTx?.let { credit ->
                if (LearnedTxDecisionEntity.sourceKeyFor(credit) in blockedSelf) return@let
                if (credit.userTagOrNull() != null) return@let
                // Only auto-move credits that the user has not retagged away from CREDITS.
                if (!SpendCategory.isCredits(credit.category)) return@let
                txDao.updateCategory(credit.id, SpendCategory.SELF_TRANSFER.name)
                if (credit.statusTag.isBlank()) txDao.updateStatusTag(credit.id, pair.note)
                pairDao.upsert(
                    SelfTransferPairEntity(
                        pairKey = SelfTransferPairEntity.pairKeyFor(
                            LearnedTxDecisionEntity.sourceKeyFor(tx),
                            LearnedTxDecisionEntity.sourceKeyFor(credit),
                        ),
                        debitSourceKey = LearnedTxDecisionEntity.sourceKeyFor(tx),
                        creditSourceKey = LearnedTxDecisionEntity.sourceKeyFor(credit),
                        decision = SelfTransferPairEntity.PAIRED,
                    ),
                )
            }
            paired++
        }
        return paired
    }

    /**
     * Deletes merchant confirmations shadowed by a bank debit (same amount ±6h),
     * collapses bank+bank rows that share amount + non-unknown merchantKey within ±6h
     * (keep earliest), drops Groww/Zerodha UMRN payment alerts when a matching ACH
     * ledger debit exists, and collapses opaque NEFT "Amt Deducted" legs when a named
     * "credited to payee" leg exists. Returns how many rows were removed.
     */
    suspend fun purgeCrossSourceDuplicates(): Int = scanMutex.withLock {
        db.withTransaction { purgeCrossSourceDuplicatesLocked(null) }
    }

    /** [windowStart] null = all debits; otherwise debits from that time on. */
    private suspend fun purgeCrossSourceDuplicatesLocked(windowStart: Long?): Int {
        val debits = txDao.listDebitsInRange(windowStart ?: Long.MIN_VALUE, DateRanges.OPEN_END_MS)
        val points = debits.filter { it.isDebit() }.map { tx ->
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
        val afterInvest = afterBank.filter { it.id !in gone }
        for (id in SmsDebitParser.opaqueNeftLegDuplicateIds(afterInvest)) {
            if (gone.add(id)) {
                txDao.deleteById(id)
                removed++
            }
        }
        return removed
    }

    /**
     * Re-apply high-confidence rules over sticky merchant memory: ATM→Withdrawals,
     * Transfer-To-RD→Savings, opaque NEFT→Bank transfer / Transfers, self-name→Self,
     * jewellery→Shopping. Also normalizes opaque Online Banking merchant labels.
     */
    private suspend fun repairHighConfidenceCategories(windowStart: Long?): Int {
        val aliases = prefs.selfNameAliases.first()
        val correctedKeys = correctionDao.listMerchantKeys().toSet()
        val notCardBill = learnedDao
            .listKeys(LearnedTxDecisionEntity.TYPE_CARD_BILL, LearnedTxDecisionEntity.NO)
            .toSet()
        val forceCardBill = learnedDao
            .listKeys(LearnedTxDecisionEntity.TYPE_CARD_BILL, LearnedTxDecisionEntity.YES)
            .toSet()
        val notSelfTransfer = learnedDao
            .listKeys(LearnedTxDecisionEntity.TYPE_SELF_TRANSFER, LearnedTxDecisionEntity.NO)
            .toSet()
        // Credits are skipped below, so only debits are loaded.
        val all = txDao.listDebitsInRange(windowStart ?: Long.MIN_VALUE, DateRanges.OPEN_END_MS)
        var fixed = 0
        val retaggedMerchants = mutableSetOf<String>()

        for (tx in all) {
            // Credits stay as user left them — only self-transfer pairing may move untouched CREDITS.
            if (tx.isCredit()) continue

            var merchantRaw = tx.merchantRaw
            var merchantKey = tx.merchantKey
            val body = tx.bodySnippet
            val sourceKey = LearnedTxDecisionEntity.sourceKeyFor(tx)

            val opaque = SmsDebitParser.opaqueBankTransferMerchant(body)
            if (opaque != null) {
                val opaqueKey = SmsDebitParser.normalizeMerchantKey(opaque)
                if (merchantKey != opaqueKey || merchantRaw != opaque) {
                    txDao.updateParsedFields(tx.id, tx.amountPaise, opaque, opaqueKey)
                    merchantRaw = opaque
                    merchantKey = opaqueKey
                    fixed++
                }
            }

            // The user's explicit retag is final — never let structural rules revert it
            // on every scan (e.g. an IMPS payment the user tagged as "Rent").
            if (tx.merchantKey in correctedKeys || merchantKey in correctedKeys) continue

            // Learned: user said this is not a card bill payment — never re-apply.
            if (sourceKey !in notCardBill &&
                (sourceKey in forceCardBill || SmsDebitParser.isCardBillPayment(body, merchantRaw))
            ) {
                // Credit-card bill payment from bank: keep visible but exclude from totals.
                val already = tx.isCardBillPaymentNote()
                if (!already && tx.statusTag.isBlank()) {
                    txDao.updateStatusTag(tx.id, SmsDebitParser.CARD_PAYMENT_NOTE)
                    if (normalizeCategoryKey(tx.category) != SpendCategory.BILLS.name) {
                        txDao.updateCategory(tx.id, SpendCategory.BILLS.name)
                    }
                    fixed++
                } else if (already && normalizeCategoryKey(tx.category) != SpendCategory.BILLS.name) {
                    txDao.updateCategory(tx.id, SpendCategory.BILLS.name)
                    fixed++
                }
                continue
            }

            // Auto-tag itemized card spends when cardLast4 is still blank.
            if (tx.cardLast4.isBlank()) {
                SmsDebitParser.extractCardLast4(body)?.let { last4 ->
                    txDao.updateCardLast4(tx.id, last4)
                    fixed++
                }
            }

            val resolved = SelfTransferMatcher.resolveWithRules(
                merchantKey = merchantKey,
                merchantRaw = merchantRaw,
                body = body,
                aliasesCsv = aliases,
            ) ?: continue
            if (!CategoryRules.beatsMemory(resolved, merchantKey, merchantRaw, body)) continue
            if (resolved == SpendCategory.SELF_TRANSFER && sourceKey in notSelfTransfer) continue

            val stored = normalizeCategoryKey(tx.category)
            if (stored == resolved.name) {
                remember(merchantKey, merchantRaw, resolved.name)
                continue
            }
            // Own-account pairing already proved this generic IMPS/NEFT is a self transfer.
            if (resolved == SpendCategory.TRANSFERS && stored == SpendCategory.SELF_TRANSFER.name) continue
            if (retaggedMerchants.add(merchantKey)) {
                retag(tx.id, resolved.name, applyToSimilar = true, logCorrection = false)
            } else {
                txDao.updateCategory(tx.id, resolved.name)
            }
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
    /**
     * Undo for an AI- or template-found row: drop it, never read that SMS again, cache a
     * NOT_TXN verdict so triage skips it, and forget any learned template that produced it.
     * Returns the number of templates removed, or null when the row is gone.
     */
    suspend fun markNotTransaction(transactionId: Long): Int? = scanMutex.withLock {
        val tx = txDao.getById(transactionId) ?: return null
        val senderKey = SmsDebitParser.senderKey(tx.sender)
        val body = runCatchingCancellable { smsReader.readByIds(listOf(tx.smsId))[tx.smsId]?.body }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: tx.bodySnippet
        txDao.deleteById(tx.id)
        ruleDao.insert(
            ParseRuleEntity(
                senderKey = senderKey,
                kind = ParseRuleEntity.KIND_IGNORE_SMS,
                pattern = tx.smsId.toString(),
                sampleSnippet = tx.bodySnippet,
            ),
        )
        verdictDao.upsert(
            AiSmsVerdictEntity(
                smsId = tx.smsId,
                senderKey = senderKey,
                kind = SmsTriageVerdict.KIND_NOT_TXN,
                model = "user",
                bodySnippet = body.take(AiSmsTriage.MAX_BODY_CHARS),
            ),
        )
        val templates = ruleDao.listAll().filter { rule ->
            (rule.kind == ParseRuleEntity.KIND_TEMPLATE_DEBIT || rule.kind == ParseRuleEntity.KIND_TEMPLATE_CREDIT) &&
                (
                    rule.sourceSmsId == tx.smsId ||
                        (rule.senderKey == senderKey && AiSmsTriage.applyTemplate(rule.pattern, body) != null)
                    )
        }
        templates.forEach { ruleDao.delete(it.id) }
        DiagLog.i(
            "ai",
            "not_transaction",
            mapOf("txId" to tx.id, "parseSource" to tx.parseSource, "templatesRemoved" to templates.size),
        )
        return templates.size
    }

    suspend fun reportWrongParse(transactionId: Long): WrongParseResult {
        val tx = txDao.getById(transactionId) ?: return WrongParseResult.NotFound
        val senderKey = SmsDebitParser.senderKey(tx.sender)
        val apiKey = prefs.geminiApiKey.first()
        val ai = if (apiKey.isBlank()) {
            null
        } else {
            runCatchingCancellable { gemini.reparse(apiKey, tx.bodySnippet, tx.sender) }.getOrNull()
        }

        if (ai != null && ai.isDebit && ai.amountPaise != null) {
            val merchantRaw = (ai.merchant ?: tx.merchantRaw).take(80)
            val merchantKey = SmsDebitParser.normalizeMerchantKey(merchantRaw)
            txDao.updateParsedFields(tx.id, ai.amountPaise, merchantRaw, merchantKey)
            txDao.updateDedupKey(
                tx.id,
                SmsDebitParser.directionalDedupKey(
                    tx.bodySnippet,
                    ai.amountPaise,
                    tx.occurredAt,
                    isCredit = false,
                ),
            )
            // The old category may belong to the mis-parsed merchant — let the next
            // batch pass recategorize under the corrected one.
            txDao.updateAiStatusForIds(listOf(tx.id), TransactionEntity.AI_PENDING)
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

    private data class ResolvedCategory(
        val key: String,
        val aiStatus: String,
        val source: String,
    )

    /**
     * Gemini-first resolution at scan time — no network here:
     * 1. High-confidence structural rules (ATM, RD/savings, self-name, opaque NEFT) are final.
     * 2. merchant_memory (learned from retags, prior Gemini results) is final.
     * 3. Everything else gets a provisional CategoryRules match (or Other) and is marked
     *    PENDING for the batched Gemini pass.
     */
    private suspend fun resolveCategoryKey(
        merchantKey: String,
        merchantRaw: String,
        body: String,
    ): ResolvedCategory {
        val aliases = prefs.selfNameAliases.first()
        val ruled = SelfTransferMatcher.resolveWithRules(merchantKey, merchantRaw, body, aliases)
        if (ruled != null &&
            CategoryRules.beatsMemory(ruled, merchantKey, merchantRaw, body)
        ) {
            remember(merchantKey, merchantRaw, ruled.name)
            return ResolvedCategory(ruled.name, TransactionEntity.AI_DONE, "rule_final")
        }
        memDao.get(merchantKey)?.let {
            return ResolvedCategory(it.category, TransactionEntity.AI_DONE, "memory")
        }
        return ResolvedCategory(
            key = ruled?.name ?: SpendCategory.OTHER.name,
            aiStatus = TransactionEntity.AI_PENDING,
            source = "provisional_pending",
        )
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

    /**
     * [logCorrection] must be true only for user-initiated retags: corrections become
     * few-shot examples for the batch Gemini prompt, so internal repairs and Gemini's
     * own results must never log themselves as "the user said so".
     */
    suspend fun retag(
        transactionId: Long,
        categoryKey: String,
        applyToSimilar: Boolean,
        logCorrection: Boolean = true,
    ) {
        val tx = txDao.getById(transactionId) ?: return
        val oldKey = normalizeCategoryKey(tx.category)
        if (logCorrection && oldKey != categoryKey) {
            correctionDao.insert(
                CategoryCorrectionEntity(
                    merchantKey = tx.merchantKey,
                    merchantRaw = tx.merchantRaw,
                    bodySnippet = tx.bodySnippet,
                    fromCategory = oldKey,
                    toCategory = categoryKey,
                ),
            )
            correctionDao.prune(MAX_CORRECTIONS)
        }
        // Learn self-transfer preference so pairing/repair does not undo the user.
        if (logCorrection) {
            val sourceKey = LearnedTxDecisionEntity.sourceKeyFor(tx)
            val wasSelf = SpendCategory.isSelfTransfer(oldKey)
            val nowSelf = SpendCategory.isSelfTransfer(categoryKey)
            when {
                wasSelf && !nowSelf ->
                    learnedDao.upsert(
                        LearnedTxDecisionEntity(
                            sourceKey = sourceKey,
                            decisionType = LearnedTxDecisionEntity.TYPE_SELF_TRANSFER,
                            decision = LearnedTxDecisionEntity.NO,
                        ),
                    )
                nowSelf ->
                    learnedDao.delete(sourceKey, LearnedTxDecisionEntity.TYPE_SELF_TRANSFER)
                tx.isCredit() && !SpendCategory.isCredits(categoryKey) && !nowSelf ->
                    // User moved a credit out of Credits (e.g. to a spend category) — don't re-pair.
                    learnedDao.upsert(
                        LearnedTxDecisionEntity(
                            sourceKey = sourceKey,
                            decisionType = LearnedTxDecisionEntity.TYPE_SELF_TRANSFER,
                            decision = LearnedTxDecisionEntity.NO,
                        ),
                    )
            }
        }
        if (shouldApplyToSimilar(tx, applyToSimilar)) {
            txDao.updateCategoryForMerchantAndType(
                merchantKey = tx.merchantKey,
                category = categoryKey,
                type = TransactionEntity.TYPE_DEBIT,
            )
            remember(tx.merchantKey, tx.merchantRaw, categoryKey)
        } else {
            txDao.updateCategory(transactionId, categoryKey)
        }
        ensureCustomCategoryIcon(categoryKey)
    }

    suspend fun setTransactionNote(id: Long, note: String) {
        val tx = txDao.getById(id) ?: return
        val trimmed = note.trim()
        // Clearing system status tags should teach the scanner not to re-apply them.
        if (trimmed.isEmpty() && tx.isPossibleDuplicateNote()) {
            markNotDuplicate(id)
            return
        }
        if (trimmed.isEmpty() && tx.isCardBillPaymentNote()) {
            clearCardBillPayment(id)
            return
        }
        if (trimmed.isEmpty() && (
                tx.statusTag.startsWith("Paired ·", ignoreCase = true) ||
                    tx.statusTag.equals(SmsDebitParser.SELF_RD_WENT_NOTE, ignoreCase = true) ||
                    tx.statusTag.equals(SmsDebitParser.SELF_RD_FROM_NOTE, ignoreCase = true)
                )
        ) {
            txDao.updateStatusTag(id, "")
            learnedDao.upsert(
                LearnedTxDecisionEntity(
                    sourceKey = LearnedTxDecisionEntity.sourceKeyFor(tx),
                    decisionType = LearnedTxDecisionEntity.TYPE_SELF_TRANSFER,
                    decision = LearnedTxDecisionEntity.NO,
                ),
            )
            return
        }
        // User writing over a card-bill status tag → learn CARD_BILL NO and clear status.
        if (tx.isCardBillPaymentNote() && trimmed.isNotEmpty() &&
            !trimmed.equals(SmsDebitParser.CARD_PAYMENT_NOTE, ignoreCase = true)
        ) {
            learnedDao.upsert(
                LearnedTxDecisionEntity(
                    sourceKey = LearnedTxDecisionEntity.sourceKeyFor(tx),
                    decisionType = LearnedTxDecisionEntity.TYPE_CARD_BILL,
                    decision = LearnedTxDecisionEntity.NO,
                ),
            )
            txDao.updateStatusTag(id, "")
        }
        // User writing over a duplicate status tag → learn NOT_DUPLICATE for nearby peers.
        if (tx.isPossibleDuplicateNote() && trimmed.isNotEmpty() &&
            !trimmed.equals(GeminiCategorizer.DUPLICATE_NOTE, ignoreCase = true)
        ) {
            for (peer in findNearbyDuplicatePeers(tx)) {
                decisionDao.upsert(
                    DuplicateDecisionEntity(
                        pairKey = DuplicateDecisionEntity.pairKeyFor(tx, peer),
                        decision = DuplicateDecisionEntity.NOT_DUPLICATE,
                    ),
                )
            }
            txDao.updateStatusTag(id, "")
        }
        txDao.updateNote(id, trimmed)
    }

    /** Recent individual tags: stored notes may hold several comma-separated tags. */
    suspend fun recentTags(limit: Int = 12): List<String> {
        val seen = LinkedHashMap<String, String>()
        for (note in txDao.recentNotes(limit * 3)) {
            if (TransactionEntity.isSystemStatusTag(note)) continue
            for (tag in note.split(',')) {
                val t = tag.trim()
                if (t.isEmpty() || TransactionEntity.isSystemStatusTag(t)) continue
                seen.putIfAbsent(TransactionEntity.normalizeUserTag(t), t)
                if (seen.size >= limit) return seen.values.toList()
            }
        }
        return seen.values.toList()
    }

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
        val item = TxForAiCategorize(
            id = tx.id,
            merchantRaw = tx.merchantRaw,
            amountPaise = tx.amountPaise,
            sender = tx.sender,
            bodySnippet = tx.bodySnippet,
        )
        val (mapped, _) = runCatchingCancellable {
            gemini.categorizeTransactions(
                apiKey = apiKey,
                items = listOf(item),
                customCategoryLabels = listCustomCategoryKeys().map { SpendCategory.displayLabel(it) },
                examples = buildAiExamples(),
                selfAliases = SelfTransferMatcher.parseAliases(prefs.selfNameAliases.first()),
            )
        }.getOrNull() ?: return null
        val storageKey = mapped[tx.id] ?: return null
        retag(transactionId, storageKey, applyToSimilar, logCorrection = false)
        return SpendCategory.displayLabel(storageKey)
    }

    /** Serializes the batch pass: worker + UI refresh must not double-spend quota. */
    private val aiPassMutex = Mutex()

    /**
     * AI fallback for SMS the built-in parsers and learned templates both reject. Candidates
     * (local filter, no cached verdict) go to Gemini in batches; DEBIT / CREDIT verdicts insert
     * through the same dedup path as rule rows (tagged [TransactionEntity.SOURCE_AI]), every
     * verdict is cached, and validated per-sender templates are saved so the next SMS in that
     * format parses locally.
     */
    /**
     * [incremental] = true (the per-SMS worker / quiet catch-up) only looks at SMS that
     * arrived since the last completed pass, so a new bank SMS doesn't re-filter two
     * months of inbox. A full pass (pull-to-refresh, period sync) still covers the
     * whole Home window and retries anything an earlier pass couldn't send.
     */
    suspend fun runAiTriagePass(incremental: Boolean = false): AiTriageResult = triageMutex.withLock {
        val apiKey = prefs.geminiApiKey.first()
        if (apiKey.isBlank() || !prefs.aiTriageEnabled.first()) {
            return AiTriageResult(0, 0, 0, 0)
        }
        val homeStart = DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1)
        val watermark = prefs.aiTriageWatermarkMs.first()
        val sinceMs = if (incremental && watermark > 0L) {
            maxOf(homeStart, watermark - TRIAGE_OVERLAP_MS)
        } else {
            homeStart
        }
        val messages = runCatchingCancellable { smsReader.readSince(sinceMs) }.getOrDefault(emptyList())
        if (messages.isEmpty()) return AiTriageResult(0, 0, 0, 0)
        val newestRead = messages.maxOf { it.dateMs }

        val storedSmsIds = txDao.listSmsIdsSince(sinceMs).toHashSet()
        val judged = verdictDao.listSmsIds().toHashSet()
        val rulesBySender = ruleDao.listAll().groupBy { it.senderKey }
        val candidates = messages.asSequence()
            .filter { it.id !in storedSmsIds && it.id !in judged }
            .filter { sms ->
                val senderRules = rulesBySender[SmsDebitParser.senderKey(sms.sender)].orEmpty()
                !isIgnoredByRules(senderRules, sms.id, sms.body) &&
                    SmsDebitParser.parse(sms.body, sms.sender) == null &&
                    SmsDebitParser.parseAtmReversal(sms.body) == null &&
                    CreditSmsParser.parseCredit(sms.body, sms.sender, sms.dateMs) == null &&
                    templateDebit(senderRules, sms) == null &&
                    templateCredit(senderRules, sms) == null &&
                    AiSmsTriage.isCandidate(sms.body, sms.sender)
            }
            .sortedByDescending { it.dateMs }
            .toList()
        if (candidates.isEmpty()) {
            DiagLog.i("ai", "triage_skip", mapOf("reason" to "no_candidates", "incremental" to incremental))
            prefs.setAiTriageWatermarkMs(maxOf(watermark, newestRead))
            return AiTriageResult(0, 0, 0, 0)
        }

        val toSend = candidates.take(AiSmsTriage.BATCH_SIZE * AiSmsTriage.MAX_CALLS_PER_PASS)
        DiagLog.i("ai", "triage_start", mapOf("candidates" to candidates.size, "sending" to toSend.size))
        val smsById = toSend.associateBy { it.id }
        val verdicts = mutableListOf<Pair<SmsTriageVerdict, String>>()
        var firstError: String? = null
        for (chunk in toSend.chunked(AiSmsTriage.BATCH_SIZE)) {
            val items = chunk.map { SmsForTriage(id = it.id, sender = it.sender, body = it.body) }
            val (batch, model, error) = gemini.triageSms(apiKey, items)
            if (error != null) {
                if (firstError == null) firstError = "Gemini failed (${error.code}): ${error.message}"
                DiagLog.e("ai", "triage_batch_fail", mapOf("code" to error.code, "message" to error.message))
                continue
            }
            batch.forEach { verdicts += it to model.orEmpty() }
        }

        var inserted = 0
        var learned = 0
        scanMutex.withLock {
            db.withTransaction {
            val state = newInsertState(sinceMs)
            val myBankIds = app.smspend.domain.IndianBanks.parseIdsCsv(prefs.myBankIds.first())
            val existingTemplates = ruleDao.listAll()
                .filter { it.kind == ParseRuleEntity.KIND_TEMPLATE_DEBIT || it.kind == ParseRuleEntity.KIND_TEMPLATE_CREDIT }
                .mapTo(HashSet()) { Triple(it.senderKey, it.kind, it.pattern) }
            val batchNotTxnBodies = verdicts
                .filter { it.first.kind == SmsTriageVerdict.KIND_NOT_TXN }
                .mapNotNull { smsById[it.first.id] }
                .groupBy({ SmsDebitParser.senderKey(it.sender) }, { it.body })

            for ((verdict, model) in verdicts) {
                val sms = smsById[verdict.id] ?: continue
                val senderKey = SmsDebitParser.senderKey(sms.sender)
                val amount = verdict.amountPaise
                val isTxn = verdict.kind != SmsTriageVerdict.KIND_NOT_TXN &&
                    amount != null && amount > 0 &&
                    AiSmsTriage.amountAppearsIn(sms.body, amount)
                val kind = if (isTxn) verdict.kind else SmsTriageVerdict.KIND_NOT_TXN
                verdictDao.upsert(
                    AiSmsVerdictEntity(
                        smsId = sms.id,
                        senderKey = senderKey,
                        kind = kind,
                        amountPaise = if (isTxn) amount ?: 0L else 0L,
                        model = model,
                        bodySnippet = sms.body.take(AiSmsTriage.MAX_BODY_CHARS),
                    ),
                )
                if (!isTxn || amount == null) continue

                val ok = if (kind == SmsTriageVerdict.KIND_DEBIT) {
                    insertParsedDebit(
                        state,
                        sms,
                        debitFrom(sms, amount, verdict.payee),
                        amount,
                        TransactionEntity.SOURCE_AI,
                    ) == DebitInsertOutcome.INSERTED
                } else {
                    insertParsedCredit(
                        state,
                        sms,
                        creditFrom(sms, amount, verdict.payee),
                        myBankIds,
                        TransactionEntity.SOURCE_AI,
                    )
                }
                if (ok) inserted++

                val template = verdict.templateRegex?.trim().orEmpty()
                if (template.isEmpty()) continue
                val ruleKind = if (kind == SmsTriageVerdict.KIND_DEBIT) {
                    ParseRuleEntity.KIND_TEMPLATE_DEBIT
                } else {
                    ParseRuleEntity.KIND_TEMPLATE_CREDIT
                }
                if (Triple(senderKey, ruleKind, template) in existingTemplates) continue
                val notTxn = verdictDao.listBodies(senderKey, SmsTriageVerdict.KIND_NOT_TXN) +
                    batchNotTxnBodies[senderKey].orEmpty()
                if (!AiSmsTriage.validateTemplate(template, sms.body, amount, notTxn)) {
                    DiagLog.i("ai", "template_reject", mapOf("sender" to sms.sender, "pattern" to template.take(120)))
                    continue
                }
                ruleDao.insert(
                    ParseRuleEntity(
                        senderKey = senderKey,
                        kind = ruleKind,
                        pattern = template,
                        sampleSnippet = sms.body.take(180),
                        sourceSmsId = sms.id,
                    ),
                )
                existingTemplates += Triple(senderKey, ruleKind, template)
                learned++
                DiagLog.i("ai", "template_learned", mapOf("sender" to sms.sender, "kind" to ruleKind))
            }
            if (inserted > 0) {
                purgeCrossSourceDuplicatesLocked(sinceMs - MAINTENANCE_LOOKBACK_MS)
                applyCreditPairSelfTransfersLocked(sinceMs)
            }
            }
        }
        // Only advance past SMS that were all sent and answered; otherwise the next
        // pass picks the leftovers up again (verdicts are cached, so nothing repeats).
        if (firstError == null && toSend.size == candidates.size) {
            prefs.setAiTriageWatermarkMs(maxOf(watermark, newestRead))
        }
        val result = AiTriageResult(candidates.size, toSend.size, inserted, learned, firstError)
        DiagLog.i(
            "ai",
            "triage_end",
            mapOf(
                "candidates" to result.candidates,
                "sent" to result.sent,
                "inserted" to result.inserted,
                "templatesLearned" to result.templatesLearned,
                "error" to result.error,
            ),
        )
        return result
    }

    /**
     * The batched Gemini pass: resolves PENDING transactions via merchant_memory first,
     * then sends the rest (one representative per merchant) to Gemini with the user's
     * taxonomy + correction few-shots. Results auto-apply and are remembered. Failed
     * batches stay PENDING and retry on the next scan; without an API key everything
     * is marked SKIPPED (rules-only mode).
     */
    suspend fun runAiCategorizationPass(useMemory: Boolean = true): AiPassResult = aiPassMutex.withLock {
        val pending = txDao.listByAiStatus(TransactionEntity.AI_PENDING)
            .filter { it.isDebit() }
        if (pending.isEmpty()) {
            DiagLog.i("ai", "ai_pass_skip", mapOf("reason" to "no_pending"))
            return AiPassResult(0, 0, 0, 0)
        }
        val apiKey = prefs.geminiApiKey.first()
        if (apiKey.isBlank()) {
            updateAiStatusIfPendingChunked(pending.map { it.id }, TransactionEntity.AI_SKIPPED)
            val result = AiPassResult(pending.size, 0, 0, pending.size, "No Gemini API key")
            DiagLog.w(
                "ai",
                "ai_pass_end",
                mapOf(
                    "pending" to result.pending,
                    "applied" to result.applied,
                    "fromMemory" to result.fromMemory,
                    "failed" to result.failed,
                    "error" to result.error,
                ),
            )
            return result
        }

        DiagLog.i(
            "ai",
            "ai_pass_start",
            mapOf(
                "pending" to pending.size,
                "useMemory" to useMemory,
            ),
        )

        var fromMemory = 0
        val needAi = mutableListOf<TransactionEntity>()
        for (tx in pending) {
            val mem = if (useMemory) memDao.get(tx.merchantKey) else null
            if (mem != null) {
                fromMemory += txDao.updateCategoryIfUnchanged(
                    tx.id,
                    mem.category,
                    TransactionEntity.AI_PENDING,
                    tx.category,
                )
            } else {
                needAi += tx
            }
        }
        if (needAi.isEmpty()) {
            val result = AiPassResult(pending.size, 0, fromMemory, 0)
            DiagLog.i(
                "ai",
                "ai_pass_end",
                mapOf(
                    "pending" to result.pending,
                    "applied" to result.applied,
                    "fromMemory" to result.fromMemory,
                    "failed" to result.failed,
                ),
            )
            return result
        }

        val groups = needAi.groupBy { it.merchantKey }
        val representatives = groups.values.map { txs -> txs.maxBy { it.occurredAt } }
        val customLabels = listCustomCategoryKeys().map { SpendCategory.displayLabel(it) }
        val examples = buildAiExamples()
        val aliases = SelfTransferMatcher.parseAliases(prefs.selfNameAliases.first())

        var applied = 0
        var failed = 0
        var firstError: String? = null
        for (chunk in representatives.chunked(GeminiCategorizer.AI_BATCH_SIZE)) {
            DiagLog.i(
                "ai",
                "ai_batch",
                mapOf(
                    "reps" to chunk.size,
                    "txEstimate" to chunk.sumOf { groups[it.merchantKey]?.size ?: 1 },
                ),
            )
            val items = chunk.map { tx ->
                TxForAiCategorize(
                    id = tx.id,
                    merchantRaw = tx.merchantRaw,
                    amountPaise = tx.amountPaise,
                    sender = tx.sender,
                    bodySnippet = tx.bodySnippet,
                )
            }
            val (mapped, error) = gemini.categorizeTransactions(
                apiKey = apiKey,
                items = items,
                customCategoryLabels = customLabels,
                examples = examples,
                selfAliases = aliases,
            )
            if (error != null) {
                failed += chunk.sumOf { groups[it.merchantKey]?.size ?: 1 }
                if (firstError == null) {
                    firstError = "Gemini failed (${error.code}): ${error.message}"
                }
                DiagLog.e(
                    "ai",
                    "ai_batch_fail",
                    mapOf(
                        "code" to error.code,
                        "message" to error.message,
                        "reps" to chunk.size,
                    ),
                )
                continue
            }
            for (rep in chunk) {
                val group = groups[rep.merchantKey].orEmpty()
                val storageKey = mapped[rep.id]
                if (storageKey != null) {
                    // Guarded: rows retagged by the user while this call was in flight
                    // are DONE already and stay untouched.
                    var groupApplied = 0
                    for (tx in group) {
                        groupApplied += txDao.updateCategoryIfUnchanged(
                            tx.id,
                            storageKey,
                            TransactionEntity.AI_PENDING,
                            tx.category,
                        )
                    }
                    if (groupApplied > 0) {
                        remember(rep.merchantKey, rep.merchantRaw, storageKey)
                        ensureCustomCategoryIcon(storageKey)
                        applied += groupApplied
                        DiagLog.i(
                            "ai",
                            "ai_applied",
                            mapOf(
                                "merchantKey" to rep.merchantKey,
                                "category" to storageKey,
                                "count" to groupApplied,
                            ),
                        )
                    }
                } else {
                    // Gemini answered Other (or omitted the id): keep the provisional
                    // rules category and stop retrying this row.
                    updateAiStatusIfPendingChunked(group.map { it.id }, TransactionEntity.AI_DONE)
                    DiagLog.i(
                        "ai",
                        "ai_other_or_omit",
                        mapOf(
                            "merchantKey" to rep.merchantKey,
                            "count" to group.size,
                            "snippet" to DiagLog.truncateSnippet(rep.bodySnippet),
                        ),
                    )
                }
            }
        }
        val result = AiPassResult(pending.size, applied, fromMemory, failed, firstError)
        if (firstError != null) {
            DiagLog.w(
                "ai",
                "ai_pass_end",
                mapOf(
                    "pending" to result.pending,
                    "applied" to result.applied,
                    "fromMemory" to result.fromMemory,
                    "failed" to result.failed,
                    "error" to firstError,
                ),
            )
        } else {
            DiagLog.i(
                "ai",
                "ai_pass_end",
                mapOf(
                    "pending" to result.pending,
                    "applied" to result.applied,
                    "fromMemory" to result.fromMemory,
                    "failed" to result.failed,
                ),
            )
        }
        return result
    }

    /**
     * Rows were marked SKIPPED while no API key was configured. Once a key exists,
     * flip them back to PENDING and run the pass so key-less history gets categorized.
     */
    suspend fun retrySkippedWithAi(): AiPassResult {
        val skipped = txDao.listByAiStatus(TransactionEntity.AI_SKIPPED)
            .filter { it.isDebit() }
        if (skipped.isNotEmpty()) {
            updateAiStatusChunked(skipped.map { it.id }, TransactionEntity.AI_PENDING)
        }
        return runAiCategorizationPass()
    }

    /**
     * One-tap re-baseline (Settings): marks this month's transactions PENDING and reruns
     * the Gemini pass with memory bypassed, so the improved prompt + latest corrections
     * get a fresh say. Protected from re-marking: merchants the user explicitly corrected,
     * high-confidence structural categories, and rows excluded from totals (ATM failed /
     * flagged duplicates).
     */
    suspend fun reBaselineWithAi(): AiPassResult {
        val monthStart = DateRanges.monthWindow(0).startMs
        val aliases = prefs.selfNameAliases.first()
        val correctedKeys = correctionDao.listMerchantKeys().toSet()
        memDao.deleteByCategory(SpendCategory.OTHER.name)
        val toPend = txDao.listInRange(monthStart, DateRanges.endExclusiveNow())
            .filter { it.isDebit() }
            .filter { tx ->
                if (tx.merchantKey in correctedKeys) return@filter false
                if (!tx.countsTowardTotals()) return@filter false
                val ruled = SelfTransferMatcher.resolveWithRules(
                    merchantKey = tx.merchantKey,
                    merchantRaw = tx.merchantRaw,
                    body = tx.bodySnippet,
                    aliasesCsv = aliases,
                )
                ruled == null || !CategoryRules.beatsMemory(ruled, tx.merchantKey, tx.merchantRaw, tx.bodySnippet)
            }
        updateAiStatusChunked(toPend.map { it.id }, TransactionEntity.AI_PENDING)
        return runAiCategorizationPass(useMemory = false)
    }

    /**
     * Few-shot examples for the batch prompt, strongest signal first: the user's recent
     * retag corrections, then learned merchant memory, then this month's already-decided
     * transactions (for consistency across batches). One example per merchant.
     */
    private suspend fun buildAiExamples(): List<AiCategoryExample> {
        val out = mutableListOf<AiCategoryExample>()
        val seen = mutableSetOf<String>()
        for (c in correctionDao.recent(EXAMPLE_CORRECTIONS)) {
            if (!seen.add(c.merchantKey)) continue
            out += AiCategoryExample(
                merchant = c.merchantRaw,
                categoryLabel = SpendCategory.displayLabel(c.toCategory),
                snippet = c.bodySnippet,
                correctedFromLabel = SpendCategory.displayLabel(c.fromCategory),
            )
        }
        for (m in memDao.listRecent(EXAMPLE_TOTAL)) {
            if (out.size >= EXAMPLE_TOTAL) break
            if (m.category == SpendCategory.OTHER.name) continue
            if (!seen.add(m.merchantKey)) continue
            out += AiCategoryExample(
                merchant = m.displayName,
                categoryLabel = SpendCategory.displayLabel(m.category),
            )
        }
        val monthStart = DateRanges.monthWindow(0).startMs
        for (tx in txDao.listInRange(monthStart, DateRanges.endExclusiveNow())) {
            if (out.size >= EXAMPLE_TOTAL) break
            if (!tx.isDebit()) continue
            if (tx.aiStatus == TransactionEntity.AI_PENDING) continue
            val key = normalizeCategoryKey(tx.category)
            if (key == SpendCategory.OTHER.name) continue
            if (!seen.add(tx.merchantKey)) continue
            out += AiCategoryExample(
                merchant = tx.merchantRaw,
                categoryLabel = SpendCategory.displayLabel(key),
            )
        }
        return out
    }

    private suspend fun updateAiStatusChunked(ids: List<Long>, status: String) {
        for (chunk in ids.chunked(ID_CHUNK)) {
            txDao.updateAiStatusForIds(chunk, status)
        }
    }

    /** Guarded variant used inside the pass: never moves rows already finalized. */
    private suspend fun updateAiStatusIfPendingChunked(ids: List<Long>, status: String) {
        for (chunk in ids.chunked(ID_CHUNK)) {
            txDao.updateAiStatusForIdsIfPending(chunk, status)
        }
    }

    /**
     * Ask Gemini which same-amount pairs within 24h are likely the same payment.
     * Flags the later row with note "Possible duplicate" when its note is empty.
     * Never deletes. Honors learned NOT_DUPLICATE / DUPLICATE decisions.
     */
    suspend fun flagPossibleDuplicatesWithGemini(): DuplicateFlagResult {
        applyLearnedDuplicateFlags()
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
                val earlier = txDao.getById(pair.earlierId) ?: continue
                val decisionKey = DuplicateDecisionEntity.pairKeyFor(earlier, later)
                if (decisionDao.get(decisionKey)?.decision == DuplicateDecisionEntity.NOT_DUPLICATE) {
                    continue
                }
                if (later.statusTag.isNotBlank() && !later.isPossibleDuplicateNote()) continue
                txDao.updateStatusTag(later.id, GeminiCategorizer.DUPLICATE_NOTE)
                flagged++
            }
        }
        return DuplicateFlagResult(
            flagged = flagged,
            candidates = pairs.size,
            error = firstError,
        )
    }

    /** Auto-flag pairs the user previously confirmed as duplicates. */
    private suspend fun applyLearnedDuplicateFlags(): Int {
        val duplicateKeys = decisionDao
            .listPairKeysByDecision(DuplicateDecisionEntity.DUPLICATE)
            .toSet()
        if (duplicateKeys.isEmpty()) return 0
        var flagged = 0
        for (pairKey in duplicateKeys) {
            val parts = pairKey.split("|")
            if (parts.size != 2) continue
            // Pair keys are immutable source keys ("sms:<id>"), so look rows up by those.
            val a = findBySourceKey(parts[0]) ?: continue
            val b = findBySourceKey(parts[1]) ?: continue
            val later = if (a.occurredAt <= b.occurredAt) b else a
            if (later.statusTag.isBlank() || later.isPossibleDuplicateNote()) {
                txDao.updateStatusTag(later.id, GeminiCategorizer.DUPLICATE_NOTE)
                flagged++
            }
        }
        return flagged
    }

    private suspend fun findBySourceKey(key: String): TransactionEntity? {
        txDao.getBySourceKey(key)?.let { return it }
        val smsId = key.removePrefix("sms:").takeIf { it != key }?.toLongOrNull() ?: return null
        return txDao.getBySmsId(smsId)
    }

    /**
     * Same amount within 24h, distinct rows not already flagged.
     * Hydrates full SMS bodies from the inbox when available so the review sheet
     * is not limited to the 180-char stored snippet. Skips learned NOT_DUPLICATE pairs.
     */
    suspend fun findDuplicatePairCandidates(
        windowMs: Long = 24L * 60 * 60 * 1000,
    ): List<DuplicatePairCandidate> {
        applyLearnedDuplicateFlags()
        val excluded = decisionDao
            .listPairKeysByDecision(DuplicateDecisionEntity.NOT_DUPLICATE)
            .toSet()
        val all = txDao.listDebitsInRange(Long.MIN_VALUE, DateRanges.OPEN_END_MS).sortedBy { it.occurredAt }
        val pairs = buildDuplicatePairCandidates(all, windowMs, excluded)
        if (pairs.isEmpty()) return pairs
        val byId = all.associateBy { it.id }
        val smsIds = pairs.flatMap { pair ->
            listOfNotNull(byId[pair.earlierId]?.smsId, byId[pair.laterId]?.smsId)
        }.distinct()
        val fullById = runCatchingCancellable { smsReader.readByIds(smsIds) }.getOrDefault(emptyMap())
        return pairs.map { pair ->
            val earlierTx = byId[pair.earlierId]
            val laterTx = byId[pair.laterId]
            val earlierBody = earlierTx?.let { fullById[it.smsId]?.body }?.takeIf { it.isNotBlank() }
                ?: pair.earlierSnippet
            val laterBody = laterTx?.let { fullById[it.smsId]?.body }?.takeIf { it.isNotBlank() }
                ?: pair.laterSnippet
            pair.copy(earlierSnippet = earlierBody, laterSnippet = laterBody)
        }
    }

    suspend fun clearMerchantMemory() {
        memDao.clearAll()
    }

    suspend fun clearOtherMemory() {
        memDao.deleteByCategory(SpendCategory.OTHER.name)
    }
}
