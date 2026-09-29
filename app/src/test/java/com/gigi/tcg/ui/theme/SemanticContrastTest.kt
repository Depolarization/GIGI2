package com.gigi.tcg.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V8F/V9-B：深色档胜负语义色的 WCAG AA 对比度锁。
 * 口径：对 dark surface(0xFF141218, material3 1.3.2 实测基线; compose-bom 2025.09.00 不抬高版本;
 *   ⚠️ 代码常量 DarkSurface=0xFF1C1B1FL 是旧误记值，比实际 surface 亮，属保守基准)，
 *   以及对深色 surfaceContainerHighest(0xFF36343B, 原注释误称"Card 容器"——非 surface 也非 Card, material3 1.3.2 实测)。
 * win/lose 在 surfaceContainerHighest 上从 3.46:1 提亮到 4.63/4.58:1。亮色档口径与 ContrastTest 对称（对白底 surface）。
 * 纯 JVM 单测：只把 Color 拆成 sRGB 分量算数，不碰 android.graphics。
 *
 * ⚠️ 实测坑：`Color(0xFF58B07Eu)`（**UInt** 重载）在本工程 Compose 版本上是坏的 ——
 * 分量解出 r=0.0 / g=0.0 / b=NaN（位段没被重排）。`Color(0xFF58B07E)`（Int → **Long** 重载，
 * 生产 Color.kt 用的就是这个）才正常，且 `red/green/blue` 返回的是 **sRGB 0..1**（非线性）。
 * 所以构造测试基准色一律用不带 `u` 的字面量，分量要 WCAG 前必须自己 gamma 线性化。
 */
class SemanticContrastTest {

    /** @param argb 0x00RRGGBB（与 [argbLong] 口径一致，取分量用位与 `and`，不是逻辑与） */
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

    // Compose Color 是纯 value class，分量可直接在 JVM 取（0f..1f，sRGB 口径）；统一转成 0xRRGGBB 整数口径
    private fun Color.argbLong(): Long {
        val r = Math.round(red * 255f).toLong()
        val g = Math.round(green * 255f).toLong()
        val b = Math.round(blue * 255f).toLong()
        return (r shl 16) or (g shl 8) or b
    }

    private companion object {
        // 基准色用 Long 字面量（与 relativeLuminance 形参同口径）；`u` 后缀会走坏的 UInt 重载
        const val DarkSurface = 0xFF1C1B1FL
        const val DarkCard = 0xFF36343BL
        const val White = 0xFFFFFFFFL
    }

    @Test
    fun colorComponents_readableOnJvm() {
        val c = Color(0xFF58B07E)
        assertEquals(0x58L, c.argbLong() shr 16 and 0xFF)
        assertEquals(0xB0L, c.argbLong() shr 8 and 0xFF)
        assertEquals(0x7EL, c.argbLong() and 0xFF)
    }

    @Test
    fun formula_selfCheck_blackOnWhiteIs21() {
        assertEquals(21.0, contrastRatio(0xFF000000L, White), 0.1)
    }

    @Test
    fun formula_selfCheck_sameColorIs1() {
        assertEquals(1.0, contrastRatio(DarkSurface, DarkSurface), 0.001)
    }

    @Test
    fun winColor_meetsWcagAA_onDarkSurface() {
        val ratio = contrastRatio(WinColor.argbLong(), DarkSurface)
        assertTrue("WinColor vs dark surface = $ratio:1, need >= 4.5", ratio >= 4.5)
    }

    @Test
    fun loseColor_meetsWcagAA_onDarkSurface() {
        val ratio = contrastRatio(LoseColor.argbLong(), DarkSurface)
        assertTrue("LoseColor vs dark surface = $ratio:1, need >= 4.5", ratio >= 4.5)
    }

    @Test
    fun winColor_meetsWcagAA_onDarkCard() {
        // V9-B：胜负文案实际渲染在深色 surfaceContainerHighest(0xFF36343B, material3 1.3.2 实测)上，锁该容器口径（实测 4.63:1）
        val ratio = contrastRatio(WinColor.argbLong(), DarkCard)
        assertTrue("WinColor vs dark Card = $ratio:1, need >= 4.5", ratio >= 4.5)
    }

    @Test
    fun loseColor_meetsWcagAA_onDarkCard() {
        // V9-B：同上，LoseColor 对 surfaceContainerHighest(0xFF36343B) 实测 4.58:1
        val ratio = contrastRatio(LoseColor.argbLong(), DarkCard)
        assertTrue("LoseColor vs dark Card = $ratio:1, need >= 4.5", ratio >= 4.5)
    }

    @Test
    fun goldColor_stillMeetsWcagAA_onDarkSurface() {
        // GoldColor 本次未动（排行榜前三固定色），此断言兼作"没被误伤"的锁
        val ratio = contrastRatio(GoldColor.argbLong(), DarkSurface)
        assertTrue("GoldColor vs dark surface = $ratio:1, need >= 4.5", ratio >= 4.5)
    }

    @Test
    fun winColorLight_regressionLock_onWhite() {
        val ratio = contrastRatio(WinColorLight.argbLong(), White)
        assertTrue("WinColorLight vs white = $ratio:1, need >= 4.5", ratio >= 4.5)
    }

    @Test
    fun loseColorLight_regressionLock_onWhite() {
        val ratio = contrastRatio(LoseColorLight.argbLong(), White)
        assertTrue("LoseColorLight vs white = $ratio:1, need >= 4.5", ratio >= 4.5)
    }
}
