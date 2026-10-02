// V27 卡牌统计页回归锁（纯 JVM，工程无 Robolectric）：
// 1) tab 文案资源与页数一致（stats_tab_char / stats_tab_action，两页）；
// 2) 页码 ↔ 导出表类型映射：第 0 页 = 角色牌（charTable=true）、第 1 页 = 行动牌；
// 3) 表类型 → 子目录/标题资源映射（角色牌 → EXPORT_DIR_CHAR / export_char_title）；
// 4) 源文件级：indicator 实时跟随写法（currentPage 插值 + wrapContentSize 解约束）
//    照抄自 RankRoute，与 RankTabIndicatorTest 同构；旧的两枚页内导出按钮必须已删除。
// V28-C 追加：多选导出对话框（默认全选、positive=保存、跑完显式关窗、全不勾有提示）。
// V29 反转 V28-C 的滚动结构（修「滑动白屏」）：
// 5) 结构回退成 RankRoute 的「固定头 + 页内滚动」：根 Column(fillMaxSize) → 头部/TabRow 固定，
//    PullToRefreshBox 里 HorizontalPager(fillMaxSize)，每页各自 LazyColumn 滚动；
//    全页不得再出现 verticalScroll（pager 在无限高约束下算不出页高 ⇒ 滑动时新页塌成 0 高白屏）；
// 6) TabRow 恢复左右满宽（不再 weight(1f) 让位给右缘按钮）；
// 7) 导出入口进详情面板末尾，样式是 filled Button + 下载图标 + fillMaxWidth（旧 IconButton 不得复活）；
// 8) 导出反馈一次点击只回**一条**消息（合并两张结果 + 带落盘 Uri 供宿主「查看」），
//    聚合口径由 exportFeedbackText 直接做纯 JVM 断言；
// 9) 对话框条目文案带真正导出的条数（stats_export_option_char / _action 占位符资源），
//    条数谓词必须与 CardStatsExport 的 useCount > 0 过滤同口径；
// 10) 新文案三语齐全（values / values-en / values-zh-rTW），杜绝切语言后缺字。
// V39-B 任务 A：第 5) 条「固定头 + 页内滚动」**反转** —— 信息卡 + tab 行进页内 LazyColumn，
//    整页一起滚；PullToRefreshBox→HorizontalPager→LazyColumn 三层关系与「无 verticalScroll」照旧锁死。
// V40-A 又反转 V39-B（用户报「切 tab 连带着整个卡牌统计页一起切」）：头部**移出 pager、
//    全页只一份**，纵向整体滑动改由折叠式头部补（容器层 nestedScroll：上滑先折头、到顶下拉回展）。
//    pager 内容里不得再出现头部；P2R 包着折叠连接、折叠连接包着 pager；V29 有界高/无滚条防线照旧。
package com.gigi.tcg.ui.screens.cardstats

