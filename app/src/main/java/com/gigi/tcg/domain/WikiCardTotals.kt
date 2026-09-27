// 导出图胶囊 `角色牌 143/147` 的分母来源。两个来源、两种口径：
// 1️⃣ 首选 gcg/basicInfo 的 *_card_num_total（见 [officialCardTotals]）——官方权威值，实测 角色牌 147 / 行动牌 941。
// 2️⃣ 兜底公开图鉴频道树计数（本文件原 [wikiCardTotals]）：🔴 图鉴接口**会去重手牌**，行动牌只数出 568
//    远小于真实 941 ⇒ 单独当分母会把「没收集全」显示成「全收集」，这正是本次修的 bug。
// 老逻辑之所以依赖图鉴：gcg/cardList 实测从不返回 *_card_num_total（恒 null），回退成"已得数" ⇒ 143/143。
// 本文件只做纯计算（不碰 android.graphics / Context），便于 JVM 单测；
// 容错风格与 WikiParser 一致：树形态异常/频道缺失/字段缺失一律折算 0，绝不抛异常。

package com.gigi.tcg.domain

import com.gigi.tcg.data.model.GcgBasicInfoData
import com.gigi.tcg.data.model.WikiChannelNode

/** 角色牌频道 id（与 CardWikiViewModel 的三分类频道一致） */
const val WIKI_CHANNEL_CHAR: Int = 233

/** 行动牌频道 id（魔物牌 235 不属于"行动牌"口径，不计入） */
const val WIKI_CHANNEL_ACTION: Int = 234

/** 总手牌数（来自图鉴或 basicInfo；缺失/解析失败为 0，由调用方决定兜底） */
data class WikiCardTotals(
    val avatarTotal: Int = 0,
    val actionTotal: Int = 0,
)

/**
 * 累加指定频道下的卡牌条目数。
 * 实测口径（2026-09 打过 channel_id=231 真接口）：树只有两级 `[231 卡牌图鉴] → children [233/234/235]`，
 * 三个频道自身 `children` 为空、条目全在各自 `list` 里 ⇒ 正常路径等价于 `节点.list.size`。
 * 仍向下递归是因为 Wiki 编辑侧随时可能给频道加子频道：届时条目挂在子节点上，只数一层会静默漏数
 * （单测同时覆盖有/无嵌套两种形态）。同 id 的子节点继续累加，不同 id 的分支也要下钻，
 * 因为真实分类频道可能整体比实测更深一层。
 */
fun wikiCardTotals(channels: List<WikiChannelNode>?): WikiCardTotals = WikiCardTotals(
    avatarTotal = countChannelEntries(channels, WIKI_CHANNEL_CHAR),
    actionTotal = countChannelEntries(channels, WIKI_CHANNEL_ACTION),
)

/**
 * gcg/basicInfo 响应 → 官方真实总手牌数（分母首选来源）。
 * 复用 [WikiCardTotals] 形态（而非再造一个类型）：两级来源在 resolveTotal 里是同位替换，形状一致才可比。
 * 任一字段缺失/为 0 一律折算 0 ⇒ 该级自动不参与优先级链（见 computeGcgSummary 的三级降级），
 * 因此接口整体取不到（null）与字段半缺（只回 avatar）都能自然降级，调用方无需分支。
 */
fun officialCardTotals(basicInfo: GcgBasicInfoData?): WikiCardTotals = WikiCardTotals(
    avatarTotal = basicInfo?.avatarCardNumTotal?.takeIf { it > 0 } ?: 0,
    actionTotal = basicInfo?.actionCardNumTotal?.takeIf { it > 0 } ?: 0,
)

private fun countChannelEntries(nodes: List<WikiChannelNode>?, channelId: Int): Int {
    if (nodes.isNullOrEmpty()) return 0
    var total = 0
    for (node in nodes) {
        val own = if (node.id == channelId) node.list.orEmpty().size else 0
        total += own + countChannelEntries(node.children, channelId)
    }
    return total
}
