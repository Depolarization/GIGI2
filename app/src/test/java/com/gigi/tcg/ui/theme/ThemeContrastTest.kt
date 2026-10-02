package com.gigi.tcg.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V37-B：固定色板（[LightColors] / [DarkColors]）的 WCAG 对比度锁。
 *
 * 立规缘由（真机取证，不是主观描述）：夜间模式 Snackbar 背景实测主色 `(230,224,233)=#E6E0E9`
 * ——那是 M3 1.3.2 **深色基线的 `inverseSurface`**（浅色），而正文吃 `inverseOnSurface`；
 * MIUI 壁纸动态取色（原 `dynamicColor` 默认 true，接管整个 ColorScheme）把这一对里的正文侧
 * 也解析成了浅色 ⇒ 浅底 + 浅字 ≈1.0:1，肉眼全白，「导出数据图表」Button 同病。
 *
 * 口径：正文级 ≥4.5:1，大字/图标级 ≥3.0:1。纯 JVM 单测——只把 [Color] 拆成 sRGB 分量算数，
 * 不起 Activity、不碰 Compose runtime、不依赖 android.graphics。
 */
class ThemeContrastTest {

    private companion object {
        const val BODY_LEVEL = 4.5
        const val LARGE_LEVEL = 3.0

        // 深色主题 snackbar 容器的「必须是深色」硬阈值：#2B2930 亮度 0.0232、浅色主题
        // inverseSurface #322F35 亮度 0.0297，都在线下；而 M3 基线那个浅底 #E6E0E9 亮度 0.760，
        // 超线 5 倍 ⇒ 阈值既不误伤也抓得住回归。
        const val DARK_CONTAINER_MAX_LUMINANCE = 0.15

        /** 正文级配对清单：(名称, 前景槽, 背景槽)，两套主题共用同一张表 */
        val BODY_PAIRS = listOf(
            Triple("onSurface/surface", ColorScheme::onSurface, ColorScheme::surface),
            Triple(
                "onSurface/surfaceContainerHighest",
                ColorScheme::onSurface,
                ColorScheme::surfaceContainerHighest,
            ),
            Triple("onSurfaceVariant/surface", ColorScheme::onSurfaceVariant, ColorScheme::surface),
            Triple(
                "onSurfaceVariant/surfaceVariant",
                ColorScheme::onSurfaceVariant,
                ColorScheme::surfaceVariant,
            ),
            Triple("onPrimary/primary", ColorScheme::onPrimary, ColorScheme::primary),
            Triple("onSecondary/secondary", ColorScheme::onSecondary, ColorScheme::secondary),
            Triple("onTertiary/tertiary", ColorScheme::onTertiary, ColorScheme::tertiary),
            Triple("onError/error", ColorScheme::onError, ColorScheme::error),
            Triple("onBackground/background", ColorScheme::onBackground, ColorScheme::background),
            Triple(
                "onPrimaryContainer/primaryContainer",
                ColorScheme::onPrimaryContainer,
                ColorScheme::primaryContainer,
            ),
            Triple(
                "onSecondaryContainer/secondaryContainer",
                ColorScheme::onSecondaryContainer,
                ColorScheme::secondaryContainer,
            ),
            Triple(
                "onTertiaryContainer/tertiaryContainer",
                ColorScheme::onTertiaryContainer,
                ColorScheme::tertiaryContainer,
            ),
            Triple(
                "onErrorContainer/errorContainer",
                ColorScheme::onErrorContainer,
                ColorScheme::errorContainer,
            ),
            // Snackbar 正文与「查看」按钮：ToastHost 显式传的就是这两对槽（见 GigiToast.kt）
            Triple(
                "inverseOnSurface/inverseSurface",
                ColorScheme::inverseOnSurface,
                ColorScheme::inverseSurface,
            ),
            Triple(
                "inversePrimary/inverseSurface",
                ColorScheme::inversePrimary,
                ColorScheme::inverseSurface,
            ),
        )
    }

    private fun assertBodyPairs(scheme: ColorScheme, themeName: String) {
        BODY_PAIRS.forEach { (name, fgOf, bgOf) ->
            val value = ContrastUtils.wcagContrast(fgOf(scheme), bgOf(scheme))
            assertTrue(
                "$themeName $name = ${"%.2f".format(value)}:1，需 >= $BODY_LEVEL",
                value >= BODY_LEVEL,
            )
        }
    }

