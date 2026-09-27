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
    /** 图鉴总数（导出图胶囊的分母）；服务端/映射层缺失时为 null ⇒ 兜底用已得数 */
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
    /** 角色牌图鉴总数（接口缺失或 ≤0 时 == avatarCardNum，绝不给 0 分母） */
    val avatarCardTotal: Int,
    /** 行动牌图鉴总数（接口缺失或 ≤0 时 == actionCardNum） */
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

fun computeGcgSummary(stats: GcgStats?, lists: PreparedCardLists): GcgSummary {
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

    return GcgSummary(
        nickname = stats?.nickname ?: LocaleStrings.getOrDefault(R.string.common_unknown, "未知"),
        level = stats?.level ?: 0,
        avatarCardNum = stats?.avatarCardNumGained ?: 0,
        actionCardNum = stats?.actionCardNumGained ?: 0,
        // 总数缺失/为 0 时兜底成已得数 ⇒ 胶囊显示 147/147，绝不出现 147/0
        avatarCardTotal = stats?.avatarCardNumTotal?.takeIf { it > 0 } ?: (stats?.avatarCardNumGained ?: 0),
        actionCardTotal = stats?.actionCardNumTotal?.takeIf { it > 0 } ?: (stats?.actionCardNumGained ?: 0),
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
