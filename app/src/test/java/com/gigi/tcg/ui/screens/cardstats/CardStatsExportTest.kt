// 长图内容组装的纯 JVM 单测（DESIGN-V8 §7）：列结构、序号、3 位小数、除零口径、类别映射、副标题退化。
// 不碰 android.graphics——渲染层另由 TableLayoutTest 覆盖。

package com.gigi.tcg.ui.screens.cardstats

import com.gigi.tcg.domain.CARD_TYPE_ASSIST
import com.gigi.tcg.domain.CARD_TYPE_EVENT
import com.gigi.tcg.domain.CARD_TYPE_MODIFY
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgSummary
import com.gigi.tcg.ui.export.CELL_PADDING_PX
import com.gigi.tcg.ui.export.MAX_CELL_LINES
import com.gigi.tcg.ui.export.ROW_HEIGHT_PX
import com.gigi.tcg.ui.export.TEXT_SIZE_BODY_PX
import com.gigi.tcg.ui.export.TEXT_SIZE_HEADER_PX
import com.gigi.tcg.ui.export.TableSpec
import com.gigi.tcg.ui.export.computeTableLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class CardStatsExportTest {

    private fun summary(
        nickname: String = "Clin",
        actionTotalUse: Int = 0,
    ) = GcgSummary(
        nickname = nickname,
        level = 45,
        avatarCardNum = 132,
        actionCardNum = 800,
        totalGames = 3493L,
        winGames = 2058L,
        winRate = "58.9%",
        winRateValue = 58.9,
        actionTotalUse = actionTotalUse,
        modifyUse = 0,
        modifyPercent = "0%",
        assistUse = 0,
        assistPercent = "0%",
        eventUse = 0,
        eventPercent = "0%",
    )

    private fun char(useCount: Int?, proficiency: Int?, name: String? = "行秋") =
        GcgCard(name = name, cardType = "CardTypeCharacter", useCount = useCount, proficiency = proficiency)

    @Test
    fun `角色牌表列结构与表头`() {
        val spec = buildCharTableSpec(summary(), "110526730", listOf(char(10, 5)))
        assertEquals(6, spec.columns.size)
        assertEquals(
            listOf("#", "名称", "出场数", "出场率%", "胜率%", "胜局数"),
            spec.columns.map { it.header },
        )
        assertEquals(listOf(false, false, true, true, true, true), spec.columns.map { it.alignEnd })
        assertEquals("角色牌数据", spec.title)
    }

    @Test
    fun `行动牌表列结构与表头`() {
        val spec = buildActionTableSpec(summary(), "110526730", listOf(GcgCard(name = "顺风", cardType = CARD_TYPE_EVENT, useCount = 3)))
        assertEquals(5, spec.columns.size)
        assertEquals(
            listOf("#", "类别", "名称", "使用次数", "使用率%"),
            spec.columns.map { it.header },
        )
        assertEquals(listOf(false, false, false, true, true), spec.columns.map { it.alignEnd })
        assertEquals("行动牌数据", spec.title)
    }

    @Test
    fun `行数等于入参数量且每行单元格数对齐列数`() {
        val cards = listOf(char(10, 5), char(20, 1), char(30, 30), char(5, 0))
        val charSpec = buildCharTableSpec(summary(), "u", cards)
        assertEquals(cards.size, charSpec.rows.size)
        charSpec.rows.forEach { row -> assertEquals(charSpec.columns.size, row.size) }

        val actionCards = listOf(
            GcgCard(name = "a", cardType = CARD_TYPE_MODIFY, useCount = 1),
            GcgCard(name = "b", cardType = CARD_TYPE_ASSIST, useCount = 2),
        )
        val actionSpec = buildActionTableSpec(summary(actionTotalUse = 3), "u", actionCards)
        assertEquals(actionCards.size, actionSpec.rows.size)
        actionSpec.rows.forEach { row -> assertEquals(actionSpec.columns.size, row.size) }
    }

    @Test
    fun `序号从1起且全局连续`() {
        val cards = (1..5).map { char(it, 0) }
        val charSpec = buildCharTableSpec(summary(), "u", cards)
        assertEquals(listOf("1", "2", "3", "4", "5"), charSpec.rows.map { it[0] })

        val actionCards = (1..5).map { GcgCard(name = "n$it", cardType = CARD_TYPE_EVENT, useCount = it) }
        val actionSpec = buildActionTableSpec(summary(actionTotalUse = 15), "u", actionCards)
        assertEquals(listOf("1", "2", "3", "4", "5"), actionSpec.rows.map { it[0] })
    }

    @Test
    fun `百分比保留3位小数含四舍五入`() {
        // 出场率：9419/100000*100 = 9.419；90581/100000*100 = 90.581
        val cards = listOf(char(9419, 5000), char(90581, 50581))
        val rows = buildCharTableSpec(summary(), "u", cards).rows
        assertEquals("9.419", rows[0][3])
        assertEquals("90.581", rows[1][3])
        assertEquals(listOf("9419", "5000"), listOf(rows[0][2], rows[0][5]))

        // 胜率 2/3 = 66.6666…⇒ 第 3 位四舍五入为 66.667（2 位小数会退化成 66.67）
        val rounding = buildCharTableSpec(summary(), "u", listOf(char(3, 2))).rows[0]
        assertEquals("66.667", rounding[4])
    }

    @Test
    fun `分母为0时百分比为0点000且不出现NaN或Infinity`() {
        val empty = buildCharTableSpec(summary(), "u", emptyList())
        assertEquals(0, empty.rows.size)

        // 角色牌分母 = ΣuseCount，而 0 次卡已被过滤 ⇒ 「分母为 0 且仍有行」在角色牌表里不可能出现；
        // 改用分母为 0 而分子非 0 的行动牌（useCount=3 / actionTotalUse=0）真正断言字符串内容，
        // 避免 rows 为空导致 forEach 空转（永远通过、失去意义）。
        val actionRows = buildActionTableSpec(
            summary(actionTotalUse = 0),
            "u",
            listOf(GcgCard(name = "a", cardType = CARD_TYPE_EVENT, useCount = 3)),
        ).rows
        assertEquals(1, actionRows.size)
        assertEquals("0.000", actionRows[0][4])
        assertEquals("3", actionRows[0][3])
        val flat = actionRows.joinToString("|")
        assertFalse(flat.contains("NaN"))
        assertFalse(flat.contains("Infinity"))
        assertFalse(flat.contains("-0.000"))
    }

    @Test
    fun `使用次数为0或null的卡不进表`() {
        val rows = buildCharTableSpec(summary(), "u", listOf(char(0, 0), char(null, null), char(5, 1))).rows
        assertEquals(1, rows.size)
        assertEquals("1", rows[0][0])
        assertEquals("行秋", rows[0][1])
    }

    @Test
    fun `行动牌类别映射 未知类型原样回显不抛异常`() {
        val cards = listOf(
            GcgCard(name = "装备", cardType = CARD_TYPE_MODIFY, useCount = 1),
            GcgCard(name = "支援", cardType = CARD_TYPE_ASSIST, useCount = 1),
            GcgCard(name = "事件", cardType = CARD_TYPE_EVENT, useCount = 1),
            GcgCard(name = "未来卡", cardType = "CardTypeUnknown", useCount = 1),
            GcgCard(name = "无类型", cardType = null, useCount = 1),
        )
        val rows = buildActionTableSpec(summary(actionTotalUse = 5), "u", cards).rows
        assertEquals(listOf("装备牌", "支援牌", "事件牌", "CardTypeUnknown", ""), rows.map { it[1] })
        assertEquals("20.000", rows[0][4])
    }

    @Test
    fun `可空字段按0处理不崩`() {
        // useCount 给非 0 值，否则这张卡会被过滤掉、rows[0] 直接越界
        val row = buildCharTableSpec(
            summary(), "u",
            listOf(GcgCard(name = null, cardType = "CardTypeCharacter", useCount = 1)),
        ).rows[0]
        assertEquals("未知", row[1])
        assertEquals("1", row[2])
        assertEquals("0", row[5]) // proficiency 为 null ⇒ ?: 0
        assertEquals("100.000", row[3]) // 出场率分母 = ΣuseCount = 1
        assertEquals("0.000", row[4]) // 胜率 = 0 / 1
    }

    @Test
    fun `徽章行不含数据来源渠道`() {
        val badges = buildCharTableSpec(summary(), "u", emptyList()).badges
        assertEquals(4, badges.size)
        assertTrue(badges.none { it.contains("GIGI", ignoreCase = true) })
    }

    @Test
    fun `过滤后行数与序号连续`() {
        // 角色牌：0 次、null 次（哪怕 proficiency 非 0）、以及 0/0 的卡都不进表，剩下的重新编号
        val charCards = listOf(char(0, 7), char(null, 9), char(4, 2), char(0, 0), char(6, 3))
        val charRows = buildCharTableSpec(summary(), "u", charCards).rows
        assertEquals(2, charRows.size)
        assertEquals(listOf("1", "2"), charRows.map { it[0] })
        assertEquals(listOf("4", "6"), charRows.map { it[2] })

        val actionCards = listOf(
            GcgCard(name = "零次", cardType = CARD_TYPE_EVENT, useCount = 0),
            GcgCard(name = "null次", cardType = CARD_TYPE_MODIFY, useCount = null, proficiency = 3),
            GcgCard(name = "用过", cardType = CARD_TYPE_ASSIST, useCount = 2),
        )
        val actionRows = buildActionTableSpec(summary(actionTotalUse = 10), "u", actionCards).rows
        assertEquals(1, actionRows.size)
        assertEquals("1", actionRows[0][0])
        assertEquals("用过", actionRows[0][2])
        assertEquals("20.000", actionRows[0][4]) // 分母仍是 summary.actionTotalUse = 10
    }

    @Test
    fun `副标题在uid空白时退化为只显昵称`() {
        val withUid = buildCharTableSpec(summary(), "110526730", emptyList())
        assertEquals("Clin - 110526730", withUid.subtitle)

        listOf("", "   ").forEach { uid ->
            val blank = buildCharTableSpec(summary(), uid, emptyList())
            assertEquals("Clin", blank.subtitle)
            assertFalse(blank.subtitle!!.contains(" - "))
        }
    }

    @Test
    fun `徽章行含关键统计且不带图鉴分母`() {
        val badges = buildCharTableSpec(summary(), "u", emptyList()).badges
        assertTrue(badges.contains("角色牌 132"))
        assertTrue(badges.contains("行动牌 800"))
        assertTrue(badges.contains("共进行 3493 场游戏"))
        assertTrue(badges.contains("胜率 58.9%"))
        // 图鉴总数（147/941）接口拿不到 ⇒ 只显分子，不出现 "/147" "/941"
        assertFalse(badges.any { it.contains("/147") || it.contains("/941") })
    }

    @Test
    fun `导出规格常量 1600宽与60行双栏阈值`() {
        // 显式钉死：这两个值决定了分享图的规格统一性与双栏观感，改动须同步设计文档
        // （1080 → 1600 是 V8H 修"文本被大量省略"的根因修复，改回 1080 下面两条宽度断言必红）
        assertEquals(1600, EXPORT_IMAGE_WIDTH_PX)
        assertEquals(60, EXPORT_TWO_COLUMN_THRESHOLD)
        assertTrue(EXPORT_TWO_COLUMN_THRESHOLD > 0)
    }

    // ---- V8H：保守字宽模型下的列宽验收（.task/tmp/verify_v8h.py 固化为测试）----
    // 中文/全角（code ≥ 0x2E80）= 1.0×字号，其余 = 0.55×字号；真机字宽只小不大，故为保守下界。

    private fun conservativeTextWidth(text: String, textSizePx: Float): Double =
        text.sumOf { c ->
            if (c.code >= 0x2E80) textSizePx.toDouble() else textSizePx * 0.55
        }

    private fun availOf(layoutColumnWidth: Int): Double =
        layoutColumnWidth - 2.0 * CELL_PADDING_PX

    private fun layoutFor(spec: TableSpec, charTable: Boolean) =
        computeTableLayout(spec, EXPORT_IMAGE_WIDTH_PX, exportTwoColumnThreshold(charTable))

    private fun assertHeadersFitWith8PercentMargin(spec: TableSpec, charTable: Boolean) {
        val layout = layoutFor(spec, charTable)
        spec.columns.forEachIndexed { i, column ->
            val avail = availOf(layout.columnWidth[i])
            val need = conservativeTextWidth(column.header, TEXT_SIZE_HEADER_PX)
            assertTrue(
                "列「${column.header}」表头放不下或余量不足 8%：width=${layout.columnWidth[i]} " +
                    "avail=$avail need=$need need×1.08=${need * 1.08}",
                avail >= need * 1.08,
            )
        }
    }

    private fun actionSpec(rowCount: Int) = buildActionTableSpec(
        summary(actionTotalUse = rowCount), "u",
        List(rowCount) { GcgCard(name = "卡", cardType = CARD_TYPE_EVENT, useCount = 1) },
    )

    @Test
    fun `全部表头在1600px布局下完整放入各自列且留8%余量`() {
        // 角色牌 147 行单栏 1600px / 行动牌 941 行双栏（真实最大规模，每栏仅 800px）
        val charSpec = buildCharTableSpec(summary(), "u", List(147) { char(10, 5) })
        assertHeadersFitWith8PercentMargin(charSpec, charTable = true)
        assertHeadersFitWith8PercentMargin(actionSpec(941), charTable = false)
        // 角色牌名称列（单栏 avail=571）10 个中文字单行必须完整显示（用户口径：角色名 ≤10 字）
        val charLayout = layoutFor(charSpec, charTable = true)
        val nameIndex = charSpec.columns.indexOfFirst { it.header == "名称" }
        assertTrue(
            "角色牌名称列 avail=${availOf(charLayout.columnWidth[nameIndex])} 放不下 10 个中文字",
            availOf(charLayout.columnWidth[nameIndex]) >= 10 * TEXT_SIZE_BODY_PX,
        )
    }

    // V9-C：行动牌名称列从"单行 8 字"升级为"两行 16 字"（正文 28f→26f + wrapCellText 两行换行）
    @Test
    fun `行动牌双栏时名称列两行可容至少15个中文字`() {
        val spec = actionSpec(941)
        val layout = layoutFor(spec, charTable = false)
        assertEquals(2, layout.columnsPerBand) // 前提：确为双栏
        val nameIndex = spec.columns.indexOfFirst { it.header == "名称" }
        val avail = availOf(layout.columnWidth[nameIndex])
        val perLine = floor(avail / (TEXT_SIZE_BODY_PX * 1.05)).toInt() // 保守系数 1.05
        assertTrue(
            "双栏名称列两行容不下 15 个中文字：width=${layout.columnWidth[nameIndex]} avail=$avail " +
                "每行$perLine 字 ×$MAX_CELL_LINES = ${perLine * MAX_CELL_LINES}",
            perLine * MAX_CELL_LINES >= 15,
        )
        // 行高约束：两行文字块必须仍装得进 64px 数据行，否则相邻行重叠
        assertTrue(
            "两行高度 ${TEXT_SIZE_BODY_PX * 1.18 * MAX_CELL_LINES} 超出行高 $ROW_HEIGHT_PX",
            TEXT_SIZE_BODY_PX * 1.18f * MAX_CELL_LINES <= ROW_HEIGHT_PX,
        )
    }

    @Test
    fun `类别列放得下装备牌且数值列放得下六位数字`() {
        val spec = actionSpec(941)
        val layout = layoutFor(spec, charTable = false)
        for (i in listOf(3, 4)) { // 使用次数 / 使用率%
            assertTrue(
                "数值列 ${spec.columns[i].header} 放不下 6 位数字",
                availOf(layout.columnWidth[i]) >= conservativeTextWidth("999999", TEXT_SIZE_BODY_PX),
            )
        }
        // 百分比列极端值 100.000（7 字符）
        assertTrue(
            availOf(layout.columnWidth[4]) >= conservativeTextWidth("100.000", TEXT_SIZE_BODY_PX),
        )
        val catIndex = spec.columns.indexOfFirst { it.header == "类别" }
        assertTrue(
            "类别列放不下「装备牌」",
            availOf(layout.columnWidth[catIndex]) >= conservativeTextWidth("装备牌", TEXT_SIZE_BODY_PX),
        )
        val hashIndex = 0
        assertTrue(
            "# 列放不下 3 位数字（941 行）",
            availOf(layout.columnWidth[hashIndex]) >= conservativeTextWidth("941", TEXT_SIZE_BODY_PX),
        )
    }

    @Test
    fun `列宽之和精确等于图宽或半图宽`() {
        val charSpec = buildCharTableSpec(summary(), "u", List(147) { char(10, 5) })
        val charLayout = layoutFor(charSpec, charTable = true)
        assertEquals("角色牌恒单栏", 1, charLayout.columnsPerBand)
        assertEquals(EXPORT_IMAGE_WIDTH_PX, charLayout.columnWidth.sum())
        val actionLayout = layoutFor(actionSpec(941), charTable = false)
        assertEquals(2, actionLayout.columnsPerBand)
        assertEquals(EXPORT_IMAGE_WIDTH_PX / 2, actionLayout.columnWidth.sum())
    }
}
