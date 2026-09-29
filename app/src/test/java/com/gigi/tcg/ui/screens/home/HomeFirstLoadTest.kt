// ①a/①c 覆盖：首刷时序（profile 落定后错峰 staggerMs 再 records）。
// V36 变更：fetchWithSilentRetry（首刷 RETRYABLE 静默重试一次）已删除——重试统一由
// MihoyoClient 指数退避负责（设计红线 2），页面层再叠一层会把最坏等待翻倍。
// 原「静默重试」的 5 个用例随之移除，改为源码回归锁防止被重新加回来。
// HomeViewModel 本体依赖 Android Application，这里测其提取出的纯函数 helper + 源码文本断言。

package com.gigi.tcg.ui.screens.home

import java.io.File
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFirstLoadTest {
    @Test
    fun `first load issues records only after profile completed plus stagger`() = runTest {
        val events = mutableListOf<String>()
        val start = currentTime
        runStaggeredFirstLoad(
            staggerMs = 400,
            loadProfile = {
                events += "profile@${currentTime - start}"
            },
            loadRecords = {
                events += "records@${currentTime - start}"
            },
        )
        // 顺序：profile 先完成，records 在其后 400ms 错峰点发出
        assertEquals(listOf("profile@0", "records@400"), events)
        assertEquals(400, currentTime.toInt())
    }

    @Test
    fun `zero stagger still serializes profile before records`() = runTest {
        val events = mutableListOf<String>()
        runStaggeredFirstLoad(
            staggerMs = 0,
            loadProfile = { events += "profile" },
            loadRecords = { events += "records" },
        )
        assertEquals(listOf("profile", "records"), events)
        assertEquals(0, currentTime.toInt())
    }

    /** V36/3 任务 F：页面层静默重试必须删干净（与 MihoyoClient 退避重试叠加 = 最坏等待翻倍） */
    @Test
    fun `page-level silent retry is gone, retry belongs to MihoyoClient`() {
        val src = File("src/main/java/com/gigi/tcg/ui/screens/home/HomeViewModel.kt").readText()
        // 按调用形态断言（注释里允许提及这个名字说明历史，代码里不得再出现）
        assertFalse("fetchWithSilentRetry 不得再存在", src.contains("fetchWithSilentRetry("))
        assertFalse("页面层不得再自行判定 RETRYABLE retcode（红线 2）", src.contains("RETRYABLE_RETCODES"))
    }

    /** V36/3 任务 G：下拉圈由在途计数把关，静默刷新时慢块未落定不收圈 */
    @Test
    fun `refresh spinner end is gated by in-flight count, not only state`() {
        val src = File("src/main/java/com/gigi/tcg/ui/screens/home/HomeViewModel.kt").readText()
        assertTrue("loadProfile/loadRecords 应各自进出在途计数（+=1 / -=1 各两处）",
            Regex("pendingLoads \\+= 1").findAll(src).count() == 2 &&
                Regex("pendingLoads -= 1").findAll(src).count() == 2)
        assertTrue("maybeEndRefreshing 必须先看 pendingLoads == 0", src.contains("pendingLoads == 0"))
    }
}
