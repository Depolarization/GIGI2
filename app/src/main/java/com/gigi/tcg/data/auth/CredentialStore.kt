package com.gigi.tcg.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gigi.tcg.data.api.CredentialSource
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore + AES-GCM 凭据存储（设计文档 §3.4）。
 * 明文仅出现在 [save] 参数与 [cookieHeader] 返回值；磁盘上只有 Base64(IV + 密文)。
 */
class CredentialStore(context: Context) : CredentialSource {

    private val appContext = context.applicationContext

    private val prefs =
        appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun save(cookieFragments: String) {
        // Keystore 密钥要求随机加密（setRandomizedEncryptionRequired(true)），
        // 加密 IV 必须由系统生成并从 cipher.iv 回读，调用方传入会抛
        // InvalidAlgorithmParameterException: Caller-provided IV not permitted。
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val iv = requireNotNull(cipher.iv) { "Keystore did not provide a GCM IV" }
        val ciphertext = cipher.doFinal(cookieFragments.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ciphertext, 0, payload, iv.size, ciphertext.size)
        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    override fun cookieHeader(): String? {
        val encoded = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= GCM_IV_BYTES) return null
            val iv = payload.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null // 密文损坏/密钥失效 → 视为未登录，不抛出
        } catch (e: IllegalArgumentException) {
            null // Base64 解码失败等
        }
    }

    /** 退出登录：清密文 + 删 Keystore 别名（双重清除，设计红线 4）。 */
    fun clear() {
        prefs.edit().clear().apply()
        try {
            val keyStore = keyStore()
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (e: GeneralSecurityException) {
            // 密钥本就不可用时，密文已清除即可
        }
    }

    private fun obtainKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private companion object {
        const val PREFS_FILE = "gigi_credentials"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_ALIAS = "gigi_credentials_key"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