    @Test
    fun formula_selfCheck_blackOnWhiteIs21() {
        // Int 口径（ContrastUtils 的规范入参是 0xAARRGGBB；alpha 不参与计算，这里用不带高位底的
        // 字面量避开 Kotlin 整型字面量溢出，Color 口径的用例见下面的值锁）
        assertEquals(21.0, ContrastUtils.wcagContrast(0x000000, 0xFFFFFF), 0.1)
    }

    @Test
    fun formula_selfCheck_sameColorIs1() {
        assertEquals(1.0, ContrastUtils.wcagContrast(0x2B2930, 0x2B2930), 0.001)
    }

    @Test
    fun lightColors_allBodyPairs_meetWcagAA() = assertBodyPairs(LightColors, "浅色")

    @Test
    fun darkColors_allBodyPairs_meetWcagAA() = assertBodyPairs(DarkColors, "深色")

    @Test
    fun bothThemes_largeAndIconLevel_meetWcag3() {
        listOf("浅色" to LightColors, "深色" to DarkColors).forEach { (name, scheme) ->
            // 图标/描边/大字：主色作前景（选中态图标等）、outline 作分隔线
            val iconPairs = listOf(
                "primary/surface" to listOf(ColorScheme::primary, ColorScheme::surface),
                "error/surface" to listOf(ColorScheme::error, ColorScheme::surface),
                "outline/surface" to listOf(ColorScheme::outline, ColorScheme::surface),
                "onSurfaceVariant/surfaceContainerHighest" to
                    listOf(ColorScheme::onSurfaceVariant, ColorScheme::surfaceContainerHighest),
            )
            iconPairs.forEach { (pair, slots) ->
                val value = ContrastUtils.wcagContrast(slots[0](scheme), slots[1](scheme))
                assertTrue("$name $pair = ${"%.2f".format(value)}:1，需 >= $LARGE_LEVEL", value >= LARGE_LEVEL)
            }
        }
    }

    /**
     * V42：记录卡「积分变化」在 **0（持平）** 时用 `onSurfaceVariant`（不占用红绿，见
     * [scoreDeltaColor]）。这个组合此前只挂在上面那条 3.0 大字/图标档里，从没锁过**正文级**，
     * 而变化值是 `bodySmall`(12sp) ⇒ 属正文，必须按 4.5 验。
     * 实测 7.20:1（暗 × #36343B）/ 7.21:1（亮 × #E6E0E9），两档都远超门槛。
     */
    @Test
    fun onSurfaceVariant_meetsWcagAA_bodyLevel_onCardContainer() {
        listOf("浅色" to LightColors, "深色" to DarkColors).forEach { (name, scheme) ->
            val value = ContrastUtils.wcagContrast(scheme.onSurfaceVariant, scheme.surfaceContainerHighest)
            assertTrue(
                "$name onSurfaceVariant vs Card = ${"%.2f".format(value)}:1，需 >= $BODY_LEVEL",
                value >= BODY_LEVEL,
            )
        }
    }

