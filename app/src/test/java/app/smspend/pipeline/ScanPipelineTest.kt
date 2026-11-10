package app.smspend.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.smspend.data.TransactionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review #25: SMS inbox → parser → dedup → Room, end to end on an in-memory DB. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ScanPipelineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var g: TestGraph
    private val now = System.currentTimeMillis()

    @Before fun setUp() { g = TestGraph(context) }
    @After fun tearDown() { g.close() }

    private suspend fun debits(): List<TransactionEntity> =
        g.db.transactions().listAll().filter { it.type == TransactionEntity.TYPE_DEBIT }

    @Test
    fun debitsAreStoredAndNoiseIsSkipped() = runBlocking {
        g.inbox.add(1, "Sent Rs.500.00 From HDFC Bank A/C x1234 To SWIGGY On 01/11/26 Ref 430512345678 Not You? Call 18002586161", now - 60_000)
        g.inbox.add(2, "Your A/c XX1234 is debited with INR 75.00 on 01-11-26 for UPI txn to Rapido Bike. Avl Bal INR 3,210.00 -IDFC FIRST Bank", now - 50_000, "AX-IDFCFB-S")
        g.inbox.add(3, "123456 is your OTP for a txn of Rs 999 at AMAZON. Do not share.", now - 40_000)
        g.inbox.add(4, "Your UPI transaction of Rs 500 to xyz@ybl failed. Amount if debited will be refunded in 3-5 days.", now - 30_000)

        val result = g.repo.scanInbox()

        assertEquals(4, result.scannedSms)
        val rows = debits().sortedBy { it.amountPaise }
        assertEquals(listOf(7_500L, 50_000L), rows.map { it.amountPaise })
        assertEquals(listOf("Rapido Bike", "SWIGGY"), rows.map { it.merchantRaw })
    }

    @Test
    fun rescanAndResentSmsDoNotDuplicate() = runBlocking {
        val body = "Sent Rs.500.00 From HDFC Bank A/C x1234 To SWIGGY On 01/11/26 Ref 430512345678 Not You? Call 18002586161"
        g.inbox.add(1, body, now - 60_000)
        g.repo.scanInbox()
        // Same alert again under another DLT header (same ref + amount), then a plain rescan.
        g.inbox.add(2, body, now - 30_000, "JD-HDFCBK-T")
        g.repo.scanInbox()
        g.repo.scanInbox(forceFullMonth = true)

        assertEquals(1, debits().size)
    }

    @Test
    fun lateSmsIsPickedUpByTheNextIncrementalScan() = runBlocking {
        g.inbox.add(1, "Sent Rs.100.00 From HDFC Bank A/C x1234 To SWIGGY On 01/11/26 Ref 430500000001 Not You?", now - 120_000)
        g.repo.scanInbox()
        g.inbox.add(2, "Sent Rs.200.00 From HDFC Bank A/C x1234 To ZOMATO On 01/11/26 Ref 430500000002 Not You?", now - 10_000)
        val second = g.repo.scanInbox()

        assertTrue("second scan inserted ${second.newTransactions}", second.newTransactions >= 1)
        assertEquals(setOf(10_000L, 20_000L), debits().map { it.amountPaise }.toSet())
    }
}
