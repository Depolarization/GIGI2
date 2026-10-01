// 卡组导出图的内容组装 + 版式计算（V37-F 任务 E，V38-B 按官方参考图重做版式）。
// 本文件只做字符串与算术，🔴 不得引用 android.graphics —— 绘制在 ui/export/DeckImageRenderer.kt，
// 分界线照 CardStatsExport.kt:2 的工程约定，为的是 750px 画布下的尺寸、网格节奏、文件名清洗、反馈文案
// 能在纯 JVM 单测里锁死（工程无 Robolectric）。
//
// 🔴 V38-B 版式数值取自用户提供的官方导出参考图（20260929181737.png，1200×1630，纸面面板宽 900）：
// 面板 900 → 画布 750 的换算系数 5/6。角色牌 144×240 → 120×200、行动牌 96×160（6 列铺满 646 内容宽）、
// 行列间距 18/20 → 14、分区标题金棕 #84603D、纸面米白 #DBD5CE、页脚两行 UID/昵称在左下。
// 🔴 顶部 banner（米游社 logo + 七圣召唤文本）整段移除（用户明确要求）；两个金色分区标题保留。
// 🔴 官方 .share-footer（logo+二维码广告条）依旧不绘制；参考图底部只有 UID/昵称与游戏 logo。

package com.gigi.tcg.ui.export

import androidx.annotation.StringRes
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgDeck
import com.gigi.tcg.data.model.GcgDeckCard
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

// ---- 分区与网格（比例取自用户提供的官方参考图，见文件头换算口径） ----

internal val DECK_BODY_PADDING_TOP_PX = designPx(15)
internal val DECK_BODY_PADDING_SIDE_PX = designPx(15)
internal val DECK_GROUP_PADDING_SIDE_PX = designPx(11)

/** 角色牌恒 3 张（参考图与实测都是 3），超出钳制、不足按实际数居中 */
internal const val DECK_ROLE_CARD_COUNT = 3

/** 行动牌网格恒 6 列（参考图 6 列），行数 = ceil(张数/6) 自动增长 */
internal const val DECK_ACTION_GRID_COLUMNS = 6

/**
 * 🔴 **一副合法卡组的行动牌张数**（V38 用户规则纠正）。
 *
 * 七圣召唤的对战规则：必须 **3 角色牌 + 30 行动牌** 才能出战，不满 30 不能配��。
 * 实测 `157777921` 的 11 副牌组**全部**满足：`action_cards` 数组长度（= 种类数）只有 22~25，
 * 但 `sum(num)` **恒为 30** ⇒ 接口给的是**按种类去重**的列表。
 *
 * 官方参考图正是 **6 列 × 5 行 = 30 格**，重复的牌**各占一格**（可见成对并排的同图），
 * 官方**不画任何张数徽标**。⇒ 版式按本常量铺满 5 行；玩家没填满时（`sum(num) < 30`）
 * **末行留空占位**，而不是把网格压缩 —— 压缩会让「这牌组不合法」这件事被视觉掩盖。
 */
internal const val DECK_ACTION_FULL_DECK_COUNT = 30

/** 行动牌网格行数：合法卡组恒 5 行；不满 30 时向上取整并留空占位 */
internal fun actionGridRows(actionCount: Int): Int =
    ((actionCount.coerceAtLeast(0) + DECK_ACTION_GRID_COLUMNS - 1) / DECK_ACTION_GRID_COLUMNS)
        .coerceAtLeast(1)

/** 角色牌 120×200（参考图 144×240 × 5/6） */
internal val DECK_ROLE_CARD_WIDTH_PX = 120
internal val DECK_ROLE_CARD_HEIGHT_PX = 200

/** 行列间距（参考图 18/20px@900 → 14/16px@750，取单一常量） */
internal val DECK_CARD_GRID_GAP_PX = 14

/** 行动牌 96×160 = 参考图实测原尺寸（面板 900 → 画布 750 恰好 1:1，无需换算）。
 *  🔴 不写成对同文件 internal val 的算术引用：Kotlin internal 顶层属性走懒编译访问器，
 *  初始化器里读其它属性会拿到 0（实测踩坑），故按 30/22/14 手算成字面量。 */
internal const val DECK_ACTION_CARD_WIDTH_PX = 96
internal const val DECK_ACTION_CARD_HEIGHT_PX = 160

/** 卡面宽高比：接口图 URL 自带 `resize,m_fixed,h_275,w_160`，与页内 tile 同一口径 */
internal const val DECK_CARD_FACE_RATIO = 275f / 160f

