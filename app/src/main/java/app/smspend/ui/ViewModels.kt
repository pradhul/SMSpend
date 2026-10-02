package app.smspend.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.smspend.data.CategoryNoteEntity
import app.smspend.data.CategorySpend
import app.smspend.data.DiagLog
import app.smspend.data.GeminiKeyCheck
import app.smspend.data.OtherSmsExporter
import app.smspend.data.RecurringMerchantEntity
import app.smspend.data.SmsInboxReader
import app.smspend.data.SmSpendDatabase
import app.smspend.data.SpendRepository
import app.smspend.data.TransactionEntity
import app.smspend.data.UserPreferences
import app.smspend.data.MonthlySpendTrend
import app.smspend.data.MonthSpendReport
import app.smspend.data.SpendReportExporter
import app.smspend.data.TrendsBullet
import app.smspend.domain.RecurringDetector
import app.smspend.domain.SpendCategory
import app.smspend.ui.theme.ThemeMode
import app.smspend.util.DateRanges
import app.smspend.util.MonthWindow
import app.smspend.widget.SpendWidgetUpdater
import app.smspend.work.SmsScanWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Pager within Months: index 0 = pick-a-month, 1..N = home months (oldest → this month). */
const val HOME_PICKER_PAGE = 0

/** Quiet inbox catch-up while moving between screens. Pull-to-refresh ignores this. */
private const val QUIET_SCAN_MIN_GAP_MS = 20_000L

enum class HomeSegment {
    TODAY,
    THIS_WEEK,
    MONTHS,
}

data class DuplicateReviewState(
    val pairs: List<app.smspend.data.DuplicatePairCandidate>,
)

