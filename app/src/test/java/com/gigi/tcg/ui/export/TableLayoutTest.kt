package com.gigi.tcg.ui.export

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.math.ceil

/**
 * 布局模型的回归锁（纯 JVM，无 Robolectric）。
 * 假 measurer：CJK/全角（code ≥ 0x2E80）= 1.0×字号，其余 = 0.55×字号；真机字宽只小不大 ⇒ 保守上界。
 * 核心不变量：**列宽 = 该列 max(表头, 各单元格) + 内边距**，文本永不折行、永不省略。
 */
class TableLayoutTest {

    private fun fakeMeasure(text: String, textSizePx: Float): Float =
        text.sumOf { c ->
            if (c.code >= 0x2E80) textSizePx.toDouble() else textSizePx * 0.55
        }.toFloat()

    private val measurer = TextMeasurer { text, textSizePx -> fakeMeasure(text, textSizePx) }

    private fun measure20(text: String) = fakeMeasure(text, TEXT_SIZE_BODY_PX)

    private val charColumns = listOf(
        TableColumn("#", CellAlign.START),
        TableColumn("名称", CellAlign.CENTER),
        TableColumn("出场数", CellAlign.CENTER, 100),
        TableColumn("出场率%", CellAlign.CENTER, 111),
        TableColumn("胜率%", CellAlign.CENTER, 111),
        TableColumn("胜局数", CellAlign.CENTER, 100),
    )

    private val actionColumns = listOf(
        TableColumn("#", CellAlign.START),
        TableColumn("类别", CellAlign.CENTER, 90),
        TableColumn("名称", CellAlign.CENTER),
        TableColumn("使用次数", CellAlign.CENTER, 100),
        TableColumn("使用率%", CellAlign.CENTER, 111),
    )

    private fun spec(
        rowCount: Int,
        columns: List<TableColumn> = charColumns,
        cell: (row: Int, col: Int) -> String = { r, _ -> "v$r" },
        levelText: String? = "牌手等级 45",
        badges: List<String> = listOf("角色牌 147/147", "行动牌 941/941", "共进行 3493 场游戏", "胜率 58.9%"),
        signature: String = "暂无签名",
        exportDateText: String? = "2026-09-27",
    ) = TableSpec(
        title = "角色牌数据",
        nickname = "Clin - 110526730",
        levelText = levelText,
        badges = badges,
        signature = signature,
        columns = columns,
        rows = List(rowCount) { r -> List(columns.size) { c -> cell(r, c) } },
        exportDateText = exportDateText,
    )

    private fun layoutOf(s: TableSpec, threshold: Int = 60) = computeTableLayout(s, measurer, threshold)

    // ---- 列宽模型 ----

    // 1. 精确断言：# 列 = max(表头 11, 最宽序号 33) + 首列内边距(15+10)；名称列 = 最长牌名 + 20
    @Test
    fun columnWidthIsMaxOfHeaderAndCellsPlusPadding() {
        val layout = layoutOf(spec(rowCount = 147, cell = { r, c -> if (c == 1) "行秋" else (r + 1).toString() }))
        assertEquals(ceil(measure20("147")).toInt() + FIRST_CELL_LEFT_PADDING_PX + CELL_PADDING_PX, layout.columnWidth[0])
        assertEquals(58, layout.columnWidth[0])
        assertEquals(ceil(measure20("行秋")).toInt() + 2 * CELL_PADDING_PX, layout.columnWidth[1])
        assertEquals(60, layout.columnWidth[1])
        // 表头撑开的列：单元格更窄时 minWidthPx 兜底（出场数表头 80 + 20 = 100）
        assertEquals(100, layout.columnWidth[2])
        assertEquals(111, layout.columnWidth[3])
    }

    // 2. 🔴 永不折行的回归锁：名称列宽 ≥ 最长角色名的测量宽 + 内边距（用户实测「阿佩普的绿洲守望者」折行）
    @Test
    fun nameColumnAlwaysFitsTheLongestCardNameWithoutWrapping() {
        val longName = "愚人众·火之债务处理人"
        val layout = layoutOf(
            spec(rowCount = 3, cell = { r, c -> if (c == 1) if (r == 0) longName else "行秋" else "1" })
        )
        val nameWidth = layout.columnWidth[1]
        assertTrue(
            "名称列 $nameWidth 放不下「$longName」（测量 ${measure20(longName)} + 内边距 20）",
            nameWidth >= measure20(longName) + 2 * CELL_PADDING_PX,
        )
        assertEquals(ceil(measure20(longName)).toInt() + 2 * CELL_PADDING_PX, nameWidth)
    }