internal fun deckCardHeightPx(widthPx: Int): Int = (widthPx * DECK_CARD_FACE_RATIO).roundToInt()

// ---- 文字与间距 ----

internal val DECK_PILL_TEXT_PX = designPx(11)

/** 分区标题（出战阵容/出战牌组）字号，参考图量得 ≈26px@900 → 22px@750 */
internal val DECK_GROUP_LABEL_TEXT_PX = 22

internal val DECK_TITLE_LINE_HEIGHT_PX = designPx(22)
internal val DECK_AUTHOR_LINE_HEIGHT_PX = designPx(15)
internal val DECK_DESC_LINE_HEIGHT_PX = designPx(14)
internal val DECK_PILL_HEIGHT_PX = designPx(20)

/** 分区标题行高（参考图字带 22px@900 → 18px@750，留 2px 富余） */
internal val DECK_GROUP_LABEL_HEIGHT_PX = 20

/** 标题带顶距（参考图纸面顶到「出战阵容」约 26px@750） */
internal val DECK_TOP_MARGIN_PX = 24
private val TITLE_TO_CARDS_GAP_PX = 20
private val ROLE_TO_ACTION_GAP_PX = 56
private val UID_LINE_HEIGHT_PX = 26
private val UID_LINE_GAP_PX = 6

/** 页脚高度：UID + 昵称两行 + 下面留白（V38 起右下**不再画 logo**，留白仅作纸面呼吸区） */
internal val DECK_FOOTER_HEIGHT_PX = UID_LINE_HEIGHT_PX * 2 + UID_LINE_GAP_PX + 32

private val PILL_GAP_PX = designPx(6)
private val PILL_H_PADDING_PX = designPx(10)

// ---- 配色（参考图 PIL 采样值，导出不跟随应用主题 ⇒ 写死） ----

/** 纸面米白（参考图面板众数 RGB 219,213,206） */
internal const val DECK_COLOR_PAPER_BG = 0xFFDBD5CE.toInt()

/** 分区标题金棕（参考图「出战阵容」笔画众数 RGB 132,96,61） */
internal const val DECK_COLOR_SECTION_TITLE = 0xFF84603D.toInt()

/** 四角云纹/卷草（程序化近似的浅褐色；比第一版压深一档，否则 750px 画布上完全不可辨） */
internal const val DECK_COLOR_CORNER_DECO = 0xFFBCAF9E.toInt()

/** 纸面板内描边（参考图面板边缘细线） */
internal const val DECK_COLOR_PANEL_BORDER = 0xFFB7A98F.toInt()

/** 纸面板双线框的内线（比外线更浅，只做层次，不抢视线） */
internal const val DECK_COLOR_PANEL_BORDER_INNER = 0xFFCDC2AF.toInt()

/** 页脚 UID/昵称（参考图浅字压在深色桌面上；本项目画在米白纸面上，改取纸面深褐保证可读） */
internal const val DECK_COLOR_FOOTER_TEXT = 0xFF6E5744.toInt()

/** 标签条底/字（官方 label-container #f1eadb，版式算术仍用） */
internal const val DECK_COLOR_PILL_TEXT = 0xFF8A6C4A.toInt()

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
    /** 角色牌区标题（参考图「出战阵容」位） */
    val roleLabel: String,
    /** 行动牌区标题（参考图「出战牌组」位） */
    val actionLabel: String,
    /** 品牌行（V38-B 起顶部 banner 已移除，渲染层不再使用，仅保留数据通道兼容） */
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
 * 🔴 纵向**只增长不分页**：实测一副牌组 3 + 22–25 张，行动牌 6 列最多 5 行、整图约 1500px 高
 * （远小于统计页那张 3 万像素长图，内存预算同一套闸）。拆成多张会破坏「一张图分享一副牌组」，
 * 所以张数再多也只是继续加行。
 */
data class DeckImageLayout(
    val widthPx: Int,
    val heightPx: Int,
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
    /** 「出战阵容」分区标题顶（角色牌为空时 null） */
    val roleLabelTopPx: Int?,
    /** 「出战牌组」分区标题顶（行动牌为空时 null） */
    val actionLabelTopPx: Int?,
    val roleSlots: List<DeckCardSlot>,
    val actionSlots: List<DeckCardSlot>,
    val roleCardWidthPx: Int,
    val roleCardHeightPx: Int,
    val actionCardWidthPx: Int,
    val actionCardHeightPx: Int,
    /** 页脚顶（UID 行起点） */
    val footerTopPx: Int,
    /** 页脚左缘 = 卡牌网格左缘 [cardsLeftPx]（V39：UID/昵称行左对齐网格，不是 body padding） */
    val footerLeftPx: Int,
    /** 页脚右缘 = 卡牌网格右边界 [cardsLeftPx] + [cardsContentWidthPx]（V39：卡组名右对齐到它） */
    val footerRightPx: Int,
)