import com.gigi.tcg.R
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_ACTION
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_CHAR
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStatsTabsTest {

    @Test
    fun tabCountMatchesLabelTable() {
        assertEquals("tab 页数与文案表必须一致", STATS_TAB_COUNT, STATS_TAB_LABEL_RES.size)
        assertEquals(2, STATS_TAB_COUNT)
    }

    @Test
    fun tabLabelsUseDedicatedStatsStrings() {
        assertEquals(R.string.stats_tab_char, STATS_TAB_LABEL_RES[0])
        assertEquals(R.string.stats_tab_action, STATS_TAB_LABEL_RES[1])
    }

    @Test
    fun pageMapsToCharTable() {
        assertTrue("第 0 页 = 角色牌表", isCharTable(0))
        assertFalse("第 1 页 = 行动牌表", isCharTable(1))
        // 越界兜底按第 0 页语义，不给未定义页导出错表的机会
        assertTrue(isCharTable(-1))
        assertTrue(isCharTable(STATS_TAB_COUNT))
    }

    @Test
    fun charTableMapsToCharDirAndTitle() {
        assertEquals(EXPORT_DIR_CHAR, exportDirRes(charTable = true))
        assertEquals(EXPORT_DIR_ACTION, exportDirRes(charTable = false))
        assertEquals(R.string.export_char_title, exportTitleRes(charTable = true))
        assertEquals(R.string.export_action_title, exportTitleRes(charTable = false))
    }

    @Test
    fun selectionIsEmptyOnlyWhenNothingChecked() {
        assertFalse("只勾角色牌不为空", StatsExportSelection(charTable = true, actionTable = false).isEmpty)
        assertFalse("只勾行动牌不为空", StatsExportSelection(charTable = false, actionTable = true).isEmpty)
        assertFalse("两项全选不为空", StatsExportSelection(charTable = true, actionTable = true).isEmpty)
        assertTrue("全不勾才是空（点保存应给提示，不静默失败）", StatsExportSelection(charTable = false, actionTable = false).isEmpty)
    }

    // ---- 源文件级不变量（与 RankTabIndicatorTest 同构，File 读取源码文本） ----

    private val routeSrc: String by lazy { srcOf("CardStatsRoute.kt") }
    private val exportActionSrc: String by lazy { srcOf("CardStatsExportAction.kt") }

    /**
     * 只留代码行（丢掉 // 开头的整行注释）：文件头/函数 KDoc 里会点名被废弃的旧写法
     * （verticalScroll、stats_export_current、TabRow 的 weight 让位…），拿原文做「不得复活」断言会自己打自己。
     */
    private val routeCode: String by lazy {
        routeSrc.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    private fun srcOf(fileName: String): String {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/cardstats/$fileName")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        return file.readText()
    }

    @Test
    fun indicatorInterpolatesWithCurrentPageNotSettled() {
        assertTrue(
            "indicator 必须用 currentPage + currentPageOffsetFraction 实时插值（跟手）",
            routeCode.contains("pagerState.currentPage + pagerState.currentPageOffsetFraction"),
        )
        assertFalse(
            "统计页 indicator 不应引入 settledPage（那是 RankRoute 数据加载的语义，与跟随无关）",
            routeCode.contains("settledPage"),
        )
    }

    @Test
    fun indicatorUnwrapsParentConstraints() {
        // 缺 wrapContentSize 时 TabRow 的固定宽度约束会把 width() 夹成整行宽（V7A 回归）
        val fill = routeCode.indexOf(".fillMaxWidth()")
        val wrap = routeCode.indexOf("wrapContentSize(Alignment.BottomStart)")
        val width = routeCode.indexOf(".width(rightDp - leftDp)")
        assertTrue("indicator 链必须有 fillMaxWidth()", fill >= 0)
        assertTrue("indicator 链必须有 wrapContentSize(Alignment.BottomStart)", wrap >= 0)
        assertTrue("wrapContentSize 必须在显式 width 之前", wrap in 0 until width)
    }

    @Test
    fun pagerBackedTabSwitching() {
        assertTrue("必须用 HorizontalPager 承载左右滑动", routeCode.contains("HorizontalPager(state = pagerState"))
        assertTrue("pager 页数取 STATS_TAB_COUNT", routeCode.contains("pageCount = { STATS_TAB_COUNT }"))
    }

    @Test
    fun oldDualExportButtonsRemoved() {
        assertFalse("旧的「导出角色牌」页内按钮必须删除", routeCode.contains("stats_export_char"))
        assertFalse("旧的「导出行动牌」页内按钮必须删除", routeCode.contains("stats_export_action"))
        assertFalse(
            "旧的 tab 行 IconButton contentDescription（「当前图表」口径已废）不得复活",
            routeCode.contains("stats_export_current"),
        )
    }

    // ---- V29：结构回退到 RankRoute 的「固定头 + 页内滚动」（修滑动白屏） ----

    @Test
    fun noVerticalScrollAnywhereInPage() {
        // 白屏根因：pager 落在 verticalScroll 里 ⇒ 无限高约束 ⇒ 翻页首帧页高塌成 0。
        // 现在代码里不允许出现任何纵向 verticalScroll（横向筛选条的 horizontalScroll 不在此列）。
        assertEquals(
            "全页不得有 verticalScroll（改回 RankRoute 的固定头 + 页内 LazyColumn）",
            0,
            Regex("verticalScroll\\(").findAll(routeCode).count(),
        )
        // V29 需求 8：行动牌四个筛选 chip 改 ToggleGroup 后已无横向滚动条
        // （四项等分一整行放得下，不需要滚）。角色牌排序 ToggleGroup 同理。
        assertEquals(
            "筛选条改 ToggleGroup 后不应再有 horizontalScroll",
            0,
            Regex("horizontalScroll\\(").findAll(routeCode).count(),
        )
    }

    @Test
    fun pagerFillsBoundedViewportHeight() {
        assertTrue(
            "HorizontalPager 必须在有界高里 fillMaxSize（RankRoute:171 同写法）",
            routeCode.contains("HorizontalPager(state = pagerState, modifier = ComposeModifier.fillMaxSize())"),
        )
    }

    /**
     * 🔴 V40-A **再次反转**（V39-B 的「头部进每页 LazyColumn」作废）：用户报「切 tab 连带着整个
     * 卡牌统计页一起切」——根因就是信息卡/tab 行在每页各复制一份，天然随翻页横移。现在头部
     * **移出 pager、全页只此一份**；V39-B 的「整体纵向滚动」不丢，改由折叠式头部补（容器层
     * nestedScroll：上滑先折头、到顶下拉回展）。本测试锁新结构三件事：
     * ① pager 的 content 里不得再出现头部（出现即又会被翻页带走）；
     * ② 折叠链路在场且内外次序正确（P2R → 折叠连接 → pager）；
     * ③ 白屏病根防线原样：pager 仍吃有界高（weight(1f)），全页仍无 verticalScroll。
     */
    @Test
    fun headerAndTabsLiveOutsideThePagerWithNestedScrollCollapse() {
        val pull = routeCode.indexOf("PullToRefreshBox(")
        val nested = routeCode.indexOf("nestedScroll(")
        val pager = routeCode.indexOf("HorizontalPager(state = pagerState")
        assertTrue("必须有 PullToRefreshBox（下拉刷新仍可用）", pull >= 0)
        assertTrue("折叠连接必须走 nestedScroll", nested >= 0)
        assertTrue("pager 必须在 PullToRefreshBox 的 content 内（RankRoute 同层级）", pager > pull)
        assertTrue("折叠连接必须在 pager 之外（P2R 内、pager 前）", nested in pull until pager)

        // ① pager 的 lambda body 里不得再出现头部 —— 上一轮的病根（头部进页内 ⇒ 翻页横移）
        val pagerBody = bracedBlockAfter(routeCode, "HorizontalPager(state = pagerState")
        assertFalse("pager 页内不得再出现信息卡", pagerBody.contains("StatsInfoCardItem"))
        assertFalse("pager 页内不得再出现 tab 行", pagerBody.contains("StatsTabRowItem"))
        // 全页各只有一处调用（函数定义被 (?<!fun ) 排掉），且都在 pager 之前
        assertEquals("信息卡全页只此一份", 1, Regex("(?<!fun )StatsInfoCardItem\\(").findAll(routeCode).count())
        assertEquals("tab 行全页只此一份", 1, Regex("(?<!fun )StatsTabRowItem\\(").findAll(routeCode).count())

        // ② 两页函数体：头部 item 已删干净，吸顶列名（用户点名保留）还在
        listOf("CharStatsPage", "ActionStatsPage").forEach { page ->
            val body = functionBodyOf(routeCode, "private fun $page(")
            assertFalse("$page 不再持有信息卡 item", body.contains("StatsInfoCardItem"))
            assertFalse("$page 不再持有 tab 行 item", body.contains("StatsTabRowItem"))
            assertTrue("$page 的列名仍 sticky（用户点名保留）", body.contains("stickyHeader("))
        }
        // 旧头部 item key 不得复活（出现了就说明有页又偷偷把头部塞回去了）
        listOf("char-info", "char-tabs", "action-info", "action-tabs").forEach { staleKey ->
            assertFalse("旧头部 item key 不得复活: $staleKey", routeCode.contains(staleKey))
        }

        // ③ 白屏病根防线（V29）原样：pager 吃 weight(1f) 的**有界**高、全页仍无 verticalScroll
        assertTrue("列表区必须继续 weight(1f)（有界高防线）", routeCode.contains("ComposeModifier.weight(1f)"))
        assertEquals(
            "全页不得出现 verticalScroll（V29 白屏病根）",
            0,
            Regex("verticalScroll\\(").findAll(routeCode).count(),
        )
        // 折叠机制必须在场（防止「移出 pager」退化成 V29 的固定头）
        assertTrue("折叠连接类必须在场", routeCode.contains("NestedScrollConnection"))
        assertTrue("折叠量必须走 state 引用（连接只建一次）", routeCode.contains("mutableFloatStateOf(0f)"))
        // 旧的固定头横向 padding 容器不得回来
        assertFalse("旧的固定头横向 padding 容器已随本轮删除", routeCode.contains("padding(horizontal = ContentHorizontalPadding)"))
    }

    // ---- V45：行程上界 = 自然高 − 残留（修「tab 不接顶」+「展开详情后顶部悬更高」） ----

    /**
     * 🔴 V45。用户实测（K30 截图 `/sdcard/DCIM/Screenshots/`）指认两个显示 bug：
     *① 「**下方列表滑动时 tab 顶部不会接顶**」—— V44 折在 84~88px 残留处，tab 上方永远悬一截空隙。
     * ② 「**展开详情后，距顶距离变得更大**」—— V44 行程上界是**常量 168dp**，
     *    而展开详情后头部自然高 542px → **1307px**；行程仍 462px ⇒ 残留 **845px（占屏 35%）**。
     *
     * **两者同源：把常量当成了上界。** 行程必须能吃掉**整个头部自然高**（含详情区）。
     *
     * 本用例锁死 V45 的正确口径：
     *  - **容器总高** = 卡片自然高（`onSizeChanged` 测量）。
     *  - **行程上界** = `自然高 − 残留`，由连接**每次现算**（不缓存、不经 effect 中转）。
     *
     * 🔴 为什么必须「现算 + 不中转」：V44 第一版用 `LaunchedEffect(stateA, stateB)` 推导上界，
     * 而**`LaunchedEffect` 的 key 传的是 state 对象而非值，effect 只在首次组合跑一次**，
     * 首帧读0 就永久定格 ⇒ 上界恒 0 ⇒ 头部彻底不折叠、展开详情后把列表挤扁到被遮住（真机复现）。
     * 本轮改成**直接读测量写入的那个 `MutableFloatState`** ⇒ 没有 key、没有时序问题。
     */
    @Test
    fun collapseLimitTracksNaturalHeightSoNothingHangsBelowTabs() {
        val conn = functionBodyOf(routeCode, "private class HeaderCollapseConnection(")
        val container = functionBodyOf(routeCode, "private fun CollapsibleHeaderContainer(")

        // ① 残留高度：V45 取 0dp（完全折尽 ⇒ tab 硬接顶），且必须是一个独立常量
        assertTrue(
            "折尽后的残留高度必须是显式常量 HeaderCollapseResidualDp",
            routeCode.contains("private val HeaderCollapseResidualDp"),
        )
        assertTrue(
            "🔴 残留必须取 0.dp：用户指认「tab 顶部不会接顶」，就是84~88px 残留造成的",
            Regex("""private val HeaderCollapseResidualDp = 0\.dp""").containsMatchIn(routeCode),
        )
        // ② 上界必须由「自然高 − 残留」现算，不能是任何写死的常量
        assertTrue(
            "上界 limitPx 必须现算（getter），不能缓存成构造期的常量",
            Regex("""private val limitPx: Float\s*\n\s*get\(\)""").containsMatchIn(conn),
        )
        assertTrue(
            "上界必须读测量写入的 naturalHeightPx（直接读 state，不经 effect 中转）",
            conn.contains("naturalHeightPx.floatValue"),
        )
        assertTrue(
            "上界必须减去残留高度（自然高 − 残留 = 折尽后可见的那一截）",
            conn.contains("(base - residualPx)") && conn.contains("residualPx: Float"),
        )
        assertFalse(
            "🔴 上界不得写死常量：写死会让「展开详情」后自然高 1307px 而行程仍 462px ⇒ " +
                "残留 845px（用户实测「距顶距离变得更大」）",
            conn.contains("collapseLimitPx") || conn.contains("HeaderCollapseMaxDp"),
        )
        // ③ 自然高未测到时用兜底行程，而不是 0（否则首帧完全不折、头部像被钉住）
        assertTrue(
            "自然高尚未测到时应回退到兜底行程 HeaderCollapseMinTravelDp",
            conn.contains("minTravelPx") && conn.contains("if (natural > 0f) natural else minTravelPx"),
        )
        assertTrue(
            "兜底行程必须作为常量声明并换算成 px 传入",
            routeCode.contains("private val HeaderCollapseMinTravelDp") &&
                routeCode.contains("HeaderCollapseMinTravelDp.toPx()"),
        )
        // ④ 不得用 effect 推导上界（V44 第一版的病根）
        assertFalse(
            "行程不得由两个实测高度推导（collapsibleHeightOf 已撤销）",
            routeCode.contains("collapsibleHeightOf"),
        )
        assertFalse(
            "不得用 LaunchedEffect(stateA, stateB) 重算上界（key 传对象不传值，effect 只跑一次）",
            Regex("LaunchedEffect\\(\\s*\\w*[Hh]eader\\w*").findAll(routeCode).any { m ->
                val seg = routeCode.substring(m.range.first, minOf(m.range.first + 200, routeCode.length))
                seg.contains("limitPx") || seg.contains("Collapse") || seg.contains("naturalHeight")
            },
        )
        // ⑤ 容器只管「总高」，公式是「自然高 − 已折叠量」，且不参与行程决策
        assertTrue(
            "CollapsibleHeaderContainer 必须同时收折叠量与自然高",
            container.contains("collapsePx: MutableFloatState") &&
                container.contains("naturalHeightPx: MutableFloatState"),
        )
        assertTrue(
            "容器高度公式必须是「自然高 − 已折叠量」",
            Regex("\\(naturalHeight\\s*-\\s*collapsePx\\.floatValue\\)\\.coerceAtLeast\\(0f\\)")
                .containsMatchIn(container),
        )
        assertFalse(
            "🔴 容器不得再用任何常量当总高（V44 把 168dp 当总高 ⇒ 默认态 462px < 卡片 543px，" +
                "底部被裁 81px，用户报「默认被遮挡住」）",
            container.contains("HeaderCollapseResidualDp") ||
                container.contains("HeaderCollapseMinTravelDp") ||
                container.contains("(limit - collapsePx.floatValue)"),
        )
        // 首帧自然高未知（=0）时不得裁剪，否则头部先以 0 高画一帧再白闪
        assertTrue(
            "首帧自然高为 0 时应先按自然排版（不设 height），量到后再接管",
            container.contains("if (naturalHeight > 0f)") && container.contains("ComposeModifier.fillMaxWidth()"),
        )
        // 测量回调必须挂内层 Column（Box 之后），不能挂外层 Box —— 否则量到折叠后可见高、自锁
        val afterBox = container.substringAfter("Box(boxModifier) {")
        assertTrue(
            "自然高测量必须挂在内层 wrapContentHeight(unbounded) 的 Column 上",
            afterBox.contains("wrapContentHeight(unbounded = true") &&
                Regex("""wrapContentHeight\(unbounded = true, align = Alignment\.Top\)\s*\n\s*\.onSizeChanged \{""")
                    .containsMatchIn(afterBox),
        )
        // ⑥ 同一个 state 同时喂给容器（算总高）与连接（算上界），两者不得各测各的
        assertTrue(
            "Route 必须把同一个自然高 state 同时传给容器与连接",
            routeCode.contains("CollapsibleHeaderContainer(headerCollapsePx, headerNaturalHeightPx)") &&
                routeCode.contains("naturalHeightPx = headerNaturalHeightPx"),
        )
    }

    /**
     * 折叠手势链必须做到「折满即放行」+「吃 fling」，这两处都是用户体感的直接来源。
     *
     * -折满即放行：旧实现折满后仍要走一轮「old − next == 0」的判定才把余量给列表，边界处发涩。
     * - 吃 fling：旧实现只认 [NestedScrollSource.UserInput]，抬手瞬间头部停住而列表继续惯性滚
     *   ⇒ 两段速度不连续，这是「阻尼感」的另一半来源。
     */
    @Test
    fun collapseConnectionReleasesWhenFullyCollapsedAndAcceptsFling() {
        val preBody = bracedBlockAfter(routeCode, "override fun onPreScroll(")
        val postBody = bracedBlockAfter(routeCode, "override fun onPostScroll(")

        // ① 上滑路径要接受 fling（SideEffect）：抬手后头部与仍在惯性滚的列表脱节。
        assertTrue(
            "折叠连接的上滑路径必须接受 fling（SideEffect），否则抬手后头部与列表脱节",
            preBody.contains("NestedScrollSource.SideEffect"),
        )
        // 🔴 V46：回展路径**同样**必须吃 fling。
        // 用户实测「向下滑动阻尼大、上滑跟手」—— 上滑侧早就放行了 SideEffect，
        // 回展侧却仍只认 UserInput ⇒ 快速下滑松手的惯性滚动不带动头部，
        // 视觉上就是「头被拽住、拉不动」。**两条路径必须对称**，改一边就要改另一边。
        assertTrue(
            "🔴 回展路径也必须吃 fling：只认 UserInput 会让「向下滑动阻尼大」（用户实测）",
            postBody.contains("NestedScrollSource.SideEffect"),
        )
        assertFalse(
            "回展路径不得只认 UserInput（旧写法 `source != UserInput || available.y …`）",
            postBody.contains("if (source != NestedScrollSource.UserInput || available.y"),
        )
        // ② 折满立即放行：剩余 delta 一次性全给列表
        // 🔴 V45：上界字段改名 limitPx（V44 是构造期常量 collapseLimitPx，V45 改为每次现算的
        //    `自然高 − 残留` getter）。短路条件多了一个 `limit <= 0f` 分支——
        //    自然高尚未测到、上界算出 0 时必须直接放行，否则头部会被「锁死」折不动。
        assertTrue(
            "折满必须立即放行（old >= 上界时 return Offset.Zero）",
            preBody.contains("if (limit <= 0f || old >= limit) return Offset.Zero"),
        )
        // ③ 展平立即放行，否则「到顶下拉」会先卡一下才出刷新圈
        assertTrue("展平（old <= 0）必须立即放行", postBody.contains("if (old <= 0f) return Offset.Zero"))
    }

    /** 按花括号配平取 [anchor] 后第一个 `{...}` 块（含括号）；源码里字符串模板的花括号天然成对，不影响配平 */
    private fun bracedBlockAfter(code: String, anchor: String): String {
        val anchorAt = code.indexOf(anchor)
        assertTrue("源码里找不到锚点: $anchor", anchorAt >= 0)
        val open = code.indexOf('{', anchorAt + anchor.length)
        assertTrue("锚点后缺少 `{`: $anchor", open >= 0)
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
        }
        throw AssertionError("锚点代码块未配平: $anchor")
    }

    /** 函数体：从签名到列 0 的首个 `}`（体内的闭括号都有缩进，不会误截断） */
    private fun functionBodyOf(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue("找不到函数: $signature", start >= 0)
        val end = code.indexOf("\n}", start)
        assertTrue("函数体未闭合: $signature", end > start)
        return code.substring(start, end)
    }

    @Test
    fun eachPageScrollsInternallyWithLazyColumn() {
        // 照抄榜一（RankRoute:214-215）：LazyColumn(fillMaxSize) + 带稳定 key 的 itemsIndexed
        val lazyCount = Regex("LazyColumn\\(").findAll(routeCode).count()
        assertEquals("两页各自一个 LazyColumn 承载页内滚动", 2, lazyCount)
        assertTrue("LazyColumn 必须 fillMaxSize 吃满页高", routeCode.contains("LazyColumn(\n        ComposeModifier.fillMaxSize(),"))
        assertTrue("列表项必须用带 key 的 itemsIndexed", routeCode.contains("itemsIndexed("))
        assertTrue("角色牌 key 带下标（牌名可能重复）", routeCode.contains("-> \"char-\$index"))
        assertTrue("行动牌 key 带下标", routeCode.contains("-> \"action-\$index"))
    }

    // ---- V29：TabRow 恢复满宽 ----

    @Test
    fun tabRowFillsFullWidthAgain() {
        // 锚点必须带换行+缩进：裸 "TabRow(" 会先命中调用点 StatsTabRow(pagerState …
        val tabRowStart = routeCode.indexOf("TabRow(\n        modifier")
        assertTrue("必须有独立成块的 TabRow", tabRowStart >= 0)
        val tabRowHead = routeCode.substring(tabRowStart, minOf(tabRowStart + 160, routeCode.length))
        assertTrue("TabRow 必须 fillMaxWidth（恢复原本左右撑满）", tabRowHead.contains("ComposeModifier.fillMaxWidth()"))
        assertFalse("TabRow 不再让位给右缘按钮（weight(1f) 必须移除）", tabRowHead.contains("weight(1f)"))
        assertFalse("tab 行右侧的 IconButton 必须删除", routeCode.contains("IconButton("))
        // 🔴 V47：光断言 TabRow 自身不够—— 外层包装也能把它挤窄。
        // 旧代码在 StatsTabRowItem 里给整行套了 padding(start = 16dp)（照抄列表内缩进口径），
        // V40-A 把 tab 搬出 pager 后它变成纯副作用 ⇒ TabRow 的 fillMaxWidth 填的是
        // 「扣掉 16dp 后的宽度」⇒ 最左侧永远缺一条边距（用户实测发现）。这条就是补那个缝。
        val itemBody = functionBodyOf(routeCode, "private fun StatsTabRowItem(")
        assertFalse(
            "🔴 tab 行外层不得再套 padding：会让 TabRow 的 fillMaxWidth 填不满、最左侧缺一条边距",
            itemBody.contains("padding("),
        )
        assertTrue(
            "tab 行外层应直接把 StatsTabRow 挂在根 Column 上（与榜一 RankRoute 同构）",
            Regex("""private fun StatsTabRowItem\(pagerState: PagerState\) \{\s*\n\s*StatsTabRow\(pagerState = pagerState\)""")
                .containsMatchIn(routeCode),
        )
    }

    // ---- V29：导出入口移到详情面板末尾 ----

    @Test
    fun exportButtonIsLastItemOfDetailPanel() {
        val animated = routeCode.indexOf("AnimatedVisibility(")
        val footprint = routeCode.indexOf("R.string.stats_footprint")
        // 从「足迹」之后找 Button( ⇒ 避开它上面那个 FilledTonalButton（展开/收起）
        val button = routeCode.indexOf("Button(", footprint)
        assertTrue("详情面板用 AnimatedVisibility", animated >= 0)
        assertTrue("导出按钮必须在「足迹」组之后（面板最后一项）", button > footprint)
        assertTrue("按钮点击即打开多选对话框", routeCode.contains("onClick = onExportClick"))
        val buttonBlock = routeCode.substring(button, minOf(button + 420, routeCode.length))
        assertTrue("按钮样式 = filled Button + 满宽", buttonBlock.contains("fillMaxWidth()"))
        assertTrue(
            "按钮图标语义 = 导出（IosShare 向上箭头，不是 Download 下箭头）",
            buttonBlock.contains("Icons.Outlined.IosShare"),
        )
        assertTrue("按钮文案走 stats_export_button", buttonBlock.contains("exportLabel"))
    }

    // ---- V29：一条导出反馈 + 落盘 Uri ----

    @Test
    fun exportFeedbackCarriesMergedMessageAndUris() {
        assertTrue("Route 必须定义宿主接线契约（StatsExportFeedback）", routeCode.contains("typealias StatsExportFeedback = (message: String, uris: List<Uri>) -> Unit"))
        assertTrue("Route 必须把回调透传给导出动作", routeCode.contains("onResult = onShowExportResult"))
        assertTrue("exportOneTable 必须回传落盘 Uri（Q+ 非空）", exportActionSrc.contains("TableExportOutcome(succeeded = true, uri = uri)"))
        assertTrue("saveBitmap 的返回值必须接住", exportActionSrc.contains("val uri = CardImageSaver(appContext).saveBitmap("))
        // 一次点击只回一条：onResult 在 run 里只出现一次（合并消息），旧的两张各回一条必须消失
        assertEquals("整次导出只回调一次 onResult（两张表合并成一条消息）", 1, Regex("onResult\\(").findAll(exportActionSrc).count())
        assertFalse("不再回相册路径 Toast 文案（合并消息不显示具体路径）", exportActionSrc.contains("toast_saved_to_album_path"))
        assertTrue("导出按钮文案走 stats_export_button（旧 stats_export_current 已弃用）", routeCode.contains("R.string.stats_export_button"))
        assertTrue("失败兜底仍走 error_export_failed", exportActionSrc.contains("R.string.error_export_failed"))
    }

    // ---- V29：对话框条目带条数（口径 = 真正导出的条数） ----

    @Test
    fun exportRowCountMatchesSpecFilterPredicate() {
        // 与 CardStatsExport 的 buildCharTableSpec / buildActionTableSpec 同谓词，防两处漂移
        assertTrue(
            "导出表过滤谓词（CardStatsExport）必须仍是 useCount > 0",
            srcOf("CardStatsExport.kt").contains("filter { (it.useCount ?: 0) > 0 }"),
        )
        assertEquals("出场次数为 0 或 null 的牌不计数", 2, exportRowCount(listOf(GcgCard(name = "a", useCount = 3), GcgCard(name = "b", useCount = 0), GcgCard(name = "c", useCount = null), GcgCard(name = "d", useCount = 1))))
    }

    @Test
    fun exportDialogOptionsCarryRowCountWithPlaceholder() {
        assertTrue("角色牌条目 = 带占位符成品文案", routeCode.contains("R.string.stats_export_option_char, charCount"))
        assertTrue("行动牌条目 = 带占位符成品文案", routeCode.contains("R.string.stats_export_option_action, actionCount"))
        assertFalse("条目文案不再直接复用 tab 名（要带条数）", routeCode.contains("R.string.stats_tab_char, charChecked"))
        assertTrue("条数取全量列表口径（导出不跟筛选）", routeCode.contains("exportRowCount(state.charList)"))
        assertTrue("行动牌同样取全量列表（不能用 filteredActionList）", routeCode.contains("exportRowCount(state.actionList)"))
    }

    @Test
    fun exportDialogIsMultiSelectWithSave() {
        assertTrue("必须用 M3 AlertDialog", routeCode.contains("AlertDialog("))
        assertTrue("多选项用 Checkbox 承载", routeCode.contains("Checkbox(checked ="))
        assertTrue("对话框标题走资源", routeCode.contains("R.string.stats_export_dialog_title"))
        assertTrue("positive = 保存", routeCode.contains("R.string.action_save"))
        assertTrue("negative = 取消", routeCode.contains("R.string.action_cancel"))
    }

    @Test
    fun exportDialogDefaultsToAllChecked() {
        // 两个勾选态都必须初始为 true（产品确认：默认两张都导）
        val charDefault = routeCode.contains("var exportCharChecked by remember { mutableStateOf(true) }")
        val actionDefault = routeCode.contains("var exportActionChecked by remember { mutableStateOf(true) }")
        assertTrue("角色牌默认勾选", charDefault)
        assertTrue("行动牌默认勾选", actionDefault)
    }

    @Test
    fun exportDialogClosesExplicitlyAndHintsOnEmptySelection() {
        assertTrue(
            "M3 confirmButton 不自动关窗 ⇒ 保存后必须显式置 false",
            routeCode.contains("exportDialogOpen = false"),
        )
        assertTrue("全不勾时给提示（不静默失败）", routeCode.contains("R.string.stats_export_pick_at_least_one"))
        assertTrue("按勾选项导出（走 StatsExportSelection）", routeCode.contains("exportAction.run(selection)"))
    }

    // ---- 聚合消息口径（注入 LocaleStrings 解析器，无需 Android 环境） ----

    private val feedbackTemplates = mapOf(
        R.string.stats_export_saved_one to "one:%1\$d",
        R.string.stats_export_saved_two to "two:%1\$d",
        R.string.stats_export_partial to "partial:%1\$d/%2\$d",
        R.string.stats_export_failed to "failed",
        R.string.stats_export_failed_detail to "failed:%1\$s",
    )

    @After
    fun detachLocaleStrings() = LocaleStrings.installResolverForTest(null)

    private fun withFeedbackTemplates(block: () -> Unit) {
        LocaleStrings.installResolverForTest { id -> feedbackTemplates[id] }
        block()
    }

    @Test
    fun feedbackMergesTwoSuccessesIntoOneCountMessageWithoutPath() {
        withFeedbackTemplates {
            val text = exportFeedbackText(
                listOf(TableExportOutcome(succeeded = true, uri = null), TableExportOutcome(succeeded = true, uri = null)),
            )
            assertEquals("两张都成功 → saved_two（只报张数，不报路径）", "two:2", text)
        }
    }

    @Test
    fun feedbackSingleSuccessUsesSavedOne() {
        withFeedbackTemplates {
            assertEquals("one:1", exportFeedbackText(listOf(TableExportOutcome(succeeded = true, uri = null))))
        }
    }

    @Test
    fun feedbackAllFailuresStillReportsOneMessage() {
        withFeedbackTemplates {
            val text = exportFeedbackText(
                listOf(
                    TableExportOutcome(succeeded = false, uri = null, error = "boom"),
                    TableExportOutcome(succeeded = false, uri = null, error = "bang"),
                ),
            )
            assertEquals("全失败也要一条消息（带第一张的错误原因，不吞掉）", "failed:boom", text)
            assertEquals(
                "错误原因为空时回落纯失败文案",
                "failed",
                exportFeedbackText(listOf(TableExportOutcome(succeeded = false, uri = null))),
            )
        }
    }

    @Test
    fun feedbackMixedSuccessAndFailureReportsBothCounts() {
        withFeedbackTemplates {
            assertEquals(
                "一成一败 → 两个数字都说清",
                "partial:1/1",
                exportFeedbackText(
                    listOf(
                        TableExportOutcome(succeeded = true, uri = null),
                        TableExportOutcome(succeeded = false, uri = null, error = "boom"),
                    ),
                ),
            )
        }
    }

    // ---- V29：新文案三语齐全 ----

    @Test
    fun newExportDialogStringsExistInAllLocales() {
        val keys = listOf(
            "stats_export_dialog_title",
            "stats_export_pick_at_least_one",
            "action_save",
            "stats_export_button",
            "stats_export_option_char",
            "stats_export_option_action",
            "stats_export_saved_one",
            "stats_export_saved_two",
            "stats_export_partial",
            "stats_export_failed",
            "stats_export_failed_detail",
            "action_view",
        )
        listOf("values", "values-en", "values-zh-rTW").forEach { dir ->
            val file = File("src/main/res/$dir/strings.xml")
            assertTrue("资源文件不存在: ${file.absolutePath}", file.exists())
            val text = file.readText()
            keys.forEach { key ->
                assertTrue("$dir 缺少 string: $key", text.contains("name=\"$key\""))
            }
        }
    }
}
