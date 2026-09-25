// V7E 回归锁：叠加层/Login 分支退出组合时必须重置 LoginViewModel。
// 历史缺陷：viewModel(key="login-add-account") 挂 Activity 级 ViewModelStore、跨组合进出持久，
// 退出叠加层后状态残留 LoggedIn ⇒ 第二次"添加账号"首次组合的 LaunchedEffect(loginState)
// 立即消费残留值 → onLoggedIn → 叠加层同帧被摘除（"闪一下回主页"）。
// 修复：DisposableEffect(loginViewModel) { onDispose { loginViewModel.cancel() } }。
// 本测试对真实源码做文本断言，防止后人删掉该 DisposableEffect 复发缺陷。
package com.gigi.tcg.ui.login

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGateOverlayResetTest {

    private val src: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/login/AppGate.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        file.readText()
    }

    /** 不变量 1：存在 DisposableEffect，且其 onDispose 块内调用 .cancel()（两处各一） */
    @Test
    fun disposableEffectResetsViewModelOnDispose() {
        val effectCount = Regex("DisposableEffect\\(").findAll(src).count()
        assertTrue("应存在 DisposableEffect( 重置 VM", effectCount >= 1)
        val disposeCancelCount = Regex("onDispose\\s*\\{[^}]*\\.cancel\\(\\)").findAll(src).count()
        assertTrue(
            "每处 DisposableEffect 的 onDispose 都必须调用 loginViewModel.cancel()，" +
                "实际=$disposeCancelCount（期望=$effectCount）",
            disposeCancelCount == effectCount,
        )
    }

    /** 不变量 2：Main 叠加层 + Login 分支各一份，共 2 处 */
    @Test
    fun bothBranchesResetTheViewModel() {
        val count = Regex("DisposableEffect\\(").findAll(src).count()
        assertTrue("应有 2 处 DisposableEffect（Main 叠加层 + Login 分支对称防御），实际=$count", count == 2)
    }

    /** 不变量 3：Main 分支的 DisposableEffect 落在 `is GateUiState.Main ->` 到 `is GateUiState.Login ->` 区间内 */
    @Test
    fun mainBranchEffectIsInsideMainSection() {
        val mainPos = src.indexOf("is GateUiState.Main ->")
        val loginPos = src.indexOf("is GateUiState.Login ->")
        assertTrue("应存在 Main 分支", mainPos >= 0)
        assertTrue("Login 分支应位于 Main 分支之后", loginPos > mainPos)
        val mainSection = src.substring(mainPos, loginPos)
        val effectPos = mainSection.indexOf("DisposableEffect(")
        assertTrue("DisposableEffect 必须位于 Main 分支文本区间内（叠加层里）", effectPos >= 0)
        assertTrue(
            "Main 区间内 DisposableEffect 的 onDispose 必须调用 cancel()",
            Regex("DisposableEffect\\s*\\([^)]*\\)\\s*\\{\\s*onDispose\\s*\\{[^}]*\\.cancel\\(\\)").containsMatchIn(mainSection),
        )
        val overlayPos = mainSection.indexOf("if (current.addAccount)")
        assertTrue("叠加层条件块应存在", overlayPos >= 0)
        assertTrue(
            "DisposableEffect 应位于叠加层 if (current.addAccount) 块内",
            effectPos > overlayPos,
        )
    }
}
