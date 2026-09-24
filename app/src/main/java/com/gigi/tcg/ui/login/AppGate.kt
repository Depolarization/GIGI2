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
import com.gigi.tcg.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.gigi.tcg.ui.navigation.GigiNavHost

/** 退出登录回调：清密文 + 删 Keystore 别名 + 内存会话重置（设计红线 4） */
val LocalLogout = compositionLocalOf<() -> Unit> { {} }

sealed interface GateUiState {
    /** 启动瞬态：本地凭据读取（Keystore 解密）尚未完成 */
    data object Unauthenticated : GateUiState

    data class Login(val expiredNotice: Boolean = false) : GateUiState

    data class Main(val verified: Boolean) : GateUiState
}

class GateViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer = (app as GigiApp).container

    private val _uiState = MutableStateFlow<GateUiState>(GateUiState.Unauthenticated)
    val uiState: StateFlow<GateUiState> = _uiState.asStateFlow()

    /** 每次进入主界面只后台校验一次（对齐 web check 的触发时机） */
    private var verifiedForCurrentSession = false

    init {
        // 同步读本地凭据（SharedPreferences 已缓存 + AES-GCM 解密，毫秒级）：
        // 有凭据即直达主界面骨架，后台校验（设计 §3.5 零等待）
        val hasCookie = runCatching { container.credentialStore.cookieHeader() != null }
            .getOrDefault(false)
        _uiState.value = if (hasCookie) GateUiState.Main(verified = false) else GateUiState.Login()
    }

    fun observeMain() {
        viewModelScope.launch {
            if (verifiedForCurrentSession) return@launch
            verifiedForCurrentSession = true
            try {
                val uid = container.repository
                    .fetchLoginInfo(container.currentServer.value)
                    .gameUid
                if (uid.isNullOrEmpty()) {
                    // 合法响应但无 game_uid：仅在确无有效会话时判定失效（auth.tsx 语义）
                    if (container.sessionUid.value == null) {
                        _uiState.value = GateUiState.Login(expiredNotice = true)
                    }
                    return@launch
                }
                container.updateSession(uid)
                _uiState.value = GateUiState.Main(verified = true)
            } catch (e: Throwable) {
                if (isAuthFailureError(e)) {
                    // 明确的凭据失效码：无论有无会话都回登录页
                    container.updateSession(null)
                    verifiedForCurrentSession = false
                    _uiState.value = GateUiState.Login(expiredNotice = true)
                }
                // 其余（-500004 限流/网络失败）：🔴 保持主界面不踢下线，错误由页面自身提示
            }
        }
    }

    fun onLoggedIn(uid: String) {
        container.updateSession(uid)
        verifiedForCurrentSession = true
        _uiState.value = GateUiState.Main(verified = true)
    }

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.credentialStore.clear() }
            container.updateSession(null)
            verifiedForCurrentSession = false
            _uiState.value = GateUiState.Login()
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
    CompositionLocalProvider(LocalLogout provides { viewModel.logout() }) {
        when (state) {
            is GateUiState.Main -> GigiNavHost()

            is GateUiState.Login -> {
                val loginViewModel: LoginViewModel = viewModel(factory = LoginViewModel.factory(app))
                val loginState by loginViewModel.uiState.collectAsStateWithLifecycle()
                LoginScreen(loginViewModel, expiredNotice = (state as GateUiState.Login).expiredNotice)
                LaunchedEffect(loginState) {
                    (loginState as? LoginUiState.LoggedIn)?.let { viewModel.onLoggedIn(it.uid) }
                }
            }

            GateUiState.Unauthenticated -> Unit // 瞬态：不阻塞下一帧
        }
    }
}
