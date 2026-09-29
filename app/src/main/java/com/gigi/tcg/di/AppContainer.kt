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
import com.gigi.tcg.i18n.LocaleStrings
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * [AppContainer.refreshAccounts] 的纯决策结果（内存态要写的激活 uid / 服务器 / 需回写盘的 uid）。
 */
internal data class AccountRefreshPlan(
    val activeUid: String?,
    val server: ServerId,
    val uidToPersist: String?,
)

/**
 * 账户内存态决策，从 [AppContainer.refreshAccounts] 抽出为顶层 internal 纯函数：
 * 不碰 Context / CredentialStore，JVM 单测能直接复现冷启动崩溃路径（AppContainerTest）。
 *
 * 判据（🔴 V36-0 审计 P1：原实现 `storedAccounts.first { it.uid == activeUid }` 无兜底）：
 * - `storedActiveUid == null` → 回落首个账户，并要求回写盘（既有行为，保持不变）；
 * - `storedActiveUid != null` 却**在账户表里查不到**（凭据被部分清除、DataStore 写入中断、
 *   多账号切换时序异常都会造成落盘 activeUid 与账户表错配）⇒ 判定为**脏值**：`first { }` 当时直接抛
 *   `NoSuchElementException`，而 `refreshAccounts()` 由 `init` 调用 ⇒ **冷启动即崩**。
 *   现在复位为 null + 服务器兜到 [ServerId.DEFAULT]。**不回落首个账户**：那个账户的凭据未必还有效，
 *   替用户悄悄切号会让「当前在用哪个号」不可预期，不如回到未登录态由用户自己选。
 *   脏值的落盘纠正留在内存复位这一步：`CredentialStore.setActiveUid(uid: String)` 只能写非空 uid、
 *   写不回 null，加清除口要动 CredentialStore（不在本棒独占文件内），故此处不越界。
 * - 命中账户 → 服务器取该账户 [StoredAccount.server]（与 null 分支同为「拿不到就 DEFAULT」的兜底语义）。
 */
internal fun planAccountRefresh(
    storedAccounts: List<StoredAccount>,
    storedActiveUid: String?,
): AccountRefreshPlan {
    val activeUid = storedActiveUid ?: storedAccounts.firstOrNull()?.uid
    if (activeUid == null) {
        return AccountRefreshPlan(activeUid = null, server = ServerId.DEFAULT, uidToPersist = null)
    }
    val matched = storedAccounts.firstOrNull { it.uid == activeUid }
        ?: return AccountRefreshPlan(activeUid = null, server = ServerId.DEFAULT, uidToPersist = null)
    return AccountRefreshPlan(
        activeUid = activeUid,
        server = matched.server(),
        uidToPersist = activeUid.takeIf { storedActiveUid == null },
    )
}

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
            // 连接层自愈（DNS/TCP/TLS 换路由重试）：只覆盖 socket 段，与 MihoyoClient
            // 业务层退避（信封 retcode / 解析）语义不同、各管一段，不会重复计数。
            .retryOnConnectionFailure(true)
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
        // 文案桥接线自检：数据层/ViewModel 取三语文案的通道必须随容器就绪
        // （正常路径下 GigiApp.onCreate 已 attach；容器单独构造时兜底）
        if (!LocaleStrings.resolved) LocaleStrings.attach(appContext)
        refreshAccounts()
    }

    /**
     * 从凭据区刷新账户内存态（🔴 只读不落盘）。决策本体在纯函数 [planAccountRefresh]
     * （含脏 activeUid 的兜底，AppContainerTest 锁死），这里只做「读盘 → 写内存态 → 必要的回写」。
     */
    fun refreshAccounts() {
        val storedAccounts = credentialStore.accounts()
        _accounts.value = storedAccounts
        val plan = planAccountRefresh(storedAccounts, credentialStore.activeUid())
        plan.uidToPersist?.let { credentialStore.setActiveUid(it) }
        _activeAccountUid.value = plan.activeUid
        _currentServer.value = plan.server
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