    /**
     * 🔴 本棒的回归锁（V37-8 真机 bug 的来龙去脉）：
     * M3 1.3.2 深色基线把「inverse」定义成**整对反相**——`inverseSurface = #E6E0E9`（浅底）配
     * `inverseOnSurface = #322F35`（深字）。这对本身合法，但 M3 Snackbar 默认就吃它，
     * 于是夜间只要任何一侧被动态取色改写（真机 MIUI 把 `inverseOnSurface` 也解析成浅色），
     * 立刻退化成浅底浅字 ≈1.0:1 —— 用户报的「snackbar 内容完全看不见」就是这么来的。
     * V37-B 的修法是把深色主题的 inverse 三槽改成与页面同相的**深底浅字**（#2B2930 + #E6E0E9），
     * 并把 `inversePrimary` 从基线深紫 #6750A4 提到浅紫 #D0BCFF —— 容器变深后基线那个深紫落在
     * 上面只有 2.28:1，「查看」按钮会二次看不清。
     *
     * 这条锁死三件事：**深色主题的 snackbar 底绝不能是浅色**、底必须比字暗、三槽具体值不许漂移。
     *
     * ⚠️ 关于派单原文「inverseSurface 亮度低于 surface」：本主题 dark `surface = #141218` 是**最暗档**
     * （与 background / surfaceDim 同值），而派单同时指定 snackbar 底取 `#2B2930 一档`
     * （= surfaceContainerHigh），两者字面上互斥（#2B2930 比 #141218 略亮）。故按派单本意
     * 「绝不能是浅色」落地为三条同时成立：绝对暗度阈值、不比页面最高容器档更亮、底比字暗；
     * 另加一条「必须比页面底更亮」保住 snackbar 与页面的区分度。
     */
    @Test
    fun darkColors_inverseSurface_isDarkContainer_regressionLock() {
        val containerL = ContrastUtils.relativeLuminance(ContrastUtils.toArgb(DarkColors.inverseSurface))
        val textL = ContrastUtils.relativeLuminance(ContrastUtils.toArgb(DarkColors.inverseOnSurface))
        val pageL = ContrastUtils.relativeLuminance(ContrastUtils.toArgb(DarkColors.surface))
        val highestL = ContrastUtils.relativeLuminance(ContrastUtils.toArgb(DarkColors.surfaceContainerHighest))

        assertTrue(
            "深色 inverseSurface 亮度=$containerL，必须 <$DARK_CONTAINER_MAX_LUMINANCE —— " +
                "snackbar 底绝不能是浅色（V37 真机就是这么瞎的）",
            containerL < DARK_CONTAINER_MAX_LUMINANCE,
        )
        assertTrue(
            "深色 inverseSurface($containerL) 必须比 inverseOnSurface($textL) 更暗：深底浅字，不许反过来",
            containerL < textL,
        )
        assertTrue(
            "深色 inverseSurface($containerL) 不得比页面最高容器档 surfaceContainerHighest($highestL) 更亮",
            containerL <= highestL,
        )
        assertTrue(
            "深色 inverseSurface($containerL) 必须比页面底 surface($pageL) 更亮，否则 snackbar 与页面糊成一块",
            containerL > pageL,
        )
        // 具体值也钉死：改任何一个都得先来改这条并说明理由
        assertEquals(Color(0xFF2B2930), DarkColors.inverseSurface)
        assertEquals(Color(0xFFE6E0E9), DarkColors.inverseOnSurface)
        assertEquals(Color(0xFFD0BCFF), DarkColors.inversePrimary)
    }

    /** 浅色主题的 snackbar 底同样是深色（M3 基线本来如此），一并锁住，防止有人"顺手"提亮 */
    @Test
    fun lightColors_inverseSurface_isDarkContainer() {
        val containerL = ContrastUtils.relativeLuminance(ContrastUtils.toArgb(LightColors.inverseSurface))
        val textL = ContrastUtils.relativeLuminance(ContrastUtils.toArgb(LightColors.inverseOnSurface))
        assertTrue(
            "浅色 inverseSurface 亮度=$containerL，必须 <$DARK_CONTAINER_MAX_LUMINANCE（浅色主题 snackbar 也是深底）",
            containerL < DARK_CONTAINER_MAX_LUMINANCE,
        )
        assertTrue(
            "浅色 inverseSurface($containerL) 必须比 inverseOnSurface($textL) 更暗",
            containerL < textL,
        )
    }

    /**
     * `inversePrimary` 作背景块时的前景配对。
     *
     * ⚠️ 派单原文要点了 `inverseOnPrimary` / `inverseError` / `onInverseError` 三个槽，但本工程
     * 实际解析到的 material3 是 **1.3.2**（compose-bom 2025.09.00 锁到 1.3.2），其 ColorScheme
     * **没有这三个槽**（只有 primary/onPrimary/inversePrimary/inverseOnSurface/inverseSurface 等，
     * 已用 javap 核过 getter 表）。所以这里按 1.3.2 的真实槽位落地：
     * 深色主题 `inversePrimary == primary == #D0BCFF`（见上面回归锁的理由），
     * 故 `onPrimary`（深紫 #381E72）压它是 7.71:1 ✓；
     * 浅色主题 `inversePrimary = #D0BCFF` 是浅紫底、`onPrimary = #FFFFFF` 属于 primary 那一套
     * （白压浅紫 1.58:1）——两套槽不跨套硬凑，浅色的 inversePrimary 该配深紫，
     * 其作为「深底上的 action」已由 inversePrimary/inverseSurface = 7.73:1 锁在 BODY_PAIRS 里。
     */
    @Test
    fun inversePrimary_asBackground_meetsWcagAA_inDarkTheme() {
        val value = ContrastUtils.wcagContrast(DarkColors.onPrimary, DarkColors.inversePrimary)
        assertTrue(
            "深色 onPrimary/inversePrimary = ${"%.2f".format(value)}:1，需 >= $BODY_LEVEL",
            value >= BODY_LEVEL,
        )
    }

