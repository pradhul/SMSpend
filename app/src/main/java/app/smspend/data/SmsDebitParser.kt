package app.smspend.data

import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs

data class ParsedDebit(
    val amountPaise: Long,
    val merchantRaw: String,
    val merchantKey: String,
)

/** Parsed HDFC-style ATM reverse SMS — not a spend; used to cancel a Withdrawn debit. */
data class AtmReversal(
    val amountPaise: Long,
    val placeRaw: String,
    val placeKey: String,
    val cardLast4: String? = null,
)

/** ATM withdrawal row for reversal matching (unit-testable without Room). */
data class AtmWithdrawalPoint(
    val id: Long,
    val amountPaise: Long,
    val occurredAt: Long,
    val merchantKey: String,
    val bodySnippet: String = "",
)

/** Lightweight row used by cross-source purge helpers (unit-testable without Room). */
data class CrossSourcePoint(
    val id: Long,
    val amountPaise: Long,
    val occurredAt: Long,
    val merchantKey: String,
    val isBank: Boolean,
    /** Optional SMS body — needed for ACH vs UMRN investment-alert collapse. */
    val bodySnippet: String = "",
)

object SmsDebitParser {
    /** Bump when parsing logic changes so stored transactions get re-checked once. */
    const val VERSION = 10

    /** ± window for bank-vs-merchant cross-source dedup (any merchant app + bank SMS). */
    const val CROSS_SOURCE_WINDOW_MS = 6L * 60 * 60 * 1000

    /** Window to match ATM Txn Reversed SMS to a Withdrawn debit. */
    const val ATM_REVERSAL_WINDOW_MS = 24L * 60 * 60 * 1000

    /** ± window to collapse opaque "Amt Deducted NEFT" with named "credited to payee" leg. */
    const val NEFT_LEG_COLLAPSE_WINDOW_MS = 3L * 60 * 60 * 1000

    const val SELF_TRANSFER_PAIR_WINDOW_MS = 5L * 60 * 1000
    const val SELF_TRANSFER_FEDERAL_WINDOW_MS = 2L * 60 * 1000
    const val SELF_RD_CROSS_NOTE_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

    const val ATM_FAILED_NOTE = "Failed · reversed"
    const val SELF_RD_WENT_NOTE = "Went to RD"
    const val SELF_RD_FROM_NOTE = "From own account"
    const val CARD_PAYMENT_NOTE = "Card bill payment"
    const val CARD_SPEND_TAG_PREFIX = "Card ·"
    const val CARD_SPEND_TAG_FALLBACK = "Card spend"

    /**
     * DLT sender cores for Indian banks. Matched as substring of [senderKey] so
     * "VM-HDFCBK-S" → "HDFCBK" and "AD-SBIINB" → "SBIINB" both hit.
     */
    private val bankSenderTokens = listOf(
        "HDFCBK", "SBIINB", "SBIIN", "ICICIB", "AXISBK", "KOTAKB",
        "IDFCFB", "YESBNK", "PNBSMS", "BOIIND", "CANBNK", "UNIONB",
        "INDUSB", "FEDBNK", "CUBLTD", "KARBNK", "RBLBNK", "SCISMS",
        "BOBTXN", "BOBSMS", "BARODA",
    )

    /**
     * Merchant / UPI / fintech app DLT senders. These are never bank ledger SMS,
     * even when the body mentions a/c or "from your account".
     */
    private val merchantAppSenderTokens = listOf(
        "GROWW", "PHONEPE", "PAYTM", "GPAY", "GOOGLEPAY", "GOOGPAY",
        "BHARATPE", "MOBIKWIK", "AMAZONPAY", "AMAZONP", "SWIGGY", "ZOMATO",
        "ZERODHA", "RAINBOW", "CREDCL", "CRED", "SLICE", "JUPITER",
        "NIYO", "FLIPKART", "MYNTRA", "UBER", "OLA", "RAPIDO",
        "AIRTEL", "JIOCASH", "FREECHARGE", "PAYU", "RAZORPAY",
    )

    /** Body cues that the SMS is from a bank ledger, not a merchant app confirmation. */
    private val bankBodyHints = listOf(
        "a/c", "acct", "avl bal", "avbl bal", "card ending", "from your account",
    )

