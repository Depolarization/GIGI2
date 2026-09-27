// 长图内容组装的纯 JVM 单测（DESIGN-V8 §7）：列结构、序号、3 位小数、除零口径、类别映射、
// 页眉文案（昵称/等级/胶囊/签名/日期）与 nowrap 列宽口径（🔴 永不折行、永不省略号）。
// 不碰 android.graphics——渲染层与布局模型另由 TableLayoutTest 覆盖。

package com.gigi.tcg.ui.screens.cardstats

import com.gigi.tcg.domain.CARD_TYPE_ASSIST
import com.gigi.tcg.domain.CARD_TYPE_EVENT
import com.gigi.tcg.domain.CARD_TYPE_MODIFY
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgStats
import com.gigi.tcg.domain.GcgSummary
import com.gigi.tcg.domain.WikiCardTotals
import com.gigi.tcg.domain.computeGcgSummary
import com.gigi.tcg.domain.prepareCardLists
import com.gigi.tcg.ui.export.CELL_PADDING_PX
import com.gigi.tcg.ui.export.CellAlign
import com.gigi.tcg.ui.export.FIRST_CELL_LEFT_PADDING_PX
import com.gigi.tcg.ui.export.PAGE_MARGIN_PX
import com.gigi.tcg.ui.export.TABLE_BORDER_PX
import com.gigi.tcg.ui.export.TEXT_SIZE_BODY_PX
import com.gigi.tcg.ui.export.TableSpec
import com.gigi.tcg.ui.export.TextMeasurer
import com.gigi.tcg.ui.export.computeTableLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlin.math.ceil
import org.junit.Test

class CardStatsExportTest {

    // 假 measurer：CJK/全角（code ≥ 0x2E80）= 1.0×字号，其余 = 0.55×字号（与 TableLayoutTest 同口径）
    private fun textWidth(text: String, textSizePx: Float): Float =
        text.sumOf { c ->
            if (c.code >= 0x2E80) textSizePx.toDouble() else textSizePx * 0.55
        }.toFloat()

    private val measurer = TextMeasurer { text, textSizePx -> textWidth(text, textSizePx) }

