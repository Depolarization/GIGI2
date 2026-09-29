// 卡组导出图的内容组装 + 版式计算（V37-F 任务 E）。
// 本文件只做字符串与算术，🔴 不得引用 android.graphics —— 绘制在 ui/export/DeckImageRenderer.kt，
// 分界线照 CardStatsExport.kt:2 的工程约定，为的是 375→750 换算、牌图节奏、文件名清洗、反馈文案
// 能在纯 JVM 单测里锁死（工程无 Robolectric）。
//
// 版式数值取自官方网页版逆向实测（.task/v36-probe/official-deck-image/FEASIBILITY.md §4/§5）：
// 设计宽 375、出图 JPEG q=0.95、DPR=min(2,dpr) ⇒ 物理宽 750px；
// share-header 高 90 → share-body 底 #fcf7f0 + padding 15/15/20 → 牌组区 padding 10/11/16，
// 角色牌图 76、行动牌图 54，标签条 #f1eadb，描述 11px/#b28659。
// 官方那 375 是 CSS px，本项目按 dp 读同一套数（数值不变、只换单位），再 ×2 落到物理像素。
// 🔴 官方 .share-footer（logo 50 + 二维码 60 的米游社广告位）整段**不绘制**（用户明确要求排除）。

package com.gigi.tcg.ui.export

import androidx.annotation.StringRes
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgDeck
import com.gigi.tcg.i18n.LocaleStrings
import kotlin.math.ceil
import kotlin.math.roundToInt

// ---- 画布：官方设计宽 375 × 出图 DPR 2 ----

/** 官方设计宽（CSS px），本项目按同一数值的 dp 复刻 */
internal const val DECK_DESIGN_WIDTH = 375

/** 出图 DPR 上限，对齐官方 `pixelRatio: Math.min(2, devicePixelRatio)` */
internal const val DECK_EXPORT_SCALE = 2

/** 物理画布宽 = 750px（派单硬指标）。导出图是发出去的静态图，不随屏幕密度变 ⇒ 全部尺寸按物理 px 定死 */
internal const val DECK_CANVAS_WIDTH_PX = DECK_DESIGN_WIDTH * DECK_EXPORT_SCALE

/** 设计值（375 宽档）→ 物理像素 */
private fun designPx(designValue: Int): Int = designValue * DECK_EXPORT_SCALE

// ---- 分区尺寸（官方 commons.css 实测值） ----

internal val DECK_HEADER_HEIGHT_PX = designPx(90)
internal val DECK_BODY_PADDING_TOP_PX = designPx(15)
internal val DECK_BODY_PADDING_SIDE_PX = designPx(15)
internal val DECK_BODY_PADDING_BOTTOM_PX = designPx(20)
internal val DECK_GROUP_PADDING_TOP_PX = designPx(10)
internal val DECK_GROUP_PADDING_SIDE_PX = designPx(11)
internal val DECK_GROUP_PADDING_BOTTOM_PX = designPx(16)

/** 角色牌图宽 76、行动牌图宽 54（官方 `<card-group :role-size="76px" :action-size="54px">`） */
internal val DECK_ROLE_CARD_WIDTH_PX = designPx(76)
internal val DECK_ACTION_CARD_WIDTH_PX = designPx(54)

/** 卡面宽高比：接口图 URL 自带 `resize,m_fixed,h_275,w_160`，与页内 tile 同一口径 */
internal const val DECK_CARD_FACE_RATIO = 275f / 160f

internal fun deckCardHeightPx(widthPx: Int): Int = (widthPx * DECK_CARD_FACE_RATIO).roundToInt()

// ---- 文字与间距 ----

internal val DECK_TITLE_TEXT_PX = designPx(18)
internal val DECK_AUTHOR_TEXT_PX = designPx(12)
internal val DECK_DESC_TEXT_PX = designPx(11)
internal val DECK_PILL_TEXT_PX = designPx(11)
internal val DECK_GROUP_LABEL_TEXT_PX = designPx(13)