    private val creditHints = listOf(
        "credited", "received", "deposited", "refund", "cashback credited",
        "neft credit", "imps credit", "has been credited",
    )

    /** Past-tense, completed-transaction evidence. Promo blockers are skipped when present. */
    private val strongDebitHints = listOf(
        "debited", "deducted", "withdrawn", "spent", "used at", "used for",
    )

    /** Weaker debit evidence; only trusted when the message has no promotional smell. */
    private val weakDebitHints = listOf(
        "paid", "payment of", "sent", "purchase", "txn of", "transaction of", "debit of",
    )

    /** Messages that are never completed spends, regardless of other hints. */
    private val hardBlockers = listOf(
        "one time password", "has requested", "is requesting", "requested money",
        "collect request", "payment request", "will be debited", "will be deducted",
        "due on", "is due", "overdue", "min due", "minimum due", "total due", "min amt due",
        "statement", "e-mandate", "emandate", "mandate created", "mandate registered",
        "mandate set", "insufficient", "declined", "reversed", "reversal", "autopay setup",
        "txn failed", "transaction failed", "payment failed", "could not be processed",
    )
    private val otpPattern = Pattern.compile("""(?i)\botp\b""")

    /** Promotional smell; blocks messages that lack strong (past-tense) debit evidence. */
    private val promoBlockers = listOf(
        "loan", "offer", "pre-approved", "pre approved", "preapproved", "apply now",
        "cashback", "discount", "voucher", "eligible", "congratulations", "reward",
        "insurance", "recharge", "renew", "upto", "up to", "hurry", "click", "http",
        "www.", "interest rate", "win ", "t&c", "tnc",
    )

    /**
     * Matches "Rs 1,23,456.78", "INR 500", "₹2,000" — supports both Indian (2,2,3)
     * and western (3,3,3) digit grouping. The trailing lookahead stops partial matches
     * of ungrouped numbers (e.g. "500" inside "500000").
     */
    private val amountPattern = Pattern.compile(
        """(?i)(?:rs\.?|inr|₹)\s*:?\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)(?![0-9])""",
    )

    /** If these appear just before an amount, it's a balance/limit/offer figure, not the spend. */
    private val badAmountContext = listOf(
        "bal", "avl", "avbl", "available", "limit", "due", "outstanding",
        "upto", "up to", "worth", "cashback", "reward",
    )
    private val badAmountContextWords = Pattern.compile("""(?i)\b(get|win|save|earn|loan)(\s+of)?\s*$""")

    /** ATM: "Withdrawn Rs.20000 From … Card x1868 At +KUDLU MAIN ROAD …" */
    private val atmWithdrawnAtPattern = Pattern.compile(
        """(?i)Withdrawn\s+(?:Rs\.?|INR|₹)\s*[\d,]+\.?\d*\s+From\b.+?\bAt\s+\+?([A-Za-z0-9 .,&'-]{3,60}?)(?=\s+(?:on|SMS|Ref|Not|Call|Txn|UPI|to\s+\d)|[.!,]|$)""",
    )

    /** Prefer explicit UPI VPA when present: "To:9986981757@ybl" */
    private val toVpaPattern = Pattern.compile(
        """(?i)\bTo:([A-Za-z0-9._\-]+@[A-Za-z0-9._\-]+)""",
    )

    private val merchantPatterns = listOf(
        Pattern.compile(
            """(?i)(?:to|at|towards|for|paid to|sent to|VPA\s*)[:\s]+([A-Za-z0-9 .&'@_+-]{2,80}?)(?=\s+(?:via|on|ref|upi|not you|avbl|umrn|avl|\d{1,2}[/-])|[.!,]|$)""",
        ),
        Pattern.compile("""(?i)info[:\s]+([A-Za-z0-9 .&_-]{2,80}?)(?=\.\s*Avl|\.\s*Available|$)"""),
        Pattern.compile("""(?i)UPI/([A-Za-z0-9._-]{2,40})"""),
    )

    private val currencyPrefix = Pattern.compile("""(?i)^(INR|Rs\.?|₹)\b""")
    private val barePhone = Pattern.compile("""^\d{10,}$""")
    private val blockToContext = Pattern.compile(
        """(?i)(?:sms\s*block|block\s+\w+\s+\d{2,}\s+to|call)\s*$""",
    )

