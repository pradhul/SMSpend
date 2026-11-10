package app.smspend.ui

import app.smspend.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.smspend.data.DuplicatePairCandidate
import app.smspend.ui.theme.Ink
import app.smspend.ui.theme.Line
import app.smspend.ui.theme.Mint
import app.smspend.ui.theme.MintDeep
import app.smspend.ui.theme.Mute
import app.smspend.ui.theme.PaperRaised
import androidx.compose.foundation.layout.heightIn

// My banks picker and duplicate review sheets. (Split out of the former Screens.kt.)

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
    val banks = remember(query, selectedIds, detectedIds) {
        app.smspend.domain.IndianBanks.ALL
            .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            .sortedWith(
                compareByDescending<app.smspend.domain.IndianBank> { it.id in selectedIds }
                    .thenByDescending { it.id in detectedIds }
                    .thenBy { it.label.lowercase() },
            )
    }
    val selectedCount = banks.count { it.id in selectedIds }
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
            Text(stringResource(R.string.bank_and_duplicate_your_banks), style = MaterialTheme.typography.titleLarge, color = Ink)
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
                label = { Text(stringResource(R.string.bank_and_duplicate_search_banks)) },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (selectedCount > 0 && query.isBlank()) {
                    item(key = "hdr-selected") {
                        Text(
                            "Selected · $selectedCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MintDeep,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                items(
                    count = banks.size,
                    key = { banks[it].id },
                ) { index ->
                    val bank = banks[index]
                    val checked = bank.id in selectedIds
                    val detected = bank.id in detectedIds
                    val showOtherHeader = query.isBlank() &&
                        selectedCount > 0 &&
                        index == selectedCount
                    Column {
                        if (showOtherHeader) {
                            Text(
                                "Other banks",
                                style = MaterialTheme.typography.labelMedium,
                                color = Mute,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
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
                                    Text(
                                        "Seen in SMS",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MintDeep,
                                    )
                                }
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
                Text(stringResource(R.string.bank_and_duplicate_save_banks))
            }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bank_and_duplicate_skip_for_now), color = Mute)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/**
 * Post-sync duplicate review: pages through suspected pairs one at a time
 * using the shared [DuplicateCompareSheet]. Decided pairs are removed from
 * [pairs] by the view model; skipped pairs stay and are tracked locally.
 */
@Composable
fun DuplicateReviewSheet(
    pairs: List<DuplicatePairCandidate>,
    onKeepBoth: (String) -> Unit,
    onMuteLater: (String) -> Unit,
    onRemoveLater: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialTotal = remember { pairs.size }
    var skippedIds by remember { mutableStateOf(setOf<String>()) }
    val current = pairs.firstOrNull { it.pairId !in skippedIds }
    LaunchedEffect(current == null) {
        if (current == null) onDismiss()
    }
    if (current == null) return
    val decided = (initialTotal - pairs.size).coerceAtLeast(0)
    val position = (decided + skippedIds.size + 1).coerceIn(1, initialTotal)
    DuplicateCompareSheet(
        pair = current,
        positionLabel = "Pair $position of $initialTotal",
        onKeepBoth = { onKeepBoth(current.pairId) },
        onMute = { onMuteLater(current.pairId) },
        onDelete = { onRemoveLater(current.laterId) },
        onSkip = { skippedIds = skippedIds + current.pairId },
        dismissLabel = "Later",
        onDismiss = onDismiss,
    )
}

@Composable
internal fun DuplicateSideBlock(
    title: String,
    titleColor: Color,
    merchant: String,
    timeLabel: String,
    sender: String,
    note: String,
    snippet: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PaperRaised)
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = titleColor)
        Text(
            merchant.ifBlank { "Unknown" },
            fontWeight = FontWeight.SemiBold,
            color = Ink,
        )
        Text(
            "$timeLabel · $sender",
            style = MaterialTheme.typography.bodySmall,
            color = Mute,
        )
        if (note.isNotBlank()) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = Mute,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            snippet,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = Ink,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(max = 140.dp),
        )
    }
}
