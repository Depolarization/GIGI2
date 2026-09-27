// 段位文本色：把「段位」这一语义直接编码进颜色，取代此前一律用 semantic.gold 的写法
// （黄铜/星银/赤金/影幻 此前全染成金，等于没有信息）。
// 色相取自原神赛事官网各段位图标的材质色（铜→银→赤金→幻紫），亮度按 WCAG/M3 文字对比度微调。
// 这四个值是**单套定色**：白底、浅 Card、浅弹窗底、深色 surface、深色弹窗底五种容器上
// 都在 3:1 及以上（最紧的是浅弹窗底 3.18:1，实测表见下）；
// 因此不随 isSystemInDarkTheme 拆深浅两套、也不接 Material You 动态取色；
// 只有「无段位」回落到 colorScheme.onSurfaceVariant，那一路才走主题。

package com.gigi.tcg.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 对比度实测（WCAG，依次为 白底 / 浅 Card 0xF7F2FA / 浅弹窗底 0xECE6F0 / 深色 surface 0x1C1B1F / 深色弹窗底 0x2B2932）：
//   黄铜 4.11 3.72 3.35 4.17 3.49｜星银 4.31 3.91 3.52 3.97 3.32
//   赤金 3.90 3.53 3.18 4.40 3.68｜影幻 4.02 3.65 3.29 4.26 3.56

/** 黄铜：铜棕，与官网图标 #A9713B 同值未改 */
internal const val TIER_ARGB_BRASS = 0xFFA9713B.toInt()

/** 星银：冷调银蓝灰。纯银白在浅底上不可读，压暗到这个亮度才五底都过 3:1 */
internal const val TIER_ARGB_SILVER = 0xFF6E7B8B.toInt()

/** 赤金：官网金 #C9A227 白底只有 2.42:1，压深一档（色相不变的深赤金） */
internal const val TIER_ARGB_GOLD = 0xFFA07C15.toInt()

/** 影幻：幻紫。官网 #7C5CD6 在深色弹窗底 2.97:1 差一线，提亮一档、色相不变 */
internal const val TIER_ARGB_PHANTOM = 0xFF8A6AE0.toInt()

// 段位名 → 色。键必须收齐三语 tier_* 资源的实际值 + 接口可能返回的英文段位名：
// 上屏文案经 tierLabel() 本地化后是「当前语言的段位名 + ★」，只认简化字会漏掉繁中环境
// （繁中「黃銅」的「黃」与简化字「黄」是不同码位），漏命中只是不着色，不会失读。
private val TIER_COLOR_BY_NAME = mapOf(
    "黄铜" to TIER_ARGB_BRASS, "黃銅" to TIER_ARGB_BRASS, "Brass" to TIER_ARGB_BRASS,
    "星银" to TIER_ARGB_SILVER, "星銀" to TIER_ARGB_SILVER, "Silver" to TIER_ARGB_SILVER,
    "赤金" to TIER_ARGB_GOLD, "Gold" to TIER_ARGB_GOLD,
    "影幻" to TIER_ARGB_PHANTOM, "Phantom" to TIER_ARGB_PHANTOM,
)

/**
 * 段位名（可带 ★ 星缀、可带本地化文案）→ ARGB。返回 null 表示不强调，由调用侧取主题默认色。
 * 纯函数、不依赖 Compose/Android，供 JVM 单测直接锁配色与对比度。
 */
internal fun tierColorArgb(tier: String?): Int? {
    val name = tier?.trim()?.trimEnd('★') ?: return null
    if (name.isEmpty()) return null
    return TIER_COLOR_BY_NAME[name]
        ?: TIER_COLOR_BY_NAME.entries.firstOrNull { (key, _) -> name.startsWith(key) }?.value
}

/** 段位文本色；无段位/未知段位回落到 onSurfaceVariant（不参与动态取色的四档定色见文件头注释） */
@Composable
fun tierColor(tier: String): Color =
    tierColorArgb(tier)?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant
