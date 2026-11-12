package app.smspend.notify

import app.smspend.util.rupeesToPaise
import java.util.Locale
import java.util.regex.Pattern

/**
 * Parsed fields from a UPI app notification (PhonePe / later GPay, Paytm).
 * [candidateNote] is leftover text after amount + payee are stripped — only present
 * when the app includes the user's typed note in the notification.
 */
data class ParsedUpiNotification(
    val amountPaise: Long,
    val payee: String,
    val candidateNote: String,
)

object UpiNotificationParser {
    private val amountPattern = Pattern.compile(
        """(?i)(?:rs\.?|inr|₹)\s*:?\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)(?![0-9])""",
    )

    private val payeePatterns = listOf(
        Pattern.compile(
            """(?i)(?:paid\s+to|sent\s+to|payment\s+to)\s+([A-Za-z][A-Za-z0-9 .&'@_-]{1,50}?)(?=\s+(?:via|using|on|ref|upi|for|sent|paid|₹|rs\.?|inr)|[.!,]|$)""",
        ),
        Pattern.compile(
            """(?i)(?:paid|sent|payment)\s+(?:rs\.?|inr|₹)?\s*[0-9,]*(?:\.[0-9]+)?\s+to\s+([A-Za-z][A-Za-z0-9 .&'@_-]{1,50}?)(?=\s+(?:via|using|on|ref|upi|for|sent|paid)|[.!,]|$)""",
        ),
        Pattern.compile(
            """(?i)you\s+paid\s+(?:rs\.?|inr|₹)?\s*[0-9,]*(?:\.[0-9]+)?\s+to\s+([A-Za-z][A-Za-z0-9 .&'@_-]{1,50}?)(?=\s+(?:via|using|on|ref|upi|for)|[.!,]|$)""",
        ),
    )

    fun parse(title: String?, text: String?, bigText: String?): ParsedUpiNotification? {
        val fields = listOfNotNull(bigText, text, title)
            .map { it.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim() }
            .filter { it.length >= 3 }
        if (fields.isEmpty()) return null

        val combined = fields.joinToString(" ")
        val amountPaise = fields.firstNotNullOfOrNull { extractAmountPaise(it) }
            ?: extractAmountPaise(combined)
            ?: return null
        val payee = fields.firstNotNullOfOrNull { extractPayee(it) }
            ?: extractPayee(combined)
            ?: ""
        val noteSource = fields.firstOrNull { extractPayee(it) != null } ?: combined
        val candidateNote = extractCandidateNote(noteSource, payee)
        if (payee.isBlank() && candidateNote.isBlank()) return null

        return ParsedUpiNotification(
            amountPaise = amountPaise,
            payee = payee,
            candidateNote = candidateNote,
        )
    }

    /** Formats "payee · note" for storage on a transaction. */
    fun formatNote(payee: String, candidateNote: String): String {
        val p = payee.trim()
        val n = candidateNote.trim()
        return when {
            p.isNotEmpty() && n.isNotEmpty() -> "$p · $n"
            p.isNotEmpty() -> p
            else -> n
        }
    }

    fun extractAmountPaise(text: String): Long? {
        val matcher = amountPattern.matcher(text)
        var best: Long? = null
        while (matcher.find()) {
            rupeesToPaise(matcher.group(1))?.let { best = it }
        }
        return best
    }

    fun extractPayee(text: String): String? {
        for (p in payeePatterns) {
            val m = p.matcher(text)
            if (m.find()) {
                val value = m.group(1)?.trim()?.trimEnd('.', ',', ';') ?: continue
                if (value.length >= 2) return value
            }
        }
        return null
    }

    private fun extractCandidateNote(combined: String, payee: String): String {
        var rest = combined
        // Drop amount tokens
        rest = amountPattern.matcher(rest).replaceAll(" ")
        if (payee.isNotBlank()) {
            rest = rest.replace(payee, " ", ignoreCase = true)
        }
        // Drop common boilerplate
        val boilerplate = listOf(
            "paid to", "sent to", "payment to", "you paid", "you sent",
            "successfully", "successful", "phonepe", "google pay", "gpay", "paytm",
            "upi", "via", "using", "on", "ref", "transaction", "payment",
        )
        var lower = rest.lowercase(Locale.US)
        for (b in boilerplate) {
            lower = lower.replace(b, " ")
        }
        return lower
            .replace(Regex("""[^a-z0-9 .&'@_-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
    }
}
