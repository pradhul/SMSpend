package app.smspend.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.smspend.data.DuplicatePairCandidate
import app.smspend.data.SpendRepository
import app.smspend.data.TransactionEntity
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
import app.smspend.util.paiseToCompactRupees
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.text.style.TextDecoration
import java.util.concurrent.TimeUnit

// Bottom sheets for a single transaction: details, duplicates, re-tagging. (Split out of the former Screens.kt.)

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
    var duplicatePeers by remember { mutableStateOf<List<TransactionEntity>>(emptyList()) }
    var showMarkDuplicate by remember { mutableStateOf(false) }
    var confirmDuplicatePeer by remember { mutableStateOf<TransactionEntity?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val credit = transaction.isCredit()

    LaunchedEffect(transaction.id, transaction.statusTag, transaction.note, transaction.category) {
        recentTags = repo.recentTags()
        duplicatePeers = repo.findNearbyDuplicatePeers(transaction)
        draftNote = transaction.note
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
                color = if (transaction.isAtmFailed()) Ink.copy(alpha = 0.45f) else Ink,
            )
            Text(
                if (credit) {
                    "+${transaction.amountPaise.paiseToCompactRupees()}"
                } else {
                    transaction.amountPaise.paiseToCompactRupees()
                },
                style = MaterialTheme.typography.headlineMedium,
                color = when {
                    credit -> Ok
                    transaction.isAtmFailed() || transaction.isCardBillPaymentNote() ||
                        transaction.isPossibleDuplicateNote() -> Mute
                    else -> Debit
                },
                textDecoration = if (transaction.isAtmFailed()) TextDecoration.LineThrough else TextDecoration.None,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                statusChipFor(transaction)?.let { chip ->
                    StatusChip(label = chip.first, tint = chip.second)
                }
                if (transaction.isAiFound()) {
                    StatusChip(label = "AI", tint = MintDeep)
                }
                Text(
                    "${SpendCategory.displayLabel(transaction.category)} · " +
                        DateRanges.formatTxnTime(transaction.occurredAt) +
                        aiPendingSuffix(transaction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mute,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("TAG", style = MaterialTheme.typography.labelMedium, color = Mute)
            if (transaction.statusTag.isNotBlank()) {
                Text(
                    "Status: ${transaction.statusTag}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mute,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            cardSpendChipLabel(transaction)?.let { label ->
                StatusChip(label = label, tint = MintDeep)
                Spacer(modifier = Modifier.height(8.dp))
            }
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
                Text("Save tags", color = MintDeep)
            }
            if (recentTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val draftTagList = draftNote.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    recentTags.forEach { tag ->
                        val norm = TransactionEntity.normalizeUserTag(tag)
                        val selectedTag =
                            draftTagList.any { TransactionEntity.normalizeUserTag(it) == norm }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, if (selectedTag) Ink else Line, RoundedCornerShape(10.dp))
                                .background(if (selectedTag) Mint.copy(alpha = 0.35f) else Paper)
                                .toggleable(value = selectedTag, role = Role.Checkbox) {
                                    val next = if (selectedTag) {
                                        draftTagList.filterNot {
                                            TransactionEntity.normalizeUserTag(it) == norm
                                        }
                                    } else {
                                        draftTagList + tag
                                    }
                                    draftNote = next.joinToString(", ")
                                    scope.launch {
                                        repo.setTransactionNote(transaction.id, draftNote)
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
            Text("STATUS", style = MaterialTheme.typography.labelMedium, color = Mute)
            if (transaction.isPossibleDuplicateNote()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            repo.markNotDuplicate(transaction.id)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Not a duplicate", color = Ok)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (!transaction.isCredit()) {
                if (showMarkDuplicate) {
                    Text(
                        "Pick the matching transaction",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Mute,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (duplicatePeers.isEmpty()) {
                        Text(
                            "No same-amount peers within 24h",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Mute,
                        )
                    } else {
                        duplicatePeers.take(6).forEach { peer ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, Line, RoundedCornerShape(10.dp))
                                    .clickable { confirmDuplicatePeer = peer }
                                    .padding(12.dp),
                            ) {
                                Column {
                                    Text(peer.merchantRaw, fontWeight = FontWeight.SemiBold, color = Ink)
                                    Text(
                                        "${peer.amountPaise.paiseToCompactRupees()} · ${DateRanges.formatTxnTime(peer.occurredAt)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Mute,
                                    )
                                }
                            }
                        }
                    }
                    TextButton(onClick = { showMarkDuplicate = false }) {
                        Text("Cancel", color = Mute)
                    }
                } else if (!transaction.isPossibleDuplicateNote()) {
                    OutlinedButton(
                        onClick = { showMarkDuplicate = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Mark as duplicate", color = Ink)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (transaction.isCardBillPaymentNote()) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                repo.clearCardBillPayment(transaction.id)
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Not a card bill payment", color = Debit)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                repo.markAsCardBillPayment(transaction.id)
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Mark as card bill payment", color = Ink)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
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
            if (transaction.isLearnedParse()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (transaction.isAiFound()) {
                        "Gemini found this in an SMS the built-in rules didn't recognise."
                    } else {
                        "Read with a format SMSpend learned from an earlier AI find."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Mute,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            val removedTemplates = repo.markNotTransaction(transaction.id)
                            Toast.makeText(
                                context,
                                when {
                                    removedTemplates == null -> "Already removed"
                                    removedTemplates > 0 -> "Removed · learned format forgotten"
                                    else -> "Removed · won't be counted again"
                                },
                                Toast.LENGTH_LONG,
                            ).show()
                            onDismiss()
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Not a transaction", color = Debit)
                }
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
    confirmDuplicatePeer?.let { peer ->
        val laterId = if (transaction.occurredAt <= peer.occurredAt) peer.id else transaction.id
        val earlierId = if (laterId == peer.id) transaction.id else peer.id
        DuplicateCompareSheet(
            pair = pairCandidateFor(transaction, peer),
            onKeepBoth = {
                scope.launch {
                    repo.rememberNotDuplicatePair(earlierId, laterId)
                    confirmDuplicatePeer = null
                    showMarkDuplicate = false
                }
            },
            onMute = {
                scope.launch {
                    repo.markAsDuplicate(transaction.id, peer.id)
                    confirmDuplicatePeer = null
                    onDismiss()
                }
            },
            onDelete = {
                scope.launch {
                    repo.removeDuplicateLaters(listOf(laterId))
                    confirmDuplicatePeer = null
                    onDismiss()
                }
            },
            deleteLabel = "Delete the later copy",
            dismissLabel = "Cancel",
            onDismiss = { confirmDuplicatePeer = null },
        )
    }
}

/**
 * Chip-tap entry point: loads the matched twin and shows the shared compare sheet.
 * The tapped transaction is already muted, so the actions are Keep both / Delete / Keep muted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DuplicateStatusSheet(
    transaction: TransactionEntity,
    repo: SpendRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var peer by remember(transaction.id) { mutableStateOf<TransactionEntity?>(null) }
    var loaded by remember(transaction.id) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(transaction.id) {
        peer = repo.findDuplicatePeer(transaction.id)
        loaded = true
    }
    if (!loaded) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = PaperRaised,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .fillMaxWidth(),
            ) {
                Text("Possible duplicate", style = MaterialTheme.typography.titleLarge, color = Ink)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Checking the other copy…", style = MaterialTheme.typography.bodyMedium, color = Mute)
            }
        }
        return
    }
    val matched = peer
    if (matched == null) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = PaperRaised,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .fillMaxWidth(),
            ) {
                Text("Possible duplicate", style = MaterialTheme.typography.titleLarge, color = Ink)
                Text(
                    "${transaction.merchantRaw} · ${transaction.amountPaise.paiseToCompactRupees()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mute,
                )
                Text(
                    "No matching transaction found nearby. It's muted — excluded from totals.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mute,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            repo.markNotDuplicate(transaction.id)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Mint),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Not a duplicate — count it")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Keep muted", color = Mute)
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    } else {
        val flaggedIsEarlier = transaction.occurredAt <= matched.occurredAt
        DuplicateCompareSheet(
            pair = pairCandidateFor(transaction, matched),
            earlierTitle = if (flaggedIsEarlier) "This one · muted" else "Counted in totals",
            laterTitle = if (flaggedIsEarlier) "Counted in totals" else "This one · muted",
            onKeepBoth = {
                scope.launch {
                    repo.markNotDuplicate(transaction.id)
                    onDismiss()
                }
            },
            onMute = null,
            onDelete = {
                scope.launch {
                    repo.removeDuplicateLaters(listOf(transaction.id))
                    onDismiss()
                }
            },
            deleteLabel = "Delete the muted copy",
            dismissLabel = "Keep muted",
            onDismiss = onDismiss,
        )
    }
}

/** Builds a compare-sheet pair from two loaded transactions, ordered by time. */
internal fun pairCandidateFor(a: TransactionEntity, b: TransactionEntity): DuplicatePairCandidate {
    val earlier = if (a.occurredAt <= b.occurredAt) a else b
    val later = if (a.occurredAt <= b.occurredAt) b else a
    return DuplicatePairCandidate(
        pairId = "${earlier.id}|${later.id}",
        earlierId = earlier.id,
        laterId = later.id,
        amountPaise = later.amountPaise,
        earlierSender = earlier.sender,
        laterSender = later.sender,
        earlierSnippet = earlier.bodySnippet,
        laterSnippet = later.bodySnippet,
        earlierMerchant = earlier.merchantRaw,
        laterMerchant = later.merchantRaw,
        earlierAt = earlier.occurredAt,
        laterAt = later.occurredAt,
        earlierNote = earlier.note,
        laterNote = later.note,
    )
}

internal fun duplicateGapLabel(earlierAt: Long, laterAt: Long): String {
    val gapMs = abs(laterAt - earlierAt)
    return when {
        gapMs < TimeUnit.MINUTES.toMillis(1) -> "same minute"
        gapMs < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(gapMs)} min apart"
        else -> "${TimeUnit.MILLISECONDS.toHours(gapMs)} h apart"
    }
}

/**
 * The single duplicate-resolution surface used by every flow: post-sync review,
 * chip tap, and the detail-sheet "Mark as duplicate" confirmation.
 * Shows both transactions with three actions — Keep both, Mute (optional), Delete (confirmed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateCompareSheet(
    pair: DuplicatePairCandidate,
    positionLabel: String? = null,
    earlierTitle: String = "Earlier · counted",
    laterTitle: String = "Later copy",
    onKeepBoth: () -> Unit,
    onMute: (() -> Unit)? = null,
    onDelete: () -> Unit,
    deleteLabel: String = "Delete this copy",
    onSkip: (() -> Unit)? = null,
    dismissLabel: String = "Done",
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember(pair.pairId) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PaperRaised,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Possible duplicate", style = MaterialTheme.typography.titleLarge, color = Ink)
                positionLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Mute)
                }
            }
            Text(
                "${pair.amountPaise.paiseToCompactRupees()} · " +
                    "${duplicateGapLabel(pair.earlierAt, pair.laterAt)} — same payment reported twice?",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
            )
            Spacer(modifier = Modifier.height(12.dp))
            DuplicateSideBlock(
                title = earlierTitle,
                titleColor = Ok,
                merchant = pair.earlierMerchant,
                timeLabel = DateRanges.formatTxnTime(pair.earlierAt),
                sender = pair.earlierSender,
                note = pair.earlierNote,
                snippet = pair.earlierSnippet,
            )
            Spacer(modifier = Modifier.height(8.dp))
            DuplicateSideBlock(
                title = laterTitle,
                titleColor = Mute,
                merchant = pair.laterMerchant,
                timeLabel = DateRanges.formatTxnTime(pair.laterAt),
                sender = pair.laterSender,
                note = pair.laterNote,
                snippet = pair.laterSnippet,
            )
            Spacer(modifier = Modifier.height(14.dp))
            if (onMute != null) {
                Button(
                    onClick = onMute,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Mint),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Mute this copy")
                }
                Text(
                    "Keeps it visible but excludes it from totals. Reversible anytime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Mute,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = onKeepBoth,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Keep both — separate payments", color = Ok)
            }
            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(deleteLabel, color = Debit)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (onSkip != null) {
                    TextButton(onClick = onSkip) {
                        Text("Skip", color = Mute)
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text(dismissLabel, color = Mute)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = PaperRaised,
            title = { Text("Delete this copy?", color = Ink) },
            text = {
                Text(
                    "Removes it from SMSpend and skips it on future scans. " +
                        "Your SMS inbox is not affected.",
                    color = Mute,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text("Delete", color = Debit)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel", color = Mute)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RetagSheet(
    transaction: TransactionEntity,
    hasApiKey: Boolean,
    customCategoryKeys: List<String> = emptyList(),
    recentTags: List<String> = emptyList(),
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onSave: (categoryKey: String, applyAll: Boolean) -> Unit,
    onSaveTag: (tag: String) -> Unit = {},
    onAskGemini: (applyAll: Boolean) -> Unit,
) {
    var selectedTab by remember(transaction.id, initialTab) {
        mutableStateOf(initialTab.coerceIn(0, 1))
    }
    var selected by remember(transaction.id) {
        mutableStateOf(
            if (SpendCategory.isCustomKey(transaction.category)) {
                null
            } else {
                SpendCategory.fromStored(transaction.category)
            },
        )
    }
    var selectedCustomKey by remember(transaction.id) {
        mutableStateOf(
            if (SpendCategory.isCustomKey(transaction.category)) transaction.category else null,
        )
    }
    var customText by remember(transaction.id) {
        mutableStateOf(
            if (SpendCategory.isCustomKey(transaction.category)) {
                SpendCategory.displayLabel(transaction.category)
            } else {
                ""
            },
        )
    }
    // Credits never apply-to-similar: they often share a bank-label merchant, so
    // bulk retag would hit every credit into that bank (repository guards this too).
    var applyAll by remember(transaction.id) { mutableStateOf(!transaction.isCredit()) }
    var busy by remember(transaction.id) { mutableStateOf(false) }
    var savePressed by remember(transaction.id) { mutableStateOf(false) }
    val systemNote = hasSystemStatus(transaction)
    var draftTags by remember(transaction.id, transaction.note) {
        mutableStateOf(transaction.userTags())
    }
    var newTagText by remember(transaction.id) { mutableStateOf("") }
    LaunchedEffect(transaction.id, transaction.note, transaction.statusTag) {
        draftTags = transaction.userTags()
        newTagText = ""
    }
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
            Text(
                if (selectedTab == 0) "CHANGE CATEGORY" else "TAGS",
                style = MaterialTheme.typography.labelMedium,
                color = Mute,
            )
            Text(
                "${transaction.merchantRaw} · ${transaction.amountPaise.paiseToCompactRupees()}",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = PaperRaised,
                contentColor = Ink,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Category") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Tags") },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(90)))
                        .using(
                            SizeTransform(clip = false) { _, _ ->
                                tween(280, easing = FastOutSlowInEasing)
                            },
                        )
                },
                label = "retagTabContent",
            ) { tab ->
                Column {
                    if (tab == 0) {
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
                                        .selectable(selected = selectedChip, role = Role.RadioButton) {
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
                                            .selectable(selected = selectedChip, role = Role.RadioButton) {
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
                if (!transaction.isCredit()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                        Text(
                            "Apply to all similar (${transaction.merchantRaw})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Ink,
                        )
                    }
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
            } else {
                if (systemNote) {
                    statusChipFor(transaction)?.let { chip ->
                        StatusChip(label = chip.first, tint = chip.second)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        "System status (${transaction.statusTag}) is separate from your tag. Clear status only if you want this row to count as normal spend.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Mute,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { onSaveTag("") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Clear status tag", color = Debit)
                    }
                }
                fun mergedTags(): List<String> {
                    val extra = newTagText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val out = mutableListOf<String>()
                    val seen = mutableSetOf<String>()
                    for (tag in draftTags + extra) {
                        if (seen.add(TransactionEntity.normalizeUserTag(tag))) out.add(tag)
                    }
                    return out
                }

                val storedNorm = transaction.userTags().map { TransactionEntity.normalizeUserTag(it) }
                val draftDirty =
                    mergedTags().map { TransactionEntity.normalizeUserTag(it) } != storedNorm

                if (draftTags.isNotEmpty()) {
                    Text("Tags on this transaction", style = MaterialTheme.typography.labelMedium, color = Mute)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        draftTags.forEach { tag ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, Ink, RoundedCornerShape(10.dp))
                                    .background(Mint.copy(alpha = 0.35f))
                                    .clickable { draftTags = draftTags - tag }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    "$tag  ✕",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Ink,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = newTagText,
                    onValueChange = { newTagText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Add tag") },
                    placeholder = { Text("Bike taxi, Grocery, Rahul…") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Ink,
                        unfocusedBorderColor = Line,
                        focusedLabelColor = Mute,
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onSaveTag(mergedTags().joinToString(", ")) },
                    enabled = draftDirty,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Mint),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Save tags")
                }
                if (!systemNote && transaction.userTags().isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { onSaveTag("") }) {
                        Text("Remove all tags", color = Debit)
                    }
                }
                if (recentTags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Recent tags", style = MaterialTheme.typography.labelMedium, color = Mute)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        recentTags.forEach { tag ->
                            val norm = TransactionEntity.normalizeUserTag(tag)
                            val selectedTag =
                                draftTags.any { TransactionEntity.normalizeUserTag(it) == norm }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, if (selectedTag) Ink else Line, RoundedCornerShape(10.dp))
                                    .background(if (selectedTag) Mint.copy(alpha = 0.35f) else Paper)
                                    .toggleable(value = selectedTag, role = Role.Checkbox) {
                                        draftTags = if (selectedTag) {
                                            draftTags.filterNot {
                                                TransactionEntity.normalizeUserTag(it) == norm
                                            }
                                        } else {
                                            draftTags + tag
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(tag, style = MaterialTheme.typography.bodyMedium, color = Ink)
                            }
                        }
                    }
                }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