internal val DECK_TITLE_LINE_HEIGHT_PX = designPx(22)
internal val DECK_AUTHOR_LINE_HEIGHT_PX = designPx(15)
internal val DECK_DESC_LINE_HEIGHT_PX = designPx(14)
internal val DECK_PILL_HEIGHT_PX = designPx(20)
internal val DECK_GROUP_LABEL_HEIGHT_PX = designPx(16)

private val TITLE_TO_AUTHOR_GAP_PX = designPx(6)
private val AUTHOR_TO_PILL_GAP_PX = designPx(8)
private val PILL_TO_DESC_GAP_PX = designPx(9)
private val DESC_TO_GROUP_GAP_PX = designPx(10)
private val GROUP_LABEL_TO_CARDS_GAP_PX = designPx(5)
private val ROLE_TO_ACTION_GAP_PX = designPx(16)
private val CARD_COL_GAP_PX = designPx(8)
private val CARD_ROW_GAP_PX = designPx(8)
private val PILL_GAP_PX = designPx(6)
private val PILL_H_PADDING_PX = designPx(10)

// ---- 配色（官方实测值，导出不跟随应用主题 ⇒ 写死） ----

/** share-body 底色 */
internal const val DECK_COLOR_BODY_BG = 0xFFFCF7F0.toInt()

/** 标签条底/字（官方 label-container #f1eadb） */
internal const val DECK_COLOR_PILL_BG = 0xFFF1EADB.toInt()
internal const val DECK_COLOR_PILL_ALT_BG = 0xFFFAF3E5.toInt()
internal const val DECK_COLOR_PILL_TEXT = 0xFF8A6C4A.toInt()

/** 描述文字 #b28659（官方 desc） */
internal const val DECK_COLOR_DESC_TEXT = 0xFFB28659.toInt()
internal const val DECK_COLOR_TITLE_TEXT = 0xFF4A3F35.toInt()
internal const val DECK_COLOR_GROUP_LABEL = 0xFF7A5C3A.toInt()
internal const val DECK_COLOR_HEADER_BG = 0xFFF6EEDF.toInt()

/** 卡图缺失时的占位块：宁可画一块暖色底，也不能让一张 404 掀翻整次导出 */
internal const val DECK_COLOR_PLACEHOLDER_BG = 0xFFEAE0CD.toInt()
internal const val DECK_COLOR_PLACEHOLDER_BORDER = 0xFFD8C9AE.toInt()

/** 一张牌的面：渲染只用图 URL 与名字（都可能为 null / 空串） */
data class DeckCardFace(val name: String?, val imageUrl: String?)

/** 导出图的全部文字与牌面内容（纯数据，JVM 可直接断言） */
data class DeckImageSpec(
    /** 牌组名（标题行），由调用方在组合期算好 */
    val title: String,
    /** 作者行（分享人昵称/UID）；空串整块不占高 */
    val authorText: String,
    /** 标签条文字序列；全空时整块不占高 */
    val pills: List<String>,
    /** 描述行（张数摘要 + 导出日期） */
    val descText: String,
    val roleLabel: String,
    val actionLabel: String,
    /** 品牌行（官方 header 是装饰图，我们只有 export_logo，解不出时退化成这行字） */
    val brandText: String,
    val roleCards: List<DeckCardFace>,
    val actionCards: List<DeckCardFace>,
)

/** 一张牌在图上的落位 */
data class DeckCardSlot(val xPx: Int, val yPx: Int, val widthPx: Int, val heightPx: Int)

/** 标签条上的一枚胶囊（已折行） */
data class DeckPill(val text: String, val xPx: Int, val yPx: Int, val widthPx: Int, val heightPx: Int)

/**
 * 纯计算的版式结果（不引用 android.graphics）。绘制层只按这里的矩形照抄，不再自己算高度
 * ⇒ 空牌组 / 超长名 / 40 张牌三类边界都在同一处被单测锁住。
 * 🔴 纵向**只增长不分页**：实测一副牌组 3 + 22–25 张，行动牌 5 列最多 5 行、整图约 2000px 高
 * （远小于统计页那张 3 万像素长图，内存预算同一套闸）。拆成多张会破坏「一张图分享一副牌组」，
 * 所以张数再多也只是继续加行。
 */
