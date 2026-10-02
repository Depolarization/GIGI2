// 段位文本色的纯 JVM 单测：锁「段位名 → 色」的命中规则、四档互不混淆、四档**两档**常量的精确 ARGB 值
// （V37-4：取色来路 = 官方段位图标本体色相；V39-H：改成随主题浅/深两档、按正文级 4.5 重定档），
// 以及「两档 × 各自真实容器底」的对比度门槛。
//
// 🔴 WCAG 计算**复用 V37-B 提供的 ContrastUtils**（theme 包内，Color.kt），不在测试里另写一份会漂移的实现，
// 也不往 main 的 theme 包里加同名 `wcagContrast`（并行棒会在同包再声明一个 ⇒ 重名编译错误）。
// V37-B 若把 ContrastUtils 改名，这里跟着改（已写进探针 residual 提醒合并方）。

package com.gigi.tcg.ui.theme

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TierColorsTest {

    /** main 源码（剥 `//` 单行注释）；闸门注释里会引用旧写法，不剥会误报 */
    private fun readSource(vararg parts: String): String {
        val file = File(arrayOf("src", "main", "java", "com", "gigi", "tcg", *parts).joinToString("/"))
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        return file.readText().lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }

    /** 段位名 → 0xAARRGGBB（ContrastUtils 口径）；未命中即失败，不静默按 0 计算 */
    private fun argbOf(tier: String, darkTheme: Boolean = true): Int {
        val argb = tierColorArgb(tier, darkTheme)
        assertNotNull("$tier 应有段位色", argb)
        return argb!!
    }

    /** HSL 色相（度）/饱和度（%），只用于「两档之间只许动亮度」这条锁，不参与对比度计算 */
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
        val saturation = delta / (1.0 - kotlin.math.abs(2 * lightness - 1))
        return ((hue + 360.0) % 360.0) to saturation * 100.0
    }

    private companion object {
        const val BODY_LEVEL = 4.5

        // 🔴 V39-H：段位是 `titleMedium`(16sp) Bold 上屏（V41 起玩家详情弹窗里为 `titleSmall` 14sp Medium），
        // 按 WCAG 属**正文**（large text 要 ≥18.66sp 粗体），门槛 4.5 而不是 V37-4 的 3.0；
        // 而浅底要求前景暗、深底要求前景亮，单套色物理上做不到 ⇒ 拆两档。
        //
        // 两档各自要覆盖的容器，是 M3 固定色板里**该主题真会落到段位文本上**的那几张底。
        // 段位文案的两个上屏点（CardStatsRoute 的昵称行、PlayerDetailDialog 的昵称行）
        // 分别落在 Card 与弹窗里：Card 容器 material3 1.3.2 = `surfaceContainerHighest`
        // （夜 #36343B / 白 #E6E0E9，`FilledCardTokens.ContainerColor` 实测；旧注释误称 #F7F2FA
        // =`surfaceContainerLow`，那是 ElevatedCard/Sheet 档）。白侧另收纯白与 surfaceContainerLow，
        // 因为 ElevatedCard / 底部弹 Sheet / 导出预览也吃这两档。
        val DarkBackgrounds = listOf(
            "darkCard" to 0xFF36343B.toInt(),
            "darkSurface" to 0xFF141218.toInt(),
            "darkDialog" to 0xFF2B2930.toInt(),
            "darkSheet" to 0xFF1D1B20.toInt(),
        )
        val LightBackgrounds = listOf(
            "lightCard" to 0xFFE6E0E9.toInt(),
            "lightSurface" to 0xFFFEF7FF.toInt(),
            "lightDialog" to 0xFFECE6F0.toInt(),
            "lightSheet" to 0xFFF7F2FA.toInt(),
            "white" to 0xFFFFFFFF.toInt(),
        )

        val Tiers = listOf("黄铜", "星银", "赤金", "影幻")

        // 官方徽章本体 HSL 色相（V37-4 像素众数实测）：色相/饱和度是「段位认色」的信息本体，不许漂
        val OfficialBadgeHue = mapOf(
            "黄铜" to 19.4, "星银" to 212.6, "赤金" to 44.2, "影幻" to 316.0,
        )
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

    /**
     * V37-4：四档值**逐位钉死**。上面几条只比常量自身（改常量它们会一起跟着过 ⇒ 防不了漂移），
     * 这里写字面量：官方徽章实测色相 19.4° / 212.6° / 44.2° / 316.0°。
     * V39-H：只把亮度按两档重定档 ⇒ 这里的深档值相对 V37-4 整体上移、新增浅档整体下压。
     */
    @Test
    fun tierArgbConstants_areLockedToOfficialBadgeValues() {
        assertEquals(0xFFBF9784.toInt(), TIER_ARGB_BRASS)
        assertEquals(0xFF88A2BF.toInt(), TIER_ARGB_SILVER)
        assertEquals(0xFFC89814.toInt(), TIER_ARGB_GOLD)
        assertEquals(0xFFDC81C4.toInt(), TIER_ARGB_PHANTOM)
        assertEquals(0xFF835944.toInt(), TIER_ARGB_BRASS_LIGHT)
        assertEquals(0xFF486586.toInt(), TIER_ARGB_SILVER_LIGHT)
        assertEquals(0xFF7C5E0D.toInt(), TIER_ARGB_GOLD_LIGHT)
        assertEquals(0xFFAA2F89.toInt(), TIER_ARGB_PHANTOM_LIGHT)
    }

    /** 影幻旧值 #8A6AE0 是蓝紫、与官方图标（316.0° 紫粉）不符；钉一条反向断言，防止被"顺手改回去" */
    @Test
    fun phantom_noLongerCarriesLegacyBlueViolet() {
        assertNotEquals(0xFF8A6AE0.toInt(), TIER_ARGB_PHANTOM)
        assertNotEquals(0xFF8A6AE0.toInt(), TIER_ARGB_PHANTOM_LIGHT)
        assertEquals(TIER_ARGB_PHANTOM, tierColorArgb("影幻"))
    }

    /**
     * V39-H：亮色档必须经 `tierColorArgb(tier, darkTheme=false)` 才取得到，
     * 且**单参旧访问方式仍返回深档**（不许悄悄换套，签名与语义都保持原样）。
     */
    @Test
    fun tierColorArgb_darkThemeFlag_selectsTheMatchingTier() {
        Tiers.forEach { tier ->
            val dark = tierColorArgb(tier, darkTheme = true)
            val light = tierColorArgb(tier, darkTheme = false)
            assertEquals("$tier 单参访问必须等于深档", tierColorArgb(tier), dark)
            assertNotEquals("$tier 两档必须不同值（单套做不到浅底暗、深底亮）", dark, light)
            assertNotNull("$tier 亮档也必须命中", light)
        }
        assertNull("未命中段位两档都返回 null", tierColorArgb("青铜", false))
    }

    /**
     * V39-H：两档之间**只许动亮度**——色相/饱和度必须仍是官方徽章本体那组值，
     * 否则「段位=颜色」这条信息就丢了（V37-4 的旧值就是靠压亮度换来的）。
     */
    @Test
    fun tierLightVariant_keepsOfficialBadgeHueAndSaturation() {
        OfficialBadgeHue.forEach { (tier, officialHue) ->
            listOf(true to "深档", false to "浅档").forEach { (dark, label) ->
                val (hue, sat) = hueSat(argbOf(tier, dark))
                val distance = minOf(kotlin.math.abs(hue - officialHue), 360.0 - kotlin.math.abs(hue - officialHue))
                assertTrue("$tier $label 色相 $hue° 偏离官方 $officialHue° 超过 2°", distance <= 2.0)
                assertTrue("$tier $label 饱和度 $sat% 越界（官方值是 30~82%）", sat in 25.0..85.0)
            }
        }
        // 同档内亮度（浅档 < 深档）与饱和度两档一致
        Tiers.forEach { tier ->
            val (_, darkSat) = hueSat(argbOf(tier, true))
            val (_, lightSat) = hueSat(argbOf(tier, false))
            assertTrue("$tier 两档饱和度差 ${kotlin.math.abs(darkSat - lightSat)}%，应 <5%",
                kotlin.math.abs(darkSat - lightSat) < 5.0)
            assertTrue(
                "$tier 浅档必须比深档暗（ContrastUtils 亮度口径）",
                ContrastUtils.relativeLuminance(argbOf(tier, false)) <
                    ContrastUtils.relativeLuminance(argbOf(tier, true)),
            )
        }
    }

    /**
     * 键表必须收齐**三语资源实际值 + 接口英文值 + 幻影别名**（V37-4），两档同一套键：
     * 上屏文案由 tierLabel() 按当前语言给出，繁中「黃銅/星銀」与简化字不同码位，漏键=不着色。
     * 繁中 tier_gold=赤金、tier_phantom=影幻 与简化同形，同形≠不用测，这里一并显式命中。
     */
    @Test
    fun tierColorArgb_hitsEveryLocalizedSpelling() {
        listOf("黄铜", "黃銅", "Brass").forEach {
            assertEquals("$it 应命中黄铜", TIER_ARGB_BRASS, tierColorArgb(it))
            assertEquals("$it 应命中黄铜浅档", TIER_ARGB_BRASS_LIGHT, tierColorArgb(it, false))
        }
        listOf("星银", "星銀", "Silver").forEach {
            assertEquals("$it 应命中星银", TIER_ARGB_SILVER, tierColorArgb(it))
            assertEquals("$it 应命中星银浅档", TIER_ARGB_SILVER_LIGHT, tierColorArgb(it, false))
        }
        listOf("赤金", "Gold").forEach {
            assertEquals("$it 应命中赤金", TIER_ARGB_GOLD, tierColorArgb(it))
            assertEquals("$it 应命中赤金浅档", TIER_ARGB_GOLD_LIGHT, tierColorArgb(it, false))
        }
        listOf("影幻", "幻影", "Phantom").forEach {
            assertEquals("$it 应命中影幻", TIER_ARGB_PHANTOM, tierColorArgb(it))
            assertEquals("$it 应命中影幻浅档", TIER_ARGB_PHANTOM_LIGHT, tierColorArgb(it, false))
        }
    }

    /** 上屏形态 = 段位名 + ★ 星缀，可能再被包一层前后空白（列表复用/拼接残留），仍须命中 */
    @Test
    fun tierColorArgb_toleratesStarsAndSurroundingBlank() {
        assertEquals(TIER_ARGB_GOLD, tierColorArgb(" 赤金★★★ "))
        assertEquals(TIER_ARGB_PHANTOM, tierColorArgb("影幻★★ "))
        assertEquals(TIER_ARGB_BRASS, tierColorArgb("\t黃銅★★★★★\n"))
        assertEquals(TIER_ARGB_SILVER, tierColorArgb(" Silver"))
        assertEquals(TIER_ARGB_GOLD_LIGHT, tierColorArgb(" 赤金★★★ ", false))
    }

    @Test
    fun tierColorArgb_tiersAreDistinct() {
        val colors = Tiers.mapNotNull(::tierColorArgb).toSet()
        assertEquals("四档段位色（深档）必须两两可辨，实际=$colors", Tiers.size, colors.size)
        val lightColors = Tiers.map { tierColorArgb(it, false) }.toSet()
        assertEquals("四档段位色（浅档）必须两两可辨，实际=$lightColors", Tiers.size, lightColors.size)
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
     * V39-H 验收口径：深档 × 夜间四种真实容器底、浅档 × 白天五种真实容器底，**全部 ≥ 正文级 4.5**。
     * （V37-4 那条「五底 ≥3.0」已作废：段位是 16sp Bold＝正文档，门槛本就不是 3.0。）
     * 跨档组合不锁——深档只在夜间上屏、浅档只在白天上屏，混用属调用点取错主题档。
     */
    @Test
    fun tierColors_meetWcagAA_onTheirOwnThemeContainers() {
        listOf(true to DarkBackgrounds, false to LightBackgrounds).forEach { (dark, backgrounds) ->
            val label = if (dark) "深档" else "浅档"
            Tiers.forEach { tier ->
                val argb = argbOf(tier, dark)
                backgrounds.forEach { (name, bg) ->
                    val ratio = ContrastUtils.wcagContrast(argb, bg)
                    assertTrue("$tier $label vs $name = ${"%.2f".format(ratio)}:1, need >= $BODY_LEVEL",
                        ratio >= BODY_LEVEL)
                }
            }
        }
    }

    /** 两档最紧的一档都必须是 Card（surfaceContainerHighest）；这条同时是「Card 底 = #36343B/#E6E0E9」的档口锁 */
    @Test
    fun tierColors_worstContainerIsCard_onBothThemes() {
        listOf(true to DarkBackgrounds, false to LightBackgrounds).forEach { (dark, backgrounds) ->
            val worst = Tiers.flatMap { tier ->
                backgrounds.map { (name, bg) ->
                    name to ContrastUtils.wcagContrast(argbOf(tier, dark), bg)
                }
            }.minByOrNull { it.second }!!
            val expected = if (dark) "darkCard" else "lightCard"
            assertEquals("两档最紧的容器底都应是 Card（$expected），实际=${worst.first}", expected, worst.first)
            assertTrue("最紧一档也必须 ≥$BODY_LEVEL，实际 ${"%.2f".format(worst.second)}", worst.second >= BODY_LEVEL)
        }
    }

    /**
     * V39-H 源码闸门：上屏入口 `tierColor()` 必须走**随主题**的那条路（带 darkTheme 的重载）。
     * 单参重载返回的是深档，白天浅底上会掉回 2.8~2.9 —— 那正是本轮要了结的缺口，
     * 所以「谁在 Composable 里拿段位色」这件事钉在源码里，而不是只钉在数值上。
     */
    @Test
    fun sourceGate_tierColorRoutesThroughTheThemeAwareOverload() {
        val tierSrc = readSource("ui", "theme", "TierColors.kt")
        assertTrue(
            "tierColor 必须按主题取档（tierColorArgb(tier, <darkTheme>)）",
            Regex("""tierColorArgb\([^)]+,\s*\w+""").containsMatchIn(
                tierSrc.substringAfter("fun tierColor(tier: String)"),
            ),
        )
        // tierColor 之后不得再出现单参 tierColorArgb( 调用（那里拿到的会是深档）
        val afterEntry = tierSrc.substringAfter("fun tierColor(tier: String)")
        assertTrue(
            "tierColor 体内不得用单参 tierColorArgb(tier)（只返回深档）",
            !Regex("""tierColorArgb\([^,()]*\)""").containsMatchIn(afterEntry),
        )
        // 浅档四枚必须真的进键表，否则「新增档位」只是死常量
        listOf("BRASS", "SILVER", "GOLD", "PHANTOM").forEach { tier ->
            assertTrue("浅档 TIER_ARGB_${tier}_LIGHT 未接入键表", tierSrc.contains("TIER_ARGB_${tier}_LIGHT,"))
        }
    }

    @Test
    fun tierColorArgb_outputIsPlainSrgbColor() {
        // 纯函数值与 Compose Color 口径一致：Color(Int) 解出的分量必须原样回读（复用 V37-B 的 toArgb）
        assertEquals(0xFFDC81C4.toInt(), ContrastUtils.toArgb(Color(TIER_ARGB_PHANTOM)))
        assertEquals(TIER_ARGB_PHANTOM, argbOf("影幻"))
    }
}
