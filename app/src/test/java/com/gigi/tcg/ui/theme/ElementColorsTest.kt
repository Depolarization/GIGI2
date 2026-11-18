package com.gigi.tcg.ui.theme

import com.gigi.tcg.domain.CardElement
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 元素关键词（正文着色）对技能卡容器 `surfaceContainerLow` 的对比度锁。
 * 正文级门槛 4.5:1；亮暗两档 × 8 元素 + Unknown 全部覆盖。
 */
class ElementColorsTest {

    private companion object {
        const val BODY_LEVEL = 4.5
    }

    private val elements = CardElement.entries

    @Test
    fun `亮色元素关键词对技能卡容器过正文 AA`() {
        elements.forEach { element ->
            val ratio = ContrastUtils.wcagContrast(
                ElementColors.light(element),
                LightColors.surfaceContainerLow,
            )
            assertTrue("亮色 $element 对比度 $ratio", ratio >= BODY_LEVEL)
        }
    }

    @Test
    fun `夜间元素关键词对技能卡容器过正文 AA`() {
        elements.forEach { element ->
            val ratio = ContrastUtils.wcagContrast(
                ElementColors.dark(element),
                DarkColors.surfaceContainerLow,
            )
            assertTrue("夜间 $element 对比度 $ratio", ratio >= BODY_LEVEL)
        }
    }
}
