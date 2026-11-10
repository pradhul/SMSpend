package app.smspend.ui

import app.smspend.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.smspend.ui.theme.Debit
import app.smspend.ui.theme.Ink
import app.smspend.ui.theme.Line
import app.smspend.ui.theme.Mint
import app.smspend.ui.theme.MintDeep
import app.smspend.ui.theme.ThemeMode
import app.smspend.ui.theme.Mute
import app.smspend.ui.theme.Ok
import app.smspend.ui.theme.Paper
import app.smspend.ui.theme.PaperRaised
import kotlinx.coroutines.launch

// Settings. (Split out of the former Screens.kt.)

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
    var draftSelfNames by remember { mutableStateOf(state.selfNameAliases) }
    var keyFocused by remember { mutableStateOf(false) }
    var budgetFocused by remember { mutableStateOf(false) }
    var namesFocused by remember { mutableStateOf(false) }
    LaunchedEffect(state.apiKey) {
        if (!keyFocused) draftKey = state.apiKey
    }
    LaunchedEffect(state.monthlyBudgetPaise) {
        if (!budgetFocused) {
            draftBudget = state.monthlyBudgetPaise?.let { (it / 100).toString() }.orEmpty()
        }
    }
    LaunchedEffect(state.selfNameAliases) {
        if (!namesFocused) draftSelfNames = state.selfNameAliases
    }
    val needsStoragePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted || !needsStoragePermission) {
            when (pendingExport) {
                "all" -> viewModel.exportOtherSms(all = true)
                "other" -> viewModel.exportOtherSms(all = false)
                "log" -> viewModel.exportCategorizationLog()
            }
        } else {
            Toast.makeText(
                context,
                "Storage permission needed to save the dump",
                Toast.LENGTH_SHORT,
            ).show()
        }
        pendingExport = null
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.category_back), tint = Ink)
            }
            Text(stringResource(R.string.home_settings), style = MaterialTheme.typography.titleLarge, color = Ink)
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.settings_gemini_api_key),
            body = stringResource(R.string.settings_used_for_unknown_merchants_and),
        ) {
            OutlinedTextField(
                value = draftKey,
                onValueChange = { draftKey = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { keyFocused = it.isFocused },
                label = { Text(stringResource(R.string.onboarding_api_key)) },
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
                Text(stringResource(R.string.settings_save_check_key), color = MintDeep)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.settings_monthly_budget),
            body = stringResource(R.string.settings_how_much_you_plan_to),
        ) {
            OutlinedTextField(
                value = draftBudget,
                onValueChange = { draftBudget = it.filter { ch -> ch.isDigit() } },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { budgetFocused = it.isFocused },
                label = { Text(stringResource(R.string.settings_rupees_per_month)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ink,
                    unfocusedBorderColor = Line,
                    focusedLabelColor = Mute,
                ),
            )
            TextButton(onClick = { viewModel.setMonthlyBudgetRupees(draftBudget) }) {
                Text(stringResource(R.string.settings_save_budget), color = MintDeep)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.settings_appearance),
            body = stringResource(R.string.settings_light_keeps_the_paper_look),
        ) {
            ThemeModePicker(
                selected = state.themeMode,
                onSelect = viewModel::setThemeMode,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.settings_auto_scan),
            body = stringResource(R.string.settings_reads_new_bank_sms_as),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_auto_scan_sms), color = Ink)
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_use_ai_to_catch_missed), color = Ink)
                    Text(
                        if (state.apiKey.isBlank()) "Needs a Gemini API key" else "Found spends get an AI badge you can undo",
                        color = Mute,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = state.aiTriageEnabled,
                    onCheckedChange = { viewModel.setAiTriageEnabled(it) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_app_lock_biometric), color = Ink)
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
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.settings_self_transfers),
            body = stringResource(R.string.settings_your_name_aliases_comma_separated),
        ) {
            OutlinedTextField(
                value = draftSelfNames,
                onValueChange = { draftSelfNames = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { namesFocused = it.isFocused },
                label = { Text(stringResource(R.string.settings_your_name_s)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ink,
                    unfocusedBorderColor = Line,
                    focusedLabelColor = Mute,
                ),
            )
            TextButton(onClick = { viewModel.setSelfNameAliases(draftSelfNames) }) {
                Text(stringResource(R.string.settings_save_names), color = MintDeep)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.settings_capture_upi_app_notifications),
            body = stringResource(R.string.settings_reads_phonepe_payment_notifications_to),
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
            title = stringResource(R.string.bank_and_duplicate_your_banks),
            body = stringResource(R.string.settings_used_to_detect_money_moving),
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
            title = stringResource(R.string.settings_export_transactions),
            body = stringResource(R.string.settings_only_runs_when_you_tap),
        ) {
            OutlinedButton(
                onClick = {
                    pendingExport = "other"
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
                Text(stringResource(R.string.settings_export_other_sms_to_downloads), color = Ink)
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    pendingExport = "all"
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
                Text(stringResource(R.string.settings_export_all_inbox_sms), color = Ink)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.settings_diagnostics),
            body = "Rolling on-device categorization log (${state.logSizeLabel}). SMS text in it is masked (digits hidden). It's only saved to Downloads when you tap Export; clear it to free space.",
        ) {
            OutlinedButton(
                onClick = {
                    pendingExport = "log"
                    if (needsStoragePermission) {
                        exportLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        viewModel.exportCategorizationLog()
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(R.string.settings_export_categorization_log), color = Ink)
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.clearCategorizationLog() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(R.string.settings_clear_log), color = Ink)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = stringResource(R.string.settings_reset_category_learning),
            body = stringResource(R.string.settings_smspend_remembers_the_category_you),
        ) {
            OutlinedButton(
                onClick = { viewModel.clearMemory() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(stringResource(R.string.settings_reset_learning), color = Ink)
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

/** Segmented System / Light / Dark control, styled like the Home period tabs. */
@Composable
internal fun ThemeModePicker(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemeMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Text(
                mode.label,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) Ink else Paper)
                    .border(1.dp, if (isSelected) Ink else Line, RoundedCornerShape(12.dp))
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(mode) }
                    .padding(vertical = 10.dp),
                color = if (isSelected) Mint else Mute,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun SettingsCard(title: String, body: String, content: @Composable () -> Unit) {
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
