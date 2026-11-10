package app.smspend.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import app.smspend.util.runCatchingCancellable
import app.smspend.work.WidgetMidnightWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Clock / time-zone changes (exempt from the implicit-broadcast limits) and
 * DATE_CHANGED (delivered while the app process is alive): refresh the widget now
 * and re-aim the midnight job at the new local midnight.
 */
class DateChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Unit
            else -> return
        }
        val appContext = context.applicationContext
        runCatchingCancellable { WidgetMidnightWorker.schedule(appContext, ExistingWorkPolicy.REPLACE) }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                runCatchingCancellable { SpendWidgetUpdater.update(appContext) }
            } finally {
                pending.finish()
            }
        }
    }
}