    /** "ATM Txn Reversed:Rs.20000 …" / "ATM transaction reversed …" */
    private val atmReversalHeader = Pattern.compile(
        """(?i)\batm\s+(?:txn|transaction)?\s*reversed\b""",
    )
    private val atmReversalAtPattern = Pattern.compile(
        """(?i)\bAt\s+\+?([A-Za-z0-9 .,&'-]{2,60}?)(?=\s+On|[.!,]|$)""",
    )
    private val atmCardLast4 = Pattern.compile(
        """(?i)\bCard\s*x?(\d{4})\b""",
    )

    /**
     * Transaction reference extractors. Deliberately exclude UMRN (mandate ids repeat
     * every month and must not collapse distinct installments).
     */
    private val txnRefPatterns = listOf(
        Pattern.compile(
            """(?i)(?:UPI\s*Ref(?:\s*No)?|Ref(?:erence)?(?:\s*No)?|UTR|RRN|Txn\s*(?:ID|No))[.:#\s]*([A-Za-z0-9]{6,25})""",
        ),
    )

    /**
     * HDFC-style outbound NEFT confirmation:
     * "NEFT money transfer Txn No … has been credited to AMMA SBI on 01-08-2026 …"
     * Payee is the beneficiary, not the user's own account.
     */
    private val neftCreditedToPayeePattern = Pattern.compile(
        """(?i)\bNEFT\b[^.]*?money transfer\b.*?\bcredited to\s+([A-Za-z0-9 .&'-]{2,60}?)\s+(?:on\b|[.!,]|$)""",
    )

    private val inboundCreditPayeePrefixes = listOf(
        "your", "a/c", "acct", "account", "my ", "self",
    )

    /**
     * True when this SMS is a bank debit (ledger is source of truth). Everything else
     * that [parse] accepts is treated as a merchant confirmation (any UPI/fintech app)
     * and may be suppressed when a matching bank debit exists.
     *
     * Merchant/app senders are never bank, even if the body has account phrases.
     */
    fun isBankDebit(body: String, sender: String): Boolean {
        val key = senderKey(sender)
        if (isMerchantAppSenderKey(key)) return false
        if (bankSenderTokens.any { key.contains(it) }) return true
        val lower = body.lowercase(Locale.US)
        return bankBodyHints.any { lower.contains(it) }
    }

    fun isMerchantAppSender(sender: String): Boolean =
        isMerchantAppSenderKey(senderKey(sender))

    private fun isMerchantAppSenderKey(key: String): Boolean =
        merchantAppSenderTokens.any { key.contains(it) }

    /** True if any bank debit shares [amountPaise] within ±[windowMs] of [occurredAt]. */
    fun hasMatchingBankDebit(
        amountPaise: Long,
        occurredAt: Long,
        bankDebits: List<Pair<Long, Long>>,
        windowMs: Long = CROSS_SOURCE_WINDOW_MS,
    ): Boolean = bankDebits.any { (amt, at) ->
        amt == amountPaise && abs(at - occurredAt) <= windowMs
    }

    /**
     * Merchant confirmation ids shadowed by a bank debit (same amount, within window).
     * [txs] entries need id, amountPaise, occurredAt, isBank.
     */
    fun merchantIdsShadowedByBank(
        txs: List<CrossSourcePoint>,
        windowMs: Long = CROSS_SOURCE_WINDOW_MS,
    ): List<Long> {
        val banks = txs.filter { it.isBank }
        val merchants = txs.filter { !it.isBank }
        return merchants.filter { m ->
            banks.any { b ->
                b.amountPaise == m.amountPaise && abs(b.occurredAt - m.occurredAt) <= windowMs
            }
        }.map { it.id }
    }

