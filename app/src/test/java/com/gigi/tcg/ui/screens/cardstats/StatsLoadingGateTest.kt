// V37-C（V37-5 缓存 Loading 语义）统计页回归锁，纯 JVM（工程无 Robolectric）：
// 用户报「每次切换页面都会出现 progressbar + 正在加载」，主代理实测根因不是缓存失效
// （force 全 false、5min cachedPrivate 秒回），而是 load() 入口**无条件** `loading = true`
// ⇒ 缓存命中也先画一帧 LoadingView。口径由 V36-5 定稿、HomeViewModel.loadProfile 落地：
// 「有缓存内容时不置 loading=true，直接静默替换」。本文件钉死该判据 + 源文件级不变量。
// V37-G 追加：统计页**换账号必须有页面侧 uid 观察者**（两名子代理独立点名的数据串号缺口）。
package com.gigi.tcg.ui.screens.cardstats

import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgStats
import com.gigi.tcg.domain.computeGcgSummary
import com.gigi.tcg.domain.prepareCardLists
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsLoadingGateTest {

    private val cards = listOf(
        GcgCard(name = "角色A", cardType = "CardTypeCharacter", useCount = 9, proficiency = 6),
        GcgCard(name = "装备X", cardType = "CardTypeModify", useCount = 10),
    )

    private val summary = computeGcgSummary(
        GcgStats(nickname = "牌手", level = 9, avatarCardNumGained = 30, actionCardNumGained = 120),
        prepareCardLists(cards),
    )

    /** 已经装载成功、正常展示中的状态（页面重入时的起点） */
    private val loaded = StatsUiState(
        loading = false,
        summary = summary,
        charList = prepareCardLists(cards).charCards,
        actionList = prepareCardLists(cards).actionCards,
        avatarUrl = "https://avatar/1",
        tier = "赤金 3★",
    )

    @Test
    fun `首屏无内容必须进阻塞 Loading`() {
        val first = StatsUiState()
        assertFalse("初值没有内容", first.hasContent)
        assertTrue("首屏必须转圈（否则用户对着空白等）", shouldShowBlockingLoading(first.hasContent, isError = false))
    }

    @Test
    fun `页面重入已有内容必须静默替换（本棒核心回归锁）`() {
        assertTrue("有 summary + 两列牌 = 有内容", loaded.hasContent)
        assertFalse(
            "重入装载不得把 loading 打回 true —— 这就是用户看到的切页闪菊花",
            shouldShowBlockingLoading(loaded.hasContent, isError = false),
        )
    }

    @Test
    fun `重入静默与 force 无关 下拉刷新同样保留内容`() {
        // 判据不看 force：force=true 只决定是否绕缓存（HomeViewModel.loadProfile 同口径）。
        // 下拉刷新要留内容 + 顶部指示器，收掉整屏 Loading 才是本棒的改动。
        assertFalse(shouldShowBlockingLoading(hasContent = true, isError = false))
    }

    @Test
    fun `summary 缺失但列表有牌也算有内容`() {
        // 红线 1：接口字段全可空。只看 summary != null 会把这种重入误判成首屏 ⇒ 白闪一次。
        val lists = prepareCardLists(cards)
        val noSummary = StatsUiState(loading = false, summary = null, charList = lists.charCards)
        assertTrue(noSummary.hasContent)
        assertFalse(shouldShowBlockingLoading(noSummary.hasContent, isError = false))

        val onlyAction = StatsUiState(loading = false, summary = null, actionList = lists.actionCards)
        assertTrue("只有行动牌也算", onlyAction.hasContent)
    }

    @Test
    fun `换账号清空内容后必须回到阻塞 Loading`() {
        // 内容归属闸门（语义照 V36 MyViewModel.AccountScopeGuard：账号变才清、页面重入不清）：
        // load() 里 uid 变了先把账户级字段清掉，清完必然「无内容」⇒ 阻塞 Loading 接管，
        // 绝不静默留着上一账号的统计当「既有内容」。
        val cleared = loaded.copy(summary = null, charList = emptyList(), actionList = emptyList(), avatarUrl = null, tier = "")
        assertFalse(cleared.hasContent)
        assertTrue(shouldShowBlockingLoading(cleared.hasContent, isError = false))
    }

    @Test
    fun `错误态重试必须回到 Loading 即使残留旧内容`() {
        // 本页错误分支只置 error、不清内容（现状），所以「错误页」下 hasContent 可能仍为 true。
        // 错误页上没有任何可静默替换的可见内容 ⇒ isError 必须赢过 hasContent，
        // retry()（force=true）才照常给用户一个转圈，而不是对着残留旧数字以为没反应。
        val failed = loaded.copy(loading = false, error = "请检查网络重试")
        assertTrue("错误与旧内容可以并存（现状不改）", failed.hasContent)
        assertTrue(shouldShowBlockingLoading(failed.hasContent, isError = true))
    }

    @Test
    fun `空结果态下次装载仍走阻塞 Loading`() {
        // uid 空 / cardList 空会落成 loading=false + 无内容（Route 出 EmptyState），
        // 下次重入没有内容可留 ⇒ 判据回 true，与首屏同路径。
        val empty = StatsUiState(loading = false, summary = null, charList = emptyList(), actionList = emptyList())
        assertTrue(empty.isEmpty)
        assertTrue(shouldShowBlockingLoading(empty.hasContent, isError = false))
    }

    // ---- 源文件级不变量（旧写法不得复活） ----

    private val vmSrc: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsViewModel.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        file.readText()
    }

    private val code: String by lazy {
        vmSrc.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    @Test
    fun loadEntryMustNotUnconditionallySetLoading() {
        assertFalse(
            "load() 入口的无条件 loading = true 必须消失",
            code.contains("copy(loading = true"),
        )
        assertTrue("入口判据必须走纯函数", code.contains("shouldShowBlockingLoading("))
    }

    @Test
    fun generationGuardAndRefreshingSemanticsUnchanged() {
        // 派单红线：generation 防竞态计数不得为「少改点」删掉
        assertTrue("代数防竞态必须保留", code.contains("if (gen != generation) return@launch"))
        assertTrue("每次装载必须自增代数", code.contains("val gen = ++generation"))
        // 任务 C：下拉圈只由用户手势驱动，本棒没动它（置 true 只允许出现在 refresh()）
        assertEquals("整份 VM 只有一处把 refreshing 置 true", 1, Regex("_refreshing\\.value = true").findAll(code).count())
        assertEquals(
            "落定处收圈的数量不变（uid 空 / 空列表 / 成功 / 失败 四处）",
            4,
            Regex("_refreshing\\.value = false").findAll(code).count(),
        )
    }

    // ---- V37-G 任务 D：换账号必须有页面侧触发器（V37-C residual 6-1 / V37-AD residual 7） ----

    private val routeCode: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsRoute.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        stripComments(file.readText())
    }

    /**
     * VM 侧的归属闸门（contentUid）只在有人调 load() 时才生效 ⇒ 页面必须自己观察 uid 变化。
     * 断言只认结构特征（collectAsStateWithLifecycle + LaunchedEffect(uid) + 变化才重拉），
     * 不锁行号、不锁整段文本 —— 派单 §8.5 的口径。
     */
    @Test
    fun accountSwitchTriggersAReloadFromTheRoute() {
        assertTrue(
            "uid 必须走 collectAsStateWithLifecycle 观察（组合内读 StateFlow.value 是 lint error）",
            routeCode.contains("container.sessionUid.collectAsStateWithLifecycle()"),
        )
        assertFalse("组合里禁止 sessionUid.value", routeCode.contains("sessionUid.value"))
        assertTrue("必须有按 uid 重启的观察者", routeCode.contains("LaunchedEffect(sessionUid)"))

        val guard = routeCode.substringAfter("LaunchedEffect(sessionUid)").substringBefore("    }")
        assertTrue("观察者必须持有上一次 uid 的基线（首帧不触发，避免与 VM init 双跑）", guard.contains("lastUid != null"))
        assertTrue("只在 uid 真正变化时重拉", guard.contains("!= lastUid"))
        assertTrue("变化时必须有装载入口被调用", guard.contains("viewModel.retry()"))
        assertFalse("换账号不是下拉手势，不得点亮下拉指示器", guard.contains("refresh()"))

        assertTrue(
            "守卫状态必须活到组合之外：统计页的 VM 随条目 saveState 存活，而 remember 在重入时被重置 ⇒ 判不出变化",
            routeCode.contains("rememberSaveable"),
        )
    }

    /** 剥掉行注释与块注释（含 KDoc），源码锁只匹配真正的代码 */
    private fun stripComments(src: String): String = src
        .replace(Regex("""(?s)/\*.*?\*/"""), " ")
        .replace(Regex("""(?m)//[^\n]*"""), " ")
}
