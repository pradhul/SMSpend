package app.smspend.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.smspend.notify.SmSpendNotifications
import app.smspend.smSpend
import app.smspend.widget.SpendWidgetUpdater
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class SmsScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.smSpend
        if (!app.prefs.autoScanEnabled.first()) return Result.success()
        val hasSms = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.READ_SMS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasSms) return Result.success()

        return runCatching {
            val result = app.repository.scanInbox(forceFullMonth = false)
            if (result.newTransactions > 0) {
                SmSpendNotifications.notifyNewSpends(applicationContext, result.newTransactions)
            }
            app.repository.processPostScanAlerts(applicationContext)
            app.repository.refreshRecurringMerchants()
            SpendWidgetUpdater.update(applicationContext)
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        const val UNIQUE_NAME = "smspend_sms_scan"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context.applicationContext)
            if (!enabled) {
                wm.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<SmsScanWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun reschedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context.applicationContext)
            wm.cancelUniqueWork(UNIQUE_NAME)
            if (enabled) {
                val request = PeriodicWorkRequestBuilder<SmsScanWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    )
                    .build()
                wm.enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            }
        }
    }
}
