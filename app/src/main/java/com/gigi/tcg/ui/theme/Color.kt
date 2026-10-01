package com.gigi.tcg.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// 暗色档深绿：深色 Card 容器(= material3 1.3.2 无变体 Card 的 surfaceContainerHighest 0xFF36343B) 上过 WCAG AA
// （实测 4.63:1，旧值 0xFF439865 仅 3.46:1；对 dark surface 0xFF1C1B1F 达 6.47:1）。
// 全部语义色仅作文字/图标前景色，无一处作背景块，提亮只升对比度。
// HSL 色相约 144°不变、只提亮度（观感保持深绿），亮度受 AA 下限约束。
val WinColor = Color(0xFF58B07E)
// 暗色档深红：深色 Card 容器(0xFF36343B) 上过 WCAG AA（实测 4.58:1，旧值 0xFFCA6D6D 仅 3.46:1；
// 对 dark surface 0xFF1C1B1F 达 6.39:1）。
// HSL 色相 0°、纯红（R>G=B）不变，只提亮度，观感保持深红。
val LoseColor = Color(0xFFE88080)
// 暗色档金（tokens.css --color-gold，排行榜前三固定色也用它，勿改值）
val GoldColor = Color(0xFFD4A643)

// 🔴 亮色档三枚的定档底 = **真 Card 容器** `surfaceContainerHighest` = 0xFFE6E0E9（material3 1.3.2
// `FilledCardTokens.ContainerColor`，V39-G 用 M3 sources 核实）。此前按 0xFFF7F2FA 调是**调错了档**：
// `surfaceContainerLow` 那一档属 ElevatedCard / 底部弹Sheet，普通 `Card()` 根本不吃它，
// 于是白天 Card 上的胜负文案实测只有 3.89~3.94，稳定不达标（正文门槛 4.5）。
// 现按「同 HSL 色相+饱和度、只压亮度」重定档，四底全过正文级 AA（门槛 4.5，[ContrastUtils] 实测）：
// ```
//                     浅 Card      浅 surface   浅弹窗底      surfaceContainerLow   纯白
//                     #E6E0E9      #FEF7FF      #ECE6F0       #F7F2FA              #FFFFFF
//   WinColorLight     4.69          5.78         4.96          5.51                 6.08   (旧 #2E7D4F: 3.89)
//   LoseColorLight    4.66          5.74         4.93          5.48                 6.04   (旧 #B84A4A: 3.94)
//   GoldColorLight    4.69          5.78         4.96          5.51                 6.08   (旧 #8A6A16: 3.90)
// ```
// 越往浅的底越宽松 ⇒ 浅 Card 是绑定档；改这几枚必须连这段表一起改。
val GoldColorLight = Color(0xFF7B5E14)
val WinColorLight = Color(0xFF296F46)
val LoseColorLight = Color(0xFFA74141)

/**
 * WCAG 2.1 相对亮度 / 对比度工具。放在主源码里，是为了让「色板达标」这件事既能被
 * [ThemeContrastTest] 钉死，也能被调色时直接复用，不必在测试里另写一份会漂移的实现。
 *
 * 口径：入参是 0xAARRGGBB 的 Int，逐通道 sRGB 线性化后加权。
 * 注意 [Color] 的 `red/green/blue` 返回的是 **sRGB 0..1（未线性化）**，且本工程 Compose
 * 版本上 `Color(0x..u)`（UInt 重载）解分量是坏的（详见 SemanticContrastTest 的实测坑记录），
 * 所以这里统一先四舍五入回 8-bit Int 再算。
 */
internal object ContrastUtils {

