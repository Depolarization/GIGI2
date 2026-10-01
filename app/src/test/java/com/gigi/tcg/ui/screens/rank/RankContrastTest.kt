// V39-H2 验收锁（G2 审计清单③「仅白天不达标」三处）：排行榜前三名名次数字的奖牌色。
//
// 锁三层，缺一不可：
//   1) 数值层：银/铜两档 ARGB 逐位钉死（改值必须连注释表一起改）；
//   2) 对比度层：两档 × **各自主题的页面底** `colorScheme.background` ≥ 正文级 4.5
//      （名次文案是 titleMedium=16sp 常规字重，按 WCAG 属正文档，够不上 large text 的 3.0 档）；
//   3) 源码层：第 1 名必须走 `LocalSemanticColors.current.gold`（随主题两档），
//      不许再直接 import 暗色档常量 GoldColor —— 那正是白天 2.14:1 的根因（跨档取色）。
//
// 对比度一律复用 com.gigi.tcg.ui.theme.ContrastUtils（同口径实现只留一份，避免测试里漂移）。

package com.gigi.tcg.ui.screens.rank

import com.gigi.tcg.ui.theme.ContrastUtils
import com.gigi.tcg.ui.theme.GoldColor
import com.gigi.tcg.ui.theme.GoldColorLight
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankContrastTest {

    private companion object {
        const val BODY_LEVEL = 4.5

        // 名次列的真实上屏底：LazyColumn / Row 都不带自有背景，落在 colorScheme.background 上
        // （夜 #141218 / 白 #FEF7FF）。G2 审计（.task/probe/contrast_audit_G2.out.txt）就是按这两档判的。
        const val DARK_BACKGROUND = 0xFF141218.toInt()
        const val LIGHT_BACKGROUND = 0xFFFEF7FF.toInt()

        val Medals = listOf(
            Medal("第 2 名 银", RANK_ARGB_SILVER, RANK_ARGB_SILVER_LIGHT, 2),
            Medal("第 3 名 铜", RANK_ARGB_BRONZE, RANK_ARGB_BRONZE_LIGHT, 3),
        )
    }

    private data class Medal(val label: String, val darkArgb: Int, val lightArgb: Int, val rank: Int)

    /**
     * main 源码，先剥块注释再剥 `//` 单行注释：本文件的口径注释里要引用旧写法
     * （`import GoldColor`、`isSystemInDarkTheme()`），不剥会把闸门误报成违规。
     */
    private fun readSource(): String {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/rank/RankRoute.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        return Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
            .replace(file.readText(), "")
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }

    /** RankRow 函数体：从签名到列 0 的第一个 "\n}" */
    private fun rankRowBody(src: String): String {
        val start = src.indexOf("private fun RankRow(")
        assertTrue("找不到 RankRow 函数", start >= 0)
        return src.substring(start, src.indexOf("\n}", start))
    }

    /** HSL 色相（度）/饱和度（%）：只用于「两档之间只许动亮度」这条锁，不参与对比度计算 */
    private fun hueSat(argb: Int): Pair<Double, Double> {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max == min) return 0.0 to 0.0
        val delta = max - min
        val hue = when (max) {
            r -> ((g - b) / delta) % 6.0
            g -> (b - r) / delta + 2.0
            else -> (r - g) / delta + 4.0
        } * 60.0
        val lightness = (max + min) / 2.0
        return ((hue + 360.0) % 360.0) to delta / (1.0 - kotlin.math.abs(2 * lightness - 1)) * 100.0
    }

    /** 两档值逐位钉死：旧单值 = 深档（夜达标、白天 2.45/3.48 掉穿），浅档是 G2 脚本给的保色相达标色 */
    @Test
    fun medalArgbConstants_areLocked() {
        assertEquals(0xFF9AA2AD.toInt(), RANK_ARGB_SILVER)
        assertEquals(0xFF697482.toInt(), RANK_ARGB_SILVER_LIGHT)
        assertEquals(0xFFB07A4A.toInt(), RANK_ARGB_BRONZE)
        assertEquals(0xFF986940.toInt(), RANK_ARGB_BRONZE_LIGHT)
    }

    /** 档位路由：darkTheme 必须真的换套；第 1 名与第 4 名及以后都返回 null（前者走 semantic.gold、后者走默认色） */
    @Test
    fun medalArgb_routesByThemeTier_andOwnsOnlyRank2And3() {
        Medals.forEach { medal ->
            assertEquals(medal.label, medal.darkArgb, medalArgb(medal.rank, darkTheme = true))
            assertEquals(medal.label, medal.lightArgb, medalArgb(medal.rank, darkTheme = false))
            assertNotEquals(
                "${medal.label} 两档必须不同值（单套做不到浅底暗、深底亮 —— G2 §4 两区间不相交）",
                medal.darkArgb,
                medal.lightArgb,
            )
        }
        assertNull("第 1 名不吃本表，走 semantic.gold", medalArgb(1, true))
        assertNull("第 1 名浅档也不吃本表", medalArgb(1, false))
        assertNull("第 4 名及以后不强调", medalArgb(4, true))
        assertNull("第 0/负数名次不强调", medalArgb(0, false))
    }

    /**
     * 核心验收：银/铜两档 × 各自主题底 ≥4.5（改前 白 2.45 / 3.48 ✗）。
     * 第 1 名同步验一遍 semantic.gold 两档（值取自 Theme.kt 的映射源，深 #D4A643 / 浅 #7B5E14）——
     * 改前是「白天也拿深档」= 2.14 ✗，现在浅档换 GoldColorLight。
     */
    @Test
    fun medalColors_meetWcagAA_onTheirOwnThemeBackground() {
        Medals.forEach { medal ->
            val dark = ContrastUtils.wcagContrast(medal.darkArgb, DARK_BACKGROUND)
            val light = ContrastUtils.wcagContrast(medal.lightArgb, LIGHT_BACKGROUND)
            assertTrue("${medal.label} 深档 × 夜 background = ${"%.2f".format(dark)}:1, need >= $BODY_LEVEL",
                dark >= BODY_LEVEL)
            assertTrue("${medal.label} 浅档 × 白 background = ${"%.2f".format(light)}:1, need >= $BODY_LEVEL",
                light >= BODY_LEVEL)
        }
        val goldDark = ContrastUtils.wcagContrast(ContrastUtils.toArgb(GoldColor), DARK_BACKGROUND)
        val goldLight = ContrastUtils.wcagContrast(ContrastUtils.toArgb(GoldColorLight), LIGHT_BACKGROUND)
        assertTrue("第 1 名 semantic.gold 深档 × 夜 background = ${"%.2f".format(goldDark)}:1",
            goldDark >= BODY_LEVEL)
        assertTrue("第 1 名 semantic.gold 浅档 × 白 background = ${"%.2f".format(goldLight)}:1",
            goldLight >= BODY_LEVEL)
    }

    /** 反例锁（根因留档）：把暗色档鎏金拿去压白底 = 白天 2.14，正是本轮了结的缺口，不许改回去 */
    @Test
    fun legacyCrossTierGold_isTheRegressionBeingClosed() {
        val ratio = ContrastUtils.wcagContrast(ContrastUtils.toArgb(GoldColor), LIGHT_BACKGROUND)
        assertTrue("旧写法（白天也吃 GoldColor 深档）应仍是不达标的 ${"%.2f".format(ratio)}:1", ratio < BODY_LEVEL)
    }

    /** 浅档只许沿亮度轴位移：色相/饱和度必须仍与深档同值，否则奖牌「金/银/铜」的认色信息会丢 */
    @Test
    fun medalLightVariants_keepHueAndSaturation_andAreDarker() {
        Medals.forEach { medal ->
            val (darkHue, darkSat) = hueSat(medal.darkArgb)
            val (lightHue, lightSat) = hueSat(medal.lightArgb)
            val distance = minOf(
                kotlin.math.abs(darkHue - lightHue),
                360.0 - kotlin.math.abs(darkHue - lightHue),
            )
            assertTrue("${medal.label} 两档色相漂 ${distance}°，应 ≤2°", distance <= 2.0)
            assertTrue(
                "${medal.label} 两档饱和度差 ${kotlin.math.abs(darkSat - lightSat)}%，应 <5%",
                kotlin.math.abs(darkSat - lightSat) < 5.0,
            )
            assertTrue(
                "${medal.label} 浅档必须比深档暗（浅底要暗字）",
                ContrastUtils.relativeLuminance(medal.lightArgb) <
                    ContrastUtils.relativeLuminance(medal.darkArgb),
            )
        }
    }

    /**
     * 源码闸门：
     *  a) RankRoute 不得再直接引用暗色档常量 `GoldColor`（跨档取色是本次根因）；
     *  b) 第 1 名必须经 `LocalSemanticColors.current.gold`；
     *  c) 浅档两枚必须真的接进 medalArgb 的档位分支，否则只是死常量；
     *  d) RankRow 名次 Text 必须吃 medalColor(...)，不许就地写 Color(0xFF..) 硬编码。
     */
    @Test
    fun sourceGate_medalColorRoutesThroughThemeAndTwoTiers() {
        val src = readSource()
        assertTrue(
            "RankRoute 不应再 import 暗色档 GoldColor（白天浅底上只有 2.14:1）",
            !src.contains("import com.gigi.tcg.ui.theme.GoldColor"),
        )
        assertTrue(
            "第 1 名必须走 semantic.gold（随主题两档）",
            src.contains("LocalSemanticColors.current.gold"),
        )
        listOf("SILVER", "BRONZE").forEach { name ->
            assertTrue(
                "浅档 RANK_ARGB_${name}_LIGHT 必须进档位分支",
                Regex("""RANK_ARGB_${name}_LIGHT""").containsMatchIn(
                    src.substringAfter("internal fun medalArgb(").substringBefore("\n}"),
                ),
            )
        }
        // 深浅判据照 TierColors.isDarkTierTheme：取 background 相对亮度，不用 isSystemInDarkTheme()
        val judge = src.substringAfter("private fun ColorScheme.isDarkRankTheme(): Boolean")
            .lineSequence().take(3).joinToString("\n")
        assertTrue("档位判据应对 colorScheme.background 做亮度判定，实际=$judge",
            judge.contains("relativeLuminance") && judge.contains("toArgb(background)"))
        assertTrue("档位判据不得用 isSystemInDarkTheme()", !src.contains("isSystemInDarkTheme()"))

        val body = rankRowBody(src)
        assertTrue("名次 Text 应吃 medalColor(rank)", body.contains("medalColor(rank)"))
        assertTrue("名次 Text 不应硬编码 Color(0x..)", !Regex("""Color\(0x""").containsMatchIn(body))
    }
}
