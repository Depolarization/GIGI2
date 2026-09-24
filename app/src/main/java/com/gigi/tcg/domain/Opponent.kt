// 对手 UID 解析（新版逻辑，与胜负无关）：逐字对照 Web 版 utils/opponent.ts（原 main.lua 语义）。
// trans_no 形如 "{UID_A}_{UID_B}"：不等于当前用户 UID 的那一段即对手 UID。
// 提取失败 → "unknown"。禁止采用旧版"胜取 second / 负取 first"规则。

package com.gigi.tcg.domain

const val UNKNOWN_OPPONENT_UID = "unknown"

private val TRANS_NO_PATTERN = Regex("([^_]+)_([^_]+)")

fun extractOpponentUid(transNo: String?, curUid: String): String {
    val match = TRANS_NO_PATTERN.find(transNo ?: return UNKNOWN_OPPONENT_UID)
        ?: return UNKNOWN_OPPONENT_UID
    val first = match.groupValues[1]
    val second = match.groupValues[2]
    // 原版按字符串比较（first == cur_uid），uid 以字符串形态参与比较
    return if (first == curUid) second else first
}
