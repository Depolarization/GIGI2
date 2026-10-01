// V39-H2 验收锁（G2 审计清单②「仅夜间不达标」）：登录页二维码**占位框**上的 CircularProgressIndicator。
//
// 改前：占位底**写死** `Color.White`，而进度环前景随主题 ⇒ 夜间浅紫压白 = 1.70:1 ✗（白档 6.44 ✓）。
//       这是"容器不随主题、前景随主题"的半随主题错配。
// 改后：底换 `colorScheme.surfaceContainerLowest`（白档恰好 = 0xFFFFFFFF ⇒ **白天观感一字不变**；
//       夜档 #0F0D13），前景显式钉成 `colorScheme.primary`（同 M3 1.3.2 默认档
//       ProgressIndicatorTokens.ActiveIndicatorColor → Primary；写出来是为了通道同源可见、可测，
//       照 GigiToast.kt:122 的成对钉死口径）。
//
// 🔴 「纸面恒白」是不是业务要求？判定：**不是**（依据见本文件 sourceGate 那条与 QrBox 内注释）——
//   占位 Box 只在 `qr == null`（生成中/失败）时组合，与二维码永不同时出现；二维码位图自带白像素
//   （LoginViewModel.encodeQrBitmap 把亮模块写成 0xFFFFFFFF、并带 MARGIN=1 静区），
//   扫码所需的白底由位图自己提供，与容器无关；失败态更是**空的**白方块、没有任何可扫内容。
//   ⇒ 白底纯属观感延续，按最小改法换色槽，**不需要**走「白底保留 + 图形固定深档」的豁免路线。
//   （对照：QrBox 里"已扫描"蒙层那两处 `Color.White` 是**刻意与主题无关**的固定浅底浅图形配对，
//    图标与文字同值、G2 判 8.45/8.45 双档达标，属 D-4 正面样板，本棒不动、本测试也不把它算进违规。）
//
// 门槛：进度环属**图形/图标级** ⇒ 3.0。口径复用 com.gigi.tcg.ui.theme.ContrastUtils。

package com.gigi.tcg.ui.login

import com.gigi.tcg.ui.theme.ContrastUtils
import com.gigi.tcg.ui.theme.DarkColors
import com.gigi.tcg.ui.theme.LightColors
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginQrPlaceholderContrastTest {

    private companion object {
        const val GRAPHIC_LEVEL = 3.0

        /** 主题档 → (前景候选, 占位底)；前景两个候选都锁：primary 是钉死值，onSurface 是
         *  「万一组件走 LocalContentColor 继承」的那条通道，任一候选都必须过图形档 3.0。 */
        val Themes = listOf(
            Triple("夜", DarkColors, 0xFF0F0D13.toInt()),
            Triple("白", LightColors, 0xFFFFFFFF.toInt()),
        )
    }

    private fun readSource(): String {
        val file = File("src/main/java/com/gigi/tcg/ui/login/LoginScreen.kt")
        assertTrue("源码文件不存在: ${file.absolutePath}", file.exists())
        return Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
            .replace(file.readText(), "")
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
    }

    /** QrBox 函数体：从签名到列 0 的第一个 "\n}" */
    private fun qrBoxBody(src: String): String {
        val start = src.indexOf("private fun QrBox(")
        assertTrue("找不到 QrBox 函数", start >= 0)
        return src.substring(start, src.indexOf("\n}", start))
    }

    /** 验收主口径：进度环前景 × 占位底（surfaceContainerLowest），两主题 ≥3.0 */
    @Test
    fun spinnerForegrounds_meetGraphicLevel_onPlaceholderContainer() {
        Themes.forEach { (label, scheme, placeholderArgb) ->
            val bg = ContrastUtils.toArgb(scheme.surfaceContainerLowest)
            assertTrue("$label 占位底槽位应与基准一致", bg == placeholderArgb)
            listOf(
                "primary" to ContrastUtils.toArgb(scheme.primary),
                "onSurface" to ContrastUtils.toArgb(scheme.onSurface),
            ).forEach { (name, fg) ->
                val ratio = ContrastUtils.wcagContrast(fg, bg)
                assertTrue("$name/$label × 占位底 = ${"%.2f".format(ratio)}:1, need >= $GRAPHIC_LEVEL",
                    ratio >= GRAPHIC_LEVEL)
            }
        }
    }

    /** 占位底必须等于色板槽 surfaceContainerLowest（白档 = 纯白 ⇒ 白天"纸面"观感不变） */
    @Test
    fun placeholderContainer_isTheExpectedSlot_andStaysWhiteInLightTheme() {
        Themes.forEach { (label, scheme, expected) ->
            val actual = ContrastUtils.toArgb(scheme.surfaceContainerLowest)
            assertTrue("$label surfaceContainerLowest 必须 = ${Integer.toHexString(expected)}，" +
                "实际 ${Integer.toHexString(actual)}", actual == expected)
        }
    }

    /** 反例锁（根因留档）：写死的 Color.White 底 + 随主题的夜间前景 = 1.70，正是本轮了结的缺口 */
    @Test
    fun legacyWhiteContainerWithThemedForeground_isTheNightGap() {
        val ratio = ContrastUtils.wcagContrast(
            ContrastUtils.toArgb(DarkColors.primary),
            ContrastUtils.toArgb(LightColors.surfaceContainerLowest), // = 写死的 Color.White 同值
        )
        assertTrue("旧口径 夜 primary × 写死白底 = ${"%.2f".format(ratio)}:1，必须 <3.0", ratio < GRAPHIC_LEVEL)
    }

    /**
     * 源码闸门：
     *  a) QrBox 的**占位分支**（`} else {`）里不得再出现写死色（`Color.White` / `Color(0x..)`），
     *     底与前景都要角色槽 ⇒ 前景/背景同随主题；
     *  b) 进度环必须显式钉 `color = ...primary`（与底成对，别留默认值给人换）；
     *  c) "已扫描"蒙层那两处 `Color.White` 是刻意的固定配对（G2 D-4 样板），只许留在 Scanned 分支里。
     */
    @Test
    fun sourceGate_placeholderUsesSlots_notHardcodedWhite() {
        val body = qrBoxBody(readSource())
        val placeholder = body.substringAfter("} else {")
        assertTrue("占位分支应用 surfaceContainerLowest 槽", placeholder.contains("surfaceContainerLowest"))
        assertTrue(
            "占位分支不得再出现写死白/写死 hex（前景随主题、底不随主题就是本次根因）",
            !Regex("""Color\.White|Color\(0x""").containsMatchIn(placeholder),
        )
        assertTrue(
            "进度环前景必须显式钉成 colorScheme.primary（与底成对）",
            Regex("""CircularProgressIndicator\(\s*color\s*=\s*MaterialTheme\.colorScheme\.primary""")
                .containsMatchIn(placeholder),
        )
        // 蒙层那侧仍是一对固定白色（图标 + 文字同值）——本轮刻意不动，钉住防误改
        val scanned = body.substringAfter("QrPhase.Scanned").substringBefore("} else {")
        assertTrue("已扫描蒙层的固定白配对属 D-4 样板，不许被顺手改掉", scanned.contains("Color.White"))
    }
}
