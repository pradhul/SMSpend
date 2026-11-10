package app.smspend.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fake cipher (Robolectric has no AndroidKeyStore); reversible and recognisable. */
private class FakeCipher : SecretCipher {
    override fun encrypt(plain: String) = "v1:" + plain.reversed()
    override fun decrypt(encoded: String) = encoded.removePrefix("v1:").reversed()
}

/** Review #10: the Gemini key is encrypted at rest and old plain values migrate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ApiKeyStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun keyIsStoredEncryptedAndReadBack() = runBlocking {
        val prefs = UserPreferences(context, FakeCipher())
        prefs.setGeminiApiKey("  AIzaSECRET  ")
        assertEquals("AIzaSECRET", prefs.geminiApiKey.first())
        val raw = prefs.rawValuesForTest().values.joinToString("|")
        assertFalse("plain key must not be stored", raw.contains("AIzaSECRET"))
        assertTrue(raw.contains("TERCESazIA"))
        prefs.clearApiKey()
        assertEquals("", prefs.geminiApiKey.first())
    }

    @Test
    fun legacyPlainKeyIsMigrated() = runBlocking {
        val prefs = UserPreferences(context, FakeCipher())
        prefs.setLegacyPlainKeyForTest("AIzaOLD")
        // Readable before migration…
        assertEquals("AIzaOLD", prefs.geminiApiKey.first())
        prefs.migrateLegacyApiKey()
        // …and after, from the encrypted slot only.
        assertEquals("AIzaOLD", prefs.geminiApiKey.first())
        val raw = prefs.rawValuesForTest()
        assertFalse(raw.values.any { it.contains("AIzaOLD") })
        assertFalse("gemini_api_key" in raw)
    }
}
