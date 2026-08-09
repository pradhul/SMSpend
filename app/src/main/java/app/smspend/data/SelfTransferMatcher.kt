package app.smspend.data

import app.smspend.domain.SpendCategory
import java.util.Locale

/**
 * Fuzzy payee ↔ self-name matching for marking account-to-account moves.
 * Alias "pradhul" matches "PRADHUL DEV K P" and "PRADHULDEV K P".
 */
object SelfTransferMatcher {
    private const val MIN_ALIAS_LEN = 4

    private val businessWords = setOf(
        "traders", "stores", "store", "enterprises", "enterprise", "pvt", "ltd",
        "limited", "services", "service", "hospital", "pharmacy", "restaurant",
        "cafe", "mart", "bazaar", "shop", "jewellers", "jewellery", "motors",
        "auto", "electronics", "solutions", "technologies", "private", "company",
        "hotel", "clinic", "diagnostics", "labs", "associates", "industries",
    )

    fun parseAliases(csv: String): List<String> =
        csv.split(',')
            .map { it.trim() }
            .filter { it.length >= MIN_ALIAS_LEN }

    fun matches(payeeRaw: String, aliasesCsv: String): Boolean {
        val aliases = parseAliases(aliasesCsv)
        if (aliases.isEmpty()) return false
        val payeeNorm = normalize(payeeRaw)
        if (payeeNorm.length < MIN_ALIAS_LEN) return false
        if (hasBusinessWord(payeeNorm)) return false

        val payeeTokens = tokens(payeeNorm)
        val payeeCompact = payeeTokens.joinToString("")
        if (payeeTokens.isEmpty()) return false

        for (alias in aliases) {
            val aliasNorm = normalize(alias)
            if (aliasNorm.length < MIN_ALIAS_LEN) continue
            val aliasTokens = tokens(aliasNorm)
            if (aliasTokens.isEmpty()) continue

            val tokenMatch = aliasTokens.all { at ->
                payeeTokens.any { pt ->
                    pt == at ||
                        (at.length >= MIN_ALIAS_LEN && pt.startsWith(at)) ||
                        (pt.length >= MIN_ALIAS_LEN && at.startsWith(pt))
                }
            }
            if (tokenMatch) return true

            val aliasCompact = aliasTokens.joinToString("")
            if (aliasCompact.length >= MIN_ALIAS_LEN &&
                (payeeCompact.contains(aliasCompact) ||
                    (payeeCompact.length >= MIN_ALIAS_LEN && aliasCompact.contains(payeeCompact)))
            ) {
                return true
            }
        }
        return false
    }

    private fun normalize(raw: String): String =
        raw.lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9\s]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun tokens(normalized: String): List<String> =
        normalized.split(' ').filter { it.isNotBlank() }

    private fun hasBusinessWord(normalized: String): Boolean {
        val toks = tokens(normalized)
        return toks.any { it in businessWords }
    }

    /**
     * Rules win over self-name matching except for plain transfers: an RD installment
     * addressed to your own name ("Transfer To RD XX0715 SWATHI P V") must stay Savings,
     * while a bare "Sent … To SWATHI P V" (no rule, or generic NEFT/IMPS transfer rule)
     * becomes Self transfer.
     */
    fun resolveWithRules(
        merchantKey: String,
        merchantRaw: String,
        body: String,
        aliasesCsv: String,
    ): SpendCategory? {
        val ruled = CategoryRules.match(merchantKey, merchantRaw, body)
        val selfName = matches(merchantRaw, aliasesCsv) || matches(merchantKey, aliasesCsv)
        if (selfName && (ruled == null || ruled == SpendCategory.TRANSFERS)) {
            return SpendCategory.SELF_TRANSFER
        }
        return ruled
    }
}
