// Keystore + AES-GCM 凭据存储（设计文档 §3.4，⑧⑨⑩ 多账户扩展）。
// 明文仅出现在 [save] 参数与 [cookieHeader] 返回值；磁盘上只有 Base64(IV + 密文)。
// 槽位模型：每个账户一个 Keystore 别名 gigi_credentials_key_<uid> 与一个密文 prefs 键 ciphertext_<uid>；
// 另有账户索引（uid/nickname/serverId/lastActiveEpochMs，JSON 串）与 activeUid 键存于同一 prefs，
// 索引头 = 最近使用账户。旧版单槽（ciphertext / gigi_credentials_key）保留为兼容与升级回退：
// 无激活账户时 cookieHeader() 回退读取旧槽（androidTest 注入与升级用户），
// 登录校验成功时由 Gate 经 adoptActiveCookie 收养为正式账户。

package com.gigi.tcg.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.CredentialSource
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 多账户索引元素（uid 为身份键 = 原神 game_uid；服务器与账户在登录时绑定，⑧） */
@Serializable
data class StoredAccount(
    val uid: String = "",
    val nickname: String? = null,
    val serverId: String = ServerId.DEFAULT.id,
    val lastActiveEpochMs: Long = 0L,
) {
    /** 绑定服务器（索引数据损坏/非法标识符时兜底默认服务器） */
    fun server(): ServerId = ServerId.from(serverId) ?: ServerId.DEFAULT

    /** 菜单展示名：昵称缺失时以 uid 尾号兜底，保证可区分 */
    fun displayName(): String = nickname ?: "玩家${uid.takeLast(4)}"
}

class CredentialStore(context: Context) : CredentialSource {

    private val appContext = context.applicationContext

    private val prefs =
        appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // ===== 读取 =====

    /** 当前激活账户的凭据（Cookie 头）；无激活账户时回退旧版单槽；均无 → null */
    override fun cookieHeader(): String? {
        activeUid()?.let { uid ->
            decryptForKey(cipherPrefsKey(uid), keyAlias(uid))?.let { return it }
        }
        return decryptForKey(LEGACY_CIPHER_KEY, LEGACY_KEY_ALIAS)
    }

    /** 账户索引（头 = 最近使用）；索引损坏时返回空列表，视为无账户 */
    fun accounts(): List<StoredAccount> = parseAccountIndex(prefs.getString(KEY_INDEX, null))

    /** 当前激活账户 uid（无/非法 → null） */
    fun activeUid(): String? = prefs.getString(KEY_ACTIVE_UID, null)?.takeIf { isSafeUid(it) }

    // ===== 写入（新登录 / 静默续命成功） =====

    /**
     * 账户级落盘：密文写该账户槽位 + 索引去重后提到最前 + 置为激活。
     * 与旧版单参 [save] 不同，本方法维护完整索引（⑨多账户）。
     */
    fun save(cookieFragments: String, account: StoredAccount) {
        require(isSafeUid(account.uid)) { "非法 uid：拒绝用作存储键" }
        saveCiphertext(cipherPrefsKey(account.uid), keyAlias(account.uid), cookieFragments)
        val rest = accounts().filterNot { it.uid == account.uid }
        val record = account.copy(lastActiveEpochMs = System.currentTimeMillis())
        writeIndex(listOf(record) + rest)
        prefs.edit().putString(KEY_ACTIVE_UID, record.uid).apply()
    }

    /**
     * 旧版单槽写（兼容 androidTest 凭据注入）：有已登记激活账户时只更新该账户密文
     * （不动索引/激活），否则写旧版单槽（供 cookieHeader 回退读取）。
     */
    fun save(cookieFragments: String) {
        val uid = activeUid()
        if (uid != null && accounts().any { it.uid == uid }) {
            saveCiphertext(cipherPrefsKey(uid), keyAlias(uid), cookieFragments)
        } else {
            saveCiphertext(LEGACY_CIPHER_KEY, LEGACY_KEY_ALIAS, cookieFragments)
        }
    }

    /** 仅把 [uid] 置为激活（索引重排：提到最前 = 最近使用） */
    fun setActiveUid(uid: String) {
        if (!isSafeUid(uid)) return
        val all = accounts()
        val hit = all.firstOrNull { it.uid == uid } ?: return
        writeIndex(listOf(hit) + all.filterNot { it.uid == uid })
        prefs.edit().putString(KEY_ACTIVE_UID, uid).apply()
    }

    // ===== ⑩ 升级收养：旧版单槽凭据 → 正式账户 =====

