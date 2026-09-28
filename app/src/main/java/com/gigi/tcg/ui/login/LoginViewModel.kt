// 登录页状态机：语义对齐 web LoginPage.tsx（startQr 循环 + runSeq 防重入）与
// 设计文档 §3.3（NoRole 零副作用：AuthManager 已保证不写凭据，本层不切登录态）。
// Expired/Cancelled/超时自动换码；Scanned 仅更新提示；Confirmed → finalize(当前服务器)。
// V29-B：切换服务器**不再换码**（二维码与 ServerId 无关，只有 finalize 认服务器），
// 换码只由码自身失效驱动，理由与不变式见 selectServer。
// 登录成功这一刻静默删除相册里最后保存的那张二维码（自动换码/取消/无角色都不删），
// 删除判定收敛为纯函数 shouldDeleteSavedQr（QrSaveTargetTest 钉死）。
// V27-E：二维码 PNG 落盘 Pictures/GIGI/二维码/（不带 UID）编排在本 VM（saveQrToAlbum），
// 位图仍由状态机持有、UI 只读取；换码/离开页面的位图释放见 retiredQrBitmap / onCleared。

package com.gigi.tcg.ui.login

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.gigi.tcg.GigiApp
import com.gigi.tcg.R
import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.auth.AuthFinalizeResult
import com.gigi.tcg.data.auth.AuthManager
import com.gigi.tcg.data.auth.QrCreateException
import com.gigi.tcg.data.auth.QrSession
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.i18n.displayNameSync
import com.gigi.tcg.i18n.serverDisplayNameSync
import com.gigi.tcg.ui.dialogs.cardcover.CardImageSaver
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_QR
import com.gigi.tcg.ui.dialogs.cardcover.GIGI_ALBUM_NAME
import com.gigi.tcg.ui.dialogs.cardcover.buildAlbumRelativePath
import com.gigi.tcg.ui.dialogs.cardcover.exportDateText
import com.gigi.tcg.ui.dialogs.cardcover.exportDirName
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 二维码落盘目标（V27 冻结契约，纯值便于 JVM 单测钉死，见 QrSaveTargetTest）：
 * - 位置 = Pictures/GIGI/<export_dir_qr>/，二维码是临时图，按任务契约**不带 UID 一级**；
 * - 文件名前缀 = R.string.login_qr_title 的本地化文案（实测产物 `扫码登录_2026-09-27.png`；
 *   添加账户分支顶栏标题虽为「添加账户」，落盘前缀仍按此口径，保持历史文件名不变）；
 * - PNG 强制：黑白码走 JPEG 会压出噪点、影响扫码（历史决策，不得改回 JPEG）。
 */
internal data class QrSaveTarget(
    @StringRes val dirRes: Int,
    @StringRes val fileNamePrefixRes: Int,
    val format: Bitmap.CompressFormat,
)

internal fun qrSaveTarget(): QrSaveTarget = QrSaveTarget(
    dirRes = EXPORT_DIR_QR,
    fileNamePrefixRes = R.string.login_qr_title,
    format = Bitmap.CompressFormat.PNG,
)

/** 保存文件名主干 = 前缀_日期（前缀/日期由 UI 侧解析本地化后注入，纯拼接便于单测） */
internal fun qrSaveBaseName(prefix: String, dateText: String): String = "${prefix}_$dateText"

/** 二维码生命周期事件：已保存相册二维码「删不删」的唯一决策输入 */
internal enum class QrLifecycleEvent {
    /** 扫码登录成功 → 删 */
    LoginSucceeded,

    /** 过期/超时/手动重新生成自动换码 → 保留（新一轮保存会覆盖 lastSavedQrUri） */
    Refreshed,

    /** 退出/取消添加账户 → 保留（用户没登录，图是他主动存的） */
    Cancelled,

    /** 无角色引导换服 → 保留（登录未完成） */
    NoRole,
}

internal fun shouldDeleteSavedQr(event: QrLifecycleEvent): Boolean =
    event == QrLifecycleEvent.LoginSucceeded

/**
 * no-role 提示文案分因（纯函数，NoRoleNoticeTest 钉死）。
 *
 * 🔴 为什么必须分因：判据只有一个 —— [AuthFinalizeResult.NoRole.region] 没在
 * `getGameRecordCard` 返回里命中**带 game_role_id 的**同 region 项。但"没命中"有两种截然
 * 不同的成因，此前被合并成同一句"该账号未绑定 X 的原神角色"：
 * 1. **真没绑 / 角色不带 id**（[AuthFinalizeResult.NoRole.boundRegions] 为空）——原提示正确；
 * 2. **角色绑在别的区服**（boundRegions 非空且不含所选服）——典型如"渠道服用户扫了码但角色
 *    绑在官服"（或反之）。此时原提示是**误导**：用户会认定自己没绑角色，于是反复换码重扫，
 *    而正确动作只是切到角色所在的服务器。
 *
 * 所以第 2 种改用 [R.string.login_no_role_elsewhere_notice]，把"绑在哪"直接说出来。
 * 纯展示逻辑，不改变任何凭据判定（登录成败仍由 AuthManager 决定）。
 */
