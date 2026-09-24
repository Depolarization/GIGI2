// 段位计算：逐字对照 Web 版 utils/tier.ts（源自 utils.lua get_tier_stars 阈值链）。
// 输入天梯积分，输出 [段位名, 星数]。

package com.gigi.tcg.domain

data class TierStars(val tier: String, val stars: Int)

// score < upper 归入该档；upper 严格对应原版各 elseif 分支的阈值。
private val TIER_TABLE: List<Triple<Int, String, Int>> = listOf(
    Triple(1200, "黄铜", 1),
    Triple(1400, "黄铜", 2),
    Triple(1600, "黄铜", 3),
    Triple(1800, "黄铜", 4),
    Triple(2000, "黄铜", 5),
    Triple(2100, "星银", 1),
    Triple(2200, "星银", 2),
    Triple(2300, "星银", 3),
    Triple(2400, "星银", 4),
    Triple(2500, "星银", 5),
    Triple(2600, "赤金", 1),
    Triple(2700, "赤金", 2),
    Triple(2800, "赤金", 3),
    Triple(2900, "赤金", 4),
    Triple(3000, "赤金", 5),
)

fun getTierStars(score: Int): TierStars {
    if (score < 1) {
        return TierStars("", 0)
    }
    for ((upper, tier, stars) in TIER_TABLE) {
        if (score < upper) {
            return TierStars(tier, stars)
        }
    }
    return TierStars("影幻", 0)
}

/** 段位展示文本，如 "黄铜★★★"（原版 string.rep('★', stars) 语义，0 星不显示） */
fun formatTier(t: TierStars): String = t.tier + "★".repeat(t.stars)