    // 3. 🔴 永不省略号的回归锁：行动牌「使用率%」列宽 ≥ 该表头测量宽（真机曾被截成「使用率…」）
    @Test
    fun actionUseRateHeaderIsNeverEllipsized() {
        val layout = layoutOf(
            spec(rowCount = 100, columns = actionColumns, cell = { _, c -> listOf("1", "装备牌", "顺风", "12", "1.500")[c] })
        )
        val rateWidth = layout.columnWidth[4]
        assertTrue(
            "「使用率%」列宽 $rateWidth < 表头测量宽 ${measure20("使用率%")}",
            rateWidth >= measure20("使用率%"),
        )
        // 类别列同理：单元格「装备牌」60 + 内边距 20 = 80，但 minWidthPx=90 兜底
        assertEquals(90, layout.columnWidth[1])
        assertEquals(ceil(measure20("顺风")).toInt() + 2 * CELL_PADDING_PX, layout.columnWidth[2])
    }

    // 4. 列宽之和 == bandWidthPx，且 columnX 是它的前缀和（末列右缘正好等于栏宽）
    @Test
    fun columnXIsPrefixSumOfColumnWidths() {
        val layout = layoutOf(spec(rowCount = 5))
        assertEquals(layout.bandWidthPx, layout.columnWidth.sum())
        assertEquals(0, layout.columnX.first())
        val last = layout.columnWidth.lastIndex
        assertEquals(layout.bandWidthPx, layout.columnX[last] + layout.columnWidth[last])
    }

    // ---- 画布尺寸 ----

    // 5. 单栏画布宽 == 表格内容宽 + 2×(24+2)
    @Test
    fun singleBandCanvasWidthIsTableWidthPlusPageMarginsAndBorder() {
        val layout = layoutOf(spec(rowCount = 60))
        assertEquals(1, layout.columnsPerBand)
        assertEquals(layout.bandWidthPx, layout.tableWidthPx)
        assertEquals(layout.bandWidthPx + 2 * (PAGE_MARGIN_PX + TABLE_BORDER_PX), layout.widthPx)
        assertEquals(layout.bandWidthPx + 52, layout.widthPx)
    }

    // 6. 双栏画布宽 == 2×单栏内容宽 + 15 栏间距 + 52；两栏共用同一套全局列宽 ⇒ 逐项等宽
    @Test
    fun twoBandsShareGlobalColumnWidthsAndCanvasAddsBandGap() {
        // 第 1 栏行里有超长牌名，第 2 栏行里全是短值：全局取 max ⇒ 两栏仍然等宽
        val s = spec(
            rowCount = 120,
            columns = actionColumns,
            cell = { r, c -> if (c == 2 && r == 5) "元素共鸣：交织之火" else listOf("1", "装备牌", "顺风", "12", "1.500")[c] },
        )
        val layout = layoutOf(s)
        assertEquals(2, layout.columnsPerBand)
        assertEquals(60, layout.rowsPerBand)
        assertEquals(ceil(120 / 2.0).toInt(), layout.rowsPerBand)
        assertEquals(2 * layout.bandWidthPx, layout.tableWidthPx)
        assertEquals(2 * layout.bandWidthPx + BAND_GAP_PX + 52, layout.widthPx)
        // 两栏读同一份 columnWidth（等宽的结构性保证）：第 2 栏左缘 = 26 + 单栏宽 + 15
        assertEquals(ceil(measure20("元素共鸣：交织之火")).toInt() + 2 * CELL_PADDING_PX, layout.columnWidth[2])
        val band2Left = PAGE_MARGIN_PX + TABLE_BORDER_PX + layout.bandWidthPx + BAND_GAP_PX
        assertEquals(band2Left + layout.bandWidthPx + TABLE_BORDER_PX, layout.widthPx - PAGE_MARGIN_PX)
    }

    // 7. 高度 == 页眉块高 + 110(banner+mb) + 2 + 50 + 行数×50 + 2 + 111(页脚)；日期已挪到 logo 下方，不占竖向流
    @Test
    fun heightFollowsTheMeasuredVerticalStack() {
        val s = spec(rowCount = 5)
        val layout = layoutOf(s)
        val expected = computeHeaderBlockHeight(s) + 110 + 2 + 50 + 5 * ROW_HEIGHT_PX + 2 + 111
        assertEquals(expected, layout.heightPx)
    }