data class DeckImageLayout(
    val widthPx: Int,
    val heightPx: Int,
    val headerHeightPx: Int,
    /** 文字块左缘（body padding 15） */
    val textLeftPx: Int,
    val textContentWidthPx: Int,
    /** 牌面区左缘（body padding + 牌组区 padding 11） */
    val cardsLeftPx: Int,
    val cardsContentWidthPx: Int,
    val titleTopPx: Int,
    /** null = 该块不画（作者缺失时整行连间距都不占） */
    val authorTopPx: Int?,
    val pills: List<DeckPill>,
    val descTopPx: Int,
    val roleLabelTopPx: Int?,
    val actionLabelTopPx: Int?,
    val roleSlots: List<DeckCardSlot>,
    val actionSlots: List<DeckCardSlot>,
    val roleCardWidthPx: Int,
    val roleCardHeightPx: Int,
    val actionCardWidthPx: Int,
    val actionCardHeightPx: Int,
)

/**
 * 纯函数：算版式。列数由可用宽与牌宽推出（@750px ⇒ 角色牌 3 列、行动牌 5 列），
 * 行数 = ceil(张数/列数)；张数 0 ⇒ 0 行且整块不占高，列数下限 1 ⇒ 永不除零。
 */
fun computeDeckImageLayout(spec: DeckImageSpec, measurer: TextMeasurer): DeckImageLayout {
    val textLeftPx = DECK_BODY_PADDING_SIDE_PX
    val textContentWidthPx = DECK_CANVAS_WIDTH_PX - 2 * DECK_BODY_PADDING_SIDE_PX
    val cardsLeftPx = textLeftPx + DECK_GROUP_PADDING_SIDE_PX
    val cardsContentWidthPx = DECK_CANVAS_WIDTH_PX - 2 * cardsLeftPx

    val roleCardWidthPx = DECK_ROLE_CARD_WIDTH_PX
    val roleCardHeightPx = deckCardHeightPx(roleCardWidthPx)
    val actionCardWidthPx = DECK_ACTION_CARD_WIDTH_PX
    val actionCardHeightPx = deckCardHeightPx(actionCardWidthPx)

    var y = DECK_HEADER_HEIGHT_PX + DECK_BODY_PADDING_TOP_PX

    val titleTopPx = y
    if (spec.title.isNotEmpty()) {
        y += DECK_TITLE_LINE_HEIGHT_PX
        if (spec.authorText.isNotEmpty()) y += TITLE_TO_AUTHOR_GAP_PX
    }
    val authorTopPx: Int? = if (spec.authorText.isNotEmpty()) {
        val top = y
        y += DECK_AUTHOR_LINE_HEIGHT_PX
        top
    } else {
        null
    }

    var pills = emptyList<DeckPill>()
    if (spec.pills.any { it.isNotBlank() }) {
        y += AUTHOR_TO_PILL_GAP_PX
        pills = layoutPills(spec.pills, measurer, textLeftPx, textContentWidthPx, y)
        y = pills.last().yPx + DECK_PILL_HEIGHT_PX + PILL_TO_DESC_GAP_PX
    }

    val descTopPx = y
    if (spec.descText.isNotEmpty()) y += DECK_DESC_LINE_HEIGHT_PX
    y += DESC_TO_GROUP_GAP_PX + DECK_GROUP_PADDING_TOP_PX

    val hasRole = spec.roleCards.isNotEmpty()
    val roleLabelTopPx = if (hasRole) y else null
    if (hasRole) y += DECK_GROUP_LABEL_HEIGHT_PX + GROUP_LABEL_TO_CARDS_GAP_PX
    val roleSlots = gridSlots(spec.roleCards.size, cardsLeftPx, cardsContentWidthPx, roleCardWidthPx, y)
    if (hasRole && roleSlots.isNotEmpty()) y = roleSlots.last().yPx + roleCardHeightPx + ROLE_TO_ACTION_GAP_PX

    val hasAction = spec.actionCards.isNotEmpty()
    val actionLabelTopPx = if (hasAction) y else null
    if (hasAction) y += DECK_GROUP_LABEL_HEIGHT_PX + GROUP_LABEL_TO_CARDS_GAP_PX
    val actionSlots = gridSlots(spec.actionCards.size, cardsLeftPx, cardsContentWidthPx, actionCardWidthPx, y)
    if (hasAction && actionSlots.isNotEmpty()) y = actionSlots.last().yPx + actionCardHeightPx

    val heightPx = y + DECK_GROUP_PADDING_BOTTOM_PX + DECK_BODY_PADDING_BOTTOM_PX

    return DeckImageLayout(
        widthPx = DECK_CANVAS_WIDTH_PX,
        heightPx = heightPx,
        headerHeightPx = DECK_HEADER_HEIGHT_PX,
        textLeftPx = textLeftPx,
        textContentWidthPx = textContentWidthPx,
        cardsLeftPx = cardsLeftPx,
        cardsContentWidthPx = cardsContentWidthPx,
        titleTopPx = titleTopPx,
        authorTopPx = authorTopPx,
        pills = pills,
        descTopPx = descTopPx,
        roleLabelTopPx = roleLabelTopPx,
        actionLabelTopPx = actionLabelTopPx,
        roleSlots = roleSlots,
        actionSlots = actionSlots,
        roleCardWidthPx = roleCardWidthPx,
        roleCardHeightPx = roleCardHeightPx,
        actionCardWidthPx = actionCardWidthPx,
        actionCardHeightPx = actionCardHeightPx,
    )
}

