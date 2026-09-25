// 登录态门控：对齐 web App.tsx AuthGate + auth.tsx AuthProvider 语义——
// 1) 本地有凭据 → 立即渲染主界面（设计 §3.5：零等待，检测在后台并行）；
// 2) sessionUid 语义：会话内曾成功登录后，单次 -500004/网络失败不得踢下线，
//    仅明确凭据失效码（-100/-101，isAuthFailureError 集中判定）才回登录页；
// 3) 登录成功（容器 sessionUid 已写）→ 切主界面。
// 退出登录经 LocalLogout 暴露（🔴 不改 GigiNavHost，接入由后续任务在页面侧完成）。

package com.gigi.tcg.ui.login

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.GigiApp
import com.gigi.tcg.data.api.isAuthFailureError
import com.gigi.tcg.data.auth.AuthFinalizeResult
import com.gigi.tcg.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    data class Main(val verified: Boolean) : GateUiState
}

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
        _uiState.value = if (hasCookie) GateUiState.Main(verified = false) else GateUiState.Login()
    }

    fun observeMain() {
        if (verifiedForCurrentSession) return
        verifiedForCurrentSession = true
        val generation = ++verificationGeneration
        val server = container.currentServer.value
        verificationJob = viewModelScope.launch {
            try {
                val loginInfo = container.repository.fetchLoginInfo(server)
                if (generation != verificationGeneration) return@launch
                val uid = loginInfo.gameUid
                if (uid.isNullOrEmpty()) {
                    if (container.sessionUid.value == null && container.activeAccountUid.value == null) {
                        _uiState.value = GateUiState.Login(expiredNotice = true)
                    }
                    return@launch
                }
                val store = container.credentialStore
                val activeUid = store.activeUid()
                if (activeUid == null || store.accounts().none { it.uid == activeUid }) {
                    store.adoptActiveCookie(uid, server, loginInfo.nickname)
                }
                if (generation != verificationGeneration) return@launch
                container.refreshAccounts()
                container.updateSession(uid)
                _uiState.value = GateUiState.Main(verified = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation != verificationGeneration) return@launch
                if (isAuthFailureError(e)) {
                    val store = container.credentialStore
                    val account = store.accounts()
                        .firstOrNull { it.uid == store.activeUid() }
                    val refreshed = account?.let {
                        try {
                            container.authManager.refreshStoredSession(it)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Throwable) {
                            null
                        }
                    }
                    if (generation != verificationGeneration) return@launch
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
            } finally {
                if (generation == verificationGeneration) verificationJob = null
            }
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
        if (container.credentialStore.cookieHeader() == null) return
        invalidateVerification()
        _uiState.value = GateUiState.Login(addAccount = true)
    }

    fun cancelAddAccount() {
        invalidateVerification()
        if (container.credentialStore.cookieHeader() != null) {
            verifiedForCurrentSession = true
            _uiState.value = GateUiState.Main(verified = true)
        } else {
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
            is GateUiState.Main -> GigiNavHost()

            is GateUiState.Login -> {
                val loginViewModel: LoginViewModel = viewModel(
                    key = if (current.addAccount) "login-add-account" else "login",
                    factory = LoginViewModel.factory(app, current.addAccount),
                )
                val loginState by loginViewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(loginViewModel) { loginViewModel.begin() }
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
