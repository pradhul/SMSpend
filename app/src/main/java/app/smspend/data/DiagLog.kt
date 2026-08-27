package app.smspend.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * On-device rolling JSONL log for categorization diagnostics.
 * Two files: [CURRENT] + [PREVIOUS], rotate when current exceeds [MAX_BYTES].
 */
object DiagLog {
    private const val MAX_BYTES = 512L * 1024
    private const val CURRENT = "categorize.log"
    private const val PREVIOUS = "categorize.log.1"
    const val SNIPPET_MAX = 120

    @Volatile
    private var dir: File? = null

    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "smspend-diaglog").apply { isDaemon = true }
    }

    fun init(context: Context) {
        val d = File(context.applicationContext.filesDir, "logs")
        if (!d.exists()) d.mkdirs()
        dir = d
    }

    fun i(tag: String, msg: String, data: Map<String, Any?> = emptyMap()) =
        log("I", tag, msg, data)

    fun w(tag: String, msg: String, data: Map<String, Any?> = emptyMap()) =
        log("W", tag, msg, data)

    fun e(tag: String, msg: String, data: Map<String, Any?> = emptyMap()) =
        log("E", tag, msg, data)

    fun truncateSnippet(text: String, max: Int = SNIPPET_MAX): String =
        if (text.length <= max) text else text.take(max) + "…"

    fun approxSizeBytes(): Long = synchronized(lock) {
        val d = dir ?: return 0L
        currentFile(d).length() + previousFile(d).length()
    }

    fun approxSizeLabel(): String {
        val bytes = approxSizeBytes()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /** Older rotate file first, then current — chronological for typical append order. */
    fun exportBytes(): ByteArray = synchronized(lock) {
        val d = dir ?: return ByteArray(0)
        val prev = previousFile(d)
        val cur = currentFile(d)
        val out = java.io.ByteArrayOutputStream(
            (prev.length() + cur.length() + 1).toInt().coerceAtLeast(0),
        )
        if (prev.exists() && prev.length() > 0) {
            val prevBytes = prev.readBytes()
            out.write(prevBytes)
            if (prevBytes.last() != '\n'.code.toByte() && cur.exists() && cur.length() > 0) {
                out.write('\n'.code)
            }
        }
        if (cur.exists() && cur.length() > 0) {
            out.write(cur.readBytes())
        }
        out.toByteArray()
    }

    fun clear() {
        synchronized(lock) {
            val d = dir ?: return
            currentFile(d).delete()
            previousFile(d).delete()
        }
    }

    fun exportToDownloads(context: Context): OtherExportResult {
        val bytes = exportBytes()
        if (bytes.isEmpty()) {
            return OtherExportResult(count = 0, fileName = null, error = "Log is empty")
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val device = Build.MODEL
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .take(24)
            .ifBlank { "device" }
        val fileName = "smspend-categorize-$device-$stamp.jsonl"
        val written = runCatching { writeToDownloads(context, fileName, bytes) }.getOrElse { e ->
            return OtherExportResult(
                count = 0,
                fileName = null,
                error = e.message ?: "Write failed",
            )
        }
        if (!written) {
            return OtherExportResult(
                count = 0,
                fileName = null,
                error = "Could not write to Downloads",
            )
        }
        val lines = bytes.count { it == '\n'.code.toByte() }.coerceAtLeast(1)
        return OtherExportResult(count = lines, fileName = fileName)
    }

    private fun log(level: String, tag: String, msg: String, data: Map<String, Any?>) {
        if (dir == null) return
        val line = buildLine(level, tag, msg, data)
        writer.execute {
            synchronized(lock) {
                appendLine(line)
            }
        }
    }

    private fun buildLine(level: String, tag: String, msg: String, data: Map<String, Any?>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val obj = JSONObject()
            .put("ts", iso.format(Date()))
            .put("level", level)
            .put("tag", tag)
            .put("msg", msg)
        if (data.isNotEmpty()) {
            val dataObj = JSONObject()
            for ((k, v) in data) {
                when (v) {
                    null -> dataObj.put(k, JSONObject.NULL)
                    is Number, is Boolean, is String -> dataObj.put(k, v)
                    else -> dataObj.put(k, v.toString())
                }
            }
            obj.put("data", dataObj)
        }
        return obj.toString()
    }

    private fun appendLine(line: String) {
        val d = dir ?: return
        if (!d.exists()) d.mkdirs()
        val cur = currentFile(d)
        if (cur.exists() && cur.length() >= MAX_BYTES) {
            rotate(d)
        }
        cur.appendText(line + "\n", Charsets.UTF_8)
        if (cur.length() >= MAX_BYTES) {
            rotate(d)
        }
    }

    private fun rotate(d: File) {
        val cur = currentFile(d)
        val prev = previousFile(d)
        if (prev.exists()) prev.delete()
        if (cur.exists()) cur.renameTo(prev)
    }

    private fun currentFile(d: File) = File(d, CURRENT)
    private fun previousFile(d: File) = File(d, PREVIOUS)

    private fun writeToDownloads(context: Context, fileName: String, bytes: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-ndjson")
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
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.exists() && !downloads.mkdirs()) return false
            FileOutputStream(File(downloads, fileName)).use { it.write(bytes) }
            true
        }
    }
}
