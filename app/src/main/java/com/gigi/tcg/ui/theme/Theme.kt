package com.gigi.tcg.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import com.gigi.tcg.domain.ScoreDelta
import com.gigi.tcg.domain.scoreDelta

@Immutable
data class SemanticColors(
    val win: Color = WinColor,
    val lose: Color = LoseColor,
    val gold: Color = GoldColor,
)

val LocalSemanticColors: ProvidableCompositionLocal<SemanticColors> =
    compositionLocalOf { SemanticColors() }

/**
 * 积分变化 → 语义色。**这是"变化量上色"的唯一口径**（V42）。
 *
 * 背景：记录卡的「天梯/巅峰 积分变化」此前是**恒定**着色——天梯恒 `win`（绿）、巅峰恒 `gold`（金），
 * 颜色压根不携带信息，于是**减分被染成绿色**。用户 2026-10-02 转达玩家建议「减分改成红色/橙色更直观」。
 *
 * 口径（复用既有 win/lose 两枚语义色，不新造色、不新开色板槽）：
 * | 符号 | 颜色 | 理由 |
 * | :-- | :-- | :-- |
 * | 涨 (>0) | [SemanticColors.win] | 与同卡右侧「胜」同色，赢=绿这条线全 App 统一 |
 * | 跌 (<0) | [SemanticColors.lose] | 与同卡右侧「负」同色，"红=不好"全 App 统一 |
 * | 平 (0)  | `onSurfaceVariant` | 中性降强调，不占用红绿任何一档（染成任一色都是撒谎） |
 *
 * 🔴 为什么不直接用 `colorScheme.error`：M3 里 `error` 的语义角色是**错误/失败状态**，
 * 减分不是错误；用它会与右侧已是 lose 色的「负」撞成两种红，让"红"在一张卡里指两件事。
 * （对比度不是理由——实测 error 在两档都过正文门槛：暗 7.19:1 / 亮 5.04:1。）
 * 🔴 为什么不单独开一档"橙"：橙与本卡 `semantic.gold`(#D4A643，5.46:1) 色相太近，14sp 小字上更难分辨；
 * 而 M3 的 ColorScheme 根本没有"数值下降"这个角色，硬加一档等于破坏色板体系（动态取色也会冲掉）。
 *
 * 三色实测对比度（无变体 Card 容器 = `surfaceContainerHighest`，正文门槛 4.5，全部通过）：
 * | 槽位 | 暗 × #36343B | 亮 × #E6E0E9 |
 * | :-- | :-- | :-- |
 * | win | 4.63 | 4.69 |
 * | lose | 4.58 | 4.66 |
 * | onSurfaceVariant | 7.20 | 7.21 |
 * 语义色**只作前景**，不作背景块（叠加白字必然对比度不足）。
 * 颜色不是唯一信道：负数文案自带 `-`（`formatScoreChange`），不依赖颜色也能分辨涨跌（WCAG 1.4.1）。
 */
@Composable
fun scoreDeltaColor(change: Int): Color = when (scoreDelta(change)) {
    ScoreDelta.Up -> LocalSemanticColors.current.win
    ScoreDelta.Down -> LocalSemanticColors.current.lose
    ScoreDelta.Flat -> MaterialTheme.colorScheme.onSurfaceVariant
}

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
