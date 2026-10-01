package com.gigi.tcg.ui.theme

import androidx.compose.ui.graphics.Color
import java.io.File
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

        // 🔴 V39-G 用 material3 1.3.2 sources 核实：无变体 `Card()` 的容器 = `FilledCardTokens.ContainerColor`
        // = `surfaceContainerHighest` ⇒ 夜 #36343B / 白 #E6E0E9。旧注释里的 #F7F2FA 是
        // `surfaceContainerLow`（ElevatedCard / Sheet 档），拿它当「Card 底」是这一轮两个缺口的共同根因。
        const val LightCard = 0xFFE6E0E9L
        const val DarkCard = 0xFF36343BL

        // 原注释误记为 0x1C1B1F（比实际 surface 亮，属保守基准），此处保留不动以免改出口径。
        const val White = 0xFFFFFFFFL

        /** 白天侧真会承接到语义色前景的容器：页面底 / ElevatedCard·Sheet / Card / 弹窗 / 纯白 */
        val LightContainers = listOf(
            "surface" to 0xFFFEF7FFL,
            "surfaceContainerLow" to 0xFFF7F2FAL,
            "surfaceContainer" to 0xFFF3EDF7L,
            "surfaceContainerHigh" to 0xFFECE6F0L,
            "surfaceContainerHighest(Card)" to LightCard,
            "white" to White,
        )
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

    /**
     * 🔴 V39-H：亮色档语义色的**真底**是 `surfaceContainerHighest #E6E0E9`（material3 1.3.2
     * `FilledCardTokens.ContainerColor`，V39-G 用 M3 sources 核实），不是 `#F7F2FA`
     * （那是 `surfaceContainerLow`＝ElevatedCard/Sheet 档）。旧亮色档按 `#F7F2FA` 调，
     * 落在普通 Card 上实测 3.89/3.94/3.90 ⇒ 白天正文不达标。这条锁真底。
     */
    @Test
    fun lightSemantics_meetWcagAA_onLightCardContainer() {
        listOf(
            "WinColorLight" to WinColorLight.argbLong(),
            "LoseColorLight" to LoseColorLight.argbLong(),
            "GoldColorLight" to GoldColorLight.argbLong(),
        ).forEach { (name, fg) ->
            val ratio = contrastRatio(fg, LightCard)
            assertTrue("$name vs light Card(#E6E0E9) = ${"%.2f".format(ratio)}:1, need >= 4.5", ratio >= 4.5)
        }
    }

    /**
     * V39-H：亮色档不能只对着 Card 调——ElevatedCard/Sheet(`surfaceContainerLow`)、
     * 页面底(`surface`)、弹窗底(`surfaceContainerHigh`)与纯白都要一起过正文级 AA，
     * 否则换一档容器就复发。
     */
    @Test
    fun lightSemantics_meetWcagAA_onEveryLightContainer() {
        listOf(
            "WinColorLight" to WinColorLight.argbLong(),
            "LoseColorLight" to LoseColorLight.argbLong(),
            "GoldColorLight" to GoldColorLight.argbLong(),
        ).forEach { (name, fg) ->
            LightContainers.forEach { (container, bg) ->
                val ratio = contrastRatio(fg, bg)
                assertTrue("$name vs $container = ${"%.2f".format(ratio)}:1, need >= 4.5", ratio >= 4.5)
            }
        }
    }

    /** 暗色档三枚对夜 Card / 夜弹窗底的正文档锁（gold 也在内，之前只锁过 win/lose 对 surface） */
    @Test
    fun darkSemantics_meetWcagAA_onDarkContainers() {
        listOf(
            "WinColor" to WinColor.argbLong(),
            "LoseColor" to LoseColor.argbLong(),
            "GoldColor" to GoldColor.argbLong(),
        ).forEach { (name, fg) ->
            listOf("darkCard" to DarkCard, "darkDialog" to 0xFF2B2930L).forEach { (container, bg) ->
                val ratio = contrastRatio(fg, bg)
                assertTrue("$name vs $container = ${"%.2f".format(ratio)}:1, need >= 4.5", ratio >= 4.5)
            }
        }
    }

    /**
     * V39-H：语义色**只作前景**这条口径的源码闸门——六枚值一旦有调用点拿去当背景块，
     * 就必然出现「浅底浅字 / 深底深字」的错配。
     * ⚠️ 边界：本闸门只抓**直接引用常量**（`background(WinColor)` / `containerColor = GoldColor` 这类，
     * 排行榜 `RankRoute:255` 就是这一路）；`MyFavoritesPage:96` 的 `background(semantic.win)`
     * 走的是 LocalSemanticColors 槽位，属 V39-G §3-1 第 1 条「缺 onWin/onLose 成对前景槽」的问题，
     * 由持锁棒按 D2 的成对 API 收口，不在本闸门口径内（这里放开会让本轮只动色值的范围越界）。
     */
    @Test
    fun semanticColors_areForegroundOnly_inSourceTree() {
        val srcRoot = File("src/main/java/com/gigi/tcg")
        assertTrue("源码目录不存在: ${srcRoot.absolutePath}", srcRoot.exists())
        val offenders = srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "Color.kt" }
            .filter { file ->
                val stripped = file.readText().lineSequence()
                    .map { it.substringBefore("//") }
                    .joinToString("\n")
                listOf(
                    Regex("""background\(\s*\w*(WinColor|LoseColor|GoldColor)"""),
                    Regex("""(containerColor|backgroundColor)\s*=\s*\w*(WinColor|LoseColor|GoldColor)"""),
                ).any { it.containsMatchIn(stripped) }
            }
            .map { it.relativeTo(srcRoot).path }
            .toList()
        assertTrue("语义色常量被当背景块用了（应只作前景，见 Color.kt 口径注释）: $offenders", offenders.isEmpty())
    }
}
