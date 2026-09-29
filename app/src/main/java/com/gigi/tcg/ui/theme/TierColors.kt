// 段位文本色：把「段位」这一语义直接编码进颜色，取代此前一律用 semantic.gold 的写法
// （黄铜/星银/赤金/影幻 此前全染成金，等于没有信息）。
// 🔴 V37-4 取色来路：四档色相**实测取自官方段位图标本体**
// （webstatic.mihoyo.com/ys/event/tcgmatch/images/home_page_rank_{1..4}_badge.*.png，
// 脚本与产物 .task/v37-probe/extract_tier_colors.py / badges/*.png / tier_colors.json，
// 保留像素众数色相：黄铜 19.4° 橙棕｜星银 212.6° 冷蓝灰｜赤金 44.2° 金｜影幻 316.0° 紫粉）。
// 亮度（不是色相）为满足「五底 WCAG ≥ 3:1」而让路：官方图标本体是渐变+描边，直接取本体色在浅弹窗底
// 掉到 2.x，故锁死色相/饱和度、只压亮度到可行区间上沿。
// 旧值里偏得最多的是**影幻**：旧 #8A6AE0 是蓝紫，官方图实测是紫粉（#D157B0）。
// 这四个值是**单套定色**：白底、浅 Card、浅弹窗底、深色 surface、深色弹窗底五种容器上
// 都在 3:1 及以上（最紧的是浅弹窗底 3.01:1，实测表见下）；
// 因此不随 isSystemInDarkTheme 拆深浅两套、也不接 Material You 动态取色；
// 只有「无段位」回落到 colorScheme.onSurfaceVariant，那一路才走主题。

package com.gigi.tcg.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 对比度实测（WCAG，依次为 白底 / 浅 Card 0xF7F2FA / 浅弹窗底 0xECE6F0 / 深色 surface(0x141218, material3 1.3.2 实测基线; compose-bom 2025.09.00 不抬高版本; 旧注释误记为 0x1C1B1F) / 深色弹窗底 0x2B2932）：
//   黄铜 3.80 3.44 3.10 4.51 3.77｜星银 3.73 3.38 3.04 4.60 3.84
//   赤金 3.69 3.35 3.01 4.64 3.88｜影幻 3.68 3.34 3.01 4.65 3.89
// 五底最差值落在浅弹窗底（3.01~3.10），深色两档反而最宽松（图标本体偏亮）。

/** 黄铜：官方 1 段徽章本体色相 19.4°（橙棕），亮度压到 0.52 使五底 ≥3:1 */
internal const val TIER_ARGB_BRASS = 0xFFAB775E.toInt()

/** 星银：官方 2 段徽章本体色相 212.6°（冷蓝灰）。纯银白在浅底上不可读，压暗到这个亮度才五底都过 3:1 */
internal const val TIER_ARGB_SILVER = 0xFF6687AD.toInt()

/** 赤金：官方 3 段徽章本体色相 44.2°（金），旧值 #A07C15 同色相、按图标实测饱和度微调 */
internal const val TIER_ARGB_GOLD = 0xFFA77F11.toInt()

/** 影幻：官方 4 段徽章本体色相 316.0°（紫粉）。旧值 #8A6AE0 是蓝紫，与官方图不符，V37-4 按实测色相改粉 */
internal const val TIER_ARGB_PHANTOM = 0xFFD157B0.toInt()

// 段位名 → 色。键必须收齐三语 tier_* 资源的实际值 + 接口可能返回的英文段位名：
// 上屏文案经 tierLabel() 本地化后是「当前语言的段位名 + ★」，只认简化字会漏掉繁中环境
// （繁中「黃銅」的「黃」与简化字「黄」是不同码位），漏命中只是不着色，不会失读。
// 繁中实测：tier_brass=黃銅、tier_silver=星銀、tier_gold=赤金（与简化同形）、tier_phantom=影幻（同形）；
// 「幻影」是繁中/日文书写里对 Phantom 的另一种写法，接口并不下发，收进来只是防御性别名。
private val TIER_COLOR_BY_NAME = mapOf(
    "黄铜" to TIER_ARGB_BRASS, "黃銅" to TIER_ARGB_BRASS, "Brass" to TIER_ARGB_BRASS,
    "星银" to TIER_ARGB_SILVER, "星銀" to TIER_ARGB_SILVER, "Silver" to TIER_ARGB_SILVER,
    "赤金" to TIER_ARGB_GOLD, "Gold" to TIER_ARGB_GOLD,
    "影幻" to TIER_ARGB_PHANTOM, "幻影" to TIER_ARGB_PHANTOM, "Phantom" to TIER_ARGB_PHANTOM,
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