internal fun noRoleNotice(result: AuthFinalizeResult.NoRole, server: ServerId): String {
    val selected = server.displayNameSync()
    val elsewhere = result.boundRegions
        .filter { it != result.region }
        .map { serverDisplayNameSync(it) }
    // 🔴 用 getOrDefault 而非 get：JVM 单测无 Android 资源桥，get 会返回 [missing:…] 哨兵，
    // 默认字面量与 values/strings.xml 保持一致（与数据层既有约定同范式）。
    return if (elsewhere.isEmpty()) {
        LocaleStrings.getOrDefault(
            R.string.login_no_role_notice,
            "该米游社账号未绑定%1\$s的原神角色，请切换服务器后重试",
            selected,
        )
    } else {
        LocaleStrings.getOrDefault(
            R.string.login_no_role_elsewhere_notice,
            "该米游社账号的原神角色绑定在%1\$s，不在你选择的%2\$s，请切换服务器后重试",
            elsewhere.joinToString("、"),
            selected,
        )
    }
}

/**
 * 保存动作回执：Success 带相册相对路径（Toast 文案参数）；Failure 已含本地化文案。
 * 与 public 的 [LoginViewModel.saveQrToAlbum] 配套，故同为 public（internal 会被「public 函数
 * 暴露 internal 类型」挡下）。
 */
sealed interface QrSaveOutcome {
    data class Success(val albumPath: String) : QrSaveOutcome
    data class Failure(val message: String) : QrSaveOutcome
}

/** 二维码存活期限：超过后即便上游未报 -3501 也主动换码（米游社二维码实测约 180s 失效） */
private const val QR_LIFETIME_MS = 180_000L

/** 二维码失败诊断日志 tag（真机 logcat -s GigiQr 定位瞬时失败是网络类还是业务类） */
private const val QR_LOG_TAG = "GigiQr"

/** 二维码阶段：等待扫码 / 已扫描待确认 / 过期取消后换码中 */
enum class QrPhase { Waiting, Scanned, Refreshing }

sealed interface LoginUiState {
    /** 服务器级提示（NoRole 引导换服），跨状态保留直到用户切服 */
    val notice: String?

    data class Checking(override val notice: String? = null) : LoginUiState

