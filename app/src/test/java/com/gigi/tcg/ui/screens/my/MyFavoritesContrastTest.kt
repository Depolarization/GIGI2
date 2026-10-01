// V39-H2 验收锁（G2 审计清单① 第 1 条，全表里唯一被标成「夜/白两档都不达标」的一处）：
// 收藏对局列表的 胜/负 28dp 徽标。
//
// 改前：`Modifier.background(semantic.win/lose)` 上叠 `inverseOnSurface` 文字。根因两条：
//   ① 语义色的设计口径是**只作前景**（Color.kt:9），被拿去当背景块；
//   ② `Modifier.background()` 不传播 contentColor，同一块里文字通道与图标通道必然取到不同值。
// 改后：中性容器底（`surfaceContainerHigh`）+ 语义色前景，块内唯一前景通道与文字同源。
//
// ⚠️ 与 G2 报告的一处数字勘误（不改 G2 文件，只在此登记）：G2 §3 记的「夜 2.04 / 白 4.46」里
//   白档是 **V39-H 重定档前**的亮档语义色（#B84A4A 那一套）。按现色板复算，旧写法白天是 5.34~5.37 ✓、
//   夜间仍是 2.04~2.07 ✗ ⇒ 缺陷方向成立（语义色当底块必然在深档掉穿），档位数字以本测试为准。
//
// 门槛：徽标文案 labelLarge=14sp ⇒ 按 WCAG 属**正文**（large text 要 ≥18.66sp 粗体），门槛 4.5。
// 对比度一律复用 com.gigi.tcg.ui.theme.ContrastUtils（与 Color.kt / TierColorsTest 同口径，实现只留一份）。

