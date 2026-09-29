// 任务 A（V36 闪退）：导出后点 Snackbar「查看」崩溃的回归锁。
// 根因：openInGallery 曾用非 Activity Context 启动 Activity 且不带 FLAG_ACTIVITY_NEW_TASK，
// 抛的是 AndroidRuntimeException（不是 ActivityNotFoundException），窄 catch 抓不到。
// 工程无 Robolectric，构造真实 Intent/Uri 在纯 JVM 走不通（android.jar 方法全部 not mocked），
// 故这里两条腿：① 对可内联的 Intent.FLAG_* 常量做真断言（GALLERY_VIEW_INTENT_FLAGS 是编译期内联值）；
// ② 对源码文本做断言，锁死「startActivity 必须被 runCatching 兜住」的形态，防止回退成窄 try/catch。
package com.gigi.tcg.ui.components

import android.content.Intent
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenInGalleryTest {

    private val src: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/components/GigiToast.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        file.readText()
    }

    @Test
    fun `gallery view intent carries new-task and read-permission flags`() {
        // 纯 JVM 可执行：Intent.FLAG_* 是 static final int，Kotlin 编译期即内联进常量
        assertTrue(
            "必须带 FLAG_ACTIVITY_NEW_TASK（非 Activity Context 启动 Activity 的硬要求）",
            Intent.FLAG_ACTIVITY_NEW_TASK and GALLERY_VIEW_INTENT_FLAGS != 0,
        )
        assertTrue(
            "必须保留 FLAG_GRANT_READ_URI_PERMISSION（content uri 授权给相册应用）",
            Intent.FLAG_GRANT_READ_URI_PERMISSION and GALLERY_VIEW_INTENT_FLAGS != 0,
        )
    }

    @Test
    fun `openInGallery builds intent from the flag constant and wraps startActivity in runCatching`() {
        val body = src.substringAfter("fun openInGallery(")
        assertTrue("intent 应经 addFlags(GALLERY_VIEW_INTENT_FLAGS) 挂上新任务标志", body.contains("addFlags(GALLERY_VIEW_INTENT_FLAGS)"))
        assertTrue("startActivity 必须被 runCatching 兜住（AndroidRuntimeException/SecurityException 都要吞）", body.contains("runCatching { context.startActivity(intent) }"))
        assertTrue("不得回退成只抓 ActivityNotFoundException 的窄 catch", !body.contains("catch (e: ActivityNotFoundException)"))
    }

    @Test
    fun `toastHost guards action callback`() {
        val host = src.substringAfter("fun ToastHost(").substringBefore("SnackbarHost(")
        assertTrue("onAction 调用必须包在 runCatching 里（action 抛错不得杀死 Snackbar 消费协程）", host.contains("runCatching { toast.onAction?.invoke() }"))
    }
}