data class HomeUiState(
    val segment: HomeSegment = HomeSegment.TODAY,
    val months: List<MonthWindow> = emptyList(),
    val selectedPageIndex: Int = 0,
    val categoriesByPage: Map<Int, List<CategorySpend>> = emptyMap(),
    val segmentCategories: List<CategorySpend> = emptyList(),
    val categories: List<CategorySpend> = emptyList(),
    val iconNames: Map<String, String> = emptyMap(),
    val monthTotalPaise: Long = 0,
    val monthSpendPaise: Long = 0,
    val monthLabel: String = "",
    val monthTitle: String = "Today",
    val periodStartMs: Long = 0L,
    val periodEndMs: Long = 0L,
    val lastSyncLabel: String = "",
    val scanning: Boolean = false,
    val message: String? = null,
    val momPct: Int? = null,
    val priorMonthShortLabel: String? = null,
    val monthlyBudgetPaise: Long? = null,
    val expectedSoon: List<RecurringMerchantEntity> = emptyList(),
    val upcomingExplain: String? = null,
    val upcomingExplainLoading: Boolean = false,
    val customMonth: MonthWindow? = null,
    val pickerChoices: List<MonthWindow> = emptyList(),
    val duplicateReview: DuplicateReviewState? = null,
    val showMyBanks: Boolean = false,
    val detectedBankIds: Set<String> = emptySet(),
    val selectedBankIds: Set<String> = emptySet(),
    val periodNotices: List<String> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repo: SpendRepository,
    private val prefs: UserPreferences,
    private val appContext: Context,
) : ViewModel() {
    private val scanning = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val lastSyncLabel = MutableStateFlow("—")
    private val segment = MutableStateFlow(HomeSegment.TODAY)
    private val months = DateRanges.homeMonths()
    private val pageCount = months.size + 1 // picker + months
    private val thisMonthPage = pageCount - 1
    private val selectedPageIndex = MutableStateFlow(thisMonthPage)
    private val warmCenterIndex = MutableStateFlow(thisMonthPage)
    private val customMonth = MutableStateFlow<MonthWindow?>(null)
    private val duplicateReview = MutableStateFlow<DuplicateReviewState?>(null)
    private val showMyBanks = MutableStateFlow(false)
    private val detectedBankIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectedBankIds = MutableStateFlow<Set<String>>(emptySet())
    private val upcomingExplain = MutableStateFlow<String?>(null)
    private val upcomingExplainLoading = MutableStateFlow(false)
    private val scanGate = Mutex()
    private var lastQuietScanAtMs = 0L

    private val categoriesByPageFlow = combine(warmCenterIndex, customMonth, segment) { center, custom, seg ->
        Triple(center, custom, seg)
    }.flatMapLatest { (center, custom, seg) ->
        when (seg) {
            HomeSegment.TODAY -> {
                val (start, end) = DateRanges.todayWindow()
                repo.observeRangeByCategory(start, end).map { cats ->
                    mapOf(-1 to cats)
                }
            }
            HomeSegment.THIS_WEEK -> {
                val week = DateRanges.weekWindow(0)
                repo.observeRangeByCategory(week.startMs, week.endExclusiveMs).map { cats ->
                    mapOf(-1 to cats)
                }
            }
            HomeSegment.MONTHS -> {
                val indices = buildSet {
                    add(center)
                    if (center > HOME_PICKER_PAGE) add(center - 1)
                    if (center < pageCount - 1) add(center + 1)
                    add(thisMonthPage)
                    if (thisMonthPage > HOME_PICKER_PAGE + 1) add(thisMonthPage - 1)
                }.toList()
                val flows = indices.map { index ->
                    val range = monthForPage(index, custom) ?: return@map flowOf(index to emptyList<CategorySpend>())
                    repo.observeRangeByCategory(range.startMs, range.endExclusiveMs)
                        .map { cats -> index to cats }
                }
                if (flows.isEmpty()) flowOf(emptyMap())
                else combine(flows) { pairs -> pairs.toMap() }
            }
        }
    }

    private val noticesByPageFlow = combine(warmCenterIndex, customMonth, segment) { center, custom, seg ->
        Triple(center, custom, seg)
    }.flatMapLatest { (center, custom, seg) ->
        when (seg) {
            HomeSegment.TODAY -> {
                val (start, end) = DateRanges.todayWindow()
                repo.observeRangeNotices(start, end).map { mapOf(-1 to it) }
            }
            HomeSegment.THIS_WEEK -> {
                val week = DateRanges.weekWindow(0)
                repo.observeRangeNotices(week.startMs, week.endExclusiveMs).map { mapOf(-1 to it) }
            }
            HomeSegment.MONTHS -> {
                val indices = buildSet {
                    add(center)
                    if (center > HOME_PICKER_PAGE) add(center - 1)
                    if (center < pageCount - 1) add(center + 1)
                    add(thisMonthPage)
                    if (thisMonthPage > HOME_PICKER_PAGE + 1) add(thisMonthPage - 1)
                }.toList()
                val flows = indices.map { index ->
                    val range = monthForPage(index, custom) ?: return@map flowOf(index to emptyList<String>())
                    repo.observeRangeNotices(range.startMs, range.endExclusiveMs)
                        .map { notices -> index to notices }
                }
                if (flows.isEmpty()) flowOf(emptyMap())
                else combine(flows) { pairs -> pairs.toMap() }
            }
        }
    }

    private val iconNamesFlow = repo.observeCategoryIconNames()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val expectedSoonFlow = repo.observeExpectedSoon(7)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val budgetFlow = prefs.monthlyBudgetPaise
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val baseState = combine(
        combine(selectedPageIndex, categoriesByPageFlow, scanning, message, lastSyncLabel) { index, byPage, scan, msg, sync ->
            Triple(index, byPage, Triple(scan, msg, sync))
        },
        combine(customMonth, segment, duplicateReview, showMyBanks, detectedBankIds) { custom, seg, dups, banks, detected ->
            Triple(custom, seg, Triple(dups, banks, detected))
        },
        selectedBankIds,
        noticesByPageFlow,
    ) { triple, extras, selectedBanks, noticesByPage ->
        val (index, byPage, rest) = triple
        val (scan, msg, sync) = rest
        val (custom, seg, bankRest) = extras
        val (dups, showBanks, detected) = bankRest
        val period = when (seg) {
            HomeSegment.TODAY -> {
                val (s, e) = DateRanges.todayWindow()
                PeriodSnap("Today", DateRanges.formatTodaySubtitle(), s, e, byPage[-1].orEmpty())
            }
            HomeSegment.THIS_WEEK -> {
                val week = DateRanges.weekWindow(0)
                PeriodSnap(week.title(), week.subtitle(), week.startMs, week.endExclusiveMs, byPage[-1].orEmpty())
            }
            HomeSegment.MONTHS -> {
                val month = monthForPage(index, custom)
                PeriodSnap(
                    month?.title() ?: "Pick a month",
                    month?.subtitle().orEmpty(),
                    month?.startMs ?: 0L,
                    month?.endExclusiveMs ?: 0L,
                    byPage[index].orEmpty(),
                )
            }
        }
        val cats = period.cats
        val spend = cats.filter { SpendCategory.countsAsSpend(it.bucket.key) }.sumOf { it.totalPaise }
        val priorIndex = when {
            seg != HomeSegment.MONTHS -> null
            index == HOME_PICKER_PAGE -> null
            index > HOME_PICKER_PAGE + 1 -> index - 1
            else -> null
        }
        val priorCats = priorIndex?.let { byPage[it].orEmpty() }.orEmpty()
        val priorSpend = priorCats.filter { SpendCategory.countsAsSpend(it.bucket.key) }.sumOf { it.totalPaise }
        val priorMonth = priorIndex?.let { monthForPage(it, custom) }
        val mom = if (priorIndex != null) RecurringDetector.weekOverWeekPct(spend, priorSpend) else null
        val notices = when (seg) {
            HomeSegment.TODAY, HomeSegment.THIS_WEEK -> noticesByPage[-1].orEmpty()
            HomeSegment.MONTHS -> noticesByPage[index].orEmpty()
        }
        HomeUiState(
            segment = seg,
            months = months,
            selectedPageIndex = index,
            categoriesByPage = byPage,
            segmentCategories = cats,
            categories = cats,
            monthTotalPaise = cats.sumOf { it.totalPaise },
            monthSpendPaise = spend,
            monthLabel = period.subtitle,
            monthTitle = period.title,
            periodStartMs = period.startMs,
            periodEndMs = period.endMs,
            lastSyncLabel = sync,
            scanning = scan,
            message = msg,
            momPct = mom,
            priorMonthShortLabel = priorMonth?.shortLabel(),
            customMonth = custom,
            pickerChoices = DateRanges.pickerMonths(),
            duplicateReview = dups,
            showMyBanks = showBanks,
            detectedBankIds = detected,
            selectedBankIds = selectedBanks,
            periodNotices = notices,
        )
    }

    private data class PeriodSnap(
        val title: String,
        val subtitle: String,
        val startMs: Long,
        val endMs: Long,
        val cats: List<CategorySpend>,
    )

    val uiState: StateFlow<HomeUiState> = combine(
        baseState,
        iconNamesFlow,
        budgetFlow,
        expectedSoonFlow,
        combine(upcomingExplain, upcomingExplainLoading) { text, loading -> text to loading },
    ) { base, icons, budget, soon, explain ->
        val (text, loading) = explain
        base.copy(
            iconNames = icons,
            monthlyBudgetPaise = budget,
            expectedSoon = soon,
            upcomingExplain = text,
            upcomingExplainLoading = loading,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        HomeUiState(
            months = months,
            selectedPageIndex = thisMonthPage,
            pickerChoices = DateRanges.pickerMonths(),
        ),
    )

    init {
        refreshSyncLabel()
        viewModelScope.launch {
            categoriesByPageFlow.collect { byPage ->
                val keys = byPage.values.flatten().map { it.bucket.key }.distinct()
                runCatching { repo.ensureIconsForCategories(keys) }
            }
        }
        viewModelScope.launch {
            val configured = prefs.myBanksConfigured.first()
            if (!configured) {
                maybePromptMyBanks()
            } else {
                selectedBankIds.value = app.smspend.domain.IndianBanks.parseIdsCsv(prefs.myBankIds.first())
            }
        }
    }

    fun selectSegment(value: HomeSegment) {
        segment.value = value
    }

    fun pageCount(): Int = pageCount

    fun monthForPage(page: Int, custom: MonthWindow? = customMonth.value): MonthWindow? {
        return when {
            page == HOME_PICKER_PAGE -> custom
            page in 1 until pageCount -> months.getOrNull(page - 1)
            else -> null
        }
    }

    fun selectPage(index: Int) {
        selectedPageIndex.value = index.coerceIn(0, pageCount - 1)
    }

    fun warmAround(index: Int) {
        warmCenterIndex.value = index.coerceIn(0, pageCount - 1)
    }

    fun pickCustomMonth(month: MonthWindow) {
        customMonth.value = month
        selectedPageIndex.value = HOME_PICKER_PAGE
        warmCenterIndex.value = HOME_PICKER_PAGE
        segment.value = HomeSegment.MONTHS
    }

    private fun refreshSyncLabel() {
        viewModelScope.launch {
            lastSyncLabel.value = DateRanges.formatSyncTime(repo.lastSyncAt())
        }
    }

    /** Incremental inbox read when Home is shown or the app returns to the foreground. */
    fun catchUpInbox() {
        viewModelScope.launch { runScan(userInitiated = false) }
    }

    /** Pull-to-refresh. Shows the spinner and always reads, ignoring the quiet-scan gap. */
    fun refresh() {
        viewModelScope.launch { runScan(userInitiated = true) }
    }

    private suspend fun runScan(userInitiated: Boolean) {
        scanGate.withLock {
            val now = System.currentTimeMillis()
            val hadSync = repo.lastSyncAt() > 0L
            val lastScanAt = maxOf(lastQuietScanAtMs, if (hadSync) repo.lastSyncAt() else 0L)
            if (!userInitiated && hadSync && now - lastScanAt < QUIET_SCAN_MIN_GAP_MS) {
                return@withLock
            }
            val showSpinner = userInitiated || !hadSync
            if (showSpinner) {
                scanning.value = true
                message.value = null
            }
            try {
                val result = repo.scanInbox()
                if (userInitiated || result.newTransactions > 0) {
                    message.value = if (result.newTransactions == 0) {
                        "Up to date · ${result.scannedSms} SMS checked"
                    } else {
                        "Added ${result.newTransactions} spends"
                    }
                }
                refreshSyncLabel()
                if (userInitiated || result.newTransactions > 0 || !hadSync) {
                    // Fire-and-forget so the spinner isn't held on Gemini latency;
                    // rows update live via Room flows when categories land.
                    viewModelScope.launch {
                        runCatching { repo.runAiTriagePass() }
                        runCatching { repo.runAiCategorizationPass() }
                        runCatching { SpendWidgetUpdater.update(appContext) }
                    }
                    runCatching { repo.refreshRecurringMerchants() }
                    runCatching { SpendWidgetUpdater.update(appContext) }
                    maybePromptMyBanks()
                }
                lastQuietScanAtMs = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.value = e.message ?: "Scan failed"
            } finally {
                if (showSpinner) scanning.value = false
            }
        }
    }

    fun explainUpcomingSpends() {
        viewModelScope.launch {
            val items = uiState.value.expectedSoon
            if (items.isEmpty()) {
                upcomingExplain.value = "No recurring merchants detected yet."
                return@launch
            }
            upcomingExplainLoading.value = true
            upcomingExplain.value = null
            runCatching { repo.explainUpcomingSpends(items) }
                .onSuccess { upcomingExplain.value = it }
                .onFailure { upcomingExplain.value = it.message ?: "Could not explain upcoming spends" }
            upcomingExplainLoading.value = false
        }
    }

    fun syncThisPeriod() {
        viewModelScope.launch {
            scanGate.withLock {
                scanning.value = true
                message.value = null
                val state = uiState.value
                val start = if (state.periodStartMs > 0) state.periodStartMs else months.first().startMs
                val end = if (state.periodEndMs > 0) state.periodEndMs else DateRanges.endExclusiveNow()
                try {
                    val result = repo.syncPeriod(start, end)
                    message.value = buildString {
                        append("Synced · ${result.newTransactions} new")
                        if (result.recategorized > 0) append(" · ${result.recategorized} recategorized")
                        if (result.duplicatePairs.isNotEmpty()) {
                            append(" · ${result.duplicatePairs.size} possible duplicates")
                        }
                    }
                    refreshSyncLabel()
                    runCatching { repo.refreshRecurringMerchants() }
                    runCatching { SpendWidgetUpdater.update(appContext) }
                    if (result.duplicatePairs.isNotEmpty()) {
                        duplicateReview.value = DuplicateReviewState(result.duplicatePairs)
                    }
                    maybePromptMyBanks()
                    lastQuietScanAtMs = System.currentTimeMillis()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message.value = e.message ?: "Sync failed"
                } finally {
                    scanning.value = false
                }
            }
        }
    }

    fun dismissDuplicateReview() {
        duplicateReview.value = null
    }

    fun keepDuplicatePair(pairId: String) {
        val current = duplicateReview.value ?: return
        val pair = current.pairs.firstOrNull { it.pairId == pairId }
        val remaining = current.pairs.filterNot { it.pairId == pairId }
        duplicateReview.value = if (remaining.isEmpty()) null else DuplicateReviewState(remaining)
        if (pair != null) {
            viewModelScope.launch {
                repo.rememberNotDuplicatePair(pair.earlierId, pair.laterId)
            }
        }
    }

    /** Mute the later copy of a pair: keep it visible, exclude it from totals. */
    fun muteDuplicatePair(pairId: String) {
        val current = duplicateReview.value ?: return
        val pair = current.pairs.firstOrNull { it.pairId == pairId } ?: return
        val remaining = current.pairs.filterNot { it.pairId == pairId }
        duplicateReview.value = if (remaining.isEmpty()) null else DuplicateReviewState(remaining)
        viewModelScope.launch {
            repo.markAsDuplicate(pair.laterId, pair.earlierId)
            message.value = "Muted duplicate — excluded from totals"
            runCatching { SpendWidgetUpdater.update(appContext) }
        }
    }

    fun removeDuplicatePair(laterId: Long) {
        viewModelScope.launch {
            val current = duplicateReview.value ?: return@launch
            scanning.value = true
            val removed = repo.removeDuplicateLaters(listOf(laterId))
            val remaining = current.pairs.filterNot { it.laterId == laterId }
            duplicateReview.value = if (remaining.isEmpty()) null else DuplicateReviewState(remaining)
            if (removed > 0) {
                message.value = "Removed $removed duplicate"
                runCatching { SpendWidgetUpdater.update(appContext) }
            }
            scanning.value = false
        }
    }

    fun toggleBank(id: String) {
        selectedBankIds.value = selectedBankIds.value.toMutableSet().also { set ->
            if (!set.add(id)) set.remove(id)
        }
    }

    fun saveMyBanks() {
        viewModelScope.launch {
            prefs.setMyBankIds(app.smspend.domain.IndianBanks.idsToCsv(selectedBankIds.value))
            prefs.setMyBanksConfigured(true)
            showMyBanks.value = false
            message.value = "Banks saved"
            runCatching { repo.onMyBanksSaved() }
        }
    }

    fun openMyBanksEditor() {
        viewModelScope.launch {
            selectedBankIds.value = app.smspend.domain.IndianBanks.parseIdsCsv(prefs.myBankIds.first())
                .ifEmpty { detectedBankIds.value }
            showMyBanks.value = true
        }
    }

    fun dismissMyBanks() {
        viewModelScope.launch {
            // Allow skip but mark configured so we don't loop forever.
            if (selectedBankIds.value.isNotEmpty()) {
                prefs.setMyBankIds(app.smspend.domain.IndianBanks.idsToCsv(selectedBankIds.value))
            }
            prefs.setMyBanksConfigured(true)
            showMyBanks.value = false
        }
    }

    private suspend fun maybePromptMyBanks() {
        if (prefs.myBanksConfigured.first()) return
        val detected = runCatching { repo.detectMyBanksFromInbox() }.getOrDefault(emptySet())
        detectedBankIds.value = detected
        if (selectedBankIds.value.isEmpty()) {
            selectedBankIds.value = detected
        }
        showMyBanks.value = true
    }

    fun clearMessage() {
        message.value = null
    }

    fun selectedMonth(): MonthWindow =
        monthForPage(selectedPageIndex.value) ?: months.last()

    fun currentPeriodBounds(): Pair<Long, Long> {
        val s = uiState.value
        return s.periodStartMs to s.periodEndMs
    }

    class Factory(
        private val repo: SpendRepository,
        private val prefs: UserPreferences,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(repo, prefs, appContext.applicationContext) as T
    }
}

class CategoryViewModel(
    private val repo: SpendRepository,
    private val categoryKey: String,
    private val monthStartMs: Long,
    private val monthEndMs: Long,
) : ViewModel() {
    val transactions: StateFlow<List<TransactionEntity>?> =
        repo.observeCategoryTxns(categoryKey, monthStartMs, monthEndMs)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val iconName: StateFlow<String?> =
        repo.observeCategoryIconName(categoryKey)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _monthTotals = MutableStateFlow<List<Long>>(emptyList())
    val monthTotals: StateFlow<List<Long>> = _monthTotals

    val notes: StateFlow<List<CategoryNoteEntity>> =
        repo.observeCategoryNotes(categoryKey, monthStartMs)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nettedTotal: StateFlow<Long> =
        repo.observeCategoryNettedTotal(categoryKey, monthStartMs, monthEndMs)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val nettingNotice: StateFlow<String?> =
        repo.observeCategoryNettingNotice(categoryKey, monthStartMs, monthEndMs)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            runCatching { repo.ensureCustomCategoryIcon(categoryKey) }
            _monthTotals.value =
                runCatching { repo.categoryMonthTotalsYtd(categoryKey) }.getOrDefault(emptyList())
        }
    }

    fun addNote(text: String) {
        viewModelScope.launch {
            repo.addCategoryNote(categoryKey, monthStartMs, text)
        }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch {
            repo.deleteCategoryNote(id)
        }
    }

    class Factory(
        private val repo: SpendRepository,
        private val categoryKey: String,
        private val monthStartMs: Long,
        private val monthEndMs: Long,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CategoryViewModel(repo, categoryKey, monthStartMs, monthEndMs) as T
    }
}

