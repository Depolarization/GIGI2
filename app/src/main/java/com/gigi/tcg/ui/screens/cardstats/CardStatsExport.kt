// 卡牌使用详情长图的内容组装（DESIGN-V8 §3.1/§3.2）。
// 本文件只做字符串与算术，🔴 不得引用 android.graphics —— 渲染在 ui/export/TableImageRenderer.kt，
// 分界线是为了让列结构/百分比口径能在纯 JVM 单测里覆盖（工程无 Robolectric）。

package com.gigi.tcg.ui.screens.cardstats

import com.gigi.tcg.domain.CARD_TYPE_ASSIST
import com.gigi.tcg.domain.CARD_TYPE_EVENT
import com.gigi.tcg.domain.CARD_TYPE_MODIFY
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgSummary
import com.gigi.tcg.ui.export.TableColumn
import com.gigi.tcg.ui.export.TableSpec
import java.util.Locale

/** 长图固定宽度（px，与屏幕密度无关，保证分享出去的图规格统一） */
internal const val EXPORT_IMAGE_WIDTH_PX = 1600

/** 行动牌行数超过它就分双栏（对齐参考图） */
internal const val EXPORT_TWO_COLUMN_THRESHOLD = 60

/**
 * 角色牌恒单栏。147 行上限 ⇒ 单栏高约 9692px / ARGB 约 59MB，可控；
 * 若套 60 行阈值折成双栏，每栏 800px 装 6 列在约束下无解
 * （表头 8% 余量 + 名称 ≥8 中文字 + 6 位数字共需约 671px，而可用 slack 只有 560px）。
 */
internal const val EXPORT_CHAR_TWO_COLUMN_THRESHOLD = Int.MAX_VALUE

/** 按表选择双栏阈值：角色牌恒单栏，行动牌超 60 行双栏 */
internal fun exportTwoColumnThreshold(charTable: Boolean): Int =
    if (charTable) EXPORT_CHAR_TWO_COLUMN_THRESHOLD else EXPORT_TWO_COLUMN_THRESHOLD

private const val UNKNOWN_CARD_NAME = "未知"

internal fun buildCharTableSpec(summary: GcgSummary, uid: String, cards: List<GcgCard>): TableSpec {
    // 出场率分母 = Σ角色牌 useCount（与 StatsUiState.charTotalUse / Summary.charTotalUse 同口径，
    // 而非"游玩场次数"——说明文案与原版代码的差异照原版保留）
    val totalUse = cards.sumOf { it.useCount ?: 0 }
    val columns = listOf(
        TableColumn("#", 1.0f, alignEnd = false),
        TableColumn("名称", 5.0f, alignEnd = false),
        TableColumn("出场数", 1.3f, alignEnd = true),
        TableColumn("出场率%", 1.7f, alignEnd = true),
        TableColumn("胜率%", 1.6f, alignEnd = true),
        TableColumn("胜局数", 1.3f, alignEnd = true),
    )
    val rows = cards.mapIndexed { index, card ->
        val useCount = card.useCount ?: 0
        val wins = card.proficiency ?: 0
        listOf(
            (index + 1).toString(),
            card.name ?: UNKNOWN_CARD_NAME,
            useCount.toString(),
            formatExportPercent(useCount, totalUse),
            formatExportPercent(wins, useCount),
            wins.toString(),
        )
    }
    return TableSpec(
        title = "角色牌数据",
        subtitle = buildSubtitle(summary, uid),
        badges = buildBadges(summary),
        columns = columns,
        rows = rows,
    )
}

internal fun buildActionTableSpec(summary: GcgSummary, uid: String, cards: List<GcgCard>): TableSpec {
    // 使用率分母 = GcgSummary.actionTotalUse（= Σ行动牌 use_count）
    val totalUse = summary.actionTotalUse
    // 权重按 .task/tmp/verify_v8h.py 的列宽约束验算（表头 8% 余量、名称 ≥8 中文字、
    // 「装备牌」3 字、"100.000" 7 字符），派单初值 1.0/1.6/3.9/1.9/1.8 会让
    // 「使用次数」表头余量只剩 6.3%（<8%），故微调为下面这组（总权重 10.0）。
    val columns = listOf(
        TableColumn("#", 0.9f, alignEnd = false),
        TableColumn("类别", 1.5f, alignEnd = false),
        TableColumn("名称", 3.8f, alignEnd = false),
        TableColumn("使用次数", 1.9f, alignEnd = true),
        TableColumn("使用率%", 1.9f, alignEnd = true),
    )
    val rows = cards.mapIndexed { index, card ->
        val useCount = card.useCount ?: 0
        listOf(
            (index + 1).toString(),
            actionTypeName(card.cardType),
            card.name ?: UNKNOWN_CARD_NAME,
            useCount.toString(),
            formatExportPercent(useCount, totalUse),
        )
    }
    return TableSpec(
        title = "行动牌数据",
        subtitle = buildSubtitle(summary, uid),
        badges = buildBadges(summary),
        columns = columns,
        rows = rows,
    )
}

/** 未知类型原样回显（服务端新增类型时导出不能崩） */
private fun actionTypeName(cardType: String?): String = when (cardType) {
    CARD_TYPE_MODIFY -> "装备牌"
    CARD_TYPE_ASSIST -> "支援牌"
    CARD_TYPE_EVENT -> "事件牌"
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
    "角色牌 ${summary.avatarCardNum}",
    "行动牌 ${summary.actionCardNum}",
    "共进行 ${summary.totalGames} 场游戏",
    "胜率 ${summary.winRate}",
    "数据来源：GIGI",
)
