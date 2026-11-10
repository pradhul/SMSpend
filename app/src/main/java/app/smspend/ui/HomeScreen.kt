package app.smspend.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabPosition
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.smspend.data.CategorySpend
import app.smspend.data.SpendRepository
import app.smspend.domain.CategoryBucket
import app.smspend.domain.SpendCategory
import app.smspend.ui.theme.Debit
import app.smspend.ui.theme.Ink
import app.smspend.ui.theme.Line
import app.smspend.ui.theme.LocalSmSpendColors
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
import kotlin.math.abs
import kotlin.math.roundToInt

// Home: period pager, hero, category tiles and donut. (Split out of the former Screens.kt.)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    repo: SpendRepository,
    hasApiKey: Boolean,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTrends: () -> Unit,
    onOpenExports: () -> Unit,
    onOpenCategory: (CategoryBucket, Long, Long, String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val months = state.months.ifEmpty { DateRanges.homeMonths() }
    val pageCount = viewModel.pageCount()
    val initialPage = state.selectedPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
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
        val target = viewModel.uiState.value.selectedPageIndex
            .coerceIn(0, (viewModel.pageCount() - 1).coerceAtLeast(0))
        if (pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
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
            onKeepBoth = viewModel::keepDuplicatePair,
            onMuteLater = viewModel::muteDuplicatePair,
            onRemoveLater = viewModel::removeDuplicatePair,
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
                IconButton(onClick = onOpenExports) {
                    Icon(Icons.Default.TableChart, contentDescription = "Report & export", tint = Ink)
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
                    upcomingExplain = if (state.segment == HomeSegment.THIS_WEEK) state.upcomingExplain else null,
                    upcomingExplainLoading = state.segment == HomeSegment.THIS_WEEK && state.upcomingExplainLoading,
                    hasApiKey = hasApiKey,
                    notices = state.periodNotices,
                    onExplainUpcoming = viewModel::explainUpcomingSpends,
                    onSync = { viewModel.syncThisPeriod() },
                    onRefresh = { viewModel.refresh() },
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
                                    upcomingExplain = if (isSettled) state.upcomingExplain else null,
                                    upcomingExplainLoading = isSettled && state.upcomingExplainLoading,
                                    hasApiKey = hasApiKey,
                                    notices = if (isSettled) state.periodNotices else emptyList(),
                                    onExplainUpcoming = viewModel::explainUpcomingSpends,
                                    onSync = { viewModel.syncThisPeriod() },
                                    onRefresh = { viewModel.refresh() },
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
                                upcomingExplain = if (isSettled) state.upcomingExplain else null,
                                upcomingExplainLoading = isSettled && state.upcomingExplainLoading,
                                hasApiKey = hasApiKey,
                                notices = if (isSettled) state.periodNotices else emptyList(),
                                onExplainUpcoming = viewModel::explainUpcomingSpends,
                                onSync = { viewModel.syncThisPeriod() },
                                onRefresh = { viewModel.refresh() },
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
internal fun Modifier.pagerTabIndicatorOffset(
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
internal fun PeriodPageSkeleton() {
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
internal fun PeriodSpendPage(
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
    upcomingExplain: String?,
    upcomingExplainLoading: Boolean,
    hasApiKey: Boolean,
    notices: List<String> = emptyList(),
    onExplainUpcoming: () -> Unit,
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
    val credits = remember(categories) {
        categories.filter { SpendCategory.isCredits(it.bucket.key) }
    }
    val spendTotalPaise = spending.sumOf { it.totalPaise }
    val savedTotalPaise = saved.sumOf { it.totalPaise }
    val savedFullWidth = saved.size < 2
    val movesFullWidth = accountMoves.size < 2
    val creditsFullWidth = credits.size < 2

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
                statusMessage = message,
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
            if (notices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Mint.copy(alpha = 0.22f))
                        .border(1.dp, Line, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    notices.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = Ink)
                    }
                }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Likely due soon", style = MaterialTheme.typography.labelMedium, color = Mute)
                            Text(
                                "From your recurring spend pattern",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Mute,
                            )
                        }
                        if (hasApiKey) {
                            TextButton(
                                onClick = onExplainUpcoming,
                                enabled = !upcomingExplainLoading,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                if (upcomingExplainLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MintDeep,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Asking…", color = MintDeep, style = MaterialTheme.typography.labelLarge)
                                } else {
                                    Text("Why?", color = MintDeep, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    rows.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AnimatedVisibility(
                        visible = upcomingExplain != null,
                        enter = fadeIn(tween(100)) + slideInVertically(tween(100)) { it / 4 },
                        exit = fadeOut(tween(80)),
                    ) {
                        upcomingExplain?.let { text ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Mute,
                            )
                        }
                    }
                    if (!hasApiKey) {
                        Text(
                            "Add a Gemini key in Settings to ask why these are due.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Mute,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            when {
                spending.isEmpty() && saved.isEmpty() && accountMoves.isEmpty() && credits.isEmpty() && !scanning -> {
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
                                fullWidth = false,
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
                            saved.forEach { cat ->
                                item(
                                    key = "save-${cat.bucket.key}",
                                    span = { GridItemSpan(if (savedFullWidth) maxLineSpan else 1) },
                                ) {
                                    CategoryTile(
                                        cat = cat,
                                        weekTotalPaise = savedTotalPaise.coerceAtLeast(1),
                                        isSavings = true,
                                        fullWidth = savedFullWidth,
                                        iconName = iconNames[cat.bucket.key],
                                        enableSharedElement = enableSharedElement,
                                        onClick = { onOpenCategory(cat.bucket) },
                                        sharedTransitionScope = sharedTransitionScope,
                                        animatedContentScope = animatedContentScope,
                                    )
                                }
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
                            accountMoves.forEach { cat ->
                                item(
                                    key = "move-${cat.bucket.key}",
                                    span = { GridItemSpan(if (movesFullWidth) maxLineSpan else 1) },
                                ) {
                                    CategoryTile(
                                        cat = cat,
                                        weekTotalPaise = movesTotal.coerceAtLeast(1),
                                        isSavings = true,
                                        fullWidth = movesFullWidth,
                                        iconName = iconNames[cat.bucket.key],
                                        enableSharedElement = enableSharedElement,
                                        onClick = { onOpenCategory(cat.bucket) },
                                        sharedTransitionScope = sharedTransitionScope,
                                        animatedContentScope = animatedContentScope,
                                    )
                                }
                            }
                        }
                        if (credits.isNotEmpty()) {
                            val creditsTotal = credits.sumOf { it.totalPaise }
                            item(
                                key = "credits-header",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Column(modifier = Modifier.padding(top = 4.dp)) {
                                    Text(
                                        "Credits",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Ok,
                                    )
                                    Text(
                                        "Credits into your banks — tag to net against matching spends",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Mute,
                                    )
                                }
                            }
                            credits.forEach { cat ->
                                item(
                                    key = "credit-${cat.bucket.key}",
                                    span = { GridItemSpan(if (creditsFullWidth) maxLineSpan else 1) },
                                ) {
                                    CategoryTile(
                                        cat = cat,
                                        weekTotalPaise = creditsTotal.coerceAtLeast(1),
                                        isSavings = true,
                                        fullWidth = creditsFullWidth,
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
}

@Composable
internal fun MonthPickerPage(
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
internal fun CategoryTile(
    cat: CategorySpend,
    weekTotalPaise: Long,
    isSavings: Boolean,
    fullWidth: Boolean,
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
    val merchantLine = cat.merchants.take(if (fullWidth) 3 else 1).joinToString(" · ")
        .ifBlank { "${cat.txnCount} txn${if (cat.txnCount == 1) "" else "s"}" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PaperRaised)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        if (fullWidth) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = cat.bucket.label,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        cat.bucket.label,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        merchantLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Mute,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        cat.totalPaise.paiseToCompactRupees(),
                        fontWeight = FontWeight.SemiBold,
                        color = amountColor,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        "${cat.txnCount} txn${if (cat.txnCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Mute,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
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
        } else {
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
            merchantLine,
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
}

@Composable
internal fun PeriodHero(
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
    statusMessage: String? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(listOf(Mint.copy(alpha = 0.45f), PaperRaised)),
            )
            .border(1.dp, Line, RoundedCornerShape(22.dp))
            .padding(16.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
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
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    momPct?.let { pct ->
                        val vs = priorMonthShortLabel?.let { "vs $it" } ?: "vs last month"
                        val label = when {
                            pct > 0 -> "↑$pct% $vs"
                            pct < 0 -> "↓${-pct}% $vs"
                            else -> "Same as last month"
                        }
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MintDeep,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Mute,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "scanned $lastSyncLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Mute,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    statusMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MintDeep,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "pull to rescan",
                        style = MaterialTheme.typography.labelMedium,
                        color = Mute.copy(alpha = 0.85f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
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
                        .height(6.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Ink.copy(alpha = 0.08f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progress.coerceAtMost(1f).coerceAtLeast(0.03f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(barColor),
                    )
                }
            }
            if (savedTotalPaise > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Ok.copy(alpha = 0.1f))
                        .border(1.dp, Ok.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
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
                Spacer(modifier = Modifier.height(12.dp))
                Text("Of spending", style = MaterialTheme.typography.labelMedium, color = Mute)
                Spacer(modifier = Modifier.height(8.dp))
                CategorySpendDonut(categories = spending, totalPaise = spendTotalPaise)
            }
        }
    }
}

internal data class DonutSlice(
    val label: String,
    val paise: Long,
    val color: Color,
)

@Composable
internal fun CategorySpendDonut(
    categories: List<CategorySpend>,
    totalPaise: Long,
) {
    val isDark = LocalSmSpendColors.current.isDark
    val otherColor = Mute.copy(alpha = 0.55f)
    val slices = remember(categories, totalPaise, isDark, otherColor) {
        if (totalPaise <= 0L) emptyList()
        else {
            val sorted = categories
                .filter { it.totalPaise > 0 }
                .sortedByDescending { it.totalPaise }
            val top = sorted.take(4)
            val otherPaise = sorted.drop(4).sumOf { it.totalPaise }
            buildList {
                top.forEach { cat ->
                    add(
                        DonutSlice(
                            label = cat.bucket.label,
                            paise = cat.totalPaise,
                            color = CategoryColors.forKey(cat.bucket.key, isDark),
                        ),
                    )
                }
                if (otherPaise > 0) {
                    add(
                        DonutSlice(
                            label = "Other",
                            paise = otherPaise,
                            color = otherColor,
                        ),
                    )
                }
            }
        }
    }
    val spendCount = remember(categories) { categories.sumOf { it.txnCount } }
    if (slices.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(80.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Butt)
                val diameter = size.minDimension - stroke.width
                val topLeft = androidx.compose.ui.geometry.Offset(
                    (size.width - diameter) / 2f,
                    (size.height - diameter) / 2f,
                )
                val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
                var start = -90f
                slices.forEach { slice ->
                    val sweep = (slice.paise.toFloat() / totalPaise.toFloat()) * 360f
                    drawArc(
                        color = slice.color,
                        startAngle = start,
                        sweepAngle = sweep.coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = stroke,
                    )
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$spendCount",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                Text(
                    "spend",
                    style = MaterialTheme.typography.labelMedium,
                    color = Mute,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            slices.forEach { slice ->
                val pct = ((slice.paise.toDouble() / totalPaise.toDouble()) * 100.0)
                    .roundToInt()
                    .coerceAtLeast(1)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(slice.color),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        slice.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "$pct%",
                        style = MaterialTheme.typography.labelMedium,
                        color = Mute,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
