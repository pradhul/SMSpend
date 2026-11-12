package app.smspend.util

import kotlin.math.abs

/**
 * Full amount: ₹ + Indian grouping + paise when non-zero, e.g. ₹1,20,000 or ₹486.50.
 * Built on [formatIndianGrouped] so every screen groups digits the same way,
 * independent of the device's ICU data.
 */
fun Long.paiseToRupeeString(): String {
    val magnitude = abs(this)
    val body = "₹" + formatIndianGrouped(magnitude / 100) + paiseFraction(magnitude)
    return if (this < 0) "-$body" else body
}

/** Whole-rupee amount with Indian digit grouping, e.g. ₹1,20,000. */
fun Long.paiseToCompactRupees(): String {
    val whole = this / 100
    return "₹${formatIndianGrouped(whole)}"
}

/**
 * Formats an integer with Indian digit grouping: last 3 digits, then groups of 2
 * — e.g. 120000 → "1,20,000", 3200000 → "32,00,000".
 */
internal fun formatIndianGrouped(value: Long): String {
    val negative = value < 0
    val digits = abs(value).toString()
    if (digits.length <= 3) return if (negative) "-$digits" else digits

    val last3 = digits.takeLast(3)
    var rest = digits.dropLast(3)
    val groups = mutableListOf<String>()
    while (rest.length > 2) {
        groups.add(0, rest.takeLast(2))
        rest = rest.dropLast(2)
    }
    if (rest.isNotEmpty()) groups.add(0, rest)

    val formatted = groups.joinToString(",") + "," + last3
    return if (negative) "-$formatted" else formatted
}

/**
 * The one place an amount written in an SMS / notification / text field becomes paise:
 * "1,23,456.78", "2,000", "50.0", "₹ 99", "Rs.12". Exact decimal arithmetic (no
 * float rounding), Indian or western grouping. Null for anything that isn't a positive
 * amount with at most two decimals.
 */
fun rupeesToPaise(raw: String?): Long? {
    if (raw == null) return null
    val cleaned = raw.trim()
        .removePrefix("₹")
        .let { RUPEE_PREFIX.replaceFirst(it, "") }
        .replace(",", "")
        .replace(" ", "")
    if (cleaned.isEmpty() || !PLAIN_DECIMAL.matches(cleaned)) return null
    return try {
        val paise = java.math.BigDecimal(cleaned).movePointRight(2).longValueExact()
        paise.takeIf { it > 0 }
    } catch (_: ArithmeticException) {
        null
    }
}

private val RUPEE_PREFIX = Regex("""^(?i)(?:rs\.?|inr)\s*""")
private val PLAIN_DECIMAL = Regex("""^\d+(?:\.\d{1,2})?$""")

/** Fractional part as two digits, or "" for whole rupees. */
private fun paiseFraction(paise: Long): String {
    val p = abs(paise % 100)
    return if (p == 0L) "" else ".${p.toString().padStart(2, '0')}"
}

/**
 * Short amount for sentences: Indian grouping, whole rupees from ₹1,000 up, paise
 * below that. No currency symbol (callers write "₹$x"). 123456789 → "12,34,568".
 */
fun Long.paiseToPlainRupees(): String {
    val negative = this < 0
    val magnitude = abs(this)
    val body = if (magnitude >= 100_000L) {
        formatIndianGrouped((magnitude + 50) / 100)
    } else {
        formatIndianGrouped(magnitude / 100) + paiseFraction(magnitude)
    }
    return if (negative) "-$body" else body
}