/**
 * 网格：列数 = 能整排放下的张数（(可用宽+列间距)/(牌宽+列间距) 向下取整，下限 1）。
 * 末行不铺满 ⇒ 左对齐（官方 `.card-group` 是左起 flex，不拉宽末行）。
 */
private fun gridSlots(count: Int, leftPx: Int, contentWidthPx: Int, cardWidthPx: Int, topPx: Int): List<DeckCardSlot> {
    if (count <= 0) return emptyList()
    val cardHeightPx = deckCardHeightPx(cardWidthPx)
    val columns = ((contentWidthPx + CARD_COL_GAP_PX) / (cardWidthPx + CARD_COL_GAP_PX)).coerceAtLeast(1)
    return List(count) { index ->
        DeckCardSlot(
            xPx = leftPx + (index % columns) * (cardWidthPx + CARD_COL_GAP_PX),
            yPx = topPx + (index / columns) * (cardHeightPx + CARD_ROW_GAP_PX),
            widthPx = cardWidthPx,
            heightPx = cardHeightPx,
        )
    }
}

/** 标签条折行：单枚胶囊超过一行宽就自己截断（[ellipsize]），绝不撑破画布 */
private fun layoutPills(labels: List<String>, measurer: TextMeasurer, leftPx: Int, contentWidthPx: Int, topPx: Int): List<DeckPill> {
    val usable = labels.filter { it.isNotBlank() }
    if (usable.isEmpty()) return emptyList()
    val out = ArrayList<DeckPill>(usable.size)
    var x = leftPx
    var rowTop = topPx
    for (label in usable) {
        val textWidth = ceil(measurer.measure(label, DECK_PILL_TEXT_PX.toFloat()))
        val preferred = (textWidth + 2 * PILL_H_PADDING_PX).toInt()
        val width = minOf(preferred, contentWidthPx)
        if (x > leftPx && x + width > leftPx + contentWidthPx) {
            x = leftPx
            rowTop += DECK_PILL_HEIGHT_PX + PILL_GAP_PX
        }
        val text = if (preferred > contentWidthPx) {
            val budget = (contentWidthPx - 2 * PILL_H_PADDING_PX).toFloat()
            ellipsize(label, budget) { measurer.measure(it, DECK_PILL_TEXT_PX.toFloat()) }
        } else {
            label
        }
        out += DeckPill(text, x, rowTop, width, DECK_PILL_HEIGHT_PX)
        x += width + PILL_GAP_PX
    }
    return out
}

/** 文案通道同 CardStatsExport.exportText：有 resolver 走资源，纯 JVM 单测回落这里的中文默认值 */
private fun exportText(@StringRes id: Int, default: String): String = LocaleStrings.getOrDefault(id, default)

private fun exportText(@StringRes id: Int, default: String, vararg args: Any): String =
    LocaleStrings.getOrDefault(id, default, *args)