    /** Snackbar「查看」按钮（action）单独一档：容器换深后仍须 ≥4.5，这正是用户报的第二处看不清 */
    @Test
    fun snackbarAction_meetsWcagAA_onBothContainers() {
        listOf("浅色" to LightColors, "深色" to DarkColors).forEach { (name, scheme) ->
            val value = ContrastUtils.wcagContrast(scheme.inversePrimary, scheme.inverseSurface)
            assertTrue(
                "$name snackbar action inversePrimary/inverseSurface = ${"%.2f".format(value)}:1，需 >= $BODY_LEVEL",
                value >= BODY_LEVEL,
            )
        }
    }

    /** Button 主配对：用户报的「导出数据图表」看不清，落点是 onPrimary/primary，两套主题都要 ≥4.5 */
    @Test
    fun buttonPrimaryPair_meetsWcagAA_bothThemes() {
        listOf("浅色" to LightColors, "深色" to DarkColors).forEach { (name, scheme) ->
            val value = ContrastUtils.wcagContrast(scheme.onPrimary, scheme.primary)
            assertTrue("$name Button onPrimary/primary = ${"%.2f".format(value)}:1，需 >= $BODY_LEVEL", value >= BODY_LEVEL)
        }
    }

    /**
     * 源码闸门：Material You 动态取色必须已从主题里删干净（不留死代码、不留恒 false 的开关），
     * ToastHost 也不许把 Snackbar 配色交回 M3 默认回落。
     */
    @Test
    fun sourceGate_noDynamicColorSchemeAndSnackbarColorsExplicit() {
        fun read(vararg parts: String): String {
            val file = File(arrayOf("src", "main", "java", "com", "gigi", "tcg", *parts).joinToString("/"))
            assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
            return file.readText()
        }
        val themeSrc = read("ui", "theme", "Theme.kt")
        val colorSrc = read("ui", "theme", "Color.kt")
        val toastSrc = read("ui", "components", "GigiToast.kt")

        listOf(themeSrc to "Theme.kt", colorSrc to "Color.kt").forEach { (src, name) ->
            assertTrue("$name 不得 import 动态取色 API", !src.contains("androidx.compose.material3.dynamic"))
            assertTrue("$name 不得调用 dynamicDarkColorScheme(", !src.contains("dynamicDarkColorScheme("))
            assertTrue("$name 不得调用 dynamicLightColorScheme(", !src.contains("dynamicLightColorScheme("))
        }
        // 参数表是逐行写的，用行首 ")" 收尾；不能用到第一个 ")" 为止——默认值
        // `isSystemInDarkTheme()` 自带一对括号，会把参数表截断成漏掉后面的开关。
        val signature = themeSrc.substringAfter("fun GigiTheme").substringBefore("\n)")
        assertTrue("GigiTheme 参数表应已无 dynamicColor 开关，实际=$signature", !signature.contains("dynamicColor"))
        assertTrue("ToastHost 必须显式传 containerColor", toastSrc.contains("containerColor ="))
        assertTrue("ToastHost 必须显式传 contentColor", toastSrc.contains("contentColor ="))
        assertTrue("ToastHost 必须显式传 actionContentColor（action 单独一档）", toastSrc.contains("actionContentColor ="))
        assertTrue("ToastHost 必须显式传 dismissActionContentColor", toastSrc.contains("dismissActionContentColor ="))
        // 深色 snackbar 底的字面量也钉在源码里，防止只改测试不改板
        assertTrue("Color.kt 深色 inverseSurface 必须是 0xFF2B2930", colorSrc.contains("inverseSurface = Color(0xFF2B2930)"))
    }

