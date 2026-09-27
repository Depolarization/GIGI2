package com.gigi.tcg.ui.export

import android.graphics.Bitmap
import com.gigi.tcg.ui.screens.cardstats.EXPORT_ACTION_IMAGE_WIDTH_PX
import com.gigi.tcg.ui.screens.cardstats.EXPORT_CHAR_IMAGE_WIDTH_PX
import com.gigi.tcg.ui.screens.cardstats.EXPORT_TWO_COLUMN_THRESHOLD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TableLayoutTest {

    private fun columns(n: Int, nameWeight: Float = 4f) = listOf(
        TableColumn("#", 1f, CellAlign.START),
        TableColumn("名称", nameWeight, CellAlign.CENTER),
        TableColumn("出场数", 1.5f, CellAlign.CENTER),
        TableColumn("出场率%", 1.5f, CellAlign.CENTER),
        TableColumn("胜率%", 1.5f, CellAlign.CENTER),
    ).let { if (n == 5) it else it + listOf(TableColumn("胜局数", 1.5f, CellAlign.CENTER)) }

    private fun spec(
        rowCount: Int,
        columnCount: Int = 5,
        subtitle: String? = "Clin - 110526730",
        badges: List<String> = listOf("共进行 3493 场游戏"),
    ) = TableSpec(
        title = "行动牌数据",
        subtitle = subtitle,
        badges = badges,
        columns = columns(columnCount),
        rows = List(rowCount) { List(columnCount) { "v$it" } },
    )

    // 1. 单栏：rows.size == threshold
    @Test
    fun rowsEqualToThresholdStaySingleColumn() {
        val layout = computeTableLayout(spec(rowCount = 60), widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60)
        assertEquals(1, layout.columnsPerBand)
        assertEquals(60, layout.rowsPerBand)
    }

    // 2. 双栏：rows.size == threshold + 1
    @Test
    fun oneRowOverThresholdSplitsToTwoBands() {
        val layout = computeTableLayout(spec(rowCount = 61), widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60)
        assertEquals(2, layout.columnsPerBand)
        assertEquals(31, layout.rowsPerBand) // ceil(61/2)
    }

    // 3. 双栏时两栏行数差 <= 1（奇数行 61：左 31 右 30）
    @Test
    fun oddRowCountBandsDifferByAtMostOne() {
        val rows = 61
        val layout = computeTableLayout(spec(rowCount = rows), widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60)
        val left = layout.rowsPerBand
        val right = rows - left
        assertEquals(31, left)
        assertEquals(30, right)
        assertTrue("bands differ by more than 1: $left vs $right", kotlin.math.abs(left - right) <= 1)
    }

    // 4a. 单栏列宽总和精确 == 画布宽 − 2×页边距（1444 − 52 = 1392）；页边距是压缩表格来的
    @Test
    fun singleColumnWidthsSumExactlyToBandWidth() {
        val layout = computeTableLayout(spec(rowCount = 3), widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60)
        assertEquals(EXPORT_ACTION_IMAGE_WIDTH_PX - 2 * PAGE_MARGIN_PX, layout.columnWidth.sum())
        assertEquals(EXPORT_ACTION_IMAGE_WIDTH_PX - 2 * PAGE_MARGIN_PX, layout.bandWidthPx)
        assertEquals(layout.bandWidthPx, layout.columnWidth.sum())
    }

    // 4b. 双栏时每栏 = 画布/栏数 − 2×页边距（722 − 52 = 670）；columnX 只描述第一栏（相对本栏左边缘）
    @Test
    fun twoColumnBandTakesSlotMinusMarginsAndXStartsAtZero() {
        val layout = computeTableLayout(spec(rowCount = 100), widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60)
        val bandWidthPx = EXPORT_ACTION_IMAGE_WIDTH_PX / 2 - 2 * PAGE_MARGIN_PX
        assertEquals(bandWidthPx, layout.columnWidth.sum())
        assertEquals(bandWidthPx, layout.bandWidthPx)
        assertEquals(0, layout.columnX.first())
        // columnX 最后一列 + 其宽度 == 栏宽（不含第二栏偏移——偏移由绘制端加 页边距 + 栏槽位 722）
        val last = layout.columnX.size - 1
        assertEquals(bandWidthPx, layout.columnX[last] + layout.columnWidth[last])
        assertTrue(layout.columnX.max() < bandWidthPx)
    }

    // 4c. 🔴 页边距契约（V26）：表格宽 = 槽位 − 2×26px，两表的画布宽不同、边距相同
    @Test
    fun pageMarginsAreCompressedNotCanvasWidened() {
        val charLayout = computeTableLayout(
            spec(rowCount = 3), widthPx = EXPORT_CHAR_IMAGE_WIDTH_PX, twoColumnThreshold = 60,
        )
        assertEquals(EXPORT_CHAR_IMAGE_WIDTH_PX - 2 * PAGE_MARGIN_PX, charLayout.bandWidthPx) // 角色牌单栏 770
        val actionLayout = computeTableLayout(
            spec(rowCount = 100), widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60,
        )
        assertEquals(EXPORT_ACTION_IMAGE_WIDTH_PX / 2 - 2 * PAGE_MARGIN_PX, actionLayout.bandWidthPx) // 行动牌双栏每栏 670
    }

    // 5. 最小宽度保护：weight 极小的列不被压瘪
    @Test
    fun tinyWeightColumnGetsMinimumWidth() {
        val cols = listOf(
            TableColumn("名称", 100f, CellAlign.CENTER),
            TableColumn("#", 0.01f, CellAlign.START),
        )
        val s = TableSpec("t", null, emptyList(), cols, listOf(listOf("a", "b")))
        val layout = computeTableLayout(s, widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60)
        assertTrue(
            "min-width protection failed: ${layout.columnWidth}",
            layout.columnWidth[1] >= MIN_COLUMN_WIDTH_PX,
        )
        assertEquals(EXPORT_ACTION_IMAGE_WIDTH_PX - 2 * PAGE_MARGIN_PX, layout.columnWidth.sum())
    }

    // 6. heightPx 手算比对（V8I-B：titleHeightPx 含表头前留白，全部引用常量不写死数字）
    @Test
    fun heightMatchesManualComputation() {
        val layout = computeTableLayout(
            spec(rowCount = 5, badges = listOf("b1", "b2")),
            widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX,
            twoColumnThreshold = 60,
        )
        val expectedTitle =
            TITLE_LINE_HEIGHT_PX + SUBTITLE_LINE_HEIGHT_PX + BADGES_LINE_HEIGHT_PX + HEADER_TOP_GAP_PX
        assertEquals(expectedTitle, layout.titleHeightPx)
        assertEquals(HEADER_HEIGHT_PX, layout.headerHeightPx)
        assertEquals(ROW_HEIGHT_PX, layout.rowHeightPx)
        val expectedHeight =
            expectedTitle + HEADER_HEIGHT_PX + 5 * ROW_HEIGHT_PX + BOTTOM_PADDING_PX
        assertEquals(expectedHeight, layout.heightPx)
    }

    // 7. 空 rows 也是合法布局
    @Test
    fun emptyRowsYieldValidLayoutWithZeroRowsPerBand() {
        val layout = computeTableLayout(spec(rowCount = 0), widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60)
        assertEquals(0, layout.rowsPerBand)
        assertEquals(1, layout.columnsPerBand)
        assertEquals(EXPORT_ACTION_IMAGE_WIDTH_PX - 2 * PAGE_MARGIN_PX, layout.columnWidth.sum())
        val expectedHeight =
            layout.titleHeightPx + layout.headerHeightPx + 0 * layout.rowHeightPx + BOTTOM_PADDING_PX
        assertEquals(expectedHeight, layout.heightPx)
    }

    // 8. 行长 != columns.size => IllegalArgumentException（信息含行号）
    @Test
    fun mismatchedRowLengthThrowsWithRowIndex() {
        val cols = columns(5)
        val bad = TableSpec(
            title = "t",
            subtitle = null,
            badges = emptyList(),
            columns = cols,
            rows = listOf(
                List(5) { "x" },
                List(4) { "x" }, // 第 1 行少一格
            ),
        )
        val e = assertThrows(IllegalArgumentException::class.java) {
            computeTableLayout(bad, widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60)
        }
        assertTrue("message should mention row number: $e.message", e.message!!.contains("#1"))
    }

    // 9. columns 为空 => IllegalArgumentException
    @Test
    fun emptyColumnsThrow() {
        val bad = TableSpec("t", null, emptyList(), emptyList(), emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            computeTableLayout(bad, widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60)
        }
    }

    // 9b. widthPx <= 0 => IllegalArgumentException
    @Test
    fun nonPositiveWidthThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            computeTableLayout(spec(rowCount = 1), widthPx = 0, twoColumnThreshold = 60)
        }
    }

    // 10. chooseBitmapConfig：小图 ARGB_8888，超 64MB 降级 RGB_565
    @Test
    fun bitmapConfigDowngradesOverMemoryBudget() {
        assertEquals(Bitmap.Config.ARGB_8888, chooseBitmapConfig(1080, 200))
        // 1080 x 20000 x 4B = 86.4MB > 64MB
        assertEquals(Bitmap.Config.RGB_565, chooseBitmapConfig(1080, 20_000))
        // 阈值边界内保持 ARGB：1080 x 15000 x 4B ≈ 61MB < 64MB
        assertEquals(Bitmap.Config.ARGB_8888, chooseBitmapConfig(1080, 15_000))
        // V26 新规格真实两表：角色牌 822×7604×4 ≈ 23.8MB 保 ARGB；
        // 行动牌 1444×23804×4 ≈ 137MB > 64MB → 降级 RGB_565
        assertEquals(Bitmap.Config.ARGB_8888, chooseBitmapConfig(EXPORT_CHAR_IMAGE_WIDTH_PX, 7_604))
        assertEquals(Bitmap.Config.RGB_565, chooseBitmapConfig(EXPORT_ACTION_IMAGE_WIDTH_PX, 23_804))
    }

    // 10b. 渲染前预算闸：真实最大表放行，更大表拒绝（不等 createBitmap 抛 OOM）
    @Test
    fun renderBudgetPassesRealTablesAndRejectsLarger() {
        val actionLayout = computeTableLayout(
            spec(rowCount = 941), widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = EXPORT_TWO_COLUMN_THRESHOLD,
        )
        // V26 行高 50：titleHeading 172 + 表头 50 + ceil(941/2)=471 行 ×50 + 32 = 23804
        assertEquals(23_804, actionLayout.heightPx)
        // 941 行双栏 1444px = 1444×23804×2 ≈ 68.7MB（RGB_565）必须能过闸，否则真实数据导不出去
        checkRenderMemoryBudget(EXPORT_ACTION_IMAGE_WIDTH_PX, actionLayout.heightPx,
            chooseBitmapConfig(EXPORT_ACTION_IMAGE_WIDTH_PX, actionLayout.heightPx))
        assertTrue(
            bitmapMemoryBytes(EXPORT_ACTION_IMAGE_WIDTH_PX, actionLayout.heightPx, Bitmap.Config.RGB_565) <
                100L * 1024 * 1024,
        )
        assertEquals(Bitmap.Config.RGB_565,
            chooseBitmapConfig(EXPORT_ACTION_IMAGE_WIDTH_PX, actionLayout.heightPx))
        // 1444 x 50000 x 2B ≈ 137MB > 100MB ⇒ 可读 IOException 而非 OOM
        assertThrows(IOException::class.java) {
            checkRenderMemoryBudget(EXPORT_ACTION_IMAGE_WIDTH_PX, 50_000, Bitmap.Config.RGB_565)
        }
        // RGB_565 与 ARGB_8888 的字节数差一倍（纯算术复核）
        assertEquals(
            EXPORT_ACTION_IMAGE_WIDTH_PX.toLong() * 50_000 * 2,
            bitmapMemoryBytes(EXPORT_ACTION_IMAGE_WIDTH_PX, 50_000, Bitmap.Config.RGB_565),
        )
        assertEquals(
            EXPORT_ACTION_IMAGE_WIDTH_PX.toLong() * 50_000 * 4,
            bitmapMemoryBytes(EXPORT_ACTION_IMAGE_WIDTH_PX, 50_000, Bitmap.Config.ARGB_8888),
        )
    }

    // 13. 🔴 尺寸常量回归锁（V26：照抄参考图实测，改回旧值会让版式重新变得又扁又挤）
    @Test
    fun sizeConstantsArePinned() {
        assertEquals(40, MIN_COLUMN_WIDTH_PX)
        assertEquals(12, CELL_PADDING_PX)
        assertEquals(26, PAGE_MARGIN_PX)
        assertEquals(50, ROW_HEIGHT_PX)
        assertEquals(50, HEADER_HEIGHT_PX)
        assertEquals(28, HEADER_TOP_GAP_PX)
        assertEquals(40, SUBTITLE_LINE_HEIGHT_PX)
        assertEquals(40, BADGES_LINE_HEIGHT_PX)
        assertEquals(64, TITLE_LINE_HEIGHT_PX)
        assertEquals(32, BOTTOM_PADDING_PX)
        // 正文 26f→20f、表头 26f→22f（参考图实测）；20f×1.18×2 行 = 47.2px ≤ 50px 行高
        assertEquals(20f, TEXT_SIZE_BODY_PX, 0f)
        assertEquals(22f, TEXT_SIZE_HEADER_PX, 0f)
        assertEquals(2, MAX_CELL_LINES)
        // TEXT_SIZE_TITLE_PX 是 private 锁不到；V26 值为 24f（分区标题带内居中深棕粗体）
    }

    // 11a. ellipsize：未超长原样返回
    @Test
    fun ellipsizeReturnsOriginalWhenItFits() {
        val measure: (String) -> Float = { it.length * 10f }
        assertEquals("abcdef", ellipsize("abcdef", maxWidthPx = 60f, measure = measure))
        assertEquals("", ellipsize("", maxWidthPx = 5f, measure = measure))
    }

    // 11b. ellipsize：超长截断且结果不超限、带省略号
    @Test
    fun ellipsizeTruncatesToFitLimit() {
        val measure: (String) -> Float = { it.length * 10f }
        val result = ellipsize("abcdefghij", maxWidthPx = 50f, measure = measure)
        assertTrue("must fit: '$result'", measure(result) <= 50f)
        assertTrue("must mark truncation: '$result'", result.endsWith("…"))
        assertNotEquals("abcdefghij", result)
    }

    // 11c. ellipsize：宽度连省略号都放不下时给空串
    @Test
    fun ellipsizeZeroWidthReturnsEmpty() {
        val measure: (String) -> Float = { it.length * 10f }
        assertEquals("", ellipsize("abc", maxWidthPx = 0f, measure = measure))
    }

    // ---- V9-C：wrapCellText（数据格两行优先，能放下就不省略）----
    // 每字 10px、maxWidth 80px ⇒ 每行 8 字
    private val tenPerChar: (String) -> Float = { it.length * 10f }

    // 14a. 放得下就原样返回，且不加省略号
    @Test
    fun wrapKeepsTextWhenItFits() {
        assertEquals(listOf("abcd"), wrapCellText("abcd", maxWidthPx = 80f, measure = tenPerChar))
        assertEquals(listOf(""), wrapCellText("", maxWidthPx = 80f, measure = tenPerChar))
    }

    // 14b. 15 字在两行内完整放下（用户口径："大多数行动牌名称 ≤15 字不该被省略"）
    @Test
    fun wrapFifteenCharsAcrossTwoLinesWithoutEllipsis() {
        val name = "字".repeat(15)
        val lines = wrapCellText(name, maxWidthPx = 80f, measure = tenPerChar) // 8 字/行
        assertEquals(listOf("字".repeat(8), "字".repeat(7)), lines)
        assertTrue("两行内放下就不该出现省略号: $lines", lines.none { it.contains("…") })
        assertEquals(2, lines.size)
    }

    // 14c. 20 字超出两行边界 ⇒ 第二行截断加省略号，且每行都不超限
    @Test
    fun wrapEllipsizesOnlyBeyondSecondLine() {
        val name = "字".repeat(20)
        val lines = wrapCellText(name, maxWidthPx = 80f, measure = tenPerChar)
        assertEquals(2, lines.size)
        assertEquals("字".repeat(8), lines[0])
        assertTrue("第二行必须标记截断: $lines", lines[1].endsWith("…"))
        lines.forEach { assertTrue("行超限: '$it'", tenPerChar(it) <= 80f) }
        // 剩余 12 字放不下 ⇒ 省略号前尽量长（8 格宽 - 1 格省略号 = 7 字 + …）
        assertEquals(8, lines[1].length)
    }

    // 14d. 宽度非法 ⇒ 单空串（drawText 对空串 no-op，不能让布局炸）
    @Test
    fun wrapNonPositiveWidthReturnsSingleEmpty() {
        assertEquals(listOf(""), wrapCellText("abc", maxWidthPx = 0f, measure = tenPerChar))
        assertEquals(listOf(""), wrapCellText("abc", maxWidthPx = -5f, measure = tenPerChar))
    }

    // 14e. 文本自带换行：按硬换行切开，各段互不影响
    @Test
    fun wrapHonorsEmbeddedNewlines() {
        assertEquals(listOf("abcd", "efgh"), wrapCellText("abcd\nefgh", 80f, tenPerChar))
        // 脏数据（两段各 12 字）压不进两行 ⇒ 仍是两行，末行省略
        val dirty = wrapCellText("abcdefghijkl\nabcdefghijkl", 80f, tenPerChar)
        assertEquals(2, dirty.size)
        assertTrue("超过两行的脏数据必须省略: $dirty", dirty[1].endsWith("…"))
        // 结尾换行不吞掉行、也不抛
        assertEquals(listOf("abcd"), wrapCellText("abcd\n", 80f, tenPerChar).take(1))
    }

    // 14f. 单字比整行还宽时仍推进（不死循环），并锁死行数上限
    @Test
    fun wrapNeverExceedsMaxLinesAndAlwaysProgresses() {
        val singleCharTooWide = wrapCellText("abcdefghij", maxWidthPx = 5f, measure = tenPerChar)
        assertEquals(2, singleCharTooWide.size)
        assertTrue(singleCharTooWide[1].endsWith("…"))
        val maxLines1 = wrapCellText("abcdefghij", 80f, tenPerChar, maxLines = 1)
        assertEquals(1, maxLines1.size)
        assertTrue(maxLines1[0].endsWith("…"))
    }

    // 14g. 🔴 真实行动牌权重（生产列宽）下：名称列 avail ≥ 8×正文字号，真机字宽下 15 字两行完整放下
    @Test
    fun wrapRealActionNameColumnKeepsFifteenCjkChars() {
        val realColumns = listOf(
            TableColumn("#", 20f, CellAlign.START),
            TableColumn("类别", 46f, CellAlign.CENTER),
            TableColumn("名称", 244f, CellAlign.CENTER),
            TableColumn("使用次数", 80f, CellAlign.CENTER),
            TableColumn("使用率%", 80f, CellAlign.CENTER),
        )
        val realSpec = TableSpec(
            "行动牌数据", "Clin - 1", listOf("b"), realColumns,
            List(941) { listOf("1", "装备牌", "名", "1", "1.000") },
        )
        val layout = computeTableLayout(realSpec, EXPORT_ACTION_IMAGE_WIDTH_PX, EXPORT_TWO_COLUMN_THRESHOLD)
        val nameIndex = realColumns.indexOfFirst { it.header == "名称" }
        val avail = layout.columnWidth[nameIndex] - 2 * CELL_PADDING_PX
        // 🔴 不变量：双栏 670px 下名称列 avail ≥ 8 × 正文字号（= 160px）
        assertTrue(
            "名称列 avail=$avail < 8 × $TEXT_SIZE_BODY_PX（生产权重退化？）：${layout.columnWidth}",
            avail >= 8 * TEXT_SIZE_BODY_PX,
        )
        // 真机 CJK = 1.0 em（每行 8 字）⇒ 两行 16 字，15 字卡名不该出现省略号
        val realMeasure: (String) -> Float = { it.length * TEXT_SIZE_BODY_PX }
        val lines = wrapCellText("字".repeat(15), avail.toFloat(), realMeasure)
        assertTrue(
            "名称列 avail=$avail 放不下 15 字（字号 $TEXT_SIZE_BODY_PX）：$lines",
            lines.none { it.contains("…") } && lines.size <= MAX_CELL_LINES,
        )
        // 两行必须仍装得进行高：字号 × 1.18 × 行数 <= ROW_HEIGHT_PX
        assertTrue(TEXT_SIZE_BODY_PX * 1.18f * MAX_CELL_LINES <= ROW_HEIGHT_PX.toFloat())
    }

    // 12. badges 空 vs 非空 => titleHeightPx 不同（空时不留空行）
    @Test
    fun emptyBadgesDoNotOccupyTitleHeight() {
        val withBadges = computeTableLayout(
            spec(rowCount = 2, badges = listOf("共进行 3493 场游戏")),
            widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60,
        )
        val withoutBadges = computeTableLayout(
            spec(rowCount = 2, badges = emptyList()),
            widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60,
        )
        assertEquals(
            BADGES_LINE_HEIGHT_PX,
            withBadges.titleHeightPx - withoutBadges.titleHeightPx,
        )
        val noSubtitle = computeTableLayout(
            spec(rowCount = 2, subtitle = null, badges = emptyList()),
            widthPx = EXPORT_ACTION_IMAGE_WIDTH_PX, twoColumnThreshold = 60,
        )
        assertEquals(
            SUBTITLE_LINE_HEIGHT_PX,
            withoutBadges.titleHeightPx - noSubtitle.titleHeightPx,
        )
    }
}
