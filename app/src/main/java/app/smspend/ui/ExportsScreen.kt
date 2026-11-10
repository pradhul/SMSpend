package app.smspend.ui

import app.smspend.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.smspend.data.SpendReportRow
import app.smspend.ui.theme.Debit
import app.smspend.ui.theme.Ink
import app.smspend.ui.theme.Line
import app.smspend.ui.theme.Mint
import app.smspend.ui.theme.MintDeep
import app.smspend.ui.theme.MintWash
import app.smspend.ui.theme.Mute
import app.smspend.ui.theme.Paper
import app.smspend.ui.theme.PaperRaised
import app.smspend.util.DateRanges
import app.smspend.util.paiseToCompactRupees
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ReportDayGroup(
    val dayStartMs: Long,
    val rows: List<SpendReportRow>,
)

/**
 * Exports page: the current month's recorded spendings as a spreadsheet-style report
 * that can be read on the phone and saved as CSV (opens in Google Sheets / Excel).
 */
@Composable
fun ExportsScreen(
    viewModel: ExportsViewModel,
    hasApiKey: Boolean,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.onScreenVisible() }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        viewModel.consumeMessage()
    }

    val report = state.report
    val groups = remember(report) {
        report?.rows.orEmpty()
            .groupBy { DateRanges.startOfTodayMs(it.occurredAt) }
            .entries
            .sortedBy { it.key }
            .map { ReportDayGroup(it.key, it.value) }
    }

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
            Text(stringResource(R.string.exports_report_this_month), style = MaterialTheme.typography.titleLarge, color = Ink)
        }
        Spacer(modifier = Modifier.height(8.dp))
        when {
            report == null -> Text(
                "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            report.rows.isEmpty() -> Text(
                "No spends recorded this month yet",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "summary") {
                    ReportSummaryCard(
                        monthSubtitle = report.month.subtitle(),
                        totalPaise = report.totalPaise,
                        rowCount = report.rows.size,
                        exporting = state.exporting,
                        notesLoading = state.notesLoading,
                        hasApiKey = hasApiKey,
                        hasNotes = state.dateNotes.isNotEmpty(),
                        onExportCsv = viewModel::exportCsv,
                        onAnnotateDates = viewModel::annotateDates,
                    )
                }
                item(key = "table-header") {
                    ReportTableHeader()
                }
                groups.forEach { group ->
                    item(key = "day-${group.dayStartMs}") {
                        ReportDayHeader(
                            dayStartMs = group.dayStartMs,
                            note = state.dateNotes[group.dayStartMs],
                        )
                    }
                    items(
                        count = group.rows.size,
                        key = { i -> "tx-${group.rows[i].txId}" },
                    ) { i ->
                        ReportTxRow(row = group.rows[i], isLast = i == group.rows.size - 1)
                    }
                }
                item(key = "total") {
                    ReportTotalRow(totalPaise = report.totalPaise)
                }
            }
        }
    }
}

@Composable
private fun ReportSummaryCard(
    monthSubtitle: String,
    totalPaise: Long,
    rowCount: Int,
    exporting: Boolean,
    notesLoading: Boolean,
    hasApiKey: Boolean,
    hasNotes: Boolean,
    onExportCsv: () -> Unit,
    onAnnotateDates: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PaperRaised)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Text(stringResource(R.string.exports_all_recorded_spendings), style = MaterialTheme.typography.labelMedium, color = Mute)
        Text(
            totalPaise.paiseToCompactRupees(),
            style = MaterialTheme.typography.headlineMedium,
            color = Debit,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "$monthSubtitle · $rowCount spends",
            style = MaterialTheme.typography.bodyMedium,
            color = Mute,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onExportCsv,
                enabled = !exporting,
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Mint),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Mint,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.exports_save_csv), style = MaterialTheme.typography.labelLarge)
            }
            if (hasApiKey) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onAnnotateDates, enabled = !notesLoading) {
                    if (notesLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = MintDeep,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        if (hasNotes) "Refresh day notes" else "Gemini day notes",
                        color = MintDeep,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "CSV saves to Downloads and opens in Google Sheets or Excel",
            style = MaterialTheme.typography.bodyMedium,
            color = Mute,
        )
    }
}

@Composable
private fun ReportTableHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(PaperRaised)
            .border(1.dp, Line, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "SPENDING",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = Mute,
            )
            Text(
                "AMOUNT",
                style = MaterialTheme.typography.labelMedium,
                color = Mute,
            )
        }
    }
}

@Composable
private fun ReportDayHeader(dayStartMs: Long, note: String?) {
    val dayLabel = remember(dayStartMs) {
        SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(dayStartMs))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            dayLabel,
            style = MaterialTheme.typography.labelLarge,
            color = Ink,
        )
        if (note != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MintWash.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    note,
                    style = MaterialTheme.typography.labelMedium,
                    color = MintDeep,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReportTxRow(row: SpendReportRow, isLast: Boolean) {
    val time = remember(row.txId) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(row.occurredAt))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PaperRaised)
            .border(1.dp, Line)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    row.merchant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${row.aboutText} · $time",
                    style = MaterialTheme.typography.labelMedium,
                    color = Mute,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                row.amountPaise.paiseToCompactRupees(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Debit,
            )
        }
        if (!isLast) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = Line.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun ReportTotalRow(totalPaise: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .background(PaperRaised)
            .border(1.dp, Line, RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Total · this month",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = Ink,
        )
        Text(
            totalPaise.paiseToCompactRupees(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Debit,
        )
    }
}