    /**
     * Among bank debits: same amount + same non-unknown merchantKey within window →
     * keep earliest (by occurredAt, then id), return later ids to delete.
     */
    fun bankSameMerchantDuplicateIds(
        banks: List<CrossSourcePoint>,
        windowMs: Long = CROSS_SOURCE_WINDOW_MS,
    ): List<Long> {
        // Opaque Online Banking rows share merchantKey "bank transfer" with no payee —
        // do not collapse distinct NEFT/IMPS of the same amount.
        val onlyBanks = banks.filter {
            it.isBank &&
                it.merchantKey.isNotBlank() &&
                it.merchantKey != "unknown" &&
                it.merchantKey != "bank transfer"
        }
        val toRemove = mutableSetOf<Long>()
        val byAmount = onlyBanks.groupBy { it.amountPaise }
        for ((_, group) in byAmount) {
            if (group.size <= 1) continue
            val sorted = group.sortedWith(compareBy({ it.occurredAt }, { it.id }))
            for (i in sorted.indices) {
                val keep = sorted[i]
                if (keep.id in toRemove) continue
                for (j in (i + 1) until sorted.size) {
                    val other = sorted[j]
                    if (other.id in toRemove) continue
                    if (other.merchantKey != keep.merchantKey) continue
                    if (abs(other.occurredAt - keep.occurredAt) > windowMs) continue
                    toRemove += other.id
                }
            }
        }
        return toRemove.toList()
    }

    private val investmentBrands = listOf("groww", "zerodha", "kuvera", "coin by zerodha")

    /** ACH ledger debit: "Info: ACH D- Groww Pay Services P-00005ZRW5OPR". */
    fun isAchInvestmentDebit(body: String, merchantKey: String = ""): Boolean {
        val hay = "$merchantKey $body".lowercase(Locale.US)
        if (investmentBrands.none { hay.contains(it) }) return false
        return hay.contains("ach d") || hay.contains("info: ach") ||
            Regex("""\bach\b""").containsMatchIn(hay)
    }

    /**
     * Mandate / UMRN payment alert that often re-states an ACH investment debit:
     * "PAYMENT ALERT! INR 75000 deducted … towards Groww … UMRN: …"
     */
    fun isInvestmentMandateAlert(body: String, merchantKey: String = ""): Boolean {
        val hay = "$merchantKey $body".lowercase(Locale.US)
        if (investmentBrands.none { hay.contains(it) }) return false
        if (isAchInvestmentDebit(body, merchantKey)) return false
        return hay.contains("umrn") ||
            (hay.contains("payment alert") && hay.contains("towards"))
    }

    private fun sharedInvestmentBrand(a: String, b: String): Boolean {
        val hayA = a.lowercase(Locale.US)
        val hayB = b.lowercase(Locale.US)
        return investmentBrands.any { hayA.contains(it) && hayB.contains(it) }
    }

    /**
     * Bank UMRN / PAYMENT ALERT investment SMS shadowed by a same-amount ACH ledger
     * debit within [windowMs]. Does **not** collapse two ACH rows (distinct ACH refs /
     * real double investments stay).
     */
    fun investmentMandateAlertDuplicateIds(
        txs: List<CrossSourcePoint>,
        windowMs: Long = CROSS_SOURCE_WINDOW_MS,
    ): List<Long> {
        val banks = txs.filter { it.isBank && it.bodySnippet.isNotBlank() }
        val ach = banks.filter { isAchInvestmentDebit(it.bodySnippet, it.merchantKey) }
        val alerts = banks.filter { isInvestmentMandateAlert(it.bodySnippet, it.merchantKey) }
        if (ach.isEmpty() || alerts.isEmpty()) return emptyList()
        return alerts.filter { alert ->
            ach.any { a ->
                a.amountPaise == alert.amountPaise &&
                    abs(a.occurredAt - alert.occurredAt) <= windowMs &&
                    sharedInvestmentBrand(
                        "${a.merchantKey} ${a.bodySnippet}",
                        "${alert.merchantKey} ${alert.bodySnippet}",
                    )
            }
        }.map { it.id }
    }

