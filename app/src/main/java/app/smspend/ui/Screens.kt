package app.smspend.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabPosition
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.smspend.data.CategoryNoteEntity
import app.smspend.data.CategorySpend
import app.smspend.data.CategoryYtdTrend
import app.smspend.data.SpendRepository
import app.smspend.data.TransactionEntity
import app.smspend.data.WrongParseResult
import app.smspend.domain.CategoryBucket
import app.smspend.domain.SpendCategory
import app.smspend.ui.theme.Debit
import app.smspend.ui.theme.Ink
import app.smspend.ui.theme.Line
import app.smspend.ui.theme.Mint
import app.smspend.ui.theme.MintDeep
import app.smspend.ui.theme.Mute
import app.smspend.ui.theme.Ok
import app.smspend.ui.theme.Paper
import app.smspend.ui.theme.PaperRaised
import app.smspend.util.DateRanges
import app.smspend.util.DueLabels
import app.smspend.util.MonthWindow
import app.smspend.util.paiseToCompactRupees
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    repo: SpendRepository,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTrends: () -> Unit,
    onOpenCategory: (CategoryBucket, Long, Long, String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val months = state.months.ifEmpty { DateRanges.homeMonths() }
    val pageCount = viewModel.pageCount()
    val initialPage = (pageCount - 1).coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { pageCount },
    )
    val scope = rememberCoroutineScope()
    val fling = PagerDefaults.flingBehavior(
        state = pagerState,
        snapAnimationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
    )

    LaunchedEffect(pagerState, state.segment) {
        if (state.segment != HomeSegment.MONTHS) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> viewModel.warmAround(page) }
    }
    LaunchedEffect(pagerState, state.segment) {
        if (state.segment != HomeSegment.MONTHS) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page -> viewModel.selectPage(page) }
    }

    if (state.showMyBanks) {
        MyBanksSheet(
            selectedIds = state.selectedBankIds,
            detectedIds = state.detectedBankIds,
            onToggle = viewModel::toggleBank,
            onSave = viewModel::saveMyBanks,
            onSkip = viewModel::dismissMyBanks,
        )
    }
    state.duplicateReview?.let { review ->
        DuplicateReviewSheet(
            pairs = review.pairs,
            onRemove = viewModel::removeReviewedDuplicates,
            onDismiss = viewModel::dismissDuplicateReview,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SMSpend", style = MaterialTheme.typography.headlineMedium, color = Ink)
            Row {
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Ink)
                }
                IconButton(onClick = onOpenTrends) {
                    Icon(Icons.Default.ShowChart, contentDescription = "Trends", tint = Ink)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Ink)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                HomeSegment.TODAY to "Today",
                HomeSegment.THIS_WEEK to "This week",
                HomeSegment.MONTHS to "Months",
            ).forEach { (seg, label) ->
                val selected = state.segment == seg
                Text(
                    label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Ink else PaperRaised)
                        .border(1.dp, if (selected) Ink else Line, RoundedCornerShape(12.dp))
                        .clickable { viewModel.selectSegment(seg) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    color = if (selected) Mint else Mute,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        when (state.segment) {
            HomeSegment.TODAY, HomeSegment.THIS_WEEK -> {
                PeriodSpendPage(
                    title = state.monthTitle,
                    subtitle = state.monthLabel,
                    categories = state.categories,
                    iconNames = state.iconNames,
                    scanning = state.scanning,
                    message = state.message,
                    lastSyncLabel = state.lastSyncLabel,
                    drawChart = state.categories.isNotEmpty(),
                    enableSharedElement = false,
                    momPct = null,
                    priorMonthShortLabel = null,
                    monthlyBudgetPaise = if (state.segment == HomeSegment.TODAY) null else state.monthlyBudgetPaise,
                    expectedSoon = if (state.segment == HomeSegment.THIS_WEEK) state.expectedSoon else emptyList(),
                    onSync = { viewModel.syncThisPeriod() },
                    onRefresh = { viewModel.refresh(forceFullRange = false) },
                    onOpenCategory = { bucket ->
                        onOpenCategory(bucket, state.periodStartMs, state.periodEndMs, state.monthTitle)
                    },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                )
            }
            HomeSegment.MONTHS -> {
                val pagerScroll = pagerState.currentPage + pagerState.currentPageOffsetFraction
                val lastTabIndex = (pageCount - 1).coerceAtLeast(0)
                ScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage.coerceIn(0, lastTabIndex),
                    edgePadding = 16.dp,
                    containerColor = Paper,
                    contentColor = Ink,
                    divider = {},
                    indicator = { positions ->
                        if (positions.isNotEmpty() && pagerScroll.isFinite()) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.pagerTabIndicatorOffset(pagerState, positions),
                                color = MintDeep,
                            )
                        }
                    },
                ) {
                    repeat(pageCount) { index ->
                        val label = when {
                            index == HOME_PICKER_PAGE ->
                                state.customMonth?.shortLabel() ?: "Pick"
                            else -> months.getOrNull(index - 1)?.title() ?: "—"
                        }
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        index,
                                        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
                                    )
                                }
                            },
                            text = {
                                Text(
                                    label,
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            selectedContentColor = Ink,
                            unselectedContentColor = Mute,
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    flingBehavior = fling,
                    key = { page -> viewModel.monthForPage(page)?.startMs ?: -1L - page },
                ) { page ->
                    val settled = pagerState.settledPage
                    val isSettled = settled == page
                    val isWarm = kotlin.math.abs(page - settled) <= 1 ||
                        kotlin.math.abs(page - pagerState.currentPage) <= 1
                    if (page == HOME_PICKER_PAGE) {
                        val custom = state.customMonth
                        if (custom != null) {
                            val categories = state.categoriesByPage[page].orEmpty()
                            if (isWarm || categories.isNotEmpty()) {
                                PeriodSpendPage(
                                    title = custom.title(),
                                    subtitle = custom.subtitle(),
                                    categories = categories,
                                    iconNames = state.iconNames,
                                    scanning = state.scanning && isSettled,
                                    message = if (isSettled) state.message else null,
                                    lastSyncLabel = state.lastSyncLabel,
                                    drawChart = categories.isNotEmpty() && kotlin.math.abs(page - settled) <= 1,
                                    enableSharedElement = false,
                                    momPct = if (isSettled) state.momPct else null,
                                    priorMonthShortLabel = if (isSettled) state.priorMonthShortLabel else null,
                                    monthlyBudgetPaise = if (isSettled) state.monthlyBudgetPaise else null,
                                    expectedSoon = if (isSettled) state.expectedSoon else emptyList(),
                                    onSync = { viewModel.syncThisPeriod() },
                                    onRefresh = { viewModel.refresh(forceFullRange = false) },
                                    onOpenCategory = { bucket ->
                                        onOpenCategory(bucket, custom.startMs, custom.endExclusiveMs, custom.title())
                                    },
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedContentScope = animatedContentScope,
                                )
                            } else {
                                PeriodPageSkeleton()
                            }
                        } else {
                            MonthPickerPage(
                                choices = state.pickerChoices,
                                onPick = viewModel::pickCustomMonth,
                            )
                        }
                    } else {
                        val month = viewModel.monthForPage(page)!!
                        val categories = state.categoriesByPage[page].orEmpty()
                        if (isWarm || categories.isNotEmpty()) {
                            PeriodSpendPage(
                                title = month.title(),
                                subtitle = month.subtitle(),
                                categories = categories,
                                iconNames = state.iconNames,
                                scanning = state.scanning && isSettled,
                                message = if (isSettled) state.message else null,
                                lastSyncLabel = state.lastSyncLabel,
                                drawChart = categories.isNotEmpty() && kotlin.math.abs(page - settled) <= 1,
                                enableSharedElement = false,
                                momPct = if (isSettled) state.momPct else null,
                                priorMonthShortLabel = if (isSettled) state.priorMonthShortLabel else null,
                                monthlyBudgetPaise = if (isSettled) state.monthlyBudgetPaise else null,
                                expectedSoon = if (isSettled) state.expectedSoon else emptyList(),
                                onSync = { viewModel.syncThisPeriod() },
                                onRefresh = { viewModel.refresh(forceFullRange = false) },
                                onOpenCategory = { bucket ->
                                    onOpenCategory(bucket, month.startMs, month.endExclusiveMs, month.title())
                                },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedContentScope = animatedContentScope,
                            )
                        } else {
                            PeriodPageSkeleton()
                        }
                    }
                }
            }
        }
    }
}

