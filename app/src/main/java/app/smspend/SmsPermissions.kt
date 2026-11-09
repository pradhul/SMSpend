package app.smspend

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Runtime SMS permissions the app needs: read the inbox and be woken for new SMS. */
object SmsPermissions {
    val REQUIRED: Array<String> = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
    )

    fun canRead(context: Context): Boolean = granted(context, Manifest.permission.READ_SMS)

    fun canReceive(context: Context): Boolean = granted(context, Manifest.permission.RECEIVE_SMS)

    fun allGranted(context: Context): Boolean = REQUIRED.all { granted(context, it) }

    /** Whether the app has asked for SMS access before (needed to tell "never asked" from "Don't ask again"). */
    fun wasRequested(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REQUESTED, false)

    fun markRequested(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REQUESTED, true)
            .apply()
    }

    /**
     * What the UI should offer.
     *
     * [showRationale] is `shouldShowRequestPermissionRationale(READ_SMS)`. It is
     * false both before the first request and after "Don't ask again", so
     * [askedBefore] separates the two. Once blocked, the system dialog no longer
     * appears and the only way back is the app's settings page.
     */
    fun resolve(
        readGranted: Boolean,
        receiveGranted: Boolean,
        askedBefore: Boolean,
        showRationale: Boolean,
    ): Access = when {
        readGranted && receiveGranted -> Access.GRANTED
        // Upgrading users already granted READ_SMS; RECEIVE_SMS is in the same
        // group, so requesting it is granted without a dialog.
        readGranted -> Access.NEEDS_RECEIVE
        askedBefore && !showRationale -> Access.BLOCKED
        else -> Access.CAN_ASK
    }

    enum class Access { GRANTED, NEEDS_RECEIVE, CAN_ASK, BLOCKED }

    private const val PREFS = "smspend_permissions"
    private const val KEY_REQUESTED = "sms_requested"

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** Thrown when an inbox read is attempted without READ_SMS, so callers can't mistake it for "0 SMS". */
class SmsPermissionMissingException :
    SecurityException("SMS permission is off. Allow SMS access for SMSpend to read bank SMS.")
