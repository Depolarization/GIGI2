package com.gigi.tcg.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.channels.Channel

/** 一条轻提示（对齐 toast.tsx ToastItem） */
data class ToastMessage(val id: Long, val text: String)

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

    internal suspend fun receive(): ToastMessage = channel.receive()
}

/** 页面内取用 `show(text)` 的局部入口，默认 no-op，由 ToastHost 宿主侧 provide */
val LocalToast = compositionLocalOf<(String) -> Unit> { {} }

/** 渲染当前一条 Snackbar，并在协程内持续消费 controller 队列 */
@Composable
fun ToastHost(controller: ToastController, modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(controller) {
        while (true) {
            val toast = controller.receive()
            hostState.showSnackbar(toast.text, duration = SnackbarDuration.Short)
        }
    }
    SnackbarHost(hostState = hostState, modifier = modifier)
}
