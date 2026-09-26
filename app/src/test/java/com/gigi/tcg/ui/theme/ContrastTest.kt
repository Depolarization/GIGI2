package com.gigi.tcg.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {

    private fun linearize(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f
        else Math.pow(((channel + 0.055) / 1.055), 2.4).toFloat()

    private fun relativeLuminance(color: Color): Float =
        0.2126f * linearize(color.red) + 0.7152f * linearize(color.green) + 0.0722f * linearize(color.blue)

    private fun contrast(a: Color, b: Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val light = maxOf(la, lb)
        val dark = minOf(la, lb)
        return (light + 0.05f) / (dark + 0.05f)
    }

    @Test
    fun winColorLight_meetsWcagAA_onWhite() {
        val ratio = contrast(WinColorLight, Color.White)
        assertTrue("WinColorLight vs White = $ratio:1, need >= 4.5", ratio >= 4.5f)
    }

    @Test
    fun loseColorLight_meetsWcagAA_onWhite() {
        val ratio = contrast(LoseColorLight, Color.White)
        assertTrue("LoseColorLight vs White = $ratio:1, need >= 4.5", ratio >= 4.5f)
    }

    @Test
    fun goldColorLight_meetsWcagAA_onWhite() {
        val ratio = contrast(GoldColorLight, Color.White)
        assertTrue("GoldColorLight vs White = $ratio:1, need >= 4.5", ratio >= 4.5f)
    }

    @Test
    fun darkTierColors_areLocked() {
        // V9-B：深色档 win/lose 提亮到过 Card 容器(0xFF36343B) WCAG AA（4.63/4.58:1），锁新值
        assertEquals(Color(0xFF58B07E), WinColor)
        assertEquals(Color(0xFFE88080), LoseColor)
        assertEquals(Color(0xFFD4A643), GoldColor)
    }
}
