package app.smspend.data

import app.smspend.domain.IndianBanks
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs

data class ParsedCredit(
    val amountPaise: Long,
    val upiRef: String?,
    val bankId: String?,
    val bodySnippet: String,
    val sender: String,
    val occurredAt: Long,
)

/**
 * Parse inbound credit SMS for multi-bank self-transfer pairing.
 * Credits are never stored as spend; they only confirm a debit is a self move.
 */
object CreditSmsParser {
    private val amountPattern = Pattern.compile(
        """(?i)(?:rs\.?|inr|₹)\s*:?\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)(?![0-9])""",
    )
    private val upiRefPattern = Pattern.compile(
        """(?i)(?:upi\s*ref(?:\s*no)?|ref)\s*[:#]?\s*(\d{9,})""",
    )
    private val creditHints = listOf(
        "credited", "received rs", "received inr", "has been credited",
        "account is credited", "credited with",
    )
    private val debitHints = listOf(
        "debited", "deducted", "sent rs", "withdrawn", "amt deducted",
    )

    fun extractUpiRef(body: String): String? {
        val m = upiRefPattern.matcher(body)
        return if (m.find()) m.group(1) else null
    }

    fun parseCredit(body: String, sender: String, occurredAt: Long): ParsedCredit? {
        val lower = body.lowercase(Locale.US)
        if (creditHints.none { lower.contains(it) }) return null
        // Prefer not to treat clear debit SMS as credits.
        if (debitHints.any { lower.contains(it) } && !lower.contains("credited") && !lower.contains("received")) {
            return null
        }
        val amount = extractAmountPaise(body) ?: return null
        val bank = IndianBanks.matchSender(sender)
        return ParsedCredit(
            amountPaise = amount,
            upiRef = extractUpiRef(body),
            bankId = bank?.id,
            bodySnippet = body.take(180),
            sender = sender,
            occurredAt = occurredAt,
        )
    }

    private fun extractAmountPaise(text: String): Long? {
        val m = amountPattern.matcher(text)
        if (!m.find()) return null
        val raw = m.group(1)?.replace(",", "") ?: return null
        val rupees = raw.toDoubleOrNull() ?: return null
        return Math.round(rupees * 100.0).takeIf { it > 0 }
    }

    /**
     * Mark debit transaction IDs that pair with a credit on another declared bank.
     * Tier A: shared UPI ref within [refWindowMs].
     * Tier B: same amount within [amountWindowMs] when credit has no ref (Federal-style).
     */
    fun selfTransferDebitIds(
        debits: List<TransactionEntity>,
        credits: List<ParsedCredit>,
        declaredBankIds: Set<String>,
        refWindowMs: Long = SmsDebitParser.SELF_TRANSFER_PAIR_WINDOW_MS,
        amountWindowMs: Long = SmsDebitParser.SELF_TRANSFER_FEDERAL_WINDOW_MS,
    ): List<Pair<Long, String>> {
        if (declaredBankIds.isEmpty() || credits.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<Long, String>>()
        val usedCredits = mutableSetOf<Int>()
        for (debit in debits.sortedBy { it.occurredAt }) {
            val debitBank = IndianBanks.matchSender(debit.sender)?.id
            if (debitBank != null && debitBank !in declaredBankIds) continue
            val debitRef = extractUpiRef(debit.bodySnippet)
            var bestIdx: Int? = null
            var bestNote: String? = null
            var bestDt = Long.MAX_VALUE
            for ((idx, credit) in credits.withIndex()) {
                if (idx in usedCredits) continue
                if (credit.amountPaise != debit.amountPaise) continue
                val creditBank = credit.bankId
                if (creditBank == null || creditBank !in declaredBankIds) continue
                if (debitBank != null && creditBank == debitBank) continue
                val dt = abs(credit.occurredAt - debit.occurredAt)
                val bankLabel = IndianBanks.byId(creditBank)?.label ?: creditBank
                if (debitRef != null && credit.upiRef != null && debitRef == credit.upiRef) {
                    if (dt <= refWindowMs && dt < bestDt) {
                        bestIdx = idx
                        bestDt = dt
                        bestNote = "Paired · $bankLabel"
                    }
                } else if (credit.upiRef == null && dt <= amountWindowMs && dt < bestDt) {
                    bestIdx = idx
                    bestDt = dt
                    bestNote = "Paired · $bankLabel"
                }
            }
            val idx = bestIdx ?: continue
            usedCredits += idx
            out += debit.id to (bestNote ?: "Paired")
        }
        return out
    }
}
