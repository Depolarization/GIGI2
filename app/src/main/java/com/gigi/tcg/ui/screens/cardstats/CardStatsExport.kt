// 卡牌使用详情长图的内容组装（DESIGN-V8 §3.1/§3.2）。
// 本文件只做字符串与算术，🔴 不得引用 android.graphics —— 渲染在 ui/export/TableImageRenderer.kt，
// 分界线是为了让列结构/百分比口径能在纯 JVM 单测里覆盖（工程无 Robolectric）。

package com.gigi.tcg.ui.screens.cardstats

import androidx.annotation.StringRes
import com.gigi.tcg.R
import com.gigi.tcg.domain.CARD_TYPE_ASSIST
import com.gigi.tcg.domain.CARD_TYPE_EVENT
import com.gigi.tcg.domain.CARD_TYPE_MODIFY
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgSummary
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.ui.dialogs.cardcover.exportDateText
import com.gigi.tcg.ui.export.CellAlign
import com.gigi.tcg.ui.export.TableColumn
import com.gigi.tcg.ui.export.TableSpec
import java.util.Locale

/**
 * 长图固定宽度（px，与屏幕密度无关，保证分享出去的图规格统一）。
 * 按表区分（照抄参考图的表宽）：角色牌单栏 6 列 822px；行动牌 1444px（>60 行双栏，每栏槽位 722）。
 * 表格本身还要减去 TableImageRenderer.PAGE_MARGIN_PX×2 的页边距：770 / 670。
 * 行动牌取 1444 是参考图 B 的原宽：1200 时「使用率%」表头被省略号截断、9 字卡名折成 8+1（单字残行看起来像缩进）。
 */
internal const val EXPORT_CHAR_IMAGE_WIDTH_PX = 822
internal const val EXPORT_ACTION_IMAGE_WIDTH_PX = 1444

/** 行动牌行数超过它就分双栏（对齐参考图） */
internal const val EXPORT_TWO_COLUMN_THRESHOLD = 60

/**
 * 角色牌恒单栏。147 行上限 ⇒ 单栏高 7604px / ARGB 约 23.8MB，可控；
 * 若套 60 行阈值折成双栏，每栏槽位仅 411px（表宽 359px）装 6 列在约束下无解
 * （5 列 ×40px 保底就吃掉 200px，名称列只剩 159px / avail 135，单行不足 7 个汉字）。
 */
internal const val EXPORT_CHAR_TWO_COLUMN_THRESHOLD = Int.MAX_VALUE

/** 按表选择画布宽：角色牌 822（单栏 6 列），行动牌 1444（双栏每栏 722） */
internal fun exportImageWidth(charTable: Boolean): Int =
    if (charTable) EXPORT_CHAR_IMAGE_WIDTH_PX else EXPORT_ACTION_IMAGE_WIDTH_PX

/** 按表选择双栏阈值：角色牌恒单栏，行动牌超 60 行双栏 */
internal fun exportTwoColumnThreshold(charTable: Boolean): Int =
    if (charTable) EXPORT_CHAR_TWO_COLUMN_THRESHOLD else EXPORT_TWO_COLUMN_THRESHOLD

private const val UNKNOWN_CARD_NAME = "未知"

/**
 * 文案通道：本文件是**纯函数层**（JVM 单测直接调 buildCharTableSpec，见 CardStatsExportTest），
 * 拿不到 Compose 的 stringResource，也不能依赖已 attach 的 resolver。
 * 故走 [LocaleStrings.getOrDefault]：有 resolver 时按当前语言取资源，
 * 没有（纯 JVM 测试）时回落到这里的中文默认值 ⇒ 测试与旧调用点都不必改。
 * 🔴 默认值必须与 values/strings.xml 里的同 id 文案逐字一致，否则中文设备上的导出图会跟着变。
 */
private fun exportText(@StringRes id: Int, default: String): String =
    LocaleStrings.getOrDefault(id, default)

private fun exportText(@StringRes id: Int, default: String, vararg args: Any): String =
    LocaleStrings.getOrDefault(id, default, *args)

internal fun buildCharTableSpec(summary: GcgSummary, uid: String, cards: List<GcgCard>): TableSpec {
    // 出场率分母 = Σ角色牌 useCount（与 StatsUiState.charTotalUse / Summary.charTotalUse 同口径，
    // 而非"游玩场次数"——说明文案与原版代码的差异照原版保留）
    val totalUse = cards.sumOf { it.useCount ?: 0 }
    // 使用次数为 0（含 null）的牌不进表（用户要求减少绘制压力）；被滤掉的牌对 totalUse 贡献为 0，
    // 分母口径不受影响。序号按过滤后的下标重新连续，排序沿用服务端返回顺序。
    val visible = cards.filter { (it.useCount ?: 0) > 0 }
    // 权重 = 目标列宽 − MIN_COLUMN_WIDTH_PX(40)，且 Σ目标列宽精确 == 表宽 770 ⇒ slack = 770 − 6×40 = 530 = Σweight。
    // 实算列宽 [60, 200, 130, 130, 130, 120]（Node 按 IEEE-754 复刻 distributeColumnWidths 逐位核对）：
    // # avail 36 放得下 3 位序号（147 行）；名称 avail 176 ⇒ 单行 8 个汉字（角色名普遍 2~4 字，
    // 232px 时右侧大片留白，按用户反馈收窄）；四个数值列全部满足「表头 + 8% 余量」（见 CardStatsExportTest）。
    // # 列左对齐（表头也随列 START），其余列（含名称）居中 —— 照抄参考图。
    val columns = listOf(
        TableColumn("#", 20f, CellAlign.START),
        TableColumn(exportText(R.string.export_col_name, "名称"), 160f, CellAlign.CENTER),
        TableColumn(exportText(R.string.export_col_appear_count, "出场数"), 90f, CellAlign.CENTER),
        TableColumn(exportText(R.string.export_col_appear_rate, "出场率%"), 90f, CellAlign.CENTER),
        TableColumn(exportText(R.string.export_col_win_rate, "胜率%"), 90f, CellAlign.CENTER),
        TableColumn(exportText(R.string.export_col_win_count, "胜局数"), 80f, CellAlign.CENTER),
    )
    val rows = visible.mapIndexed { index, card ->
        val useCount = card.useCount ?: 0
        val wins = card.proficiency ?: 0
        listOf(
            (index + 1).toString(),
            card.name ?: exportText(R.string.common_unknown, UNKNOWN_CARD_NAME),
            useCount.toString(),
            formatExportPercent(useCount, totalUse),
            formatExportPercent(wins, useCount),
            wins.toString(),
        )
    }
    return TableSpec(
        title = exportText(R.string.export_char_title, "角色牌数据"),
        subtitle = buildSubtitle(summary, uid),
        badges = buildBadges(summary),
        columns = columns,
        rows = rows,
        exportDate = exportDateText(),
    )
}

