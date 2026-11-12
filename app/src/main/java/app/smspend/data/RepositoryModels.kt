package app.smspend.data

import app.smspend.util.paiseToPlainRupees
import app.smspend.domain.CategoryBucket
import app.smspend.util.MonthWindow
import kotlinx.coroutines.flow.map

// Result / snapshot types returned by SpendRepository (split out of SpendRepository.kt).

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

/** Indian grouping like the rest of the app (was western "%,.0f": "1,234,567"). */
internal fun formatPaiseShort(paise: Long): String = paise.paiseToPlainRupees()
