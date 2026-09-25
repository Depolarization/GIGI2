package com.gigi.tcg.ui.export

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TableLayoutTest {

    private fun columns(n: Int, nameWeight: Float = 4f) = listOf(
        TableColumn("#", 1f, alignEnd = false),
        TableColumn("名称", nameWeight, alignEnd = false),
        TableColumn("出场数", 1.5f, alignEnd = true),
        TableColumn("出场率%", 1.5f, alignEnd = true),
        TableColumn("胜率%", 1.5f, alignEnd = true),
    ).let { if (n == 5) it else it + listOf(TableColumn("胜局数", 1.5f, alignEnd = true)) }

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
        val layout = computeTableLayout(spec(rowCount = 60), widthPx = 1080, twoColumnThreshold = 60)
        assertEquals(1, layout.columnsPerBand)
        assertEquals(60, layout.rowsPerBand)
    }

    // 2. 双栏：rows.size == threshold + 1
    @Test
    fun oneRowOverThresholdSplitsToTwoBands() {
        val layout = computeTableLayout(spec(rowCount = 61), widthPx = 1080, twoColumnThreshold = 60)
        assertEquals(2, layout.columnsPerBand)
        assertEquals(31, layout.rowsPerBand) // ceil(61/2)
    }

    // 3. 双栏时两栏行数差 <= 1（奇数行 61：左 31 右 30）
    @Test
    fun oddRowCountBandsDifferByAtMostOne() {
        val rows = 61
        val layout = computeTableLayout(spec(rowCount = rows), widthPx = 1080, twoColumnThreshold = 60)
        val left = layout.rowsPerBand
        val right = rows - left
        assertEquals(31, left)
        assertEquals(30, right)
        assertTrue("bands differ by more than 1: $left vs $right", kotlin.math.abs(left - right) <= 1)
    }

    // 4a. 单栏列宽总和精确 == widthPx
    @Test
    fun singleColumnWidthsSumExactlyToWidth() {
        val layout = computeTableLayout(spec(rowCount = 3), widthPx = 1080, twoColumnThreshold = 60)
        assertEquals(1080, layout.columnWidth.sum())
        assertEquals(layout.columnWidth.sum(), layout.widthPx)
    }

    // 4b. 双栏时每栏占半宽：列宽总和 == widthPx / 2；columnX 只描述第一栏
    @Test
    fun twoColumnBandTakesHalfWidthAndXStartsAtZero() {
        val layout = computeTableLayout(spec(rowCount = 100), widthPx = 1080, twoColumnThreshold = 60)
        assertEquals(540, layout.columnWidth.sum())
        assertEquals(0, layout.columnX.first())
        // columnX 最后一列 + 其宽度 == 半宽（不含第二栏偏移——偏移由绘制端加 widthPx/2）
        val last = layout.columnX.size - 1
        assertEquals(540, layout.columnX[last] + layout.columnWidth[last])
        assertTrue(layout.columnX.max() < 540)
    }

    // 5. 最小宽度保护：weight 极小的列不被压瘪
    @Test
    fun tinyWeightColumnGetsMinimumWidth() {
        val cols = listOf(
            TableColumn("名称", 100f, alignEnd = false),
            TableColumn("#", 0.01f, alignEnd = false),
        )
        val s = TableSpec("t", null, emptyList(), cols, listOf(listOf("a", "b")))
        val layout = computeTableLayout(s, widthPx = 1080, twoColumnThreshold = 60)
        assertTrue(
            "min-width protection failed: ${layout.columnWidth}",
            layout.columnWidth[1] >= MIN_COLUMN_WIDTH_PX,
        )
        assertEquals(1080, layout.columnWidth.sum())
    }

    // 6. heightPx 手算比对
    @Test
    fun heightMatchesManualComputation() {
        val layout = computeTableLayout(
            spec(rowCount = 5, badges = listOf("b1", "b2")),
            widthPx = 1080,
            twoColumnThreshold = 60,
        )
        val expectedTitle = TITLE_LINE_HEIGHT_PX + SUBTITLE_LINE_HEIGHT_PX + BADGES_LINE_HEIGHT_PX
        assertEquals(expectedTitle, layout.titleHeightPx)
        assertEquals(64, layout.headerHeightPx)
        assertEquals(56, layout.rowHeightPx)
        val expectedHeight = expectedTitle + 64 + 5 * 56 + BOTTOM_PADDING_PX
        assertEquals(expectedHeight, layout.heightPx)
    }

    // 7. 空 rows 也是合法布局
    @Test
    fun emptyRowsYieldValidLayoutWithZeroRowsPerBand() {
        val layout = computeTableLayout(spec(rowCount = 0), widthPx = 1080, twoColumnThreshold = 60)
        assertEquals(0, layout.rowsPerBand)
        assertEquals(1, layout.columnsPerBand)
        assertEquals(1080, layout.columnWidth.sum())
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
            computeTableLayout(bad, widthPx = 1080, twoColumnThreshold = 60)
        }
        assertTrue("message should mention row number: $e.message", e.message!!.contains("#1"))
    }

    // 9. columns 为空 => IllegalArgumentException
    @Test
    fun emptyColumnsThrow() {
        val bad = TableSpec("t", null, emptyList(), emptyList(), emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            computeTableLayout(bad, widthPx = 1080, twoColumnThreshold = 60)
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

    // 12. badges 空 vs 非空 => titleHeightPx 不同（空时不留空行）
    @Test
    fun emptyBadgesDoNotOccupyTitleHeight() {
        val withBadges = computeTableLayout(
            spec(rowCount = 2, badges = listOf("共进行 3493 场游戏")),
            widthPx = 1080, twoColumnThreshold = 60,
        )
        val withoutBadges = computeTableLayout(
            spec(rowCount = 2, badges = emptyList()),
            widthPx = 1080, twoColumnThreshold = 60,
        )
        assertEquals(
            BADGES_LINE_HEIGHT_PX,
            withBadges.titleHeightPx - withoutBadges.titleHeightPx,
        )
        val noSubtitle = computeTableLayout(
            spec(rowCount = 2, subtitle = null, badges = emptyList()),
            widthPx = 1080, twoColumnThreshold = 60,
        )
        assertEquals(
            SUBTITLE_LINE_HEIGHT_PX,
            withoutBadges.titleHeightPx - noSubtitle.titleHeightPx,
        )
    }
}