/** 角色牌张数判据：恒钳制到 [0, 3]（参考图与实测都是 3 张，多出的牌不进图） */
fun characterCardCount(size: Int): Int = size.coerceIn(0, DECK_ROLE_CARD_COUNT)

/**
 * 纯函数：算版式。角色牌 ≤3 张一行居中；行动牌恒 [DECK_ACTION_GRID_COLUMNS] 列、
 * 行数 = ceil(张数/列数) 居中排布；张数 0 ⇒ 0 行且整块（含分区标题）不占高。
 */
fun computeDeckImageLayout(spec: DeckImageSpec, measurer: TextMeasurer): DeckImageLayout {
    val textLeftPx = DECK_BODY_PADDING_SIDE_PX
    val textContentWidthPx = DECK_CANVAS_WIDTH_PX - 2 * DECK_BODY_PADDING_SIDE_PX
    val cardsLeftPx = textLeftPx + DECK_GROUP_PADDING_SIDE_PX
    val cardsContentWidthPx = DECK_CANVAS_WIDTH_PX - 2 * cardsLeftPx
    // 页脚左右缘贴卡牌网格（V39 用户指令），渲染层直接用这两值，不再自己加 padding
    val footerLeftPx = cardsLeftPx
    val footerRightPx = cardsLeftPx + cardsContentWidthPx

    val roleCardWidthPx = DECK_ROLE_CARD_WIDTH_PX
    val roleCardHeightPx = DECK_ROLE_CARD_HEIGHT_PX
    val actionCardWidthPx = DECK_ACTION_CARD_WIDTH_PX
    val actionCardHeightPx = DECK_ACTION_CARD_HEIGHT_PX

    // V37 文字流（牌组名/作者/标签条/描述）已从画面移除，但保留其纵向算术与数据通道 ⇒ 旧版式断言不回归
    var y = DECK_BODY_PADDING_TOP_PX
    val titleTopPx = y
    if (spec.title.isNotEmpty()) {
        y += DECK_TITLE_LINE_HEIGHT_PX
        if (spec.authorText.isNotEmpty()) y += designPx(6)
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
        y += designPx(8)
        pills = layoutPills(spec.pills, measurer, textLeftPx, textContentWidthPx, y)
        y = pills.last().yPx + DECK_PILL_HEIGHT_PX + designPx(9)
    }
    val descTopPx = y

    y = DECK_TOP_MARGIN_PX
    val roleCount = characterCardCount(spec.roleCards.size)
    val hasRole = roleCount > 0
    val roleLabelTopPx = if (hasRole) y else null
    if (hasRole) y += DECK_GROUP_LABEL_HEIGHT_PX + TITLE_TO_CARDS_GAP_PX
    val roleSlots = centeredGridSlots(roleCount, y, roleCardWidthPx, roleCardHeightPx, roleCount)

    y = roleSlots.lastOrNull()?.let { it.yPx + it.heightPx } ?: (DECK_TOP_MARGIN_PX)
    if (hasRole) y += ROLE_TO_ACTION_GAP_PX
    val hasAction = spec.actionCards.isNotEmpty()
    val actionLabelTopPx = if (hasAction) y else null
    if (hasAction) y += DECK_GROUP_LABEL_HEIGHT_PX + TITLE_TO_CARDS_GAP_PX
    val actionSlots = centeredGridSlots(
        spec.actionCards.size, y, actionCardWidthPx, actionCardHeightPx, DECK_ACTION_GRID_COLUMNS,
    )
    // 🔴 画布高度按**合法卡组的 5 行**（3+30 规则）算，不按实际张数 ——
    //    玩家没填满 30 时末行留空占位，视觉上就能看出「这牌组还差几张」，
    //    而压缩网格会把「不合法」这件事掩盖掉（V38 用户明确要求留占位空间）。
    val actionRows = maxOf(actionGridRows(spec.actionCards.size), actionGridRows(DECK_ACTION_FULL_DECK_COUNT))
    val actionBlockHeightPx = actionRows * actionCardHeightPx +
        (actionRows - 1).coerceAtLeast(0) * DECK_CARD_GRID_GAP_PX

    // 行动牌区底缘：按固定行数算（末行空占位也占高），而不是「最后一张牌的实际底缘」
    val lastBottom = if (hasAction) {
        // hasAction ⇒ actionLabelTopPx 必非空（同一次判定），用 Elvis 兜底只为满足空安全
        (actionLabelTopPx ?: y) + DECK_GROUP_LABEL_HEIGHT_PX + TITLE_TO_CARDS_GAP_PX + actionBlockHeightPx
    } else {
        roleSlots.lastOrNull()?.let { it.yPx + it.heightPx } ?: y
    }
    val footerTopPx = lastBottom + DECK_BODY_PADDING_TOP_PX
    val heightPx = footerTopPx + DECK_FOOTER_HEIGHT_PX

    return DeckImageLayout(
        widthPx = DECK_CANVAS_WIDTH_PX,
        heightPx = heightPx,
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
        footerTopPx = footerTopPx,
        footerLeftPx = footerLeftPx,
        footerRightPx = footerRightPx,
    )
}

