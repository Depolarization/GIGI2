// 登录态门控：对齐 web App.tsx AuthGate + auth.tsx AuthProvider 语义——
// 1) 本地有凭据 → 立即渲染主界面（设计 §3.5：零等待，检测在后台并行）；
// 2) sessionUid 语义：会话内曾成功登录后，单次 -500004/网络失败不得踢下线，
//    仅明确凭据失效码（-100/-101，isAuthFailureError 集中判定）才回登录页；
// 3) 登录成功（容器 sessionUid 已写）→ 切主界面。
// 退出登录经 LocalLogout 暴露（🔴 不改 GigiNavHost，接入由后续任务在页面侧完成）。

package com.gigi.tcg.ui.login

import android.app.Application
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.GigiApp
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.api.isAuthFailureError
import com.gigi.tcg.data.auth.AuthFinalizeResult
import com.gigi.tcg.data.model.LoginInfoData
import com.gigi.tcg.di.AppContainer
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.gigi.tcg.ui.navigation.GigiNavHost

/** 退出登录回调：清密文 + 删 Keystore 别名 + 内存会话重置（设计红线 4） */
val LocalLogout = compositionLocalOf<() -> Unit> { {} }

data class AccountActions(
    val switchAccount: (String) -> Unit,
    val addAccount: () -> Unit,
    val logout: () -> Unit,
)

val LocalAccountActions = compositionLocalOf<AccountActions> {
    AccountActions({}, {}, {})
}

sealed interface GateUiState {
    /** 启动瞬态：本地凭据读取（Keystore 解密）尚未完成 */
    data object Unauthenticated : GateUiState

    data class Login(
        val expiredNotice: Boolean = false,
        val addAccount: Boolean = false,
    ) : GateUiState

    /**
     * 主界面态。addAccount=true 是"叠加层"标记：AppGate 仍在 Main 分支内（GigiNavHost
     * 不离开组合、导航栈与滚动位置保留），仅在其上盖一层添加账号 LoginScreen；
     * 🔴 添加账号的进入/退出不切分支、不 invalidateVerification（V7B）。
     */
    data class Main(val verified: Boolean, val addAccount: Boolean = false) : GateUiState
}

/**
 * 启动播种判定（P0-1，纯函数便于 JVM 测）：本地有凭据且已有激活账户索引时，
 * 该账户 uid（= gameUid，见 CredentialStore.adoptActiveCookie 落盘语义）即可直接作为会话 uid。
 * 无凭据 → null（登录页）；旧版单槽（有 cookie 无账户索引）→ null，交由 observeMain 校验后收养。
 */
internal fun seededSessionUid(hasCookie: Boolean, activeAccountUid: String?): String? =
    if (hasCookie) activeAccountUid else null

/**
 * 校验请求重试判定（P0-2，纯函数便于 JVM 测）：仅"非鉴权失败"的瞬时错误可退避重试，
 * 且未超出尝试上限。鉴权失败（-100/-101）走既有续命/登出路径，不在此重试。
 */
internal fun shouldRetryVerify(t: Throwable, attempt: Int, maxAttempts: Int): Boolean =
    t !is CancellationException && !isAuthFailureError(t) && attempt in 1..maxAttempts

class GateViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as GigiApp).container

    private val _uiState = MutableStateFlow<GateUiState>(GateUiState.Unauthenticated)
    val uiState: StateFlow<GateUiState> = _uiState.asStateFlow()

    private var verifiedForCurrentSession = false
    private var verificationJob: Job? = null
    private var verificationGeneration = 0L
    private var logoutJob: Job? = null

    private fun invalidateVerification() {
        verificationGeneration++
        verificationJob?.cancel()
        verificationJob = null
        verifiedForCurrentSession = false
    }

    init {
        container.refreshAccounts()
        val hasCookie = runCatching { container.credentialStore.cookieHeader() != null }
            .getOrDefault(false)
        // 设计 1（零等待）：本地凭据的账户 uid 即 gameUid（adoptActiveCookie/activateAccount
        // 均以 account.uid 作会话身份），有激活账户就先播种 sessionUid，页面不必等校验请求返回。
        seededSessionUid(hasCookie, container.activeAccountUid.value)?.let { container.updateSession(it) }
        _uiState.value = if (hasCookie) GateUiState.Main(verified = false) else GateUiState.Login()
    }

    fun observeMain() {
        if (verifiedForCurrentSession) return
        verifiedForCurrentSession = true
        val generation = ++verificationGeneration
        val server = container.currentServer.value
        verificationJob = viewModelScope.launch {
            try {
                handleVerifyAttempt(generation, server)
            } finally {
                if (generation == verificationGeneration) verificationJob = null
            }
        }
    }

    /**
     * 单次校验闭环：请求（瞬时失败退避重试 1s/2s/3s，最多 MAX_VERIFY_ATTEMPTS 次）→
     * 成功收养/落会话；鉴权失败走续命分支（行为不变）；重试用尽的瞬时失败保持 Main，
     * 仅复位 verifiedForCurrentSession 供后续再触发，🔴 绝不回登录页（设计 2）。
     */
    private suspend fun handleVerifyAttempt(generation: Long, server: ServerId) {
        var attempt = 0
        var loginInfo: LoginInfoData? = null
        while (loginInfo == null) {
            attempt++
            val result = runCatching { container.repository.fetchLoginInfo(server) }
            loginInfo = when {
                result.isSuccess -> result.getOrThrow()
                result.exceptionOrNull() is CancellationException ->
                    throw result.exceptionOrNull() as CancellationException
                else -> {
                    val error = result.exceptionOrNull() as Throwable
                    if (generation != verificationGeneration) return
                    if (isAuthFailureError(error)) {
                        handleAuthFailure(generation, error)
                        return
                    }
                    if (!shouldRetryVerify(error, attempt, MAX_VERIFY_ATTEMPTS)) {
                        Log.w(
                            TAG,
                            "verify retry exhausted ($attempt/$MAX_VERIFY_ATTEMPTS): " +
                                "${error.javaClass.simpleName}: ${error.message}",
                        )
                        verifiedForCurrentSession = false
                        return
                    }
                    Log.w(TAG, "verify retry $attempt/$MAX_VERIFY_ATTEMPTS: ${error.javaClass.simpleName}: ${error.message}")
                    delay(attempt * VERIFY_RETRY_BASE_MS)
                    null
                }
            }
        }
        if (generation != verificationGeneration) return
        val uid = loginInfo.gameUid
        if (uid.isNullOrEmpty()) {
            if (container.sessionUid.value == null && container.activeAccountUid.value == null) {
                _uiState.value = GateUiState.Login(expiredNotice = true)
            }
            return
        }
        val store = container.credentialStore
        val activeUid = store.activeUid()
        if (activeUid == null || store.accounts().none { it.uid == activeUid }) {
            store.adoptActiveCookie(uid, server, loginInfo.nickname)
        }
        if (generation != verificationGeneration) return
        container.refreshAccounts()
        container.updateSession(uid)
        _uiState.value = GateUiState.Main(verified = true)
    }

    /** 鉴权失败（-100/-101）：先静默续命，成功则落新会话，失败才登出回登录页（既有行为不变）。 */
    private suspend fun handleAuthFailure(generation: Long, error: Throwable) {
        val store = container.credentialStore
        val account = store.accounts().firstOrNull { it.uid == store.activeUid() }
        val refreshed = account?.let {
            try {
                container.authManager.refreshStoredSession(it)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                null
            }
        }
        if (generation != verificationGeneration) return
        if (refreshed is AuthFinalizeResult.Success) {
            container.refreshAccounts()
            container.updateSession(refreshed.gameUid)
            _uiState.value = GateUiState.Main(verified = true)
        } else {
            container.updateSession(null)
            verifiedForCurrentSession = false
            _uiState.value = GateUiState.Login(expiredNotice = true)
        }
    }

    fun onLoggedIn(uid: String) {
        invalidateVerification()
        container.refreshAccounts()
        container.updateSession(uid)
        verifiedForCurrentSession = true
        _uiState.value = GateUiState.Main(verified = true)
    }

    fun switchAccount(uid: String) {
        invalidateVerification()
        if (!container.activateAccount(uid)) {
            verifiedForCurrentSession = true
            _uiState.value = GateUiState.Main(verified = true)
            return
        }
        container.repository.clearPrivateCache()
        _uiState.value = GateUiState.Main(verified = false)
        observeMain()
    }

    fun addAccount() {
        if (container.credentialStore.cookieHeader() == null) {
            _uiState.value = GateUiState.Login()
            return
        }
        // 🔴 不调 invalidateVerification()：添加账号是叠加层，不该重跑启动校验（V7B）
        val verified = (_uiState.value as? GateUiState.Main)?.verified ?: true
        _uiState.value = GateUiState.Main(verified = verified, addAccount = true)
    }

    fun cancelAddAccount() {
        val current = _uiState.value
        if (current is GateUiState.Main) {
            // 只摘掉叠加层：Main 分支不切换 ⇒ GigiNavHost 不重建，导航栈/滚动保留
            _uiState.value = current.copy(addAccount = false)
        } else {
            // 兜底：确实没 cookie 的情况
            container.updateSession(null)
            _uiState.value = GateUiState.Login()
        }
    }

    fun logout() {
        if (logoutJob?.isActive == true) return
        invalidateVerification()
        val operationGeneration = verificationGeneration
        logoutJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val store = container.credentialStore
                val activeUid = store.activeUid()
                val next = if (activeUid == null) {
                    store.clearLegacy()
                    store.accounts().firstOrNull()
                } else {
                    store.removeAccount(activeUid).firstOrNull()
                }
                if (operationGeneration != verificationGeneration) return@launch
                container.repository.clearPrivateCache()
                if (next != null && container.activateAccount(next.uid)) {
                    _uiState.value = GateUiState.Main(verified = false)
                    observeMain()
                } else {
                    container.refreshAccounts()
                    container.updateSession(null)
                    _uiState.value = GateUiState.Login()
                }
            } finally {
                logoutJob = null
            }
        }
    }

    companion object {
        private const val TAG = "GigiGate"
        private const val MAX_VERIFY_ATTEMPTS = 3
        private const val VERIFY_RETRY_BASE_MS = 1_000L

        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(app) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GateViewModel(app) as T
            }
    }
}

