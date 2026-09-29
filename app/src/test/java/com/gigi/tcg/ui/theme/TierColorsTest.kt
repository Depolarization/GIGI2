// 段位文本色的纯 JVM 单测：锁「段位名 → 色」的命中规则、四档互不混淆、四档常量的精确 ARGB 值
// （V37-4：取色来路 = 官方段位图标本体色相，改值必须同时改注释与这里的字面量），
// 以及五底对比度门槛。
//
// 🔴 WCAG 计算**复用 V37-B 提供的 ContrastUtils**（theme 包内，Color.kt），不在测试里另写一份会漂移的实现，
// 也不往 main 的 theme 包里加同名 `wcagContrast`（并行棒会在同包再声明一个 ⇒ 重名编译错误）。
// V37-B 若把 ContrastUtils 改名，这里跟着改（已写进探针 residual 提醒合并方）。

package com.gigi.tcg.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TierColorsTest {

    /** 段位名 → 0xAARRGGBB（ContrastUtils 口径）；未命中即失败，不静默按 0 计算 */
    private fun argbOf(tier: String): Int {
        val argb = tierColorArgb(tier)
        assertNotNull("$tier 应有段位色", argb)
        return argb!!
    }

    private companion object {
        // M3 固定色板实际会落到的五种容器底：段位文案上屏的位置是卡片/弹窗里的文本，
        // 浅弹窗底（surfaceContainerHigh ≈0xFFECE6F0）最吃对比度，深色两档反而最宽松。
        val White = 0xFFFFFFFF.toInt()
        val CardSurfaceLight = 0xFFF7F2FA.toInt()
        val DialogSurfaceLight = 0xFFECE6F0.toInt()
        val DarkSurface = 0xFF1C1B1F.toInt()
        val DarkDialogSurface = 0xFF2B2932.toInt()

        val Backgrounds = listOf(
            "white" to White,
            "lightCard" to CardSurfaceLight,
            "lightDialog" to DialogSurfaceLight,
            "darkSurface" to DarkSurface,
            "darkDialog" to DarkDialogSurface,
        )

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

    /**
     * V37-4：四档值**逐位钉死**。上面几条只比常量自身（改常量它们会一起跟着过 ⇒ 防不了漂移），
     * 这里写字面量：官方徽章实测色相 19.4° / 212.6° / 44.2° / 316.0°。
     */
    @Test
    fun tierArgbConstants_areLockedToOfficialBadgeValues() {
        assertEquals(0xFFAB775E.toInt(), TIER_ARGB_BRASS)
        assertEquals(0xFF6687AD.toInt(), TIER_ARGB_SILVER)
        assertEquals(0xFFA77F11.toInt(), TIER_ARGB_GOLD)
        assertEquals(0xFFD157B0.toInt(), TIER_ARGB_PHANTOM)
    }

    /** 影幻旧值 #8A6AE0 是蓝紫、与官方图标（316.0° 紫粉）不符；钉一条反向断言，防止被"顺手改回去" */
    @Test
    fun phantom_noLongerCarriesLegacyBlueViolet() {
        assertNotEquals(0xFF8A6AE0.toInt(), TIER_ARGB_PHANTOM)
        assertEquals(TIER_ARGB_PHANTOM, tierColorArgb("影幻"))
    }

    /**
     * 键表必须收齐**三语资源实际值 + 接口英文值 + 幻影别名**（V37-4）：
     * 上屏文案由 tierLabel() 按当前语言给出，繁中「黃銅/星銀」与简化字不同码位，漏键=不着色。
     * 繁中 tier_gold=赤金、tier_phantom=影幻 与简化同形，同形≠不用测，这里一并显式命中。
     */
    @Test
    fun tierColorArgb_hitsEveryLocalizedSpelling() {
        listOf("黄铜", "黃銅", "Brass").forEach {
            assertEquals("$it 应命中黄铜", TIER_ARGB_BRASS, tierColorArgb(it))
        }
        listOf("星银", "星銀", "Silver").forEach {
            assertEquals("$it 应命中星银", TIER_ARGB_SILVER, tierColorArgb(it))
        }
        listOf("赤金", "Gold").forEach {
            assertEquals("$it 应命中赤金", TIER_ARGB_GOLD, tierColorArgb(it))
        }
        listOf("影幻", "幻影", "Phantom").forEach {
            assertEquals("$it 应命中影幻", TIER_ARGB_PHANTOM, tierColorArgb(it))
        }
    }

    /** 上屏形态 = 段位名 + ★ 星缀，可能再被包一层前后空白（列表复用/拼接残留），仍须命中 */
    @Test
    fun tierColorArgb_toleratesStarsAndSurroundingBlank() {
        assertEquals(TIER_ARGB_GOLD, tierColorArgb(" 赤金★★★ "))
        assertEquals(TIER_ARGB_PHANTOM, tierColorArgb("影幻★★ "))
        assertEquals(TIER_ARGB_BRASS, tierColorArgb("\t黃銅★★★★★\n"))
        assertEquals(TIER_ARGB_SILVER, tierColorArgb(" Silver"))
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
     * 四档段位色在五种实际容器底上都要 ≥ 3.0:1（V37-4 的验收口径）。
     * 门槛取 3.0 而不是 4.5：段位是**大号粗体**文本（titleMedium 16sp Bold，与昵称同一个 Text），
     * WCAG 对 large text 的 AA 线就是 3:1；同时锁住"最差那一档"落在浅弹窗底。
     */
    @Test
    fun tierColors_readableOnAllSurfaces() {
        Tiers.forEach { tier ->
            val argb = argbOf(tier)
            Backgrounds.forEach { (name, bg) ->
                val ratio = ContrastUtils.wcagContrast(argb, bg)
                assertTrue("$tier vs $name = $ratio:1, need >= 3.0", ratio >= 3.0)
            }
        }
        // 五底里最紧的一档必须是浅弹窗底（改色板时如果这档跌破 3.0，上面逐底断言会先响）
        val worst = Tiers.flatMap { tier -> Backgrounds.map { (name, bg) -> name to ContrastUtils.wcagContrast(argbOf(tier), bg) } }
            .minByOrNull { it.second }!!
        assertEquals("最紧的容器底应是浅弹窗底", "lightDialog", worst.first)
        assertTrue("最紧一档也必须 ≥3.0，实际 ${worst.second}", worst.second >= 3.0)
    }

    @Test
    fun tierColorArgb_outputIsPlainSrgbColor() {
        // 纯函数值与 Compose Color 口径一致：Color(Int) 解出的分量必须原样回读（复用 V37-B 的 toArgb）
        assertEquals(0xFFD157B0.toInt(), ContrastUtils.toArgb(Color(TIER_ARGB_PHANTOM)))
        assertEquals(TIER_ARGB_PHANTOM, argbOf("影幻"))
    }
}
