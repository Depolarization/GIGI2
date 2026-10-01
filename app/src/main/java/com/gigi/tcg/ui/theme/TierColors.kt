// 段位文本色：把「段位」这一语义直接编码进颜色，取代此前一律用 semantic.gold 的写法
// （黄铜/星银/赤金/影幻 此前全染成金，等于没有信息）。
// 🔴 V37-4 取色来路：四档色相**实测取自官方段位图标本体**
// （webstatic.mihoyo.com/ys/event/tcgmatch/images/home_page_rank_{1..4}_badge.*.png，
// 脚本与产物 .task/v37-probe/extract_tier_colors.py / badges/*.png / tier_colors.json，
// 保留像素众数色相：黄铜 19.4° 橙棕｜星银 212.6° 冷蓝灰｜赤金 44.2° 金｜影幻 316.0° 紫粉）。
// 旧值里偏得最多的是**影幻**：旧 #8A6AE0 是蓝紫，官方图实测是紫粉（#D157B0）。
//
// 🔴 V39-H：**改随主题两档**（照语义色 WinColor/WinColorLight 的做法），并按正文级 4.5 重定档。
// 为什么非拆不可：V37-4 那套是「单套定色 + 3.0 门槛」，前提是段位属 WCAG large text——
// 但段位实际随 `titleMedium`(16sp) Bold 上屏，16sp 粗体**够不上** large text（需 ≥18.66sp 粗体），
// 按 WCAG 属正文档 ⇒ 门槛 4.5。旧单套值 × 真 Card 底实测只有 夜 3.23~3.33 / 白 2.84~2.93，双向不达标。
// 改法：**色相/饱和度锁死在官方徽章实测值，只把亮度压(浅档)/提(深档)到 4.5 可行区间**
// ——V37-4 原本就是「亮度让路、色相不动」，本轮只是把让路的目标从 3.0 换成 4.5，
// 单套做不到（浅底要暗、深底要亮），所以必须拆两套。只有「无段位」回落到
// colorScheme.onSurfaceVariant，那一路本来就随主题。
// 深浅两档各自的绑定容器都是**无变体 Card 的真底** `surfaceContainerHighest`
// （夜 #36343B / 白 #E6E0E9；material3 1.3.2 `FilledCardTokens.ContainerColor`，V39-G 用 M3 sources 核实。
// 注意 `#F7F2FA` 是 `surfaceContainerLow`＝ElevatedCard/Sheet 那一档，**不是** Card）。

package com.gigi.tcg.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 对比度实测（[ContrastUtils.wcagContrast]，正文门槛 4.5；V39-H 重定档后由 TierColorsTest 逐底钉死）：
//   深档 × 夜 Card #36343B / 夜 surface #141218 / 夜弹窗底 #2B2930 / 夜 Sheet #1D1B20
//     黄铜 4.66 7.06 5.45 6.48｜星银 4.66 7.05 5.45 6.48｜赤金 4.65 7.05 5.44 6.47｜影幻 4.66 7.07 5.46 6.49
//   浅档 × 白 Card #E6E0E9 / 白 surface #FEF7FF / 白弹窗底 #ECE6F0 / 白 Sheet #F7F2FA / 纯白
//     黄铜 4.66 5.74 4.93 5.47 6.04｜星银 4.65 5.73 4.92 5.47 6.03
//     赤金 4.68 5.76 4.95 5.50 6.06｜影幻 4.66 5.74 4.93 5.47 6.04
// 两档最差值都落在白/夜 Card 那一档（比弹窗底更接近中性灰，压对比度最狠）。

/** 黄铜（深色档）：官方 1 段徽章色相 19.4°/饱和 31.6% 不变，亮度 52.0→63.3% 使夜 Card ≥4.5 */
internal const val TIER_ARGB_BRASS = 0xFFBF9784.toInt()

/** 星银（深色档）：官方 2 段徽章色相 212.6°/饱和 30.1% 不变，亮度 53.9→64.1% */
internal const val TIER_ARGB_SILVER = 0xFF88A2BF.toInt()