    // 7b. 等级行为 null ⇒ 整行连同间距都不占（25 行高 + 25 下边距）
    @Test
    fun nullLevelTextRemovesItsWholeLineHeight() {
        val withLevel = spec(rowCount = 2)
        val withoutLevel = withLevel.copy(levelText = null)
        assertEquals(50, computeHeaderBlockHeight(withLevel) - computeHeaderBlockHeight(withoutLevel))
        assertEquals(50, layoutOf(withLevel).heightPx - layoutOf(withoutLevel).heightPx)
    }

    // 7c. 🔴 旧版日期是 banner 下方一条独立行（行高 34，有/无日期差 34px 留白）。
    //     现在日期绝对定位在 logo 正下方 ⇒ 有无日期都必须同高，别把占位漏回来。
    @Test
    fun dateTextNeverReservesVerticalSpace() {
        val withDate = spec(rowCount = 2)
        val withoutDate = withDate.copy(exportDateText = null)
        assertEquals(layoutOf(withDate).heightPx, layoutOf(withoutDate).heightPx)
    }

    // ---- 双栏阈值口径（沿用旧行为：>阈值才双栏，ceil(n/2) 分栏）----

    @Test
    fun rowsEqualToThresholdStaySingleColumn() {
        val layout = layoutOf(spec(rowCount = 60))
        assertEquals(1, layout.columnsPerBand)
        assertEquals(60, layout.rowsPerBand)
    }

    @Test
    fun oneRowOverThresholdSplitsToTwoBands() {
        val layout = layoutOf(spec(rowCount = 61))
        assertEquals(2, layout.columnsPerBand)
        assertEquals(31, layout.rowsPerBand) // ceil(61/2)，第 2 栏取剩余 30
        assertEquals(30, 61 - layout.rowsPerBand)
    }

    @Test
    fun emptyRowsYieldValidLayoutWithZeroRowsPerBand() {
        val layout = layoutOf(spec(rowCount = 0))
        assertEquals(0, layout.rowsPerBand)
        assertEquals(1, layout.columnsPerBand)
        // 无行时列宽全部由表头/minWidthPx 决定，画布仍有合法正尺寸
        assertTrue(layout.widthPx > 0 && layout.heightPx > 0)
        assertEquals(computeHeaderBlockHeight(spec(rowCount = 0)) + 110 + 2 + 50 + 2 + 111, layout.heightPx)
    }

    // ---- 入参校验 ----

    @Test
    fun mismatchedRowLengthThrowsWithRowIndex() {
        val bad = spec(rowCount = 2).copy(rows = listOf(List(6) { "x" }, List(5) { "x" }))
        val e = assertThrows(IllegalArgumentException::class.java) { layoutOf(bad) }
        assertTrue("message should mention row number: ${e.message}", e.message!!.contains("#1"))
    }

    @Test
    fun emptyColumnsThrow() {
        val bad = spec(rowCount = 1).copy(columns = emptyList(), rows = emptyList())
        assertThrows(IllegalArgumentException::class.java) { layoutOf(bad) }
    }

    @Test
    fun nonPositiveThresholdThrows() {
        assertThrows(IllegalArgumentException::class.java) { computeTableLayout(spec(rowCount = 1), measurer, 0) }
    }

    // ---- 内存闸（阈值不变，画布宽改为表格推出）----

    @Test
    fun bitmapConfigDowngradesOverMemoryBudget() {
        assertEquals(Bitmap.Config.ARGB_8888, chooseBitmapConfig(1080, 200))
        // 1080 × 20000 × 4B = 86.4MB > 64MB
        assertEquals(Bitmap.Config.RGB_565, chooseBitmapConfig(1080, 20_000))
        // 阈值边界内保持 ARGB：1080 × 15000 × 4B ≈ 61MB < 64MB
        assertEquals(Bitmap.Config.ARGB_8888, chooseBitmapConfig(1080, 15_000))
    }

    // 941 行行动牌双栏（极端真实用例）：新模型下画布由表格推出，RGB_565 仍 < 100MB ⇒ 必须放行
    @Test
    fun largestRealTableStillPassesMemoryBudget() {
        val layout = layoutOf(
            spec(
                rowCount = 941,
                columns = actionColumns,
                cell = { r, c -> listOf((r + 1).toString(), "装备牌", "裁断", "999999", "100.000")[c] },
            )
        )
        assertEquals(2, layout.columnsPerBand)
        assertEquals(471, layout.rowsPerBand)
        val config = chooseBitmapConfig(layout.widthPx, layout.heightPx)
        checkRenderMemoryBudget(layout.widthPx, layout.heightPx, config)
        assertTrue(
            "${layout.widthPx}x${layout.heightPx} $config = ${bitmapMemoryBytes(layout.widthPx, layout.heightPx, config)} 应 < 100MB",
            bitmapMemoryBytes(layout.widthPx, layout.heightPx, config) < 100L * 1024 * 1024,
        )
    }

    @Test
    fun renderBudgetRejectsAbsurdlyTallCanvas() {
        // 1444 × 50000 × 2B ≈ 137MB > 100MB ⇒ 可读 IOException 而非 OOM
        assertThrows(IOException::class.java) {
            checkRenderMemoryBudget(1444, 50_000, Bitmap.Config.RGB_565)
        }
    }

    @Test
    fun memoryByteMathMatchesConfigDepth() {
        assertEquals(1000L * 2000 * 2, bitmapMemoryBytes(1000, 2000, Bitmap.Config.RGB_565))
        assertEquals(1000L * 2000 * 4, bitmapMemoryBytes(1000, 2000, Bitmap.Config.ARGB_8888))
    }

    // ---- 版式常量回归锁（照抄参考图实测；改回旧值会让表格重新变得又扁又挤）----

    @Test
    fun sizeConstantsArePinned() {
        assertEquals(50, ROW_HEIGHT_PX)
        assertEquals(50, HEADER_HEIGHT_PX)
        assertEquals(10, CELL_PADDING_PX)
        assertEquals(15, FIRST_CELL_LEFT_PADDING_PX)
        assertEquals(24, PAGE_MARGIN_PX)
        assertEquals(2, TABLE_BORDER_PX)
        assertEquals(15, BAND_GAP_PX)
        // 表格本体 1:1 照抄参考图：正文与表头都是 20px（旧 22px 表头是拉伸时代的产物）
        assertEquals(20f, TEXT_SIZE_BODY_PX, 0f)
        assertEquals(20f, TEXT_SIZE_HEADER_PX, 0f)
    }

    // ---- 日期锚点：logo 正下方、右缘与 logo 对齐（回归锁）----

    // 日期与 logo 右缘共用同一锚点（画布宽 − 40），不再贴着画布右缘 − 24 悬空
    @Test
    fun dateAnchorsToLogoBottomRightNotCanvasEdge() {
        // 竖向：logo 底 147 + 组内间距 8 ⇒ 日期顶 155，仍远在分隔虚线（页眉块底附近）之上
        assertEquals(147, LOGO_TOP_PX + LOGO_HEIGHT_PX)
        assertTrue("logo 底 + 间距必须落在页眉块内，否则压到分隔线", DATE_BELOW_LOGO_GAP_PX in 1..13)
        // 横向：logo 右缘距画布 40 ⇒ 日期右缘同理，比旧版（距右缘 24）更靠内，与 logo 成一列
        assertEquals(40, LOGO_RIGHT_INSET_PX)
        assertTrue(
            "日期宽度 + 间距不该超过 logo 宽（否则左溢出到 logo 之外）",
            fakeMeasure("2026-09-27", DATE_TEXT_SIZE_PX) + DATE_BELOW_LOGO_GAP_PX < LOGO_WIDTH_PX,
        )
    }

    // ---- ellipsize：表格本体已不用（nowrap），但最近对局卡片渲染器仍在用，行为必须保持 ----

    @Test
    fun ellipsizeReturnsOriginalWhenItFits() {
        val measure: (String) -> Float = { it.length * 10f }
        assertEquals("abcdef", ellipsize("abcdef", maxWidthPx = 60f, measure = measure))
        assertEquals("", ellipsize("", maxWidthPx = 5f, measure = measure))
    }

    @Test
    fun ellipsizeTruncatesToFitLimit() {
        val measure: (String) -> Float = { it.length * 10f }
        val result = ellipsize("abcdefghij", maxWidthPx = 50f, measure = measure)
        assertTrue("must fit: '$result'", measure(result) <= 50f)
        assertTrue("must mark truncation: '$result'", result.endsWith("…"))
        assertNotEquals("abcdefghij", result)
    }

    @Test
    fun ellipsizeZeroWidthReturnsEmpty() {
        val measure: (String) -> Float = { it.length * 10f }
        assertEquals("", ellipsize("abc", maxWidthPx = 0f, measure = measure))
    }
}
