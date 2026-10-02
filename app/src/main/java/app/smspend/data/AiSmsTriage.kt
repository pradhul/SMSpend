package app.smspend.data

import app.smspend.domain.IndianBanks
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Amount (and optional payee) pulled from an SMS by a learned per-sender template. */
data class TemplateMatch(
    val amountPaise: Long,
    val payee: String?,
)

/**
 * Local, no-network helpers for the AI fallback: which rejected SMS are worth sending to
 * Gemini, and validation / application of the per-sender regex templates it proposes.
 */
object AiSmsTriage {
    /** SMS per Gemini triage call. */
    const val BATCH_SIZE = 40

    /** Gemini calls per triage pass; the rest wait for the next pass. */
    const val MAX_CALLS_PER_PASS = 3

    const val MAX_BODY_CHARS = 400

    /** A template must carry this many consecutive literal characters to count as a format. */
    const val MIN_LITERAL_ANCHOR = 6

    private const val MAX_TEMPLATE_LENGTH = 400

    private val transactionCues = listOf(
        "a/c", "acct", "account", "upi", "imps", "neft", "rtgs", "card", "ref", "txn",
    )

    /** Never transactions; skip without spending an API call. */
    private val notTransactionPhrases = listOf(
        "will be debited", "will be deducted", "due on", "is due", "statement",
        "requested money", "collect request", "payment request",
    )

    /** DLT suffix of the sender header: -P promotional, -G government. */
    private val skipSuffixes = setOf("P", "G")

    /**
     * True when an SMS that both built-in parsers rejected looks like a bank / fintech
     * transaction worth asking Gemini about.
     */
    fun isCandidate(body: String, sender: String): Boolean {
        val text = body.replace('\n', ' ').trim()
        if (text.length < 20) return false
        if (dltSuffix(sender) in skipSuffixes) return false
        if (SmsDebitParser.isOtp(text)) return false
        if (!SmsDebitParser.hasAmount(text)) return false
        val lower = text.lowercase(Locale.US)
        if (notTransactionPhrases.any { lower.contains(it) }) return false
        val financeSender = SmsDebitParser.isBankSender(sender) ||
            SmsDebitParser.isMerchantAppSender(sender) ||
            IndianBanks.matchSender(sender) != null
        return financeSender || transactionCues.any { lower.contains(it) }
    }

    fun dltSuffix(sender: String): String? {
        val parts = sender.trim().uppercase(Locale.US).split('-').filter { it.isNotBlank() }
        if (parts.size < 3) return null
        return parts.last().takeIf { it.length == 1 }
    }

    /**
     * Accept a Gemini-proposed template only if it compiles, has an `amount` group, reproduces
     * [expectedAmountPaise] on [sampleBody], is anchored on real literal text, and does not match
     * any body the AI already called a non-transaction for this sender.
     */
    fun validateTemplate(
        pattern: String,
        sampleBody: String,
        expectedAmountPaise: Long,
        notTransactionBodies: List<String> = emptyList(),
    ): Boolean {
        if (pattern.isBlank() || pattern.length > MAX_TEMPLATE_LENGTH) return false
        if (!pattern.contains("(?<amount>")) return false
        if (longestLiteralRun(pattern) < MIN_LITERAL_ANCHOR) return false
        val match = applyTemplate(pattern, sampleBody) ?: return false
        if (match.amountPaise != expectedAmountPaise) return false
        return notTransactionBodies.none { applyTemplate(pattern, it) != null }
    }

    private val anyAmountPattern = Pattern.compile("""(?<![\d,])(\d{1,3}(?:,\d{2,3})+|\d+)(?:\.(\d{1,2}))?(?![\d,])""")

    /** True when [amountPaise] is written somewhere in [body]; guards against invented amounts. */
    fun amountAppearsIn(body: String, amountPaise: Long): Boolean {
        val m = anyAmountPattern.matcher(body)
        while (m.find()) {
            val rupees = "${m.group(1).replace(",", "")}.${m.group(2) ?: "0"}".toDoubleOrNull() ?: continue
            if (Math.round(rupees * 100.0) == amountPaise) return true
        }
        return false
    }

    /** Runs a stored template against [body]; null when it does not match or yields no amount. */
    fun applyTemplate(pattern: String, body: String): TemplateMatch? {
        val compiled = compile(pattern) ?: return null
        val m = compiled.matcher(body.replace('\n', ' '))
        if (!m.find()) return null
        val raw = runCatching { m.group("amount") }.getOrNull() ?: return null
        val rupees = raw.replace(",", "").trim().toDoubleOrNull() ?: return null
        val paise = Math.round(rupees * 100.0)
        if (paise <= 0) return null
        val payee = runCatching { m.group("payee") }.getOrNull()
            ?.trim()
            ?.trimEnd('.', ',', ';', ' ')
            ?.trim()
            ?.takeIf { it.length >= 2 }
        return TemplateMatch(amountPaise = paise, payee = payee)
    }

    private fun compile(pattern: String): Pattern? =
        try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
        } catch (_: PatternSyntaxException) {
            null
        }

    /** Longest run of required literal characters (escapes like \d, classes, groups, optional chars break it). */
    internal fun longestLiteralRun(pattern: String): Int {
        val classEscapes = "dDsSwWbBpPQEkKuxntrfaeGZzAh0123456789"
        var best = 0
        var run = 0
        var i = 0
        fun nextIsOptionalQuantifier(at: Int): Boolean =
            at < pattern.length && pattern[at] in "*?{"
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    val n = pattern[i + 1]
                    i += 2
                    if (n in classEscapes || nextIsOptionalQuantifier(i)) {
                        run = 0
                    } else {
                        run++
                        best = maxOf(best, run)
                    }
                    continue
                }
                c == '[' -> {
                    run = 0
                    i++
                    while (i < pattern.length && pattern[i] != ']') {
                        if (pattern[i] == '\\') i++
                        i++
                    }
                    i++
                    continue
                }
                c == '(' -> {
                    run = 0
                    i++
                    if (i < pattern.length && pattern[i] == '?') {
                        if (i + 1 < pattern.length && pattern[i + 1] == '<') {
                            while (i < pattern.length && pattern[i] != '>') i++
                        } else {
                            while (i < pattern.length && pattern[i] != ':' && pattern[i] != ')') i++
                        }
                        i++
                    }
                    continue
                }
                c == '{' -> {
                    run = 0
                    while (i < pattern.length && pattern[i] != '}') i++
                    i++
                    continue
                }
                c in ")|.^$*+?" -> {
                    run = 0
                    i++
                    continue
                }
                else -> {
                    i++
                    if (nextIsOptionalQuantifier(i)) {
                        run = 0
                    } else {
                        run++
                        best = maxOf(best, run)
                    }
                }
            }
        }
        return best
    }
}
