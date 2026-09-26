// 手写依赖容器（禁用 Hilt/Dagger）：GigiApp 持有单实例，页面经 ViewModel 取用 repository。

package com.gigi.tcg.di

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.gigi.tcg.BuildConfig
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.CredentialSource
import com.gigi.tcg.data.api.MihoyoClient
import com.gigi.tcg.data.auth.AuthFinalizeResult
import com.gigi.tcg.data.auth.AuthManager
import com.gigi.tcg.data.auth.CredentialStore
import com.gigi.tcg.data.auth.StoredAccount
import com.gigi.tcg.data.cache.WikiDiskCache
import com.gigi.tcg.data.github.BugReporter
import com.gigi.tcg.data.github.GitHubApi
import com.gigi.tcg.data.github.UpdateChecker
import com.gigi.tcg.data.repo.GigiApiTransport
import com.gigi.tcg.data.repo.GigiRepository
import com.gigi.tcg.data.repo.MihoyoClientEnvelopeTransport
import com.gigi.tcg.data.repo.SessionRefresher
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
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

    /** 静默续命真身（设计 §3.3 续期）：激活账户 → AuthManager.refreshStoredSession →
     *  成功后同步容器会话态（等价 AppGate 既有用法：refreshAccounts + updateSession(gameUid)）。
     *  🔴 必须作为 repository 的构造参数注入——漏传即等于续命功能不存在。 */
    private val sessionRefresher: SessionRefresher by lazy {
        ContainerSessionRefresher(
            accounts = { credentialStore.accounts() },
            activeUid = { credentialStore.activeUid() },
            refresh = { account -> authManager.refreshStoredSession(account) },
            onRefreshed = { refreshed ->
                refreshAccounts()
                updateSession(refreshed.gameUid)
            },
        )
    }

    val repository: GigiRepository by lazy {
        GigiRepository(
            transport = apiTransport,
            wikiDiskCache = wikiDiskCache,
            json = json,
            sessionRefresher = sessionRefresher,
        )
    }

    /** 接线自检：单测经容器真实构造路径断言续命端口确已注入 repository（防"机制写了没接线"回归） */
    @VisibleForTesting
    internal fun isSessionRefreshWired(): Boolean = repository.isSessionRefreshWired()

    /** 接线自检：暴露注入 repository 的同一实例，供单测验证真身链（AuthManager）可达 */
    @VisibleForTesting
    internal val sessionRefresherForTest: SessionRefresher get() = sessionRefresher

    // ===== GitHub 基础设施（V9-D 预留：更新检查 / 公告 / Bug 上报数据层）=====
    // 🔴 三者全部 by lazy：UI 接线由后续棒做，本棒不接入任何调用点，急切求值等于凭空多一条启动路径。
    // GitHubApi 复用全局 okHttp（内部只 newBuilder 换超时）：公开仓库读取不需要凭据，
    // 也绝不能把米游社 Cookie 通道带到 GitHub 域名。

    val gitHubApi: GitHubApi by lazy { GitHubApi(okHttp, json) }

    val updateChecker: UpdateChecker by lazy {
        UpdateChecker(
            api = gitHubApi,
            currentVersionName = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE,
        )
    }

    val bugReporter: BugReporter by lazy {
        BugReporter(
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
        )
    }
}

/** 续命端口真身：激活账户不出索引/无激活态时不动网络，直接 false；
 *  网络/凭据异常折算 false（GigiRepository 据此抛原错误），CancellationException 照抛不吞。 */
internal class ContainerSessionRefresher(
    private val accounts: () -> List<StoredAccount>,
    private val activeUid: () -> String?,
    private val refresh: suspend (StoredAccount) -> AuthFinalizeResult,
    private val onRefreshed: (AuthFinalizeResult.Success) -> Unit,
) : SessionRefresher {

    override suspend fun refreshActive(): Boolean {
        val uid = activeUid() ?: return false
        val account = accounts().firstOrNull { it.uid == uid } ?: return false
        val result = try {
            refresh(account)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            return false
        }
        if (result !is AuthFinalizeResult.Success) return false
        onRefreshed(result)
        return true
    }
}
