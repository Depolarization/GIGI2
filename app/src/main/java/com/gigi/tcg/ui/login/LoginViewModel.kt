// 登录页状态机：语义对齐 web LoginPage.tsx（startQr 循环 + runSeq 防重入）与
// 设计文档 §3.3（NoRole 零副作用：AuthManager 已保证不写凭据，本层不切登录态）。
// Expired/Cancelled/超时自动换码；Scanned 仅更新提示；Confirmed → finalize(凭据)。
// V33（2026-09-28）：拆除服务器预选控件 —— 二维码与 ServerId 无关（V29-B 结论成立），
// 而服务器归属的正确决策时机是**扫码确认之后**：finalize(凭据) 按真实绑定角色列表当场
// 决策（唯一角色直接登录 / 多角色弹 ChooseRole 交用户选 / 零角色提示去米游社绑定）。
// 登录前的二选一本质是"让用户替系统猜"：渠道服用户必须先知"渠道服=世界树"才能登进去，
// 单角色账号也要无谓地多一次操作；选择器撤除后，渠道服识别由数据（region/is_official）负责。
// V34（2026-09-28）：ChooseRole 由单选改**多选** —— chooseRoles 对每个选中角色逐个走
// AuthManager.finalize（指定服务器入口）并落盘为多账户（CredentialStore 账户级存储天然支持，
// V32 真机已验证双账户并存）；全成功激活第一个、全失败 Failed、部分失败保留凭据回选择器
// （候选只列失败项 + partialFailNotice），无需重新扫码即可补登。
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

    /** 无角色提示去米游社绑定（V33 前为"引导换服"）→ 保留（登录未完成） */
    NoRole,
}

internal fun shouldDeleteSavedQr(event: QrLifecycleEvent): Boolean =
    event == QrLifecycleEvent.LoginSucceeded

/**
 * no-role 提示文案（V33：拆除服务器预选后只剩一种成因，纯函数便于 JVM 单测钉死）。
 *
 * 旧实现分三因组装文案（未绑任何角色 / 角色绑在别的服 / 混入非原神项），因为当时的判据是
 * "所选 region 没在绑定列表里命中"。V33 后角色发现改为自动决策：唯一角色直接登录、
 * 多角色列候选让用户选 —— 一屏都到不了"某个所选服没有角色"的状态，唯一可达的 NoRole 是
 * "账号没有可登录的国服原神角色"。因此文案统一引导去米游社绑定/检查，**不再提"切换服务器"**
 *（该入口已撤销，再给这个建议等于把用户指向一个不存在的按钮）。
 * 🔴 判据只有一个：AuthFinalizeResult.NoRole —— 本函数不读它的字段（数据完整性保留在数据层）。
 */
internal fun noRoleNotice(): String = LocaleStrings.getOrDefault(
    R.string.login_no_role_none_notice,
    "该米游社账号未绑定任何原神角色，请先在米游社「我的角色」中绑定后再试",
)

/**
 * 部分成功提示（V34 多选）：已保存 N 个账户 / M 个失败 / 首个失败原因，展示在角色选择
 * 对话框上（纯函数便于 JVM 单测钉死；候选此时只列失败项，用户直接补登）。
 */
