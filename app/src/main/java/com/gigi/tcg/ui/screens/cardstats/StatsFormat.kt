// 卡牌统计右侧数值列的格式化：本屏专用纯函数（不进 domain，避免改动全局 formatPercent 的数据口径）。

package com.gigi.tcg.ui.screens.cardstats

import java.util.Locale

/**
 * 统计百分比格式化：统一保留 1 位小数（含 ".0"，如 23.0%、12.0%、100.0%），
 * 供等宽右对齐的数值列纵向对齐使用。
 *
 * 与 web `utils/percent.ts` 的 formatPercent 语义差异：web 会把 "x.0%" 去尾为 "x%"，
 * 而本棒需求要求"统一位数"以保证四列对齐，故不再去 ".0"。
 * 非法值（null/NaN/Infinity）→ "0.0%"（与 domain 侧"非法记 0"口径一致）。
 */
fun formatStatPercent(value: Double?): String {
    if (value == null || !value.isFinite()) {
        return "0.0%"
    }
    return String.format(Locale.US, "%.1f%%", value)
}
