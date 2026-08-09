package app.smspend.util

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

private val indianLocale = Locale.Builder().setLanguage("en").setRegion("IN").build()

fun Long.paiseToRupeeString(): String {
    val rupees = this / 100.0
    val nf = NumberFormat.getCurrencyInstance(indianLocale)
    nf.maximumFractionDigits = if (this % 100L == 0L) 0 else 2
    return nf.format(rupees)
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