package com.gigi.tcg.ui.screens.my

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.gigi.tcg.ui.theme.ContrastUtils
import com.gigi.tcg.ui.theme.DarkColors
import com.gigi.tcg.ui.theme.LightColors
import com.gigi.tcg.ui.theme.LoseColor
import com.gigi.tcg.ui.theme.LoseColorLight
import com.gigi.tcg.ui.theme.WinColor
import com.gigi.tcg.ui.theme.WinColorLight
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MyFavoritesContrastTest {

    private data class Theme(
        val label: String,
        val scheme: ColorScheme,
        val win: Color,
        val lose: Color,
    )

    private companion object {
        const val BODY_LEVEL = 4.5

        /** 两套主题各自的 胜/负 前景 = 该主题 LocalSemanticColors 解析出的槽位值（见 Theme.kt:46） */
        val Themes = listOf(
            Theme("夜", DarkColors, WinColor, LoseColor),
            Theme("白", LightColors, WinColorLight, LoseColorLight),
        )
    }

    /** main 源码，剥块注释 + `//` 单行注释（口径注释里要引用被禁的旧写法，不剥会误报） */
    private fun readSource(): String {
        val file = File("src/main/java/com/gigi/tcg/ui/screens/my/MyFavoritesPage.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        return Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
            .replace(file.readText(), "")
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }

    private fun matchRowBody(src: String): String {
        val start = src.indexOf("private fun MatchRow(")
        assertTrue("找不到 MatchRow 函数", start >= 0)
        return src.substring(start, src.indexOf("\n}", start))
    }

    private fun assertPass(theme: Theme, name: String, fg: Color, bg: Color) {
        val ratio = ContrastUtils.wcagContrast(fg, bg)
        assertTrue("$name/${theme.label} vs 徽标底 = ${"%.2f".format(ratio)}:1, need >= $BODY_LEVEL",
            ratio >= BODY_LEVEL)
    }

    /** 验收主口径：胜/负前景 × surfaceContainerHigh（改后实际用的底），两主题 × 胜负四组全 ≥4.5 */
    @Test
    fun badgeForegrounds_meetWcagAA_onSurfaceContainerHigh() {
        Themes.forEach { theme ->
            assertPass(theme, "win", theme.win, theme.scheme.surfaceContainerHigh)
            assertPass(theme, "lose", theme.lose, theme.scheme.surfaceContainerHigh)
        }
    }

    /** 备选中性底（无变体 Card 的真容器 surfaceContainerHighest）同样锁住 ⇒ 换底不许破线 */
    @Test
    fun badgeForegrounds_meetWcagAA_onSurfaceContainerHighest() {
        Themes.forEach { theme ->
            assertPass(theme, "win", theme.win, theme.scheme.surfaceContainerHighest)
            assertPass(theme, "lose", theme.lose, theme.scheme.surfaceContainerHighest)
        }
    }

    /**
     * 选 surfaceContainerHigh 而不是 surfaceContainerHighest 的理由必须可验：
     * High 与 Card 底（Highest）不同亮度 ⇒ 夜更深、白更浅，徽标仍看得出是"一块"；
     * Highest 与父 Card **同色**，那样会画出一块看不见的底（只剩有色文字）。
     */
    @Test
    fun chosenContainerDiffersFromParentCardBase() {
        val darkHigh = ContrastUtils.toArgb(DarkColors.surfaceContainerHigh)
        val darkCard = ContrastUtils.toArgb(DarkColors.surfaceContainerHighest)
        val lightHigh = ContrastUtils.toArgb(LightColors.surfaceContainerHigh)
        val lightCard = ContrastUtils.toArgb(LightColors.surfaceContainerHighest)
        assertTrue("夜 surfaceContainerHigh 必须比 Card 底更暗",
            ContrastUtils.relativeLuminance(darkHigh) < ContrastUtils.relativeLuminance(darkCard))
        assertTrue("白 surfaceContainerHigh 必须比 Card 底更亮",
            ContrastUtils.relativeLuminance(lightHigh) > ContrastUtils.relativeLuminance(lightCard))
    }

    /**
     * 反例锁（根因留档）：旧写法「inverseOnSurface 压在 semantic.win/lose 这块底色上」
     * **夜间两枚都掉穿**（win 2.04 / lose 2.07）；并锁「新口径最差值 > 旧口径最差值」，
     * 改法方向本身不许被改回去。白天两枚（5.34~5.37）在 H 棒重定档后恰好过线，
     * 但那靠的是语义色亮度、不是配对正确 ⇒ 仍按「语义色只作前景」收口。
     */
    @Test
    fun legacySemanticAsBackgroundBlock_stillFailsAtNight() {
        val legacy = listOf(
            "夜 win" to ContrastUtils.wcagContrast(DarkColors.inverseOnSurface, WinColor),
            "夜 lose" to ContrastUtils.wcagContrast(DarkColors.inverseOnSurface, LoseColor),
            "白 win" to ContrastUtils.wcagContrast(LightColors.inverseOnSurface, WinColorLight),
            "白 lose" to ContrastUtils.wcagContrast(LightColors.inverseOnSurface, LoseColorLight),
        )
        legacy.filter { it.first.startsWith("夜") }.forEach { (label, ratio) ->
            assertTrue("旧口径 $label = ${"%.2f".format(ratio)}:1，夜间必须 <4.5（正是本轮要了结的缺口）",
                ratio < BODY_LEVEL)
        }
        val legacyWorst = legacy.minOf { it.second }
        val currentWorst = Themes.minOf { theme ->
            listOf(
                ContrastUtils.wcagContrast(theme.win, theme.scheme.surfaceContainerHigh),
                ContrastUtils.wcagContrast(theme.lose, theme.scheme.surfaceContainerHigh),
            ).min()
        }
        assertTrue("新口径最差值 ${"%.2f".format(currentWorst)} 必须优于旧口径 ${"%.2f".format(legacyWorst)}",
            currentWorst > legacyWorst)
        assertTrue("新口径最差值必须 ≥$BODY_LEVEL，实际 ${"%.2f".format(currentWorst)}",
            currentWorst >= BODY_LEVEL)
    }

    /**
     * 源码闸门：
     *  a) 语义色槽位（semantic.win/lose）在文件里**只出现在前景位**，绝不出现在 `background(...)` 里；
     *  b) 徽标底必须取中性容器槽、文字色必须吃 semantic.win/lose（同源，通道不分叉）；
     *  c) MatchRow 内不再把 `inverseOnSurface` 当万能前景。
     */
    @Test
    fun sourceGate_semanticSlotsStayForegroundOnly() {
        val src = readSource()
        assertTrue(
            "background(...) 里不得出现语义色槽（语义色只作前景，见 Color.kt:9 口径）",
            !Regex("""background\([^)]*semantic\.(win|lose|gold)""").containsMatchIn(src),
        )
        val body = matchRowBody(src)
        // 🔴 V39-G1（照官方布局）：胜负从「左侧 28dp 徽标块」改成**中间列的居中文字**，
        // 底下没有独立色块了 —— 文字直接落在 Card 自身的 surfaceContainerHighest 上。
        // 所以这里锁的底从 surfaceContainerHigh 改成 Card 底；
        // 上面的 badgeForegrounds_*_onSurfaceContainerHighest 用例已按同口径验过 4.5 门槛。
        assertTrue(
            "胜/负文字落在 Card 自身底（surfaceContainerHighest）上，不另设色块",
            Regex("""background\(MaterialTheme\.colorScheme\.surfaceContainerHighest\)""").containsMatchIn(src) ||
                !Regex("""background\(""").containsMatchIn(body),
        )
        assertTrue(
            "徽标文字必须与前景同源：color = if (isWin) semantic.win else semantic.lose",
            Regex("""color\s*=\s*if \(isWin\) semantic\.win else semantic\.lose""").containsMatchIn(body),
        )
        assertTrue("MatchRow 内不得再用 inverseOnSurface", !body.contains("inverseOnSurface"))
    }
}
