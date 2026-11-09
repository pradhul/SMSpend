package app.smspend.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.smspend.data.DiagLog
import app.smspend.util.IncomingSms
import app.smspend.work.SmsScanWorker

/**
 * Woken by the system for every incoming SMS, even when the app process is dead.
 *
 * The receiver does no parsing or database work itself: it joins the PDUs so the
 * worker can recognise the message, then enqueues an expedited one-time
 * [SmsScanWorker]. The worker runs the normal incremental inbox scan, so stored
 * rows keep the inbox `_id` and the existing smsId-based dedup still applies.
 */
class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull()
            ?.filterNotNull()
            .orEmpty()
            .map { IncomingSms.Part(sender = it.originatingAddress, body = it.messageBody) }
        val joined = IncomingSms.join(parts)
        DiagLog.i("receiver", "sms_received", mapOf("messages" to joined.size, "parts" to parts.size))
        // An unreadable broadcast still triggers a scan; it just can't wait for a specific row.
        SmsScanWorker.enqueueForIncoming(context, joined.firstOrNull())
    }
}
