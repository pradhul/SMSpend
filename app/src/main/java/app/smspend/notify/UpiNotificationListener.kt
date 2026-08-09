package app.smspend.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.smspend.smSpend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Captures UPI app notifications (PhonePe first) so payee / typed note can be merged
 * onto the matching bank SMS transaction.
 *
 * Enable via Settings → Capture UPI app notifications → system notification access.
 */
class UpiNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName !in WATCHED_PACKAGES) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val parsed = UpiNotificationParser.parse(title, text, bigText) ?: return
        val postedAt = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()

        scope.launch {
            applicationContext.smSpend.repository.captureUpiNotification(
                packageName = sbn.packageName,
                amountPaise = parsed.amountPaise,
                payee = parsed.payee,
                note = parsed.candidateNote,
                postedAt = postedAt,
            )
        }
    }

    companion object {
        /** Easy to extend to GPay / Paytm later. */
        val WATCHED_PACKAGES = setOf(
            "com.phonepe.app",
        )
    }
}
