package app.smspend.ui

import app.smspend.R
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.smspend.data.CategoryNoteEntity
import app.smspend.data.SpendRepository
import app.smspend.data.TransactionEntity
import app.smspend.domain.CategoryBucket
import app.smspend.domain.SpendCategory
import app.smspend.ui.theme.Debit
import app.smspend.ui.theme.Ink
import app.smspend.ui.theme.Line
import app.smspend.ui.theme.MintDeep
import app.smspend.ui.theme.Mute
import app.smspend.ui.theme.Ok
import app.smspend.ui.theme.Paper
import app.smspend.ui.theme.PaperRaised
import app.smspend.util.paiseToCompactRupees
import kotlinx.coroutines.launch
import app.smspend.util.runCatchingCancellable

// One category's transactions for a period. (Split out of the former Screens.kt.)

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
    val txnState by viewModel.transactions.collectAsStateWithLifecycle()
    val txns = txnState.orEmpty()
    val txnsReady = txnState != null
    val iconName by viewModel.iconName.collectAsStateWithLifecycle()
    val monthTotals by viewModel.monthTotals.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val total by viewModel.nettedTotal.collectAsStateWithLifecycle()
    val nettingNotice by viewModel.nettingNotice.collectAsStateWithLifecycle()
    val isSavings = !SpendCategory.countsAsSpend(bucket.key)
    val isCredits = SpendCategory.isCredits(bucket.key)
    var retagLaunch by remember { mutableStateOf<RetagLaunch?>(null) }
    var detailTarget by remember { mutableStateOf<TransactionEntity?>(null) }
    var duplicateTarget by remember { mutableStateOf<TransactionEntity?>(null) }
    var draftNote by remember { mutableStateOf("") }
    var customCategoryKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var recentTags by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accent = CategoryColors.forKey(bucket.key)
    val headerIcon = CategoryIcons.forKey(bucket.key, iconName)
    LaunchedEffect(Unit) {
        customCategoryKeys = runCatchingCancellable { repo.listCustomCategoryKeys() }.getOrDefault(emptyList())
        recentTags = runCatchingCancellable { repo.recentTags() }.getOrDefault(emptyList())
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
                if (isSavings && !isCredits) {
                    Text(
                        "Set aside — not spending",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ok,
                    )
                }
                if (isCredits) {
                    Text(
                        "Credits into your banks — tag to offset matching spends, or leave as Credits",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ok,
                    )
                }
                Text(
                    if (txnsReady) total.paiseToCompactRupees() else "…",
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (isSavings || isCredits) Ok else Debit,
                )
                Text(
                    if (txnsReady) "${txns.size} payments" else "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mute,
                )
                nettingNotice?.let { notice ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(notice, style = MaterialTheme.typography.bodyMedium, color = MintDeep)
                }
            }
        }
        if (monthTotals.size >= 2) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.category_this_year), style = MaterialTheme.typography.labelMedium, color = Mute)
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
        Text(stringResource(R.string.category_notes), style = MaterialTheme.typography.titleMedium, color = Ink)
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
                    Text(stringResource(R.string.category_delete), color = Debit)
                }
            }
        }
        OutlinedTextField(
            value = draftNote,
            onValueChange = { draftNote = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.category_add_a_note)) },
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
            Text(stringResource(R.string.category_add_note), color = MintDeep)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.category_transactions), style = MaterialTheme.typography.titleMedium, color = Ink)
        Text(
            "Tap category · tag button · hold for SMS",
            style = MaterialTheme.typography.bodyMedium,
            color = Mute,
        )
        LazyColumn {
            items(txns, key = { it.id }) { tx ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { retagLaunch = RetagLaunch(tx, initialTab = 0) },
                            onLongClick = { detailTarget = tx },
                        )
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransactionRowContent(
                        tx = tx,
                        subtitle = txnListSubtitle(tx),
                        onAddTag = { retagLaunch = RetagLaunch(tx, initialTab = 1) },
                        onDuplicateChip = { duplicateTarget = tx },
                    )
                }
                HorizontalDivider(color = Line)
            }
        }
    }

    retagLaunch?.let { launch ->
        val tx = txns.firstOrNull { it.id == launch.tx.id } ?: launch.tx
        RetagSheet(
            transaction = tx,
            hasApiKey = hasApiKey,
            customCategoryKeys = customCategoryKeys,
            recentTags = recentTags,
            initialTab = launch.initialTab,
            onDismiss = { retagLaunch = null },
            onSave = { categoryKey, applyAll ->
                scope.launch {
                    repo.retag(tx.id, categoryKey, applyAll)
                    retagLaunch = null
                    customCategoryKeys =
                        runCatchingCancellable { repo.listCustomCategoryKeys() }.getOrDefault(customCategoryKeys)
                }
            },
            onSaveTag = { tag ->
                scope.launch {
                    repo.setTransactionNote(tx.id, tag)
                    recentTags = runCatchingCancellable { repo.recentTags() }.getOrDefault(recentTags)
                    retagLaunch = null
                }
            },
            onAskGemini = { applyAll ->
                scope.launch {
                    repo.askGeminiForTransaction(tx.id, applyAll)
                    retagLaunch = null
                }
            },
        )
    }

    detailTarget?.let { snap ->
        val tx = txns.firstOrNull { it.id == snap.id } ?: snap
        TransactionDetailSheet(
            transaction = tx,
            hasApiKey = hasApiKey,
            repo = repo,
            onDismiss = { detailTarget = null },
            onChangeCategory = {
                detailTarget = null
                retagLaunch = RetagLaunch(tx, initialTab = 0)
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
    duplicateTarget?.let { snap ->
        val tx = txns.firstOrNull { it.id == snap.id } ?: snap
        DuplicateStatusSheet(
            transaction = tx,
            repo = repo,
            onDismiss = { duplicateTarget = null },
        )
    }
}
