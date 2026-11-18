// 报告 §8.2：官方 attr 的冒号位置不统一（key 带冒号 / value 前缀冒号两种形态），
// 归一函数要保证两种输入上屏结果一致；这里用真实 entry_page 片段锁死 6 条。

package com.gigi.tcg.domain

import com.gigi.tcg.data.model.CardAttr
import com.gigi.tcg.data.model.CardBasicInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class CardDetailNormalizeTest {

    private fun attr(key: String, vararg value: String) = CardAttr(key = key, value = value.toList())

    @Test
    fun `value 前缀全角冒号被去掉`() {
        assertEquals("水元素", CardDetailParser.normalizeAttrValue("：水元素"))
        assertEquals("其他武器", CardDetailParser.normalizeAttrValue("：其他武器"))
        assertEquals("魔物", CardDetailParser.normalizeAttrValue("：魔物"))
    }

    @Test
    fun `value 尾随冒号被去掉`() {
        assertEquals("水元素", CardDetailParser.normalizeAttrValue("水元素："))
        assertEquals("2点", CardDetailParser.normalizeAttrValue("2点:"))
    }

    @Test
    fun `首尾空白与连续空白折叠`() {
        assertEquals("水元素", CardDetailParser.normalizeAttrValue("  水元素  "))
        assertEquals("角色邀请 友好对局", CardDetailParser.normalizeAttrValue("角色邀请 \t\u00A0友好对局"))
    }

    @Test
    fun `中间冒号保持原样`() {
        assertEquals("快速行动：装备给我方", CardDetailParser.normalizeAttrValue("快速行动：装备给我方"))
    }

    @Test
    fun `空输入与纯冒号返回空串`() {
        assertEquals("", CardDetailParser.normalizeAttrValue(null))
        assertEquals("", CardDetailParser.normalizeAttrValue("   "))
        assertEquals("", CardDetailParser.normalizeAttrValue("："))
    }

    @Test
    fun `元素判定同时接受两种冒号形态`() {
        val keyColon = CardBasicInfo(attr = listOf(attr("元素：", "火元素")))
        val valueColon = CardBasicInfo(attr = listOf(attr("元素", "：水元素")))

        assertEquals(CardElement.Fire, CardDetailParser.elementOf(keyColon))
        assertEquals(CardElement.Water, CardDetailParser.elementOf(valueColon))
    }
}