class TrendsViewModel(
    private val repo: SpendRepository,
) : ViewModel() {
    private val _monthly = MutableStateFlow<MonthlySpendTrend?>(null)
    val monthly: StateFlow<MonthlySpendTrend?> = _monthly

    private val _summary = MutableStateFlow<List<TrendsBullet>?>(null)
    val summary: StateFlow<List<TrendsBullet>?> = _summary

    private val _summaryError = MutableStateFlow<String?>(null)
    val summaryError: StateFlow<String?> = _summaryError

    private val _summaryLoading = MutableStateFlow(false)
    val summaryLoading: StateFlow<Boolean> = _summaryLoading

    private val iconNamesFlow = repo.observeCategoryIconNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val iconNames: StateFlow<Map<String, String>> = iconNamesFlow

    /**
     * Reloads the chart without clearing it, so coming back to Trends doesn't
     * flash "Loading…". Gemini bullets are requested once until the user asks again.
     */
    fun onScreenVisible() {
        viewModelScope.launch {
            val snap = runCatching { repo.monthlySpendTrend() }.getOrNull() ?: return@launch
            _monthly.value = snap
            val keys = snap.ranks.map { it.bucket.key }
            runCatching { repo.ensureIconsForCategories(keys) }
            if (_summary.value == null && snap.ranks.isNotEmpty() &&
                runCatching { repo.hasGeminiKey() }.getOrDefault(false)
            ) {
                loadSummary()
            }
        }
    }

    fun refreshSummary() {
        viewModelScope.launch { loadSummary() }
    }

    private suspend fun loadSummary() {
        if (_summaryLoading.value) return
        _summaryLoading.value = true
        _summaryError.value = null
        runCatching { repo.trendsSummaryBullets() }
            .onSuccess { _summary.value = it }
            .onFailure { _summaryError.value = it.message ?: "Could not summarize trends" }
        _summaryLoading.value = false
    }

    class Factory(private val repo: SpendRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TrendsViewModel(repo) as T
    }
}

