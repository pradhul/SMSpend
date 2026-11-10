package app.smspend.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("smspend_prefs")

class UserPreferences(
    private val context: Context,
    private val cipher: SecretCipher = KeystoreSecretCipher(),
) {
    /** Plain-text key from <= 1.2.0; migrated to [keyApiEncrypted] on first start. */
    private val keyApiLegacyPlain = stringPreferencesKey("gemini_api_key")
    private val keyApiEncrypted = stringPreferencesKey("gemini_api_key_enc")
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
    private val keyAiTriageWatermark = longPreferencesKey("ai_triage_watermark_ms")

    /**
     * Decrypted Gemini key ("" when none). Falls back to a not-yet-migrated plain value
     * so a key keeps working even if migration hasn't run yet in this process.
     */
    val geminiApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[keyApiEncrypted]?.let { cipher.decrypt(it) }
            ?: prefs[keyApiLegacyPlain]
            ?: ""
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
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

    /** Newest SMS date an AI triage pass fully handled (incremental passes start here). */
    val aiTriageWatermarkMs: Flow<Long> = context.dataStore.data.map { it[keyAiTriageWatermark] ?: 0L }

    suspend fun setAiTriageWatermarkMs(value: Long) {
        context.dataStore.edit { it[keyAiTriageWatermark] = value }
    }

    suspend fun setGeminiApiKey(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            clearApiKey()
            return
        }
        val sealed = cipher.encrypt(trimmed)
        context.dataStore.edit {
            it[keyApiEncrypted] = sealed
            it.remove(keyApiLegacyPlain)
        }
    }

    /**
     * Moves a plain-text key saved by an older version into Keystore-encrypted storage.
     * Safe to call on every start. If encryption fails the plain value is kept so the
     * user's key isn't lost; it is retried next start.
     */
    suspend fun migrateLegacyApiKey() {
        val plain = context.dataStore.data.first()[keyApiLegacyPlain] ?: return
        if (plain.isBlank()) {
            context.dataStore.edit { it.remove(keyApiLegacyPlain) }
            return
        }
        val sealed = try {
            cipher.encrypt(plain.trim())
        } catch (e: Exception) {
            DiagLog.w("secret", "migrate_failed", mapOf("type" to e.javaClass.simpleName))
            return
        }
        context.dataStore.edit {
            it[keyApiEncrypted] = sealed
            it.remove(keyApiLegacyPlain)
        }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[keyOnboarded] = value }
    }

    /** Test hook: write a key the way <= 1.2.0 did (plain text). */
    @androidx.annotation.VisibleForTesting
    internal suspend fun setLegacyPlainKeyForTest(value: String) {
        context.dataStore.edit { it[keyApiLegacyPlain] = value }
    }

    /** Test hook: every stored value as text, to assert nothing secret is kept in plain. */
    @androidx.annotation.VisibleForTesting
    internal suspend fun rawValuesForTest(): Map<String, String> =
        context.dataStore.data.first().asMap().entries.associate { it.key.name to it.value.toString() }

    suspend fun clearApiKey() {
        context.dataStore.edit {
            it.remove(keyApiEncrypted)
            it.remove(keyApiLegacyPlain)
        }
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
