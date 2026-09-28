package com.gigi.tcg.debug

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.runner.AndroidJUnitRunner
import com.gigi.tcg.GigiApp
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.auth.AuthFinalizeResult
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking

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
 *
 * 双路径：继承 AndroidJUnitRunner，使 connectedDebugAndroidTest 可直接跑 JUnit 测试；
 * args 携带 cookie_b64 / clear=1 时走取证注入路径，不进 JUnit。
 * 注入必须在非主线程 finish()（MonitoringInstrumentation 强制校验主线程）。
 *
 * 🔴 raw_cookie 通道（2026-09-28 新增，账号系统端到端测试；V33 起分两种模式）：
 * 把**未经交换的米游社原始 cookie** 交给 App 自己的 [com.gigi.tcg.data.auth.AuthManager.finalize]，
 * 与扫码登录第③④步完全同一条业务代码路径（绑定接口角色发现 → badge 交换 →
 * CredentialStore 落盘），区别只是 cookie 来源不是二维码轮询而是外部注入。
 * - `-e raw_cookie_b64 <b64> -e auto 1`：**无服务器偏好**入口 finalize(cookies) —— 与真机
 *   扫码确认后的首评完全一致：多角色账号期望 result=select_role（附候选快照）、单角色
 *   期望 result=success（自动命中）；
 * - `-e raw_cookie_b64 <b64> -e server cn_qd01`：指定区服入口 —— 等价于"用户在角色选择器里
 *   选定了世界树"，期望 success 且账户落盘归属 cn_qd01。
 * 两种模式都回报落盘后的账户索引快照（uid|nickname|serverId），供测试断言归属。
 */
class DebugCredentialInjector : AndroidJUnitRunner() {

