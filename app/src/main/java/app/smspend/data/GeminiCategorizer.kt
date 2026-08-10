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

data class MerchantForCategorize(
    val merchantKey: String,
    val merchantRaw: String,
    val bodySnippet: String,
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
)

data class UpcomingSpendHint(
    val displayName: String,
    val medianPaise: Long,
    val cadenceDays: Int,
    val lastOccurredAt: Long,
    val nextExpectedAt: Long,
)

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
        const val DUPLICATE_NOTE = "Possible duplicate"
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
    ): GeminiResult {
        val models = orderedModels()
        var lastFailure: GeminiResult.Failure? = null
        for (model in models) {
            if (isCooling(model)) continue
            val outcome = callModel(apiKey, model, prompt, jsonMode)
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
    ): GeminiResult = withContext(Dispatchers.IO) {
        generateContentSync(apiKey, prompt, jsonMode)
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
            payload.put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json"),
            )
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        return runCatching {
            client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val apiMsg = runCatching {
                        JSONObject(raw).optJSONObject("error")?.optString("message")
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                    return@use GeminiResult.Failure(
                        code = response.code,
                        message = apiMsg ?: "Gemini error ${response.code} ($model)",
                    )
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
                    GeminiResult.Failure(response.code, "Empty Gemini response ($model)")
                } else {
                    GeminiResult.Success(text = text, model = model)
                }
            }
        }.getOrElse { e ->
            GeminiResult.Failure(-1, e.message ?: "Network error")
        }
    }

    // --- Prompt builders -----------------------------------------------------------------------

    private fun categoryList(): String =
        SpendCategory.entries
            .filter { it != SpendCategory.OTHER }
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

    private fun duplicateFlagPrompt(batch: List<DuplicatePairCandidate>): String {
        val lines = batch.joinToString("\n\n") { p ->
            """
            pair_id=${p.pairId}
            amount_paise=${p.amountPaise}
            A sender=${p.earlierSender} sms=${p.earlierSnippet.take(180)}
            B sender=${p.laterSender} sms=${p.laterSnippet.take(180)}
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
        runCatching { JSONObject(cleaned) }.getOrNull()?.let { return it }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }

    private fun parseJsonArray(raw: String): JSONArray? {
        val cleaned = stripMarkdown(raw)
        runCatching { JSONArray(cleaned) }.getOrNull()?.let { return it }
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start >= 0 && end > start) {
            return runCatching { JSONArray(cleaned.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }
}
