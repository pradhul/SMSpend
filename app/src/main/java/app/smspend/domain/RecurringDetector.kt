package app.smspend.domain

import app.smspend.data.TransactionEntity

data class RecurringMerchant(
    val merchantKey: String,
    val displayName: String,
    val medianPaise: Long,
    val cadenceDays: Int,
    val lastOccurredAt: Long,
    val nextExpectedAt: Long,
)

object RecurringDetector {
    private const val MIN_HITS = 3
    private val MONTHLY = 25..35
    private val WEEKLY = 6..8

    fun detect(transactions: List<TransactionEntity>, nowMs: Long = System.currentTimeMillis()): List<RecurringMerchant> {
        return transactions
            .groupBy { it.merchantKey }
            .mapNotNull { (key, txs) ->
                val sorted = txs.sortedBy { it.occurredAt }
                if (sorted.size < MIN_HITS) return@mapNotNull null
                val gapsDays = sorted.zipWithNext { a, b ->
                    ((b.occurredAt - a.occurredAt) / (24 * 60 * 60 * 1000L)).toInt()
                }.filter { it > 0 }
                if (gapsDays.size < MIN_HITS - 1) return@mapNotNull null
                val medianGap = median(gapsDays) ?: return@mapNotNull null
                val cadence = when {
                    medianGap in MONTHLY -> medianGap
                    medianGap in WEEKLY -> medianGap
                    else -> return@mapNotNull null
                }
                val amounts = sorted.map { it.amountPaise }
                val medianPaise = medianLong(amounts) ?: return@mapNotNull null
                val last = sorted.last()
                RecurringMerchant(
                    merchantKey = key,
                    displayName = last.merchantRaw,
                    medianPaise = medianPaise,
                    cadenceDays = cadence,
                    lastOccurredAt = last.occurredAt,
                    nextExpectedAt = last.occurredAt + cadence * 24L * 60 * 60 * 1000,
                )
            }
            .filter { it.nextExpectedAt >= nowMs - 2L * 24 * 60 * 60 * 1000 } // still relevant
            .sortedBy { it.nextExpectedAt }
    }

    private fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2 else s[mid]
    }

    private fun medianLong(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2 else s[mid]
    }

    fun weekOverWeekPct(currentSpendPaise: Long, priorSpendPaise: Long): Int? {
        if (priorSpendPaise <= 0L) return null
        val delta = currentSpendPaise - priorSpendPaise
        return ((delta * 100.0) / priorSpendPaise).toInt()
    }
}
