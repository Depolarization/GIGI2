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
