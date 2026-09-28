package com.gigi.tcg.di

import com.gigi.tcg.data.auth.AuthFinalizeResult
import com.gigi.tcg.data.auth.StoredAccount
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * U3A2 接线证明：静默续命端口（SessionRefresher）确已注入 GigiRepository。
 * 纯 JVM 单测构造不出真 AppContainer（CredentialStore 需 Context、LruCache 为未 mock 桩），
 * 故分两层：① 真身 ContainerSessionRefresher 行为；② AppContainer.kt 源码文本断言防"写了没接线"回归。
 */
class SessionRefreshWiringTest {

    private fun account(uid: String) = StoredAccount(uid = uid, nickname = "n-$uid")

    private fun success(uid: String) =
        AuthFinalizeResult.Success(
            mergedCookie = "cookie=$uid",
            gameUid = uid,
            nickname = "n",
            exchanged = true,
            region = "cn_gf01",
        )

    // ---- 测试 1：ContainerSessionRefresher 行为 ----

    @Test
    fun `null activeUid returns false without touching accounts or refresh`() = runTest {
        var accountsCalled = 0
        var refreshCalled = 0
        var refreshedCalled = 0
        val refresher = ContainerSessionRefresher(
            accounts = { accountsCalled++; listOf(account("u1")) },
            activeUid = { null },
            refresh = { refreshCalled++; success("u1") },
            onRefreshed = { refreshedCalled++ },
        )
        assertEquals(false, refresher.refreshActive())
        assertEquals(0, accountsCalled)
        assertEquals(0, refreshCalled)
        assertEquals(0, refreshedCalled)
    }

    @Test
    fun `activeUid missing from index returns false without refresh`() = runTest {
        var refreshCalled = 0
        var refreshedCalled = 0
        val refresher = ContainerSessionRefresher(
            accounts = { listOf(account("u1")) },
            activeUid = { "ghost" },
            refresh = { refreshCalled++; success("ghost") },
            onRefreshed = { refreshedCalled++ },
        )
        assertEquals(false, refresher.refreshActive())
        assertEquals(0, refreshCalled)
        assertEquals(0, refreshedCalled)
    }

    @Test
    fun `Success returns true and notifies onRefreshed exactly once`() = runTest {
        var refreshCalled = 0
        val refreshed = mutableListOf<AuthFinalizeResult.Success>()
        val refresher = ContainerSessionRefresher(
            accounts = { listOf(account("u1"), account("u2")) },
            activeUid = { "u2" },
            refresh = { acct -> refreshCalled++; assertEquals("u2", acct.uid); success(acct.uid) },
            onRefreshed = { refreshed += it },
        )
        assertEquals(true, refresher.refreshActive())
        assertEquals(1, refreshCalled)
        assertEquals(1, refreshed.size)
        assertEquals("u2", refreshed.single().gameUid)
    }

    @Test
    fun `NoRole returns false with zero side effects`() = runTest {
        var refreshedCalled = 0
        val refresher = ContainerSessionRefresher(
            accounts = { listOf(account("u1")) },
            activeUid = { "u1" },
            refresh = { AuthFinalizeResult.NoRole(region = "cn_gf01") },
            onRefreshed = { refreshedCalled++ },
        )
        assertEquals(false, refresher.refreshActive())
        assertEquals(0, refreshedCalled)
    }

    @Test
    fun `refresh IOException folds to false without notifying`() = runTest {
        var refreshedCalled = 0
        val refresher = ContainerSessionRefresher(
            accounts = { listOf(account("u1")) },
            activeUid = { "u1" },
            refresh = { throw IOException("network down") },
            onRefreshed = { refreshedCalled++ },
        )
        assertEquals(false, refresher.refreshActive())
        assertEquals(0, refreshedCalled)
    }

    @Test
    fun `refresh RuntimeException folds to false without notifying`() = runTest {
        var refreshedCalled = 0
        val refresher = ContainerSessionRefresher(
            accounts = { listOf(account("u1")) },
            activeUid = { "u1" },
            refresh = { throw RuntimeException("boom") },
            onRefreshed = { refreshedCalled++ },
        )
        assertEquals(false, refresher.refreshActive())
        assertEquals(0, refreshedCalled)
    }

    @Test
    fun `refresh CancellationException is rethrown not folded`() = runTest {
        val refresher = ContainerSessionRefresher(
            accounts = { listOf(account("u1")) },
            activeUid = { "u1" },
            refresh = { throw CancellationException("scope cancelled") },
            onRefreshed = { fail("onRefreshed must not run on cancellation") },
        )
        val result = runCatching { refresher.refreshActive() }
        val error = result.exceptionOrNull()
        assertTrue("期望外抛 CancellationException（不能吞成 false），实际=$result",
            error is CancellationException)
        assertEquals("scope cancelled", error?.message)
    }

    // ---- 测试 2：源码文本断言，防"机制写了没接线"回归 ----

    @Test
    fun `AppContainer source wires sessionRefresher into GigiRepository`() {
        val candidates = listOf(
            // 单测工作目录 = app/ 模块目录
            "src/main/java/com/gigi/tcg/di/AppContainer.kt",
            // 兜底：工作目录 = 工程根
            "app/src/main/java/com/gigi/tcg/di/AppContainer.kt",
        )
        val file = requireNotNull(candidates.map(::File).firstOrNull { it.isFile }) {
            "AppContainer.kt not found from cwd=${File(".").getAbsolutePath()} (candidates=$candidates)"
        }
        val lines: List<String> = file.readText().lines()
        assertTrue("AppContainer.kt 抽取为空（file=${file.absolutePath}）", lines.isNotEmpty())

        // b) 确实定义了 sessionRefresher 端口 lazy
        val declRe = Regex("sessionRefresher\\s*:\\s*SessionRefresher")
        val wiringRe = Regex("sessionRefresher\\s*=")
        val declLines = lines.mapIndexed { index: Int, line: String ->
            if (declRe.containsMatchIn(line)) "L${index + 1}: $line" else null
        }.filterNotNull()
        assertTrue("未找到 `sessionRefresher: SessionRefresher` 声明：\n$declLines", declLines.isNotEmpty())

        // a) GigiRepository( 构造调用体内出现 sessionRefresher =（按括号配对圈定构造调用范围）
        val ctorStart = lines.indexOfFirst { line -> line.contains("GigiRepository(") && !line.contains("class ") }
        assertTrue("未找到 GigiRepository( 构造调用", ctorStart >= 0)
        var depth = 0
        var wiringLine = -1
        for (i in ctorStart until lines.size) {
            val line: String = lines[i]
            depth += line.count { c: Char -> c == '(' } - line.count { c: Char -> c == ')' }
            if (wiringLine < 0 && wiringRe.containsMatchIn(line)) wiringLine = i
            if (i > ctorStart && depth <= 0) break
        }
        val wiring = if (wiringLine >= 0) "${wiringLine + 1}: ${lines[wiringLine]}" else ""
        assertTrue(
            "GigiRepository( 构造参数（自 L${ctorStart + 1} 起）未出现 `sessionRefresher =`，命中=[$wiring]",
            wiring.isNotEmpty(),
        )
        println("WIRING-EVIDENCE ctor@L${ctorStart + 1} decl@${declLines.joinToString()} wiring@$wiring")
    }
}
