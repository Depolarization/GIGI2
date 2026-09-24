package com.gigi.tcg.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** 逐字移植 web/src/utils/__tests__/percent.test.ts（10 用例） */
class PercentTest {

    // ---- calcPercent（分母为 0 记 0）----

    @Test
    fun `calcPercent 常规计算`() {
        assertEquals(25.0, calcPercent(1.0, 4.0), 1e-9)
        assertEquals(66.66666666666667, calcPercent(2.0, 3.0), 1e-6) // 对齐 toBeCloseTo 精度
    }

    @Test
    fun `calcPercent 分母为 0 或负数 → 0`() {
        assertEquals(0.0, calcPercent(5.0, 0.0), 1e-9)
        assertEquals(0.0, calcPercent(5.0, -1.0), 1e-9)
    }

    @Test
    fun `calcPercent 分子为 0 → 0`() {
        assertEquals(0.0, calcPercent(0.0, 10.0), 1e-9)
    }

    // ---- formatPercent（对照 game.lua）----

    @Test
    fun `formatPercent 保留一位小数`() {
        assertEquals("12.3%", formatPercent(12.34))
        assertEquals("66.7%", formatPercent(66.666666))
    }

    @Test
    fun `formatPercent 整数值去尾点零`() {
        assertEquals("12%", formatPercent(12.0))
        assertEquals("12%", formatPercent(12.0))
        assertEquals("0%", formatPercent(0.0))
        assertEquals("100%", formatPercent(100.0))
    }

    @Test
    fun `formatPercent 非法值 → 0 百分比`() {
        assertEquals("0%", formatPercent(Double.NaN))
        assertEquals("0%", formatPercent(null))
        assertEquals("0%", formatPercent(null)) // TS undefined 与 null 在 Kotlin 侧统一为 null
        assertEquals("0%", formatPercent(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `formatPercent 0%（分母为 0 的组合用法）`() {
        assertEquals("0%", formatPercent(calcPercent(3.0, 0.0)))
    }

    // ---- percentSortKey（T5：胜率排序键）----

    @Test
    fun `percentSortKey 保留原值用于数值比较`() {
        assertEquals(9.4, percentSortKey(9.4), 1e-9)
        assertEquals(0.0, percentSortKey(0.0), 1e-9)
        assertEquals(100.0, percentSortKey(100.0), 1e-9)
    }

    @Test
    fun `percentSortKey 非法值统一取 0（排序稳定 不抛错）`() {
        assertEquals(0.0, percentSortKey(Double.NaN), 1e-9)
        assertEquals(0.0, percentSortKey(null), 1e-9)
        assertEquals(0.0, percentSortKey(null), 1e-9)
        assertEquals(0.0, percentSortKey(Double.POSITIVE_INFINITY), 1e-9)
    }

    @Test
    fun `percentSortKey 修复字典序陷阱 9% 不应排在 10% 之后`() {
        val rows = listOf(9.0, 10.0) // [{r:9},{r:10}]
        val sorted = rows.sortedByDescending { percentSortKey(it) }
        assertEquals(listOf(10.0, 9.0), sorted)
        // 对比：字符串比较会得到错误的 ["9%", "10%"]（降序下）
        val wrong = listOf("9%", "10%").sortedWith { a, b -> if (a < b) 1 else -1 }
        assertEquals(listOf("9%", "10%"), wrong)
    }
}
