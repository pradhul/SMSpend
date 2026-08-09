package app.smspend

import android.app.Application
import android.content.Context
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

class SmSpendApp : Application() {
    lateinit var prefs: UserPreferences
        private set
    lateinit var repository: SpendRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        prefs = UserPreferences(this)
        val db = SmSpendDatabase.get(this)
        repository = SpendRepository(
            db = db,
            smsReader = SmsInboxReader(this),
            prefs = prefs,
        )
        SmSpendNotifications.ensureChannels(this)
        appScope.launch {
            val enabled = prefs.autoScanEnabled.first()
            SmsScanWorker.schedule(this@SmSpendApp, enabled)
            // Parser logic changed: re-check previously imported transactions once.
            if (prefs.parserVersion.first() < SmsDebitParser.VERSION) {
                runCatching { repository.repairTransactions() }
                    .onSuccess {
                        prefs.setParserVersion(SmsDebitParser.VERSION)
                        runCatching { SpendWidgetUpdater.update(this@SmSpendApp) }
                    }
            }
            runCatching { repository.refreshRecurringMerchants() }
        }
    }
}

val Context.smSpend: SmSpendApp
    get() = applicationContext as SmSpendApp
