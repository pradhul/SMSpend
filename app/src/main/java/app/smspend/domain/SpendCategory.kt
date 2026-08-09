package app.smspend.domain

import java.util.Locale

enum class SpendCategory(val label: String) {
    FOOD_DINING("Food & dining"),
    GROCERY("Grocery"),
    SHOPPING("Shopping"),
    FUEL("Fuel"),
    TRAVEL("Travel"),
    BILLS("Bills & utilities"),
    EMI("EMI & loans"),
    SAVINGS("Savings & investments"),
    ENTERTAINMENT("Entertainment"),
    TRANSFERS("Transfers"),
    WITHDRAWALS("Withdrawals"),
    SELF_TRANSFER("Self transfer"),
    OTHER("Other");

    /** False for money set aside or moved between own accounts — not consumption spend. */
    val countsAsSpend: Boolean
        get() = this != SAVINGS && this != SELF_TRANSFER

    /** True only for real savings/investments — not self transfers that fund them. */
    val countsAsSaved: Boolean
        get() = this == SAVINGS

    companion object {
        private const val CUSTOM_PREFIX = "custom:"

        private val legacyAliases = mapOf(
            "FOOD_DELIVERY" to FOOD_DINING,
            "FOOD DELIVERY" to FOOD_DINING,
            "FOOD & DINING" to FOOD_DINING,
            "BILLS & UTILITIES" to BILLS,
            "EMI & LOANS" to EMI,
            "SAVINGS & INVESTMENTS" to SAVINGS,
            "SELF TRANSFER" to SELF_TRANSFER,
            "SELF-TRANSFER" to SELF_TRANSFER,
            "WITHDRAWAL" to WITHDRAWALS,
            "ATM" to WITHDRAWALS,
        )

        fun countsAsSpend(storedKey: String): Boolean {
            if (isCustomKey(storedKey)) return true
            return fromStored(storedKey).countsAsSpend
        }

        fun countsAsSaved(storedKey: String): Boolean {
            if (isCustomKey(storedKey)) return false
            return fromStored(storedKey).countsAsSaved
        }

        fun isSelfTransfer(storedKey: String): Boolean =
            !isCustomKey(storedKey) && fromStored(storedKey) == SELF_TRANSFER

        fun fromLabel(label: String): SpendCategory {
            val trimmed = label.trim()
            if (trimmed.isBlank()) return OTHER
            entries.firstOrNull { it.label.equals(trimmed, ignoreCase = true) }?.let { return it }
            entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it }
            legacyAliases[trimmed.uppercase(Locale.US)]?.let { return it }
            return OTHER
        }

        /** Resolve a stored key to a preset, or OTHER if custom/unknown. */
        fun fromStored(value: String): SpendCategory {
            if (isCustomKey(value)) return OTHER
            entries.firstOrNull { it.name == value }?.let { return it }
            legacyAliases[value.uppercase(Locale.US)]?.let { return it }
            return fromLabel(value)
        }

        fun storageKey(category: SpendCategory): String = category.name

        fun customStorageKey(label: String): String {
            val clean = label.trim().replace(Regex("""\s+"""), " ")
            require(clean.isNotBlank()) { "Category label blank" }
            return CUSTOM_PREFIX + clean.lowercase(Locale.US)
        }

        fun isCustomKey(value: String): Boolean =
            value.startsWith(CUSTOM_PREFIX, ignoreCase = true)

        fun displayLabel(stored: String): String {
            if (isCustomKey(stored)) {
                val raw = stored.substringAfter(':').trim()
                return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }
            return fromStored(stored).label
        }

        /** Normalize user typing / chip selection into a storage key. */
        fun resolveStorageKey(typedOrLabel: String, selectedPreset: SpendCategory?): String {
            val typed = typedOrLabel.trim()
            if (typed.isNotBlank()) {
                entries.firstOrNull {
                    it.label.equals(typed, ignoreCase = true) || it.name.equals(typed, ignoreCase = true)
                }?.let { return it.name }
                return customStorageKey(typed)
            }
            return (selectedPreset ?: OTHER).name
        }
    }
}

/** Home / navigation category bucket (preset or custom). */
data class CategoryBucket(
    val key: String,
    val label: String,
) {
    companion object {
        fun fromStored(stored: String) = CategoryBucket(
            key = stored,
            label = SpendCategory.displayLabel(stored),
        )

        fun fromPreset(category: SpendCategory) = CategoryBucket(
            key = category.name,
            label = category.label,
        )
    }
}
