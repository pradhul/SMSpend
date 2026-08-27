package app.smspend.domain

import app.smspend.data.TransactionEntity

/**
 * Single accounting engine for period totals, category breakdowns, and credit residual.
 * Home, Trends, widgets, and insights must all use this — never raw SUM(amount).
 */
object PeriodAccounting {
    data class NetResult(
        val netSpendPaise: Long,
        /** Credits still visible under Credits after FIFO tag netting. */
        val residualCredits: List<TransactionEntity>,
        /** Debits that remain after status mute + self-transfer one-leg + tag netting. */
        val countableDebits: List<TransactionEntity>,
        /** Amount netted off credits (and matching tagged debits) in this period. */
        val nettedPaise: Long,
    )

    /**
     * Apply status mute, self-transfer one-leg counting, then FIFO same-tag credit↔debit netting.
     */
    fun netPeriod(txs: List<TransactionEntity>): NetResult {
        val unmuted = txs.filter { it.countsTowardTotals() }
        val debits = unmuted.filter { it.isDebit() }
        val credits = unmuted.filter { it.isCredit() }

        val countableDebits = debits.filterNot { isPairedSelfTransferLeg(it) }
        val unpairedCredits = credits.filterNot { isPairedSelfTransferLeg(it) }

        val (afterNetDebits, residualCredits, netted) = fifoTagNet(countableDebits, unpairedCredits)
        return NetResult(
            netSpendPaise = afterNetDebits.sumOf { it.amountPaise },
            residualCredits = residualCredits,
            countableDebits = afterNetDebits,
            nettedPaise = netted,
        )
    }

    fun categoryTotals(txs: List<TransactionEntity>): Map<String, Long> {
        val net = netPeriod(txs)
        // Manually categorized credits must not inflate spend categories.
        return net.countableDebits
            .groupBy { it.category }
            .mapValues { (_, rows) -> rows.sumOf { it.amountPaise } }
    }

    fun residualCreditTotal(txs: List<TransactionEntity>): Long =
        netPeriod(txs).residualCredits.sumOf { it.amountPaise }

    fun isPairedSelfTransferLeg(tx: TransactionEntity): Boolean {
        val s = tx.statusTag.trim()
        if (s.startsWith("Paired ·", ignoreCase = true)) return true
        if (s.equals("Went to RD", ignoreCase = true)) return true
        if (s.equals("From own account", ignoreCase = true)) return true
        return false
    }

    /**
     * FIFO-net same user-tag credit against same-tag debit. Rows can carry multiple
     * tags; a credit nets against a debit when they share at least one tag.
     * Partial nets leave a residual credit with the remaining amountPaise.
     * Untagged credits always remain residual; untagged debits always count in full.
     */
    private fun fifoTagNet(
        debits: List<TransactionEntity>,
        credits: List<TransactionEntity>,
    ): Triple<List<TransactionEntity>, List<TransactionEntity>, Long> {
        data class Bucket(var remaining: Long, val original: TransactionEntity)

        val debitRemaining = LinkedHashMap<Long, Bucket>()
        for (tx in debits) {
            debitRemaining[tx.id] = Bucket(tx.amountPaise, tx)
        }

        fun normalizedTags(tx: TransactionEntity): Set<String> =
            tx.userTags().map { TransactionEntity.normalizeUserTag(it) }.toSet()

        val residual = mutableListOf<TransactionEntity>()
        var netted = 0L

        for (credit in credits) {
            val creditTags = normalizedTags(credit)
            if (creditTags.isEmpty()) {
                residual.add(credit)
                continue
            }
            var left = credit.amountPaise
            // FIFO among shared-tag debits ordered by occurredAt ascending (oldest first).
            val peers = debits
                .filter { normalizedTags(it).any { t -> t in creditTags } }
                .sortedBy { it.occurredAt }
            for (peer in peers) {
                if (left <= 0L) break
                val bucket = debitRemaining[peer.id] ?: continue
                if (bucket.remaining <= 0L) continue
                val take = minOf(left, bucket.remaining)
                bucket.remaining -= take
                left -= take
                netted += take
            }
            if (left > 0L) {
                residual.add(if (left == credit.amountPaise) credit else credit.copy(amountPaise = left))
            }
        }

        val afterDebits = debits.mapNotNull { tx ->
            val bucket = debitRemaining[tx.id] ?: return@mapNotNull tx
            when {
                bucket.remaining <= 0L -> null
                bucket.remaining == tx.amountPaise -> tx
                else -> tx.copy(amountPaise = bucket.remaining)
            }
        }
        return Triple(afterDebits, residual, netted)
    }
}
