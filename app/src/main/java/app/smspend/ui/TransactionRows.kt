package app.smspend.ui

import app.smspend.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.smspend.data.SmsDebitParser
import app.smspend.data.TransactionEntity
import app.smspend.data.WrongParseResult
import app.smspend.domain.SpendCategory
import app.smspend.ui.theme.Debit
import app.smspend.ui.theme.Ink
import app.smspend.ui.theme.MintDeep
import app.smspend.ui.theme.Mute
import app.smspend.ui.theme.Ok
import app.smspend.util.DateRanges
import app.smspend.util.paiseToCompactRupees
import androidx.compose.ui.text.style.TextDecoration

// Transaction list rows and status chips shared by Home, Category and Search. (Split out of the former Screens.kt.)

/** Subtle marker for rows still waiting on the batched Gemini pass. */
internal fun aiPendingSuffix(tx: TransactionEntity): String =
    if (tx.aiStatus == TransactionEntity.AI_PENDING) " · AI pending" else ""

/** Status tags that render as a subcategory-style chip in the row itself. */
@Composable
internal fun statusChipFor(tx: TransactionEntity): Pair<String, Color>? = when {
    tx.isAtmFailed() -> "Failed" to Debit
    tx.isCardBillPaymentNote() -> "Card payment" to Mute
    tx.isPossibleDuplicateNote() -> "Duplicate" to Mute
    tx.statusTag.startsWith("Paired ·", ignoreCase = true) -> "Self transfer" to Mute
    tx.statusTag.equals(SmsDebitParser.SELF_RD_WENT_NOTE, ignoreCase = true) -> "Self transfer" to Mute
    tx.statusTag.equals(SmsDebitParser.SELF_RD_FROM_NOTE, ignoreCase = true) -> "From own account" to Mute
    tx.isCredit() -> "Credit" to Ok
    else -> null
}

internal fun cardSpendChipLabel(tx: TransactionEntity): String? = tx.cardSpendLabel()

internal fun hasSystemStatus(tx: TransactionEntity): Boolean =
    tx.isAtmFailed() || tx.isCardBillPaymentNote() || tx.isPossibleDuplicateNote() ||
        tx.statusTag.startsWith("Paired ·", ignoreCase = true) ||
        tx.statusTag.equals(SmsDebitParser.SELF_RD_WENT_NOTE, ignoreCase = true) ||
        tx.statusTag.equals(SmsDebitParser.SELF_RD_FROM_NOTE, ignoreCase = true)

/** @deprecated Use [hasSystemStatus] — status no longer lives in note. */
internal fun hasStatusChipNote(tx: TransactionEntity): Boolean = hasSystemStatus(tx)

internal fun txnListSubtitle(tx: TransactionEntity): String {
    // Tags render as chips on their own row — keep the subtitle to time only.
    val time = DateRanges.formatTxnTime(tx.occurredAt)
    return time + aiPendingSuffix(tx)
}

internal fun searchListSubtitle(tx: TransactionEntity): String {
    val catTime = "${SpendCategory.displayLabel(tx.category)} · ${DateRanges.formatTxnTime(tx.occurredAt)}"
    return catTime + aiPendingSuffix(tx)
}

@Composable
internal fun StatusChip(
    label: String,
    tint: Color,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(tint.copy(alpha = 0.12f))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 8.dp, vertical = 2.dp)
    Box(modifier = base) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TransactionRowContent(
    tx: TransactionEntity,
    subtitle: String,
    onAddTag: (() -> Unit)? = null,
    onDuplicateChip: (() -> Unit)? = null,
) {
    val chip = statusChipFor(tx)
    val failed = tx.isAtmFailed()
    val mutedAmount = failed || tx.isCardBillPaymentNote() || tx.isPossibleDuplicateNote()
    val credit = tx.isCredit()
    val tags = tx.userTags()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                tx.merchantRaw,
                fontWeight = FontWeight.SemiBold,
                color = if (failed) Ink.copy(alpha = 0.45f) else Ink,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (chip != null) {
                    StatusChip(
                        label = chip.first,
                        tint = chip.second,
                        onClick = if (tx.isPossibleDuplicateNote()) onDuplicateChip else null,
                    )
                }
                if (tx.isAiFound()) {
                    StatusChip(label = "AI", tint = MintDeep)
                }
                cardSpendChipLabel(tx)?.let { label ->
                    StatusChip(label = label, tint = MintDeep)
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mute,
                )
            }
            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tags.forEach { tag ->
                        StatusChip(label = tag, tint = MintDeep)
                    }
                }
            }
        }
        if (onAddTag != null) {
            IconButton(
                onClick = onAddTag,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.LocalOffer,
                    contentDescription = stringResource(R.string.transaction_rows_add_tag),
                    tint = if (tx.note.isNotBlank()) MintDeep else Mute,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            if (credit) "+${tx.amountPaise.paiseToCompactRupees()}" else tx.amountPaise.paiseToCompactRupees(),
            fontWeight = if (mutedAmount) FontWeight.Medium else FontWeight.SemiBold,
            color = when {
                credit -> Ok
                mutedAmount -> Mute
                else -> Debit
            },
            textDecoration = if (failed) TextDecoration.LineThrough else TextDecoration.None,
        )
    }
}

internal data class RetagLaunch(
    val tx: TransactionEntity,
    val initialTab: Int = 0,
)

internal fun wrongParseMessage(result: WrongParseResult): String = when (result) {
    is WrongParseResult.Fixed -> buildString {
        append("Amount fixed to ${result.newAmountPaise.paiseToCompactRupees()}")
        if (result.ruleSaved) append(" · rule saved for next time")
    }
    is WrongParseResult.Removed ->
        if (result.ruleSaved) "Removed · similar messages will be skipped"
        else "Removed · won't be counted again"
    WrongParseResult.NotFound -> "Already removed"
}