    private var injectMode = false

    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        val clear = args?.getString(EXTRA_CLEAR)
        val encoded = args?.getString(EXTRA_COOKIE_B64)
        val rawEncoded = args?.getString(EXTRA_RAW_COOKIE_B64)
        if (clear == null && encoded == null && rawEncoded == null) {
            return
        }
        injectMode = true
        val serverId = args?.getString(EXTRA_SERVER)
        val autoMode = args?.getString(EXTRA_AUTO) == "1"
        val appContext = targetContext!!.applicationContext
        val thread = HandlerThread("debug-credential-injector").apply { start() }
        val done = java.util.concurrent.CountDownLatch(1)
        Handler(thread.looper).post {
            try {
                inject(appContext, clear, encoded, rawEncoded, serverId, autoMode)
            } finally {
                done.countDown()
                thread.quitSafely()
            }
        }
        done.await()
    }

    private fun inject(
        appContext: Context,
        clear: String?,
        encoded: String?,
        rawEncoded: String?,
        serverId: String?,
        autoMode: Boolean,
    ) {
        if (clear == "1") {
            com.gigi.tcg.data.auth.CredentialStore(appContext).clear()
            // 🔴 clear() 内部同样走 apply()：不等刷盘就 finish 会"回报已清、磁盘仍满"
            //（实测 2026-09-28：auto 注入读到上一次的双账户残留）。等待磁盘真的不含索引键。
            val flushed = awaitDiskClear(appContext)
            finish(
                Activity.RESULT_OK,
                Bundle().apply {
                    putString(RESULT_STATUS, "cleared")
                    putBoolean(RESULT_DISK, flushed)
                },
            )
            return
        }
        if (!rawEncoded.isNullOrEmpty()) {
            rawCookieLogin(appContext, rawEncoded, serverId, autoMode)
            return
        }
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

    /**
     * 原始 cookie 直登（只读 cookie、不走二维码）：走 App 真实的 [AuthManager.finalize]，
     * 落盘成功后附账户索引快照。异常一律折算为 error 结果回报，不让 instrument 崩掉。
     * autoMode=true 走无服务器偏好入口（等价扫码确认后首评）；否则按 serverId 指定区服
     * （等价用户在角色选择器里选定后重试）。
     */
    private fun rawCookieLogin(
        appContext: Context,
        rawEncoded: String,
        serverId: String?,
        autoMode: Boolean,
    ) {
        val cookie = String(Base64.decode(rawEncoded, Base64.NO_WRAP), Charsets.UTF_8)
        val server = ServerId.from(serverId) ?: ServerId.DEFAULT
        val container = (appContext as GigiApp).container
        val bundle = Bundle()
        bundle.putString(RESULT_SERVER, if (autoMode) "auto" else server.id)
        try {
            val result = runBlocking {
                if (autoMode) {
                    container.authManager.finalize(listOf(cookie))
                } else {
                    container.authManager.finalize(listOf(cookie), server)
                }
            }
            container.refreshAccounts()
            when (result) {
                is AuthFinalizeResult.Success -> {
                    bundle.putString(RESULT_STATUS, "success")
                    bundle.putString(RESULT_UID, result.gameUid)
                    bundle.putString(RESULT_NICKNAME, result.nickname)
                    bundle.putBoolean(RESULT_EXCHANGED, result.exchanged)
                    bundle.putString(RESULT_REGION, result.region)
                }
                is AuthFinalizeResult.SelectRole -> {
                    // V33 多角色：等价 UI 的 ChooseRole 对话框弹出（凭据未写入、零副作用）
                    bundle.putString(RESULT_STATUS, "select_role")
                    bundle.putString(
                        RESULT_CANDIDATES,
                        result.candidates.joinToString(";") {
                            "${it.region}|${it.uid}|${it.nickname}|${it.regionName}|${it.level}"
                        },
                    )
                }
                is AuthFinalizeResult.NoRole -> {
                    bundle.putString(RESULT_STATUS, "no_role")
                    bundle.putString(
                        RESULT_BOUND,
                        result.boundRoles.joinToString(";") { "${it.region}|${it.uid}|${it.regionName}" },
                    )
                }
            }
        } catch (t: Throwable) {
            bundle.putString(RESULT_STATUS, "error: ${t.javaClass.simpleName}: ${t.message}")
        }
        // 无论成败都附落盘快照：账户索引（uid|nickname|serverId）、激活 uid、旧版单槽是否残留
        val store = container.credentialStore
        bundle.putString(
            RESULT_ACCOUNTS,
            store.accounts().joinToString(";") { "${it.uid}|${it.nickname}|${it.serverId}" },
        )
        bundle.putString(RESULT_ACTIVE, store.activeUid())
        bundle.putBoolean(RESULT_DISK, awaitDiskFlush(appContext, store.activeUid()))
        finish(Activity.RESULT_OK, bundle)
    }

    /**
     * instrumentation 进程退出早于 QueuedWork 异步刷盘：CredentialStore.save 内部全部走
     * SharedPreferences.apply()（内存即时可见、磁盘异步），不等刷盘直接 [finish] 会把注入结果
     * 丢在内存里（实测 2026-09-28：App 重启后 prefs 目录为空 / 只含上一次的旧账户）。
     *
     * 🔴 校验条件必须**精确匹配本次期望的 active_uid**：文件已存在时（增量注入第二个账户），
     * 只检查键存在会被上一次写入的旧值满足、立即误判为已刷盘（实测踩坑）。
     * 轮询期间反复催刷（QueuedWork.waitToFinish 为 @hide，反射失败不致命——写入在
     * queued-work 独立线程执行，只要本进程还活着就会完成）。
     */
    private fun awaitDiskFlush(appContext: Context, expectedActiveUid: String?): Boolean {
        if (expectedActiveUid.isNullOrEmpty()) return false
        val prefsFile = java.io.File(appContext.applicationInfo.dataDir, "shared_prefs/$PREFS_FILE.xml")
        val needle = "name=\"active_uid\">$expectedActiveUid<"
        val deadline = System.currentTimeMillis() + DISK_FLUSH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            runCatching {
                Class.forName("android.app.QueuedWork").getMethod("waitToFinish").invoke(null)
            }
            val text = runCatching { prefsFile.readText() }.getOrNull()
            if (text != null && text.contains(needle)) return true
            Thread.sleep(DISK_FLUSH_POLL_MS)
        }
        return false
    }

    override fun onStart() {
        if (injectMode) {
            // 注入路径已在工作线程 finish()，不启动 JUnit
            return
        }
        super.onStart()
    }

    /**
     * clear() 后的写后读校验：磁盘 prefs 不再含账户索引键（account_index / active_uid）才算清干净。
     * 口径与 [awaitDiskFlush] 同源：apply() 异步写必须等，QueuedWork 反射催刷失败不致命。
     * 空 map（`<map />`）或文件不存在都视为已清空。
     */
    private fun awaitDiskClear(appContext: Context): Boolean {
        val prefsFile = java.io.File(appContext.applicationInfo.dataDir, "shared_prefs/$PREFS_FILE.xml")
        val deadline = System.currentTimeMillis() + DISK_FLUSH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            runCatching {
                Class.forName("android.app.QueuedWork").getMethod("waitToFinish").invoke(null)
            }
            val text = runCatching { prefsFile.readText() }.getOrNull()
            if (text == null || (!text.contains("account_index") && !text.contains("active_uid"))) {
                return true
            }
            Thread.sleep(DISK_FLUSH_POLL_MS)
        }
        return false
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
        const val EXTRA_RAW_COOKIE_B64 = "raw_cookie_b64"
        const val EXTRA_SERVER = "server"
        const val EXTRA_AUTO = "auto"
        const val RESULT_STATUS = "status"
        const val RESULT_LENGTH = "cookie_length"
        const val RESULT_UID = "uid"
        const val RESULT_NICKNAME = "nickname"
        const val RESULT_SERVER = "server"
        const val RESULT_REGION = "region"
        const val RESULT_EXCHANGED = "exchanged"
        const val RESULT_BOUND = "bound_roles"
        const val RESULT_CANDIDATES = "candidates"
        const val RESULT_ACCOUNTS = "accounts"
        const val RESULT_ACTIVE = "active_uid"
        const val RESULT_DISK = "disk_flushed"
        const val PREFS_FILE = "gigi_credentials"
        const val DISK_FLUSH_TIMEOUT_MS = 5_000L
        const val DISK_FLUSH_POLL_MS = 100L
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_ALIAS = "gigi_credentials_key"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