    private fun summary(
        nickname: String = "Clin",
        actionTotalUse: Int = 0,
        avatarCardTotal: Int = 147,
        actionCardTotal: Int = 941,
    ) = GcgSummary(
        nickname = nickname,
        level = 45,
        avatarCardNum = 132,
        actionCardNum = 800,
        avatarCardTotal = avatarCardTotal,
        actionCardTotal = actionCardTotal,
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

    private fun actionSpec(rowCount: Int) = buildActionTableSpec(
        summary(actionTotalUse = rowCount), "u",
        List(rowCount) { GcgCard(name = "卡", cardType = CARD_TYPE_EVENT, useCount = 1) },
    )

    private fun layoutFor(spec: TableSpec, charTable: Boolean) =
        computeTableLayout(spec, measurer, exportTwoColumnThreshold(charTable))

    @Test
    fun `角色牌表列结构与表头`() {
        val spec = buildCharTableSpec(summary(), "110526730", listOf(char(10, 5)))
        assertEquals(6, spec.columns.size)
        assertEquals(
            listOf("#", "名称", "出场数", "出场率%", "胜率%", "胜局数"),
            spec.columns.map { it.header },
        )
        // 照抄参考图：# 列 START（左对齐），其余列（含名称）全部 CENTER
        assertEquals(
            listOf(
                CellAlign.START, CellAlign.CENTER, CellAlign.CENTER,
                CellAlign.CENTER, CellAlign.CENTER, CellAlign.CENTER,
            ),
            spec.columns.map { it.align },
        )
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
        assertEquals(
            listOf(
                CellAlign.START, CellAlign.CENTER, CellAlign.CENTER,
                CellAlign.CENTER, CellAlign.CENTER,
            ),
            spec.columns.map { it.align },
        )
        assertEquals("行动牌数据", spec.title)
    }

    @Test
    fun `页眉文案齐备`() {
        val spec = buildCharTableSpec(summary(), "110526730", listOf(char(10, 5)))
        assertEquals("Clin - 110526730", spec.nickname)
        assertTrue("levelText 必须带等级数字：${spec.levelText}", spec.levelText?.contains("45") == true)
        assertEquals("暂无签名", spec.signature)
        val date = spec.exportDateText
        assertNotNull("exportDateText 必须填充（日期行右对齐）", date)
        assertTrue("exportDateText 应为 yyyy-MM-dd，实际 $date", Regex("\\d{4}-\\d{2}-\\d{2}").matches(date!!))
    }

    @Test
    fun `uid空白时昵称行退化为只显昵称`() {
        listOf("", "   ").forEach { uid ->
            val blank = buildCharTableSpec(summary(), uid, emptyList())
            assertEquals("Clin", blank.nickname)
            assertFalse(blank.nickname.contains(" - "))
        }
    }

    // ---- 签名框：真实签名（米游社 introduce）优先，取不到回落「暂无签名」占位 ----

    @Test
    fun `有真实签名时两张表都用真实签名`() {
        val introduce = "万壑千岩沉玉间"
        val charSpec = buildCharTableSpec(summary(), "u", listOf(char(10, 5)), signature = introduce)
        val actionSpec = buildActionTableSpec(summary(), "u", emptyList(), signature = introduce)
        assertEquals(introduce, charSpec.signature)
        assertEquals(introduce, actionSpec.signature)
    }

    @Test
    fun `签名缺失或空白回落暂无签名占位不留空白框`() {
        // null = 社区 UID 不可得 / 接口失败；"" 与全空白 = 用户没设置签名（实测服务端返回空串）
        listOf(null, "", "   ", "\n\t ").forEach { raw ->
            val spec = buildCharTableSpec(summary(), "u", emptyList(), signature = raw)
            assertEquals("占位不能是空串（空串渲染层会整框不画）：raw=$raw", "暂无签名", spec.signature)
            assertEquals("暂无签名", buildActionTableSpec(summary(), "u", emptyList(), signature = raw).signature)
        }
    }

    @Test
    fun `未传签名的旧调用点仍走占位不崩`() {
        // 默认参数路径：导出动作接线前的调用形态（CardStatsExportAction 传 3 个实参）
        assertEquals("暂无签名", buildCharTableSpec(summary(), "u", emptyList()).signature)
    }

    @Test
    fun `超长签名截断加省略号且不撑破白框`() {
        // 真实样本里见过 40+ 字的长签名（V28-S 报告 B.3），白框宽 = 文字实测宽 + 内边距，
        // 渲染层不截断 ⇒ 必须在组装层收尾，否则框被画出画布右缘。
        val long = "呱～文明的建成。能量不是榨取，是调谐；引力不是操控，是在与时空的对话。" +
            "万壑千岩沉玉间，海祇的旧梦依旧；珊瑚宫的心事谁来听，只余潮声与晚风。"
        val signature = exportSignatureText(long)
        assertTrue("超长签名必须以省略号收尾：$signature", signature.endsWith("…"))
        assertTrue("截短了才有意义", signature.length < long.length)
        val width = textWidth(signature, EXPORT_SIGNATURE_TEXT_SIZE_PX)
        assertTrue("签名宽 $width 仍超过预算 ${EXPORT_SIGNATURE_MAX_WIDTH_PX}", width <= EXPORT_SIGNATURE_MAX_WIDTH_PX)

        // 端到端核对最窄画布：空行的单栏角色牌表 bandWidth 最小，白框（文字宽 + 左右内边距 20×2）
        // 必须留在画布内容区里，右缘最多到 bandWidth + 4（画布宽 = bandWidth + 2×(24+2)，两侧页边距 24）
        val bandWidth = layoutFor(buildCharTableSpec(summary(), "u", emptyList(), signature = long), charTable = true)
            .bandWidthPx
        assertTrue("白框 ${width + 40} 超出画布内容宽 $bandWidth", width + 2 * 20 <= bandWidth + 4)
    }

    @Test
    fun `签名压掉换行且截断不劈开代理对`() {
        // 白框只有一行高 ⇒ 换行/连续空白压成单个空格
        assertEquals("🌊签名 第二行", exportSignatureText("  🌊签名 \n 第二行  "))

        // 签名里常带 emoji（代理对）：按 char 截断会留下孤立代理对 ⇒ 画出豆腐块
        val longEmoji = "🌊".repeat(200)
        val clipped = exportSignatureText(longEmoji)
        assertTrue("应截断：${clipped.length}", clipped.length < longEmoji.length)
        assertTrue("应以省略号收尾：$clipped", clipped.endsWith("…"))
        val body = clipped.dropLast(1)
        assertTrue("截断后不能是空串", body.isNotEmpty())
        assertEquals(
            "截断点必须落在码点边界（代理对成对）",
            body.length / 2,
            Character.codePointCount(body, 0, body.length),
        )
    }

    @Test
    fun `胶囊按参考图顺序且为已得斜杠总数`() {
        val badges = buildCharTableSpec(summary(), "u", emptyList()).badges
        assertEquals(4, badges.size)
        assertEquals(listOf("角色牌 132/147", "行动牌 800/941", "共进行 3493 场游戏", "胜率 58.9%"), badges)
        assertTrue(badges[0].contains("/"))
        assertTrue(badges[1].contains("/"))
        // 兜底口径：总数缺失时两数相同，绝不出现 "/0"
        val fallback = buildCharTableSpec(summary(avatarCardTotal = 132, actionCardTotal = 800), "u", emptyList()).badges
        assertEquals("角色牌 132/132", fallback[0])
        assertFalse(fallback.any { it.endsWith("/0") })
    }

    @Test
    fun `图鉴总数经 summary 落到胶囊分母`() {
        // 端到端口径：已得 143 / 图鉴 147 ⇒ 胶囊显示"未收集满"，不再出现 143/143 的假全收集
        val stats = GcgStats(
            nickname = "Clin",
            level = 45,
            avatarCardNumGained = 143,
            actionCardNumGained = 800,
        )
        val lists = prepareCardLists(listOf(char(useCount = 3, proficiency = 3)))
        val badges = buildCharTableSpec(
            computeGcgSummary(stats, lists, WikiCardTotals(avatarTotal = 147, actionTotal = 941)),
            "u", emptyList(),
        ).badges
        assertEquals("角色牌 143/147", badges[0])
        assertEquals("行动牌 800/941", badges[1])

        // 图鉴接口失败（总数 0）⇒ 退回已得数，分母仍不为 0
        val offline = buildCharTableSpec(computeGcgSummary(stats, lists), "u", emptyList()).badges
        assertEquals("角色牌 143/143", offline[0])
        assertFalse(offline.any { it.endsWith("/0") })
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

    // ---- nowrap 列宽口径（用户实测的两个缺陷：长牌名折行、表头被省略号截断）----

    @Test
    fun `角色牌最长牌名单行放得下`() {
        val longest = "阿佩普的绿洲守望者"
        val spec = buildCharTableSpec(
            summary(), "u",
            listOf(char(1, 1), char(2, 2, name = longest)),
        )
        val layout = layoutFor(spec, charTable = true)
        val nameWidth = layout.columnWidth[1]
        assertTrue(
            "名称列 $nameWidth 放不下「$longest」（${textWidth(longest, TEXT_SIZE_BODY_PX)} + 内边距 20）",
            nameWidth >= textWidth(longest, TEXT_SIZE_BODY_PX) + 2 * CELL_PADDING_PX,
        )
        // 恒单栏：147 行也不折栏
        val full = buildCharTableSpec(summary(), "u", List(147) { char(1, 1, name = longest) })
        assertEquals(1, layoutFor(full, charTable = true).columnsPerBand)
        assertEquals(147, layoutFor(full, charTable = true).rowsPerBand)
    }

    @Test
    fun `行动牌表头与长卡名都不被截断`() {
        val spec = buildActionTableSpec(
            summary(actionTotalUse = 2), "u",
            listOf(
                GcgCard(name = "元素共鸣：交织之火", cardType = CARD_TYPE_MODIFY, useCount = 1),
                GcgCard(name = "顺风", cardType = CARD_TYPE_EVENT, useCount = 1),
            ),
        )
        val layout = layoutFor(spec, charTable = false)
        val rateWidth = layout.columnWidth[spec.columns.indexOfFirst { it.header == "使用率%" }]
        assertTrue(
            "「使用率%」列宽 $rateWidth < 表头宽 ${textWidth("使用率%", TEXT_SIZE_BODY_PX)}",
            rateWidth >= textWidth("使用率%", TEXT_SIZE_BODY_PX),
        )
        val name = "元素共鸣：交织之火"
        val nameWidth = layout.columnWidth[spec.columns.indexOfFirst { it.header == "名称" }]
        assertTrue(
            "名称列 $nameWidth 放不下 9 字卡名",
            nameWidth >= textWidth(name, TEXT_SIZE_BODY_PX) + 2 * CELL_PADDING_PX,
        )
        // 类别列由 minWidthPx 兜底：3 字类别名 + 内边距 = 80 < 90
        assertEquals(90, layout.columnWidth[1])
    }

    @Test
    fun `画布宽由表格内容推出`() {
        val spec = buildCharTableSpec(summary(), "u", List(147) { char(10, 5) })
        val layout = layoutFor(spec, charTable = true)
        assertEquals(layout.bandWidthPx, layout.columnWidth.sum())
        assertEquals(layout.bandWidthPx + 2 * (PAGE_MARGIN_PX + TABLE_BORDER_PX), layout.widthPx)
        // 首列 = max(表头 11, 序号 147 的 33) + 15 + 10
        assertEquals(ceil(textWidth("147", TEXT_SIZE_BODY_PX)).toInt() + FIRST_CELL_LEFT_PADDING_PX + CELL_PADDING_PX, layout.columnWidth[0])
        // 数值列下限生效（单元格只有 1~2 位数字，表头撑开）
        assertEquals(100, layout.columnWidth[2])
        assertEquals(111, layout.columnWidth[3])

        val actionLayout = layoutFor(actionSpec(941), charTable = false)
        assertEquals(2, actionLayout.columnsPerBand)
        assertEquals(471, actionLayout.rowsPerBand)
        assertEquals(2 * actionLayout.bandWidthPx + 15 + 2 * (PAGE_MARGIN_PX + TABLE_BORDER_PX), actionLayout.widthPx)
    }

    @Test
    fun `双栏阈值常量保持60与恒单栏`() {
        assertEquals(60, EXPORT_TWO_COLUMN_THRESHOLD)
        assertEquals(Int.MAX_VALUE, EXPORT_CHAR_TWO_COLUMN_THRESHOLD)
        assertEquals(EXPORT_CHAR_TWO_COLUMN_THRESHOLD, exportTwoColumnThreshold(charTable = true))
        assertEquals(EXPORT_TWO_COLUMN_THRESHOLD, exportTwoColumnThreshold(charTable = false))
    }

    @Test
    fun `徽章行不含数据来源渠道`() {
        val badges = buildCharTableSpec(summary(), "u", emptyList()).badges
        assertTrue(badges.none { it.contains("GIGI", ignoreCase = true) })
    }
}