    private fun linearize(channel255: Int): Double {
        val c = channel255 / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** WCAG 相对亮度，0.0（黑）..1.0（白） */
    fun relativeLuminance(argb: Int): Double =
        0.2126 * linearize((argb shr 16) and 0xFF) +
            0.7152 * linearize((argb shr 8) and 0xFF) +
            0.0722 * linearize(argb and 0xFF)

    fun toArgb(color: Color): Int {
        val r = Math.round(color.red * 255f)
        val g = Math.round(color.green * 255f)
        val b = Math.round(color.blue * 255f)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 前景/背景对比度，1.0（同色）..21.0（黑白）。与方向无关，故 fg/bg 谁亮谁暗都可。 */
    fun wcagContrast(fg: Int, bg: Int): Double {
        val lf = relativeLuminance(fg)
        val lb = relativeLuminance(bg)
        return (maxOf(lf, lb) + 0.05) / (minOf(lf, lb) + 0.05)
    }

    fun wcagContrast(fg: Color, bg: Color): Double = wcagContrast(toArgb(fg), toArgb(bg))
}

/**
 * V37-B 固定色板（浅色）。
 *
 * 背景：V37 真机夜间实测——MIUI 壁纸动态取色（`dynamicColor` 默认开）接管**整个** ColorScheme，
 * 夜间 snackbar 解析出浅底 `(230,224,233)=#E6E0E9` + 浅字，对比度 ≈1.0:1，肉眼全白；
 * 「导出数据图表」Button 同样浅紫底 + 浅紫字。用户决定关闭动态取色、走固定色板，
 * 且色槽必须显式定义（此前 [lightColorScheme] / [darkColorScheme] 是无参调用，全靠 M3 默认）。
 *
 * 取色原则：**不追求换风格**，除下面标注的 inverse 三槽外，全部沿用 Material3 1.3.2 基线紫灰，
 * 所以观感与「动态取色关掉后的静态主题」一致。基线值是本棒用 JVM 探针实测 `lightColorScheme()` /
 * `darkColorScheme()` 打出来的，不是记忆值。
 *
 * WCAG 2.1 对比度（[ContrastUtils.wcagContrast] 实测，正文级门槛 4.5:1）：
 * ```
 * onSurface/surface                     16.23   onSurface/surfaceContainerHighest  13.17
 * onSurfaceVariant/surface               8.88   onSurfaceVariant/surfaceVariant     7.24
 * onPrimary/primary                      6.44   onSecondary/secondary               6.45
 * onTertiary/tertiary                    6.47   onError/error                       6.54
 * onBackground/background               16.23   onPrimaryContainer/primaryContainer 13.32
 * onSecondaryContainer/secondaryContainer 13.24 onTertiaryContainer/tertiaryContainer 13.18
 * onErrorContainer/errorContainer       12.77
 * inverseOnSurface/inverseSurface       11.65   inversePrimary/inverseSurface        7.73  <- snackbar 正文/「查看」
 * ```
 * 大字/图标级（门槛 3.0:1）：primary/surface 6.12、onSurfaceVariant/surfaceContainerHighest 7.21、
 * outline/surface 4.33、error/surface 6.21。
 */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    inversePrimary = Color(0xFFD0BCFF),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceTint = Color(0xFF6750A4),
    // snackbar 底：深灰紫；正文：浅色。浅底浅字那个坑在浅色主题下本来就不存在。
    inverseSurface = Color(0xFF322F35),
    inverseOnSurface = Color(0xFFF5EFF7),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFEF7FF),
    surfaceDim = Color(0xFFDED8E1),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
)

/**
 * V37-B 固定色板（深色）。除 inverse 三槽外沿用 Material3 1.3.2 基线紫灰（值见 [LightColors] 注释）。
 *
 * 🔴 inverse 三槽是本棒真正修的坑，**不能按 M3 基线照抄**：
 * M3 1.3.2 深色基线 `inverseSurface = #E6E0E9`（浅）、`inverseOnSurface = #322F35`（深）、
 * `inversePrimary = #6750A4`（深）。基线把「inverse」当**整对反相**——深色主题的 inverse 面是浅底深字。
 * M3 Snackbar 默认正是吃这三个槽，所以夜间只要有任何一侧被动态取色改成浅色
 * （真机 MIUI 就把 `inverseOnSurface` 也解析成了浅色，实测底/字同为 `#E6E0E9` ⇒ 1.0:1 全看不见），
 * 整条 snackbar 立刻不可读。这里改成**夜间也是深底浅字**，与页面其它面同相，观感更一致，
 * 也不再依赖「两侧同时反相」这个脆弱前提：
 * ```
 * inverseSurface    #E6E0E9 浅 -> 0xFF2B2930 深（= surfaceContainerHigh 档，与既有弹窗底 0xFF2B2932 同族）
 * inverseOnSurface  #322F35 深 -> 0xFFE6E0E9 浅（= 深色主题 onSurface 同值）
 * inversePrimary    #6750A4 深 -> 0xFFD0BCFF 浅（= 深色主题 primary 同值）
 * ```
 * ⚠️ 连带：容器变深后 `inversePrimary` **必须**跟着换成浅紫——基线那个深紫 `#6750A4` 落在
 * `#2B2930` 上只有 2.28:1，「查看」按钮又会看不清。凡吃 `inverseSurface` 的组件都跟着变深，
 * 这是有意的全局行为，不只是 Snackbar。
 *
 * WCAG 2.1 对比度（实测，正文级门槛 4.5:1）：
 * ```
 * onSurface/surface                     14.35   onSurface/surfaceContainerHighest   9.47
 * onSurfaceVariant/surface              10.91   onSurfaceVariant/surfaceVariant      5.48
 * onPrimary/primary                      7.71   onSecondary/secondary               7.74
 * onTertiary/tertiary                    7.75   onError/error                       7.66
 * onBackground/background               14.35   onPrimaryContainer/primaryContainer  7.23
 * onSecondaryContainer/secondaryContainer 7.19  onTertiaryContainer/tertiaryContainer 7.20
 * onErrorContainer/errorContainer        7.17
 * inverseOnSurface/inverseSurface       11.08   inversePrimary/inverseSurface         8.42  <- snackbar 正文/「查看」
 * ```
 * 大字/图标级（门槛 3.0:1）：primary/surface 10.91、onSurfaceVariant/surfaceContainerHighest 7.20、
 * outline/surface 5.87、error/surface 10.89。
 */
internal val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    inversePrimary = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceTint = Color(0xFFD0BCFF),
    // 🔴 夜间 snackbar：深底 + 浅字（详见类注释）。绝不要把 inverseSurface 设回浅色。
    inverseSurface = Color(0xFF2B2930),
    inverseOnSurface = Color(0xFFE6E0E9),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3B383E),
    surfaceDim = Color(0xFF141218),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
)
