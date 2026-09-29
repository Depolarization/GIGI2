// V36/2 任务 B/J/K：「我的」页装载语义。
// MyViewModel 本体要 Android Application，JVM 侧测它抽出来的纯件（同 HomeFirstLoadTest 的做法）：
// - [AccountScopeGuard]：账号切换才清空、页面重入不清空（用户第 12 项闪动的根因是"先清空再回填"，
//   force 全 false、零网络请求、5min 内存缓存秒回 —— 坏的不是缓存失效）；
// - [runStaggeredSteps]：5 组摘要串行错峰，不再一次性并发 5 个私有接口（-500004 保流窗口）；
// - 源码回归锁：防止无条件 `target.value = null` 被改回来。
package com.gigi.tcg.ui.screens.my

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyViewModelLoadTest {

    @Test
    fun `first load counts as a change so stale slots get cleared`() {
        val guard = AccountScopeGuard()
        assertTrue(guard.onLoadedFrom("123456"))
        assertEquals("123456", guard.currentUid)
    }

    @Test
    fun `re-entering the page with the same uid does not clear`() {
        val guard = AccountScopeGuard()
        assertTrue(guard.onLoadedFrom("123456"))
        // 离开 Composition 再回来：LaunchedEffect 重启，uid 没变 ⇒ 不许清空（清空就是闪）
        assertFalse(guard.onLoadedFrom("123456"))
        assertFalse(guard.onLoadedFrom("123456"))
    }

    @Test
    fun `switching account clears and re-switching back clears again`() {
        val guard = AccountScopeGuard()
        guard.onLoadedFrom("123456")
        assertTrue(guard.onLoadedFrom("654321"))
        assertTrue(guard.onLoadedFrom("123456"))
        assertFalse(guard.onLoadedFrom("123456"))
    }

    @Test
    fun `logout clears once then stays cleared`() {
        val guard = AccountScopeGuard()
        guard.onLoadedFrom("123456")
        assertTrue("登出（uid 置 null）必须作废上一账户的数据", guard.onLoadedFrom(null))
        assertFalse(guard.onLoadedFrom(null))
    }

    @Test
    fun `summary loads are serial and staggered`() = runTest {
        val events = mutableListOf<String>()
        val start = currentTime
        val steps: List<suspend () -> Unit> = listOf(
            { events += "profile@${currentTime - start}" },
            { events += "decks@${currentTime - start}" },
            { events += "cardbacks@${currentTime - start}" },
        )
        runStaggeredSteps(400L, steps)
        assertEquals(listOf("profile@0", "decks@400", "cardbacks@800"), events)
        assertEquals(800, currentTime.toInt())
    }

    @Test
    fun `a slow step blocks the next one instead of running concurrently`() = runTest {
        val events = mutableListOf<String>()
        val steps: List<suspend () -> Unit> = listOf(
            { delay(500); events += "slow" },
            { events += "next" },
        )
        runStaggeredSteps(100L, steps)
        assertEquals(listOf("slow", "next"), events)
        // 错峰只算在慢步骤**完成之后**，不是从起点算
        assertEquals(600, currentTime.toInt())
    }

    /** 源码回归锁：无条件清空（闪动根因）不得改回来 */
    @Test
    fun `load no longer clears unconditionally`() {
        val src = File("src/main/java/com/gigi/tcg/ui/screens/my/MyViewModel.kt").readText()
        val loadBody = src.substringAfter("private fun <T : Any> load(").substringBefore("fun loadProfile")
        assertFalse("load() 里不得出现裸的 target.value = null", loadBody.contains("target.value = null"))
        assertTrue("清空必须走账户守卫", loadBody.contains("accountGuard.onLoadedFrom(uid)) clearAccountScoped()"))
    }

    /** 源码回归锁：一级页不得再并发打 5 个接口 */
    @Test
    fun `my page loads through the staggered entry point`() {
        val src = File("src/main/java/com/gigi/tcg/ui/screens/my/MyRoute.kt").readText()
        assertTrue("进页只调 loadAllStaggered()", src.contains("viewModel.loadAllStaggered()"))
        assertFalse("不得再逐组并发 load", Regex("viewModel\\.load(DeckList|CardBackList|MatchList|ChallengeSchedule)\\(\\)").containsMatchIn(src))
    }
}
