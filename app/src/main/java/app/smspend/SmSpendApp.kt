package app.smspend

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.core.content.ContextCompat
import app.smspend.data.DiagLog
import app.smspend.data.SmsDebitParser
import app.smspend.data.SmsInboxReader
import app.smspend.data.SmSpendDatabase
import app.smspend.data.SpendRepository
import app.smspend.data.UserPreferences
import app.smspend.notify.SmSpendNotifications
import app.smspend.widget.SpendWidgetUpdater
import app.smspend.work.SmsScanWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

class SmSpendApp : Application() {
    lateinit var prefs: UserPreferences
        private set
    lateinit var repository: SpendRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inboxWatch: ContentObserver? = null
    private var inboxCatchUp: Job? = null

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
        ensureSmsInboxWatch()
        appScope.launch {
            val enabled = prefs.autoScanEnabled.first()
            SmsScanWorker.schedule(this@SmSpendApp, enabled)
            // Parser logic changed: re-check previously imported transactions once.
            if (prefs.parserVersion.first() < SmsDebitParser.VERSION) {
                runCatching {
                    repository.repairTransactions()
                    // Repair only re-reads stored rows; templates the old parser skipped need a rescan.
                    if (hasSmsPermission()) {
                        repository.scanInbox(forceFullMonth = true, sinceOverrideMs = backfillSinceMs())
                    }
                }
                    .onSuccess {
                        prefs.setParserVersion(SmsDebitParser.VERSION)
                        runCatching { SpendWidgetUpdater.update(this@SmSpendApp) }
                    }
            }
            runCatching { repository.refreshRecurringMerchants() }
        }
    }

    /**
     * While this process is alive, read new bank SMS as they arrive instead of
     * waiting for the next periodic worker or a manual refresh.
     */
    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    private fun backfillSinceMs(): Long =
        Calendar.getInstance().apply {
            clear()
            set(SmsDebitParser.BACKFILL_SINCE_YEAR, SmsDebitParser.BACKFILL_SINCE_MONTH - 1, 1)
        }.timeInMillis

    fun ensureSmsInboxWatch() {
        if (inboxWatch != null) return
        if (!hasSmsPermission()) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scheduleInboxCatchUp()
            }
        }
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        inboxWatch = observer
    }

    private fun scheduleInboxCatchUp() {
        inboxCatchUp?.cancel()
        inboxCatchUp = appScope.launch {
            delay(1_500)
            try {
                val result = repository.scanInbox()
                val aiFound = runCatching { repository.runAiTriagePass() }.getOrNull()?.inserted ?: 0
                if (result.newTransactions + aiFound > 0) {
                    runCatching { repository.runAiCategorizationPass() }
                    runCatching { repository.refreshRecurringMerchants() }
                    runCatching { SpendWidgetUpdater.update(this@SmSpendApp) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagLog.e("scan", "inbox_watch_fail", mapOf("message" to (e.message ?: "unknown")))
            }
        }
    }
}

val Context.smSpend: SmSpendApp
    get() = applicationContext as SmSpendApp