    /**
     * V37-H 回归锁：LoginScreen 的 SnackbarHost 必须与 GigiToast 同构，显式传四色。
     *
     * 根因（V37-H 派单）：material3 1.3.2 深色基线 inverseSurface = #E6E0E9（**浅色**），
     * 而 M3 Snackbar 默认就用该槽作容器——夜间浅底配浅字≈1.0:1 全看不见。
     * B 棒在 Color.kt 已把深色 inverseSurface 改为 #2B2930（深），
     * 但 LoginScreen.kt:127 走裸 SnackbarHost(snackbarHostState)，
     * 若不显式传槽就依赖 M3 默认——一旦 M3 改了默认槽位或将来有主题改动就会悄悄退回不可读。
     */
    @Test
    fun darkColors_inverseOnSurface_meetsWcagAA_explicit() {
        val value = ContrastUtils.wcagContrast(DarkColors.inverseOnSurface, DarkColors.inverseSurface)
        assertTrue(
            "深色 inverseOnSurface/inverseSurface = ${"%.2f".format(value)}:1，需 >= $BODY_LEVEL " +
                "（LoginScreen/GigiToast snackbar 正文色对容器的对比度锁）",
            value >= BODY_LEVEL,
        )
    }

    /**
     * V37-H 源码闸门：LoginScreen.kt 剥注释后不得含有裸 SnackbarHost(snackbarHostState)，
     * 且必须显式传四色（与 GigiToast 同构）。
     */
    @Test
    fun sourceGate_loginScreen_snackbarHostUsesExplicitColors() {
        val loginFile = File("src/main/java/com/gigi/tcg/ui/login/LoginScreen.kt")
        assertTrue(
            "LoginScreen.kt 不存在: ${loginFile.absolutePath}（工程路径变化时更新此闸门）",
            loginFile.exists(),
        )
        val rawSrc = loginFile.readText()
        // 剥 // 单行注释（包含注释行）：防止注释里提到旧形态触发误报
        val stripped = rawSrc.lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        assertTrue(
            "LoginScreen.kt 剥注释后不得有裸 SnackbarHost(snackbarHostState)——" +
                "那是 M3 默认槽位路径，夜间浅底浅字≈1.0:1（V37 真机实测）",
            !stripped.contains("SnackbarHost(snackbarHostState)"),
        )
        // 四个色槽一个都不能少，缺一个就有漏网回归可能
        assertTrue(
            "LoginScreen.kt 必须显式传 containerColor = colorScheme.inverseSurface",
            rawSrc.contains("containerColor = colorScheme.inverseSurface"),
        )
        assertTrue(
            "LoginScreen.kt 必须显式传 contentColor = colorScheme.inverseOnSurface",
            rawSrc.contains("contentColor = colorScheme.inverseOnSurface"),
        )
        assertTrue(
            "LoginScreen.kt 必须显式传 actionContentColor = colorScheme.inversePrimary",
            rawSrc.contains("actionContentColor = colorScheme.inversePrimary"),
        )
        assertTrue(
            "LoginScreen.kt 必须显式传 dismissActionContentColor = colorScheme.inverseOnSurface",
            rawSrc.contains("dismissActionContentColor = colorScheme.inverseOnSurface"),
        )
    }

