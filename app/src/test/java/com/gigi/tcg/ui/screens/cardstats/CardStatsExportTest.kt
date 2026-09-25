// 长图内容组装的纯 JVM 单测（DESIGN-V8 §7）：列结构、序号、3 位小数、除零口径、类别映射、副标题退化。
// 不碰 android.graphics——渲染层另由 TableLayoutTest 覆盖。

package com.gigi.tcg.ui.screens.cardstats

import com.gigi.tcg.domain.CARD_TYPE_ASSIST
import com.gigi.tcg.domain.CARD_TYPE_EVENT
import com.gigi.tcg.domain.CARD_TYPE_MODIFY
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

        val zeroUseCards = listOf(char(0, 0), GcgCard(name = "null卡", cardType = "CardTypeCharacter"))
        val rows = buildCharTableSpec(summary(), "u", zeroUseCards).rows
        rows.forEach { row ->
            assertEquals("0.000", row[3])
            assertEquals("0.000", row[4])
        }
        val flat = rows.joinToString("|")
        assertFalse(flat.contains("NaN"))
        assertFalse(flat.contains("Infinity"))
        assertFalse(flat.contains("-0.000"))

        // 行动牌分母 = summary.actionTotalUse，为 0 时同样退化
        val actionRows = buildActionTableSpec(
            summary(actionTotalUse = 0),
            "u",
            listOf(GcgCard(name = "a", cardType = CARD_TYPE_EVENT, useCount = null)),
        ).rows
        assertEquals("0.000", actionRows[0][4])
        assertEquals("0", actionRows[0][3])
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
        val row = buildCharTableSpec(summary(), "u", listOf(GcgCard(name = null, cardType = "CardTypeCharacter"))).rows[0]
        assertEquals("未知", row[1])
        assertEquals("0", row[2])
        assertEquals("0", row[5])
        assertEquals("0.000", row[3])
        assertEquals("0.000", row[4])
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
    fun `导出规格常量 1080宽与60行双栏阈值`() {
        // 显式钉死：这两个值决定了分享图的规格统一性与双栏观感，改动须同步设计文档
        assertEquals(1080, EXPORT_IMAGE_WIDTH_PX)
        assertEquals(60, EXPORT_TWO_COLUMN_THRESHOLD)
        assertTrue(EXPORT_TWO_COLUMN_THRESHOLD > 0)
    }
}
