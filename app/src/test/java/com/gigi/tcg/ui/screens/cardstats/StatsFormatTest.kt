package com.gigi.tcg.ui.screens.cardstats

import org.junit.Assert.assertEquals
import org.junit.Test

/** formatStatPercent：本屏统计列专用，统一 1 位小数（含 .0），区别于 web/domain 的去尾语义。 */
class StatsFormatTest {

    @Test
    fun `保留一位小数`() {
        assertEquals("12.3%", formatStatPercent(12.34))
        assertEquals("66.7%", formatStatPercent(66.666666))
        assertEquals("23.0%", formatStatPercent(23.0))
    }

    @Test
    fun `整数值不去尾 与 web formatPercent 相反`() {
        // web/domain 侧 formatPercent(12.0)=="12%"；本屏要求统一位数 → 保留 .0
        assertEquals("12.0%", formatStatPercent(12.0))
        assertEquals("0.0%", formatStatPercent(0.0))
        assertEquals("100.0%", formatStatPercent(100.0))
    }

    @Test
    fun `非法值统一为 0点0`() {
        assertEquals("0.0%", formatStatPercent(null))
        assertEquals("0.0%", formatStatPercent(Double.NaN))
        assertEquals("0.0%", formatStatPercent(Double.POSITIVE_INFINITY))
        assertEquals("0.0%", formatStatPercent(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `使用 US Locale 小数点固定为点号`() {
        // 断言不会出现逗号分隔（依赖 Locale.US，不随系统区域漂移）
        val out = formatStatPercent(1.5)
        assertEquals("1.5%", out)
        assertEquals(false, out.contains(','))
    }

    // ---- ranksWithTies：V29 需求 8 的 # 列名次（并列同名次、下一名跳号） ----

    @Test
    fun `名次并列时同名次且下一名跳号`() {
        // 竞赛排序的 1-2-2-4 形状：30 第一、20 第二、两个 10 并列第三、4 跳到第五
        val sorted = listOf(30, 20, 10, 10, 4)
        val ranks = ranksWithTies(sorted) { it }
        assertEquals(listOf(1, 2, 3, 3, 5), ranks.toList())
    }

    @Test
    fun `全相同时全部并列第一`() {
        val ranks = ranksWithTies(listOf(0, 0, 0, 0)) { it }
        assertEquals(listOf(1, 1, 1, 1), ranks.toList())
    }

    @Test
    fun `无并列时名次即行号`() {
        val ranks = ranksWithTies(listOf(9, 7, 5, 1)) { it }
        assertEquals(listOf(1, 2, 3, 4), ranks.toList())
    }

    @Test
    fun `空表返回空数组`() {
        assertEquals(0, ranksWithTies(emptyList<Int>()) { it }.size)
    }

    @Test
    fun `四个 40 并列第二后跳到第六`() {
        val ranks = ranksWithTies(listOf(50, 40, 40, 40, 40, 10, 5)) { it }
        assertEquals(listOf(1, 2, 2, 2, 2, 6, 7), ranks.toList())
    }
}
