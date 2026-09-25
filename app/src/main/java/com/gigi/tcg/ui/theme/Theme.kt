package com.gigi.tcg.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Build

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Immutable
data class SemanticColors(
    val win: Color = WinColor,
    val lose: Color = LoseColor,
    val gold: Color = GoldColor,
)

val LocalSemanticColors: ProvidableCompositionLocal<SemanticColors> =
    compositionLocalOf { SemanticColors() }

@Composable
fun GigiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // win/lose/gold 随主题取色：亮色下暗色档对比度不足（win 约 3.96:1、lose 约 4.41:1、
    // gold 约 2.3:1，均低于 WCAG AA 4.5:1），换用深色调档；
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
