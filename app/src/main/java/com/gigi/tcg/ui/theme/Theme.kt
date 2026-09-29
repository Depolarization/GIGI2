package com.gigi.tcg.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class SemanticColors(
    val win: Color = WinColor,
    val lose: Color = LoseColor,
    val gold: Color = GoldColor,
)

val LocalSemanticColors: ProvidableCompositionLocal<SemanticColors> =
    compositionLocalOf { SemanticColors() }

/**
 * 主题入口。色板固定用 [LightColors] / [DarkColors]（见 Color.kt），**不再有动态取色开关**：
 * V37 真机夜间实测 MIUI 壁纸动态取色（原 `dynamicColor` 默认 true）会接管整个 ColorScheme，
 * 把夜间 Snackbar 解析成浅底 `#E6E0E9` + 浅字，对比度 ≈1.0:1 完全看不见，
 * 「导出数据图表」Button 也是浅紫底 + 浅紫字。用户决定关闭动态取色、走固定色板，
 * 所以 `dynamicLightColorScheme` / `dynamicDarkColorScheme` 分支与 `dynamicColor` 参数一并删除
 * （留一个恒为 false 的开关就是死代码）。要恢复动态取色请显式重新引入并覆写 inverse 三槽。
 */
@Composable
fun GigiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    // win/lose/gold 随主题取色：暗色档对 Card 容器(0xFF36343B) AA ✓（win 4.63、lose 4.58），
    // 但在亮底上对比度不足（约 2.6:1），亮色档换用深色调——对白底 AA ✓（约 5.0~5.1）、
    // 对 Card 容器(M3 light surfaceContainerLow ≈0xFFF7F2FA) AA ✓（win 4.57、lose 4.62）；
    // 判据与本函数 colorScheme 的 darkTheme 分支一致，不另引 isSystemInDarkTheme()。
    val semanticColors = SemanticColors(
        win = if (darkTheme) WinColor else WinColorLight,
        lose = if (darkTheme) LoseColor else LoseColorLight,
        gold = if (darkTheme) GoldColor else GoldColorLight,
    )

    CompositionLocalProvider(LocalSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}
