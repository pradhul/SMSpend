package app.smspend.ui

import app.smspend.R
import androidx.compose.ui.res.stringResource
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.smspend.data.CategoryMonthRank
import app.smspend.data.MonthlySpendTrend
import app.smspend.data.TrendsBullet
import app.smspend.domain.CategoryBucket
import app.smspend.ui.theme.Debit
import app.smspend.ui.theme.Ink
import app.smspend.ui.theme.Line
import app.smspend.ui.theme.LocalSmSpendColors
import app.smspend.ui.theme.MintDeep
import app.smspend.ui.theme.Mute
import app.smspend.ui.theme.Ok
import app.smspend.ui.theme.Paper
import app.smspend.ui.theme.PaperRaised
import app.smspend.ui.theme.Warn
import app.smspend.util.DateRanges
import app.smspend.util.MonthWindow
import app.smspend.util.paiseToCompactRupees
import kotlin.math.abs
import kotlin.math.roundToInt

// Trends: monthly totals, top categories, AI summary. (Split out of the former Screens.kt.)

@Composable
fun TrendsScreen(
    viewModel: TrendsViewModel,
    hasApiKey: Boolean,
    onBack: () -> Unit,
    onOpenCategory: (CategoryBucket) -> Unit,
) {
    val monthly by viewModel.monthly.collectAsStateWithLifecycle()
    val iconNames by viewModel.iconNames.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val summaryError by viewModel.summaryError.collectAsStateWithLifecycle()
    val summaryLoading by viewModel.summaryLoading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.onScreenVisible() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.category_back), tint = Ink)
            }
            Text(stringResource(R.string.trends_trends_month_by_month), style = MaterialTheme.typography.titleLarge, color = Ink)
        }
        Spacer(modifier = Modifier.height(8.dp))
        when {
            monthly == null -> {
                Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mute,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            monthly!!.ranks.isEmpty() -> {
                Text(
                    "No spend in the last ${DateRanges.TRENDS_MONTH_COUNT} months yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mute,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            else -> {
                val snap = monthly!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "chart") {
                        MonthlySpendChartCard(snap = snap)
                    }
                    item(key = "insight") {
                        TrendsSummaryCard(
                            hasApiKey = hasApiKey,
                            bullets = summary,
                            error = summaryError,
                            loading = summaryLoading,
                            onRefresh = viewModel::refreshSummary,
                        )
                    }
                    item(key = "top-hdr") {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.trends_top_spends_this_month), style = MaterialTheme.typography.titleMedium, color = Ink)
                        Text(
                            "Amounts match the category detail for this month · MoM vs last month",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Mute,
                        )
                    }
                    items(snap.ranks, key = { it.bucket.key }) { rank ->
                        TopSpendTrendCard(
                            rank = rank,
                            iconName = iconNames[rank.bucket.key],
                            onClick = { onOpenCategory(rank.bucket) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TrendsSummaryCard(
    hasApiKey: Boolean,
    bullets: List<TrendsBullet>?,
    error: String?,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PaperRaised)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.trends_what_changed_vs_last_month), style = MaterialTheme.typography.labelMedium, color = Mute)
            if (hasApiKey) {
                TextButton(onClick = onRefresh, enabled = !loading) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = MintDeep,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        if (bullets == null) "Ask Gemini" else "Refresh",
                        color = MintDeep,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        when {
            !hasApiKey -> Text(
                "Add a Gemini key in Settings to get a bullet summary of what changed — what cost more, what cost less, and bills that look unpaid.",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
            )
            bullets != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bullets.forEach { bullet -> TrendsSummaryBullet(bullet) }
            }
            loading -> Text(
                "Comparing this month with last…",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
            )
            error != null -> Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = Debit,
            )
            else -> Text(
                "Tap Ask Gemini for things like “You spent ₹2,500 extra on Alcohol this month” or “Electricity was ₹100 less”.",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
            )
        }
    }
}

