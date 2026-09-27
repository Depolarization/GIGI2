// V27 卡牌统计页回归锁（纯 JVM，工程无 Robolectric）：
// 1) tab 文案资源与页数一致（stats_tab_char / stats_tab_action，两页）；
// 2) 页码 ↔ 导出表类型映射：第 0 页 = 角色牌（charTable=true）、第 1 页 = 行动牌；
// 3) 表类型 → 子目录/标题资源映射（角色牌 → EXPORT_DIR_CHAR / export_char_title）；
// 4) 源文件级：indicator 实时跟随写法（currentPage 插值 + wrapContentSize 解约束）
//    照抄自 RankRoute，与 RankTabIndicatorTest 同构；旧的两枚页内导出按钮必须已删除。
// V28-C 追加（对应用户反馈「顶部与列表一起滚 / 导出按钮别占一行 / 点击弹多选框」）：
// 5) 全页只有一个 verticalScroll 容器，顶部卡 + tab 行 + pager 同处其内，pager 不再 fillMaxSize；
// 6) 导出入口挂在 StatsTabRowWithExport（tab 行右缘），旧的独立顶栏空行不得复活；
// 7) 多选对话框：默认全选、positive=保存、跑完显式关窗、全不勾有提示（不静默失败）；
// 8) 新文案三语齐全（values / values-en / values-zh-rTW），杜绝切语言后缺字。
package com.gigi.tcg.ui.screens.cardstats

