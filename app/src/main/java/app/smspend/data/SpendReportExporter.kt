package app.smspend.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.smspend.util.DateRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.smspend.util.runCatchingCancellable

/**
 * Writes the month spend report as a CSV to Downloads. CSV opens directly in
 * Google Sheets / Excel on the phone; native Google Sheets sync is a future feature
 * (see FUTURE_FEATURES.md).
 */
class SpendReportExporter(private val context: Context) {
    suspend fun exportCsv(
        report: MonthSpendReport,
        dateNotes: Map<Long, String> = emptyMap(),
    ): OtherExportResult = withContext(Dispatchers.IO) {
        if (report.rows.isEmpty()) {
            return@withContext OtherExportResult(
                count = 0,
                fileName = null,
                error = "No spends recorded this month yet",
            )
        }
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder()
        sb.append("Date,Time,Day note,Spending,Category,Tags,Amount (INR)\r\n")
        for (row in report.rows) {
            val dayStart = DateRanges.startOfTodayMs(row.occurredAt)
            sb.append(csv(dateFmt.format(Date(row.occurredAt)))).append(',')
            sb.append(csv(timeFmt.format(Date(row.occurredAt)))).append(',')
            sb.append(csv(dateNotes[dayStart].orEmpty())).append(',')
            sb.append(csv(row.merchant)).append(',')
            sb.append(csv(row.categoryLabel)).append(',')
            sb.append(csv(row.tags.joinToString(", "))).append(',')
            sb.append(csv(formatRupees(row.amountPaise))).append("\r\n")
        }
        sb.append(",,,,,Total,").append(csv(formatRupees(report.totalPaise))).append("\r\n")

        val monthStamp = SimpleDateFormat("yyyyMM", Locale.US).format(Date(report.month.startMs))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val fileName = "smspend-report-$monthStamp-$stamp.csv"
        val written = runCatchingCancellable {
            writeToDownloads(fileName, sb.toString().toByteArray(Charsets.UTF_8))
        }.getOrElse { e ->
            return@withContext OtherExportResult(
                count = report.rows.size,
                fileName = null,
                error = e.message ?: "Write failed",
            )
        }
        if (!written) {
            return@withContext OtherExportResult(
                count = report.rows.size,
                fileName = null,
                error = "Could not write to Downloads",
            )
        }
        OtherExportResult(count = report.rows.size, fileName = fileName)
    }

    private fun formatRupees(paise: Long): String {
        val whole = paise / 100
        val fraction = paise % 100
        return if (fraction == 0L) whole.toString()
        else String.format(Locale.US, "%d.%02d", whole, fraction)
    }

    private fun csv(value: String): String {
        if (value.isEmpty()) return ""
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    private fun writeToDownloads(fileName: String, bytes: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
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
