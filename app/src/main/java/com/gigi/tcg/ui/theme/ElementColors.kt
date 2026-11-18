// 元素固定色板（对齐设计稿 2026-11-14-card-detail-skills）。
//
// 为什么要分亮/暗两档且夜间换深色字：
// 夜间元素底色是浅色 pastel（如风 #5EEAD4），白字对比度只有 1.30–1.90:1，远低于 WCAG AA 小字 4.5:1。
// 因此夜间元素骰文字统一用深色 ink `#101418`，修正后 9.75–14.27:1。
// 无色骰不走元素色：底 = surfaceContainerHighest、边框 = outline（非文本 3:1 见 CostBadgeContrastTest）。
//
// 实测对比度（ContrastUtils，正文门槛 4.5）：
//   亮色白字 × 8 元素底色：5.36（冰）–17.07（物理）
//   夜间深字 × 8 元素底色：9.75（火）–14.27（物理）
//   充能骰：亮 5.43 / 暗 9.16
// 改任一色值必须同步改 test/.../CostBadgeContrastTest.kt 与 ElementColorsTest.kt。

package com.gigi.tcg.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.gigi.tcg.domain.CardElement

internal object ElementColors {

    // 亮色档：元素徽章底色（深一档，配白字）
    private val WindLight = Color(0xFF0F766E)
    private val WaterLight = Color(0xFF1E63C7)
    private val FireLight = Color(0xFFB42318)
    private val ThunderLight = Color(0xFF6D28D9)
    private val IceLight = Color(0xFF0E7490)
    private val RockLight = Color(0xFF92400E)
    private val GrassLight = Color(0xFF3F6212)
    private val PhysicalLight = Color(0xFF1D1B20)

    // 夜间档：元素徽章底色（浅色 pastel，配深字）
    private val WindDark = Color(0xFF5EEAD4)
    private val WaterDark = Color(0xFF93C5FD)
    private val FireDark = Color(0xFFFCA5A5)
    private val ThunderDark = Color(0xFFC4B5FD)
    private val IceDark = Color(0xFF67E8F9)
    private val RockDark = Color(0xFFFCD34D)
    private val GrassDark = Color(0xFFBEF264)
    private val PhysicalDark = Color(0xFFE6E0E9)

    /** 元素骰文字墨色：亮色白、夜间深（夜间 pastel 底上的白字不达标） */
    val InkLight = Color(0xFFFFFFFF)
    val InkDark = Color(0xFF101418)

    /** 充能骰：亮色深橙底白字 / 夜间浅金底深字 */
    val EnergyLight = Color(0xFF9A5B00)
    val EnergyInkLight = Color(0xFFFFFFFF)
    val EnergyDark = Color(0xFFF5C77E)
    val EnergyInkDark = Color(0xFF3A2600)

    fun light(element: CardElement): Color = when (element) {
        CardElement.Wind -> WindLight
        CardElement.Water -> WaterLight
        CardElement.Fire -> FireLight
        CardElement.Thunder -> ThunderLight
        CardElement.Ice -> IceLight
        CardElement.Rock -> RockLight
        CardElement.Grass -> GrassLight
        CardElement.Physical -> PhysicalLight
        CardElement.Unknown -> LightColors.primary
    }

    fun dark(element: CardElement): Color = when (element) {
        CardElement.Wind -> WindDark
        CardElement.Water -> WaterDark
        CardElement.Fire -> FireDark
        CardElement.Thunder -> ThunderDark
        CardElement.Ice -> IceDark
        CardElement.Rock -> RockDark
        CardElement.Grass -> GrassDark
        CardElement.Physical -> PhysicalDark
        CardElement.Unknown -> DarkColors.primary
    }

    fun color(element: CardElement, dark: Boolean): Color =
        if (dark) dark(element) else light(element)

    fun ink(dark: Boolean): Color = if (dark) InkDark else InkLight

    fun energy(dark: Boolean): Color = if (dark) EnergyDark else EnergyLight

    fun energyInk(dark: Boolean): Color = if (dark) EnergyInkDark else EnergyInkLight

    /** 无色骰：底用最高档容器，文字用 onSurfaceVariant（7.20/7.21） */
    fun untypedBackground(dark: Boolean): Color =
        if (dark) DarkColors.surfaceContainerHighest else LightColors.surfaceContainerHighest

    /** 无色骰边框用 outline（不是 outlineVariant）：对骰底 3.51/3.87，对卡面 4.13/5.39 */
    fun untypedBorder(dark: Boolean): Color =
        if (dark) DarkColors.outline else LightColors.outline

    fun untypedText(dark: Boolean): Color =
        if (dark) DarkColors.onSurfaceVariant else LightColors.onSurfaceVariant

    /** 技能卡容器：显式 surfaceContainerLow，与对比度测试基准一致 */
    fun cardBackground(dark: Boolean): Color =
        if (dark) DarkColors.surfaceContainerLow else LightColors.surfaceContainerLow

    @Composable
    @ReadOnlyComposable
    fun isDark(): Boolean = isSystemInDarkTheme()
}
