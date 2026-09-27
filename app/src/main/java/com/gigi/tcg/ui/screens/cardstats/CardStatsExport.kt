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

/** 行动牌行数超过它就分双栏（对齐参考图） */
internal const val EXPORT_TWO_COLUMN_THRESHOLD = 60

/**
 * 角色牌恒单栏：参考图的角色牌表就是单栏（147 行），
 * 折成双栏只会让每栏再吃掉 15px 栏间距 + 4px 边框，长牌名换来的宽度反而被浪费。
 */
internal const val EXPORT_CHAR_TWO_COLUMN_THRESHOLD = Int.MAX_VALUE

/** 按表选择双栏阈值：角色牌恒单栏，行动牌超 60 行双栏 */
internal fun exportTwoColumnThreshold(charTable: Boolean): Int =
    if (charTable) EXPORT_CHAR_TWO_COLUMN_THRESHOLD else EXPORT_TWO_COLUMN_THRESHOLD

private const val UNKNOWN_CARD_NAME = "未知"

/**
 * 数值列宽度下限 = 表头宽 + 20（表头 4 个全角字 = 80px ⇒ 100；含半角 `%` 的表头 ≈91 ⇒ 111）。
 * 为什么还要下限：列宽按内容实测，但「出场数」这一列可能整列都是 `1`/`0.000` 之类的短值，
 * 实测只给到 60px 就把表头挤折行 —— 参考图靠 nowrap 表头天然撑开，我们必须在 minWidthPx 里补回来。
 * 20 = 左右内边距，使下限本身就把 padding 算进去。
 */
private const val HEADER_MIN_WIDTH_PX = 100
private const val HEADER_PERCENT_MIN_WIDTH_PX = 111

/**
 * 签名文字字号（= 渲染层 TableImageRenderer 的 private SIGNATURE_TEXT_SIZE_PX，25px）。
 * 那边是 private、本层是纯函数（拿不到 Paint），长度兜底只能在这里做 ⇒ 两个常量必须同步改。
 */
internal const val EXPORT_SIGNATURE_TEXT_SIZE_PX = 25f

/**
 * 签名可占的文字宽度上限（px）。
 * 为什么要限：渲染层画白框时 `boxWidth = 实测文字宽 + 2×内边距(20)`，**没有任何截断**——
 * 长签名会把白框一路撑出画布右缘（导出图被裁切）。
 * 口径取最窄的那张画布 = 单栏角色牌表且行数极少：列宽下限合计
 * 36(#) + 60(名称) + 100 + 111 + 111 + 100 = 518 = bandWidth，
 * 白框右缘最多到 `画布宽 - 页边距` ⇒ 可用宽 ≈ bandWidth + 4，再扣左右内边距 40 ≈ 480，
 * 保守取 440（省略号在 CJK 字体里接近一个全角字，余量留给它）。
 * 行动牌双栏画布更宽，440 只会更保守、不越界。
 */
internal const val EXPORT_SIGNATURE_MAX_WIDTH_PX = 440f

/** 宽度估算口径：CJK/全角按 1.0 字宽、其余按 0.55（与 TableLayoutTest 的假 measurer 同口径） */
private const val FULLWIDTH_EM_RATIO = 1.0
private const val HALFWIDTH_EM_RATIO = 0.55

private const val ELLIPSIS = "…"

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

/** 单码点宽度估算（em）：CJK/全角 1.0、其余 0.55 —— 与 TableLayoutTest 的假 measurer 同口径 */
private fun emWidthOfCodePoint(codePoint: Int): Double =
    if (codePoint >= 0x2E80) FULLWIDTH_EM_RATIO else HALFWIDTH_EM_RATIO

/**
 * 按 [emWidthOfCodePoint] 累加的粗口径文本宽（无 Paint，纯 JVM 可测）。
 * 🔴 必须按码点而非 char 累加：emoji 是代理对，按 char 算会把一个 emoji 记成两个字宽，
 * 与下面的截断循环口径不一致 ⇒ 白框宽度估算失真。
 */
private fun estimateExportTextWidth(text: String, textSizePx: Float): Float {
    var width = 0.0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        width += emWidthOfCodePoint(codePoint) * textSizePx
        index += Character.charCount(codePoint)
    }
    return width.toFloat()
}

/**
 * 签名框文案：真实签名（米游社 `introduce`）优先，取不到/空白回落「暂无签名」占位（绝不留空白框）。
 *
 * 🔴 [signature] 只可能是**米游社社区 UID** 查回来的 introduce（见 data.api.getUserFullInfoUrl：
 * 用游戏 UID 去打只会拿到占位「暂无签名」）；社区 UID 尚未可得时恒 null ⇒ 导出图照旧显示占位。
 *
 * 超长时按宽度预算截断加省略号（见 [EXPORT_SIGNATURE_MAX_WIDTH_PX]）；换行/连续空白压成单个空格：
 * 白框只有一行高，带换行的签名交给 canvas.drawText 只会画出豆腐块。
 */
