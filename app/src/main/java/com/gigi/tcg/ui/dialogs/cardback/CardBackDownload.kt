// 卡背下载的纯逻辑层（V38-D）。
// 🔴 与卡牌图鉴的下载链路**不同源**：卡牌那张要先经米游社 wiki 详情接口（fetchCardDetail + entry_page
// 二次解析）才拿到 common_img/gold_img，所以它有 CardCoverViewModel 那套状态机；
// 卡背在 gcg/cardBackList 里**直接带 image_v2 原图直链**（实测 28/28 全有、无 wiki entry_page_id），
// ⇒ 本页只下载直链，不碰 wiki，也就没有 Loading/Error 状态机可建。
// 本文件刻意零 android import（连 R.string 只取 Int id），JVM 单测直接断言判据。

package com.gigi.tcg.ui.dialogs.cardback

import androidx.annotation.StringRes
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgCardBack

/**
 * 卡背相册类型子目录名资源（最终目录 `Pictures/GIGI/卡背/`），与 EXPORT_DIR_CHAR/ACTION/QR/DECK 同族。
 *
 * 🔴 **不分 UID**：卡背图是公共图床资源（act-webstatic CDN，无 Cookie 也 200），同一张图对任何账户
 * 逐字节相同 ⇒ UID 层级没有信息量。与「二维码不分账号」（EXPORT_DIR_QR）同一口径。
 */
@StringRes val EXPORT_DIR_CARDBACK: Int = R.string.export_dir_cardback

/**
 * 取卡背原图直链：优先 `image_v2`（实测 28/28 全有），缺失时才回落 `image`
 * （实测 28 份里只有 21 份有 ⇒ 不可靠，但作兜底聊胜于无）。空串按缺失处理。
 *
 * 与 MyCardBacksPage 网格里 `(cardBack.imageV2 ?: cardBack.image)` 同一判据 —— 预览与下载必须同源，
 * 否则「看到的图」和「落盘的图」可能是两张。
 */
fun resolveCardBackImageUrl(cardBack: GcgCardBack?): String? {
    if (cardBack == null) return null
    return listOfNotNull(cardBack.imageV2, cardBack.image)
        .firstOrNull { it.isNotBlank() }
}

/**
 * 该卡背可下载的直链，null = 不可下载。
 *
 * 🔴 未收集（`has_obtained != true`）一律不可下载：接口回的是**全部**卡背，未收集的只该置灰看，
 * 不该把没拿到的东西存进相册。`hasObtained` 为 null（字段缺失）按未收集处理 —— 宁可少给入口，不可误给。
 */
fun resolveDownloadable(cardBack: GcgCardBack?): String? =
    if (cardBack?.hasObtained != true) null else resolveCardBackImageUrl(cardBack)

/**
 * 落盘基础名（不含扩展名）：`<本地化"卡背">_<id>_<yyyy-MM-dd>`，如 `卡背_101_2026-09-29`。
 *
 * 卡背接口**没有名字字段**（只有 id / category，category 恒为 `CGCCardCategoryCardBack`）⇒
 * 无法像卡面那样用卡名，取「类型名 + id」；id 缺失时退化成「类型名 + 日期」。
 * 清洗与截断交给 [com.gigi.tcg.ui.dialogs.cardcover.coverFileName]（同一套非法字符/60 字上限口径）。
 *
 * @param label 已本地化的卡背称谓（`exportDirName`/stringResource 取来的文案，非资源 id）
 */
fun cardBackBaseName(cardBack: GcgCardBack?, dateText: String, label: String): String {
    val idPart = cardBack?.id?.toString()
    return listOf(label, idPart, dateText)
        .filter { !it.isNullOrBlank() }
        .joinToString("_")
}
