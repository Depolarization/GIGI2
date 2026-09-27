// V27 卡牌统计页回归锁（纯 JVM，工程无 Robolectric）：
// 1) tab 文案资源与页数一致（stats_tab_char / stats_tab_action，两页）；
// 2) 页码 ↔ 导出表类型映射：第 0 页 = 角色牌（charTable=true）、第 1 页 = 行动牌；
// 3) 表类型 → 子目录/标题资源映射（角色牌 → EXPORT_DIR_CHAR / export_char_title），
//    保证「顶栏单按钮导出当前 tab」导错表的最便宜回归路径被锁住；
// 4) 源文件级：indicator 实时跟随写法（currentPage 插值 + wrapContentSize 解约束）
//    照抄自 RankRoute，与 RankTabIndicatorTest 同构；旧的两枚页内导出按钮必须已删除。
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
        assertTrue("顶栏单按钮语义：contentDescription = 导出当前图表", routeSrc.contains("R.string.stats_export_current"))
    }
}