@Composable
fun AppGate(viewModel: GateViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is GateUiState.Main) viewModel.observeMain()
    }

    val app = viewModel.getApplication<Application>()
    CompositionLocalProvider(
        LocalLogout provides { viewModel.logout() },
        LocalAccountActions provides AccountActions(
            switchAccount = viewModel::switchAccount,
            addAccount = viewModel::addAccount,
            logout = viewModel::logout,
        ),
    ) {
        when (val current = state) {
            is GateUiState.Main -> {
                // GigiNavHost 始终留在组合里：addAccount 只叠加/摘除覆盖层，不切分支，
                // 导航栈与滚动位置在"添加账号→返回"路径上自然保留（V7B 核心）。
                Box(Modifier.fillMaxSize()) {
                    GigiNavHost()
                    if (current.addAccount) {
                        val loginViewModel: LoginViewModel = viewModel(
                            key = "login-add-account",
                            factory = LoginViewModel.factory(app, true),
                        )
                        val loginState by loginViewModel.uiState.collectAsStateWithLifecycle()
                        LaunchedEffect(loginViewModel) { loginViewModel.begin() }
                        // 🔴 VM 挂在 Activity 级 ViewModelStore、跨组合进出持久；不重置会让"第二次添加账号"
                        // 读到残留的 LoggedIn，被 LaunchedEffect(loginState) 立即消费 ⇒ 叠加层闪退（V7E）。
                        DisposableEffect(loginViewModel) {
                            onDispose { loginViewModel.cancel() }
                        }
                        // 不透明全屏覆盖：盖住顶栏与底部导航，阻断穿透点击
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                        ) {
                            LoginScreen(
                                viewModel = loginViewModel,
                                expiredNotice = false,
                                addAccount = true,
                                onCancelAddAccount = {
                                    loginViewModel.cancel()
                                    viewModel.cancelAddAccount()
                                },
                            )
                        }
                        LaunchedEffect(loginState) {
                            (loginState as? LoginUiState.LoggedIn)?.let { viewModel.onLoggedIn(it.uid) }
                        }
                    }
                }
            }

            is GateUiState.Login -> {
                val loginViewModel: LoginViewModel = viewModel(
                    key = if (current.addAccount) "login-add-account" else "login",
                    factory = LoginViewModel.factory(app, current.addAccount),
                )
                val loginState by loginViewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(loginViewModel) { loginViewModel.begin() }
                // 防御性对称（V7E）：当前状态机已产不出 Login(addAccount=true)，但 key="login"/
                // "login-add-account" 的 VM 同样跨组合持久，退出本分支时必须重置残留终态。
                DisposableEffect(loginViewModel) {
                    onDispose { loginViewModel.cancel() }
                }
                LoginScreen(
                    viewModel = loginViewModel,
                    expiredNotice = current.expiredNotice,
                    addAccount = current.addAccount,
                    onCancelAddAccount = if (current.addAccount) {
                        {
                            loginViewModel.cancel()
                            viewModel.cancelAddAccount()
                        }
                    } else {
                        null
                    },
                )
                LaunchedEffect(loginState) {
                    (loginState as? LoginUiState.LoggedIn)?.let { viewModel.onLoggedIn(it.uid) }
                }
            }

            GateUiState.Unauthenticated -> Unit
        }
    }
}
