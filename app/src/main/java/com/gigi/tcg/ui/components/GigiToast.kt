package com.gigi.tcg.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一条轻提示（对齐 toast.tsx ToastItem）
 *
 * [actionLabel] / [onAction] 是可选的 action 区（Snackbar 的 action slot）：
 * 为 null 时就是一条纯文本提示，行为与旧版完全一致（无 action、不响应点击）。
 * 「保存到相册」类提示用它挂「查看」按钮，点开系统相册定位该文件。
 */
data class ToastMessage(
    val id: Long,
    val text: String,
    val actionLabel: String? = null,
    /** 仅当 [actionLabel] 非 null 时被调用；用 suspend 以便内部做 Intent 兜底等 IO */
    val onAction: (suspend () -> Unit)? = null,
    /** 展示时长；默认 Short，带「查看」的保存提示按需显式传 Long */
    val duration: SnackbarDuration = SnackbarDuration.Short,
)

/**
 * 轻提示控制器：任意位置 `show(text)` 写入最新一条，
 * 由 [ToastHost] 消费。[ToastMessage.id] 每次自增 ⇒ 同文案也能触发新的一次展示。
 *
 * 🔴 这里用 StateFlow 而不是 Channel 队列（V39-H）：新提示必须**立即顶掉**正在显示的那条。
 * 用队列时「复制分享码（带分享 action）」的 Snackbar 会占住 4s，
 * 用户紧接着点「保存卡组图」的提示只能在队列里排队 ⇒ 看起来像没响应。
 */
class ToastController {
    private var nextId = 0L
    private val _message = MutableStateFlow<ToastMessage?>(null)
    internal val message: StateFlow<ToastMessage?> = _message.asStateFlow()

    fun show(text: String) {
        nextId += 1
        _message.value = ToastMessage(id = nextId, text = text)
    }

    /**
     * 带 action 的提示（如「已保存到相册」+「查看」）。
     * actionLabel 为 null 时退化成纯文本，与 [show] 等价。
     */
    fun showWithAction(
        text: String,
        actionLabel: String,
        duration: SnackbarDuration = SnackbarDuration.Long,
        onAction: suspend () -> Unit,
    ) {
        nextId += 1
        _message.value = ToastMessage(
            id = nextId,
            text = text,
            actionLabel = actionLabel,
            onAction = onAction,
            duration = duration,
        )
    }
}

/** 页面内取用 `show(text)` 的局部入口，默认 no-op，由 ToastHost 宿主侧 provide */
val LocalToast = compositionLocalOf<(String) -> Unit> { {} }

/**
 * 页面内取用「带 action 的提示」的局部入口，默认 no-op。
 * 签名含 [SnackbarDuration]：保存类提示按需给 Long（够读文案 + 伸手点），
 * 卡组分享这类「快速连续操作」的提示给 Short，避免挡住下一条。
 *
 * defaultFactory 写成具名常量而不是内联 `{ _, _, _, _ -> }`：
 * 内联 lambda 的参数需要从外层函数类型反推，而 `suspend () -> Unit`
 * 作为嵌套函数类型在这种上下文里没有推断锚点（Kotlin 2.1 会报
 * "Cannot infer type for this parameter"），具名常量则直接给出精确类型。
 */
private val EmptyToastAction: (String, String, SnackbarDuration, suspend () -> Unit) -> Unit =
    { _, _, _, _ -> }

val LocalToastAction: ProvidableCompositionLocal<(String, String, SnackbarDuration, suspend () -> Unit) -> Unit> =
    compositionLocalOf { EmptyToastAction }

/**
 * 渲染当前一条 Snackbar，并在协程内持续消费 controller 的最新消息。
 *
 * 新消息到达 ⇒ [LaunchedEffect] 的 key 变化 ⇒ 上一轮 `showSnackbar` 被取消（旧条立即收起），
 * 立即展示新条。这是「复制分享码后快速点保存，Snackbar 要及时更新」的实现口径。
 *
 * Snackbar 的容器/正文/action 三色在此处**显式**指定，不用 M3 默认：默认值走
 * `inverseSurface` 一族槽位，V37 真机夜间被 MIUI 动态取色解析成浅底浅字（≈1.0:1，完全看不见）。
 * 现在主题侧（Color.kt）已固定色板、这两侧同相（深底浅字），这里再显式传色 + 单测锁死，
 * 避免「默认回落」成为下一个可读性回归的入口。
 */
