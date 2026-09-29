package app.smspend.util

/**
 * How far back an inbox scan should read.
 *
 * Incremental scans used to start at the newest SMS timestamp already seen.
 * A bank text that lands with a slightly older timestamp (delayed delivery,
 * clock skew) was then skipped forever. Re-reading an overlap window is safe:
 * inserts are ignored when that SMS id is already stored.
 */
object InboxScanWindow {
    /** Re-read this far behind the watermark so delayed SMS are not skipped. */
    const val OVERLAP_MS = 48L * 60 * 60 * 1000

    fun sinceMs(
        lastSmsDateMs: Long,
        oldestHomeMonthMs: Long,
        sinceOverrideMs: Long? = null,
        forceFullMonth: Boolean = false,
    ): Long {
        val watermarkSince = if (lastSmsDateMs <= 0L) {
            oldestHomeMonthMs
        } else {
            (lastSmsDateMs - OVERLAP_MS).coerceAtLeast(0L)
        }
        return when {
            // An explicit start widens the window; it must not narrow past the overlap.
            sinceOverrideMs != null -> minOf(sinceOverrideMs, watermarkSince)
            forceFullMonth || lastSmsDateMs <= 0L -> oldestHomeMonthMs
            else -> watermarkSince
        }
    }
}