    /**
     * 激活凭据尚无账户记录时（旧版升级用户），以校验得到的 [uid]/[server] 收养：
     * 旧槽密文解密后按账户槽重加密落盘，删除旧别名，建索引并激活。
     * 返回是否发生了收养。
     */
    fun adoptActiveCookie(uid: String, server: ServerId): Boolean {
        val active = activeUid()
        if (active != null && accounts().any { it.uid == active }) return false
        if (!isSafeUid(uid)) return false
        val legacy = decryptForKey(LEGACY_CIPHER_KEY, LEGACY_KEY_ALIAS) ?: return false
        save(legacy, StoredAccount(uid = uid, nickname = null, serverId = server.id))
        prefs.edit().remove(LEGACY_CIPHER_KEY).apply()
        deleteAlias(LEGACY_KEY_ALIAS)
        return true
    }

    // ===== ⑨ 退出当前账户 =====

    /**
     * 退出 [uid]：清该账户密文 + 删其 Keystore 别名 + 从索引移除（激活键仅当被退账户为激活时清除）。
     * 返回剩余账户（索引序，头 = 最近使用）；调用方决定切换下一个还是回登录页。
     */
    fun removeAccount(uid: String): List<StoredAccount> {
        if (!isSafeUid(uid)) return accounts()
        prefs.edit().remove(cipherPrefsKey(uid)).apply()
        if (activeUid() == uid) {
            prefs.edit().remove(KEY_ACTIVE_UID).apply()
        }
        deleteAlias(keyAlias(uid))
        val rest = accounts().filterNot { it.uid == uid }
        writeIndex(rest)
        return rest
    }

    /** 旧版全清（androidTest 用）：清全部密文/索引/激活键 + 删除索引内与旧版全部 Keystore 别名 */
    fun clear() {
        // 先取别名清单再清 prefs（索引随 prefs 一起被清）
        val aliases = accounts().map { keyAlias(it.uid) } + LEGACY_KEY_ALIAS
        prefs.edit().clear().apply()
        try {
            val keyStore = keyStore()
            aliases.forEach { alias ->
                if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            }
        } catch (e: GeneralSecurityException) {
            // 密钥本就不可用时，密文已清除即可
        }
    }

    // ===== 内部 =====

    private fun saveCiphertext(prefsKey: String, alias: String, plaintext: String) {
        // Keystore 密钥要求随机加密（setRandomizedEncryptionRequired(true)），
        // 加密 IV 必须由系统生成并从 cipher.iv 回读，调用方传入会抛
        // InvalidAlgorithmParameterException: Caller-provided IV not permitted。
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey(alias))
        val iv = requireNotNull(cipher.iv) { "Keystore did not provide a GCM IV" }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ciphertext, 0, payload, iv.size, ciphertext.size)
        prefs.edit()
            .putString(prefsKey, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    private fun decryptForKey(prefsKey: String, alias: String): String? {
        val encoded = prefs.getString(prefsKey, null) ?: return null
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= GCM_IV_BYTES) return null
            val iv = payload.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(alias), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null // 密文损坏/密钥失效 → 视为未登录，不抛出
        } catch (e: IllegalArgumentException) {
            null // Base64 解码失败等
        }
    }

    private fun deleteAlias(alias: String) {
        try {
            val keyStore = keyStore()
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        } catch (e: GeneralSecurityException) {
            // 密钥本就不可用时，密文已清除即可
        }
    }

    private fun writeIndex(accounts: List<StoredAccount>) {
        prefs.edit()
            .putString(
                KEY_INDEX,
                INDEX_JSON.encodeToString(ListSerializer(StoredAccount.serializer()), accounts),
            )
            .apply()
    }

    private fun obtainKey(alias: String): SecretKey {
        val keyStore = keyStore()
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
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

    internal companion object {
        const val PREFS_FILE: String = "gigi_credentials"

        // 旧版单槽（升级回退 / androidTest 注入）
        const val LEGACY_CIPHER_KEY: String = "ciphertext"
        const val LEGACY_KEY_ALIAS: String = "gigi_credentials_key"

        const val KEY_INDEX: String = "account_index"
        const val KEY_ACTIVE_UID: String = "active_uid"

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128

        private val INDEX_JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        /** ⑨ 槽位密文 prefs 键（uid 白名单见 [isSafeUid]，防 prefs 键注入） */
        fun cipherPrefsKey(uid: String): String = "ciphertext_$uid"

        /** ⑨ 槽位 Keystore 别名（per-account 密钥，退出=删该别名） */
        fun keyAlias(uid: String): String = "gigi_credentials_key_$uid"

        /**
         * uid 白名单：仅字母/数字/下划线/连字符。Keystore 别名与 prefs 键均含 uid，
         * 不校验会把非法字符（空格、控制符、'../' 等）注入存储键。
         */
        fun isSafeUid(uid: String): Boolean =
            uid.isNotEmpty() && uid.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

        /** 索引反序列化（纯函数）：空/损坏 → 空列表，绝不抛出 */
        fun parseAccountIndex(raw: String?): List<StoredAccount> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                INDEX_JSON.decodeFromString<List<StoredAccount>>(raw)
            } catch (e: SerializationException) {
                emptyList()
            } catch (e: IllegalArgumentException) {
                emptyList()
            }
        }
    }
}
