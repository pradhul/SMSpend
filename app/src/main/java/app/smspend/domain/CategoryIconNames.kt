package app.smspend.domain

/** Stable Material icon ids shared by Gemini prompts and UI mapping. */
object CategoryIconNames {
    val ALL: List<String> = listOf(
        "Category",
        "Restaurant",
        "LocalGroceryStore",
        "ShoppingBag",
        "LocalGasStation",
        "DirectionsCar",
        "DirectionsBus",
        "DirectionsBike",
        "Flight",
        "Train",
        "ReceiptLong",
        "AccountBalance",
        "AccountBalanceWallet",
        "TrendingUp",
        "Movie",
        "SportsEsports",
        "SwapHoriz",
        "Pets",
        "FitnessCenter",
        "MedicalServices",
        "LocalHospital",
        "LocalPharmacy",
        "School",
        "Home",
        "Coffee",
        "PhoneAndroid",
        "Wifi",
        "WorkOutline",
        "ChildCare",
        "VolunteerActivism",
        "Storefront",
        "Build",
    )

    fun csv(): String = ALL.joinToString(", ")

    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.lineSequence().first()
            .trim()
            .trim('"', '\'', '.', ',', ' ', '`')
            .substringBefore(' ')
            .substringBefore('\n')
        return ALL.firstOrNull { it.equals(cleaned, ignoreCase = true) }
    }
}
