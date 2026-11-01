package com.meowgi.launcher710.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable Android Keystore key; output is IV ‖ ciphertext+tag. */
object KeystoreCipher : SecretCipher {
    private const val ALIAS = "launcher710_ai_api_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return c.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }
}

/**
 * The AI API key, encrypted with a Keystore key in its own prefs file ([PREFS_NAME]). That file
 * is left out of the launcher's backups (`BackupEngine.EXCLUDED_PREF_FILES`) and of Android
 * backups (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`): the Keystore key never
 * leaves the device, so a copied ciphertext would be useless anyway.
 */
class AiSecrets(context: Context, private val cipher: SecretCipher = KeystoreCipher) {

    companion object {
        const val PREFS_NAME = "ai_secrets"
        private const val KEY = "api_key"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val hasApiKey: Boolean get() = prefs.contains(KEY)

    /** Null when unset, or when it can't be decrypted (e.g. the Keystore key was reset). */
    var apiKey: String?
        get() {
            val stored = prefs.getString(KEY, null) ?: return null
            return try {
                String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
        set(value) {
            val v = value?.trim()
            if (v.isNullOrEmpty()) {
                prefs.edit().remove(KEY).apply()
            } else {
                val sealed = cipher.encrypt(v.toByteArray(Charsets.UTF_8))
                prefs.edit().putString(KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
            }
        }
}
