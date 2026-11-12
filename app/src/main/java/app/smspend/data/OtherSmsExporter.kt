package app.smspend.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.smspend.domain.SpendCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import app.smspend.util.runCatchingCancellable

class OtherSmsExporter(
    private val context: Context,
    private val db: SmSpendDatabase,
    private val smsReader: SmsInboxReader,
) {
    suspend fun export(allCategories: Boolean = false): OtherExportResult = withContext(Dispatchers.IO) {
        if (allCategories) {
            return@withContext exportFullInbox()
        }
        exportOtherTransactions()
    }

    /** Room "Other" category only — unchanged from prior dump behavior. */
    private suspend fun exportOtherTransactions(): OtherExportResult {
        val txs = db.transactions().listByCategory(SpendCategory.OTHER.name)
        if (txs.isEmpty()) {
            return OtherExportResult(count = 0, fileName = null, error = "No Other transactions")
        }
        val fullById = runCatchingCancellable { smsReader.readByIds(txs.map { it.smsId }) }
            .getOrDefault(emptyMap())
        val iso = utcIsoFormatter()
        val fileName = dumpFileName("smspend-other")
        val items = JSONArray()
        for (tx in txs) {
            val full = fullById[tx.smsId]
            val body = full?.body?.takeIf { it.isNotBlank() } ?: tx.bodySnippet
            val sender = full?.sender?.takeIf { it.isNotBlank() } ?: tx.sender
            items.put(
                JSONObject()
                    .put("txId", tx.id)
                    .put("smsId", tx.smsId)
                    .put("sender", sender)
                    .put("dateIso", iso.format(Date(tx.occurredAt)))
                    .put("amountPaise", tx.amountPaise)
                    .put("merchantRaw", tx.merchantRaw)
                    .put("merchantKey", tx.merchantKey)
                    .put("category", tx.category)
                    .put("type", tx.type)
                    .put("note", tx.note)
                    .put("statusTag", tx.statusTag)
                    .put("cardLast4", tx.cardLast4)
                    .put("sourceKey", tx.sourceKey)
                    .put("dedupKey", tx.dedupKey)
                    .put("body", body),
            )
        }
        return writeDump(
            fileName = fileName,
            root = JSONObject()
                .put("device", Build.MODEL)
                .put("exportedAt", iso.format(Date()))
                .put("scope", "other")
                .put("count", txs.size)
                .put("items", items),
            count = txs.size,
        )
    }

    /**
     * Full device inbox dump (no date lookback). Does not change Room / scan behavior.
     * Enriches with Room transaction fields when an SMS was already imported.
     */
    private suspend fun exportFullInbox(): OtherExportResult {
        val messages = runCatchingCancellable { smsReader.readSince(0L) }.getOrElse { e ->
            return OtherExportResult(
                count = 0,
                fileName = null,
                error = e.message ?: "Could not read inbox",
            )
        }
        if (messages.isEmpty()) {
            return OtherExportResult(count = 0, fileName = null, error = "No SMS in inbox")
        }
        val txBySmsId = db.transactions().listAll().associateBy { it.smsId }
        val iso = utcIsoFormatter()
        val fileName = dumpFileName("smspend-inbox")
        val items = JSONArray()
        for (sms in messages) {
            val tx = txBySmsId[sms.id]
            val obj = JSONObject()
                .put("smsId", sms.id)
                .put("sender", sms.sender)
                .put("dateIso", iso.format(Date(sms.dateMs)))
                .put("body", sms.body)
            if (tx != null) {
                obj.put("txId", tx.id)
                    .put("amountPaise", tx.amountPaise)
                    .put("merchantRaw", tx.merchantRaw)
                    .put("merchantKey", tx.merchantKey)
                    .put("category", tx.category)
                    .put("type", tx.type)
                    .put("note", tx.note)
                    .put("statusTag", tx.statusTag)
                    .put("cardLast4", tx.cardLast4)
                    .put("sourceKey", tx.sourceKey)
                    .put("dedupKey", tx.dedupKey)
            }
            items.put(obj)
        }
        return writeDump(
            fileName = fileName,
            root = JSONObject()
                .put("device", Build.MODEL)
                .put("exportedAt", iso.format(Date()))
                .put("scope", "inbox")
                .put("count", messages.size)
                .put("items", items),
            count = messages.size,
        )
    }

    private fun writeDump(fileName: String, root: JSONObject, count: Int): OtherExportResult {
        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        val written = runCatchingCancellable { writeToDownloads(fileName, bytes) }.getOrElse { e ->
            return OtherExportResult(
                count = count,
                fileName = null,
                error = e.message ?: "Write failed",
            )
        }
        if (!written) {
            return OtherExportResult(
                count = count,
                fileName = null,
                error = "Could not write to Downloads",
            )
        }
        return OtherExportResult(count = count, fileName = fileName)
    }

    private fun utcIsoFormatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun dumpFileName(prefix: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val device = Build.MODEL
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .take(24)
            .ifBlank { "device" }
        return "$prefix-$device-$stamp.json"
    }

    private fun writeToDownloads(fileName: String, bytes: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists() && !dir.mkdirs()) return false
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            true
        }
    }
}
