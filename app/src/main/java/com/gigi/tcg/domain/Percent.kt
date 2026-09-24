// 百分比计算与格式化：逐字对照 Web 版 utils/percent.ts（原 game.lua calc_percent / format_percent）。

package com.gigi.tcg.domain

import java.util.Locale

/** 分母 <= 0 时记 0（原版 (total > 0) and (part / total * 100) or 0） */
fun calcPercent(part: Double, total: Double): Double {
    return if (total > 0) part / total * 100 else 0.0
}

/**
 * 保留一位小数，形如 "x.0" 时去尾为整数（12.0% → 12%）；
 * 值非法（null/NaN/Infinity）→ "0%"。
 */
fun formatPercent(value: Double?): String {
    if (value == null || !value.isFinite()) {
        return "0%"
    }
    var str = String.format(Locale.US, "%.1f", value)
    if (str.endsWith(".0")) {
        str = str.substring(0, str.length - 2)
    }
    return "$str%"
}

/**
 * 百分比排序键：返回可比较的数值（非法输入取 0）。
 * 用于按"胜率"排序，避免比较 "9%" 与 "10%" 时的字符串字典序错误。
 */
fun percentSortKey(value: Double?): Double {
    return if (value != null && value.isFinite()) value else 0.0
}
