package app.smspend.data

import app.smspend.domain.SpendCategory
import app.smspend.domain.CategoryIconNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import app.smspend.util.runCatchingCancellable

sealed class GeminiKeyCheck {
    data object Ok : GeminiKeyCheck()
    data class Failed(val reason: String) : GeminiKeyCheck()
}

sealed class GeminiResult {
    data class Success(val text: String, val model: String) : GeminiResult()
    data class Failure(val code: Int, val message: String) : GeminiResult()
}

data class GeminiReparse(
    val isDebit: Boolean,
    val amountPaise: Long?,
    val merchant: String?,
    val amountRegex: String?,
    val ignoreRegex: String?,
)

/** One SMS the built-in parsers rejected, sent to Gemini for triage. */
data class SmsForTriage(
    val id: Long,
    val sender: String,
    val body: String,
)

/** Gemini's reading of one triaged SMS. */
data class SmsTriageVerdict(
    val id: Long,
    /** [KIND_DEBIT], [KIND_CREDIT], or [KIND_NOT_TXN]. */
    val kind: String,
    val amountPaise: Long?,
    val payee: String?,
    val accountSuffix: String?,
    val ref: String?,
    val templateRegex: String?,
) {
    companion object {
        const val KIND_DEBIT = "DEBIT"
        const val KIND_CREDIT = "CREDIT"
        const val KIND_NOT_TXN = "NOT_TXN"
    }
}

data class MerchantForCategorize(
    val merchantKey: String,
    val merchantRaw: String,
    val bodySnippet: String,
)

/** One pending transaction for the batched Gemini-first categorization pass. */
data class TxForAiCategorize(
    val id: Long,
    val merchantRaw: String,
    val amountPaise: Long,
    val sender: String,
    val bodySnippet: String,
)

/**
 * Few-shot example for the batch prompt. Corrections carry the SMS snippet and the
 * category the user moved away from; merchant-memory examples are just merchant → label.
 */
data class AiCategoryExample(
    val merchant: String,
    val categoryLabel: String,
    val snippet: String = "",
    val correctedFromLabel: String? = null,
)

data class DuplicatePairCandidate(
    val pairId: String,
    val earlierId: Long,
    val laterId: Long,
    val amountPaise: Long,
    val earlierSender: String,
    val laterSender: String,
    val earlierSnippet: String,
    val laterSnippet: String,
    val earlierMerchant: String = "",
    val laterMerchant: String = "",
    val earlierAt: Long = 0L,
    val laterAt: Long = 0L,
    val earlierNote: String = "",
    val laterNote: String = "",
)

data class UpcomingSpendHint(
    val displayName: String,
    val medianPaise: Long,
    val cadenceDays: Int,
    val lastOccurredAt: Long,
    val nextExpectedAt: Long,
)

data class TrendsExplainHint(
    val categoryLabel: String,
    val thisMonthPaise: Long,
    val lastMonthPaise: Long,
    val topMerchants: List<Pair<String, Long>>,
    /** Payment counts let Gemini talk about frequency ("weekly vs every two weeks"). */
    val thisMonthTxnCount: Int = 0,
    val lastMonthTxnCount: Int = 0,
)

/**
 * One bullet of the Trends "What changed" summary.
 * [tone] drives UI color: more/less/warning/info.
 */
data class TrendsBullet(
    val text: String,
    val tone: String,
) {
    companion object {
        /** Category costs more than last month. */
        const val TONE_MORE = "more"
        /** Category costs less than last month. */
        const val TONE_LESS = "less"
        /** A recurring bill looks unpaid/overdue — shown in orange. */
        const val TONE_WARNING = "warning"
        const val TONE_INFO = "info"

        fun normalizeTone(raw: String): String = when (raw.trim().lowercase()) {
            TONE_MORE, "up", "increase" -> TONE_MORE
            TONE_LESS, "down", "decrease" -> TONE_LESS
            TONE_WARNING, "warn", "alert" -> TONE_WARNING
            else -> TONE_INFO
        }
    }
}