@Composable
internal fun TrendsSummaryBullet(bullet: TrendsBullet) {
    val (dotColor, textColor) = when (bullet.tone) {
        TrendsBullet.TONE_MORE -> Debit to Ink
        TrendsBullet.TONE_LESS -> Ok to Ink
        TrendsBullet.TONE_WARNING -> Warn to Warn
        else -> Mute to Ink
    }
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            bullet.text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MonthlySpendChartCard(snap: MonthlySpendTrend) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PaperRaised)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Text(stringResource(R.string.trends_spend_by_month), style = MaterialTheme.typography.labelMedium, color = Mute)
        Text(
            snap.thisMonthTotalPaise.paiseToCompactRupees(),
            style = MaterialTheme.typography.headlineMedium,
            color = Debit,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "This month · last ${snap.months.size} months in chart · debits only",
            style = MaterialTheme.typography.bodyMedium,
            color = Mute,
        )
        Spacer(modifier = Modifier.height(12.dp))
        MonthlyStackedBars(snap = snap)
        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            snap.topKeys.forEach { key ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(CategoryColors.forKey(key)),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        CategoryBucket.fromStored(key).label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (snap.otherByMonth.any { it > 0 }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Mute.copy(alpha = 0.55f)),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.trends_other), style = MaterialTheme.typography.labelMedium, color = Ink)
                }
            }
        }
    }
}

@Composable
internal fun MonthlyStackedBars(snap: MonthlySpendTrend) {
    val monthTotals = remember(snap) {
        snap.months.indices.map { i ->
            snap.topKeys.sumOf { key -> snap.series[key]?.getOrElse(i) { 0L } ?: 0L } +
                snap.otherByMonth.getOrElse(i) { 0L }
        }
    }
    val maxTotal = (monthTotals.maxOrNull() ?: 1L).coerceAtLeast(1L)
    val otherColor = Mute.copy(alpha = 0.55f)
    val isDark = LocalSmSpendColors.current.isDark

    Column {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        ) {
            val n = snap.months.size.coerceAtLeast(1)
            val gap = 6.dp.toPx()
            val barWidth = ((size.width - gap * (n - 1)) / n).coerceAtLeast(8.dp.toPx())
            snap.months.indices.forEach { i ->
                val x = i * (barWidth + gap)
                var yBottom = size.height
                val stack = buildList {
                    snap.topKeys.forEach { key ->
                        val v = snap.series[key]?.getOrElse(i) { 0L } ?: 0L
                        if (v > 0) add(CategoryColors.forKey(key, isDark) to v)
                    }
                    val other = snap.otherByMonth.getOrElse(i) { 0L }
                    if (other > 0) add(otherColor to other)
                }
                stack.forEach { (color, paise) ->
                    val h = (paise.toFloat() / maxTotal.toFloat()) * size.height
                    drawRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(x, yBottom - h),
                        size = androidx.compose.ui.geometry.Size(barWidth, h.coerceAtLeast(1f)),
                    )
                    yBottom -= h
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            snap.months.forEach { month ->
                Text(
                    shortMonthLabel(month),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = Mute,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

internal fun shortMonthLabel(month: MonthWindow): String = when (month.monthsAgo) {
    0 -> "Now"
    1 -> "Last"
    else -> month.shortLabel()
}

@Composable
internal fun TopSpendTrendCard(
    rank: CategoryMonthRank,
    iconName: String?,
    onClick: () -> Unit,
) {
    val accent = CategoryColors.forKey(rank.bucket.key)
    val icon = CategoryIcons.forKey(rank.bucket.key, iconName)
    val mom = momLabel(rank.thisMonthPaise, rank.lastMonthPaise)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PaperRaised)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
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
                    contentDescription = rank.bucket.label,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rank.bucket.label,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    mom,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        rank.lastMonthPaise <= 0L && rank.thisMonthPaise > 0L -> MintDeep
                        rank.thisMonthPaise > rank.lastMonthPaise -> Debit
                        rank.thisMonthPaise < rank.lastMonthPaise -> Ok
                        else -> Mute
                    },
                )
            }
            Text(
                rank.thisMonthPaise.paiseToCompactRupees(),
                fontWeight = FontWeight.SemiBold,
                color = Debit,
            )
        }
    }
}

internal fun momLabel(thisMonth: Long, lastMonth: Long): String = when {
    lastMonth <= 0L && thisMonth <= 0L -> "No spend this or last month"
    lastMonth <= 0L && thisMonth > 0L -> "New this month · ${thisMonth.paiseToCompactRupees()}"
    thisMonth == lastMonth -> "Same as last month"
    else -> {
        val pct = (((thisMonth - lastMonth).toDouble() / lastMonth.toDouble()) * 100.0)
            .roundToInt()
        val arrow = if (pct > 0) "↑" else "↓"
        "$arrow ${kotlin.math.abs(pct)}% vs last month"
    }
}

@Composable
internal fun CategorySparkline(values: List<Long>, color: Color) {
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