internal fun buildActionTableSpec(summary: GcgSummary, uid: String, cards: List<GcgCard>): TableSpec {
    // 使用率分母 = GcgSummary.actionTotalUse（= Σ行动牌 use_count），与下面的过滤无关，不随行数变化
    val totalUse = summary.actionTotalUse
    // 权重 = 目标列宽 − MIN_COLUMN_WIDTH_PX(40)，且 Σ目标列宽精确 == 每栏表宽 670 ⇒ slack = 670 − 5×40 = 470 = Σweight。
    // 实算列宽 [60, 86, 284, 120, 120]（Node 按 IEEE-754 复刻 distributeColumnWidths 逐位核对）：
    // # avail 36 放得下 3 位序号（941 行）；类别 avail 62 放得下「装备牌」；
    // 名称 avail 260 ⇒ 单行 13 个汉字，「元素共鸣：交织之火」这类 9 字卡名单行完整（两行合计 26 字）；
    // 两个数值列 avail 96：表头 4 字 88 / 「使用率%」≈78 都放得下且留 8% 余量，数据放得下 6 位数字与「100.000」。
    // 1200px 栏宽（表宽 548）下「名称 ≥8 字」与 4 字表头 8% 余量不可兼得 ⇒ 画布回到参考图原宽 1444，二者兼得。
    val columns = listOf(
        TableColumn("#", 20f, CellAlign.START),
        TableColumn(exportText(R.string.export_col_type, "类别"), 46f, CellAlign.CENTER),
        TableColumn(exportText(R.string.export_col_name, "名称"), 244f, CellAlign.CENTER),
        TableColumn(exportText(R.string.export_col_use_count, "使用次数"), 80f, CellAlign.CENTER),
        TableColumn(exportText(R.string.export_col_use_rate, "使用率%"), 80f, CellAlign.CENTER),
    )
    // 使用次数为 0（含 null）的牌不进表（用户要求减少绘制压力）；序号按过滤后的下标重新连续，
    // 排序沿用服务端返回顺序。
    val visible = cards.filter { (it.useCount ?: 0) > 0 }
    val rows = visible.mapIndexed { index, card ->
        val useCount = card.useCount ?: 0
        listOf(
            (index + 1).toString(),
            actionTypeName(card.cardType),
            card.name ?: exportText(R.string.common_unknown, UNKNOWN_CARD_NAME),
            useCount.toString(),
            formatExportPercent(useCount, totalUse),
        )
    }
    return TableSpec(
        title = exportText(R.string.export_action_title, "行动牌数据"),
        subtitle = buildSubtitle(summary, uid),
        badges = buildBadges(summary),
        columns = columns,
        rows = rows,
        exportDate = exportDateText(),
    )
}

/** 未知类型原样回显（服务端新增类型时导出不能崩） */
private fun actionTypeName(cardType: String?): String = when (cardType) {
    CARD_TYPE_MODIFY -> exportText(R.string.card_type_modify, "装备牌")
    CARD_TYPE_ASSIST -> exportText(R.string.card_type_assist, "支援牌")
    CARD_TYPE_EVENT -> exportText(R.string.card_type_event, "事件牌")
    else -> cardType.orEmpty()
}

/** 3 位小数（对齐参考图 9.419 / 52.888 精度）；分母 <= 0 ⇒ "0.000"，不产生 NaN/Infinity */
private fun formatExportPercent(part: Int, total: Int): String =
    if (total > 0) String.format(Locale.US, "%.3f", part.toDouble() / total * 100) else "0.000"

/** uid 缺失时只显昵称，不留 " - " 悬空分隔符 */
private fun buildSubtitle(summary: GcgSummary, uid: String): String =
    if (uid.isBlank()) summary.nickname else "${summary.nickname} - $uid"

/** 图鉴总数（147 / 941）接口拿不到 ⇒ 按设计文档只显分子 */
private fun buildBadges(summary: GcgSummary): List<String> = listOf(
    exportText(R.string.export_badge_char, "角色牌 %1\$d", summary.avatarCardNum),
    exportText(R.string.export_badge_action, "行动牌 %1\$d", summary.actionCardNum),
    exportText(R.string.export_badge_total_games, "共进行 %1\$d 场游戏", summary.totalGames),
    exportText(R.string.export_badge_win_rate, "胜率 %1\$s", summary.winRate),
)
