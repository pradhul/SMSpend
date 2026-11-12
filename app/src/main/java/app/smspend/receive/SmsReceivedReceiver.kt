package app.smspend.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.smspend.data.DiagLog
import app.smspend.util.IncomingSms
import app.smspend.util.runCatchingCancellable
import app.smspend.work.SmsScanWorker

/**
 * Woken by the system for every incoming SMS, even when the app process is dead.
 *
 * The receiver does no parsing or database work. It joins the PDUs, drops messages
 * that can't be spends (OTPs, personal numbers, texts with no money words), records
 * a non-sensitive marker (sender + body fingerprint + time) and asks [SmsScanWorker]
 * to run. Bursts coalesce into one scan; the SMS text is never written to
 * WorkManager's database.
 */
class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = runCatchingCancellable { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull()
            ?.filterNotNull()
            .orEmpty()
            .map { IncomingSms.Part(sender = it.originatingAddress, body = it.messageBody) }
        val joined = IncomingSms.join(parts)
        if (joined.isNotEmpty() && joined.none { IncomingSms.isLikelyFinancial(it.sender, it.body) }) {
            DiagLog.i("receiver", "sms_skipped_not_financial", mapOf("messages" to joined.size))
            return
        }
        val sms = joined.firstOrNull { IncomingSms.isLikelyFinancial(it.sender, it.body) }
        DiagLog.i("receiver", "sms_received", mapOf("messages" to joined.size, "parts" to parts.size))
        // An unreadable broadcast still triggers a scan; it just can't wait for a specific row.
        SmsScanWorker.enqueueForIncoming(context, sms)
    }
}
