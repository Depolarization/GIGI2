// 段位文本色的纯 JVM 单测：锁「段位名 → 色」的命中规则、四档互不混淆，
// 以及对比度门槛（口径照 SemanticContrastTest：自己算 WCAG，不碰 android.graphics）。

package com.gigi.tcg.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TierColorsTest {

    /** @param argb 0xAARRGGBB，与 [tierColorArgb] 同口径 */
    private fun relativeLuminance(argb: Long): Double {
        fun lin(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(((argb shr 16) and 0xFF).toInt()) +
            0.7152 * lin(((argb shr 8) and 0xFF).toInt()) +
            0.0722 * lin((argb and 0xFF).toInt())
    }

    private fun contrastRatio(a: Long, b: Long): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun Color.argbLong(): Long {
        val r = Math.round(red * 255f).toLong()
        val g = Math.round(green * 255f).toLong()
        val b = Math.round(blue * 255f).toLong()
        return (r shl 16) or (g shl 8) or b
    }

    /** 段位名 → 0xAARRGGBB（Long 口径，供对比度计算）；未命中即失败，不静默按 0 计算 */
    private fun argbOf(tier: String): Long {
        val argb = tierColorArgb(tier)
        assertNotNull("$tier 应有段位色", argb)
        return argb!!.toLong() and 0xFFFFFFFFL
    }

    private companion object {
        const val White = 0xFFFFFFFFL
        // M3 默认 lightColorScheme 的几档浅容器：段位文案实际落在弹窗底/卡片底上，
        // 浅弹窗底（surfaceContainerHigh ≈0xFFECE6F0）比纯白更吃对比度
        const val CardSurfaceLight = 0xFFF7F2FAL
        const val DialogSurfaceLight = 0xFFECE6F0L
        const val DarkSurface = 0xFF1C1B1FL
        const val DarkDialogSurface = 0xFF2B2932L

        val Tiers = listOf("黄铜", "星银", "赤金", "影幻")
    }

    @Test
    fun tierColorArgb_starredLabel_hitsTierColor() {
        // 上屏文案是 tierLabel() 的产物（段位名 + ★），必须按前缀命中
        assertEquals(tierColorArgb("黄铜"), tierColorArgb("黄铜★★★"))
        assertEquals(TIER_ARGB_GOLD, tierColorArgb("赤金★★★★★"))
    }

    @Test
    fun tierColorArgb_englishLabel_hitsTierColor() {
        // tierLabel() 在英文环境返回 "Brass★★" 等，只认中文会漏色
        assertEquals(tierColorArgb("黄铜"), tierColorArgb("Brass★★"))
        assertEquals(tierColorArgb("星银"), tierColorArgb("Silver"))
        assertEquals(tierColorArgb("赤金"), tierColorArgb("Gold★★★★★"))
        assertEquals(tierColorArgb("影幻"), tierColorArgb("Phantom"))
    }

    @Test
    fun tierColorArgb_traditionalLabel_hitsTierColor() {
        // 繁中 tier_* 用的是异体字「黃/銀」，与简化字不同码位，必须单独收键
        assertEquals(TIER_ARGB_BRASS, tierColorArgb("黃銅★★"))
        assertEquals(TIER_ARGB_SILVER, tierColorArgb("星銀"))
        assertEquals(TIER_ARGB_GOLD, tierColorArgb("赤金"))
        assertEquals(TIER_ARGB_PHANTOM, tierColorArgb("影幻★★★"))
    }

    @Test
    fun tierColorArgb_allTiers_resolved() {
        assertEquals(TIER_ARGB_BRASS, tierColorArgb("黄铜"))
        assertEquals(TIER_ARGB_SILVER, tierColorArgb("星银"))
        assertEquals(TIER_ARGB_GOLD, tierColorArgb("赤金"))
        assertEquals(TIER_ARGB_PHANTOM, tierColorArgb("影幻"))
    }

    @Test
    fun tierColorArgb_tiersAreDistinct() {
        val colors = Tiers.mapNotNull(::tierColorArgb).toSet()
        assertEquals("四档段位色必须两两可辨，实际=$colors", Tiers.size, colors.size)
    }

    @Test
    fun tierColorArgb_unrankedOrUnknown_returnsNull() {
        // null / 空串 /「无段位」文案 / 未知段位都不强调，交给主题默认色
        assertNull(tierColorArgb(null))
        assertNull(tierColorArgb(""))
        assertNull(tierColorArgb("   "))
        assertNull(tierColorArgb("无段位"))
        assertNull(tierColorArgb("Unranked"))
        assertNull(tierColorArgb("青铜"))
    }

    /**
     * 四档段位色在四种实际容器底上都要 ≥ 3.0:1（契约门槛是纯白；浅弹窗底与深色弹窗底
     * 是更严苛的真实场景，一并锁住，避免日后有人把段位色挪到别的容器上就悄悄失读）。
     */
    @Test
    fun tierColors_readableOnAllSurfaces() {
        val backgrounds = listOf(
            "white" to White,
            "lightCard" to CardSurfaceLight,
            "lightDialog" to DialogSurfaceLight,
            "darkSurface" to DarkSurface,
            "darkDialog" to DarkDialogSurface,
        )
        Tiers.forEach { tier ->
            val argb = argbOf(tier)
            backgrounds.forEach { (name, bg) ->
                val ratio = contrastRatio(argb, bg)
                assertTrue("$tier vs $name = $ratio:1, need >= 3.0", ratio >= 3.0)
            }
        }
    }

    @Test
    fun tierColorArgb_outputIsPlainSrgbColor() {
        // 纯函数值与 Compose Color 口径一致：Color(Int) 解出的分量必须原样回读
        val c = Color(argbOf("影幻").toInt())
        assertEquals(0x8A6AE0L, c.argbLong())
        assertEquals(TIER_ARGB_PHANTOM, argbOf("影幻").toInt())
    }
}
