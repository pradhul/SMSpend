package app.smspend.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.smspend.smSpend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import app.smspend.data.DiagLog
import app.smspend.util.runCatchingCancellable

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

        val packageName = sbn.packageName
        scope.launch {
            // An uncaught exception here would take down the whole app process.
            runCatchingCancellable {
                applicationContext.smSpend.repository.captureUpiNotification(
                    packageName = packageName,
                    amountPaise = parsed.amountPaise,
                    payee = parsed.payee,
                    note = parsed.candidateNote,
                    postedAt = postedAt,
                )
            }.onFailure {
                DiagLog.w("upi", "capture_failed", mapOf("error" to it.javaClass.simpleName))
            }
        }
    }

    /**
     * The service can be unbound and destroyed (access revoked, app updated); stop any
     * in-flight capture instead of leaking the scope. A disconnect alone may be followed
     * by a rebind of this same instance, so the scope only ends in onDestroy.
     */
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Easy to extend to GPay / Paytm later. */
        val WATCHED_PACKAGES = setOf(
            "com.phonepe.app",
        )
    }
}