data class ExportsUiState(
    val report: MonthSpendReport? = null,
    /** Day-start millis → short Gemini label ("Onam", "Independence Day", "Salary day"). */
    val dateNotes: Map<Long, String> = emptyMap(),
    val notesLoading: Boolean = false,
    val exporting: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class ExportsViewModel(
    private val repo: SpendRepository,
    context: Context,
) : ViewModel() {
    private val exporter = SpendReportExporter(context.applicationContext)

    private val _state = MutableStateFlow(ExportsUiState())
    val state: StateFlow<ExportsUiState> = _state

    /** Reloads the report in place so a return visit doesn't flash "Loading…". */
    fun onScreenVisible() {
        viewModelScope.launch {
            val report = runCatching { repo.monthSpendReport() }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(report = report)
        }
    }

    /** Asks Gemini to label notable spend dates (holidays/festivals/landmarks). */
    fun annotateDates() {
        val rows = _state.value.report?.rows.orEmpty()
        if (rows.isEmpty() || _state.value.notesLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(notesLoading = true, message = null)
            runCatching { repo.annotateReportDates(rows) }
                .onSuccess { notes ->
                    _state.value = _state.value.copy(
                        dateNotes = notes,
                        notesLoading = false,
                        message = if (notes.isEmpty()) "No notable dates this month" else null,
                        messageIsError = false,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        notesLoading = false,
                        message = e.message ?: "Could not annotate dates",
                        messageIsError = true,
                    )
                }
        }
    }

    /** Saves the report as CSV to Downloads (opens in Google Sheets / Excel). */
    fun exportCsv() {
        val report = _state.value.report ?: return
        if (_state.value.exporting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(exporting = true, message = null)
            val result = exporter.exportCsv(report, _state.value.dateNotes)
            _state.value = _state.value.copy(
                exporting = false,
                message = result.error ?: "Saved ${result.fileName} to Downloads",
                messageIsError = result.error != null,
            )
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    class Factory(
        private val repo: SpendRepository,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExportsViewModel(repo, context) as T
    }
}

data class SettingsUiState(
    val apiKey: String = "",
    val lastSyncLabel: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val monthlyBudgetPaise: Long? = null,
    val autoScanEnabled: Boolean = true,
    val aiTriageEnabled: Boolean = true,
    val biometricLockEnabled: Boolean = false,
    val selfNameAliases: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val myBankIds: String = "",
    val showMyBanks: Boolean = false,
    val editingBankIds: Set<String> = emptySet(),
    val detectedBankIds: Set<String> = emptySet(),
    val logSizeLabel: String = "0 B",
)

class SettingsViewModel(
    private val repo: SpendRepository,
    private val prefs: UserPreferences,
    private val appContext: Context,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val messageIsError = MutableStateFlow(false)
    private val syncLabel = MutableStateFlow("")
    private val showMyBanks = MutableStateFlow(false)
    private val editingBankIds = MutableStateFlow<Set<String>>(emptySet())
    private val detectedBankIds = MutableStateFlow<Set<String>>(emptySet())
    private val logSizeLabel = MutableStateFlow(DiagLog.approxSizeLabel())

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            prefs.geminiApiKey,
            busy,
            message,
            messageIsError,
            syncLabel,
        ) { key, b, msg, err, sync ->
            SettingsUiState(
                apiKey = key,
                lastSyncLabel = sync,
                busy = b,
                message = msg,
                messageIsError = err,
            )
        },
        combine(
            prefs.monthlyBudgetPaise,
            prefs.autoScanEnabled,
            prefs.biometricLockEnabled,
            prefs.selfNameAliases,
            prefs.themeMode,
        ) { budget, autoScan, biometric, aliases, theme ->
            SettingsUiState(
                monthlyBudgetPaise = budget,
                autoScanEnabled = autoScan,
                biometricLockEnabled = biometric,
                selfNameAliases = aliases,
                themeMode = ThemeMode.fromStored(theme),
            )
        },
        combine(
            prefs.myBankIds,
            showMyBanks,
            editingBankIds,
            detectedBankIds,
            logSizeLabel,
        ) { bankIds, show, editing, detected, logSize ->
            SettingsUiState(
                myBankIds = bankIds,
                showMyBanks = show,
                editingBankIds = editing,
                detectedBankIds = detected,
                logSizeLabel = logSize,
            )
        },
        prefs.aiTriageEnabled,
    ) { base, extras, banks, aiTriage ->
        base.copy(
            aiTriageEnabled = aiTriage,
            monthlyBudgetPaise = extras.monthlyBudgetPaise,
            autoScanEnabled = extras.autoScanEnabled,
            biometricLockEnabled = extras.biometricLockEnabled,
            selfNameAliases = extras.selfNameAliases,
            themeMode = extras.themeMode,
            myBankIds = banks.myBankIds,
            showMyBanks = banks.showMyBanks,
            editingBankIds = banks.editingBankIds,
            detectedBankIds = banks.detectedBankIds,
            logSizeLabel = banks.logSizeLabel,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            syncLabel.value = DateRanges.formatSyncTime(repo.lastSyncAt())
            refreshLogSize()
        }
    }

    fun saveAndValidateApiKey(value: String) {
        viewModelScope.launch {
            val trimmed = value.trim()
            if (trimmed.isBlank()) {
                messageIsError.value = true
                message.value = "Enter an API key first"
                return@launch
            }
            busy.value = true
            message.value = null
            prefs.setGeminiApiKey(trimmed)
            when (val check = repo.validateApiKey(trimmed)) {
                is GeminiKeyCheck.Ok -> {
                    messageIsError.value = false
                    message.value = "Key saved · Gemini OK"
                    // Categorize rows skipped while the app ran without a key.
                    viewModelScope.launch {
                        runCatching { repo.retrySkippedWithAi() }
                        runCatching { SpendWidgetUpdater.update(appContext) }
                    }
                }
                is GeminiKeyCheck.Failed -> {
                    messageIsError.value = true
                    message.value = "Key saved, but check failed: ${check.reason}"
                }
            }
            busy.value = false
        }
    }

    fun setMonthlyBudgetRupees(rupeesText: String) {
        viewModelScope.launch {
            val rupees = rupeesText.trim().toLongOrNull()
            prefs.setMonthlyBudgetPaise(rupees?.times(100))
            messageIsError.value = false
            message.value = if (rupees == null || rupees <= 0) "Budget cleared" else "Budget saved"
            SpendWidgetUpdater.update(appContext)
        }
    }

    fun setAutoScanEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setAutoScanEnabled(enabled)
            SmsScanWorker.reschedule(appContext, enabled)
            messageIsError.value = false
            message.value = if (enabled) "Auto-scan on" else "Auto-scan paused"
        }
    }

    fun setAiTriageEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setAiTriageEnabled(enabled)
            messageIsError.value = false
            message.value = if (enabled) "AI will check SMS the rules miss" else "AI SMS check off"
            if (enabled) {
                runCatching { repo.runAiTriagePass() }
                runCatching { repo.runAiCategorizationPass() }
            }
        }
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setBiometricLockEnabled(enabled)
            messageIsError.value = false
            message.value = if (enabled) "App lock on" else "App lock off"
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            prefs.setThemeMode(mode.storageKey)
            runCatching { SpendWidgetUpdater.update(appContext) }
        }
    }

    fun setSelfNameAliases(value: String) {
        viewModelScope.launch {
            prefs.setSelfNameAliases(value)
            messageIsError.value = false
            message.value = if (value.trim().isBlank()) {
                "Self-transfer names cleared"
            } else {
                "Self-transfer names saved"
            }
        }
    }

    fun openMyBanksEditor() {
        viewModelScope.launch {
            val saved = app.smspend.domain.IndianBanks.parseIdsCsv(prefs.myBankIds.first())
            val detected = runCatching { repo.detectMyBanksFromInbox() }.getOrDefault(emptySet())
            detectedBankIds.value = detected
            editingBankIds.value = saved.ifEmpty { detected }
            showMyBanks.value = true
        }
    }

    fun toggleEditingBank(id: String) {
        editingBankIds.value = editingBankIds.value.toMutableSet().also { set ->
            if (!set.add(id)) set.remove(id)
        }
    }

    fun saveMyBanks() {
        viewModelScope.launch {
            prefs.setMyBankIds(app.smspend.domain.IndianBanks.idsToCsv(editingBankIds.value))
            prefs.setMyBanksConfigured(true)
            showMyBanks.value = false
            messageIsError.value = false
            message.value = "Banks saved"
            runCatching { repo.onMyBanksSaved() }
        }
    }

    fun dismissMyBanksEditor() {
        showMyBanks.value = false
    }

    fun rescan() {
        viewModelScope.launch {
            busy.value = true
            messageIsError.value = false
            runCatching {
                repo.scanInbox(
                    forceFullMonth = true,
                    sinceOverrideMs = DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1),
                )
            }
                .onSuccess {
                    message.value = "Rescanned · ${it.newTransactions} new"
                    syncLabel.value = DateRanges.formatSyncTime(repo.lastSyncAt())
                    viewModelScope.launch {
                        runCatching { repo.runAiTriagePass() }
                        runCatching { repo.runAiCategorizationPass() }
                        runCatching { SpendWidgetUpdater.update(appContext) }
                    }
                    runCatching { repo.refreshRecurringMerchants() }
                    runCatching { SpendWidgetUpdater.update(appContext) }
                }
                .onFailure {
                    messageIsError.value = true
                    message.value = it.message
                }
            busy.value = false
        }
    }

    fun clearMemory() {
        viewModelScope.launch {
            repo.clearMerchantMemory()
            messageIsError.value = false
            message.value = "Category learning reset"
        }
    }

    fun recheckParsed() {
        viewModelScope.launch {
            busy.value = true
            messageIsError.value = false
            runCatching { repo.repairTransactions() }
                .onSuccess { result ->
                    message.value = when {
                        result.removed == 0 && result.fixed == 0 ->
                            "No cross-source duplicates found"
                        result.crossSourceRemoved > 0 && result.fixed == 0 ->
                            "Removed ${result.crossSourceRemoved} cross-source duplicates"
                        else ->
                            "Removed ${result.removed} wrong entries · fixed ${result.fixed} amounts"
                    }
                    runCatching { SpendWidgetUpdater.update(appContext) }
                }
                .onFailure {
                    messageIsError.value = true
                    message.value = it.message ?: "Re-check failed"
                }
            busy.value = false
        }
    }

    fun recategorizeWithGemini() {
        viewModelScope.launch {
            busy.value = true
            messageIsError.value = false
            runCatching { repo.reBaselineWithAi() }
                .onSuccess { result ->
                    when {
                        result.pending == 0 -> {
                            message.value = "Nothing to recategorize"
                        }
                        result.applied == 0 && result.failed > 0 -> {
                            messageIsError.value = true
                            message.value = result.error
                                ?: "Gemini failed — check key / update app"
                        }
                        result.failed > 0 -> {
                            messageIsError.value = true
                            message.value =
                                "Recategorized ${result.applied} with Gemini · ${result.failed} failed (will retry)"
                        }
                        else -> {
                            message.value = "Recategorized ${result.applied} with Gemini"
                        }
                    }
                    runCatching { SpendWidgetUpdater.update(appContext) }
                }
                .onFailure {
                    messageIsError.value = true
                    message.value = it.message ?: "Recategorize failed"
                }
            busy.value = false
        }
    }

    fun flagDuplicatesWithGemini() {
        viewModelScope.launch {
            busy.value = true
            messageIsError.value = false
            runCatching { repo.flagPossibleDuplicatesWithGemini() }
                .onSuccess { result ->
                    when {
                        result.error != null && result.flagged == 0 -> {
                            messageIsError.value = true
                            message.value = result.error
                        }
                        result.candidates == 0 -> {
                            message.value = "No possible duplicate pairs found"
                        }
                        else -> {
                            message.value = "Flagged ${result.flagged} possible duplicates" +
                                if (result.error != null) " · ${result.error}" else ""
                        }
                    }
                }
                .onFailure {
                    messageIsError.value = true
                    message.value = it.message ?: "Duplicate flagging failed"
                }
            busy.value = false
        }
    }

    fun exportOtherSms(all: Boolean = false) {
        viewModelScope.launch {
            busy.value = true
            messageIsError.value = false
            val exporter = OtherSmsExporter(
                context = appContext,
                db = SmSpendDatabase.get(appContext),
                smsReader = SmsInboxReader(appContext),
            )
            runCatching { exporter.export(allCategories = all) }
                .onSuccess { result ->
                    val what = if (all) "inbox SMS" else "Other SMS"
                    when {
                        result.error != null -> {
                            messageIsError.value = true
                            message.value = result.error
                        }
                        result.fileName != null -> {
                            message.value =
                                "Saved ${result.count} $what → Downloads/${result.fileName}"
                        }
                        else -> {
                            message.value = "Nothing to export"
                        }
                    }
                }
                .onFailure {
                    messageIsError.value = true
                    message.value = it.message ?: "Export failed"
                }
            busy.value = false
        }
    }

    fun exportCategorizationLog() {
        viewModelScope.launch {
            busy.value = true
            messageIsError.value = false
            runCatching { DiagLog.exportToDownloads(appContext) }
                .onSuccess { result ->
                    when {
                        result.error != null -> {
                            messageIsError.value = true
                            message.value = result.error
                        }
                        result.fileName != null -> {
                            message.value =
                                "Saved log (${result.count} lines) → Downloads/${result.fileName}"
                        }
                        else -> {
                            message.value = "Nothing to export"
                        }
                    }
                }
                .onFailure {
                    messageIsError.value = true
                    message.value = it.message ?: "Log export failed"
                }
            refreshLogSize()
            busy.value = false
        }
    }

    fun clearCategorizationLog() {
        viewModelScope.launch {
            busy.value = true
            messageIsError.value = false
            runCatching { DiagLog.clear() }
                .onSuccess {
                    message.value = "Categorization log cleared"
                    refreshLogSize()
                }
                .onFailure {
                    messageIsError.value = true
                    message.value = it.message ?: "Could not clear log"
                }
            busy.value = false
        }
    }

    private fun refreshLogSize() {
        logSizeLabel.value = DiagLog.approxSizeLabel()
    }

    class Factory(
        private val repo: SpendRepository,
        private val prefs: UserPreferences,
        private val appContext: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repo, prefs, appContext.applicationContext) as T
    }
}

