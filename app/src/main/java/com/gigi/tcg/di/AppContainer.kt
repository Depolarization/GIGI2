// 手写依赖容器（禁用 Hilt/Dagger）：GigiApp 持有单实例，页面经 ViewModel 取用 repository。

package com.gigi.tcg.di

import android.content.Context
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.CredentialSource
import com.gigi.tcg.data.api.MihoyoClient
import com.gigi.tcg.data.auth.AuthManager
import com.gigi.tcg.data.auth.CredentialStore
import com.gigi.tcg.data.auth.StoredAccount
import com.gigi.tcg.data.cache.WikiDiskCache
import com.gigi.tcg.data.repo.GigiApiTransport
import com.gigi.tcg.data.repo.GigiRepository
import com.gigi.tcg.data.repo.MihoyoClientEnvelopeTransport
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(private val appContext: Context) {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

    val credentialStore: CredentialStore by lazy { CredentialStore(appContext) }

    val credentialSource: CredentialSource get() = credentialStore

    val wikiDiskCache: WikiDiskCache by lazy { WikiDiskCache(appContext) }

    /** 全局共享 OkHttpClient：MihoyoClient 与 AuthManager 复用同一实例 */
    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    val authManager: AuthManager by lazy { AuthManager(okHttp, credentialStore, json) }

    private val mihoyoClient: MihoyoClient by lazy {
        MihoyoClient(okHttp, json, credentialStore)
    }

    // ===== 会话内存态（🔴 不落盘：本工程服务器选择与会话均仅存内存，
    // 对齐差异见探针——web 用 serverStorage 持久化服务器选择，此处刻意不做） =====

    private val _currentServer = MutableStateFlow<ServerId>(ServerId.DEFAULT)
    val currentServer: StateFlow<ServerId> = _currentServer.asStateFlow()

    fun selectServer(server: ServerId) {
        _currentServer.value = server
    }

    private val _sessionUid = MutableStateFlow<String?>(null)

    /** 会话内已登录标记（内存级，对齐 auth.tsx sessionUidRef 语义的容器侧镜像） */
    val sessionUid: StateFlow<String?> = _sessionUid.asStateFlow()

    private val _accounts = MutableStateFlow<List<StoredAccount>>(emptyList())
    val accounts: StateFlow<List<StoredAccount>> = _accounts.asStateFlow()

    private val _activeAccountUid = MutableStateFlow<String?>(null)
    val activeAccountUid: StateFlow<String?> = _activeAccountUid.asStateFlow()

    init {
        refreshAccounts()
    }

    fun refreshAccounts() {
        val storedAccounts = credentialStore.accounts()
        _accounts.value = storedAccounts
        val storedActiveUid = credentialStore.activeUid()
        val activeUid = storedActiveUid ?: storedAccounts.firstOrNull()?.uid
        if (storedActiveUid == null && activeUid != null) {
            credentialStore.setActiveUid(activeUid)
        }
        _activeAccountUid.value = activeUid
        if (activeUid == null) {
            _currentServer.value = ServerId.DEFAULT
        } else {
            _currentServer.value = storedAccounts.first { it.uid == activeUid }.server()
        }
    }

    fun activateAccount(uid: String): Boolean {
        val account = credentialStore.accounts().firstOrNull { it.uid == uid } ?: return false
        if (!CredentialStore.isSafeUid(uid)) return false
        if (credentialStore.cookieHeaderFor(uid) == null) return false
        credentialStore.setActiveUid(uid)
        updateSession(account.uid)
        selectServer(account.server())
        refreshAccounts()
        return true
    }

    fun updateSession(uid: String?) {
        _sessionUid.value = uid
    }

    private val apiTransport: GigiApiTransport by lazy { MihoyoClientEnvelopeTransport(mihoyoClient) }

    val repository: GigiRepository by lazy {
        GigiRepository(
            transport = apiTransport,
            wikiDiskCache = wikiDiskCache,
            json = json,
        )
    }
}
