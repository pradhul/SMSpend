package app.smspend.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.smspend.SmsPermissionMissingException
import app.smspend.SmsPermissions
import app.smspend.data.DiagLog
import app.smspend.data.SmsInboxReader
import app.smspend.notify.SmSpendNotifications
import app.smspend.smSpend
import app.smspend.util.IncomingSms
import app.smspend.widget.SpendWidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import app.smspend.util.runCatchingCancellable

/**
 * One-time incremental inbox scan, enqueued (expedited) by
 * [app.smspend.receive.SmsReceivedReceiver] for incoming bank-like SMS.
 *
 * It runs the same [app.smspend.data.SpendRepository.scanInbox] as the in-app
 * catch-up, so inserts stay keyed on the inbox `_id` and dedup is unchanged.
 * Because the default SMS app writes the inbox row asynchronously, the worker
 * first waits briefly for the broadcast message to appear, and retries later if
 * it still hasn't.
 */
class SmsScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext.smSpend
        if (!app.prefs.autoScanEnabled.first()) {
            DiagLog.i("worker", "scan_skipped_auto_scan_off")
            return Result.success()
        }
        if (!SmsPermissions.canRead(applicationContext)) {
            // Not a silent success: log it and fail so the work shows as failed.
            DiagLog.e("worker", "scan_no_sms_permission")
            return Result.failure(workDataOf(KEY_ERROR to ERROR_NO_PERMISSION))
        }

        return try {
            var landed = true
            var lastSeq: Long? = null
            var newDebits = 0
            var newRows = 0
            // Coalesce bursts: SMS arriving while this scan runs only bump the signal, and
            // we loop until it stops changing instead of queueing one job per SMS.
            for (round in 0 until MAX_ROUNDS) {
                val signal = IncomingSmsSignal.read(applicationContext)
                landed = signal?.fingerprint == null || awaitInboxRow(signal, signal.fingerprint)
                val result = app.repository.scanInbox(forceFullMonth = false)
                newDebits += result.newDebitTransactions
                newRows += result.newTransactions
                DiagLog.i(
                    "worker",
                    "scan_worker_round",
                    mapOf(
                        "round" to round,
                        "landed" to landed,
                        "attempt" to runAttemptCount,
                        "scannedSms" to result.scannedSms,
                        "newTransactions" to result.newTransactions,
                    ),
                )
                lastSeq = signal?.seq
                if (IncomingSmsSignal.read(applicationContext)?.seq == lastSeq) break
            }
            if (newDebits > 0) {
                SmSpendNotifications.notifyNewSpends(applicationContext, newDebits)
            }
            // Incremental: only SMS that arrived since the last triage pass are considered.
            runCatchingCancellable { app.repository.runAiTriagePass(incremental = true) }
                .onFailure { e ->
                    DiagLog.e("worker", "triage_exception", mapOf("message" to (e.message ?: "unknown")))
                }
            // Gemini failures leave rows PENDING for the next pass; they must not fail the scan.
            // The pass is a no-op (one indexed query) when nothing is PENDING.
            runCatchingCancellable { app.repository.runAiCategorizationPass() }
                .onFailure { e ->
                    DiagLog.e("worker", "ai_pass_exception", mapOf("message" to (e.message ?: "unknown")))
                }
            // An SMS that arrived during the AI passes was dropped by KEEP; catch it now.
            if (IncomingSmsSignal.read(applicationContext)?.seq != lastSeq) {
                val late = app.repository.scanInbox(forceFullMonth = false)
                newRows += late.newTransactions
                if (late.newDebitTransactions > 0) {
                    SmSpendNotifications.notifyNewSpends(applicationContext, newDebits + late.newDebitTransactions)
                }
            }
            if (newRows > 0) {
                runCatchingCancellable { app.repository.refreshRecurringMerchants() }
                runCatchingCancellable { SpendWidgetUpdater.update(applicationContext) }
            }
            if (!landed && runAttemptCount < MAX_RETRIES) {
                // The SMS app hasn't saved it yet; scan again after backoff.
                DiagLog.w("worker", "sms_not_in_inbox_yet", mapOf("attempt" to runAttemptCount))
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SmsPermissionMissingException) {
            DiagLog.e("worker", "scan_no_sms_permission")
            Result.failure(workDataOf(KEY_ERROR to ERROR_NO_PERMISSION))
        } catch (e: Exception) {
            DiagLog.e("worker", "scan_worker_fail", mapOf("message" to (e.message ?: "unknown")))
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "scan failed")))
            }
        }
    }

    /** Below Android 12 expedited work runs as a foreground service and needs a notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            SmSpendNotifications.ID_SCAN_FOREGROUND,
            SmSpendNotifications.scanInProgress(applicationContext),
        )

    private suspend fun awaitInboxRow(signal: IncomingSmsSignal.Snapshot, fingerprint: Int): Boolean {
        val reader = SmsInboxReader(applicationContext)
        for (wait in INBOX_POLL_DELAYS_MS) {
            if (wait > 0) delay(wait)
            val recent = reader.readSince(signal.receivedAt - INBOX_LOOKBACK_MS)
            if (IncomingSms.isInInbox(recent, signal.sender, fingerprint)) return true
        }
        return false
    }

    companion object {
        /** Unique name for the receiver-triggered scan chain. */
        const val UNIQUE_NAME = "smspend_sms_received"

        /** Periodic 30-minute scans from <= 1.2.0; cancelled on start so upgrades drop them. */
        private val LEGACY_PERIODIC_NAMES = listOf("smspend_sms_scan", "smspend_sms_scan_v2")

        const val KEY_ERROR = "error"
        const val ERROR_NO_PERMISSION = "sms_permission_missing"

        private const val MAX_RETRIES = 3
        private const val MAX_ROUNDS = 5
        private const val INBOX_LOOKBACK_MS = 10L * 60 * 1000
        private val INBOX_POLL_DELAYS_MS = longArrayOf(0, 1_000, 2_000, 3_000, 4_000, 5_000)

        /**
         * Records the SMS (sender + fingerprint only) and makes sure one scan is queued.
         * KEEP: if a scan is already queued or running, it picks this SMS up through the
         * signal (the running worker loops while the signal keeps changing).
         */
        fun enqueueForIncoming(context: Context, sms: IncomingSms.Joined?) {
            IncomingSmsSignal.record(context, sms)
            val request = OneTimeWorkRequestBuilder<SmsScanWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Drops pending receiver-triggered scans, e.g. when auto-scan is turned off. */
        fun cancelPending(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
        }

        /** Removes the periodic worker that older versions scheduled. Safe to call every start. */
        fun cancelLegacyPeriodic(context: Context) {
            val wm = WorkManager.getInstance(context.applicationContext)
            LEGACY_PERIODIC_NAMES.forEach { wm.cancelUniqueWork(it) }
        }
    }
}
