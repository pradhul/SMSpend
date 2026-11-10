package app.smspend.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

/**
 * Emits the start of the current local day now, at every midnight, and whenever the
 * user changes the clock, date or time zone. Period windows (Today / This week /
 * This month) are recomputed from it so Home rolls over without reopening the app.
 */
object DayTicker {
    /** Midnight-aligned ticks only; pure so it can be unit tested with a fake clock. */
    fun midnightTicks(
        now: () -> Long = System::currentTimeMillis,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): Flow<Long> = flow {
        while (true) {
            val t = now()
            emit(DateRanges.startOfTodayMs(t))
            // +50ms so we land safely after midnight even with a slightly early wake-up.
            sleep(DateRanges.msUntilNextDay(t) + 50L)
        }
    }

    /** Clock / date / time-zone changes from the system. */
    private fun systemClockChanges(context: Context, now: () -> Long): Flow<Long> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(DateRanges.startOfTodayMs(now()))
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { context.applicationContext.unregisterReceiver(receiver) }
    }

    fun days(context: Context, now: () -> Long = System::currentTimeMillis): Flow<Long> =
        merge(midnightTicks(now), systemClockChanges(context, now)).distinctUntilChanged()
}
