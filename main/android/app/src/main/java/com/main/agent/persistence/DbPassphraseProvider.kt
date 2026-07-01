package com.main.agent.persistence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates (once) and retrieves a random 256-bit passphrase used to encrypt the
 * SQLCipher-backed Room database. The passphrase itself is protected at rest by an
 * AES-256-GCM key held in the Android Keystore (non-exportable, hardware-backed where
 * available) — only the ciphertext + IV are persisted in SharedPreferences.
 */
object DbPassphraseProvider {

    private const val PREFS_NAME     = "db_key_store"
    private const val PREF_KEY_VALUE = "db_passphrase_ciphertext"
    private const val PREF_KEY_IV    = "db_passphrase_iv"
    private const val KEYSTORE_ALIAS = "db_passphrase_wrapping_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val PASSPHRASE_LENGTH_BYTES = 32 // 256-bit

    fun getOrCreatePassphrase(context: Context): ByteArray {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingCiphertext = prefs.getString(PREF_KEY_VALUE, null)
        val existingIv = prefs.getString(PREF_KEY_IV, null)

        if (existingCiphertext != null && existingIv != null) {
            return decrypt(
                ciphertext = Base64.decode(existingCiphertext, Base64.NO_WRAP),
                iv = Base64.decode(existingIv, Base64.NO_WRAP),
            )
        }

        val passphrase = ByteArray(PASSPHRASE_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val (ciphertext, iv) = encrypt(passphrase)

        prefs.edit()
            .putString(PREF_KEY_VALUE, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(PREF_KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()

        return passphrase
    }

    private fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        val ciphertext = cipher.doFinal(plaintext)
        return ciphertext to cipher.iv
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