@Composable
fun ToastHost(controller: ToastController, modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }
    val toast by controller.message.collectAsState()
    LaunchedEffect(toast) {
        val current = toast ?: return@LaunchedEffect
        val result = hostState.showSnackbar(
            message = current.text,
            actionLabel = current.actionLabel,
            duration = current.duration,
        )
        // ActionPerformed 是唯一该跑回调的返回值；Dismissed/超时都视为用户放弃。
        // runCatching：action 由调用方注入（如 openInGallery 拉起系统应用），
        // 它抛任何异常都不该掀翻 ToastHost 所在的 LaunchedEffect——协程一死，
        // 后续 Snackbar 就再也不消费了（V36 闪退修复）。
        if (result == SnackbarResult.ActionPerformed) {
            runCatching { current.onAction?.invoke() }
        }
    }
    // V37-B：snackbar 配色显式传，不吃 M3 默认回落。
    // M3 默认 Snackbar 的三件套是 inverseSurface（容器）/ inverseOnSurface（正文）/
    // inversePrimary（action 文字），而夜间「完全看不见」正是落在这三个槽上：
    // V37 真机实测 MIUI 动态取色给出浅底 #E6E0E9 + 浅字，对比度 ≈1.0:1。
    // 固定色板（Color.kt）已把三槽钉成「两套主题都是深底浅字」（浅色 #322F35 / 深色 #2B2930），
    // 这里再按槽显式传一遍：即使将来有人重调 scheme 或 M3 改了默认取槽口径，也不会悄悄退回不可读。
    // 实测对比度（ThemeContrastTest 钉死）：正文 11.65（浅）/ 11.08（深），
    // 「查看」按钮 inversePrimary #D0BCFF 对两种容器 7.73 / 8.42 —— action 单独一档，别漏。
    val colorScheme = MaterialTheme.colorScheme
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = colorScheme.inverseSurface,
            contentColor = colorScheme.inverseOnSurface,
            actionContentColor = colorScheme.inversePrimary,
            dismissActionContentColor = colorScheme.inverseOnSurface,
        )
    }
}

/**
 * 用系统相册应用打开 [uri] 指向的那张图片。
 *
 * 走 `ACTION_VIEW` + MediaStore 的 content uri（**不**需要 FileProvider）：
 * `CardImageSaver` 在 Q+ 上落盘时就是经 MediaStore insert 建的条目、
 * 直接持有其 uri，交给系统相册即可定位并高亮该文件。
 *
 * 设备上没有能处理该 action 的相册应用、或 URI 无读权限时静默失败（不崩、不弹异常）——
 * 提示已经显示过「已保存」，用户仍可自己去相册找。
 */
// Intent.FLAG_* 是编译期内联常量，JVM 单测可直接断言（构造 Intent 则需要 android.jar，测试不走这条路）
internal const val GALLERY_VIEW_INTENT_FLAGS: Int =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK

fun openInGallery(context: Context, uri: Uri?) {
    if (uri == null) return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        // 🔴 NEW_TASK 必加：调用方可能传 Application context（GigiNavHost 曾经就是），
        // 非 Activity Context 启动 Activity 缺它必抛 AndroidRuntimeException——
        // 它**不是** ActivityNotFoundException，原先的窄 catch 抓不到 ⇒ 直接闪退（V36 修复）。
        addFlags(GALLERY_VIEW_INTENT_FLAGS)
    }
    // runCatching 覆盖 ActivityNotFoundException / SecurityException / AndroidRuntimeException，
    // 任何一条都不该让 Snackbar「查看」按钮变成崩溃点。
    runCatching { context.startActivity(intent) }
}
