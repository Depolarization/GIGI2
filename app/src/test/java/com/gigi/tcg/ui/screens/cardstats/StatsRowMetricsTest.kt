// 卡牌统计页列表几何锁（V36 任务 C 立，V37-AD 任务 E-1/E-2 改口径）。
// 不靠截图：直接引用源码里的列几何常量 + 对源码文本断言列对齐形态，防止来回翻转没人拦。
//
// 🔴 V37-2 任务 E-2 **反转** V36 任务 C 的"序号列起始对齐"决定，理由（用户原话）：
// 「列表中最左侧的序号列没有像最右侧的出场次数列那样，文本是右对齐的，这是一个错误的设计，
//   随着序号位数的增加，文本会越来越贴近名称列」。
// 起始对齐 ⇒ 名次 1→10→100 时右缘持续向右逼近牌名列（差 8dp→2dp→贴脸）；
// 右对齐 ⇒ 位数增加只向左生长，牌名列左缘与序号右缘的 [RankNameGap] 恒定。
// 旧断言「# 列不得出现 TextAlign.End」正是这条病的护栏方向错了，已随本版翻转。
package com.gigi.tcg.ui.screens.cardstats

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp

class StatsRowMetricsTest {

    private val src: String by lazy {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsRoute.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        file.readText()
    }