    /**
     * V37-H 全仓 SnackbarHost 收口锁：main 源码树内所有含 SnackbarHost( 的 Kotlin 文件
     * 必须同时含 containerColor =（否则该处未传色、夜间有回归风险）。
     * 若本用例失败，说明有新的 SnackbarHost 裸调用——参照 GigiToast/LoginScreen 补上四色。
     */
    @Test
    fun sourceGate_allSnackbarHostCallSitesPassContainerColor() {
        val srcRoot = File("src/main/java")
        assertTrue("src/main/java 不存在（工作目录异常？）", srcRoot.exists())
        val violations = srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val content = file.readText()
                // 剥注释后检查
                val stripped = content.lineSequence()
                    .map { it.substringBefore("//") }
                    .joinToString("\n")
                stripped.contains("SnackbarHost(") && !stripped.contains("containerColor =")
            }
            .map { it.relativeTo(srcRoot).path }
            .toList()
        assertTrue(
            "以下文件含 SnackbarHost 调用但未传 containerColor（全仓收口锁）: $violations",
            violations.isEmpty(),
        )
    }

    /**
     * 🔴 V39-H：语义色与段位色**吃的那张 Card 底**必须钉在色板槽位上，不能只钉一个抄来的字面量。
     * material3 1.3.2 无变体 `Card()` 走 `FilledCardTokens.ContainerColor`
     * = `surfaceContainerHighest`（夜 #36343B / 白 #E6E0E9）。V39-G 之前仓内三处注释
     * （`Theme.kt` / `Color.kt` / `TierColors.kt`）都把 `surfaceContainerLow #F7F2FA` 当「Card 容器」，
     * 于是亮色档语义色按错的底调、白天 Card 上只有 3.89~3.94。这条锁就是防它再漂回另一档。
     */
    @Test
    fun cardContainerSlot_isSurfaceContainerHighest_onBothThemes() {
        assertEquals("浅色 Card 底", Color(0xFFE6E0E9), LightColors.surfaceContainerHighest)
        assertEquals("深色 Card 底", Color(0xFF36343B), DarkColors.surfaceContainerHighest)
        // surfaceContainerLow 是 ElevatedCard / 底部弹 Sheet 档，**不是** Card：两值必须仍可区分，
        // 否则「Card 底 = #F7F2FA」那个误称就会重新说得通。
        assertNotEquals(LightColors.surfaceContainerLow, LightColors.surfaceContainerHighest)
    }

    /**
     * V39-H：两套语义色 × **本主题真容器**（槽位直接取自 colorScheme，不写字面量）全过正文级 AA。
     * 这是把「哪个语义前景允许躺在哪个容器上」这条成对约束第一次钉进单测（V39-G §4/§5a 的缺口）。
     */
    @Test
    fun semanticForegrounds_meetWcagAA_onTheirOwnThemeContainers() {
        listOf(
            Triple("浅色", LightColors, SemanticColors(win = WinColorLight, lose = LoseColorLight, gold = GoldColorLight)),
            Triple("深色", DarkColors, SemanticColors(win = WinColor, lose = LoseColor, gold = GoldColor)),
        ).forEach { (name, scheme, semantic) ->
            val containers = listOf(
                "Card(surfaceContainerHighest)" to scheme.surfaceContainerHighest,
                "surface" to scheme.surface,
                "surfaceContainer" to scheme.surfaceContainer,
                "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                "surfaceContainerLow" to scheme.surfaceContainerLow,
            )
            listOf(
                "win" to semantic.win,
                "lose" to semantic.lose,
                "gold" to semantic.gold,
            ).forEach { (fgName, fg) ->
                containers.forEach { (bgName, bg) ->
                    val value = ContrastUtils.wcagContrast(fg, bg)
                    assertTrue(
                        "$name $fgName/$bgName = ${"%.2f".format(value)}:1，需 >= $BODY_LEVEL",
                        value >= BODY_LEVEL,
                    )
                }
            }
        }
    }

    /** 同上：段位四色两档 × 本主题真容器（段位与语义色同属文字前景，共用一条闸门口径） */
    @Test
    fun tierForegrounds_meetWcagAA_onTheirOwnThemeContainers() {
        listOf(
            true to DarkColors,
            false to LightColors,
        ).forEach { (darkTheme, scheme) ->
            val containers = listOf(
                "Card(surfaceContainerHighest)" to scheme.surfaceContainerHighest,
                "surface" to scheme.surface,
                "surfaceContainerHigh(dialog)" to scheme.surfaceContainerHigh,
                "surfaceContainerLow(sheet)" to scheme.surfaceContainerLow,
            )
            listOf("黄铜", "星银", "赤金", "影幻").forEach { tier ->
                val argb = requireNotNull(tierColorArgb(tier, darkTheme)) { "$tier 应有段位色" }
                containers.forEach { (bgName, bg) ->
                    val value = ContrastUtils.wcagContrast(Color(argb), bg)
                    assertTrue(
                        "${if (darkTheme) "深档" else "浅档"} $tier/$bgName = ${"%.2f".format(value)}:1，" +
                            "需 >= $BODY_LEVEL",
                        value >= BODY_LEVEL,
                    )
                }
            }
        }
    }
}
