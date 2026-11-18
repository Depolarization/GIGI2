package com.gigi.tcg.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 费用推断：普通攻击的 energy 是无色骰，元素爆发的 energy 才是充能。
 * 误读会把普通攻击标成「充能」，是报告 07 风险表点名的坑。
 */
class CardCostTest {

    @Test
    fun `普通攻击是元素骰加无色骰`() {
        assertEquals(
            listOf(SkillCost(CostKind.Element, 1), SkillCost(CostKind.Untyped, 2)),
            CardDetailParser.costsFor("普通攻击", life = 1, energy = 2),
        )
    }

    @Test
    fun `元素战技只有元素骰`() {
        assertEquals(
            listOf(SkillCost(CostKind.Element, 3)),
            CardDetailParser.costsFor("元素战技", life = 3, energy = 0),
        )
    }

    @Test
    fun `元素爆发是元素骰加充能`() {
        assertEquals(
            listOf(SkillCost(CostKind.Element, 3), SkillCost(CostKind.Energy, 3)),
            CardDetailParser.costsFor("元素爆发", life = 3, energy = 3),
        )
    }

    @Test
    fun `零费用不显示徽章`() {
        assertTrue(CardDetailParser.costsFor("元素战技", life = 0, energy = 0).isEmpty())
        assertTrue(CardDetailParser.costsFor(null, life = null, energy = null).isEmpty())
    }

    @Test
    fun `未知技能类型退化为中性徽章且不误标充能`() {
        assertEquals(
            listOf(SkillCost(CostKind.Neutral, 2)),
            CardDetailParser.costsFor("装备牌", life = 2, energy = 0),
        )
        assertEquals(
            listOf(SkillCost(CostKind.Neutral, 2)),
            CardDetailParser.costsFor("", life = 2, energy = 3),
        )
    }
}
