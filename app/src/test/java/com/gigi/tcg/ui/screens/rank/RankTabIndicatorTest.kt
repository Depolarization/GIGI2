// V7G 回归锁：排行榜 TabRow 的自定义 indicator 必须与官方 tabIndicatorOffset 的
// modifier 链同构（javap material3 1.3.2 实证：fillMaxWidth → wrapContentSize(BottomStart)
// → offset{} → width）。缺 wrapContentSize 时，TabRow 传入的固定宽度约束会把
// Modifier.width()（enforceIncoming=true）constrainWidth 夹到整行宽
// ⇒ 手指拖动过程中 indicator 横贯整个 TabRow（V7A 引入、用户真机反馈的回归）。
// 同时锁定"连续页码插值"（currentPage + currentPageOffsetFraction，双向跟手），
// 防止退回 coerceIn(0f, 1f)（反向滑动的负 fraction 被夹成 0 ⇒ indicator 跳变），
// 也防止 settledPage 数据加载语义（硬约束 1）被顺手改掉。
package com.gigi.tcg.ui.screens.rank

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RankTabIndicatorTest {

    private val src: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/rank/RankRoute.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        file.readText()
    }

    /**
     * indicator lambda 区间：从 `indicator = {` 起到其后第一个换行 + 12 空格缩进的 `},`
     * （TabRow 实参列表中 indicator 块的收尾；块内嵌套括号均更深层缩进，不会误截断）。
     */
    private fun indicatorRegion(): String {
        val start = src.indexOf("indicator = {")
        assertTrue("找不到 indicator = { 块", start >= 0)
        val end = src.indexOf("\n            },", start)
        assertTrue("indicator 块未闭合（收尾应为换行+12空格的 \"},\"）", end > start)
        return src.substring(start, end)
    }

    /** 不变量 1（核心）：wrapContentSize(Alignment.BottomStart) 解开父约束，缺了就回归"覆盖整个 tab" */
    @Test
    fun indicatorUnwrapsParentConstraints() {
        val region = indicatorRegion()
        assertTrue(
            "indicator 必须有 wrapContentSize(Alignment.BottomStart)（否则 width() 被 TabRow 固定宽度约束夹成整行宽）",
            region.contains("wrapContentSize(Alignment.BottomStart)"),
        )
    }

    /** 不变量 2：fillMaxWidth() 在 wrapContentSize 之前（与官方链顺序一致） */
    @Test
    fun fillMaxWidthPrecedesWrapContentSize() {
        val region = indicatorRegion()
        val fill = region.indexOf("fillMaxWidth()")
        val wrap = region.indexOf("wrapContentSize(Alignment.BottomStart)")
        assertTrue("indicator 必须有 fillMaxWidth()", fill >= 0)
        assertTrue("wrapContentSize 必须存在", wrap >= 0)
        assertTrue("fillMaxWidth() 必须出现在 wrapContentSize 之前", fill < wrap)
    }

    /** 不变量 3：wrapContentSize 在 .width( 之前——顺序错了约束照样被夹 */
    @Test
    fun wrapContentSizePrecedesWidth() {
        val region = indicatorRegion()
        val wrap = region.indexOf("wrapContentSize(Alignment.BottomStart)")
        val width = region.indexOf(".width(")
        assertTrue("indicator 必须有显式 .width( 插值宽度", width >= 0)
        assertTrue("wrapContentSize 必须出现在 .width( 之前", wrap in 0 until width)
    }

    /** 不变量 4：不允许把 fraction 单独 coerceIn(0f, 1f)（反向滑动负值被夹成 0 ⇒ 跳变回归锁） */
    @Test
    fun noClampOfRawOffsetFraction() {
        val region = indicatorRegion()
        assertTrue(
            "不应再出现 currentPageOffsetFraction.coerceIn(0f, 1f)（应改连续页码插值，支持双向跟手）",
            !region.contains("currentPageOffsetFraction.coerceIn(0f, 1f)"),
        )
    }

    /** 不变量 5：仍是用 currentPageOffsetFraction 实时插值的方案，未被换回官方静态 offset */
    @Test
    fun indicatorStillInterpolatesWithOffsetFraction() {
        val region = indicatorRegion()
        assertTrue(
            "indicator 应仍用 pagerState.currentPageOffsetFraction 插值（跟手语义不能丢）",
            region.contains("pagerState.currentPageOffsetFraction"),
        )
        assertTrue(
            "连续页码 = currentPage + currentPageOffsetFraction",
            region.contains("pagerState.currentPage + pagerState.currentPageOffsetFraction"),
        )
    }

    /** 不变量 6（文件级，硬约束 1）：settledPage → selectTab 的数据加载语义不许被动到 */
    @Test
    fun dataLoadingStillUsesSettledPage() {
        assertTrue(
            "snapshotFlow { pagerState.settledPage } 必须保留（数据加载只在落定后触发）",
            src.contains("snapshotFlow { pagerState.settledPage }"),
        )
    }
}
