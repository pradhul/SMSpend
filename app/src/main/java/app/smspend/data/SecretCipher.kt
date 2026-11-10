package app.smspend.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (the Gemini API key) before they are written to DataStore. */
interface SecretCipher {
    fun encrypt(plain: String): String

    /** Null when the value can't be decrypted (e.g. the Keystore key was wiped). */
    fun decrypt(encoded: String): String?
}

/**
 * AES-256/GCM with a non-exportable key in the Android Keystore. Output is
 * "v1:" + Base64(iv || ciphertext+tag). The key never leaves secure hardware
 * (where available), so a copied DataStore file is useless on its own.
 */
class KeystoreSecretCipher(
    private val alias: String = DEFAULT_ALIAS,
) : SecretCipher {
    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String? {
        if (!encoded.startsWith(PREFIX)) return null
        return try {
            val bytes = Base64.decode(encoded.removePrefix(PREFIX), Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                existingKey() ?: return null,
                GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES),
            )
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            DiagLog.w("secret", "decrypt_failed", mapOf("type" to e.javaClass.simpleName))
            null
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? =
        (keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    @Synchronized
    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "smspend_secrets_v1"
        const val PREFIX = "v1:"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
