package app.smspend.work

import android.content.Context
import app.smspend.util.IncomingSms

/**
 * Tiny marker the SMS receiver leaves for [SmsScanWorker]: which message to wait for
 * (sender + body fingerprint, never the text) and a sequence number so a running
 * worker can tell that more SMS arrived while it was scanning.
 *
 * SharedPreferences rather than DataStore: the receiver must write synchronously
 * from onReceive without starting a coroutine. The file is app-private and excluded
 * from backups.
 */
internal object IncomingSmsSignal {
    private const val FILE = "smspend_incoming_signal"
    private const val KEY_SEQ = "seq"
    private const val KEY_SENDER = "sender"
    private const val KEY_FINGERPRINT = "fingerprint"
    private const val KEY_RECEIVED_AT = "received_at"
    private const val KEY_HAS_MESSAGE = "has_message"

    data class Snapshot(
        val seq: Long,
        val sender: String,
        /** Null when the broadcast couldn't be read; the worker then just scans. */
        val fingerprint: Int?,
        val receivedAt: Long,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun record(context: Context, sms: IncomingSms.Joined?, now: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        val next = p.getLong(KEY_SEQ, 0L) + 1
        p.edit()
            .putLong(KEY_SEQ, next)
            .putLong(KEY_RECEIVED_AT, now)
            .putBoolean(KEY_HAS_MESSAGE, sms != null)
            .putString(KEY_SENDER, sms?.sender.orEmpty())
            .putInt(KEY_FINGERPRINT, sms?.let { IncomingSms.bodyFingerprint(it.body) } ?: 0)
            // commit(): the worker may start before an apply() would hit disk.
            .commit()
    }

    /** Null until the receiver has recorded anything. */
    fun read(context: Context): Snapshot? {
        val p = prefs(context)
        val seq = p.getLong(KEY_SEQ, 0L)
        if (seq == 0L) return null
        return Snapshot(
            seq = seq,
            sender = p.getString(KEY_SENDER, "").orEmpty(),
            fingerprint = if (p.getBoolean(KEY_HAS_MESSAGE, false)) p.getInt(KEY_FINGERPRINT, 0) else null,
            receivedAt = p.getLong(KEY_RECEIVED_AT, 0L),
        )
    }
}
