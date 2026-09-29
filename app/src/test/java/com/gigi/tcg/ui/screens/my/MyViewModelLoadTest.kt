// V36/2 任务 B/J/K：「我的」页装载语义。
// MyViewModel 本体要 Android Application，JVM 侧测它抽出来的纯件（同 HomeFirstLoadTest 的做法）：
// - [AccountScopeGuard]：账号切换才清空、页面重入不清空（用户第 12 项闪动的根因是"先清空再回填"，
//   force 全 false、零网络请求、5min 内存缓存秒回 —— 坏的不是缓存失效）；
// - [runStaggeredSteps]：5 组摘要串行错峰，不再一次性并发 5 个私有接口（-500004 保流窗口）；
// - 源码回归锁：防止无条件 `target.value = null` 被改回来。
// V37-G：头像回填从「只补激活账户」扩到「逐个补全部缺头账户」⇒ [planAvatarBackfill] 纯函数 +
// 回填链结构闸门（串行/单飞/稳态零请求/落盘键不读激活账户）+ 账户行 48dp 尺寸闸门。
package com.gigi.tcg.ui.screens.my

import com.gigi.tcg.data.auth.StoredAccount
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

    // ===== V37-G 任务 A：全账户头像回填计划（planAvatarBackfill 纯函数，稳态零请求判据） =====

    @Test
    fun `avatar backfill plan is empty when every account already has one`() {
        val complete = listOf(
            StoredAccount("261958214", "Oscuro", avatar = "https://cdn/a.png"),
            StoredAccount("157777921", "墨邪", avatar = "https://cdn/b.png"),
        )

        assertTrue(
            "头像补齐后每次进「我的」页必须零请求（计划为空 ⇒ VM 直接 return，连 job 都不开）",
            planAvatarBackfill(complete).isEmpty(),
        )
    }

    /** 真机现状（2026-09-29 取证）：两个账户的索引里都没有 avatar 键 ⇒ 两个都进计划，索引序不变 */
    @Test
    fun `avatar backfill plan covers every account lacking one and keeps index order`() {
        val accounts = listOf(
            StoredAccount("261958214", "Oscuro", lastActiveEpochMs = 20L),
            StoredAccount("157777921", "墨邪", lastActiveEpochMs = 10L, avatar = "https://cdn/b.png"),
            StoredAccount("333", "C", avatar = "https://cdn/c.png"),
        )

        assertEquals(listOf("261958214"), planAvatarBackfill(accounts).map { it.uid })
        assertEquals(
            listOf("261958214", "333"),
            planAvatarBackfill(accounts + StoredAccount("333", "C")).map { it.uid },
        )
    }

    @Test
    fun `blank avatar counts as missing`() {
        // 空白不是「有头像」：my_home_page 偶发下发空串，占位回落比空图有用，必须重取
        val accounts = listOf(
            StoredAccount("111", avatar = ""),
            StoredAccount("222", avatar = "   "),
            StoredAccount("333", avatar = null),
        )

        assertEquals(listOf("111", "222", "333"), planAvatarBackfill(accounts).map { it.uid })
        assertTrue("空索引（登出后）无账户可补", planAvatarBackfill(emptyList()).isEmpty())
    }

    /** 源码回归锁：无条件清空（闪动根因）不得改回来 */    @Test
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

    /**
     * 源码回归锁（V36/2b 建，V37-G 更新断言）：存量账户头像回填链路不得被拆。
     * getUserGameRolesByCookie 实测不下发 avatar_url ⇒ 唯一来源是 my_home_page 的
     * page_info.avatar_url，经 CredentialStore 更新入口落盘后必须刷账户列表。
     */
    @Test
    fun `avatar backfill rides the load chain and refreshes accounts`() {
        val src = File("src/main/java/com/gigi/tcg/ui/screens/my/MyViewModel.kt").readText()
        assertTrue("回填必须排在装载链里（错峰，不与摘要组并发）", src.contains("{ backfillAllAvatars() }"))
        assertTrue("头像只取 my_home_page 资料卡", src.contains("fetchMyHomePageCached"))
        assertTrue("落盘走 CredentialStore 更新入口", src.contains("updateAccountAvatar"))
        assertTrue("落盘后必须刷账户列表", src.contains("refreshAccounts()"))
        // （V37-I 起早退分支带上诊断日志，从单行 return 变块——「已有头像不重取」判据不变）
        assertTrue("已有头像不得重复取（零请求稳态）", src.contains("if (!account.avatar.isNullOrBlank()) {"))
    }

    /**
     * 源码回归锁（V37-G 任务 A）：回填必须是「全部缺头账户 + 串行错峰 + 单飞 + 逐账户隔离」。
     * 一次并发 N 个私有接口正中米游社 -500004 保流窗口；落盘键若读「当前激活账户」就会串号。
     */
    @Test
    fun `avatar backfill walks every pending account serially in one chain`() {
        val body = codeOnly(File("src/main/java/com/gigi/tcg/ui/screens/my/MyViewModel.kt").readText())
            .substringAfter("fun backfillAllAvatars(")
            .substringBefore("fun loadDeckList")

        // 计划来自整张索引（不是 activeAccountUid），空计划直接返回 ⇒ 稳态零请求、零 job
        assertTrue("计划必须遍历全部账户", body.contains("planAvatarBackfill(container.accounts.value)"))
        assertTrue("账户齐全时一个请求都不发", body.contains("if (pending.isEmpty()) return"))
        // 🔴 不再以激活账户为落盘键：回填期间切账号，旧账户的头不可能挂到新账户头上
        assertFalse("avatar 段不得读 activeAccountUid（串号防线）", body.contains("activeAccountUid"))
        // 串行 + 错峰：整条链只开一个 job，逐步走 runStaggeredSteps
        assertEquals("只允许一条在途回填链", 1, Regex("viewModelScope\\.launch").findAll(body).count())
        assertTrue("逐步错峰（沿用本页装载链间隔）", body.contains("runStaggeredSteps("))
        assertTrue("错峰间隔默认 MY_LOAD_STAGGER_MS", body.contains("staggerMs: Long = MY_LOAD_STAGGER_MS"))
        assertTrue("重入装载链先取消上一条", body.contains("avatarJob?.cancel()"))
        // 逐账户隔离：本账户异常只跳过本账户，取消必须放行
        // （V37-I 起 catch 绑定 e 供诊断日志用，但**仍不外抛**——语义不变，只是多记一行）
        assertTrue("单账户请求失败不外抛", body.contains("} catch (e: Exception) {"))
        assertTrue("CancellationException 必须放行", body.contains("throw cancel"))
    }

    /**
     * 源码闸门（V37-G 任务 B/C）：账户行头像尺寸 48dp = M3 ListItem LeadingAvatar，
     * 且占位回落的数据路径不因改尺寸被动。派单红线：资料卡 64dp（V37-AD 独占）必须原地不动。
     */
    @Test
    fun `account row avatar is 48dp and placeholder fallback stays`() {
        val src = codeOnly(File("src/main/java/com/gigi/tcg/ui/screens/my/MyRoute.kt").readText())

        assertTrue("尺寸抽成具名常量且值为 48dp", Regex("internal val MyAccountAvatarSize = 48\\.dp").containsMatchIn(src))
        assertTrue("账户行走常量，不留字面量", src.contains("size = MyAccountAvatarSize"))
        assertFalse("旧值 56dp 不得复活", src.contains("size = 56.dp"))
        assertTrue("头像仍取 StoredAccount.avatar（缺值由 Avatar 画圆形 Person 占位）", src.contains("url = account.avatar"))

        val statsSrc = File("src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsRoute.kt").readText()
        assertTrue(
            "首页/统计页资料卡的 64dp 头像不在本棒范围，必须原样保留",
            statsSrc.contains("internal const val PLAYER_INFO_AVATAR_DP = 64"),
        )
    }

    /** 剥掉行注释与块注释（含 KDoc），源码锁只匹配真正的代码 */
    private fun codeOnly(src: String): String = src
        .replace(Regex("""(?s)/\*.*?\*/"""), " ")
        .replace(Regex("""(?m)//[^\n]*"""), " ")

    /**
     * 源码闸门（V37-I 任务 B）：回填结果必须在 logcat 可见 —— 「墨邪头像永远取不到」这个 bug
     * 的取证灾难正是**静默吞异常**（用户视角=头像是黑的，日志视角=一片空白）。
     * 红线：只加日志，用户可见行为与持久化契约（account_index 内容、UI 提示）一律不动。
     */
    @Test
    fun `avatar backfill logs hit skip and failure per account`() {
        val body = codeOnly(File("src/main/java/com/gigi/tcg/ui/screens/my/MyViewModel.kt").readText())
            .substringAfter("private suspend fun backfillOneAvatar(")
            .substringBefore("fun loadDeckList")

        assertTrue("命中（回填成功）必须记一行", body.contains("\"ok uid="))
        assertTrue("跳过必须记一行（带 uid 与原因）", body.contains("skip uid="))
        assertTrue("失败必须记一行（带 uid、昵称与原因）", body.contains("fail uid="))
        assertTrue(
            "走 android.util.Log（成功 info / 失败 warn），不得用 println",
            body.contains("Log.i(AVATAR_BACKFILL_LOG_TAG") && body.contains("Log.w(AVATAR_BACKFILL_LOG_TAG"),
        )
        assertFalse("println 不得出现", body.contains("println"))
        // 取数通道不变：仍是 repo 按 uid 的 fetchMyHomePageCached（V37-I 起该请求自带**目标账户自己的** cookie）
        assertTrue(body.contains("fetchMyHomePageCached(uid, server)"))
    }

    /** 源码闸门（V37-I）：log tag 是具名常量且值稳定，主代理收装机后 `adb logcat -s GigiAvatar` 可 grep */
    @Test
    fun `avatar backfill log tag is a stable constant`() {
        val src = File("src/main/java/com/gigi/tcg/ui/screens/my/MyViewModel.kt").readText()
        assertTrue(
            "tag 常量必须存在且值为 GigiAvatar",
            src.contains("private const val AVATAR_BACKFILL_LOG_TAG = \"GigiAvatar\""),
        )
    }
}