    /**
     * HDFC-style ATM bounce credit SMS. Never treated as a debit spend —
     * use [atmWithdrawalIdsReversedBy] to cancel the matching Withdrawn row.
     */
    fun parseAtmReversal(body: String): AtmReversal? {
        val text = body.replace('\n', ' ').trim()
        if (text.length < 20) return null
        if (!atmReversalHeader.matcher(text).find()) return null
        val amountPaise = extractAmountPaise(text) ?: return null
        if (amountPaise <= 0) return null
        val placeMatcher = atmReversalAtPattern.matcher(text)
        val place = if (placeMatcher.find()) {
            placeMatcher.group(1)
                ?.trim()
                ?.trimStart('+')
                ?.trimEnd('.', ',', ';', ' ')
                ?.trim()
                .orEmpty()
        } else {
            ""
        }
        if (place.length < 2) return null
        val card = atmCardLast4.matcher(text).let { m ->
            if (m.find()) m.group(1) else null
        }
        return AtmReversal(
            amountPaise = amountPaise,
            placeRaw = place,
            placeKey = normalizeMerchantKey("ATM $place"),
            cardLast4 = card,
        )
    }

    fun isAtmWithdrawal(body: String, merchantKey: String = ""): Boolean {
        val hay = "$merchantKey $body".lowercase(Locale.US)
        if (!hay.contains("withdrawn")) return false
        return hay.contains("atm") || merchantKey.startsWith("atm")
    }

    private fun atmPlacesMatch(reversalPlaceKey: String, withdrawal: AtmWithdrawalPoint): Boolean {
        if (withdrawal.merchantKey == reversalPlaceKey) return true
        val placeTokens = reversalPlaceKey.removePrefix("atm ").trim()
        if (placeTokens.length >= 3 && withdrawal.merchantKey.contains(placeTokens)) return true
        val hay = "${withdrawal.merchantKey} ${withdrawal.bodySnippet}".lowercase(Locale.US)
        return placeTokens.split(' ').filter { it.length >= 3 }.all { hay.contains(it) }
    }

    /**
     * Withdrawal ids cancelled by ATM reversal SMS (same amount + place, within window).
     * Each reversal removes at most one unmatched withdrawal (earliest first).
     */
    fun atmWithdrawalIdsReversedBy(
        reversals: List<Pair<AtmReversal, Long>>,
        withdrawals: List<AtmWithdrawalPoint>,
        windowMs: Long = ATM_REVERSAL_WINDOW_MS,
    ): List<Long> {
        if (reversals.isEmpty() || withdrawals.isEmpty()) return emptyList()
        val remaining = withdrawals
            .sortedWith(compareBy({ it.occurredAt }, { it.id }))
            .toMutableList()
        val toRemove = mutableListOf<Long>()
        val sortedRevs = reversals.sortedBy { it.second }
        for ((rev, at) in sortedRevs) {
            val idx = remaining.indexOfFirst { w ->
                w.amountPaise == rev.amountPaise &&
                    abs(w.occurredAt - at) <= windowMs &&
                    atmPlacesMatch(rev.placeKey, w)
            }
            if (idx < 0) continue
            toRemove += remaining.removeAt(idx).id
        }
        return toRemove
    }

    fun parse(body: String, sender: String): ParsedDebit? {
        val text = body.replace('\n', ' ').trim()
        if (text.length < 12) return null
        val lower = text.lowercase(Locale.US)

        if (otpPattern.matcher(lower).find()) return null
        if (hardBlockers.any { lower.contains(it) }) return null

        // Outbound NEFT confirmation ("credited to <payee>") — before debit/credit gates,
        // because the body has no debit keyword and "credited" would otherwise reject it.
        parseNeftCreditedToPayee(text)?.let { return it }

        val hasStrong = strongDebitHints.any { lower.contains(it) }
        val hasWeak = weakDebitHints.any { lower.contains(it) }
        if (!hasStrong && !hasWeak) return null

        if (creditHints.any { lower.contains(it) } && !hasStrong) return null
        if (!hasStrong && promoBlockers.any { lower.contains(it) }) return null

        val amountPaise = extractAmountPaise(text) ?: return null
        if (amountPaise <= 0) return null

        val merchantRaw = extractMerchant(text) ?: sender.ifBlank { "Unknown" }
        return ParsedDebit(
            amountPaise = amountPaise,
            merchantRaw = merchantRaw.trim().take(80),
            merchantKey = normalizeMerchantKey(merchantRaw),
        )
    }