    /** 表格单元格形态：`Text( ... modifier = ComposeModifier.width(RankColumnWidth) )`，DOT_MATCHES_ALL 跨行取块 */
    private val rankCellPattern = Regex(
        """Text\(\s*(?:rank\.toString\(\)|stringResource\(R\.string\.stat_rank\)),.*?width\(RankColumnWidth\)""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * 去掉 `//` 行注释的源码。结构闸门跑在这上面：重构后的注释里逐条引用被废弃的旧特征串
     * （`verticalScroll` 的历史说明、`PLAYER_INFO_NICK_TIER_GAP_DP` 的删除说明等），
     * 对原文断言"不得包含"会被注释自己判挂。
     */
    private fun codeOnly(source: String): String =
        source.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

    @Test
    fun `rank column is right-aligned in all four cells`() {
        val cells = rankCellPattern.findAll(src).toList()
        assertEquals("两页表头 + 两页行，共 4 处序号单元格", 4, cells.size)
        cells.forEach { m ->
            assertTrue(
                "# 列必须右对齐（与「出场次数」同口径），缺 textAlign 的是：\n${m.value}",
                m.value.contains("textAlign = TextAlign.End"),
            )
        }
    }

    @Test
    fun `rank column width fits three-digit ranks without being guesswork`() {
        // 列宽来自字号换算，不是拍脑袋：labelMedium=12sp，Roboto 数字步进 ≈0.55em ⇒ 三位数 ≈19.8dp
        val threeDigitsDp = 12f * 0.55f * 3f
        assertTrue(
            "序号列要放得下三位数名次（labelMedium ≈${threeDigitsDp}dp），实际 $RankColumnWidth",
            RankColumnWidth >= threeDigitsDp.dp,
        )
        // 也不该宽到把牌名列挤瘦（V36 那版 32dp 右对齐就是被嫌"整列表看着右坠"）
        assertTrue("序号列不该超过 24dp，实际 $RankColumnWidth", RankColumnWidth <= 24.dp)
        assertTrue("序号列与牌名列必须留呼吸位", RankNameGap >= 4.dp && RankNameGap <= 12.dp)
        // 牌名列左缘 = 列表起始内距 + 序号文字区 + 呼吸位（右对齐后这个值与名次位数无关）
        // V39-B 任务 B：起始内距 16dp → 8dp（序号列距屏幕左边距过大），省下的 8dp 还给牌名列
        assertEquals(
            "牌名列左缘 = ContentStartPadding + RankColumnWidth + RankNameGap",
            ContentStartPadding + RankColumnWidth + RankNameGap,
            8.dp + 24.dp + 8.dp,
        )
        // 三列在三位数下的余量（字号 × Roboto/等宽数字步进）：
        // 序号 labelMedium 12sp ×0.55em ×3 位 ≈19.8dp ≤ 24dp；
        // 出场/胜场 bodyMedium 14sp 等宽 ×3 位 ≈25.2dp ≤ 48dp；百分比 "100.0%" 6 字 ≈50.4dp ≤ 60dp。
        val countThreeDigits = 14f * 0.6f * 3f
        val percentSixChars = 14f * 0.6f * 6f
        assertTrue("出场/胜场列要放得下三位数（≈${countThreeDigits}dp），实际 $StatCountWidth", StatCountWidth >= countThreeDigits.dp)
        assertTrue("百分比列要放得下 100.0%（≈${percentSixChars}dp），实际 $StatPercentWidth", StatPercentWidth >= percentSixChars.dp)
    }

    /**
     * V40-A：头部（信息卡 + tab 行）移出 pager 后直接挂页面边距 16dp —— 它们外面已没有
     * LazyColumn 的 8dp contentPadding 可叠，V39-B 的 8dp 起始补偿常量随之作废。
     * 列表内距口径不变：两页 LazyColumn 起始仍 8dp（序号列距屏幕左边距过大那次的定稿）。
     */
    @Test
    fun listStartInsetKeepsPageMarginOnHeadersDirectly() {
        val code = codeOnly(src)
        assertEquals("列表起始内距必须是 8dp（序号列距左边距过大）", 8.dp, ContentStartPadding)
        assertEquals("右侧内距保持 16dp 页面边距", 16.dp, ContentHorizontalPadding)
        // 头部两件（信息卡 + tab 行）各自直接挂 16dp 页面边距（全页各一处）
        assertEquals(
            "信息卡与 tab 行都直接用页面边距（不用再「8+补偿」拼凑）",
            2,
            Regex("padding\\(start = ContentHorizontalPadding\\)").findAll(code).count(),
        )
        // 两页的 LazyColumn 都按原口径起始内距
        assertEquals("两页 contentPadding 起始都用 ContentStartPadding", 2, Regex("start = ContentStartPadding").findAll(code).count())
        // 🔴 序号列宽与右对齐一律不动（红线：本轮只挪头部内距）
        assertEquals("序号列宽保持 24dp", 24.dp, RankColumnWidth)
        assertEquals("序号↔牌名呼吸位保持 8dp", 8.dp, RankNameGap)
        // V39-B 的 8dp 起始补偿常量已随头部移出 LazyColumn 作废，标识符不得复活
        assertFalse("起始补偿常量已删除（头部不再叠在列表内距上）", code.contains("HeaderStartCompensation"))
    }

    @Test
    fun `name column reserves the gap so digits never grow into it`() {
        val nameCells = Regex("weight\\(1f\\)\\.padding\\(start = RankNameGap\\)").findAll(src).toList()
        assertEquals("两页表头 + 两页行，共 4 处牌名列（呼吸位挂在牌名列起始，位数为 1 时也不会贴脸）", 4, nameCells.size)
        // 旧的"序号列右对齐 + 牌名列再叠 8dp"双重间距形态不得回来（红线：Arrangement 与 Spacer 不叠加）
        assertFalse(
            "呼吸位只在一处声明，序号单元格不得再叠 padding(end)",
            src.contains("width(RankColumnWidth).padding(end"),
        )
    }

    /**
     * V37-1 任务 E-1：表头 sticky —— 用户实测「表头 y=1063 随滚动移动」。
     * 结构口径：整页**没有** verticalScroll（统计区 + tab 行本来就是固定的，V29 起就在滚动容器之外），
     * 表头从 `item` 改 `stickyHeader` 后钉在列表视口顶部；行内容仍用 LazyColumn（页内滚）。
     */
    @Test
    fun `table headers stick while rows scroll and outer page does not scroll`() {
        val code = codeOnly(src)
        assertFalse(
            "统计页整页不得再套 verticalScroll（否则统计区会带着列表一起滚，正是 V37-1 报的病）",
            code.contains("verticalScroll"),
        )
        listOf("char-header", "action-header").forEach { key ->
            assertTrue("表头 $key 应为 stickyHeader", code.contains("stickyHeader(key = \"$key\")"))
            assertFalse("表头 $key 不得退回随滚动的 item", code.contains("item(key = \"$key\")"))
        }
        assertTrue("列表区用 weight(1f) 吃剩余高（原先 fillMaxSize 语义上是抢整屏）", code.contains("modifier = ComposeModifier.weight(1f)"))
    }

    /** stickyHeader 的内容会盖在滚过的行之上，没有底色 ⇒ 行文字从表头字缝里穿出来 */
    @Test
    fun `sticky headers carry an opaque background`() {
        listOf("CharTableHeader", "ActionTableHeader").forEach { name ->
            val body = src.substringAfter("private fun $name(").substringBefore("\n}")
            assertTrue(
                "$name 的表头 Row 必须挂 surface 底色（sticky 时会盖住从下方滚过的行）",
                body.contains(".background(MaterialTheme.colorScheme.surface)"),
            )
        }
    }

    /**
     * V37-3 任务 B：信息头昵称+段位合并成单个 Text（SpanStyle 定向染色），
     * 结构特征串闸门——`alignByBaseline` 那套基线 hack 与 8dp Spacer 常量都不许回来。
     */
    @Test
    fun `player info header merges nickname and tier into one text`() {
        val body = codeOnly(src).substringAfter("internal fun PlayerInfoHeader(").substringBefore("\n}")
        assertTrue("昵称+段位应为单个 Text + buildAnnotatedString", body.contains("buildAnnotatedString {"))
        assertTrue("段位走 SpanStyle 染色", body.contains("SpanStyle(color = tierColor(tier))"))
        assertTrue("段位在 span 段里 append", body.contains("withStyle(tierSpan) { append(tier) }"))
        assertFalse("合并成单 Text 后不再需要基线 hack", body.contains("alignByBaseline()"))
        assertFalse("昵称↔段位 8dp Spacer 常量声明应删除", Regex("const val PLAYER_INFO_NICK_TIER_GAP_DP").containsMatchIn(codeOnly(src)))
        // 🔴 buildAnnotatedString 的 lambda 不是组合上下文：tierColor 必须在 Composable 体内先取好
        val lambda = body.substringAfter("buildAnnotatedString {").substringBefore("modifier = ComposeModifier.fillMaxWidth()")
        assertFalse("lambda 内不得调 @Composable 的 tierColor：\n$lambda", lambda.contains("tierColor("))
        assertTrue("整行左缘即对齐轴：合并后的 Text 应 fillMaxWidth", body.contains("modifier = ComposeModifier.fillMaxWidth()"))
    }
}
