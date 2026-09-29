// V36/1b 任务 B：AppContainer.refreshAccounts() 的冷启动崩溃路径（V36-0 审计 P1）。
// 真 AppContainer 构造不出来（CredentialStore 要 Context、无 Robolectric/mockk，见
// SessionRefreshWiringTest 的同款说明），故把决策抽成顶层 internal 纯函数 [planAccountRefresh]，
// 这里用真实 StoredAccount/ServerId 直接断言；末尾再加一条源码文本闸门，防 `first { }` 回流。

package com.gigi.tcg.di

import com.gigi.tcg.data.ServerId
import com.gigi.tcg.data.auth.StoredAccount
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContainerTest {

    private fun account(uid: String, serverId: String = ServerId.DEFAULT.id) =
        StoredAccount(uid = uid, nickname = uid, serverId = serverId)

    private val twoAccounts = listOf(
        account("u-official", ServerId.Official.id),
        account("u-channel", ServerId.Channel.id),
    )

    // ---- 崩溃路径：落盘 activeUid 与账户表错配 ----

    @Test
    fun `旧实现同一输入确实抛 NoSuchElementException（崩溃路径复现）`() {
        // 修前 AppContainer:102 就是这个表达式；refreshAccounts() 由 init 调用 ⇒ 冷启动即崩
        val crash = runCatching { twoAccounts.first { it.uid == "ghost" }.server() }
        assertTrue(
            "崩溃路径没复现出来（说明用例输入不成立）：$crash",
            crash.exceptionOrNull() is NoSuchElementException,
        )
        // 修后同输入走纯函数兜底：不外抛，服务器落 DEFAULT
        val plan = planAccountRefresh(twoAccounts, storedActiveUid = "ghost")
        assertEquals(ServerId.DEFAULT, plan.server)
    }

    @Test
    fun `脏 activeUid 不抛异常且 currentServer 兜到 DEFAULT`() {
        val plan = planAccountRefresh(twoAccounts, storedActiveUid = "uid-被部分清除的凭据")
        assertNull(plan.activeUid)
        assertEquals(ServerId.DEFAULT, plan.server)
        assertNull("脏值不能被回写盘", plan.uidToPersist)
    }

    @Test
    fun `脏 activeUid 复位为空而非悄悄复用首个账户`() {
        val plan = planAccountRefresh(twoAccounts, storedActiveUid = "ghost")
        assertTrue(
            "首个账户凭据未必有效，不能替用户切号",
            plan.activeUid != twoAccounts.first().uid,
        )
    }

    @Test
    fun `账户表为空但残留 activeUid 时回到未登录态`() {
        val plan = planAccountRefresh(emptyList(), storedActiveUid = "ghost")
        assertNull(plan.activeUid)
        assertEquals(ServerId.DEFAULT, plan.server)
        assertNull(plan.uidToPersist)
    }

    // ---- 既有行为不回归 ----

    @Test
    fun `正常单账户命中自己并取该账户服务器`() {
        val plan = planAccountRefresh(listOf(account("u1", ServerId.Channel.id)), storedActiveUid = "u1")
        assertEquals("u1", plan.activeUid)
        assertEquals(ServerId.Channel, plan.server)
        assertNull("落盘已有 activeUid，无需回写", plan.uidToPersist)
    }

    @Test
    fun `多账户命中第二个时取第二个的服务器`() {
        val plan = planAccountRefresh(twoAccounts, storedActiveUid = "u-channel")
        assertEquals("u-channel", plan.activeUid)
        assertEquals(ServerId.Channel, plan.server)
    }

    @Test
    fun `落盘无 activeUid 时回落首个账户并要求回写盘`() {
        val plan = planAccountRefresh(twoAccounts, storedActiveUid = null)
        assertEquals("u-official", plan.activeUid)
        assertEquals(ServerId.Official, plan.server)
        assertEquals("u-official", plan.uidToPersist)
    }

    @Test
    fun `无账户且无 activeUid 维持 DEFAULT 且无回写`() {
        val plan = planAccountRefresh(emptyList(), storedActiveUid = null)
        assertNull(plan.activeUid)
        assertEquals(ServerId.DEFAULT, plan.server)
        assertNull(plan.uidToPersist)
    }

    @Test
    fun `账户 serverId 非法时按 StoredAccount 语义兜到 DEFAULT 而不是抛`() {
        val plan = planAccountRefresh(listOf(account("u1", "prod_gf_cn")), storedActiveUid = "u1")
        assertEquals("u1", plan.activeUid)
        assertEquals(ServerId.DEFAULT, plan.server)
    }

    // ---- 源码闸门：refreshAccounts 里不得再出现无兜底的 `first { }` ----

    @Test
    fun `refreshAccounts source delegates to planAccountRefresh and keeps no bare first`() {
        val candidates = listOf(
            // 单测工作目录 = app/ 模块目录
            "src/main/java/com/gigi/tcg/di/AppContainer.kt",
            // 兜底：工作目录 = 工程根
            "app/src/main/java/com/gigi/tcg/di/AppContainer.kt",
        )
        val file = requireNotNull(candidates.map(::File).firstOrNull { it.isFile }) {
            "AppContainer.kt not found from cwd=${File(".").getAbsolutePath()} (candidates=$candidates)"
        }
        val lines = file.readText().lines()
        val start = lines.indexOfFirst { it.contains("fun refreshAccounts()") }
        assertTrue("未找到 refreshAccounts()", start >= 0)
        var depth = 0
        var end = -1
        for (i in start..lines.lastIndex) {
            depth += lines[i].count { it == '{' }
            depth -= lines[i].count { it == '}' }
            if (depth == 0 && i > start) {
                end = i
                break
            }
        }
        assertTrue("refreshAccounts() 函数体未闭合", end > start)
        val body = lines.subList(start, end + 1).joinToString("\n")
        assertTrue("refreshAccounts 未委托纯函数 planAccountRefresh：\n$body", body.contains("planAccountRefresh("))
        assertTrue(
            "refreshAccounts 里回流了无兜底的 `first { }`（冷启动崩溃模式）：\n$body",
            !Regex("""\.first\s*\{""").containsMatchIn(body),
        )
    }
}
