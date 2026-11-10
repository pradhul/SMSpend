package app.smspend.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.smspend.data.SpendRepository
import app.smspend.data.TransactionEntity
import app.smspend.ui.theme.Ink
import app.smspend.ui.theme.Line
import app.smspend.ui.theme.MintDeep
import app.smspend.ui.theme.Mute
import app.smspend.ui.theme.Paper
import kotlinx.coroutines.launch
import app.smspend.util.runCatchingCancellable

// Search across all transactions. (Split out of the former Screens.kt.)

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
    var retagLaunch by remember { mutableStateOf<RetagLaunch?>(null) }
    var detailTarget by remember { mutableStateOf<TransactionEntity?>(null) }
    var duplicateTarget by remember { mutableStateOf<TransactionEntity?>(null) }
    var customCategoryKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var recentTags by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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
                                onClick = { retagLaunch = RetagLaunch(tx, initialTab = 0) },
                                onLongClick = { detailTarget = tx },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TransactionRowContent(
                            tx = tx,
                            subtitle = searchListSubtitle(tx),
                            onAddTag = { retagLaunch = RetagLaunch(tx, initialTab = 1) },
                            onDuplicateChip = { duplicateTarget = tx },
                        )
                    }
                    HorizontalDivider(color = Line)
                }
            }
        }
    }

    retagLaunch?.let { launch ->
        val tx = results.firstOrNull { it.id == launch.tx.id } ?: launch.tx
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
        val tx = results.firstOrNull { it.id == snap.id } ?: snap
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
        val tx = results.firstOrNull { it.id == snap.id } ?: snap
        DuplicateStatusSheet(
            transaction = tx,
            repo = repo,
            onDismiss = { duplicateTarget = null },
        )
    }
}