/** Indicator follows pager scroll 1:1 — no separate TabRow animation. */
private fun Modifier.pagerTabIndicatorOffset(
    pagerState: PagerState,
    tabPositions: List<TabPosition>,
): Modifier {
    if (tabPositions.isEmpty()) return this
    val currentPage = pagerState.currentPage.coerceIn(tabPositions.indices)
    val fraction = pagerState.currentPageOffsetFraction
    val current = tabPositions[currentPage]
    val neighborIndex = (currentPage + if (fraction >= 0f) 1 else -1)
        .coerceIn(tabPositions.indices)
    val neighbor = tabPositions[neighborIndex]
    val t = kotlin.math.abs(fraction).coerceIn(0f, 1f)
    val left: Dp = lerp(current.left, neighbor.left, t)
    val width: Dp = lerp(current.width, neighbor.width, t)
    return this
        .fillMaxWidth()
        .wrapContentSize(align = Alignment.BottomStart)
        .offset(x = left)
        .width(width)
}

@Composable
private fun PeriodPageSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Ink.copy(alpha = 0.06f)),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Ink.copy(alpha = 0.06f)),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(2) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(96.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Ink.copy(alpha = 0.05f)),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(2) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(96.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Ink.copy(alpha = 0.05f)),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun PeriodSpendPage(
    title: String,
    subtitle: String,
    categories: List<CategorySpend>,
    iconNames: Map<String, String>,
    scanning: Boolean,
    message: String?,
    lastSyncLabel: String,
    drawChart: Boolean,
    enableSharedElement: Boolean,
    momPct: Int?,
    priorMonthShortLabel: String?,
    monthlyBudgetPaise: Long?,
    expectedSoon: List<app.smspend.data.RecurringMerchantEntity>,
    onSync: () -> Unit,
    onRefresh: () -> Unit,
    onOpenCategory: (CategoryBucket) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val spending = remember(categories) {
        categories.filter { SpendCategory.countsAsSpend(it.bucket.key) }
    }
    val saved = remember(categories) {
        categories.filter { SpendCategory.countsAsSaved(it.bucket.key) }
    }
    val accountMoves = remember(categories) {
        categories.filter { SpendCategory.isSelfTransfer(it.bucket.key) }
    }
    val spendTotalPaise = spending.sumOf { it.totalPaise }
    val savedTotalPaise = saved.sumOf { it.totalPaise }

    PullToRefreshBox(
        isRefreshing = scanning,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            PeriodHero(
                title = title,
                subtitle = subtitle,
                spendTotalPaise = spendTotalPaise,
                savedTotalPaise = savedTotalPaise,
                lastSyncLabel = lastSyncLabel,
                spending = spending,
                drawChart = drawChart,
                momPct = momPct,
                priorMonthShortLabel = priorMonthShortLabel,
                monthlyBudgetPaise = monthlyBudgetPaise,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onSync,
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Mint),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Mint,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (scanning) "Syncing…" else "Sync this period")
            }
            if (expectedSoon.isNotEmpty()) {
                val rows = expectedSoon.mapNotNull { item ->
                    DueLabels.formatExpectedRow(
                        displayName = item.displayName,
                        medianPaise = item.medianPaise,
                        nextExpectedAtMs = item.nextExpectedAt,
                    )
                }.take(3)
                if (rows.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Likely due soon", style = MaterialTheme.typography.labelMedium, color = Mute)
                    Text(
                        "Recurring merchants (~weekly/monthly)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Mute,
                    )
                    rows.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "pull to rescan · Sync runs repair + categories",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
            )
            AnimatedVisibility(
                visible = message != null,
                enter = fadeIn(tween(100)) + slideInVertically(tween(100)) { it / 4 },
                exit = fadeOut(tween(80)),
            ) {
                message?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(it, color = MintDeep, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            when {
                spending.isEmpty() && saved.isEmpty() && accountMoves.isEmpty() && !scanning -> {
                    Text(
                        "No activity in this period yet…",
                        color = Mute,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 24.dp),
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        item(
                            key = "spend-header",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            Column {
                                Text("Spending", style = MaterialTheme.typography.titleMedium, color = Ink)
                                if (spending.isEmpty()) {
                                    Text(
                                        "No consumption spend this period.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Mute,
                                    )
                                }
                            }
                        }
                        items(
                            items = spending,
                            key = { "spend-${it.bucket.key}" },
                        ) { cat ->
                            CategoryTile(
                                cat = cat,
                                weekTotalPaise = spendTotalPaise.coerceAtLeast(1),
                                isSavings = false,
                                iconName = iconNames[cat.bucket.key],
                                enableSharedElement = enableSharedElement,
                                onClick = { onOpenCategory(cat.bucket) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedContentScope = animatedContentScope,
                            )
                        }
                        if (saved.isNotEmpty()) {
                            item(
                                key = "save-header",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Column(modifier = Modifier.padding(top = 4.dp)) {
                                    Text(
                                        "Saved & invested",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Ok,
                                    )
                                    Text(
                                        "RD, SIP, Groww — not spending",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Mute,
                                    )
                                }
                            }
                            items(
                                items = saved,
                                key = { "save-${it.bucket.key}" },
                            ) { cat ->
                                CategoryTile(
                                    cat = cat,
                                    weekTotalPaise = savedTotalPaise.coerceAtLeast(1),
                                    isSavings = true,
                                    iconName = iconNames[cat.bucket.key],
                                    enableSharedElement = enableSharedElement,
                                    onClick = { onOpenCategory(cat.bucket) },
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedContentScope = animatedContentScope,
                                )
                            }
                        }
                        if (accountMoves.isNotEmpty()) {
                            val movesTotal = accountMoves.sumOf { it.totalPaise }
                            item(
                                key = "moves-header",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Column(modifier = Modifier.padding(top = 4.dp)) {
                                    Text(
                                        "Account moves",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Mute,
                                    )
                                    Text(
                                        "Self transfers — not spending, not saved",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Mute,
                                    )
                                }
                            }
                            items(
                                items = accountMoves,
                                key = { "move-${it.bucket.key}" },
                            ) { cat ->
                                CategoryTile(
                                    cat = cat,
                                    weekTotalPaise = movesTotal.coerceAtLeast(1),
                                    isSavings = true,
                                    iconName = iconNames[cat.bucket.key],
                                    enableSharedElement = enableSharedElement,
                                    onClick = { onOpenCategory(cat.bucket) },
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedContentScope = animatedContentScope,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthPickerPage(
    choices: List<MonthWindow>,
    onPick: (MonthWindow) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            Text(
                "Pick a month",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )
            Text(
                "Last 24 months",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        items(choices, key = { it.startMs }) { month ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onPick(month) }
                    .padding(vertical = 14.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        month.title(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        month.subtitle(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Mute,
                    )
                }
                Text(
                    month.shortLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MintDeep,
                )
            }
            HorizontalDivider(color = Line)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CategoryTile(
    cat: CategorySpend,
    weekTotalPaise: Long,
    isSavings: Boolean,
    iconName: String?,
    enableSharedElement: Boolean,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val share = (cat.totalPaise.toFloat() / weekTotalPaise.toFloat()).coerceIn(0f, 1f)
    val accent = CategoryColors.forKey(cat.bucket.key)
    val amountColor = if (isSavings) Ok else Debit
    val barBrush = if (isSavings) {
        Brush.horizontalGradient(listOf(Ok, MintDeep))
    } else {
        Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.55f)))
    }
    val iconBg = accent.copy(alpha = 0.14f)
    val icon = CategoryIcons.forKey(cat.bucket.key, iconName)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PaperRaised)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        val headerBlock = @Composable { headerModifier: Modifier ->
            Row(
                modifier = headerModifier,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = cat.bucket.label,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    cat.bucket.label,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (enableSharedElement) {
            with(sharedTransitionScope) {
                headerBlock(
                    Modifier
                        .fillMaxWidth()
                        .sharedBounds(
                            sharedContentState = rememberSharedContentState(
                                key = "cat-header-${cat.bucket.key}",
                            ),
                            animatedVisibilityScope = animatedContentScope,
                        ),
                )
            }
        } else {
            headerBlock(Modifier.fillMaxWidth())
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            cat.totalPaise.paiseToCompactRupees(),
            fontWeight = FontWeight.SemiBold,
            color = amountColor,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            cat.merchants.firstOrNull()
                ?: "${cat.txnCount} txn${if (cat.txnCount == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium,
            color = Mute,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Ink.copy(alpha = 0.08f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = share.coerceAtLeast(0.03f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(barBrush),
            )
        }
    }
}

@Composable
private fun PeriodHero(
    title: String,
    subtitle: String,
    spendTotalPaise: Long,
    savedTotalPaise: Long,
    lastSyncLabel: String,
    spending: List<CategorySpend>,
    drawChart: Boolean,
    momPct: Int?,
    priorMonthShortLabel: String?,
    monthlyBudgetPaise: Long?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(listOf(Mint.copy(alpha = 0.45f), PaperRaised)),
            )
            .border(1.dp, Line, RoundedCornerShape(22.dp))
            .padding(18.dp),
    ) {
        Column {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = Mute,
            )
            Text(
                "Spent",
                style = MaterialTheme.typography.labelMedium,
                color = Mute,
            )
            Text(
                spendTotalPaise.paiseToCompactRupees(),
                style = MaterialTheme.typography.displayLarge,
                color = Debit,
            )
            momPct?.let { pct ->
                val vs = priorMonthShortLabel?.let { "vs $it" } ?: "vs last month"
                val label = when {
                    pct > 0 -> "↑$pct% $vs"
                    pct < 0 -> "↓${-pct}% $vs"
                    else -> "Same as last month"
                }
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MintDeep)
            }
            monthlyBudgetPaise?.takeIf { it > 0 }?.let { budget ->
                val left = budget - spendTotalPaise
                val progress = (spendTotalPaise.toFloat() / budget.toFloat()).coerceIn(0f, 1.2f)
                val barColor = if (progress >= 0.8f) Debit else Ok
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (left >= 0) {
                        "${left.paiseToCompactRupees()} left this month"
                    } else {
                        "${(-left).paiseToCompactRupees()} over this month"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = barColor,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Ink.copy(alpha = 0.08f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progress.coerceAtMost(1f).coerceAtLeast(0.03f))
                            .height(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(barColor),
                    )
                }
            }
            Text(
                "$subtitle · scanned $lastSyncLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
            )
            if (savedTotalPaise > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ok.copy(alpha = 0.1f))
                        .border(1.dp, Ok.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("SAVED & INVESTED", style = MaterialTheme.typography.labelMedium, color = Ok)
                        Text(
                            "Set aside — not spending",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Mute,
                        )
                    }
                    Text(
                        savedTotalPaise.paiseToCompactRupees(),
                        fontWeight = FontWeight.SemiBold,
                        color = Ok,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (drawChart && spending.isNotEmpty() && spendTotalPaise > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Of spending", style = MaterialTheme.typography.labelMedium, color = Mute)
                Spacer(modifier = Modifier.height(10.dp))
                CategoryShareRing(categories = spending, totalPaise = spendTotalPaise)
            }
        }
    }
}

@Composable
private fun CategoryShareRing(
    categories: List<CategorySpend>,
    totalPaise: Long,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 16.dp.toPx()
                val diameter = size.minDimension - stroke
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                drawArc(
                    color = Ink.copy(alpha = 0.08f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                var start = -90f
                categories.forEach { cat ->
                    val sweep = 360f * (cat.totalPaise.toFloat() / totalPaise.toFloat())
                    drawArc(
                        color = CategoryColors.forKey(cat.bucket.key),
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${categories.size}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ink,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("spend", style = MaterialTheme.typography.labelMedium, color = Mute)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f)
                .height(120.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            categories.forEach { cat ->
                val pct = ((cat.totalPaise * 100.0) / totalPaise).roundToInt()
                val color = CategoryColors.forKey(cat.bucket.key)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${cat.bucket.label} · $pct%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun CategoryScreen(
    bucket: CategoryBucket,
    weekTitle: String,
    viewModel: CategoryViewModel,
    repo: SpendRepository,
    hasApiKey: Boolean,
    onBack: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val txns by viewModel.transactions.collectAsStateWithLifecycle()
    val iconName by viewModel.iconName.collectAsStateWithLifecycle()
    val monthTotals by viewModel.monthTotals.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val total = txns.sumOf { it.amountPaise }
    val isSavings = !SpendCategory.countsAsSpend(bucket.key)
    var retagTarget by remember { mutableStateOf<TransactionEntity?>(null) }
    var detailTarget by remember { mutableStateOf<TransactionEntity?>(null) }
    var draftNote by remember { mutableStateOf("") }
    var customCategoryKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accent = CategoryColors.forKey(bucket.key)
    val headerIcon = CategoryIcons.forKey(bucket.key, iconName)
    LaunchedEffect(Unit) {
        customCategoryKeys = runCatching { repo.listCustomCategoryKeys() }.getOrDefault(emptyList())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        headerIcon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(bucket.label, style = MaterialTheme.typography.titleLarge, color = Ink)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(PaperRaised)
                .border(1.dp, Line, RoundedCornerShape(22.dp))
                .padding(18.dp),
        ) {
            Column {
                Text(weekTitle.uppercase(), style = MaterialTheme.typography.labelMedium, color = Mute)
                if (isSavings) {
                    Text(
                        "Set aside — not spending",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ok,
                    )
                }
                Text(
                    total.paiseToCompactRupees(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (isSavings) Ok else Debit,
                )
                Text("${txns.size} payments", style = MaterialTheme.typography.bodyMedium, color = Mute)
            }
        }
        if (monthTotals.size >= 2) {
            Spacer(modifier = Modifier.height(12.dp))
            Text("This year", style = MaterialTheme.typography.labelMedium, color = Mute)
            Spacer(modifier = Modifier.height(6.dp))
            CategorySparkline(values = monthTotals, color = accent)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    monthTotals.first().paiseToCompactRupees(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mute,
                )
                Text(
                    monthTotals.last().paiseToCompactRupees(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mute,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text("Notes", style = MaterialTheme.typography.titleMedium, color = Ink)
        notes.forEach { note: CategoryNoteEntity ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    note.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.deleteNote(note.id) }) {
                    Text("Delete", color = Debit)
                }
            }
        }
        OutlinedTextField(
            value = draftNote,
            onValueChange = { draftNote = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Add a note") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Ink,
                unfocusedBorderColor = Line,
                focusedLabelColor = Mute,
            ),
        )
        TextButton(
            onClick = {
                val text = draftNote.trim()
                if (text.isNotEmpty()) {
                    viewModel.addNote(text)
                    draftNote = ""
                }
            },
            enabled = draftNote.isNotBlank(),
        ) {
            Text("Add note", color = MintDeep)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Transactions", style = MaterialTheme.typography.titleMedium, color = Ink)
        Text(
            "Tap to change category · hold to see the SMS",
            style = MaterialTheme.typography.bodyMedium,
            color = Mute,
        )
        LazyColumn {
            items(txns, key = { it.id }) { tx ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { retagTarget = tx },
                            onLongClick = { detailTarget = tx },
                        )
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tx.merchantRaw, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(
                            txnListSubtitle(tx),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Mute,
                        )
                    }
                    Text(
                        tx.amountPaise.paiseToCompactRupees(),
                        fontWeight = FontWeight.SemiBold,
                        color = Debit,
                    )
                }
            }
        }
    }

    retagTarget?.let { tx ->
        RetagSheet(
            transaction = tx,
            hasApiKey = hasApiKey,
            customCategoryKeys = customCategoryKeys,
            onDismiss = { retagTarget = null },
            onSave = { categoryKey, applyAll ->
                scope.launch {
                    repo.retag(tx.id, categoryKey, applyAll)
                    retagTarget = null
                    customCategoryKeys =
                        runCatching { repo.listCustomCategoryKeys() }.getOrDefault(customCategoryKeys)
                }
            },
            onAskGemini = { applyAll ->
                scope.launch {
                    repo.askGeminiForTransaction(tx.id, applyAll)
                    retagTarget = null
                }
            },
        )
    }

    detailTarget?.let { tx ->
        TransactionDetailSheet(
            transaction = tx,
            hasApiKey = hasApiKey,
            repo = repo,
            onDismiss = { detailTarget = null },
            onChangeCategory = {
                detailTarget = null
                retagTarget = tx
            },
            onReportWrong = {
                scope.launch {
                    val result = repo.reportWrongParse(tx.id)
                    detailTarget = null
                    Toast.makeText(context, wrongParseMessage(result), Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

private fun txnListSubtitle(tx: TransactionEntity): String {
    val time = DateRanges.formatTxnTime(tx.occurredAt)
    return if (tx.note.isNotBlank()) "${tx.note} · $time" else time
}

private fun searchListSubtitle(tx: TransactionEntity): String {
    val base = "${SpendCategory.displayLabel(tx.category)} · ${DateRanges.formatTxnTime(tx.occurredAt)}"
    return if (tx.note.isNotBlank()) "${tx.note} · $base" else base
}

private fun wrongParseMessage(result: WrongParseResult): String = when (result) {
    is WrongParseResult.Fixed -> buildString {
        append("Amount fixed to ${result.newAmountPaise.paiseToCompactRupees()}")
        if (result.ruleSaved) append(" · rule saved for next time")
    }
    is WrongParseResult.Removed ->
        if (result.ruleSaved) "Removed · similar messages will be skipped"
        else "Removed · won't be counted again"
    WrongParseResult.NotFound -> "Already removed"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionDetailSheet(
    transaction: TransactionEntity,
    hasApiKey: Boolean,
    repo: SpendRepository,
    onDismiss: () -> Unit,
    onChangeCategory: () -> Unit,
    onReportWrong: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var draftNote by remember(transaction.id, transaction.note) {
        mutableStateOf(transaction.note)
    }
    var recentTags by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(transaction.id) {
        recentTags = repo.recentTags()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PaperRaised,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("TRANSACTION", style = MaterialTheme.typography.labelMedium, color = Mute)
            Text(
                transaction.merchantRaw,
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
            )
            Text(
                transaction.amountPaise.paiseToCompactRupees(),
                style = MaterialTheme.typography.headlineMedium,
                color = Debit,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${SpendCategory.displayLabel(transaction.category)} · ${DateRanges.formatTxnTime(transaction.occurredAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("NOTE / TAG", style = MaterialTheme.typography.labelMedium, color = Mute)
            OutlinedTextField(
                value = draftNote,
                onValueChange = { draftNote = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Bike taxi, Grocery, Rahul…") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ink,
                    unfocusedBorderColor = Line,
                    focusedLabelColor = Mute,
                ),
            )
            TextButton(
                onClick = {
                    scope.launch {
                        repo.setTransactionNote(transaction.id, draftNote)
                        recentTags = repo.recentTags()
                    }
                },
                enabled = draftNote.trim() != transaction.note.trim(),
            ) {
                Text("Save note", color = MintDeep)
            }
            if (recentTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recentTags.forEach { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, Line, RoundedCornerShape(10.dp))
                                .background(Paper)
                                .clickable {
                                    draftNote = tag
                                    scope.launch {
                                        repo.setTransactionNote(transaction.id, tag)
                                        recentTags = repo.recentTags()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(tag, style = MaterialTheme.typography.bodyMedium, color = Ink)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("SOURCE SMS", style = MaterialTheme.typography.labelMedium, color = Mute)
            Text(
                "From ${transaction.sender}",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Paper)
                    .border(1.dp, Line, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Text(
                    transaction.bodySnippet,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = Ink,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onChangeCategory,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Mint),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Change category")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    busy = true
                    onReportWrong()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Debit,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("This was parsed wrong", color = Debit)
            }
            Text(
                if (hasApiKey) {
                    "Gemini will re-read this SMS — it fixes the amount if it's a real spend, or removes it and learns to skip messages like this."
                } else {
                    "This entry will be removed and never counted again. Add a Gemini API key in Settings to also auto-fix amounts and learn new formats."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetagSheet(
    transaction: TransactionEntity,
    hasApiKey: Boolean,
    customCategoryKeys: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (categoryKey: String, applyAll: Boolean) -> Unit,
    onAskGemini: (applyAll: Boolean) -> Unit,
) {
    var selected by remember {
        mutableStateOf(
            if (SpendCategory.isCustomKey(transaction.category)) {
                null
            } else {
                SpendCategory.fromStored(transaction.category)
            },
        )
    }
    var selectedCustomKey by remember {
        mutableStateOf(
            if (SpendCategory.isCustomKey(transaction.category)) transaction.category else null,
        )
    }
    var customText by remember {
        mutableStateOf(
            if (SpendCategory.isCustomKey(transaction.category)) {
                SpendCategory.displayLabel(transaction.category)
            } else {
                ""
            },
        )
    }
    var applyAll by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var savePressed by remember { mutableStateOf(false) }
    val saveScale by animateFloatAsState(
        targetValue = if (savePressed) 0.96f else 1f,
        animationSpec = tween(120),
        label = "saveScale",
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PaperRaised,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("CHANGE CATEGORY", style = MaterialTheme.typography.labelMedium, color = Mute)
            Text(
                "${transaction.merchantRaw} · ${transaction.amountPaise.paiseToCompactRupees()}",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SpendCategory.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { cat ->
                            val selectedChip = selected == cat && customText.isBlank() && selectedCustomKey == null
                            val chipScale by animateFloatAsState(
                                targetValue = if (selectedChip) 1.02f else 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessHigh,
                                ),
                                label = "chipScale",
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .scale(chipScale)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, if (selectedChip) Ink else Line, RoundedCornerShape(12.dp))
                                    .background(if (selectedChip) Mint.copy(alpha = 0.35f) else PaperRaised)
                                    .clickable {
                                        selected = cat
                                        selectedCustomKey = null
                                        customText = ""
                                    }
                                    .padding(12.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        CategoryIcons.forPreset(cat),
                                        contentDescription = null,
                                        tint = CategoryColors.forKey(cat.name),
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(cat.label, fontWeight = FontWeight.SemiBold, color = Ink)
                                }
                            }
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
                if (customCategoryKeys.isNotEmpty()) {
                    Text("Your categories", style = MaterialTheme.typography.labelMedium, color = Mute)
                    customCategoryKeys.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { key ->
                                val selectedChip = selectedCustomKey == key && customText.isBlank()
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, if (selectedChip) Ink else Line, RoundedCornerShape(12.dp))
                                        .background(if (selectedChip) Mint.copy(alpha = 0.35f) else PaperRaised)
                                        .clickable {
                                            selected = null
                                            selectedCustomKey = key
                                            customText = ""
                                        }
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        SpendCategory.displayLabel(key),
                                        fontWeight = FontWeight.SemiBold,
                                        color = Ink,
                                    )
                                }
                            }
                            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = customText,
                onValueChange = {
                    customText = it
                    if (it.isNotBlank()) {
                        selected = null
                        selectedCustomKey = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Or type a new category") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ink,
                    unfocusedBorderColor = Line,
                    focusedLabelColor = Mute,
                ),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                Text(
                    "Apply to all similar (${transaction.merchantRaw})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                )
            }
            Button(
                onClick = {
                    savePressed = true
                    val key = when {
                        customText.isNotBlank() -> SpendCategory.resolveStorageKey(customText, selected)
                        selectedCustomKey != null -> selectedCustomKey!!
                        else -> SpendCategory.resolveStorageKey("", selected)
                    }
                    onSave(key, applyAll)
                },
                enabled = !busy && (selected != null || selectedCustomKey != null || customText.isNotBlank()),
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(saveScale),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Mint),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Save")
            }
            if (hasApiKey) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        busy = true
                        onAskGemini(applyAll)
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Ask Gemini", color = Ink)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var draftKey by remember { mutableStateOf(state.apiKey) }
    var draftBudget by remember {
        mutableStateOf(
            state.monthlyBudgetPaise?.let { (it / 100).toString() }.orEmpty(),
        )
    }
    var draftThreshold by remember {
        mutableStateOf((state.largeDebitThresholdPaise / 100).toString())
    }
    var draftSelfNames by remember { mutableStateOf(state.selfNameAliases) }
    LaunchedEffect(state.apiKey) { draftKey = state.apiKey }
    LaunchedEffect(state.monthlyBudgetPaise) {
        draftBudget = state.monthlyBudgetPaise?.let { (it / 100).toString() }.orEmpty()
    }
    LaunchedEffect(state.largeDebitThresholdPaise) {
        draftThreshold = (state.largeDebitThresholdPaise / 100).toString()
    }
    LaunchedEffect(state.selfNameAliases) { draftSelfNames = state.selfNameAliases }
    val needsStoragePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    var pendingExportAll by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted || !needsStoragePermission) {
            viewModel.exportOtherSms(all = pendingExportAll)
        } else {
            Toast.makeText(
                context,
                "Storage permission needed to save the dump",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Ink)
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = Ink)
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = "Gemini API key",
            body = "Used for unknown merchants and Recategorize with Gemini. Key stays on this phone.",
        ) {
            OutlinedTextField(
                value = draftKey,
                onValueChange = { draftKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ink,
                    unfocusedBorderColor = Line,
                    focusedLabelColor = Mute,
                ),
            )
            TextButton(
                onClick = { viewModel.saveAndValidateApiKey(draftKey) },
                enabled = !state.busy,
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MintDeep,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Save & check key", color = MintDeep)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = "Monthly budget",
            body = "How much you plan to spend each month. Savings and investments don't count.",
        ) {
            OutlinedTextField(
                value = draftBudget,
                onValueChange = { draftBudget = it.filter { ch -> ch.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Rupees per month") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ink,
                    unfocusedBorderColor = Line,
                    focusedLabelColor = Mute,
                ),
            )
            TextButton(onClick = { viewModel.setMonthlyBudgetRupees(draftBudget) }) {
                Text("Save budget", color = MintDeep)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = "Alerts & auto-scan",
            body = "Background SMS scan every ~30 minutes. Large-debit alerts use notifications.",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-scan SMS", color = Ink)
                Switch(
                    checked = state.autoScanEnabled,
                    onCheckedChange = { viewModel.setAutoScanEnabled(it) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("App lock (biometric)", color = Ink)
                Switch(
                    checked = state.biometricLockEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            viewModel.setBiometricLockEnabled(false)
                            return@Switch
                        }
                        val act = context as? FragmentActivity
                        if (act == null) {
                            viewModel.setBiometricLockEnabled(true)
                            return@Switch
                        }
                        act.promptBiometric(
                            title = "Enable app lock",
                            subtitle = "Confirm to lock SMSpend",
                            onSuccess = { viewModel.setBiometricLockEnabled(true) },
                        )
                    },
                )
            }
            OutlinedTextField(
                value = draftThreshold,
                onValueChange = { draftThreshold = it.filter { ch -> ch.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Large debit alert (₹)") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ink,
                    unfocusedBorderColor = Line,
                    focusedLabelColor = Mute,
                ),
            )
            TextButton(onClick = { viewModel.setLargeDebitThresholdRupees(draftThreshold) }) {
                Text("Save threshold", color = MintDeep)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = "Self transfers",
            body = "Your name aliases (comma-separated). Matching payees are marked Self transfer and excluded from spending — e.g. Pradhul, PRADHULDEV.",
        ) {
            OutlinedTextField(
                value = draftSelfNames,
                onValueChange = { draftSelfNames = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Your name(s)") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ink,
                    unfocusedBorderColor = Line,
                    focusedLabelColor = Mute,
                ),
            )
            TextButton(onClick = { viewModel.setSelfNameAliases(draftSelfNames) }) {
                Text("Save names", color = MintDeep)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = "Capture UPI app notifications",
            body = "Reads PhonePe payment notifications to auto-fill payee (and note when the app includes it) on matching bank SMS. Open system settings and enable SMSpend under notification access.",
        ) {
            val context = LocalContext.current
            var listenerEnabled by remember {
                mutableStateOf(
                    NotificationManagerCompat.getEnabledListenerPackages(context)
                        .contains(context.packageName),
                )
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        listenerEnabled = NotificationManagerCompat
                            .getEnabledListenerPackages(context)
                            .contains(context.packageName)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            Text(
                if (listenerEnabled) "Status: enabled" else "Status: disabled",
                style = MaterialTheme.typography.bodyMedium,
                color = if (listenerEnabled) Ok else Mute,
            )
            Text(
                "Payee name is captured reliably; the typed note only when PhonePe includes it in the notification.",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    if (listenerEnabled) "Open notification access" else "Enable notification access",
                    color = Ink,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(title = "SMS scanning", body = "Last sync · ${state.lastSyncLabel}. Use Sync this period on Home to scan, repair, and clean duplicates.") {}
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = "Your banks",
            body = "Used to detect money moving between your own accounts (self transfers).",
        ) {
            OutlinedButton(
                onClick = { viewModel.openMyBanksEditor() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    if (state.myBankIds.isBlank()) "Choose banks" else "Edit banks",
                    color = Ink,
                )
            }
        }
        if (state.showMyBanks) {
            MyBanksSheet(
                selectedIds = state.editingBankIds,
                detectedIds = state.detectedBankIds,
                onToggle = viewModel::toggleEditingBank,
                onSave = viewModel::saveMyBanks,
                onSkip = viewModel::dismissMyBanksEditor,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = "Export transactions",
            body = "Saves a JSON dump to Downloads. “Other” exports categorized Other spends; “ALL inbox SMS” dumps every message on the device (no date limit) — useful for refining categorization and debugging from another device.",
        ) {
            OutlinedButton(
                onClick = {
                    pendingExportAll = false
                    if (needsStoragePermission) {
                        exportLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        viewModel.exportOtherSms(all = false)
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Export Other SMS to Downloads", color = Ink)
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    pendingExportAll = true
                    if (needsStoragePermission) {
                        exportLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        viewModel.exportOtherSms(all = true)
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Export ALL inbox SMS", color = Ink)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = "Reset category learning",
            body = "SMSpend remembers the category you pick for each merchant and reuses it. Reset this if categories keep coming out wrong.",
        ) {
            OutlinedButton(
                onClick = { viewModel.clearMemory() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Reset learning", color = Ink)
            }
        }
        AnimatedVisibility(
            visible = state.message != null,
            enter = fadeIn(tween(100)) + slideInVertically(tween(100)) { it / 3 },
            exit = fadeOut(tween(80)),
        ) {
            state.message?.let {
                Spacer(modifier = Modifier.height(12.dp))
                Text(it, color = if (state.messageIsError) Debit else MintDeep)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, body: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PaperRaised)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Mute)
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    repo: SpendRepository,
    hasApiKey: Boolean,
    onBack: () -> Unit,
) {
    val query by viewModel.queryText.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    var retagTarget by remember { mutableStateOf<TransactionEntity?>(null) }
    var detailTarget by remember { mutableStateOf<TransactionEntity?>(null) }
    var customCategoryKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        customCategoryKeys = runCatching { repo.listCustomCategoryKeys() }.getOrDefault(emptyList())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
            }
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Merchant, note, category…") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MintDeep,
                    unfocusedBorderColor = Line,
                    focusedTextColor = Ink,
                    unfocusedTextColor = Ink,
                    cursorColor = MintDeep,
                ),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (query.isBlank()) {
            Text("Type to search all transactions", style = MaterialTheme.typography.bodyMedium, color = Mute)
        } else if (results.isEmpty()) {
            Text("No matches", style = MaterialTheme.typography.bodyMedium, color = Mute)
        } else {
            LazyColumn {
                items(results, key = { it.id }) { tx ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { retagTarget = tx },
                                onLongClick = { detailTarget = tx },
                            )
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(tx.merchantRaw, fontWeight = FontWeight.SemiBold, color = Ink)
                            Text(
                                searchListSubtitle(tx),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Mute,
                            )
                        }
                        Text(
                            tx.amountPaise.paiseToCompactRupees(),
                            fontWeight = FontWeight.SemiBold,
                            color = Debit,
                        )
                    }
                    HorizontalDivider(color = Line)
                }
            }
        }
    }

    retagTarget?.let { tx ->
        RetagSheet(
            transaction = tx,
            hasApiKey = hasApiKey,
            customCategoryKeys = customCategoryKeys,
            onDismiss = { retagTarget = null },
            onSave = { categoryKey, applyAll ->
                scope.launch {
                    repo.retag(tx.id, categoryKey, applyAll)
                    retagTarget = null
                    customCategoryKeys =
                        runCatching { repo.listCustomCategoryKeys() }.getOrDefault(customCategoryKeys)
                }
            },
            onAskGemini = { applyAll ->
                scope.launch {
                    repo.askGeminiForTransaction(tx.id, applyAll)
                    retagTarget = null
                }
            },
        )
    }

    detailTarget?.let { tx ->
        TransactionDetailSheet(
            transaction = tx,
            hasApiKey = hasApiKey,
            repo = repo,
            onDismiss = { detailTarget = null },
            onChangeCategory = {
                detailTarget = null
                retagTarget = tx
            },
            onReportWrong = {
                scope.launch {
                    val result = repo.reportWrongParse(tx.id)
                    detailTarget = null
                    Toast.makeText(context, wrongParseMessage(result), Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}

@Composable
fun TrendsScreen(
    viewModel: TrendsViewModel,
    onBack: () -> Unit,
    onOpenCategory: (CategoryBucket) -> Unit,
) {
    val trends by viewModel.trends.collectAsStateWithLifecycle()
    val iconNames by viewModel.iconNames.collectAsStateWithLifecycle()
    val spending = remember(trends) {
        trends.filter { SpendCategory.countsAsSpend(it.bucket.key) }
    }
    val saved = remember(trends) {
        trends.filter { SpendCategory.countsAsSaved(it.bucket.key) }
    }
    val accountMoves = remember(trends) {
        trends.filter { SpendCategory.isSelfTransfer(it.bucket.key) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
            }
            Text("Trends · this year", style = MaterialTheme.typography.titleLarge, color = Ink)
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (spending.isNotEmpty()) {
                item {
                    Text("Spending", style = MaterialTheme.typography.titleMedium, color = Ink)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                items(spending, key = { "spend-${it.bucket.key}" }) { trend ->
                    TrendRow(
                        trend = trend,
                        iconName = iconNames[trend.bucket.key],
                        amountColor = Debit,
                        onClick = { onOpenCategory(trend.bucket) },
                    )
                }
            }
            if (saved.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Saved & invested", style = MaterialTheme.typography.titleMedium, color = Ok)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                items(saved, key = { "save-${it.bucket.key}" }) { trend ->
                    TrendRow(
                        trend = trend,
                        iconName = iconNames[trend.bucket.key],
                        amountColor = Ok,
                        onClick = { onOpenCategory(trend.bucket) },
                    )
                }
            }
            if (accountMoves.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Account moves", style = MaterialTheme.typography.titleMedium, color = Mute)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                items(accountMoves, key = { "move-${it.bucket.key}" }) { trend ->
                    TrendRow(
                        trend = trend,
                        iconName = iconNames[trend.bucket.key],
                        amountColor = Mute,
                        onClick = { onOpenCategory(trend.bucket) },
                    )
                }
            }
            if (trends.isEmpty()) {
                item {
                    Text(
                        "No category trends yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Mute,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendRow(
    trend: CategoryYtdTrend,
    iconName: String?,
    amountColor: Color,
    onClick: () -> Unit,
) {
    val accent = CategoryColors.forKey(trend.bucket.key)
    val icon = CategoryIcons.forKey(trend.bucket.key, iconName)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = trend.bucket.label,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                trend.bucket.label,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                trend.ytdTotalPaise.paiseToCompactRupees(),
                fontWeight = FontWeight.SemiBold,
                color = amountColor,
            )
        }
        if (trend.monthTotals.size >= 2) {
            Spacer(modifier = Modifier.height(8.dp))
            CategorySparkline(values = trend.monthTotals, color = accent)
        }
    }
}

@Composable
private fun CategorySparkline(values: List<Long>, color: Color) {
    val max = (values.maxOrNull() ?: 1L).coerceAtLeast(1L)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (values.size < 2) return@Canvas
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v.toFloat() / max.toFloat()) * size.height * 0.9f - size.height * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyBanksSheet(
    selectedIds: Set<String>,
    detectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val banks = remember(query) {
        app.smspend.domain.IndianBanks.ALL.filter {
            query.isBlank() || it.label.contains(query, ignoreCase = true)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onSkip,
        sheetState = sheetState,
        containerColor = PaperRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .height(520.dp),
        ) {
            Text("Your banks", style = MaterialTheme.typography.titleLarge, color = Ink)
            Text(
                "Pre-selected from SMS on this phone. Used to detect transfers between your own accounts.",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search banks") },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(banks, key = { it.id }) { bank ->
                    val checked = bank.id in selectedIds
                    val detected = bank.id in detectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(bank.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { onToggle(bank.id) })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(bank.label, color = Ink, fontWeight = FontWeight.SemiBold)
                            if (detected) {
                                Text("Seen in SMS", style = MaterialTheme.typography.bodyMedium, color = MintDeep)
                            }
                        }
                    }
                }
            }
            Button(
                onClick = onSave,
                enabled = selectedIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Mint),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Save banks")
            }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Skip for now", color = Mute)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateReviewSheet(
    pairs: List<app.smspend.data.DuplicatePairCandidate>,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PaperRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .height(480.dp),
        ) {
            Text("Possible duplicates", style = MaterialTheme.typography.titleLarge, color = Ink)
            Text(
                "Same amount within 24h. Keeping the earlier SMS; remove later copies.",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
            )
            Spacer(modifier = Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(pairs, key = { it.pairId }) { pair ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Paper)
                            .padding(12.dp),
                    ) {
                        Text(
                            pair.amountPaise.paiseToCompactRupees(),
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                        )
                        Text(
                            "Keep · ${pair.earlierSender}\n${pair.earlierSnippet}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Mute,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Remove · ${pair.laterSender}\n${pair.laterSnippet}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Debit,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Button(
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Mint),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Remove ${pairs.size} duplicates")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Keep all", color = Mute)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun OnboardingScreen(
    hasSmsPermission: Boolean,
    onRequestSms: () -> Unit,
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Ink),
            contentAlignment = Alignment.Center,
        ) {
            Text("₹", color = Mint, style = MaterialTheme.typography.displayLarge)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("SMSpend", style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text(
            "See this week’s spending from your bank SMS — private, on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = Mute,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(modifier = Modifier.height(28.dp))
        OnboardStep("1. Allow SMS", if (hasSmsPermission) "Permission granted" else "Read debit alerts only. Nothing is uploaded.") {
            if (!hasSmsPermission) {
                TextButton(onClick = onRequestSms) { Text("Grant SMS access", color = MintDeep) }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        OnboardStep("2. Add Gemini key", "Smart categories for unknown merchants (optional now).") {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = {
                if (!hasSmsPermission) {
                    onRequestSms()
                    return@Button
                }
                busy = true
                viewModel.complete(apiKey) {
                    busy = false
                    onFinished()
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Mint),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(if (busy) "Scanning…" else "Continue")
        }
    }
}

@Composable
private fun OnboardStep(title: String, body: String, extra: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PaperRaised)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Mute)
        extra()
    }
}