internal fun exportSignatureText(signature: String?): String {
    val placeholder = exportText(R.string.export_signature_placeholder, "暂无签名")
    val text = signature?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    if (text.isEmpty()) return placeholder
    if (estimateExportTextWidth(text, EXPORT_SIGNATURE_TEXT_SIZE_PX) <= EXPORT_SIGNATURE_MAX_WIDTH_PX) return text

    // 逐码点累加（代理对不劈开），留一个「…」的位置：装不下的第一个码点起截断。
    // 省略号按 1 个全角字预留（CJK 字体里 … 接近全角，粗口径的 0.55 会低估 ⇒ 宁宽不溢出）
    val ellipsisWidth = EXPORT_SIGNATURE_TEXT_SIZE_PX
    var used = 0f
    var keep = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val codePointWidth = (emWidthOfCodePoint(codePoint) * EXPORT_SIGNATURE_TEXT_SIZE_PX).toFloat()
        if (used + codePointWidth + ellipsisWidth > EXPORT_SIGNATURE_MAX_WIDTH_PX) break
        used += codePointWidth
        index += Character.charCount(codePoint)
        keep = index
    }
    return if (keep == 0) placeholder else text.take(keep) + ELLIPSIS
}

internal fun buildCharTableSpec(
    summary: GcgSummary,
    uid: String,
    cards: List<GcgCard>,
    signature: String? = null,
): TableSpec {
    // 出场率分母 = Σ角色牌 useCount（与 StatsUiState.charTotalUse / Summary.charTotalUse 同口径，
    // 而非"游玩场次数"——说明文案与原版代码的差异照原版保留）
    val totalUse = cards.sumOf { it.useCount ?: 0 }
    // 使用次数为 0（含 null）的牌不进表（用户要求减少绘制压力）；被滤掉的牌对 totalUse 贡献为 0，
    // 分母口径不受影响。序号按过滤后的下标重新连续，排序沿用服务端返回顺序。
    val visible = cards.filter { (it.useCount ?: 0) > 0 }
    // 列宽由渲染层按「该列 max(表头, 各单元格) + 内边距」实测（nowrap ⇒ 长牌名如
    // 「阿佩普的绿洲守望者」永不折行）；# 列左对齐（表头也居中，照抄参考图），其余列居中。
    val columns = listOf(
        TableColumn("#", CellAlign.START),
        TableColumn(exportText(R.string.export_col_name, "名称"), CellAlign.CENTER),
        TableColumn(exportText(R.string.export_col_appear_count, "出场数"), CellAlign.CENTER, HEADER_MIN_WIDTH_PX),
        TableColumn(exportText(R.string.export_col_appear_rate, "出场率%"), CellAlign.CENTER, HEADER_PERCENT_MIN_WIDTH_PX),
        TableColumn(exportText(R.string.export_col_win_rate, "胜率%"), CellAlign.CENTER, HEADER_PERCENT_MIN_WIDTH_PX),
        TableColumn(exportText(R.string.export_col_win_count, "胜局数"), CellAlign.CENTER, HEADER_MIN_WIDTH_PX),
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
        nickname = buildNicknameLine(summary, uid),
        levelText = exportText(R.string.export_level, "牌手等级 %1\$d", summary.level),
        badges = buildBadges(summary),
        signature = exportSignatureText(signature),
        columns = columns,
        rows = rows,
        exportDateText = exportDateText(),
    )
}

internal fun buildActionTableSpec(
    summary: GcgSummary,
    uid: String,
    cards: List<GcgCard>,
    signature: String? = null,
): TableSpec {
    // 使用率分母 = GcgSummary.actionTotalUse（= Σ行动牌 use_count），与下面的过滤无关，不随行数变化
    val totalUse = summary.actionTotalUse
    // 「类别」列下限 90 = 3 个全角类别名（装备牌/支援牌/事件牌 60）+ 左右内边距 20 + 10 余量，
    // 其余数值列同角色牌口径（见 HEADER_MIN_WIDTH_PX 注释）。
    val columns = listOf(
        TableColumn("#", CellAlign.START),
        TableColumn(exportText(R.string.export_col_type, "类别"), CellAlign.CENTER, 90),
        TableColumn(exportText(R.string.export_col_name, "名称"), CellAlign.CENTER),
        TableColumn(exportText(R.string.export_col_use_count, "使用次数"), CellAlign.CENTER, HEADER_MIN_WIDTH_PX),
        TableColumn(exportText(R.string.export_col_use_rate, "使用率%"), CellAlign.CENTER, HEADER_PERCENT_MIN_WIDTH_PX),
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
        nickname = buildNicknameLine(summary, uid),
        levelText = exportText(R.string.export_level, "牌手等级 %1\$d", summary.level),
        badges = buildBadges(summary),
        signature = exportSignatureText(signature),
        columns = columns,
        rows = rows,
        exportDateText = exportDateText(),
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
private fun buildNicknameLine(summary: GcgSummary, uid: String): String =
    if (uid.isBlank()) summary.nickname else "${summary.nickname} - $uid"

/** 胶囊顺序照参考图：角色牌 已得/总数 → 行动牌 已得/总数 → 场次 → 胜率 */
private fun buildBadges(summary: GcgSummary): List<String> = listOf(
    exportText(R.string.export_badge_char_total, "角色牌 %1\$d/%2\$d", summary.avatarCardNum, summary.avatarCardTotal),
    exportText(R.string.export_badge_action_total, "行动牌 %1\$d/%2\$d", summary.actionCardNum, summary.actionCardTotal),
    exportText(R.string.export_badge_total_games, "共进行 %1\$d 场游戏", summary.totalGames),
    exportText(R.string.export_badge_win_rate, "胜率 %1\$s", summary.winRate),
)
