package app.smspend.data

import app.smspend.domain.CategoryBucket
import app.smspend.domain.PeriodAccounting
import app.smspend.domain.SpendCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/**
 * Pure (no DB, no Android) rules used by [SpendRepository]: duplicate pairing, tag
 * netting, card-bill balance, apply-to-similar. Split out of SpendRepository so they
 * can be read and unit-tested on their own.
 */
object SpendRules {
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
