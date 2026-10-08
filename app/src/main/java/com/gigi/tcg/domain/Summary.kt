// 个人卡牌统计的派生计算：逐字对照 Web 版 utils/summary.ts（按原 game.lua 代码行为精确实现）。
// 注意（已知口径差异，照原版保留）：说明文案称"出场率=使用次数÷游玩场次"，
// 但代码实际为"÷全部角色牌使用次数之和"，本实现以代码行为为准。

package com.gigi.tcg.domain

import com.gigi.tcg.R
import com.gigi.tcg.i18n.LocaleStrings
import kotlin.math.floor

/** 卡牌条目（对应 types/api.ts GcgCard，全字段可空） */
data class GcgCard(
    val name: String? = null,
    val cardType: String? = null,
    val useCount: Int? = null,
    val proficiency: Int? = null,
    /** 卡面规则文本（识别「秘传」牌用，见 [isMaxOneActionCard]）；可为 null（缺字段时不影响计数） */
    val desc: String? = null,
)

/** 个人统计（对应 types/api.ts GcgStats，全字段可空） */
data class GcgStats(
    val nickname: String? = null,
    val level: Int? = null,
    val avatarCardNumGained: Int? = null,
    val actionCardNumGained: Int? = null,
    /**
     * 服务端"图鉴总数"字段（导出图胶囊的分母）。官方 cardList 实测从不返回这两项 ⇒ 恒 null，
     * 真实分母首选 gcg/basicInfo（见 [officialCardTotals]），本字段只是第三级兜底。
     */
    val avatarCardNumTotal: Int? = null,
    val actionCardNumTotal: Int? = null,
)

const val CARD_TYPE_CHARACTER = "CardTypeCharacter"
const val CARD_TYPE_MODIFY = "CardTypeModify"
const val CARD_TYPE_ASSIST = "CardTypeAssist"
const val CARD_TYPE_EVENT = "CardTypeEvent"

data class PreparedCardLists(
    /** 角色牌（CardTypeCharacter），按 useCount 降序 */
    val charCards: List<GcgCard>,
    /** 行动牌（非 CardTypeCharacter 三类合计），按 useCount 降序 */
    val actionCards: List<GcgCard>,
    /** 全部角色牌使用次数之和（出场率分母） */
    val charTotalUse: Int,
    /** 全部角色牌熟练度之和（获胜对局数分子） */
    val charTotalProficiency: Int,
)

fun prepareCardLists(cardList: List<GcgCard>): PreparedCardLists {
    val charCards = mutableListOf<GcgCard>()
    val actionCards = mutableListOf<GcgCard>()
    var charTotalUse = 0
    var charTotalProficiency = 0

    for (card in cardList) {
        if (card.cardType == CARD_TYPE_CHARACTER) {
            charCards.add(card)
            charTotalUse += card.useCount ?: 0
            charTotalProficiency += card.proficiency ?: 0
        } else {
            actionCards.add(card)
        }
    }
    // 对照 JS Array#sort：稳定排序，等价于原版按 use_count 降序
    charCards.sortByDescending { it.useCount ?: 0 }
    actionCards.sortByDescending { it.useCount ?: 0 }
    return PreparedCardLists(charCards, actionCards, charTotalUse, charTotalProficiency)
}

data class GcgSummary(
    val nickname: String,
    val level: Int,
    val avatarCardNum: Int,
    val actionCardNum: Int,
    /** 角色牌总数（首选 basicInfo 官方值；缺失时按图鉴→服务端→已得数降级，绝不给 0 分母） */
    val avatarCardTotal: Int,
    /** 行动牌总数（同上口径） */
    val actionCardTotal: Int,
    /** 总对局数 = floor(Σ角色牌 use_count / 3) */
    val totalGames: Long,
    /** 获胜对局数 = floor(Σ角色牌 proficiency / 3) */
    val winGames: Long,
    /** 总胜率（已格式化，分母 0 → "0%"） */
    val winRate: String,
    /** 未格式化的总胜率数值（0–100，用于排序/强调样式） */
    val winRateValue: Double,
    /** 打出行动牌数 = Σ行动牌 use_count */
    val actionTotalUse: Int,
    val modifyUse: Int,
    val modifyPercent: String,
    val assistUse: Int,
    val assistPercent: String,
    val eventUse: Int,
    val eventPercent: String,
)

