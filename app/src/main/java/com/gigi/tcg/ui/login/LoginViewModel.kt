// 登录页状态机：语义对齐 web LoginPage.tsx（startQr 循环 + runSeq 防重入）与
// 设计文档 §3.3（NoRole 零副作用：AuthManager 已保证不写凭据，本层不切登录态）。
// Expired/Cancelled/超时自动换码；Scanned 仅更新提示；Confirmed → finalize(当前服务器)。

package com.gigi.tcg.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.gigi.tcg.GigiApp
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.auth.AuthFinalizeResult
import com.gigi.tcg.data.auth.AuthManager
import com.gigi.tcg.data.auth.QrSession
import com.gigi.tcg.di.AppContainer
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 二维码存活期限：超过后即便上游未报 -3501 也主动换码（米游社二维码实测约 180s 失效） */
private const val QR_LIFETIME_MS = 180_000L

/** 二维码阶段：等待扫码 / 已扫描待确认 / 过期取消后换码中 */
enum class QrPhase { Waiting, Scanned, Refreshing }

sealed interface LoginUiState {
    /** 服务器级提示（NoRole 引导换服），跨状态保留直到用户切服 */
    val notice: String?

    data class Checking(override val notice: String? = null) : LoginUiState

    data class Qr(
        val payload: ImageBitmap,
        val phase: QrPhase,
        val server: ServerId,
        override val notice: String? = null,
    ) : LoginUiState

    /** 已确认，正在凭据交换 */
    data class Finalizing(val server: ServerId, override val notice: String? = null) : LoginUiState

    data class Failed(val message: String, val canRetry: Boolean = true) : LoginUiState {
        override val notice: String? = null
    }

    data class LoggedIn(val uid: String) : LoginUiState {
        override val notice: String? = null
    }
}

class LoginViewModel(
    app: Application,
    private val addAccount: Boolean = false,
) : AndroidViewModel(app) {

    private val container: AppContainer = (app as GigiApp).container
    private val authManager: AuthManager = container.authManager

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Checking())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _server = MutableStateFlow(container.currentServer.value)
    val serverFlow: StateFlow<ServerId> = _server.asStateFlow()

    private var qrJob: Job? = null
    private var finalizeJob: Job? = null

    /** LoginPage.tsx runSeqRef 语义：旧流程的迟到回调用 generation 判弃 */
    private var generation = 0

    init {
        // 登录成功切主界面后 NavHost 可能销毁重建本 VM：凭据已在且已 LoggedIn 时不再发起新流程
        if (addAccount || container.sessionUid.value == null) {
            startQr()
        } else {
            _uiState.value = LoginUiState.LoggedIn(container.sessionUid.value.orEmpty())
        }
    }

    /** 服务器二选一：换服即重启 QR 流程并清除换服提示（finalize/轮询均绑定当前服务器） */
    fun selectServer(server: ServerId) {
        if (_server.value == server) return
        _server.value = server
        clearNotice()
        startQr()
    }

    /** Failed 态"重新生成二维码"按钮（对齐 LoginPage 的 error 分支） */
    fun retry() = startQr()

    fun start() = begin()

    fun begin() {
        if (qrJob?.isActive == true || finalizeJob?.isActive == true) return
        generation++
        qrJob?.cancel()
        finalizeJob?.cancel()
        qrJob = null
        finalizeJob = null
        _server.value = container.currentServer.value
        _uiState.value = LoginUiState.Checking()
        startQr()
    }

    fun cancel() {
        generation++
        qrJob?.cancel()
        finalizeJob?.cancel()
        qrJob = null
        finalizeJob = null
        _uiState.value = LoginUiState.Checking()
    }

    private fun startQr() {
        val gen = ++generation
        qrJob?.cancel()
        finalizeJob?.cancel()
        qrJob = viewModelScope.launch {
            _uiState.value = LoginUiState.Checking(notice = _uiState.value.notice)
            try {
                val created = authManager.createQrLogin()
                if (gen != generation) return@launch
                val bitmap = try {
                    encodeQrBitmap(created.url)
                } catch (e: Exception) {
                    throw IOException("二维码生成失败")
                }
                _uiState.value = LoginUiState.Qr(
                    payload = bitmap,
                    phase = QrPhase.Waiting,
                    server = _server.value,
                    notice = _uiState.value.notice,
                )
                poll(gen, created, System.currentTimeMillis() + QR_LIFETIME_MS)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                if (gen == generation) {
                    _uiState.value = LoginUiState.Failed(e.message ?: "二维码生成失败")
                }
            }
        }
    }

    private suspend fun poll(gen: Int, created: QrSession.Created, deadline: Long) {
        authManager.pollQrStatus(created).collect { session ->
            if (gen != generation) return@collect
            when (session) {
                is QrSession.Created -> Unit
                QrSession.Polling -> {
                    if (System.currentTimeMillis() > deadline) restart(gen)
                }
                QrSession.Scanned -> setPhase(QrPhase.Scanned)
                is QrSession.Confirmed -> {
                    generation++
                    finalizeJob = viewModelScope.launch {
                        try {
                            finalize(session.cookieFragments)
                        } finally {
                            finalizeJob = null
                        }
                    }
                }
                QrSession.Expired, QrSession.Cancelled -> restart(gen)
            }
        }
    }

    private fun restart(gen: Int) {
        if (gen != generation) return
        generation++
        startQr() // startQr 内部 cancel 当前轮询 job
    }

    private suspend fun finalize(cookies: List<String>) {
        val server = _server.value
        _uiState.value = LoginUiState.Finalizing(server)
        try {
            when (val result = authManager.finalize(cookies, server)) {
                is AuthFinalizeResult.Success -> {
                    container.selectServer(server)
                    container.refreshAccounts()
                    container.updateSession(result.gameUid)
                    _uiState.value = LoginUiState.LoggedIn(result.gameUid)
                }
                is AuthFinalizeResult.NoRole -> {
                    // 🔴 设计 §3.3：凭据未写入（AuthManager 保证）、不切登录态，
                    // 留在登录页并高亮/提示当前服务器；对齐 web 继续 QR 流程，提示随状态保留
                    _uiState.value = LoginUiState.Checking(
                        notice = "该米游社账号未绑定${server.name}的原神角色，请切换服务器后重试",
                    )
                    startQr()
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            _uiState.value = LoginUiState.Failed(e.message ?: "登录失败，请重试")
        }
    }

    private fun setPhase(phase: QrPhase) {
        val current = _uiState.value
        if (current is LoginUiState.Qr) {
            _uiState.value = current.copy(phase = phase)
        }
    }

    private fun clearNotice() {
        val current = _uiState.value
        if (current.notice != null) {
            _uiState.value = when (current) {
                is LoginUiState.Checking -> LoginUiState.Checking()
                is LoginUiState.Qr -> current.copy(notice = null)
                is LoginUiState.Finalizing -> current.copy(notice = null)
                else -> current
            }
        }
    }

    companion object {
        fun factory(app: Application, addAccount: Boolean = false): ViewModelProvider.Factory =
            object : ViewModelProvider.AndroidViewModelFactory(app) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LoginViewModel(app, addAccount) as T
            }
    }
}

/** ZXing 编码：URL → BitMatrix → ImageBitmap（黑白，整数模块渲染） */
internal fun encodeQrBitmap(text: String, size: Int = 560): ImageBitmap {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        text,
        com.google.zxing.BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(matrix.width * matrix.height)
    var i = 0
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            pixels[i++] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    val bitmap = android.graphics.Bitmap.createBitmap(
        pixels, matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888,
    )
    return bitmap.asImageBitmap()
}
