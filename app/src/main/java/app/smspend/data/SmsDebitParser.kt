package app.smspend.data

import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import app.smspend.util.rupeesToPaise

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
    const val VERSION = 12

    /** Inbox rescan start on a VERSION bump (Canara "Acct … Dr. INR" template began Jul 2026). */
    const val BACKFILL_SINCE_YEAR = 2026
    const val BACKFILL_SINCE_MONTH = 7

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
        "e-mandate", "emandate", "mandate created", "mandate registered",
        "mandate set", "insufficient", "declined", "reversed", "reversal", "autopay setup",
        "txn failed", "transaction failed", "payment failed", "could not be processed",
    )

    /** "…transaction of Rs 500 to x@ybl failed", "…for a UPI transaction that has failed". */
    private val failedPattern = Pattern.compile("""(?i)\b(?:has\s+)?failed\b""")

    /**
     * Bill / statement wording. These only block when there is no completed-debit
     * evidence: "Rs 300 debited … Click here to see your statement" is still a spend,
     * while "card statement: total due Rs 45,000" is not.
     */
    private val softBlockers = listOf(
        "due on", "is due", "overdue", "min due", "minimum due", "total due", "min amt due",
        "statement",
    )
    private val otpPattern = Pattern.compile("""(?i)\botp\b""")

    /**
     * Promotional smell; blocks messages that lack strong (past-tense) debit evidence.
     * Checked on the text with links removed: banks put fraud-report / details URLs in
     * the footer of real debits ("Not you, https://…"), so a link alone isn't promo.
     */
    private val promoBlockers = listOf(
        "loan", "offer", "pre-approved", "pre approved", "preapproved", "apply now",
        "cashback", "discount", "voucher", "eligible", "congratulations", "reward",
        "insurance", "recharge", "renew", "upto", "up to", "hurry", "click",
        "interest rate", "win ", "t&c", "tnc",
    )
    private val urlPattern = Pattern.compile("""(?i)\b(?:https?://|www\.)\S+""")

    /** BoB: "Rs.200.00 Dr. from A/C XXXXXX1234 and Cr. to abc@ybl." */
    private val amountDrFromPattern = Pattern.compile(
        """(?i)(?:rs\.?|inr|₹)\s*:?\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)\s+Dr\.?\s+from\b""",
    )
    private val crToPayeePattern = Pattern.compile(
        """(?i)\bCr\.?\s+to\s+([A-Za-z0-9._@&' -]{2,60}?)(?=\s*[.;,(]\s|\s*[.;]?$|\s+(?:on|via|ref|upi)\b)""",
    )

    /** SBI: "A/C X1234 debited by 50.0 on date …" — amount without a currency marker. */
    private val bareDebitedAmountPattern = Pattern.compile(
        """(?i)\bdebited\s+(?:by|with|for)\s+(?:rs\.?|inr|₹)?\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)(?![0-9])""",
    )

    /** Personal (non-DLT) senders: "+919812345678", "09812345678". Banks never use these. */
    private val personalSenderPattern = Pattern.compile("""^\+?\d{10,13}$""")

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
        "upto", "up to", "cashback", "reward",
    )

    /** "worth" is a voucher/offer figure, except "purchase worth Rs 1,050 at DMART". */
    private val worthContext = Pattern.compile("""(?i)\bworth\b""")
    private val spendWorthContext = Pattern.compile("""(?i)\b(?:purchase|transaction|txn|payment)\s+worth\b""")
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
        // Axis: "UPI/P2M/430512345678/ZOMATO" — payee is the segment after the ref.
        Pattern.compile("""(?i)\bUPI/P2[AM]/\d+/([A-Za-z0-9 .&'@_-]{2,40}?)(?=/|\s+not\b|\s{2}|\s*$)"""),
        // ICICI: "…debited for Rs 240.00 on 01-Nov-26; ZOMATO credited."
        Pattern.compile("""(?i);\s*([A-Za-z0-9 .&'@_-]{2,60}?)\s+credited\b"""),
        // \b: "to" inside "ZOMATO credited" isn't the word "to".
        Pattern.compile(
            """(?i)\b(?:to|at|towards|for|paid to|sent to|VPA\s*)[:\s]+([A-Za-z0-9 .&'@_+-]{2,80}?)(?=\s+(?:via|on|from|ref|upi|not you|avbl|umrn|avl|\d{1,2}[/-])|\s*\(|[.!,]|$)""",
        ),
        Pattern.compile("""(?i)info[:\s]+([A-Za-z0-9 .&_-]{2,80}?)(?=\.\s*Avl|\.\s*Available|$)"""),
        Pattern.compile("""(?i)UPI/([A-Za-z0-9._-]{2,40})"""),
    )

    private val currencyPrefix = Pattern.compile("""(?i)^(INR|Rs\.?|₹)\b""")
    private val barePhone = Pattern.compile("""^\d{10,}$""")
    private val leadingPhone = Pattern.compile("""^\+?\d{10,}\b""")
    private val embeddedAmount = Pattern.compile("""(?i)(?:\b(?:rs\.?|inr)|₹)\s*\d""")
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
            // "Reference number 9988776655": the word "number" is not the ref. The ref must
            // carry a digit, or unrelated payments would share a dedup key.
            """(?i)(?:UPI\s*Ref(?:\s*No)?|Ref(?:erence)?(?:\s*(?:No|Number|Num))?|UTR|RRN|Txn\s*(?:ID|No))\b[.:#\s]*((?=[A-Za-z]*\d)[A-Za-z0-9]{6,25})\b""",
        ),
        // Canara: "UPI: 130598832435" / "IMPS: 626616662833"
        Pattern.compile("""(?i)\b(?:UPI|IMPS)\s*:\s*(\d{9,})"""),
    )

    /**
     * Canara-style ledger debit (2026+):
     * "Acct XXXX0755 Dr. INR 65.00 on 02/10/26 to Dream Tool R; UPI: 130598832435; Bal INR …"
     * Bare "Dr." elsewhere is not treated as a debit hint.
     */
    private val accountDrDebitPattern = Pattern.compile(
        """(?i)\b(?:Acct|A/c)\s+[X*.\d]+\s+Dr\.?\s+(?:INR|Rs\.?|₹)\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)(?![0-9])""",
    )
    private val accountDrPayeePattern = Pattern.compile(
        """(?i)\bDr\.?\s+(?:INR|Rs\.?|₹)\s*[\d,.]+.*?\bto\s+([^;]{2,60}?)\s*;""",
    )

    /** SBI YONO: "debited Rs.5,000.00 for transfer to Aryabd Ac Xx0755 dt 01.10.26" */
    private val transferToNamedAccountPattern = Pattern.compile(
        """(?i)\btransfer to\s+([A-Za-z][A-Za-z .&'-]{1,40}?)\s+(?:A/?c|Acct)\b""",
    )

    /** Destination account digits on an outbound transfer ("to … Ac Xx0755", "a/c XXX755 credited"). */
    private val destinationAccountPatterns = listOf(
        Pattern.compile("""(?i)\bto\s+[A-Za-z .&'-]{0,40}?\b(?:A/?c|Acct)\s*(?:no\.?\s*)?[X*.]*(\d{3,6})\b"""),
        Pattern.compile("""(?i)\b(?:A/?c|Acct)\s*(?:no\.?\s*)?[X*.]*(\d{3,6})\s+(?:is\s+)?credited\b"""),
    )

    /** Account digits that receive money in a credit SMS ("credited to A/c XXXX0755", "A/c XX755 Cr INR"). */
    private val creditedAccountPatterns = listOf(
        Pattern.compile("""(?i)\b(?:A/?c|Acct)\s*(?:no\.?\s*)?[X*.]*(\d{3,6})\s+Cr\.?\s"""),
        Pattern.compile("""(?i)\bcredited\s+to\s+(?:your\s+)?(?:A/?c|Acct|account)\s*(?:no\.?\s*)?[X*.]*(\d{3,6})\b"""),
        Pattern.compile("""(?i)\b(?:A/?c|Acct|account)\s*(?:no\.?\s*)?[X*.]*(\d{3,6})\s+(?:is\s+|has\s+been\s+)?credited\b"""),
    )

    /** Account digits debited in a ledger SMS ("Acct XXXX0755 Dr.", "a/c no. XXXX3296 is debited"). */
    private val debitedAccountPatterns = listOf(
        Pattern.compile("""(?i)\b(?:A/?c|Acct)\s*(?:no\.?\s*)?[X*.]*(\d{3,6})\s+Dr\.?\s"""),
        Pattern.compile("""(?i)\b(?:A/?c|Acct|account)\s*(?:no\.?\s*)?[X*.]*(\d{3,6})\s+(?:is\s+|has\s+been\s+)?debited\b"""),
        Pattern.compile("""(?i)\bdebited\s+(?:from|to)\s+(?:your\s+)?(?:A/?c|Acct|account)\s*(?:no\.?\s*)?[X*.]*(\d{3,6})\b"""),
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

    fun isBankSender(sender: String): Boolean {
        val key = senderKey(sender)
        return bankSenderTokens.any { key.contains(it) }
    }

    fun isOtp(body: String): Boolean {
        val lower = body.lowercase(Locale.US)
        return otpPattern.matcher(lower).find() || lower.contains("one time password")
    }

    fun hasAmount(body: String): Boolean = amountPattern.matcher(body).find()

    private fun isMerchantAppSenderKey(key: String): Boolean =
        merchantAppSenderTokens.any { key.contains(it) }

    /**
     * A bank ledger SMS should replace a merchant-app confirmation that already
     * claimed the same dedup key. The reverse must not drop the bank row.
     */
    fun incomingBankReplacesDedupHolder(incomingIsBank: Boolean, holderIsBank: Boolean): Boolean =
        incomingIsBank && !holderIsBank

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
        if (failedPattern.matcher(lower).find()) return null

        // Outbound NEFT confirmation ("credited to <payee>") — before debit/credit gates,
        // because the body has no debit keyword and "credited" would otherwise reject it.
        parseNeftCreditedToPayee(text)?.let { return it }
        parseAccountDrDebit(text, sender)?.let { return it }
        parseAmountDrFrom(text, sender)?.let { return it }

        val hasStrong = strongDebitHints.any { lower.contains(it) }
        val hasWeak = weakDebitHints.any { lower.contains(it) }
        if (!hasStrong && !hasWeak) return null

        // Person-to-person chatter ("Hey I paid Rs 500 for the cab") from a phone number.
        if (!hasStrong && personalSenderPattern.matcher(sender.trim()).matches()) return null
        if (!hasStrong && softBlockers.any { lower.contains(it) }) return null
        if (creditHints.any { lower.contains(it) } && !hasStrong) return null
        if (!hasStrong) {
            val withoutLinks = urlPattern.matcher(lower).replaceAll(" ")
            if (promoBlockers.any { withoutLinks.contains(it) }) return null
        }

        val amountPaise = (extractAmountPaise(text) ?: bareDebitedAmount(text)) ?: return null
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

    /** BoB "Rs.200.00 Dr. from A/C … and Cr. to abc@ybl" — Dr. right after the amount. */
    fun parseAmountDrFrom(body: String, sender: String = ""): ParsedDebit? {
        val text = body.replace('\n', ' ').trim()
        val m = amountDrFromPattern.matcher(text)
        if (!m.find()) return null
        val amountPaise = rupeesToPaise(m.group(1)) ?: return null
        val payee = crToPayeePattern.matcher(text).let { pm ->
            if (pm.find()) pm.group(1)?.trim()?.trimEnd('.', ',', ';', ' ')?.trim() else null
        }?.takeIf { it.length >= 2 && isAcceptableMerchant(it, text, 0) }
        val merchantRaw = payee ?: sender.ifBlank { "Unknown" }
        return ParsedDebit(
            amountPaise = amountPaise,
            merchantRaw = merchantRaw.take(80),
            merchantKey = normalizeMerchantKey(merchantRaw),
        )
    }

    /** Fallback when no amount carries Rs/INR/₹: "debited by 50.0". */
    private fun bareDebitedAmount(text: String): Long? {
        val m = bareDebitedAmountPattern.matcher(text)
        if (!m.find()) return null
        return rupeesToPaise(m.group(1))
    }

    fun isNeftCreditedToPayee(body: String): Boolean =
        parseNeftCreditedToPayee(body) != null

    /** "Acct XXXX0755 Dr. INR 65.00 on … to <payee>; UPI: …" — amount is the Dr figure, not Bal. */
    fun parseAccountDrDebit(body: String, sender: String = ""): ParsedDebit? {
        val text = body.replace('\n', ' ').trim()
        val m = accountDrDebitPattern.matcher(text)
        if (!m.find()) return null
        val amountPaise = rupeesToPaise(m.group(1)) ?: return null
        val payee = accountDrPayeePattern.matcher(text).let { pm ->
            if (pm.find()) pm.group(1)?.trim()?.trimEnd('.', ',', ' ')?.trim() else null
        }?.takeIf { it.length >= 2 && isAcceptableMerchant(it, text, 0) }
        val merchantRaw = payee ?: sender.ifBlank { "Unknown" }
        return ParsedDebit(
            amountPaise = amountPaise,
            merchantRaw = merchantRaw.take(80),
            merchantKey = normalizeMerchantKey(merchantRaw),
        )
    }

    /** Destination account digits of an outbound transfer debit, or null. */
    fun extractDestinationAccount(body: String): String? =
        firstGroup(destinationAccountPatterns, body)

    /** Account digits credited by an inbound credit SMS, or null. */
    fun extractCreditedAccount(body: String): String? =
        firstGroup(creditedAccountPatterns, body)

    /** Account digits debited by a ledger debit SMS, or null. */
    fun extractDebitedAccount(body: String): String? =
        firstGroup(debitedAccountPatterns, body)

    /** Masked suffixes refer to the same account when the shorter is a suffix of the longer (≥3 digits). */
    fun sameAccountSuffix(a: String, b: String): Boolean {
        if (a.length < 3 || b.length < 3) return false
        return if (a.length >= b.length) a.endsWith(b) else b.endsWith(a)
    }

    /** True when the stored merchant is just the DLT sender id fallback (no payee in the SMS). */
    fun isSenderIdMerchant(merchantRaw: String, sender: String): Boolean =
        sender.isNotBlank() && merchantRaw.trim().equals(sender.trim(), ignoreCase = true)

    private fun firstGroup(patterns: List<Pattern>, body: String): String? {
        val text = body.replace('\n', ' ')
        for (p in patterns) {
            val m = p.matcher(text)
            if (m.find()) return m.group(1)
        }
        return null
    }

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
            val paise = rupeesToPaise(matcher.group(1)) ?: 0L
            // Context window never reaches past the previous amount, so "Avl Bal Rs X. Rs Y debited"
            // doesn't poison the second amount with the first one's balance context.
            val contextStart = (start - 28).coerceAtLeast(prevMatchEnd)
            prevMatchEnd = matcher.end()
            if (paise <= 0) continue
            val context = lower.substring(contextStart, start)
            if (badAmountContext.any { context.contains(it) }) continue
            if (worthContext.matcher(context).find() && !spendWorthContext.matcher(context).find()) continue
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

        val named = transferToNamedAccountPattern.matcher(text)
        if (named.find()) {
            val payee = named.group(1)?.trim()?.trimEnd('.', ',', ' ')?.trim()
            if (!payee.isNullOrBlank() && isAcceptableMerchant(payee, text, named.start(1))) return payee
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
                val cleaned = cleanMerchant(value)
                if (isAcceptableMerchant(cleaned, text, m.start())) return cleaned
            }
        }
        return null
    }

    private val merchantLeadNoise = Pattern.compile(
        """(?i)^(?:VPA\s+|(?:a\s+)?(?:UPI\s+)?(?:txn|transaction|payment)\s+to\s+|UPI-\d+-)""",
    )

    /** "VPA swiggy@icici" → "swiggy@icici", "UPI txn to Rapido Bike" → "Rapido Bike", "UPI-4305…-AMAZON" → "AMAZON". */
    private fun cleanMerchant(value: String): String {
        val stripped = merchantLeadNoise.matcher(value).replaceFirst("").trim()
        return stripped.ifEmpty { value }
    }

    /** Reject helpline numbers, currency fragments, and "SMS BLOCK … to <phone>" captures. */
    internal fun isAcceptableMerchant(value: String, fullText: String, matchStart: Int): Boolean {
        if (value.length < 2) return false
        if (value.equals("UPI", ignoreCase = true)) return false
        if (currencyPrefix.matcher(value).find()) return false
        val digitsOnly = value.replace(Regex("""[\s\-]"""), "")
        if (barePhone.matcher(digitsOnly).matches()) return false
        // "919951860002 Axis Bank" — helpline / block-number followed by the bank name.
        if (leadingPhone.matcher(value).find()) return false
        // A sentence that runs into an amount ("for a purchase worth Rs 1") is not a payee.
        if (embeddedAmount.matcher(value).find()) return false
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