fun computeGcgSummary(
    stats: GcgStats?,
    lists: PreparedCardLists,
    /** 图鉴接口数出来的总手牌数（次级来源）；默认空 ⇒ 旧调用点与单测不必改 */
    wikiTotals: WikiCardTotals = WikiCardTotals(),
    /** gcg/basicInfo 的官方真实总手牌数（首选来源，见 [officialCardTotals]）；默认空 ⇒ 走原三级链 */
    officialTotals: WikiCardTotals = WikiCardTotals(),
): GcgSummary {
    fun actionUseByType(type: String): Int =
        lists.actionCards
            .filter { it.cardType == type }
            .sumOf { it.useCount ?: 0 }

    val totalGames = floor(lists.charTotalUse.toDouble() / 3).toLong()
    val winGames = floor(lists.charTotalProficiency.toDouble() / 3).toLong()
    val winRateValue = calcPercent(winGames.toDouble(), totalGames.toDouble())

    val actionTotalUse = lists.actionCards.sumOf { it.useCount ?: 0 }
    val modifyUse = actionUseByType(CARD_TYPE_MODIFY)
    val assistUse = actionUseByType(CARD_TYPE_ASSIST)
    val eventUse = actionUseByType(CARD_TYPE_EVENT)

    // 分母优先级：basicInfo 官方总数 → **cardList 份数口径计算** → 图鉴接口总数 → 服务端 total 字段 → 已得数。
    //
    // 🔴 为什么新增「cardList 份数口径」这一级：basicInfo（官方唯一权威源，实测 角色147/行动941）
    // 被设备指纹风控定向门禁（2026-10 实测：仅受信的 x-rpc-device_fp 可放行，公开 getFp 新指纹 → 5003，
    // 信任账号+设备绑定、第三方无法复现）。但 cardList 是**完整可获得卡池**（角色牌数=147 与官方吻合），
    // 且官方分母口径 = Σ(每张最大持有份数)：角色牌每张限 1 ⇒ 总数=角色牌种类数；
    // 行动牌多数限 2、少数限 1 ⇒ 总数=2×行动牌种类数−限1份牌数。
    // 用 cardList 现算的 147/941 与官方逐值吻合（0 误伤 0 漏检），故作为 basicInfo 之后、图鉴之前的口径。
    //
    // 🔴 为什么图鉴口径不可靠：公开图鉴接口**会去重手牌**（同名/同卡面的多份手牌只算一条），
    // 数出来的行动牌 568 远小于官方真实的 941 ⇒ 拿它当分母会把「941 张里收集了 500 张」
    // 显示成 568/568 全收集。图鉴数只保留为更次级兜底。最后一级是历史兜底：宁可显示"全收集"，也不出 x/0。
    fun resolveTotal(officialTotal: Int, computedTotal: Int, wikiTotal: Int, serverTotal: Int?, gained: Int?): Int =
        officialTotal.takeIf { it > 0 }
            ?: computedTotal.takeIf { it > 0 }
            ?: wikiTotal.takeIf { it > 0 }
            ?: serverTotal?.takeIf { it > 0 }
            ?: (gained ?: 0)

    return GcgSummary(
        nickname = stats?.nickname ?: LocaleStrings.getOrDefault(R.string.common_unknown, "未知"),
        level = stats?.level ?: 0,
        avatarCardNum = stats?.avatarCardNumGained ?: 0,
        actionCardNum = stats?.actionCardNumGained ?: 0,
        avatarCardTotal = resolveTotal(
            officialTotals.avatarTotal, lists.charCards.size, wikiTotals.avatarTotal, stats?.avatarCardNumTotal, stats?.avatarCardNumGained,
        ),
        actionCardTotal = resolveTotal(
            officialTotals.actionTotal, actionCardTotal(lists.actionCards), wikiTotals.actionTotal, stats?.actionCardNumTotal, stats?.actionCardNumGained,
        ),
        totalGames = totalGames,
        winGames = winGames,
        winRate = formatPercent(winRateValue),
        winRateValue = winRateValue,
        actionTotalUse = actionTotalUse,
        modifyUse = modifyUse,
        modifyPercent = formatPercent(calcPercent(modifyUse.toDouble(), actionTotalUse.toDouble())),
        assistUse = assistUse,
        assistPercent = formatPercent(calcPercent(assistUse.toDouble(), actionTotalUse.toDouble())),
        eventUse = eventUse,
        eventPercent = formatPercent(calcPercent(eventUse.toDouble(), actionTotalUse.toDouble())),
    )
}

/**
 * 行动牌总手牌数（份数口径，与官方 basicInfo `action_card_num_total` 一致，实测 941）。
 *
 * 官方分母 = Σ(每张行动牌的最大持有份数)。行动牌绝大多数可持有 2 份，少数限 1 份（见 [isMaxOneActionCard]）。
 * ⇒ 总数 = 2×行动牌种类数 − 限1份牌数。本函数基于 cardList 现算，与官方逐值吻合（实测 2×482−23=941）。
 * 返回 0 表示「算不出」（行动牌列表为空），让分母优先级链继续降级到图鉴。
 */
internal fun actionCardTotal(actionCards: List<GcgCard>): Int {
    if (actionCards.isEmpty()) return 0
    val maxOne = actionCards.count(::isMaxOneActionCard)
    return 2 * actionCards.size - maxOne
}

/**
 * 是否「限 1 份」的行动牌。两类自描述规则关键词（均在卡面规则文本/牌名里，非具体牌名硬匹配）：
 * - 「秘传」牌：其规则文本必含「秘传」（官方行文「整局游戏只能打出一张『秘传』卡牌」）；
 * - 「元素幻变」系列：牌名以「元素幻变：」前缀标识。
 * 实测覆盖全部 23 张限1份牌（14 秘传 + 9 元素幻变），对 454 张限2份牌 0 误伤。
 * 局限：若未来出现全新限1机制（既非秘传也非幻变）会漏判，届时官方分母变化、按新关键词补充即可。
 */
private fun isMaxOneActionCard(card: GcgCard): Boolean =
    card.desc.orEmpty().contains(SECRET_CARD_KEYWORD) ||
        card.name.orEmpty().startsWith(ELEMENTAL_ILLUSION_PREFIX)

/** 「秘传」牌规则文本关键词（限 1 份） */
private const val SECRET_CARD_KEYWORD = "秘传"

/** 「元素幻变」系列牌名前缀（限 1 份） */
private const val ELEMENTAL_ILLUSION_PREFIX = "元素幻变："
