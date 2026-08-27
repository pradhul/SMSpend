package app.smspend.data

import app.smspend.domain.SpendCategory

object CategoryRules {
    private data class Rule(val needles: List<String>, val category: SpendCategory)

    private val rules = listOf(
        Rule(
            listOf(
                "rd installment",
                "recurring deposit",
                "transfer to rd",
                "new fd",
                " sip",
                "sip ",
                "groww",
                "zerodha",
                "kuvera",
                "coin by zerodha",
                "mutual fund",
                "nps contribution",
                "ppf ",
            ),
            SpendCategory.SAVINGS,
        ),
        Rule(
            listOf(
                "self transfer",
                "own account",
            ),
            SpendCategory.SELF_TRANSFER,
        ),
        Rule(
            listOf(
                " standing instruction",
                "emi ",
                " loan emi",
                "home loan",
                "personal loan",
            ),
            SpendCategory.EMI,
        ),
        Rule(
            listOf(
                "bharat connect",
                "bharat connec",
                "bbps",
                "electricity",
                "bescom",
                "act fibernet",
                "jio fiber",
                "air fiber",
                "airtel",
                "vi ",
                "vodafone",
                "bsnl",
                "gas bill",
                "water bill",
                "utilities",
                "tata play",
                "assurance",
                "tataaia",
                "insurance",
                "charges for non-maintenance",
                "tds of",
                "google play",
                "canva",
                "cursor",
                "debit card charge",
            ),
            SpendCategory.BILLS,
        ),
        Rule(
            listOf(
                "liquor",
                "liquors",
                "wine shop",
                "beer",
                " bar ",
                "pub ",
                "distillery",
                "spotify",
                "netflix",
                "sonyliv",
                "jiohotstar",
                "hotstar",
                "zee entertainment",
                "prime video",
                "wynk",
                "booze",
            ),
            SpendCategory.ENTERTAINMENT,
        ),
        Rule(
            listOf(
                "swiggy",
                "zomato",
                "eatfit",
                "dunzo",
                "biryani",
                "biriyani",
                "restaurant",
                "cafe",
                "dominos",
                "mcdonald",
                "hungerbox",
                "bakery",
                "cake",
                "dosa",
            ),
            SpendCategory.FOOD_DINING,
        ),
        Rule(
            listOf(
                "instamart",
                "blinkit",
                "blink commerce",
                "zepto",
                "bigbasket",
                "big basket",
                "jiomart",
                "dmart",
                "nature basket",
                "licious",
                "star bazaar",
                "hyper market",
                "rice traders",
                "provision store",
                "fruits",
            ),
            SpendCategory.GROCERY,
        ),
        Rule(
            listOf(
                "amazon",
                "flipkart",
                "myntra",
                "ajio",
                "meesho",
                "nykaa",
                "jewellery",
                "jewelry",
                "khazana",
                "malabar gold",
                "zudio",
                "cosmetics",
                "stationary",
                "stationery",
            ),
            SpendCategory.SHOPPING,
        ),
        Rule(
            listOf("hpcl", "bpcl", "iocl", "indian oil", "petrol", "diesel", "fuel"),
            SpendCategory.FUEL,
        ),
        Rule(
            listOf(
                "uber",
                "ola",
                "rapido",
                "irctc",
                "makemytrip",
                "goibibo",
                "indigo",
                "airindia",
                "redbus",
                "fastag",
                "toll paid",
                "metro rail",
                "airbnb",
                "getyourguide",
                "nhai parking",
            ),
            SpendCategory.TRAVEL,
        ),
        Rule(
            listOf(
                "withdrawn rs",
                "atm txn reversed",
            ),
            SpendCategory.WITHDRAWALS,
        ),
        Rule(
            listOf(
                "salary",
                "neft txn",
                "neft",
                "imps to",
                "imps ",
                "rtgs",
                "money transfer via",
                "for money transfer",
            ),
            SpendCategory.TRANSFERS,
        ),
    )

    private val transferChannelNeedles = listOf(
        "neft txn",
        "neft",
        "imps to",
        "imps ",
        "rtgs",
        "money transfer via",
        "for money transfer",
        "online banking",
    )

    private val jewelleryNeedles = listOf(
        "jewellery",
        "jewelry",
        "khazana",
        "malabar gold",
    )

    fun match(merchantKey: String, merchantRaw: String, body: String): SpendCategory? {
        val hay = haystack(merchantKey, merchantRaw, body)
        return rules.firstOrNull { rule -> rule.needles.any { hay.contains(it) } }?.category
    }

    /**
     * Categories that must win over sticky [merchant_memory] (ATM, RD/investments,
     * opaque NEFT/IMPS, self-name, jewellery misfiled as savings).
     */
    fun beatsMemory(
        resolved: SpendCategory,
        merchantKey: String,
        merchantRaw: String,
        body: String,
    ): Boolean {
        val hay = haystack(merchantKey, merchantRaw, body)
        return when (resolved) {
            SpendCategory.WITHDRAWALS,
            SpendCategory.SAVINGS,
            SpendCategory.SELF_TRANSFER,
            -> true
            SpendCategory.TRANSFERS ->
                transferChannelNeedles.any { hay.contains(it) }
            SpendCategory.SHOPPING ->
                jewelleryNeedles.any { hay.contains(it) }
            else -> false
        }
    }

    private fun haystack(merchantKey: String, merchantRaw: String, body: String): String =
        "$merchantKey $merchantRaw $body".lowercase()
}