class GeminiCategorizer(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        /** Quota-aware order from user's AI Studio dashboard (never include retired 2.0). */
        val MODEL_CANDIDATES = listOf(
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash",
        )
        private const val COOLDOWN_MS = 60_000L
        const val BATCH_SIZE = 25

        /** Transactions per Gemini call in the batched categorization pass. */
        const val AI_BATCH_SIZE = 80
        const val DUPLICATE_NOTE = "Possible duplicate"

        /** Structured-output schema: [{"id": 12, "category": "Food & dining"}, …]. */
        private val ID_CATEGORY_ARRAY_SCHEMA: JSONObject = JSONObject()
            .put("type", "ARRAY")
            .put(
                "items",
                JSONObject()
                    .put("type", "OBJECT")
                    .put(
                        "properties",
                        JSONObject()
                            .put("id", JSONObject().put("type", "INTEGER"))
                            .put("category", JSONObject().put("type", "STRING")),
                    )
                    .put("required", JSONArray().put("id").put("category")),
            )

        /** Structured-output schema for SMS triage: [{"id", "kind", "amount_rupees", …}, …]. */
        private val SMS_TRIAGE_SCHEMA: JSONObject = JSONObject()
            .put("type", "ARRAY")
            .put(
                "items",
                JSONObject()
                    .put("type", "OBJECT")
                    .put(
                        "properties",
                        JSONObject()
                            .put("id", JSONObject().put("type", "INTEGER"))
                            .put(
                                "kind",
                                JSONObject()
                                    .put("type", "STRING")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put(SmsTriageVerdict.KIND_DEBIT)
                                            .put(SmsTriageVerdict.KIND_CREDIT)
                                            .put(SmsTriageVerdict.KIND_NOT_TXN),
                                    ),
                            )
                            .put("amount_rupees", JSONObject().put("type", "NUMBER").put("nullable", true))
                            .put("payee", JSONObject().put("type", "STRING").put("nullable", true))
                            .put("account_suffix", JSONObject().put("type", "STRING").put("nullable", true))
                            .put("ref", JSONObject().put("type", "STRING").put("nullable", true))
                            .put("template_regex", JSONObject().put("type", "STRING").put("nullable", true)),
                    )
                    .put("required", JSONArray().put("id").put("kind")),
            )

        /** Structured-output schema: [{"text": "…", "tone": "more|less|warning|info"}, …]. */
        private val TRENDS_BULLETS_SCHEMA: JSONObject = JSONObject()
            .put("type", "ARRAY")
            .put(
                "items",
                JSONObject()
                    .put("type", "OBJECT")
                    .put(
                        "properties",
                        JSONObject()
                            .put("text", JSONObject().put("type", "STRING"))
                            .put("tone", JSONObject().put("type", "STRING")),
                    )
                    .put("required", JSONArray().put("text").put("tone")),
            )

        /** Structured-output schema: [{"date": "yyyy-MM-dd", "label": "…"}, …]. */
        private val DATE_LABELS_SCHEMA: JSONObject = JSONObject()
            .put("type", "ARRAY")
            .put(
                "items",
                JSONObject()
                    .put("type", "OBJECT")
                    .put(
                        "properties",
                        JSONObject()
                            .put("date", JSONObject().put("type", "STRING"))
                            .put("label", JSONObject().put("type", "STRING")),
                    )
                    .put("required", JSONArray().put("date").put("label")),
            )
    }

    @Volatile private var preferredModel: String? = null
    private val coolingUntil = mutableMapOf<String, Long>()

    suspend fun validateApiKey(apiKey: String): GeminiKeyCheck = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext GeminiKeyCheck.Failed("API key is empty")
        when (val result = generateContent(apiKey, "Reply with exactly: ok", jsonMode = false)) {
            is GeminiResult.Success -> GeminiKeyCheck.Ok
            is GeminiResult.Failure -> when (result.code) {
                400, 403 -> GeminiKeyCheck.Failed("Invalid API key (${result.code})")
                else -> GeminiKeyCheck.Failed(result.message)
            }
        }
    }

    suspend fun categorize(
        apiKey: String,
        merchant: String,
        smsSnippet: String,
    ): SpendCategory? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val prompt = singleCategorizePrompt(merchant, smsSnippet)
        when (val result = generateContent(apiKey, prompt, jsonMode = false)) {
            is GeminiResult.Success -> parseCategoryLabel(result.text)
            is GeminiResult.Failure -> null
        }
    }

    /**
     * Batch-categorize up to [BATCH_SIZE] unique merchants.
     * Returns merchantKey → storage key (preset name or custom:…), never Other.
     */
    suspend fun categorizeBatch(
        apiKey: String,
        merchants: List<MerchantForCategorize>,
    ): Pair<Map<String, String>, GeminiResult.Failure?> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || merchants.isEmpty()) {
            return@withContext emptyMap<String, String>() to null
        }
        val batch = merchants.take(BATCH_SIZE)
        val prompt = batchCategorizePrompt(batch)
        when (val result = generateContent(apiKey, prompt, jsonMode = true)) {
            is GeminiResult.Success -> parseBatchCategoryKeys(result.text, batch.map { it.merchantKey }) to null
            is GeminiResult.Failure -> emptyMap<String, String>() to result
        }
    }

    /**
     * Gemini-first batch categorization: classifies up to [AI_BATCH_SIZE] transactions
     * against the user's own taxonomy (presets + custom labels), primed with their past
     * retag corrections as few-shot examples. Uses structured output (responseSchema) so
     * the response is guaranteed parseable JSON. Returns transaction id → storage key
     * (preset name or custom:…); ids answered "Other" are omitted.
     */
    suspend fun categorizeTransactions(
        apiKey: String,
        items: List<TxForAiCategorize>,
        customCategoryLabels: List<String>,
        examples: List<AiCategoryExample>,
        selfAliases: List<String>,
    ): Pair<Map<Long, String>, GeminiResult.Failure?> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || items.isEmpty()) {
            return@withContext emptyMap<Long, String>() to null
        }
        val batch = items.take(AI_BATCH_SIZE)
        val prompt = aiBatchPrompt(batch, customCategoryLabels, examples, selfAliases)
        val result = generateContent(
            apiKey,
            prompt,
            jsonMode = true,
            responseSchema = ID_CATEGORY_ARRAY_SCHEMA,
        )
        when (result) {
            is GeminiResult.Success ->
                parseIdCategories(result.text, batch.map { it.id }.toSet()) to null
            is GeminiResult.Failure -> emptyMap<Long, String>() to result
        }
    }

    /**
     * Plain-language explanation of why recurring merchants look due soon.
     */
    suspend fun explainUpcomingSpends(
        apiKey: String,
        items: List<UpcomingSpendHint>,
        nowMs: Long = System.currentTimeMillis(),
    ): GeminiResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext GeminiResult.Failure(400, "API key is empty")
        if (items.isEmpty()) return@withContext GeminiResult.Failure(400, "No recurring merchants")
        generateContent(apiKey, upcomingExplainPrompt(items, nowMs), jsonMode = false)
    }

    /**
     * Bullet-point Trends summary: what changed vs last month (amounts + frequency),
     * plus orange warnings for recurring bills that look unpaid this cycle.
     * Structured output guarantees parseable [{"text","tone"}] JSON.
     */
    suspend fun summarizeTrends(
        apiKey: String,
        categories: List<TrendsExplainHint>,
        overdueBills: List<UpcomingSpendHint>,
        nowMs: Long = System.currentTimeMillis(),
    ): GeminiResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext GeminiResult.Failure(400, "API key is empty")
        if (categories.isEmpty()) return@withContext GeminiResult.Failure(400, "No spend data")
        generateContent(
            apiKey,
            trendsBulletsPrompt(categories, overdueBills, nowMs),
            jsonMode = true,
            responseSchema = TRENDS_BULLETS_SCHEMA,
        )
    }

    /**
     * Labels notable spend dates for the month report (holidays/festivals/landmarks).
     * Ordinary dates are skipped by the model.
     */
    suspend fun annotateSpendDates(
        apiKey: String,
        isoDates: List<String>,
    ): GeminiResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext GeminiResult.Failure(400, "API key is empty")
        if (isoDates.isEmpty()) return@withContext GeminiResult.Failure(400, "No dates")
        generateContent(
            apiKey,
            dateNotesPrompt(isoDates),
            jsonMode = true,
            responseSchema = DATE_LABELS_SCHEMA,
        )
    }

    /**
     * Ask Gemini which amount/time pairs are the same real-world payment.
     * Returns pairIds confirmed as duplicates, plus any failure.
     */
    suspend fun flagDuplicatePairs(
        apiKey: String,
        pairs: List<DuplicatePairCandidate>,
    ): Pair<Set<String>, GeminiResult.Failure?> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || pairs.isEmpty()) {
            return@withContext emptySet<String>() to null
        }
        val batch = pairs.take(BATCH_SIZE)
        val prompt = duplicateFlagPrompt(batch)
        when (val result = generateContent(apiKey, prompt, jsonMode = true)) {
            is GeminiResult.Success -> parseDuplicateFlags(result.text) to null
            is GeminiResult.Failure -> emptySet<String>() to result
        }
    }

    /**
     * Ask Gemini to re-read an SMS that the local parser got wrong.
     */
    suspend fun reparse(
        apiKey: String,
        smsBody: String,
        sender: String,
    ): GeminiReparse? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val prompt = """
            You are fixing an SMS expense parser for Indian bank/UPI messages.
            The parser wrongly handled this SMS. Analyze it.

            Sender: $sender
            SMS: ${smsBody.take(400)}

            Reply with ONLY a JSON object, no markdown, with these keys:
            - "is_debit": true only if this SMS confirms a COMPLETED debit/spend from the user's account. Promotions, loan offers, OTPs, balance updates, payment requests, reminders and credits are false.
            - "amount_rupees": the transaction amount in rupees as a number (not the balance/limit/due), or null.
            - "merchant": short merchant/payee name, or null.
            - "amount_regex": if is_debit, a Java regex with EXACTLY ONE capture group that extracts the amount digits (with commas) from this message format, or null.
            - "ignore_regex": if NOT a debit, a Java regex matching this promotional/non-transaction message template from this sender, or null.
        """.trimIndent()

        when (val result = generateContent(apiKey, prompt, jsonMode = true)) {
            is GeminiResult.Success -> parseReparse(result.text)
            is GeminiResult.Failure -> null
        }
    }

    /**
     * Classify SMS the built-in parsers rejected: completed debit, inbound credit, or not a
     * transaction. Also asks for a reusable per-format regex so the app can parse the next
     * message from that sender locally. Returns verdicts by id (unknown ids dropped) and the model.
     */
    suspend fun triageSms(
        apiKey: String,
        items: List<SmsForTriage>,
    ): Triple<List<SmsTriageVerdict>, String?, GeminiResult.Failure?> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || items.isEmpty()) {
            return@withContext Triple(emptyList(), null, null)
        }
        val batch = items.take(AiSmsTriage.BATCH_SIZE)
        val lines = batch.joinToString("\n") { item ->
            val body = item.body.replace('\n', ' ').take(AiSmsTriage.MAX_BODY_CHARS)
            JSONObject().put("id", item.id).put("sender", item.sender).put("sms", body).toString()
        }
        val prompt = """
            You read Indian bank, card, UPI, and wallet SMS for a personal expense tracker.
            A rule-based parser could not read the messages below. Classify each one.

            For every input id return one object:
            - "kind": "DEBIT" only if the SMS confirms money has ALREADY left the user's account or card
              (UPI payment, card spend, IMPS/NEFT sent, ATM withdrawal, auto-debit done).
              "CREDIT" only if money has ALREADY arrived in the user's account (salary, refund, transfer in, interest).
              "NOT_TXN" for everything else: OTPs, offers, loan or card promotions, balance or limit updates,
              upcoming or scheduled debits, mandate setup, bill or due reminders, payment requests, failed or declined payments.
            - "amount_rupees": the transaction amount (never the balance, limit, or due amount), or null.
            - "payee": short merchant or person the money went to (DEBIT) or came from (CREDIT), or null.
            - "account_suffix": the user's account or card digits shown (e.g. "0755"), or null.
            - "ref": UPI/IMPS/NEFT reference number, or null.
            - "template_regex": for DEBIT or CREDIT only, a Java regex that matches this message FORMAT from this
              sender (not just this one message). It must use a named group (?<amount>...) for the amount digits
              (commas allowed) and may use (?<payee>...) for the payee. Keep the bank's fixed wording literal
              (e.g. "Dr\. INR "), use [\d,.]+ for numbers and lazy groups for variable text. Otherwise null.

            Messages (one JSON per line):
            $lines
        """.trimIndent()
        when (
            val result = generateContent(
                apiKey,
                prompt,
                jsonMode = true,
                responseSchema = SMS_TRIAGE_SCHEMA,
            )
        ) {
            is GeminiResult.Success ->
                Triple(parseTriageVerdicts(result.text, batch.map { it.id }.toSet()), result.model, null)
            is GeminiResult.Failure -> Triple(emptyList(), null, result)
        }
    }

    suspend fun suggestIconName(
        apiKey: String,
        categoryLabel: String,
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || categoryLabel.isBlank()) return@withContext null
        val prompt = """
            Pick the single best Material icon name for this spending category label.
            Category label: $categoryLabel
            Allowed icon names (reply with EXACTLY one name from this list, nothing else):
            ${CategoryIconNames.csv()}
        """.trimIndent()
        when (val result = generateContent(apiKey, prompt, jsonMode = false)) {
            is GeminiResult.Success -> CategoryIconNames.normalize(result.text)
            is GeminiResult.Failure -> null
        }
    }

    // --- Shared HTTP / model rotation -----------------------------------------------------------

    internal fun generateContentSync(
        apiKey: String,
        prompt: String,
        jsonMode: Boolean,
        responseSchema: JSONObject? = null,
    ): GeminiResult {
        val models = orderedModels()
        var lastFailure: GeminiResult.Failure? = null
        for (model in models) {
            if (isCooling(model)) continue
            val outcome = callModel(apiKey, model, prompt, jsonMode, responseSchema)
            when (outcome) {
                is GeminiResult.Success -> {
                    preferredModel = model
                    return outcome
                }
                is GeminiResult.Failure -> {
                    lastFailure = outcome
                    when (outcome.code) {
                        404 -> {
                            // Permanently skip until process restart by marking long cool-down.
                            coolingUntil[model] = Long.MAX_VALUE
                        }
                        429 -> {
                            coolingUntil[model] = System.currentTimeMillis() + COOLDOWN_MS
                        }
                        400, 403 -> return outcome // bad key — don't rotate
                    }
                }
            }
        }
        return lastFailure ?: GeminiResult.Failure(-1, "No Gemini models available")
    }

    private suspend fun generateContent(
        apiKey: String,
        prompt: String,
        jsonMode: Boolean,
        responseSchema: JSONObject? = null,
    ): GeminiResult = withContext(Dispatchers.IO) {
        generateContentSync(apiKey, prompt, jsonMode, responseSchema)
    }

    private fun orderedModels(): List<String> {
        val preferred = preferredModel
        return if (preferred != null && preferred in MODEL_CANDIDATES) {
            listOf(preferred) + MODEL_CANDIDATES.filter { it != preferred }
        } else {
            MODEL_CANDIDATES
        }
    }

    private fun isCooling(model: String): Boolean {
        val until = coolingUntil[model] ?: return false
        if (until == Long.MAX_VALUE) return true
        if (System.currentTimeMillis() >= until) {
            coolingUntil.remove(model)
            return false
        }
        return true
    }

    private fun callModel(
        apiKey: String,
        model: String,
        prompt: String,
        jsonMode: Boolean,
        responseSchema: JSONObject? = null,
    ): GeminiResult {
        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt)),
                    ),
                ),
            )
        if (jsonMode) {
            val config = JSONObject().put("responseMimeType", "application/json")
            if (responseSchema != null) {
                config.put("responseSchema", responseSchema)
            }
            payload.put("generationConfig", config)
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        return runCatchingCancellable {
            client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiMsg = runCatchingCancellable {
                        JSONObject(raw).optJSONObject("error")?.optString("message")
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                    val failure = GeminiResult.Failure(
                        code = response.code,
                        message = apiMsg ?: "Gemini error ${response.code} ($model)",
                    )
                    DiagLog.e(
                        "gemini",
                        "http_fail",
                        mapOf(
                            "model" to model,
                            "code" to failure.code,
                            "message" to failure.message,
                        ),
                    )
                    return@use failure
                }
                val text = JSONObject(raw)
                    .optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                    .orEmpty()
                if (text.isBlank()) {
                    DiagLog.w(
                        "gemini",
                        "empty_response",
                        mapOf(
                            "model" to model,
                            "code" to response.code,
                            "rawLen" to raw.length,
                        ),
                    )
                    GeminiResult.Failure(response.code, "Empty Gemini response ($model)")
                } else {
                    DiagLog.i(
                        "gemini",
                        "ok",
                        mapOf(
                            "model" to model,
                            "textLen" to text.length,
                        ),
                    )
                    GeminiResult.Success(text = text, model = model)
                }
            }
        }.getOrElse { e ->
            DiagLog.e(
                "gemini",
                "network_error",
                mapOf(
                    "model" to model,
                    "message" to (e.message ?: "Network error"),
                ),
            )
            GeminiResult.Failure(-1, e.message ?: "Network error")
        }
    }

    // --- Prompt builders -----------------------------------------------------------------------

    private fun categoryList(): String =
        SpendCategory.entries
            .filter {
                it != SpendCategory.OTHER &&
                    it != SpendCategory.CREDITS &&
                    it != SpendCategory.SELF_TRANSFER
            }
            .joinToString(", ") { it.label } + ", Health, Education"

    private fun singleCategorizePrompt(merchant: String, smsSnippet: String): String = """
        Classify this Indian UPI/bank debit into exactly one category.
        Categories: ${categoryList()}
        Prefer Savings & investments for RD/SIP/Groww/Zerodha/mutual funds.
        Prefer EMI & loans for Autopay SI / standing instructions / EMI.
        Prefer Bills & utilities for Bharat Connect / BBPS / utility autopay.
        Prefer Entertainment for liquor/bars/streaming.
        Prefer Self transfer only when the payee is clearly the account holder moving their own money.
        If the payee is another person's name or a bare UPI handle (P2P payment), answer Transfers.
        Prefer Health for pharmacies/hospitals/clinics; Education for schools/exams/tuition.
        Answer Other ONLY if nothing fits.
        Merchant: $merchant
        SMS: ${smsSnippet.take(280)}
        Reply with ONLY the category label, nothing else.
    """.trimIndent()

    private fun batchCategorizePrompt(batch: List<MerchantForCategorize>): String {
        val lines = batch.mapIndexed { i, m ->
            "${i + 1}. key=${m.merchantKey} | merchant=${m.merchantRaw} | sms=${m.bodySnippet.take(160)}"
        }.joinToString("\n")
        return """
            Classify each Indian UPI/bank debit into exactly one category.
            Categories: ${categoryList()}
            Prefer Savings & investments for RD/SIP/Groww/Zerodha/mutual funds.
            Prefer EMI & loans for Autopay SI / standing instructions / EMI.
            Prefer Bills & utilities for Bharat Connect / BBPS / utility autopay.
            Prefer Entertainment for liquor/bars/streaming.
            Prefer Self transfer only when the payee is clearly the account holder moving their own money.
            If the payee is another person's name or a bare UPI handle (P2P payment), answer Transfers.
            Prefer Health for pharmacies/hospitals/clinics; Education for schools/exams/tuition.
            Answer Other ONLY if nothing fits.
            Return a JSON object mapping each merchant key to a category label.
            Examples: {"rahul sharma":"Transfers","memories hospitality serv":"Food & dining","kropex auto":"Travel","apollo pharmacy":"Health","gsm education trust":"Education"}

            Items:
            $lines
        """.trimIndent()
    }

    private fun aiBatchPrompt(
        batch: List<TxForAiCategorize>,
        customCategoryLabels: List<String>,
        examples: List<AiCategoryExample>,
        selfAliases: List<String>,
    ): String {
        val presetLabels = SpendCategory.entries
            .filter {
                it != SpendCategory.OTHER &&
                    it != SpendCategory.CREDITS &&
                    it != SpendCategory.SELF_TRANSFER
            }
            .map { it.label }
        val customs = (customCategoryLabels + listOf("Health", "Education"))
            .map { it.trim() }
            .filter { label -> label.isNotBlank() && presetLabels.none { it.equals(label, ignoreCase = true) } }
            .distinctBy { it.lowercase() }

        val exampleLines = examples.joinToString("\n") { ex ->
            buildString {
                append("- \"${ex.merchant}\" → ${ex.categoryLabel}")
                if (ex.correctedFromLabel != null) {
                    append(" (user corrected this from ${ex.correctedFromLabel})")
                }
                if (ex.snippet.isNotBlank()) {
                    append(" | sms: ${ex.snippet.take(120)}")
                }
            }
        }
        val txLines = batch.joinToString("\n") { tx ->
            "- id=${tx.id} | merchant=${tx.merchantRaw} | amount=₹${tx.amountPaise / 100} | " +
                "sender=${tx.sender} | sms=${tx.bodySnippet.take(160)}"
        }
        val aliasLine = if (selfAliases.isEmpty()) {
            ""
        } else {
            "\nThe account holder's own name(s): ${selfAliases.joinToString(", ")}. " +
                "Money sent to these names is \"Self transfer\"."
        }
        val examplesBlock = if (examples.isEmpty()) {
            ""
        } else {
            """
            |
            |How THIS user categorized before — follow these over general knowledge,
            |especially the corrections:
            |$exampleLines
            """.trimMargin()
        }

        return """
            You categorize Indian bank/UPI debit SMS for a personal expense tracker.

            Preset categories (use these EXACT labels): ${presetLabels.joinToString(", ")}
            This user's custom categories (also exact labels): ${customs.joinToString(", ")}
            If nothing fits, you MAY invent ONE short new category label (1-2 words, e.g. "Pets"),
            but strongly prefer an existing label. Answer "Other" only as a last resort.

            Guidance:
            - Savings & investments: RD/SIP/Groww/Zerodha/mutual funds.
            - EMI & loans: Autopay SI / standing instructions / EMI.
            - Bills & utilities: Bharat Connect / BBPS / recharges / utility autopay.
            - Entertainment: liquor/bars/streaming subscriptions.
            - Transfers: payee is another person's name or a bare UPI handle (P2P payment).
            - Self transfer: only when the payee is clearly the account holder moving their own money.$aliasLine$examplesBlock

            Transactions to classify:
            $txLines

            Return a JSON array with EXACTLY one entry per transaction:
            [{"id":<id>,"category":"<label>"}, ...]
        """.trimIndent()
    }

    private fun duplicateFlagPrompt(batch: List<DuplicatePairCandidate>): String {
        val lines = batch.joinToString("\n\n") { p ->
            """
            pair_id=${p.pairId}
            amount_paise=${p.amountPaise}
            A merchant=${p.earlierMerchant} sender=${p.earlierSender} sms=${p.earlierSnippet.take(220)}
            B merchant=${p.laterMerchant} sender=${p.laterSender} sms=${p.laterSnippet.take(220)}
            """.trimIndent()
        }
        return """
            You compare pairs of Indian bank/UPI debit SMS that share the same amount and are close in time.
            Decide whether both SMS describe the SAME real-world payment (e.g. bank ledger + merchant confirmation, or two bank alerts for one debit).
            Return a JSON array of objects: [{"pair_id":"...","same_payment":true/false}, ...]

            Pairs:
            $lines
        """.trimIndent()
    }

    private fun upcomingExplainPrompt(items: List<UpcomingSpendHint>, nowMs: Long): String {
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val lines = items.take(8).joinToString("\n") { item ->
            val daysUntil = ((item.nextExpectedAt - nowMs) / dayMs).toInt()
            val daysSince = ((nowMs - item.lastOccurredAt) / dayMs).toInt().coerceAtLeast(0)
            val cadence = when {
                item.cadenceDays in 6..8 -> "about weekly"
                item.cadenceDays in 25..35 -> "about monthly"
                else -> "every ~${item.cadenceDays} days"
            }
            val due = when {
                daysUntil < 0 -> "overdue by ${-daysUntil}d"
                daysUntil == 0 -> "due today"
                else -> "due in ${daysUntil}d"
            }
            "- ${item.displayName}: typical ₹${item.medianPaise / 100}, $cadence, last ${daysSince}d ago, $due"
        }
        return """
            You help an Indian user understand "Likely due soon" predictions from their SMS debit history.
            These are recurring merchants detected from past spends (not a bill inbox). Explain briefly why each looks upcoming.

            Rules:
            - Use ONLY the facts below. Do not invent merchants or amounts.
            - Write 2–5 short sentences total, plain English, friendly and concrete.
            - Mention cadence (weekly/monthly) and how overdue/due timing was inferred from last spend + median gap.
            - If something looks like grocery/delivery rather than a fixed bill, say it may vary.

            Merchants:
            $lines
        """.trimIndent()
    }

    private fun trendsBulletsPrompt(
        categories: List<TrendsExplainHint>,
        overdueBills: List<UpcomingSpendHint>,
        nowMs: Long,
    ): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val dayOfMonth = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val monthName = java.text.SimpleDateFormat("MMMM", java.util.Locale.getDefault()).format(nowMs)
        val dayFmt = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault())

        val categoryLines = categories.take(8).joinToString("\n") { item ->
            val merchants = if (item.topMerchants.isEmpty()) {
                "no merchant breakdown"
            } else {
                item.topMerchants.joinToString("; ") { (name, paise) ->
                    "$name ₹${paise / 100}"
                }
            }
            "- ${item.categoryLabel}: this month so far ₹${item.thisMonthPaise / 100} across " +
                "${item.thisMonthTxnCount} payments, last month (full) ₹${item.lastMonthPaise / 100} " +
                "across ${item.lastMonthTxnCount} payments; top this month: $merchants"
        }
        val dayMs = TimeUnit.DAYS.toMillis(1)
        val overdueBlock = if (overdueBills.isEmpty()) {
            ""
        } else {
            val lines = overdueBills.take(5).joinToString("\n") { bill ->
                val daysSince = ((nowMs - bill.lastOccurredAt) / dayMs).toInt().coerceAtLeast(0)
                val cadence = when {
                    bill.cadenceDays in 6..8 -> "about weekly"
                    bill.cadenceDays in 25..35 -> "about monthly"
                    else -> "every ~${bill.cadenceDays} days"
                }
                "- ${bill.displayName}: usually ₹${bill.medianPaise / 100}, $cadence, " +
                    "last paid ${dayFmt.format(bill.lastOccurredAt)} ($daysSince days ago), " +
                    "was expected by ${dayFmt.format(bill.nextExpectedAt)}"
            }
            """
            |
            |Recurring payments that have NOT appeared this cycle (detected from past SMS, may be missed bills):
            |$lines
            """.trimMargin()
        }

        return """
            You write the "What changed" summary for an Indian user's personal spend tracker.
            All data comes from their bank/UPI debit SMS. Today is $monthName $dayOfMonth of $daysInMonth days,
            so this month is still in progress — always compare honestly using "so far".

            Category facts (this month so far vs last month full):
            $categoryLines$overdueBlock

            Write 3 to 7 bullet points as a JSON array: [{"text":"…","tone":"more|less|warning|info"}, …]

            Rules:
            - Use ONLY the facts above. Never invent categories, merchants, bills or amounts.
            - Lead with the biggest rupee changes vs last month, giving both amounts, e.g.
              "You have spent ₹2,500 on Alcohol this month so far — ₹1,500 more than all of last month (₹1,000)".
            - When payment counts changed clearly, describe frequency in brackets, e.g.
              "(about every week this month vs every two weeks last month)".
            - For usage-based recurring bills (electricity, water, gas, recharges) phrase the change simply, e.g.
              "Your electricity bill was ₹100 less than last month".
            - Add ONE bullet per missed recurring payment with tone "warning", e.g.
              "Electricity bill (~₹1,200) expected by Mon, Aug 24 hasn't been paid yet".
            - tone is "more" when the category costs more than last month, "less" when it costs less,
              "warning" only for missed recurring payments, "info" for other notable facts
              (e.g. one shop dominating a category).
            - Each bullet under 25 words, friendly and factual. No advice, no guilt-tripping.
            - Amounts in whole rupees with the ₹ sign and Indian grouping.
        """.trimIndent()
    }

    private fun dateNotesPrompt(isoDates: List<String>): String {
        val lines = isoDates.take(31).joinToString("\n")
        return """
            These are calendar dates (yyyy-MM-dd, India) on which a user's spends were recorded from SMS.
            For each date that is genuinely notable, give a very short label (1–4 words):
            an Indian public holiday or festival (e.g. "Onam", "Diwali", "Independence Day"),
            a widely known observance, or a month landmark like "Salary day" for the 1st.
            Skip ordinary dates entirely — only label a date when you are confident.

            Return a JSON array: [{"date":"yyyy-MM-dd","label":"…"}, …] with ONLY the notable dates.

            Dates:
            $lines
        """.trimIndent()
    }

    // --- Response parsers (package-visible for unit tests) -------------------------------------

    fun parseCategoryLabel(raw: String): SpendCategory {
        val cleaned = stripMarkdown(raw)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.trim('"', '\'', '.', ' ')
            .orEmpty()
        return SpendCategory.fromLabel(cleaned)
    }

    /** Maps Gemini labels to storage keys; skips blank/Other. Supports custom Health/Education. */
    fun labelToStorageKey(label: String): String? {
        val cleaned = stripMarkdown(label)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.trim('"', '\'', '.', ' ')
            .orEmpty()
        if (cleaned.isBlank() || cleaned.equals("Other", ignoreCase = true)) return null
        val preset = SpendCategory.fromLabel(cleaned)
        if (preset != SpendCategory.OTHER) return preset.name
        return SpendCategory.resolveStorageKey(cleaned, null)
    }

    fun parseBatchCategories(raw: String, expectedKeys: List<String>): Map<String, SpendCategory> {
        val keys = parseBatchCategoryKeys(raw, expectedKeys)
        return keys.mapNotNull { (k, storage) ->
            val cat = SpendCategory.fromStored(storage)
            if (SpendCategory.isCustomKey(storage) || cat == SpendCategory.OTHER) null
            else k to cat
        }.toMap()
    }

    fun parseBatchCategoryKeys(raw: String, expectedKeys: List<String>): Map<String, String> {
        val obj = parseJsonObject(raw) ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        fun putIfOk(expected: String, label: String?) {
            if (label.isNullOrBlank()) return
            val key = labelToStorageKey(label) ?: return
            out[expected] = key
        }
        for (key in expectedKeys) {
            val label = obj.optString(key).takeIf { it.isNotBlank() }
                ?: obj.keys().asSequence().firstOrNull { it.equals(key, ignoreCase = true) }
                    ?.let { obj.optString(it) }
            putIfOk(key, label)
        }
        val keyIter = obj.keys()
        while (keyIter.hasNext()) {
            val k = keyIter.next()
            if (k in out) continue
            val match = expectedKeys.firstOrNull { it.equals(k, ignoreCase = true) } ?: continue
            putIfOk(match, obj.optString(k))
        }
        return out
    }

    /**
     * Parses [{"id":12,"category":"Food & dining"}, …] → id → storage key.
     * Unknown ids and "Other" answers are dropped (caller keeps the provisional category).
     */
    fun parseIdCategories(raw: String, expectedIds: Set<Long>): Map<Long, String> {
        val arr = parseJsonArray(raw)
        if (arr == null) {
            DiagLog.w(
                "gemini",
                "unparseable_id_categories",
                mapOf(
                    "rawLen" to raw.length,
                    "expected" to expectedIds.size,
                    "snippet" to DiagLog.truncateSnippet(raw),
                ),
            )
            return emptyMap()
        }
        val out = mutableMapOf<Long, String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optLong("id", Long.MIN_VALUE)
            if (id !in expectedIds) continue
            val key = labelToStorageKey(obj.optString("category")) ?: continue
            out[id] = key
        }
        if (out.isEmpty() && expectedIds.isNotEmpty()) {
            DiagLog.w(
                "gemini",
                "empty_id_categories",
                mapOf(
                    "rawLen" to raw.length,
                    "expected" to expectedIds.size,
                ),
            )
        }
        return out
    }

    /** Parses [{"text":"…","tone":"…"}, …] → bullets; unknown tones become info. */
    fun parseTrendsBullets(raw: String): List<TrendsBullet> {
        val arr = parseJsonArray(raw) ?: return emptyList()
        val out = mutableListOf<TrendsBullet>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val text = obj.optString("text").trim().removePrefix("- ").removePrefix("• ").trim()
            if (text.isEmpty()) continue
            out += TrendsBullet(
                text = text,
                tone = TrendsBullet.normalizeTone(obj.optString("tone")),
            )
        }
        return out
    }

    /** Parses [{"date":"yyyy-MM-dd","label":"…"}, …] → date → label, dates outside the batch dropped. */
    fun parseDateLabels(raw: String, expectedDates: Set<String>): Map<String, String> {
        val arr = parseJsonArray(raw) ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val date = obj.optString("date").trim()
            val label = obj.optString("label").trim()
            if (date !in expectedDates || label.isEmpty()) continue
            out[date] = label
        }
        return out
    }

    /** Parses the triage array; drops unknown ids and unknown kinds, treats bad amounts as null. */
    fun parseTriageVerdicts(raw: String, expectedIds: Set<Long>): List<SmsTriageVerdict> {
        val arr = parseJsonArray(raw) ?: return emptyList()
        val out = mutableListOf<SmsTriageVerdict>()
        val seen = mutableSetOf<Long>()
        fun str(obj: JSONObject, key: String): String? =
            if (obj.isNull(key)) null else obj.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optLong("id", Long.MIN_VALUE)
            if (id !in expectedIds || !seen.add(id)) continue
            val kind = obj.optString("kind").trim().uppercase()
            if (kind !in setOf(
                    SmsTriageVerdict.KIND_DEBIT,
                    SmsTriageVerdict.KIND_CREDIT,
                    SmsTriageVerdict.KIND_NOT_TXN,
                )
            ) continue
            val rupees = if (obj.isNull("amount_rupees")) null else obj.optDouble("amount_rupees", Double.NaN)
            val paise = rupees?.takeIf { !it.isNaN() && it > 0 }?.let { Math.round(it * 100.0) }
            out += SmsTriageVerdict(
                id = id,
                kind = kind,
                amountPaise = paise,
                payee = str(obj, "payee"),
                accountSuffix = str(obj, "account_suffix"),
                ref = str(obj, "ref"),
                templateRegex = str(obj, "template_regex"),
            )
        }
        return out
    }

    fun parseDuplicateFlags(raw: String): Set<String> {
        val arr = parseJsonArray(raw) ?: return emptySet()
        val out = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("same_payment", false)) {
                val id = obj.optString("pair_id").takeIf { it.isNotBlank() } ?: continue
                out += id
            }
        }
        return out
    }

    private fun parseReparse(raw: String): GeminiReparse? {
        val obj = parseJsonObject(raw) ?: return null
        val rupees = if (obj.isNull("amount_rupees")) null else obj.optDouble("amount_rupees")
        return GeminiReparse(
            isDebit = obj.optBoolean("is_debit", false),
            amountPaise = rupees?.takeIf { it > 0 }?.let { Math.round(it * 100.0) },
            merchant = obj.optString("merchant").takeIf { it.isNotBlank() && it != "null" },
            amountRegex = obj.optString("amount_regex").takeIf { it.isNotBlank() && it != "null" },
            ignoreRegex = obj.optString("ignore_regex").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun stripMarkdown(text: String): String =
        text
            .replace("```json", "")
            .replace("```", "")
            .replace("**", "")
            .replace("`", "")
            .trim()

    private fun parseJsonObject(raw: String): JSONObject? {
        val cleaned = stripMarkdown(raw)
        runCatchingCancellable { JSONObject(cleaned) }.getOrNull()?.let { return it }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatchingCancellable { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }

    private fun parseJsonArray(raw: String): JSONArray? {
        val cleaned = stripMarkdown(raw)
        runCatchingCancellable { JSONArray(cleaned) }.getOrNull()?.let { return it }
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start >= 0 && end > start) {
            return runCatchingCancellable { JSONArray(cleaned.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }
}