    /**
     * 展示中的二维码。
     * 🔴 这里**不记**服务器：码本身与服务器无关（见 [selectServer]），而选中的服务器随时可被
     * 用户切走。若在此快照一份，换服后它就变成"码是为 A 服的"的假事实——真要读服务器，
     * 唯一可信来源是 [serverFlow] / finalize() 当场取的 `_server.value`。
     */
    data class Qr(
        val payload: ImageBitmap,
        val phase: QrPhase,
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

    private val _qrSaving = MutableStateFlow(false)

    /** 保存进行中：UI 据此禁用按钮并显示进度 */
    val qrSaving: StateFlow<Boolean> = _qrSaving.asStateFlow()

    private var qrJob: Job? = null
    private var finalizeJob: Job? = null

    /** 相册里最后保存的那张二维码的 uri（UI 保存成功后的回执）：只在登录成功这一刻删，换码/取消不动 */
    private var lastSavedQrUri: Uri? = null

    /**
     * 已退役、待回收的二维码位图：状态离开 Qr 时只收引用不立即 recycle（当帧渲染树可能还在用），
     * 推迟到「下一张新码就绪」（中间隔了整轮 createQrLogin 网络往返，旧图早已离开组合）
     * 或 onCleared 才真正回收，避免 560×560 ARGB（约 1.2MB）随换码累积泄漏。
     */
    private var retiredQrBitmap: Bitmap? = null

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

    /**
     * 服务器二选一：**只改目标服务器，不换码**。
     *
     * 为什么换服不换码仍能用新服 finalize —— 二维码与服务器本来就没有绑定关系：
     * - 出码（[AuthManager.createQrLogin]）与轮询（[AuthManager.pollQrStatus]）打的是
     *   passport 的 createQRLogin / queryQRLoginStatus，全程不读 ServerId；
     *   [QrSession.Created] 只携带 url / ticket / deviceId，三者都不是"按服务器建的会话"。
     * - ServerId 唯一被消费的地方是凭据交换 [AuthManager.finalize]（拿 account_id 去
     *   getGameRecordCard 找该 server.region 的角色，再 badge login 换 e_hk4e_token）。
     * - 而 [finalize] 是在 Confirmed 到达那一刻才读 `_server.value`（见下方 finalize()），
     *   不是出码时快照 ⇒ 只要用户在扫码确认之前把选中的服务器改掉，这同一张码确认后的
     *   finalize 自然走新服务器。
     *
     * 所以旧实现里的 startQr() 是纯粹的浪费：重新出一张内容完全等价的码（同一 passport
     * 会话），还顺带把用户已经扫了一半的码作废掉。真正的换码只应由码自身失效驱动
     *（QrSession.Expired / Cancelled / 超过 QR_LIFETIME_MS → restart()，本方法不碰）。
     */
    fun selectServer(server: ServerId) {
        if (_server.value == server) return
        _server.value = server
        // NoRole 提示是"这台服务器没有角色"，改了目标服务器即失效
        clearNotice()
    }

    /** Failed 态"重新生成二维码"按钮（对齐 LoginPage 的 error 分支） */
    fun retry() = startQr()

    /** 保存按钮落盘成功的回执；uri 为空（API 24-28 无 MediaStore 条目）则无从删除，忽略 */
    fun onQrSaved(uri: Uri?) {
        if (uri != null) lastSavedQrUri = uri
    }

    /**
     * 保存当前展示的二维码到相册（V27-E：目标/前缀/PNG 口径见 qrSaveTarget，不带 UID 一级）。
     * 位图沿用状态机持有的同一实例，不重新编码、不 recycle（回收会砸掉正在显示的二维码）。
     * 挂在 UI 的 rememberCoroutineScope 上执行：离开页面即取消，不把半份保存状态挂在
     * viewModelScope（LoggedIn 后 AppGate 会 cancel 掉本 VM 的 viewModelScope 任务）。
     */
    suspend fun saveQrToAlbum(): QrSaveOutcome {
        val qr = _uiState.value as? LoginUiState.Qr
            ?: return QrSaveOutcome.Failure(LocaleStrings.get(R.string.toast_save_failed))
        // 防重入：UI 已按 qrSaving 禁用，这里兜住竞态
        if (!_qrSaving.compareAndSet(expect = false, update = true)) {
            return QrSaveOutcome.Failure(LocaleStrings.get(R.string.toast_save_failed))
        }
        return try {
            val target = qrSaveTarget()
            val dir = exportDirName(target.dirRes)
            try {
                val uri = CardImageSaver(getApplication()).saveBitmap(
                    bitmap = qr.payload.asAndroidBitmap(),
                    // 前缀 = login_qr_title 本地化文案（文件名口径见 QrSaveTarget 注释）
                    baseName = qrSaveBaseName(
                        LocaleStrings.get(target.fileNamePrefixRes),
                        exportDateText(),
                    ),
                    format = target.format,
                    subDir = dir,
                )
                onQrSaved(uri)
                QrSaveOutcome.Success(
                    buildAlbumRelativePath(Environment.DIRECTORY_PICTURES, GIGI_ALBUM_NAME, dir),
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                QrSaveOutcome.Failure(e.message ?: LocaleStrings.get(R.string.toast_save_failed))
            }
        } finally {
            _qrSaving.value = false
        }
    }

    fun start() = begin()

    fun begin() {
        if (qrJob?.isActive == true || finalizeJob?.isActive == true) return
        generation++
        qrJob?.cancel()
        finalizeJob?.cancel()
        qrJob = null
        finalizeJob = null
        _server.value = container.currentServer.value
        retireQrBitmap() // 上一轮的码退役，新码就绪/VM 销毁时回收
        _uiState.value = LoginUiState.Checking()
        startQr()
    }

    fun cancel() {
        generation++
        qrJob?.cancel()
        finalizeJob?.cancel()
        qrJob = null
        finalizeJob = null
        retireQrBitmap()
        // 取消添加账户（及首登离开清理）都不动已保存的二维码：登录成功那刻已删过
        onQrLifecycleEvent(QrLifecycleEvent.Cancelled)
        _uiState.value = LoginUiState.Checking()
    }

    private fun startQr() {
        val gen = ++generation
        qrJob?.cancel()
        finalizeJob?.cancel()
        retireQrBitmap()
        qrJob = viewModelScope.launch {
            _uiState.value = LoginUiState.Checking(notice = _uiState.value.notice)
            try {
                val created = authManager.createQrLogin()
                if (gen != generation) return@launch
                val bitmap = try {
                    encodeQrBitmap(created.url)
                } catch (e: Exception) {
                    throw IOException(LocaleStrings.get(R.string.error_qr_failed))
                }
                // 旧退役图此刻已离开组合一整个网络往返（新帧早已按 Checking 渲染），可安全回收
                recycleRetiredQrBitmap()
                _uiState.value = LoginUiState.Qr(
                    payload = bitmap,
                    phase = QrPhase.Waiting,
                    notice = _uiState.value.notice,
                )
                poll(gen, created, System.currentTimeMillis() + QR_LIFETIME_MS)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                if (gen == generation) {
                    // 只记类别与 message：二维码 url / cookie / deviceId 一律不入日志
                    Log.w(QR_LOG_TAG, "createQrLogin 失败：${e.javaClass.name}: ${e.message}")
                    _uiState.value = LoginUiState.Failed(qrFailureMessage(e))
                }
            }
        }
    }

    /** 失败文案分流：网络类（AuthManager 重试已耗尽）给统一指引；业务类透出米哈游原始 message */
    private fun qrFailureMessage(e: Exception): String =
        if (e is QrCreateException && e.isNetwork) {
            LocaleStrings.get(R.string.error_network_unstable)
        } else {
            e.message ?: LocaleStrings.get(R.string.error_qr_failed)
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
        onQrLifecycleEvent(QrLifecycleEvent.Refreshed) // 自动换码保留已保存的旧图（语义钉在决策表）
        startQr() // startQr 内部 cancel 当前轮询 job
    }

    private suspend fun finalize(cookies: List<String>) {
        val server = _server.value
        retireQrBitmap() // 确认后码已失效，展示使命结束，进入回收链
        _uiState.value = LoginUiState.Finalizing(server)
        try {
            when (val result = authManager.finalize(cookies, server)) {
                is AuthFinalizeResult.Success -> {
                    container.selectServer(server)
                    container.refreshAccounts()
                    container.updateSession(result.gameUid)
                    _uiState.value = LoginUiState.LoggedIn(result.gameUid)
                    onQrLifecycleEvent(QrLifecycleEvent.LoginSucceeded)
                }
                is AuthFinalizeResult.NoRole -> {
                    // 🔴 设计 §3.3：凭据未写入（AuthManager 保证）、不切登录态，
                    // 留在登录页并高亮/提示当前服务器；对齐 web 继续 QR 流程，提示随状态保留
                    onQrLifecycleEvent(QrLifecycleEvent.NoRole) // 登录未完成：不删已保存的码
                    _uiState.value = LoginUiState.Checking(notice = noRoleNotice(result, server))
                    startQr()
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            _uiState.value = LoginUiState.Failed(e.message ?: LocaleStrings.get(R.string.error_login_retry))
        }
    }

    /** 生命周期事件 →「删已保存二维码」决策表（纯函数 shouldDeleteSavedQr，QrSaveTargetTest 钉死） */
    private fun onQrLifecycleEvent(event: QrLifecycleEvent) {
        if (shouldDeleteSavedQr(event)) deleteSavedQrImage()
    }

    /**
     * 登录成功后的相册清理：只删最后保存的那一张，先清状态避免重复删。
     * 🔴 静默容错 —— 走 IO 协程且吞掉一切异常，删图失败绝不影响登录流程
     *（viewModelScope 在 LoggedIn 后不被 cancel() 取消，本协程能跑完）。
     */
    private fun deleteSavedQrImage() {
        val uri = lastSavedQrUri ?: return
        lastSavedQrUri = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.w(QR_LOG_TAG, "删除已保存二维码失败：${e.javaClass.name}: ${e.message}")
            }
        }
    }

    /**
     * Qr→非 Qr 切换时退役当前位图：只收集引用、不回收（当帧渲染树可能仍在引用它）。
     * 不变式：retiredQrBitmap 非空时状态必不在 Qr（新 Qr 上屏前必先回收旧退役图），
     * 故此处不会覆盖掉未回收的引用。
     */
    private fun retireQrBitmap() {
        val current = _uiState.value as? LoginUiState.Qr ?: return
        retiredQrBitmap = current.payload.asAndroidBitmap()
    }

    private fun recycleRetiredQrBitmap() {
        retiredQrBitmap?.takeIf { !it.isRecycled }?.recycle()
        retiredQrBitmap = null
    }

    override fun onCleared() {
        // VM 销毁（Activity 结束）时组合必已离开：兜底释放最后一张在架位图与退役位图
        (_uiState.value as? LoginUiState.Qr)?.payload?.asAndroidBitmap()
            ?.takeIf { !it.isRecycled }?.recycle()
        recycleRetiredQrBitmap()
        super.onCleared()
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
