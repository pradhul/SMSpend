package app.smspend.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("smspend_prefs")

class UserPreferences(private val context: Context) {
    private val keyApi = stringPreferencesKey("gemini_api_key")
    private val keyOnboarded = booleanPreferencesKey("onboarded")
    private val keyMonthlyBudget = longPreferencesKey("monthly_budget_paise")
    private val keyBiometricLock = booleanPreferencesKey("biometric_lock_enabled")
    private val keyAutoScan = booleanPreferencesKey("auto_scan_enabled")
    private val keyAiTriage = booleanPreferencesKey("ai_triage_enabled")
    private val keyParserVersion = intPreferencesKey("parser_version")
    private val keySelfNameAliases = stringPreferencesKey("self_name_aliases")
    private val keyMyBankIds = stringPreferencesKey("my_bank_ids")
    private val keyMyBanksConfigured = booleanPreferencesKey("my_banks_configured")
    private val keyThemeMode = stringPreferencesKey("theme_mode")

    val geminiApiKey: Flow<String> = context.dataStore.data.map { it[keyApi].orEmpty() }
    /** "system" (default), "light", or "dark". See ui.theme.ThemeMode. */
    val themeMode: Flow<String> = context.dataStore.data.map {
        it[keyThemeMode] ?: "system"
    }
    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[keyOnboarded] == true }
    val monthlyBudgetPaise: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[keyMonthlyBudget]?.takeIf { it > 0L }
    }
    val biometricLockEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[keyBiometricLock] == true
    }
    val autoScanEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[keyAutoScan] != false // default true
    }
    val aiTriageEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[keyAiTriage] != false // default true
    }
    val parserVersion: Flow<Int> = context.dataStore.data.map {
        it[keyParserVersion] ?: 0
    }
    /** Comma-separated name aliases used to detect self transfers. */
    val selfNameAliases: Flow<String> = context.dataStore.data.map {
        it[keySelfNameAliases].orEmpty()
    }
    /** Comma-separated [IndianBank.id] values. */
    val myBankIds: Flow<String> = context.dataStore.data.map {
        it[keyMyBankIds].orEmpty()
    }
    val myBanksConfigured: Flow<Boolean> = context.dataStore.data.map {
        it[keyMyBanksConfigured] == true
    }

    suspend fun setGeminiApiKey(value: String) {
        context.dataStore.edit { it[keyApi] = value.trim() }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[keyOnboarded] = value }
    }

    suspend fun clearApiKey() {
        context.dataStore.edit { it.remove(keyApi) }
    }

    suspend fun setMonthlyBudgetPaise(value: Long?) {
        context.dataStore.edit { prefs ->
            if (value == null || value <= 0L) prefs.remove(keyMonthlyBudget)
            else prefs[keyMonthlyBudget] = value
        }
    }

    suspend fun setBiometricLockEnabled(value: Boolean) {
        context.dataStore.edit { it[keyBiometricLock] = value }
    }

    suspend fun setAutoScanEnabled(value: Boolean) {
        context.dataStore.edit { it[keyAutoScan] = value }
    }

    suspend fun setAiTriageEnabled(value: Boolean) {
        context.dataStore.edit { it[keyAiTriage] = value }
    }

    suspend fun setParserVersion(value: Int) {
        context.dataStore.edit { it[keyParserVersion] = value }
    }

    suspend fun setSelfNameAliases(value: String) {
        context.dataStore.edit { it[keySelfNameAliases] = value.trim() }
    }

    suspend fun setMyBankIds(idsCsv: String) {
        context.dataStore.edit { it[keyMyBankIds] = idsCsv.trim() }
    }

    suspend fun setMyBanksConfigured(value: Boolean) {
        context.dataStore.edit { it[keyMyBanksConfigured] = value }
    }

    suspend fun setThemeMode(value: String) {
        context.dataStore.edit { it[keyThemeMode] = value }
    }
}