/**
 * 由 [GcgDeck] 组装导出内容。张数摘要与页面文案同源（`my_deck_card_summary`）；
 * 标签条取角色牌名 —— 牌组最可辨识的标识，与 `deckDisplayName` 的二级兜底同一口径。
 *
 * @param deckTitle 调用方在组合期算好的牌组名（`deckDisplayName(deck)`，永不空白），本层不再兜底命名
 */
fun buildDeckImageSpec(
    deck: GcgDeck,
    deckTitle: String,
    nickname: String?,
    uid: String?,
    dateText: String,
): DeckImageSpec {
    val roleCards = deck.avatarCards.orEmpty().map { DeckCardFace(it.name, it.image) }
    val actionCards = deck.actionCards.orEmpty().map { DeckCardFace(it.name, it.image) }
    val author = nickname?.takeIf { it.isNotBlank() } ?: uid?.takeIf { it.isNotBlank() }
    val desc = listOf(
        exportText(R.string.my_deck_card_summary, "角色牌 %1\$d · 行动牌 %2\$d", roleCards.size, actionCards.size),
        dateText,
    ).filter { it.isNotBlank() }.joinToString(" · ")
    return DeckImageSpec(
        title = deckTitle,
        authorText = author?.let { exportText(R.string.export_deck_author, "分享人：%1\$s", it) }.orEmpty(),
        pills = roleCards.mapNotNull { it.name?.takeIf(String::isNotBlank) },
        descText = desc,
        roleLabel = exportText(R.string.my_deck_card_group_avatar, "角色牌"),
        actionLabel = exportText(R.string.my_deck_card_group_action, "行动牌"),
        brandText = exportText(R.string.export_footer, "七圣召唤"),
        roleCards = roleCards,
        actionCards = actionCards,
    )
}

private const val MAX_DECK_BASE_NAME_CHARS = 60
private val DECK_ILLEGAL_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

/**
 * 与 CardImageSaver 同一套非法字符表（那份常量在别棒文件里是 private，跨文件不动它，此处自带一份）。
 * internal 而非 private：单测直接锁清洗口径。
 */
internal fun sanitizeDeckFileName(raw: String): String =
    DECK_ILLEGAL_FILE_NAME_CHARS.replace(raw, "_").trim().trimEnd('.').take(MAX_DECK_BASE_NAME_CHARS)

/**
 * 导出文件名 base：`我的卡组_<牌组名>_yyyy-MM-dd`（后缀由 CardImageSaver 按压缩格式补）。
 * 🔴 牌组名是玩家自取，可含 `/ \ : * ? " < > |` 与控制字符、可长到几百字 ⇒ 先清洗、再把预算让给日期，
 * 否则 MediaStore insert / File 创建直接失败。清洗后为空（名字全是非法字符）时回落 [deckLabel]。
 */
fun deckExportBaseName(deckLabel: String, deckName: String, dateText: String): String {
    val date = sanitizeDeckFileName(dateText)
    val suffix = if (date.isEmpty()) "" else "_$date"
    val head = sanitizeDeckFileName("${deckLabel}_$deckName")
        .ifBlank { sanitizeDeckFileName(deckLabel).ifBlank { "deck" } }
    val budget = (MAX_DECK_BASE_NAME_CHARS - suffix.length).coerceAtLeast(1)
    return head.take(budget).trimEnd('_').ifBlank { "deck" } + suffix
}

/** 一次导出的反馈：成功→「已保存卡组图片到相册」；失败→带原因的「导出失败：…」；原因缺失→纯失败文案 */
fun deckExportFeedback(succeeded: Boolean, error: String?): String =
    if (succeeded) {
        exportText(R.string.deck_export_saved, "已保存卡组图片到相册")
    } else {
        error?.takeIf { it.isNotBlank() }
            ?.let { exportText(R.string.stats_export_failed_detail, "导出失败：%1\$s", it) }
            ?: exportText(R.string.error_export_failed, "导出失败")
    }

/** 相册类型子目录名资源（最终目录 `Pictures/GIGI/卡组/<UID>/`）。与 EXPORT_DIR_CHAR/ACTION/QR 同族 */
@StringRes
val EXPORT_DIR_DECK: Int = R.string.export_dir_deck