    /**
     * HDFC "NEFT money transfer … has been credited to <payee>" — user's outbound
     * transfer confirmation. Rejects inbound credits ("credited to your a/c").
     */
    fun parseNeftCreditedToPayee(body: String): ParsedDebit? {
        val text = body.replace('\n', ' ').trim()
        val m = neftCreditedToPayeePattern.matcher(text)
        if (!m.find()) return null
        val payee = m.group(1)
            ?.trim()
            ?.trimEnd('.', ',', ';', ' ')
            ?.trim()
            .orEmpty()
        if (payee.length < 2) return null
        val payeeLower = payee.lowercase(Locale.US)
        if (inboundCreditPayeePrefixes.any { payeeLower.startsWith(it) }) return null
        val amountPaise = extractAmountPaise(text) ?: return null
        if (amountPaise <= 0) return null
        return ParsedDebit(
            amountPaise = amountPaise,
            merchantRaw = payee.take(80),
            merchantKey = normalizeMerchantKey(payee),
        )
    }

    fun isNeftCreditedToPayee(body: String): Boolean =
        parseNeftCreditedToPayee(body) != null

    fun isOpaqueBankTransfer(body: String, merchantKey: String = ""): Boolean {
        if (opaqueBankTransferMerchant(body) != null) return true
        return merchantKey == "bank transfer"
    }

    /**
     * Bank debit that pays a credit-card bill (autopay / CRED / BillDesk / "credit card").
     * Must NOT match itemized card-spend SMS ("Spent Rs.X On HDFC Bank Card 4062 At MERCHANT").
     */
    /**
     * Bank debit that pays a credit-card bill (autopay / CRED / BillDesk).
     * Must NOT match itemized card-spend SMS ("Spent Rs.X On HDFC Bank Card 4062 At MERCHANT").
     * Check [cardLast4] / [looksLikeCardSpend] first before calling this.
     */
    fun isCardBillPayment(body: String, merchantRaw: String = ""): Boolean {
        if (looksLikeCardSpend(body)) return false
        val hay = "$merchantRaw $body".lowercase(Locale.US)
        // Avoid matching "spent on your credit card" itemized spends — handled above.
        if (hay.contains("card bill") || hay.contains("credit card bill") ||
            hay.contains("cc bill") || (hay.contains("bill payment") && hay.contains("card"))
        ) {
            return true
        }
        if (hay.contains("si-tad") || hay.contains("autopay si")) return true
        if (hay.contains("cred club") || hay.contains("cred.club")) return true
        if (hay.contains("billdesk")) return true
        // Narrow "credit card" — payment/settlement context only.
        if ((hay.contains("credit card") || hay.contains("creditcard")) &&
            (hay.contains("paid") || hay.contains("payment") || hay.contains("bill") ||
                hay.contains("autopay") || hay.contains("settled"))
        ) {
            return true
        }
        return false
    }

    fun looksLikeCardSpend(body: String): Boolean {
        val lower = body.lowercase(Locale.US)
        return lower.contains("spent") ||
            (lower.contains("purchase") && lower.contains("card")) ||
            (lower.contains("txn") && lower.contains("card") && lower.contains(" at "))
    }

    /** Last-4 digits from card-spend SMS, or null. */
    fun extractCardLast4(body: String): String? {
        if (!looksLikeCardSpend(body)) return null
        if (!body.lowercase(Locale.US).contains("card")) return null
        return atmCardLast4.matcher(body).let { m ->
            if (m.find()) m.group(1) else TransactionEntity.CARD_LAST4_UNKNOWN
        }
    }

    /**
     * Tag for itemized card-spend SMS ("Spent Rs.X On … Card 4062 At MERCHANT").
     * Returns null for bill payments and non-card SMS. Prefer [extractCardLast4] + cardLast4 column.
     */
    fun cardSpendTag(body: String, merchantRaw: String = ""): String? {
        if (isCardBillPayment(body, merchantRaw)) return null
        val last4 = extractCardLast4(body) ?: return null
        return if (last4 == TransactionEntity.CARD_LAST4_UNKNOWN) {
            CARD_SPEND_TAG_FALLBACK
        } else {
            "$CARD_SPEND_TAG_PREFIX$last4"
        }
    }

