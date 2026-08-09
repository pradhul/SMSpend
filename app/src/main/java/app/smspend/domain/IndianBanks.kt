package app.smspend.domain

/**
 * Curated Indian banks for "My banks" picker, with DLT sender tokens used for detection.
 */
data class IndianBank(
    val id: String,
    val label: String,
    val senderTokens: List<String>,
)

object IndianBanks {
    /** Minimum SMS count for a bank to be auto-selected after first scan. */
    const val AUTO_SELECT_MIN_SMS = 20

    val ALL: List<IndianBank> = listOf(
        IndianBank("hdfc", "HDFC Bank", listOf("HDFCBK")),
        IndianBank("sbi", "State Bank of India", listOf("SBIINB", "SBIIN")),
        IndianBank("icici", "ICICI Bank", listOf("ICICIB")),
        IndianBank("axis", "Axis Bank", listOf("AXISBK")),
        IndianBank("kotak", "Kotak Mahindra Bank", listOf("KOTAKB")),
        IndianBank("yes", "Yes Bank", listOf("YESBNK")),
        IndianBank("idfc", "IDFC First Bank", listOf("IDFCFB")),
        IndianBank("pnb", "Punjab National Bank", listOf("PNBSMS")),
        IndianBank("boi", "Bank of India", listOf("BOIIND")),
        IndianBank("bob", "Bank of Baroda", listOf("BOBTXN", "BOBSMS", "BARODA")),
        IndianBank("canara", "Canara Bank", listOf("CANBNK")),
        IndianBank("union", "Union Bank of India", listOf("UNIONB")),
        IndianBank("indusind", "IndusInd Bank", listOf("INDUSB")),
        IndianBank("federal", "Federal Bank", listOf("FEDBNK")),
        IndianBank("cub", "City Union Bank", listOf("CUBLTD")),
        IndianBank("karnataka", "Karnataka Bank", listOf("KARBNK")),
        IndianBank("rbl", "RBL Bank", listOf("RBLBNK")),
        IndianBank("sc", "Standard Chartered", listOf("SCISMS")),
        IndianBank("hsbc", "HSBC", listOf("HSBCIN")),
        IndianBank("dbs", "DBS Bank", listOf("DBSBNK", "DIGIBK")),
        IndianBank("bandhan", "Bandhan Bank", listOf("BDNSMS", "BANDHN")),
        IndianBank("au", "AU Small Finance Bank", listOf("AUBANK")),
        IndianBank("ujjivan", "Ujjivan Small Finance Bank", listOf("UJJIVN")),
        IndianBank("equitas", "Equitas Small Finance Bank", listOf("EQUTAS")),
        IndianBank("iob", "Indian Overseas Bank", listOf("IOBSMS")),
        IndianBank("indian", "Indian Bank", listOf("INDBNK")),
        IndianBank("cbi", "Central Bank of India", listOf("CBISMS")),
        IndianBank("uco", "UCO Bank", listOf("UCOBNK")),
        IndianBank("bankmaharashtra", "Bank of Maharashtra", listOf("MAHABK")),
        IndianBank("sib", "South Indian Bank", listOf("SIBSMS")),
        IndianBank("csb", "CSB Bank", listOf("CSBBNK")),
        IndianBank("dcb", "DCB Bank", listOf("DCBBNK")),
        IndianBank("idbi", "IDBI Bank", listOf("IDBIBK")),
        IndianBank("jkb", "Jammu & Kashmir Bank", listOf("JKBANK")),
        IndianBank("kvb", "Karur Vysya Bank", listOf("KVBANK")),
        IndianBank("tmb", "Tamilnad Mercantile Bank", listOf("TMBANK")),
        IndianBank("nainital", "Nainital Bank", listOf("NTBBNK")),
    )

    private val tokenToBank: Map<String, IndianBank> = buildMap {
        for (bank in ALL) {
            for (token in bank.senderTokens) {
                put(token.uppercase(), bank)
            }
        }
    }

    fun byId(id: String): IndianBank? = ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun parseIdsCsv(csv: String): Set<String> =
        csv.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

    fun idsToCsv(ids: Collection<String>): String =
        ids.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().sorted().joinToString(",")

    /**
     * Count SMS per bank from raw sender addresses; return banks meeting [AUTO_SELECT_MIN_SMS].
     */
    fun detectFromSenders(senders: Collection<String>, minSms: Int = AUTO_SELECT_MIN_SMS): Set<String> {
        val counts = mutableMapOf<String, Int>()
        for (sender in senders) {
            val key = sender.uppercase().replace("-", "").replace(" ", "")
            val bank = tokenToBank.entries.firstOrNull { (token, _) -> key.contains(token) }?.value
                ?: continue
            counts[bank.id] = (counts[bank.id] ?: 0) + 1
        }
        return counts.filter { it.value >= minSms }.keys
    }

    fun matchSender(sender: String): IndianBank? {
        val key = sender.uppercase().replace("-", "").replace(" ", "")
        return tokenToBank.entries.firstOrNull { (token, _) -> key.contains(token) }?.value
    }
}