class SearchViewModel(
    private val repo: SpendRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<TransactionEntity>> = query
        .debounce(250)
        .flatMapLatest { q -> repo.searchTransactions(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val queryText: StateFlow<String> = query

    fun setQuery(value: String) {
        query.value = value
    }

    class Factory(private val repo: SpendRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(repo) as T
    }
}

class OnboardingViewModel(
    private val prefs: UserPreferences,
    private val repo: SpendRepository,
) : ViewModel() {
    fun complete(apiKey: String, onDone: () -> Unit) {
        viewModelScope.launch {
            if (apiKey.isNotBlank()) prefs.setGeminiApiKey(apiKey)
            prefs.setOnboarded(true)
            prefs.setAutoScanEnabled(true)
            runCatching {
                repo.scanInbox(
                    forceFullMonth = true,
                    sinceOverrideMs = DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1),
                )
            }
            // First-run backfill: categorize the scanned history in a few batched calls
            // while the user is still on the onboarding screen. Also revives rows
            // SKIPPED by an earlier key-less run if the user re-onboards with a key.
            runCatching { repo.retrySkippedWithAi() }
            onDone()
        }
    }

    class Factory(
        private val prefs: UserPreferences,
        private val repo: SpendRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            OnboardingViewModel(prefs, repo) as T
    }
}
