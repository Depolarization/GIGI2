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

    // 分母优先级：basicInfo 官方总数 → 图鉴接口总数 → 服务端 total 字段（实测恒缺失）→ 已得数。
    //
    // 🔴 为什么图鉴口径不可靠：公开图鉴接口**会去重手牌**（同名/同卡面的多份手牌只算一条），
    // 数出来的行动牌 568 远小于官方真实的 941 ⇒ 拿它当分母会把「941 张里收集了 500 张」
    // 显示成 568/568 全收集。真实总数唯一来源是 gcg/basicInfo 的 *_card_num_total（与官方口径一致）。
    // 图鉴数只保留为次级兜底：basicInfo 需登录 Cookie，未登录/字段缺失时至少还有一个接近真实的分母。
    // 最后一级是历史兜底：宁可显示"全收集"，也不能让胶囊出现 x/0。
    fun resolveTotal(officialTotal: Int, wikiTotal: Int, serverTotal: Int?, gained: Int?): Int =
        officialTotal.takeIf { it > 0 }
            ?: wikiTotal.takeIf { it > 0 }
            ?: serverTotal?.takeIf { it > 0 }
            ?: (gained ?: 0)

    return GcgSummary(
        nickname = stats?.nickname ?: LocaleStrings.getOrDefault(R.string.common_unknown, "未知"),
        level = stats?.level ?: 0,
        avatarCardNum = stats?.avatarCardNumGained ?: 0,
        actionCardNum = stats?.actionCardNumGained ?: 0,
        avatarCardTotal = resolveTotal(
            officialTotals.avatarTotal, wikiTotals.avatarTotal, stats?.avatarCardNumTotal, stats?.avatarCardNumGained,
        ),
        actionCardTotal = resolveTotal(
            officialTotals.actionTotal, wikiTotals.actionTotal, stats?.actionCardNumTotal, stats?.actionCardNumGained,
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