    fun isCardSpendTag(note: String): Boolean {
        val n = note.trim()
        return n.equals(CARD_SPEND_TAG_FALLBACK, ignoreCase = true) ||
            n.startsWith(CARD_SPEND_TAG_PREFIX, ignoreCase = true)
    }

    /**
     * When both the opaque "Amt Deducted … NEFT txn" leg and the named
     * "credited to <payee>" leg exist for the same amount within [windowMs],
     * drop the opaque row(s) and keep the named payee row.
     */
    fun opaqueNeftLegDuplicateIds(
        txs: List<CrossSourcePoint>,
        windowMs: Long = NEFT_LEG_COLLAPSE_WINDOW_MS,
    ): List<Long> {
        val banks = txs.filter { it.isBank && it.bodySnippet.isNotBlank() }
        val named = banks.filter { isNeftCreditedToPayee(it.bodySnippet) }
        val opaque = banks.filter {
            isOpaqueBankTransfer(it.bodySnippet, it.merchantKey) &&
                !isNeftCreditedToPayee(it.bodySnippet)
        }
        if (named.isEmpty() || opaque.isEmpty()) return emptyList()
        val toRemove = mutableSetOf<Long>()
        for (op in opaque) {
            val hasNamed = named.any { n ->
                n.amountPaise == op.amountPaise &&
                    abs(n.occurredAt - op.occurredAt) <= windowMs
            }
            if (hasNamed) toRemove += op.id
        }
        return toRemove.toList()
    }

    /**
     * Extracts the transaction amount: collects every ₹ amount in the message, drops
     * ones preceded by balance/limit/offer context, then prefers the one closest to a
     * debit keyword (handles "Rs 500 debited ... Avl Bal Rs 32,000" in either order).
     */
    fun extractAmountPaise(text: String): Long? {
        val lower = text.lowercase(Locale.US)
        val matcher = amountPattern.matcher(text)
        val candidates = mutableListOf<Pair<Int, Long>>() // position to paise
        var prevMatchEnd = 0
        while (matcher.find()) {
            val start = matcher.start()
            val raw = matcher.group(1)?.replace(",", "") ?: continue
            val rupees = raw.toDoubleOrNull() ?: continue
            val paise = Math.round(rupees * 100.0)
            // Context window never reaches past the previous amount, so "Avl Bal Rs X. Rs Y debited"
            // doesn't poison the second amount with the first one's balance context.
            val contextStart = (start - 28).coerceAtLeast(prevMatchEnd)
            prevMatchEnd = matcher.end()
            if (paise <= 0) continue
            val context = lower.substring(contextStart, start)
            if (badAmountContext.any { context.contains(it) }) continue
            if (badAmountContextWords.matcher(context).find()) continue
            candidates += start to paise
        }
        if (candidates.isEmpty()) return null

        val hintPositions = buildList {
            for (hint in strongDebitHints + weakDebitHints) {
                var idx = lower.indexOf(hint)
                while (idx >= 0) {
                    add(idx)
                    idx = lower.indexOf(hint, idx + 1)
                }
            }
        }
        if (hintPositions.isEmpty()) return candidates.first().second

        return candidates.minByOrNull { (pos, _) ->
            hintPositions.minOf { abs(it - pos) }
        }?.second
    }

    /**
     * Opaque HDFC-style Online Banking NEFT / IMPS / RTGS / Money Transfer SMS
     * (no payee name) → stable merchant "Bank transfer".
     */
    fun opaqueBankTransferMerchant(body: String): String? {
        val lower = body.lowercase(Locale.US)
        val channel =
            lower.contains("neft") ||
                lower.contains("imps") ||
                lower.contains("rtgs") ||
                lower.contains("money transfer")
        if (!channel) return null
        if (lower.contains("online banking") ||
            (lower.contains("amt deducted") && channel)
        ) {
            return "Bank transfer"
        }
        return null
    }

