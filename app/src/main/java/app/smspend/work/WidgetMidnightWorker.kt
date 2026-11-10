package app.smspend.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.smspend.data.DiagLog
import app.smspend.util.DateRanges
import app.smspend.util.runCatchingCancellable
import app.smspend.widget.SpendWidgetUpdater
import java.util.concurrent.TimeUnit

/**
 * The widget only refreshed when something was scanned, so "Today" kept showing
 * yesterday's total past midnight (and "This month" the old month on the 1st).
 * This one-shot job runs just after local midnight, refreshes the widget and
 * schedules the next night. Doze may delay it a little; it still lands that morning.
 */
class WidgetMidnightWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatchingCancellable { SpendWidgetUpdater.update(applicationContext) }
            .onFailure { DiagLog.w("widget", "midnight_refresh_failed", mapOf("error" to it.javaClass.simpleName)) }
        schedule(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "widget_midnight_refresh"

        /** A few seconds past midnight so "today" has definitely rolled over. */
        private const val SLACK_MS = 5_000L

        /**
         * KEEP from app start (an existing schedule is fine); REPLACE after a clock or
         * time-zone change, when the old delay points at the wrong midnight.
         */
        fun schedule(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val request = OneTimeWorkRequestBuilder<WidgetMidnightWorker>()
                .setInitialDelay(DateRanges.msUntilNextDay() + SLACK_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, policy, request)
        }
    }
}
