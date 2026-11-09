package app.smspend.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import app.smspend.SmsPermissionMissingException
import app.smspend.SmsPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RawSms(
    val id: Long,
    val body: String,
    val sender: String,
    val dateMs: Long,
)

class SmsInboxReader(private val context: Context) {
    suspend fun readSince(minDateMs: Long): List<RawSms> = withContext(Dispatchers.IO) {
        // Without this check a revoked permission surfaces as a vague provider error
        // (or, on some OEM ROMs, an empty cursor that looks like "0 new SMS").
        if (!SmsPermissions.canRead(context)) throw SmsPermissionMissingException()
        val uri = Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )
        val selection = "${Telephony.Sms.DATE} >= ?"
        val args = arrayOf(minDateMs.toString())
        val sort = "${Telephony.Sms.DATE} ASC"
        val out = mutableListOf<RawSms>()
        val cursor = context.contentResolver.query(uri, projection, selection, args, sort)
            ?: throw IllegalStateException("Couldn't read the SMS inbox")
        cursor.use {
            while (it.moveToNext()) {
                out += it.toRawSms()
            }
        }
        out
    }

    suspend fun readById(smsId: Long): RawSms? = withContext(Dispatchers.IO) {
        val uri = Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )
        val selection = "${Telephony.Sms._ID} = ?"
        val args = arrayOf(smsId.toString())
        context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.toRawSms() else null
        }
    }

    suspend fun readByIds(smsIds: Collection<Long>): Map<Long, RawSms> = withContext(Dispatchers.IO) {
        if (smsIds.isEmpty()) return@withContext emptyMap()
        val uri = Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )
        val out = mutableMapOf<Long, RawSms>()
        // Query in chunks to stay under SQLite variable limits.
        for (chunk in smsIds.distinct().chunked(400)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val selection = "${Telephony.Sms._ID} IN ($placeholders)"
            val args = chunk.map { it.toString() }.toTypedArray()
            context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val sms = cursor.toRawSms()
                    out[sms.id] = sms
                }
            }
        }
        out
    }

    private fun Cursor.toRawSms(): RawSms {
        val id = getLong(getColumnIndexOrThrow(Telephony.Sms._ID))
        val address = getString(getColumnIndexOrThrow(Telephony.Sms.ADDRESS)).orEmpty()
        val body = getString(getColumnIndexOrThrow(Telephony.Sms.BODY)).orEmpty()
        val date = getLong(getColumnIndexOrThrow(Telephony.Sms.DATE))
        return RawSms(id = id, body = body, sender = address, dateMs = date)
    }

    companion object {
        val inboxUri: Uri = Telephony.Sms.Inbox.CONTENT_URI
    }
}
