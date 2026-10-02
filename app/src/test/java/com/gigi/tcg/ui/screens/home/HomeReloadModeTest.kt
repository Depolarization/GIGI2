// 首页刷新模式回归断言。
// 选型：不做 `isSilentReload(b) = b` 恒真纯函数，改为对真实源码做文本断言——
// 直接钉死 loadProfile/loadRecords 的 silent 判定里不再出现 force、
// 下拉刷新指示器只由用户主动刷新驱动且有统一清零（缺陷 A/B 的根因位置）。
package com.gigi.tcg.ui.screens.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeReloadModeTest {

    private fun readSource(name: String): String {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/home/$name")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        return file.readText()
    }

    /** 缺陷 B：loadProfile/loadRecords 两处 silent 判定 = "有内容 ⇒ 静默"，与 force 无关 */
    @Test
    fun silentDecision_keepsContentRegardlessOfForce() {
        val silentLines = readSource("HomeViewModel.kt")
            .lines()
            .filter { it.trim().startsWith("val silent =") }
        assertTrue(
            "应恰有两处 silent 判定（loadProfile/loadRecords），实际=${silentLines.size}",
            silentLines.size == 2,
        )
        silentLines.forEach { line ->
            assertTrue("silent 应基于已有内容 Async.Content: $line", line.contains("Async.Content"))
            assertFalse(
                "silent 判定不得依赖 force（下拉刷新须保留已有内容）: $line",
                line.contains("force"),
            )
        }
    }

    /** 缺陷 B 的另一半："有内容才保留"——非 Content（首载 / Error 重试）仍回落 Loading 转圈 */
    @Test
    fun nonContentStillFallsBackToLoading() {
        val gated = readSource("HomeViewModel.kt")
            .lines()
            .windowed(2)
            .count { (a, b) ->
                a.trim().startsWith("val silent =") && b.trim().startsWith("if (!silent)")
            }
        assertTrue(
            "两处 silent 判定后应紧跟 if (!silent) → Async.Loading 闸门，实际=$gated",
            gated == 2,
        )
    }

    /** 缺陷 A：_refreshing 置位只发生在 refresh() 且必在 uid 提前返回之后（置位必有人清零） */
    @Test
    fun refreshingFlag_setOnlyAfterUidGuardInRefresh() {
        val refreshBody = readSource("HomeViewModel.kt")
            .substringAfter("fun refresh() {")
            .substringBefore("\n    }")
        val uidReturn = refreshBody.indexOf("?: return")
        val flagSet = refreshBody.indexOf("_refreshing.value = true")
        assertTrue("refresh() 应保留 uid 提前返回", uidReturn >= 0)
        assertTrue(
            "_refreshing 置位必须在 uid 提前返回之后：未登录的 return 不得置位",
            flagSet > uidReturn,
        )
    }

    /** 缺陷 A：两块都落定后有统一清零（maybeEndRefreshing 恰两处调用） */
    @Test
    fun refreshingFlag_clearedWhenBothSettled() {
        val src = readSource("HomeViewModel.kt")
        assertTrue("应定义 maybeEndRefreshing()", src.contains("private fun maybeEndRefreshing()"))
        val callCount = Regex("(?<!fun )maybeEndRefreshing\\(\\)").findAll(src).count()
        assertTrue("应在 profile / records 两处落定后各调用一次，实际=$callCount", callCount == 2)
        assertTrue(
            "清零条件应为两块都 !Async.Loading",
            src.contains("_uiState.value.profile !is Async.Loading") &&
                src.contains("_uiState.value.records !is Async.Loading"),
        )
    }

    /** 缺陷 A：HomeRoute 的 isRefreshing 取自 VM.refreshing，不再从 Async.Loading 派生（双圈根因） */
    @Test
    fun route_isRefreshingComesFromViewModelFlag() {
        val src = readSource("HomeRoute.kt")
        assertTrue(
            "isRefreshing 应 collect VM 的 refreshing",
            src.contains("viewModel.refreshing.collectAsStateWithLifecycle()"),
        )
        assertFalse(
            "不得再由 Async.Loading 派生 isRefreshing（否则冷启动顶部圈 + 居中圈两个同转）",
            Regex("isRefreshing\\s*=\\s*[^\\n]*Async\\.Loading").containsMatchIn(src),
        )
    }

    /**
     * 缺陷 C：昵称行必须配 ellipsis，禁止硬裁切。
     * V41 起信息头**纵向分列**：昵称 `maxLines = 3`、段位 `maxLines = 1`，**两段都要 ellipsis**
     * （段位名 + 至多 5 颗星虽短，但三语/extremes 下仍不写死"不可能折行"）。
     * 🔴 断言口径跟着结构变：不能沿用"整行只有一处 maxLines"的旧前提 ——
     * 现在有两处，必须**分段定位**（昵称块到 `if (tier.isNotEmpty())`、段位块到 `Spacer`），
     * 否则会拿段位的 `maxLines = 1` 当成昵称的，判挂也判错对象。
     */
    @Test
    fun profileNickname_usesEllipsis() {
        val stats = readSource("../cardstats/CardStatsRoute.kt")
        assertTrue("PlayerInfoHeader 应引入 TextOverflow", stats.contains("import androidx.compose.ui.text.style.TextOverflow"))
        val header = stats.substringAfter("internal fun PlayerInfoHeader(")
        val nickBlock = header.substringAfter("text = nickname,").substringBefore("if (tier.isNotEmpty())")
        assertTrue(
            "昵称行 Text 应带 overflow = TextOverflow.Ellipsis",
            nickBlock.contains("overflow = TextOverflow.Ellipsis"),
        )
        assertTrue("昵称行应允许多行（maxLines = 3）", nickBlock.contains("maxLines = 3"))
        val tierBlock = header.substringAfter("if (tier.isNotEmpty())").substringBefore("Spacer")
        assertTrue("段位行同样要 ellipsis", tierBlock.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue("段位行 maxLines = 1", tierBlock.contains("maxLines = 1"))
    }
}
