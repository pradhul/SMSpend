package app.smspend

import android.app.Application
import android.content.Context
import app.smspend.data.DiagLog
import app.smspend.data.SmsDebitParser
import app.smspend.data.SmsInboxReader
import app.smspend.data.SmSpendDatabase
import app.smspend.data.SpendRepository
import app.smspend.data.UserPreferences
import app.smspend.notify.SmSpendNotifications
import app.smspend.widget.SpendWidgetUpdater
import app.smspend.work.SmsScanWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import app.smspend.util.runCatchingCancellable

class SmSpendApp : Application() {
    lateinit var prefs: UserPreferences
        private set
    lateinit var repository: SpendRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        prefs = UserPreferences(this)
        val db = SmSpendDatabase.get(this)
        repository = SpendRepository(
            db = db,
            smsReader = SmsInboxReader(this),
            prefs = prefs,
        )
        SmSpendNotifications.ensureChannels(this)
        // New SMS now arrive through SmsReceivedReceiver; drop the 30-minute
        // periodic scan that versions <= 1.2.0 scheduled.
        SmsScanWorker.cancelLegacyPeriodic(this)
        appScope.launch {
            // Parser logic changed: re-check previously imported transactions once.
            if (prefs.parserVersion.first() < SmsDebitParser.VERSION) {
                runCatchingCancellable {
                    repository.repairTransactions()
                    // Repair only re-reads stored rows; templates the old parser skipped need a rescan.
                    if (hasSmsPermission()) {
                        repository.scanInbox(forceFullMonth = true, sinceOverrideMs = backfillSinceMs())
                    }
                }
                    .onSuccess {
                        prefs.setParserVersion(SmsDebitParser.VERSION)
                        runCatchingCancellable { SpendWidgetUpdater.update(this@SmSpendApp) }
                    }
            }
            runCatchingCancellable { repository.refreshRecurringMerchants() }
        }
    }

    private fun hasSmsPermission(): Boolean = SmsPermissions.canRead(this)

    private fun backfillSinceMs(): Long =
        Calendar.getInstance().apply {
            clear()
            set(SmsDebitParser.BACKFILL_SINCE_YEAR, SmsDebitParser.BACKFILL_SINCE_MONTH - 1, 1)
        }.timeInMillis
}

val Context.smSpend: SmSpendApp
    get() = applicationContext as SmSpendApp