/** 赤金（深色档）：官方 3 段徽章色相 44.2°/饱和 81.8% 不变，亮度 36.1→43.1% */
internal const val TIER_ARGB_GOLD = 0xFFC89814.toInt()

/** 影幻（深色档）：官方 4 段徽章色相 316.0°/饱和 56.5% 不变，亮度 58.0→68.4%（旧单套值 #D157B0 即此色相） */
internal const val TIER_ARGB_PHANTOM = 0xFFDC81C4.toInt()

/** 黄铜（亮色档，V39-H 新增）：同色相/饱和，亮度压到 39.0% 使白 Card #E6E0E9 ≥4.5 */
internal const val TIER_ARGB_BRASS_LIGHT = 0xFF835944.toInt()

/** 星银（亮色档，V39-H 新增）：纯银白在浅底上不可读，压到亮度 40.4% 才过正文档 */
internal const val TIER_ARGB_SILVER_LIGHT = 0xFF486586.toInt()

/** 赤金（亮色档，V39-H 新增）：同色相/饱和，亮度压到 26.9% */
internal const val TIER_ARGB_GOLD_LIGHT = 0xFF7C5E0D.toInt()

/** 影幻（亮色档，V39-H 新增）：同色相/饱和，亮度压到 42.5% */
internal const val TIER_ARGB_PHANTOM_LIGHT = 0xFFAA2F89.toInt()

// 段位名 → 色（深色档）。键必须收齐三语 tier_* 资源的实际值 + 接口可能返回的英文段位名：
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

private val TIER_COLOR_BY_NAME_LIGHT = mapOf(
    "黄铜" to TIER_ARGB_BRASS_LIGHT, "黃銅" to TIER_ARGB_BRASS_LIGHT, "Brass" to TIER_ARGB_BRASS_LIGHT,
    "星银" to TIER_ARGB_SILVER_LIGHT, "星銀" to TIER_ARGB_SILVER_LIGHT, "Silver" to TIER_ARGB_SILVER_LIGHT,
    "赤金" to TIER_ARGB_GOLD_LIGHT, "Gold" to TIER_ARGB_GOLD_LIGHT,
    "影幻" to TIER_ARGB_PHANTOM_LIGHT, "幻影" to TIER_ARGB_PHANTOM_LIGHT, "Phantom" to TIER_ARGB_PHANTOM_LIGHT,
)

private fun resolveTierArgb(table: Map<String, Int>, tier: String?): Int? {
    val name = tier?.trim()?.trimEnd('★') ?: return null
    if (name.isEmpty()) return null
    return table[name] ?: table.entries.firstOrNull { (key, _) -> name.startsWith(key) }?.value
}

/**
 * 段位名（可带 ★ 星缀、可带本地化文案）→ 深色档 ARGB。返回 null 表示不强调，由调用侧取主题默认色。
 * 纯函数、不依赖 Compose/Android，供 JVM 单测直接锁配色与对比度。
 */
internal fun tierColorArgb(tier: String?): Int? = resolveTierArgb(TIER_COLOR_BY_NAME, tier)

/** 段位名 → 指定主题档的 ARGB；`darkTheme=false` 走 V39-H 新增的亮色档，其余规则同上 */
internal fun tierColorArgb(tier: String?, darkTheme: Boolean): Int? =
    resolveTierArgb(if (darkTheme) TIER_COLOR_BY_NAME else TIER_COLOR_BY_NAME_LIGHT, tier)

/**
 * 当前主题是否深色档。判据取 `colorScheme.background` 的相对亮度而不是 `isSystemInDarkTheme()`：
 * [GigiTheme] 的深浅是**入参**（真机调试期被显式钉过），只有色板本身才等于屏幕上实际的底。
 */
private fun ColorScheme.isDarkTierTheme(): Boolean =
    ContrastUtils.relativeLuminance(ContrastUtils.toArgb(background)) < 0.5

/** 段位文本色，随主题取浅/深两档；无段位/未知段位回落到 onSurfaceVariant */
@Composable
fun tierColor(tier: String): Color {
    val colorScheme = MaterialTheme.colorScheme
    return tierColorArgb(tier, colorScheme.isDarkTierTheme())?.let { Color(it) }
        ?: colorScheme.onSurfaceVariant
}