import com.gigi.tcg.R
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_ACTION
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_CHAR
import java.io.File
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

    private val routeSrc: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsRoute.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        file.readText()
    }

    @Test
    fun indicatorInterpolatesWithCurrentPageNotSettled() {
        assertTrue(
            "indicator 必须用 currentPage + currentPageOffsetFraction 实时插值（跟手）",
            routeSrc.contains("pagerState.currentPage + pagerState.currentPageOffsetFraction"),
        )
        assertFalse(
            "统计页 indicator 不应引入 settledPage（那是 RankRoute 数据加载的语义，与跟随无关）",
            routeSrc.contains("settledPage"),
        )
    }

    @Test
    fun indicatorUnwrapsParentConstraints() {
        // 缺 wrapContentSize 时 TabRow 的固定宽度约束会把 width() 夹成整行宽（V7A 回归）
        val fill = routeSrc.indexOf(".fillMaxWidth()")
        val wrap = routeSrc.indexOf("wrapContentSize(Alignment.BottomStart)")
        val width = routeSrc.indexOf(".width(rightDp - leftDp)")
        assertTrue("indicator 链必须有 fillMaxWidth()", fill >= 0)
        assertTrue("indicator 链必须有 wrapContentSize(Alignment.BottomStart)", wrap >= 0)
        assertTrue("wrapContentSize 必须在显式 width 之前", wrap in 0 until width)
    }

    @Test
    fun pagerBackedTabSwitching() {
        assertTrue("必须用 HorizontalPager 承载左右滑动", routeSrc.contains("HorizontalPager(state = pagerState"))
        assertTrue("pager 页数取 STATS_TAB_COUNT", routeSrc.contains("pageCount = { STATS_TAB_COUNT }"))
    }

    @Test
    fun oldDualExportButtonsRemoved() {
        assertFalse("旧的「导出角色牌」页内按钮必须删除", routeSrc.contains("stats_export_char"))
        assertFalse("旧的「导出行动牌」页内按钮必须删除", routeSrc.contains("stats_export_action"))
        assertTrue("导出入口 contentDescription = 导出当前图表", routeSrc.contains("R.string.stats_export_current"))
    }

    // ---- V28-C：顶部与列表一体滚动 ----

    @Test
    fun wholePageSharesOneVerticalScrollContainer() {
        // 页面里只允许 verticalScroll(rememberScrollState()) 一处（外层统一容器）。
        // 列表页各自再挂一份 ⇒ 变成嵌套抢手势、头部依旧钉死，正是本次要修的割裂。
        val scrollUsages = Regex("verticalScroll\\(rememberScrollState\\(\\)\\)").findAll(routeSrc).count()
        assertEquals("全页只能有一个纵向滚动容器（外层 Column）", 1, scrollUsages)
    }

    @Test
    fun headerAndTabsLiveInsidePullToRefreshScrollable() {
        val pull = routeSrc.indexOf("PullToRefreshBox(")
        val scroll = routeSrc.indexOf("verticalScroll(rememberScrollState())")
        val infoCard = routeSrc.indexOf("PlayerInfoCard(summary")
        val tabRow = routeSrc.indexOf("StatsTabRowWithExport(")
        val pager = routeSrc.indexOf("HorizontalPager(state = pagerState")
        assertTrue("必须有 PullToRefreshBox（下拉刷新仍可用）", pull >= 0)
        assertTrue("滚动容器必须在 PullToRefreshBox 之后（其 content 内）", scroll > pull)
        assertTrue("顶部信息卡要在滚动容器内部（与列表一起滚）", infoCard > scroll)
        assertTrue("tab 行要在滚动容器内部（与列表一起滚）", tabRow in scroll until pager)
    }

    @Test
    fun pagerDoesNotFillViewportHeight() {
        // pager 若 fillMaxSize：无限高约束下算不出确定高、且把头部顶出可视区（一体滚动失效）
        assertFalse(
            "HorizontalPager 不得 fillMaxSize",
            routeSrc.contains("HorizontalPager(state = pagerState, modifier = ComposeModifier.fillMaxSize())"),
        )
        assertTrue(
            "HorizontalPager 只约束宽度、按内容高",
            routeSrc.contains("HorizontalPager(state = pagerState, modifier = ComposeModifier.fillMaxWidth())"),
        )
    }

    // ---- V28-C：导出按钮并入 tab 行 ----

    @Test
    fun exportButtonSharesTabRowLine() {
        val helper = routeSrc.indexOf("private fun StatsTabRowWithExport(")
        val downloadIcon = routeSrc.indexOf("Icons.Outlined.Download")
        assertTrue("导出入口必须在 StatsTabRowWithExport 内（tab 行右缘）", helper in 0 until downloadIcon)
        // TabRow 只吃 weight(1f) 的剩余宽度（不占满整行）⇒ 按钮不会压到最后一个 tab；
        // 断言取 TabRow( 之后的一小段，避免被缩进/换行改动误伤
        val tabRowStart = routeSrc.indexOf("TabRow(")
        val tabRowHead = routeSrc.substring(tabRowStart, minOf(tabRowStart + 160, routeSrc.length))
        assertTrue("TabRow 必须挂 weight(1f) 让位给右缘按钮", tabRowHead.contains("ComposeModifier.weight(1f)"))
        assertTrue("按钮与最后一个 tab 之间要有显式间距", routeSrc.contains("Spacer(ComposeModifier.width(TabExportGap))"))
    }

    @Test
    fun standaloneExportTopRowRemoved() {
        assertFalse("旧的页内顶栏空行注释不得残留", routeSrc.contains("顶栏行（页内）"))
        assertFalse("不得再有单按钮独立 Row（旧写法：onClick = exportAction.run）", routeSrc.contains("onClick = exportAction.run"))
    }

    // ---- V28-C：多选导出对话框 ----

    @Test
    fun exportDialogIsMultiSelectWithSave() {
        assertTrue("必须用 M3 AlertDialog", routeSrc.contains("AlertDialog("))
        assertTrue("多选项用 Checkbox 承载", routeSrc.contains("Checkbox(checked ="))
        assertTrue("两项复用 tab 文案（角色牌 / 行动牌）", routeSrc.contains("R.string.stats_tab_char, charChecked"))
        assertTrue("对话框标题走资源", routeSrc.contains("R.string.stats_export_dialog_title"))
        assertTrue("positive = 保存", routeSrc.contains("R.string.action_save"))
        assertTrue("negative = 取消", routeSrc.contains("R.string.action_cancel"))
    }

    @Test
    fun exportDialogDefaultsToAllChecked() {
        // 两个勾选态都必须初始为 true（产品确认：默认两张都导）
        val charDefault = routeSrc.contains("var exportCharChecked by remember { mutableStateOf(true) }")
        val actionDefault = routeSrc.contains("var exportActionChecked by remember { mutableStateOf(true) }")
        assertTrue("角色牌默认勾选", charDefault)
        assertTrue("行动牌默认勾选", actionDefault)
    }

    @Test
    fun exportDialogClosesExplicitlyAndHintsOnEmptySelection() {
        assertTrue(
            "M3 confirmButton 不自动关窗 ⇒ 保存后必须显式置 false",
            routeSrc.contains("exportDialogOpen = false"),
        )
        assertTrue("全不勾时给 Toast 提示（不静默失败）", routeSrc.contains("R.string.stats_export_pick_at_least_one"))
        assertTrue("按勾选项导出（走 StatsExportSelection）", routeSrc.contains("exportAction.run(selection)"))
    }

    // ---- V28-C：新文案三语齐全 ----

    @Test
    fun newExportDialogStringsExistInAllLocales() {
        val keys = listOf(
            "stats_export_dialog_title",
            "stats_export_pick_at_least_one",
            "action_save",
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
