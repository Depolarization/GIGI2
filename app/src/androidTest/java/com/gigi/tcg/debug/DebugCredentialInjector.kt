package com.gigi.tcg.debug

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

/**
 * T11 真机取证专用的 instrumentation 入口（设计文档 §5.2）。
 * 通过 `am instrument -e cookie_b64 <base64>` 把凭据串写入 CredentialStore，
 * 或以 `-e clear 1` 模拟退出登录清除。
 * 不打印凭据内容，只回报长度，避免日志泄漏。
 *
 * 🔴 取证绕行说明：真机（AndroidKeyStore/keystore2）上
 * `CredentialStore.save()` 以调用方 IV 初始化加密会抛
 * "Caller-provided IV not permitted"（setRandomizedEncryptionRequired(true) 拒绝外部 IV）。
 * 本注入器不修改业务代码，改为在取证侧按同一磁盘格式
 * （gigi_credentials/ciphertext = Base64(IV+密文)、别名 gigi_credentials_key）
 * 用 Keystore 自生成 IV 的正确流程写入，供 App 正常解密读取。
 */
class DebugCredentialInjector : Instrumentation() {

    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        val appContext = targetContext!!.applicationContext
        if (args?.getString(EXTRA_CLEAR) == "1") {
            com.gigi.tcg.data.auth.CredentialStore(appContext).clear()
            finish(Activity.RESULT_OK, Bundle().apply { putString(RESULT_STATUS, "cleared") })
            return
        }
        val encoded = args?.getString(EXTRA_COOKIE_B64)
        if (encoded.isNullOrEmpty()) {
            finish(
                Activity.RESULT_OK,
                Bundle().apply { putString(RESULT_STATUS, "missing cookie_b64 / clear") },
            )
            return
        }
        val cookie = String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
        saveInKeystoreFormat(appContext, cookie)
        finish(
            Activity.RESULT_OK,
            Bundle().apply {
                putString(RESULT_STATUS, "saved")
                putInt(RESULT_LENGTH, cookie.length)
            },
        )
    }

    private fun saveInKeystoreFormat(appContext: Context, cookie: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val payload = cipher.iv + cipher.doFinal(cookie.toByteArray(Charsets.UTF_8))
        appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(payload, Base64.NO_WRAP))
            // instrumentation 进程退出早于 QueuedWork 刷盘，取证写入必须同步 commit
            .commit()
    }

    private fun obtainKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val EXTRA_COOKIE_B64 = "cookie_b64"
        const val EXTRA_CLEAR = "clear"
        const val RESULT_STATUS = "status"
        const val RESULT_LENGTH = "cookie_length"
        const val PREFS_FILE = "gigi_credentials"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_ALIAS = "gigi_credentials_key"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