/**
 * 网格：[columns] 列等距排布，整块在画布内水平居中（参考图两组牌都是居中的）。
 * 末行不铺满 ⇒ 仍按整块居中（官方导出图末行不拉宽）。
 */
private fun centeredGridSlots(count: Int, topPx: Int, cardWidthPx: Int, cardHeightPx: Int, columns: Int): List<DeckCardSlot> {
    if (count <= 0) return emptyList()
    val cols = columns.coerceAtLeast(1)
    val blockWidth = cols * cardWidthPx + (cols - 1) * DECK_CARD_GRID_GAP_PX
    val left = (DECK_CANVAS_WIDTH_PX - blockWidth) / 2
    return List(count) { index ->
        DeckCardSlot(
            xPx = left + (index % cols) * (cardWidthPx + DECK_CARD_GRID_GAP_PX),
            yPx = topPx + (index / cols) * (cardHeightPx + DECK_CARD_GRID_GAP_PX),
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
 * 🔴 **按 `num` 展开成独立格**（V38 用户纠正）。
 *
 * 接口的 `action_cards` 是**按卡牌种类去重**的：`num` 才是「牌组内携带几张」。
 * 实测 `157777921` 的 11 副牌组**全部**是「角色牌 3 + 行动牌携带总数 30」——
 * 七圣召唤的对战规则要求 3 角色牌 + 30 行动牌，不满 30 不能出战；
 * 而同一批数据的 `action_cards` 数组长度只有 22~25（种类数），**携带总数恒为 30**。
 *
 * 官方参考图正是 **6 列 × 5 行 = 30 格**：重复的牌**各占一格**（可见成对并排的同图），
 * 官方**不画任何张数徽标**。⇒ 必须展开，否则：
 * ① 格子数少于官方（22 格 vs 30 格，末行也不齐）；
 * ② 逼迫版式去画 `×N` 徽标，而官方根本没有这个东西。
 *
 * `num` 缺失/≤0 视为 1（保守：宁可多画一格同图，也不少画导致总数对不上）。
 */
internal fun expandActionCardsByCount(cards: List<GcgDeckCard>): List<DeckCardFace> =
    cards.flatMap { card ->
        val copies = (card.num ?: 1).coerceAtLeast(1)
        List(copies) { DeckCardFace(card.name, card.image) }
    }

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
    val actionCards = expandActionCardsByCount(deck.actionCards.orEmpty())
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
        // 分区标题照官方参考图文案（「出战阵容」「出战牌组」），不沿用 App 内的
        // 「角色牌/行动牌」措辞 —— V38 用户要求导出图版式严格参考官方截图。
        roleLabel = exportText(R.string.export_deck_section_role, "出战阵容"),
        actionLabel = exportText(R.string.export_deck_section_action, "出战牌组"),
        brandText = exportText(R.string.export_footer, "七圣召唤"),
        roleCards = roleCards,
        actionCards = actionCards,
    )
}

/**
 * 页脚两行（参考图左下）：`UID:<id>` + 昵称。昵称取 authorText 去掉「分享人：」前缀后的值
 * （buildDeckImageSpec 的 author 兜底链就是 昵称→UID，语义与参考图一致）；UID 缺失时只画一行。
 */
fun deckFooterLines(authorText: String, uid: String?): List<String> {
    val name = authorText.substringAfter("：").ifBlank { authorText.trim() }
    val lines = ArrayList<String>(2)
    uid?.takeIf { it.isNotBlank() }?.let { lines += "UID:$it" }
    if (name.isNotBlank()) lines += name
    return lines
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
