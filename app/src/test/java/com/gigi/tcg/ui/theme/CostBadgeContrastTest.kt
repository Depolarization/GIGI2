package com.gigi.tcg.ui.theme

import com.gigi.tcg.domain.CardElement
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 费用徽章对比度锁（设计稿 05 + 04 的 WCAG 复核表）。
 *
 * 报告初版在夜间用白字压浅色 pastel 底，实测只有 1.30–1.90:1；
 * 本测试把修正后的口径钉死：
 * - 元素骰文字：亮白 / 夜间深 `#101418`，两档都 ≥ 4.5
 * - 无色骰：文字 ≥ 4.5，边框（outline）对骰底/卡面都 ≥ 3.0
 * - 充能骰：文字 ≥ 4.5
 * - 元素/充能徽章底色 vs 卡面 ≥ 3.0（非文本 UI 边界）
 */
class CostBadgeContrastTest {

    private companion object {
        const val BODY_LEVEL = 4.5
        const val NON_TEXT_LEVEL = 3.0
    }

    private val elements = CardElement.entries

    @Test
    fun `元素骰数字两档都过正文 AA`() {
        elements.forEach { element ->
            val light = ContrastUtils.wcagContrast(ElementColors.ink(false), ElementColors.light(element))
            val dark = ContrastUtils.wcagContrast(ElementColors.ink(true), ElementColors.dark(element))
            assertTrue("亮色 $element 元素骰数字 $light", light >= BODY_LEVEL)
            assertTrue("夜间 $element 元素骰数字 $dark", dark >= BODY_LEVEL)
        }
    }

    @Test
    fun `元素骰底色对技能卡容器过非文本 3 比 1`() {
        elements.forEach { element ->
            val light = ContrastUtils.wcagContrast(ElementColors.light(element), LightColors.surfaceContainerLow)
            val dark = ContrastUtils.wcagContrast(ElementColors.dark(element), DarkColors.surfaceContainerLow)
            assertTrue("亮色 $element 徽章底 $light", light >= NON_TEXT_LEVEL)
            assertTrue("夜间 $element 徽章底 $dark", dark >= NON_TEXT_LEVEL)
        }
    }

    @Test
    fun `无色骰文字过正文 AA`() {
        val light = ContrastUtils.wcagContrast(ElementColors.untypedText(false), ElementColors.untypedBackground(false))
        val dark = ContrastUtils.wcagContrast(ElementColors.untypedText(true), ElementColors.untypedBackground(true))
        assertTrue("亮色无色骰文字 $light", light >= BODY_LEVEL)
        assertTrue("夜间无色骰文字 $dark", dark >= BODY_LEVEL)
    }

    @Test
    fun `无色骰边框对骰底与卡面都过非文本 3 比 1`() {
        for (dark in listOf(false, true)) {
            val border = ElementColors.untypedBorder(dark)
            val badge = ElementColors.untypedBackground(dark)
            val card = ElementColors.cardBackground(dark)
            val onBadge = ContrastUtils.wcagContrast(border, badge)
            val onCard = ContrastUtils.wcagContrast(border, card)
            assertTrue("dark=$dark 边框/骰底 $onBadge", onBadge >= NON_TEXT_LEVEL)
            assertTrue("dark=$dark 边框/卡面 $onCard", onCard >= NON_TEXT_LEVEL)
        }
    }

    @Test
    fun `充能骰文字过正文 AA`() {
        val light = ContrastUtils.wcagContrast(ElementColors.energyInk(false), ElementColors.energy(false))
        val dark = ContrastUtils.wcagContrast(ElementColors.energyInk(true), ElementColors.energy(true))
        assertTrue("亮色充能骰 $light", light >= BODY_LEVEL)
        assertTrue("夜间充能骰 $dark", dark >= BODY_LEVEL)
    }

    @Test
    fun `充能骰底色对技能卡容器过非文本 3 比 1`() {
        val light = ContrastUtils.wcagContrast(ElementColors.energy(false), LightColors.surfaceContainerLow)
        val dark = ContrastUtils.wcagContrast(ElementColors.energy(true), DarkColors.surfaceContainerLow)
        assertTrue("亮色充能底 $light", light >= NON_TEXT_LEVEL)
        assertTrue("夜间充能底 $dark", dark >= NON_TEXT_LEVEL)
    }
}
