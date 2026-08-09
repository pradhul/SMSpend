package app.smspend.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.smspend.data.CategoryNoteEntity
import app.smspend.data.CategorySpend
import app.smspend.data.CategoryYtdTrend
import app.smspend.data.GeminiKeyCheck
import app.smspend.data.OtherSmsExporter
import app.smspend.data.RecurringMerchantEntity
import app.smspend.data.SmsInboxReader
import app.smspend.data.SmSpendDatabase
import app.smspend.data.SpendRepository
import app.smspend.data.TransactionEntity
import app.smspend.data.UserPreferences
import app.smspend.domain.RecurringDetector
import app.smspend.domain.SpendCategory
import app.smspend.util.DateRanges
import app.smspend.util.MonthWindow
import app.smspend.widget.SpendWidgetUpdater
import app.smspend.work.SmsScanWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    val customMonth: MonthWindow? = null,
    val pickerChoices: List<MonthWindow> = emptyList(),
    val duplicateReview: DuplicateReviewState? = null,
    val showMyBanks: Boolean = false,
    val detectedBankIds: Set<String> = emptySet(),
    val selectedBankIds: Set<String> = emptySet(),
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
    ) { triple, extras, selectedBanks ->
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
    ) { base, icons, budget, soon ->
        base.copy(
            iconNames = icons,
            monthlyBudgetPaise = budget,
            expectedSoon = soon,
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

    fun refresh(forceFullRange: Boolean = false) {
        viewModelScope.launch {
            scanning.value = true
            message.value = null
            val state = uiState.value
            val since = when {
                forceFullRange -> months.first().startMs
                state.segment == HomeSegment.MONTHS ->
                    monthForPage(selectedPageIndex.value)?.startMs ?: months.last().startMs
                else -> state.periodStartMs
            }
            runCatching {
                repo.scanInbox(
                    forceFullMonth = forceFullRange,
                    sinceOverrideMs = since,
                )
            }
                .onSuccess { result ->
                    message.value = if (result.newTransactions == 0) {
                        "Up to date · ${result.scannedSms} SMS checked"
                    } else {
                        "Added ${result.newTransactions} spends"
                    }
                    refreshSyncLabel()
                    runCatching { repo.processPostScanAlerts(appContext) }
                    runCatching { repo.refreshRecurringMerchants() }
                    runCatching { SpendWidgetUpdater.update(appContext) }
                    maybePromptMyBanks()
                }
                .onFailure { message.value = it.message ?: "Scan failed" }
            scanning.value = false
        }
    }

    fun syncThisPeriod() {
        viewModelScope.launch {
            scanning.value = true
            message.value = null
            val state = uiState.value
            val start = if (state.periodStartMs > 0) state.periodStartMs else months.first().startMs
            val end = if (state.periodEndMs > 0) state.periodEndMs else DateRanges.endExclusiveNow()
            runCatching { repo.syncPeriod(start, end) }
                .onSuccess { result ->
                    message.value = buildString {
                        append("Synced · ${result.newTransactions} new")
                        if (result.recategorized > 0) append(" · ${result.recategorized} recategorized")
                        if (result.duplicatePairs.isNotEmpty()) {
                            append(" · ${result.duplicatePairs.size} possible duplicates")
                        }
                    }
                    refreshSyncLabel()
                    runCatching { repo.processPostScanAlerts(appContext) }
                    runCatching { repo.refreshRecurringMerchants() }
                    runCatching { SpendWidgetUpdater.update(appContext) }
                    if (result.duplicatePairs.isNotEmpty()) {
                        duplicateReview.value = DuplicateReviewState(result.duplicatePairs)
                    }
                    maybePromptMyBanks()
                }
                .onFailure { message.value = it.message ?: "Sync failed" }
            scanning.value = false
        }
    }

    fun dismissDuplicateReview() {
        duplicateReview.value = null
    }

    fun removeReviewedDuplicates() {
        viewModelScope.launch {
            val pairs = duplicateReview.value?.pairs.orEmpty()
            if (pairs.isEmpty()) {
                duplicateReview.value = null
                return@launch
            }
            scanning.value = true
            val removed = repo.removeDuplicateLaters(pairs.map { it.laterId })
            duplicateReview.value = null
            message.value = "Removed $removed duplicates"
            runCatching { SpendWidgetUpdater.update(appContext) }
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
            runCatching {
                repo.applyCreditPairSelfTransfers(
                    DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1),
                )
            }
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
    val transactions: StateFlow<List<TransactionEntity>> =
        repo.observeCategoryTxns(categoryKey, monthStartMs, monthEndMs)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val iconName: StateFlow<String?> =
        repo.observeCategoryIconName(categoryKey)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _monthTotals = MutableStateFlow<List<Long>>(emptyList())
    val monthTotals: StateFlow<List<Long>> = _monthTotals

    val notes: StateFlow<List<CategoryNoteEntity>> =
        repo.observeCategoryNotes(categoryKey, monthStartMs)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    private val _trends = MutableStateFlow<List<CategoryYtdTrend>>(emptyList())
    val trends: StateFlow<List<CategoryYtdTrend>> = _trends

    private val iconNamesFlow = repo.observeCategoryIconNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val iconNames: StateFlow<Map<String, String>> = iconNamesFlow

    init {
        viewModelScope.launch {
            _trends.value = runCatching { repo.categoryMonthlyTotalsThisYear() }.getOrDefault(emptyList())
            val keys = _trends.value.map { it.bucket.key }
            runCatching { repo.ensureIconsForCategories(keys) }
        }
    }

    class Factory(private val repo: SpendRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TrendsViewModel(repo) as T
    }
}

data class SettingsUiState(
    val apiKey: String = "",
    val lastSyncLabel: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val monthlyBudgetPaise: Long? = null,
    val largeDebitThresholdPaise: Long = UserPreferences.DEFAULT_LARGE_DEBIT_PAISE,
    val autoScanEnabled: Boolean = true,
    val biometricLockEnabled: Boolean = false,
    val selfNameAliases: String = "",
    val myBankIds: String = "",
    val showMyBanks: Boolean = false,
    val editingBankIds: Set<String> = emptySet(),
    val detectedBankIds: Set<String> = emptySet(),
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
            prefs.largeDebitThresholdPaise,
            prefs.autoScanEnabled,
            prefs.biometricLockEnabled,
            prefs.selfNameAliases,
        ) { budget, threshold, autoScan, biometric, aliases ->
            SettingsUiState(
                monthlyBudgetPaise = budget,
                largeDebitThresholdPaise = threshold,
                autoScanEnabled = autoScan,
                biometricLockEnabled = biometric,
                selfNameAliases = aliases,
            )
        },
        combine(
            prefs.myBankIds,
            showMyBanks,
            editingBankIds,
            detectedBankIds,
        ) { bankIds, show, editing, detected ->
            SettingsUiState(
                myBankIds = bankIds,
                showMyBanks = show,
                editingBankIds = editing,
                detectedBankIds = detected,
            )
        },
    ) { base, extras, banks ->
        base.copy(
            monthlyBudgetPaise = extras.monthlyBudgetPaise,
            largeDebitThresholdPaise = extras.largeDebitThresholdPaise,
            autoScanEnabled = extras.autoScanEnabled,
            biometricLockEnabled = extras.biometricLockEnabled,
            selfNameAliases = extras.selfNameAliases,
            myBankIds = banks.myBankIds,
            showMyBanks = banks.showMyBanks,
            editingBankIds = banks.editingBankIds,
            detectedBankIds = banks.detectedBankIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            syncLabel.value = DateRanges.formatSyncTime(repo.lastSyncAt())
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

    fun setLargeDebitThresholdRupees(rupeesText: String) {
        viewModelScope.launch {
            val rupees = rupeesText.trim().toLongOrNull() ?: return@launch
            prefs.setLargeDebitThresholdPaise(rupees * 100)
            messageIsError.value = false
            message.value = "Large-debit alert set to ₹$rupees"
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

    fun setBiometricLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setBiometricLockEnabled(enabled)
            messageIsError.value = false
            message.value = if (enabled) "App lock on" else "App lock off"
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
            runCatching {
                repo.applyCreditPairSelfTransfers(
                    DateRanges.startOfMonthMs(DateRanges.HOME_MONTH_COUNT - 1),
                )
            }
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
                    runCatching { repo.processPostScanAlerts(appContext) }
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
            val hasKey = prefs.geminiApiKey.first().isNotBlank()
            runCatching { repo.recategorizeOther(useGemini = hasKey) }
                .onSuccess { result ->
                    when {
                        !hasKey -> {
                            messageIsError.value = true
                            message.value =
                                "No API key — applied rules to ${result.rulesUpdated} only"
                        }
                        result.failed > 0 && result.geminiUpdated == 0 -> {
                            messageIsError.value = true
                            message.value = result.firstError
                                ?: "Gemini failed — check key / update app"
                        }
                        result.failed > 0 -> {
                            messageIsError.value = true
                            message.value =
                                "Updated ${result.rulesUpdated} by rules · ${result.geminiUpdated} by Gemini · ${result.failed} failed"
                        }
                        else -> {
                            message.value =
                                "Updated ${result.rulesUpdated} by rules · ${result.geminiUpdated} by Gemini"
                        }
                    }
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
