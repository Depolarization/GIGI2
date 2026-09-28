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

/**
 * 并列名次（V29 需求 8，角色牌/行动牌 # 列共用）：返回与 [sorted] 等长、同序的名次数组。
 *
 * 竞赛排序口径：**并列同名次、下一名跳号**（1,2,2,4），与主流榜一致；
 * 依据是"当前排序键的值相等"即并列，与具体用哪个键无关。
 *
 * @param sorted 已按当前排序键排好序的列表（本屏的 sortedCharList / filteredActionList）
 * @param keyOf 取该行参与比较的排序键（Int 或 Double 均可，包装成 Comparable 比较）
 */
fun <T> ranksWithTies(sorted: List<T>, keyOf: (T) -> Comparable<*>): IntArray {
    val out = IntArray(sorted.size)
    var lastKey: Comparable<*>? = null
    var lastRank = 0
    sorted.forEachIndexed { index, item ->
        val key = keyOf(item)
        // 第一个元素或与上一行键值不同 ⇒ 名次就是「行号 + 1」；相同则沿用上一行的名次
        val rank = if (index == 0 || key != lastKey) index + 1 else lastRank
        out[index] = rank
        lastKey = key
        lastRank = rank
    }
    return out
}
