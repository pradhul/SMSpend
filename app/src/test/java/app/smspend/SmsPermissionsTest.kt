package app.smspend

import app.smspend.SmsPermissions.Access
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsPermissionsTest {
    private fun resolve(read: Boolean, receive: Boolean, asked: Boolean, rationale: Boolean) =
        SmsPermissions.resolve(
            readGranted = read,
            receiveGranted = receive,
            askedBefore = asked,
            showRationale = rationale,
        )

    @Test
    fun bothGranted() {
        assertEquals(Access.GRANTED, resolve(read = true, receive = true, asked = true, rationale = false))
    }

    @Test
    fun upgradeWithOnlyReadSmsAsksForReceive() {
        assertEquals(Access.NEEDS_RECEIVE, resolve(read = true, receive = false, asked = false, rationale = false))
    }

    @Test
    fun neverAskedCanAsk() {
        assertEquals(Access.CAN_ASK, resolve(read = false, receive = false, asked = false, rationale = false))
    }

    @Test
    fun deniedOnceCanStillAsk() {
        assertEquals(Access.CAN_ASK, resolve(read = false, receive = false, asked = true, rationale = true))
    }

    @Test
    fun dontAskAgainIsBlocked() {
        assertEquals(Access.BLOCKED, resolve(read = false, receive = false, asked = true, rationale = false))
    }
}
