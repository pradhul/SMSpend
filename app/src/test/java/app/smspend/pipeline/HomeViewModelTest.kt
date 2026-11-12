package app.smspend.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.smspend.ui.HomeViewModel
import app.smspend.util.DateRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Review #25 / #1: Home reflects a newly scanned SMS without leaving the screen, and
 * the month pages map to the right windows.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var g: TestGraph
    private lateinit var main: ExecutorCoroutineDispatcher
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        main = newSingleThreadContext("test-main")
        Dispatchers.setMain(main)
        g = TestGraph(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
        g.close()
    }

    @Test
    fun todayTotalUpdatesLiveAfterAScan() = runBlocking {
        val vm = HomeViewModel(g.repo, g.prefs, context)
        withTimeout(10_000) { vm.uiState.first { it.periodEndMs > 0 } }
        assertEquals(0L, vm.uiState.value.monthTotalPaise)

        // Only meaningful when "now - 1 min" is still today (not in the first minute after midnight).
        val at = maxOf(now - 60_000, DateRanges.startOfTodayMs(now))
        g.inbox.add(1, "Sent Rs.500.00 From HDFC Bank A/C x1234 To SWIGGY On 01/11/26 Ref 430512345678 Not You?", at)
        g.repo.scanInbox()

        val state = withTimeout(10_000) { vm.uiState.first { it.monthTotalPaise == 50_000L } }
        assertEquals(DateRanges.startOfTomorrowMs(now), state.periodEndMs)
    }

    @Test
    fun monthPagesMapToMonthsAndPickerToCustom() {
        val vm = HomeViewModel(g.repo, g.prefs, context)
        val months = DateRanges.homeMonths()
        assertEquals(months.size + 1, vm.pageCount())
        assertEquals(months.last(), vm.monthForPage(vm.pageCount() - 1, custom = null, monthList = months))
        assertEquals(months.first(), vm.monthForPage(1, custom = null, monthList = months))
        assertNull(vm.monthForPage(0, custom = null, monthList = months))
        assertNull(vm.monthForPage(vm.pageCount(), custom = null, monthList = months))
    }
}