    internal fun extractMerchant(text: String): String? {
        opaqueBankTransferMerchant(text)?.let { return it }

        val atm = atmWithdrawnAtPattern.matcher(text)
        if (atm.find()) {
            val place = atm.group(1)
                ?.trim()
                ?.trimStart('+')
                ?.trimEnd('.', ',', ';', ' ')
                ?.trim()
            if (!place.isNullOrBlank() && isAcceptableMerchant(place, text, atm.start(1))) {
                return "ATM $place"
            }
        }

        val vpa = toVpaPattern.matcher(text)
        if (vpa.find()) {
            val handle = vpa.group(1)
                ?.trim()
                ?.trimEnd('.', ',', ';', ' ')
                ?.trim()
            if (!handle.isNullOrBlank() && handle.contains('@')) return handle
        }

        for (p in merchantPatterns) {
            val m = p.matcher(text)
            while (m.find()) {
                val value = m.group(1)
                    ?.trim()
                    ?.trimStart('+')
                    ?.trimEnd('.', ',', ';', ' ')
                    ?.trim()
                    ?: continue
                if (isAcceptableMerchant(value, text, m.start())) return value
            }
        }
        return null
    }

    /** Reject helpline numbers, currency fragments, and "SMS BLOCK … to <phone>" captures. */
    internal fun isAcceptableMerchant(value: String, fullText: String, matchStart: Int): Boolean {
        if (value.length < 2) return false
        if (value.equals("UPI", ignoreCase = true)) return false
        if (currencyPrefix.matcher(value).find()) return false
        val digitsOnly = value.replace(Regex("""[\s\-]"""), "")
        if (barePhone.matcher(digitsOnly).matches()) return false
        val lower = value.lowercase(Locale.US)
        if (lower.endsWith("to block") || lower == "block") return false
        val contextStart = (matchStart - 48).coerceAtLeast(0)
        val before = fullText.substring(contextStart, matchStart)
        if (blockToContext.matcher(before).find()) return false
        return true
    }

    fun normalizeMerchantKey(raw: String): String {
        val cleaned = raw.lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
        return cleaned.ifBlank { "unknown" }
    }

    /**
     * Extracts a bank/UPI transaction reference (Ref / UTR / RRN / Txn ID).
     * Returns null for UMRN-only messages so monthly mandate debits stay distinct.
     */
    fun extractTxnRef(body: String): String? {
        val text = body.replace('\n', ' ')
        for (p in txnRefPatterns) {
            val m = p.matcher(text)
            if (m.find()) {
                val ref = m.group(1)?.trim()?.uppercase(Locale.US) ?: continue
                if (ref.length >= 6) return ref
            }
        }
        return null
    }

    /**
     * Stable key for deduplicating the same transaction across re-sent SMS / alternate
     * DLT headers. Prefer txn-ref + amount; fall back to a hash of the normalized body
     * plus a minute bucket so date-less identical bodies (e.g. HDFC "Amt Deducted NEFT")
     * in different months do not collide.
     */
    fun dedupKey(body: String, amountPaise: Long, dateMs: Long = 0L): String {
        val ref = extractTxnRef(body)
        if (ref != null) return "ref:$ref:$amountPaise"
        val normalized = body.lowercase(Locale.US)
            .replace(Regex("""\s+"""), " ")
            .trim()
        val minute = if (dateMs > 0L) dateMs / 60_000L else 0L
        return "body:${normalized.hashCode().toUInt().toString(16)}:$amountPaise:$minute"
    }

    /** Direction-aware dedup so a shared UPI ref debit does not suppress the matching credit. */
    fun directionalDedupKey(
        body: String,
        amountPaise: Long,
        dateMs: Long,
        isCredit: Boolean,
    ): String {
        val base = dedupKey(body, amountPaise, dateMs)
        return if (isCredit) "CREDIT:$base" else "DEBIT:$base"
    }

    /**
     * Normalizes a DLT sender header to a stable key: "VM-HDFCBK-S" and "JD-HDFCBK"
     * both become "HDFCBK". Phone-number senders are kept as digits.
     */
    fun senderKey(sender: String): String {
        val s = sender.uppercase(Locale.US).trim()
        if (s.isEmpty()) return "UNKNOWN"
        val parts = s.split('-').filter { it.isNotBlank() }
        if (parts.size >= 2) {
            // Drop the 2-char route prefix and a trailing 1-char suffix if present.
            val core = parts.drop(1).filter { it.length > 1 }
            if (core.isNotEmpty()) return core.joinToString("")
        }
        return s.replace(Regex("""[^A-Z0-9]"""), "").ifBlank { "UNKNOWN" }
    }
}
