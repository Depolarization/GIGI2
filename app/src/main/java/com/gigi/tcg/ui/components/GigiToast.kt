package com.gigi.tcg.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.channels.Channel

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
)

/**
 * 队列式轻提示控制器：任意位置 `show(text)` 入队，
 * 由 [ToastHost] 的 LaunchedEffect 逐条消费显示。
 */
class ToastController {
    private val channel = Channel<ToastMessage>(Channel.UNLIMITED)
    private var nextId = 0L

    fun show(text: String) {
        nextId += 1
        channel.trySend(ToastMessage(id = nextId, text = text))
    }

    /**
     * 带 action 的提示（如「已保存到相册」+「查看」）。
     * actionLabel 为 null 时退化成纯文本，与 [show] 等价。
     */
    fun showWithAction(text: String, actionLabel: String, onAction: suspend () -> Unit) {
        nextId += 1
        channel.trySend(
            ToastMessage(id = nextId, text = text, actionLabel = actionLabel, onAction = onAction),
        )
    }

    internal suspend fun receive(): ToastMessage = channel.receive()
}

/** 页面内取用 `show(text)` 的局部入口，默认 no-op，由 ToastHost 宿主侧 provide */
val LocalToast = compositionLocalOf<(String) -> Unit> { {} }

/**
 * 页面内取用「带 action 的提示」的局部入口，默认 no-op。
 * 与 [LocalToast] 并存而不是把 LocalToast 改成双参：现有全部调用点（几十处）
 * 都是 `LocalToast.current(文案)` 的单参形态，改签名要全量迁移；新增一个同族
 * CompositionLocal 成本更低、且不会让既有调用点误传 action。
 *
 * defaultFactory 写成具名常量而不是内联 `{ _, _, _ -> }`：
 * 内联 lambda 的三个参数需要从外层函数类型反推，而 `suspend () -> Unit`
 * 作为嵌套函数类型在这种上下文里没有推断锚点（Kotlin 2.1 会报
 * "Cannot infer type for this parameter"），具名常量则直接给出精确类型。
 */
private val EmptyToastAction: (String, String, suspend () -> Unit) -> Unit = { _, _, _ -> }

val LocalToastAction: ProvidableCompositionLocal<(String, String, suspend () -> Unit) -> Unit> =
    compositionLocalOf { EmptyToastAction }

/** 渲染当前一条 Snackbar，并在协程内持续消费 controller 队列 */
@Composable
fun ToastHost(controller: ToastController, modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(controller) {
        while (true) {
            val toast = controller.receive()
            val result = hostState.showSnackbar(
                message = toast.text,
                actionLabel = toast.actionLabel,
                // 有 action 时时长给 Long：Short(4s) 在用户读文案+伸手点之间就消失了。
                // 无 action 时保持 Short，既有纯文本提示的节奏不变。
                duration = if (toast.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            // ActionPerformed 是唯一该跑回调的返回值；Dismissed/超时都视为用户放弃。
            if (result == SnackbarResult.ActionPerformed) {
                toast.onAction?.invoke()
            }
        }
    }
    SnackbarHost(hostState = hostState, modifier = modifier)
}

/**
 * 用系统相册应用打开 [uri] 指向的那张图片。
 *
 * 走 `ACTION_VIEW` + MediaStore 的 content uri（**不**需要 FileProvider）：
 * `CardImageSaver` 在 Q+ 上落盘时就是经 MediaStore insert 建的条目、
 * 直接持有其 uri，交给系统相册即可定位并高亮该文件。
 *
 * 设备上没有能处理该 action 的相册应用时静默失败（不崩、不弹异常）——
 * 提示已经显示过「已保存」，用户仍可自己去相册找。
 */
fun openInGallery(context: Context, uri: Uri?) {
    if (uri == null) return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // 无可用相册应用：静默降级
    }
}
