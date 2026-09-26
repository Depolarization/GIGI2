// 「最近对局」导出长图的内容组装（V24，用户真机反馈图 4 + 需求 6）。
// 本文件只做字符串与算术，🔴 不得引用 android.graphics —— 渲染复用
// ui/export/TableImageRenderer.kt（与卡牌使用情况导出同一套渲染器），
// 分界线是为了让列结构/退化口径能在纯 JVM 单测里覆盖（工程无 Robolectric）。

package com.gigi.tcg.ui.screens.home

import androidx.annotation.StringRes
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GameRecord
import com.gigi.tcg.domain.UNKNOWN_OPPONENT_UID
import com.gigi.tcg.domain.extractOpponentUid
import com.gigi.tcg.domain.formatRecordTime
import com.gigi.tcg.domain.formatScoreChange
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.ui.export.TableColumn
import com.gigi.tcg.ui.export.TableSpec

/** 空值占位：积分缺失/对手 UID 未知时用，纯标点不需要翻译 */
private const val EMPTY_CELL = "-"

/**
 * 文案通道：本文件是**纯函数层**（JVM 单测直接调 buildRecordsTableSpec），拿不到 Compose 的
 * stringResource。故走 [LocaleStrings.getOrDefault]：有 resolver 时按当前语言取资源，
 * 没有（纯 JVM 测试）时回落到这里的中文默认值。
 * 🔴 默认值必须与 values/strings.xml 里的同 id 文案逐字一致。
 */
private fun exportText(@StringRes id: Int, default: String): String =
    LocaleStrings.getOrDefault(id, default)

/**
 * 最近对局导出表的列结构（对齐列表页的字段顺序：对手 / UID / 时间 / 天梯 / 巅峰 / 胜负）。
 * 权重按「对手名最长 + UID 9 位 + 时间 11 字符 + 积分带变化量」估：总权重 12.4。
 */
internal fun buildRecordsTableSpec(
    records: List<GameRecord>,
    uid: String,
    nickname: String?,
): TableSpec {
    val columns = listOf(
        TableColumn(exportText(R.string.export_col_opponent, "对手"), 3.2f, alignEnd = false),
        TableColumn(exportText(R.string.export_col_uid, "UID"), 2.4f, alignEnd = false),
        TableColumn(exportText(R.string.export_col_time, "时间"), 2.4f, alignEnd = false),
        TableColumn(exportText(R.string.export_col_ladder, "天梯"), 1.7f, alignEnd = true),
        TableColumn(exportText(R.string.export_col_peak, "巅峰"), 1.5f, alignEnd = true),
        TableColumn(exportText(R.string.export_col_result, "胜负"), 1.2f, alignEnd = true),
    )
    val rows = records.map { record ->
        val opponentUid = extractOpponentUid(record.transNo, uid)
        listOf(
            record.nickname ?: exportText(R.string.common_unknown, "未知"),
            // 对手 UID 解析失败（trans_no 缺失/格式不符）时不印 "unknown" 字样，用占位符
            if (opponentUid == UNKNOWN_OPPONENT_UID) EMPTY_CELL else opponentUid,
            formatRecordTime(record.timestamp),
            scoreCell(record.ladderScore?.score, record.ladderScore?.scoreChange),
            scoreCell(record.peakScore?.score, record.peakScore?.scoreChange),
            resultName(record.result),
        )
    }
    return TableSpec(
        title = exportText(R.string.export_records_title, "最近对局"),
        subtitle = if (uid.isBlank()) nickname else "${nickname.orEmpty()} - $uid",
        // 服务端最多返回 10 条，「共进行 N 场」这类徽章对局部列表没有意义，故不带徽章行
        badges = emptyList(),
        columns = columns,
        rows = rows,
    )
}

/**
 * 积分单元格：`2310 (10)` —— 与列表页 `"$prefix $score $change"` 同口径（变化量沿用
 * formatScoreChange 的 `(n)` 形态）。整对都为 0/缺失时视为"该模式无积分"，给占位符，
 * 对齐列表页 home_peak_placeholder（"巅峰 -"）的语义。
 */
private fun scoreCell(score: Int?, change: Int?): String {
    val s = score ?: 0
    val c = change ?: 0
    if (s == 0 && c == 0) return EMPTY_CELL
    return "$s ${formatScoreChange(c)}"
}

/** 胜负：逐字对齐列表页 home_result_win / lose / none */
private fun resultName(result: String?): String = when (result) {
    "Win" -> exportText(R.string.home_result_win, "胜")
    "Lose" -> exportText(R.string.home_result_lose, "负")
    else -> exportText(R.string.home_result_none, "空")
}
