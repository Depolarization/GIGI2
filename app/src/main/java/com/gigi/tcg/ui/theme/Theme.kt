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

    // win/lose/gold 随主题取色，两档都按**无变体 Card 的真容器**定档：material3 1.3.2
    // `FilledCardTokens.ContainerColor = surfaceContainerHighest`（夜 #36343B / 白 #E6E0E9）。
    // ⚠️ 旧注释把 #F7F2FA 称作「Card 容器」是错的——那是 `surfaceContainerLow`，只有 ElevatedCard /
    // 底部弹 Sheet 吃它；亮色档照那个错底调出来的是 3.89~3.94（正文门槛 4.5），V39-H 已重定档。
    // 实测（[ContrastUtils]，正文门槛 4.5）：
    //   暗色档 × #36343B  win 4.63、lose 4.58、gold 5.46
    //   亮色档 × #E6E0E9  win 4.69、lose 4.66、gold 4.69（× 白 surface 5.74~5.78、× 弹窗底 4.93~4.96）
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