internal fun partialFailNotice(savedCount: Int, failedCount: Int, firstReason: String): String =
    LocaleStrings.getOrDefault(
        R.string.login_choose_role_partial,
        "已保存 %1\$d 个账户，%2\$d 个失败：%3\$s",
        savedCount,
        failedCount,
        firstReason,
    )

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
    /** 服务器级提示（NoRole 提示绑定），跨状态保留直至下一次登录尝试落定 */
    val notice: String?

    data class Checking(override val notice: String? = null) : LoginUiState

    /**
     * 展示中的二维码。
     * 🔴 这里**不记**服务器：码是 passport 会话、与服务器无关（V29-B 结论）。服务器归属只在
     * finalize 拿到绑定角色列表后才有意义（V33：由 selectRoleForAuto 决策，Success.region 回填）。
     */
    data class Qr(
        val payload: ImageBitmap,
        val phase: QrPhase,
        override val notice: String? = null,
    ) : LoginUiState

    /** 已确认，正在凭据交换（自动角色发现也在这一步完成） */
    data class Finalizing(override val notice: String? = null) : LoginUiState

    /**
     * 该米游社账号绑定了**多个**可登录角色（V33）：展示候选让用户选择，不替用户猜。
     * 此时凭据未写入（AuthManager 保证零副作用）、码已确认失效；选定后走 [chooseRoles] 重试。
     */
    data class ChooseRole(
        val candidates: List<AuthFinalizeResult.BoundRole>,
        override val notice: String? = null,
    ) : LoginUiState

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

    /**
     * ChooseRole 待决期间暂存的扫码凭据：用户选定角色后以它携选中区服重试 finalize。
     * 🔴 只驻内存（选择/重来/离开即清），不进任何 UI 状态、不落盘、不入日志。
     */
    private var pendingCookies: List<String>? = null

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
     * V33 拆除的旧实现（保留机制结论，供后人不再走回头路）：登录页曾有一枚"服务器二选一"
     * 控件（selectServer），只改 finalize 的目标服务器、不换码。机制上它是自洽的 ——
     * 出码（[AuthManager.createQrLogin]）与轮询（[AuthManager.pollQrStatus]）打的是 passport 的
     * createQRLogin / queryQRLoginStatus，全程不读 ServerId；[QrSession.Created] 只携带
     * url / ticket / deviceId，都不是"按服务器建的会话"。
     * 但产品上它是**错误的形状**：真相（角色列表）在扫码确认后才拿到，登录前就让用户二选一，
     * 等于让人替系统猜 —— 渠道服用户必须先知"渠道服=世界树"才登得进去，猜错的代价是一次
     * "未绑定"恐慌（用户报障的原型）。正确做法即 V33 现状：选择推迟到 [finalize] 拿到角色列表后
     *（唯一角色自动、多角色 [LoginUiState.ChooseRole]、零角色提示绑定）。
     */

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
        // 重新来过的码作废旧凭据：ChooseRole 待决态随之作废（提示语保留与否由调用方决定）
        pendingCookies = null
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
        pendingCookies = null
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

    /**
     * 凭据交换（扫码首评路径）：服务器不预设，由 AuthManager 按真实角色列表自动决策
     * （唯一角色直接登录 / 多角色 [AuthFinalizeResult.SelectRole] / 零角色 NoRole）。
     * 用户选定多个角色的提交路径见 [chooseRoles]（直接调 AuthManager 的指定服务器入口）。
     * - Success：落地会话并把 App 当前服务器同步为**实际登录角色**的区服（result.region）；
     * - SelectRole：暂存凭据、切 [LoginUiState.ChooseRole] 等用户选择（码已失效，不再出码）；
     * - NoRole：凭据未写入（AuthManager 保证）、不切登录态，提示后继续 QR 流程（设计 §3.3）。
     */
    private suspend fun finalize(cookies: List<String>) {
        retireQrBitmap() // 确认后码已失效，展示使命结束，进入回收链
        _uiState.value = LoginUiState.Finalizing()
        try {
            val result = authManager.finalize(cookies)
            when (result) {
                is AuthFinalizeResult.Success -> {
                    // 🔴 归属服务器以实际登录角色的 region 为准（V33 前用的是登录页预选值）。
                    // ServerId.from 未命中（理论不可达：auto 路径已按注册表过滤）时保持现状。
                    ServerId.from(result.region)?.let { container.selectServer(it) }
                    container.refreshAccounts()
                    container.updateSession(result.gameUid)
                    _uiState.value = LoginUiState.LoggedIn(result.gameUid)
                    onQrLifecycleEvent(QrLifecycleEvent.LoginSucceeded)
                }
                is AuthFinalizeResult.SelectRole -> {
                    // 多个可登录角色：码已确认失效、凭据未写入，等用户选一个再继续
                    pendingCookies = cookies
                    _uiState.value = LoginUiState.ChooseRole(result.candidates)
                }
                is AuthFinalizeResult.NoRole -> {
                    onQrLifecycleEvent(QrLifecycleEvent.NoRole) // 登录未完成：不删已保存的码
                    _uiState.value = LoginUiState.Checking(notice = noRoleNotice())
                    startQr()
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            // 失败出口清掉暂存凭据：ChooseRole 不可能在 Failed 态存活（防御性清理）
            pendingCookies = null
            _uiState.value = LoginUiState.Failed(e.message ?: LocaleStrings.get(R.string.error_login_retry))
        }
    }

    /**
     * 用户在角色选择器里选定角色（V33 单选 → V34 **多选**）：携选中区服**逐个**完成凭据交换
     * 并落盘 —— 每个角色一次完整交换（e_hk4e_token 是 per-角色，官/渠道两服各持自己的 token），
     * CredentialStore 的账户级存储天然容纳多账户共存（V32 真机已验证官服+渠道服双账户并存）。
     * 提交顺序 = 候选列表顺序（UI 侧 selectedRolesInOrder 已排好），与勾选先后无关。
     *
     * 结果三分支（见 [finalizeMulti]）：
     * - 全成功 → 激活**第一个**成功角色（列表序，稳定可预期），进 [LoginUiState.LoggedIn]；
     * - 全失败 → [LoginUiState.Failed]（消息取首个失败原因，沿用单角色失败语义）；
     * - 部分成功 → 保留暂存凭据、回 [LoginUiState.ChooseRole]（候选只列失败项 + notice）——
     *   失败角色可直接补登，**不需要重新扫码**（码已确认失效，重扫成本高）。
     *
     * 幂等保护：无暂存凭据（选择器已退出/重来过）、无候选（状态被顶掉）或已有 finalize
     * 在跑时直接忽略。
     */
    fun chooseRoles(roles: List<AuthFinalizeResult.BoundRole>) {
        val cookies = pendingCookies
        val candidates = (_uiState.value as? LoginUiState.ChooseRole)?.candidates
        if (cookies == null || candidates == null || roles.isEmpty() || finalizeJob?.isActive == true) {
            return
        }
        finalizeJob = viewModelScope.launch {
            try {
                finalizeMulti(cookies, roles)
            } finally {
                finalizeJob = null
            }
        }
    }

    /**
     * 多角色批量凭据交换（V34）：对每个选中角色独立走 [AuthManager.finalize]（指定服务器入口）
     * —— 单个角色失败不中断其余（网络/风控类失败的典型形态，逐项收集结果）。
     * 每个 Success 在 AuthManager 内部即已落盘（各自账户槽位），因此部分失败时已保存部分不受影响。
     */
    private suspend fun finalizeMulti(
        cookies: List<String>,
        roles: List<AuthFinalizeResult.BoundRole>,
    ) {
        retireQrBitmap() // 确认后码已失效，展示使命结束，进入回收链
        _uiState.value = LoginUiState.Finalizing()
        val succeeded = mutableListOf<AuthFinalizeResult.Success>()
        val failures = mutableListOf<Pair<AuthFinalizeResult.BoundRole, String>>()
        for (role in roles) {
            val server = ServerId.from(role.region) ?: continue // 理论不可达：候选来自注册表过滤后的列表
            try {
                when (val result = authManager.finalize(cookies, server)) {
                    is AuthFinalizeResult.Success -> succeeded += result
                    // 指定服务器入口下两者理论不可达（候选即来自该账号的绑定角色）；
                    // 真发生则按"该角色本次登录失败"记录原因，不中断其余角色。
                    is AuthFinalizeResult.NoRole -> failures += role to noRoleNotice()
                    is AuthFinalizeResult.SelectRole -> failures += role to noRoleNotice()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                failures += role to (e.message ?: LocaleStrings.get(R.string.error_login_retry))
            }
        }
        when {
            succeeded.isEmpty() -> {
                pendingCookies = null
                _uiState.value = LoginUiState.Failed(
                    failures.firstOrNull()?.second ?: LocaleStrings.get(R.string.error_login_retry),
                )
            }
            failures.isEmpty() -> {
                pendingCookies = null
                val first = succeeded.first()
                // 激活账户 = 第一个成功角色（候选列表顺序，勾选先后不参与决策）。
                // activateAccount 内同步 setActiveUid + updateSession + selectServer + refreshAccounts；
                // 循环中每次 save 都会把激活翻到当时保存的账户，这里必须显式收回第一个。
                if (!container.activateAccount(first.gameUid)) {
                    // 理论不可达（凭据刚由本流程落盘）；兜底按单角色路径的同步口径收尾
                    ServerId.from(first.region)?.let { container.selectServer(it) }
                    container.refreshAccounts()
                    container.updateSession(first.gameUid)
                }
                _uiState.value = LoginUiState.LoggedIn(first.gameUid)
                onQrLifecycleEvent(QrLifecycleEvent.LoginSucceeded)
            }
            else -> {
                // 部分成功：保留凭据回选择器（候选只列失败项，notice 报已保存数与首个原因），
                // 用户直接补登失败角色，无需重新扫码。
                pendingCookies = cookies
                _uiState.value = LoginUiState.ChooseRole(
                    candidates = failures.map { it.first },
                    notice = partialFailNotice(
                        savedCount = succeeded.size,
                        failedCount = failures.size,
                        firstReason = failures.first().second,
                    ),
                )
            }
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
