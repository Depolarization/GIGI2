// 卡牌统计页的「导出」动作（V27 从 CardStatsRoute 抽出，便于渲染器独立演进）：
// 组装 spec → computeTableLayout（画布宽由表格内容推出，不再由调用方传，见 SPEC-export「我们的适配」1）
// → renderTableBitmap（参考图资源经 ExportAssets 解码一次注入）→ JPEG 落盘
// Pictures/GIGI/<类型子目录>/<UID>/ → Toast 相册相对路径。
// 渲染器签名归 A1、落盘签名归 G，本文件只按冻结契约（SPEC-frozen-api §2/§3）调用。
// V28-C：入口从「导出当前 tab 单表」改为对话框多选（角色牌 / 行动牌），故本文件按勾选项
// 逐张导出；两张表仍共用同一把 Mutex 串行，避免两张巨图同时分配。

package com.gigi.tcg.ui.screens.cardstats

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.gigi.tcg.R
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgSummary
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.ui.dialogs.cardcover.CardImageSaver
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_ACTION
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_CHAR
import com.gigi.tcg.ui.dialogs.cardcover.GIGI_ALBUM_NAME
import com.gigi.tcg.ui.dialogs.cardcover.buildAlbumRelativePath
import com.gigi.tcg.ui.dialogs.cardcover.exportDateText
import com.gigi.tcg.ui.dialogs.cardcover.exportDirName
import com.gigi.tcg.ui.export.computeTableLayout
import com.gigi.tcg.ui.export.loadExportAssets
import com.gigi.tcg.ui.export.paintTextMeasurer
import com.gigi.tcg.ui.export.renderTableBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 统计页 tab 总数（角色牌 / 行动牌两页，pager 与文案表共用） */
internal const val STATS_TAB_COUNT = 2

/** tab 文案资源，下标 = pager 页码 */
internal val STATS_TAB_LABEL_RES = listOf(R.string.stats_tab_char, R.string.stats_tab_action)

/** 页码 → 导出表类型（true = 角色牌）：第 0 页角色牌、第 1 页行动牌，越界按角色牌兜底 */
internal fun isCharTable(page: Int): Boolean = page != 1

/** 表类型 → 相册子目录名资源（G 冻结：目录名已本地化，禁止中文硬编码） */
@StringRes
internal fun exportDirRes(charTable: Boolean): Int =
    if (charTable) EXPORT_DIR_CHAR else EXPORT_DIR_ACTION

/** 表类型 → 分区标题资源：导出文件名 baseName 与图内标题带同源 */
@StringRes
internal fun exportTitleRes(charTable: Boolean): Int =
    if (charTable) R.string.export_char_title else R.string.export_action_title

/**
 * 导出勾选（V28-C 对话框多选状态）：两张表各自是否落盘。
 * 用两个独立布尔而非 Set/List：调用点是 Checkbox 状态，勾选框语义即「这一项要不要导」，
 * 结构化集合反而要多一层 contains 判定。
 */
data class StatsExportSelection(val charTable: Boolean, val actionTable: Boolean) {
    /** 全不勾：此时点「保存」不该静默失败（见 CardStatsRoute 的 onSave 分支） */
    val isEmpty: Boolean get() = !charTable && !actionTable
}

/** 导出动作三元组：按钮可用性、导出中态、按勾选项触发（run 内部自判 summary/exporting/空勾选） */
data class StatsExportAction(val enabled: Boolean, val exporting: Boolean, val run: (StatsExportSelection) -> Unit)

/**
 * 卡牌统计页的「多选导出」动作：按勾选项组装 spec → 渲染 → 落盘 → 逐张反馈。
 * 反馈经 onResult 回传（成功=相册相对路径 Toast 文案，失败=错误信息），由调用方接 LocalToast；
 * 勾选两项即回调两次（每张表各自的成功/失败路径独立，一张失败不吞掉另一张）。
 *
 * [signature] 是玩家真实签名（米游社 `introduce`），透传到 build*TableSpec 画进图里的签名框；
 * 为 null 时由 CardStatsExport.exportSignatureText 回落「暂无签名」占位。
 *
 * 导出的是全量列表（charCards / actionCards 由调用方传 state.charList / state.actionList），
 * 不跟随页面排序按钮与类型筛选（产品决策：分享出去的图要完整可比对）。
 * 互斥锁串行：行动牌双栏长图降级 RGB_565 后仍约 54.5MB，两张巨图同时分配在低端机上可能 OOM。
 * 一次点击导两张时同样走这把锁 + 顺序 for 循环 ⇒ 「同时只有一张巨图在内存」的约束不被破坏。
 */
@Composable
fun rememberStatsExporter(
    context: Context,
    uid: String,
    charCards: List<GcgCard>,
    actionCards: List<GcgCard>,
    summary: GcgSummary?,
    signature: String? = null,
    onResult: (String) -> Unit,
): StatsExportAction {
    val appContext = context.applicationContext
    val coroutineScope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val exportMutex = remember { Mutex() }
    // baseName 用当前语言的分区标题；组合期取值，点击时已被最新重组固定（语言切换即重组）
    val charTitleText = stringResource(exportTitleRes(charTable = true))
    val actionTitleText = stringResource(exportTitleRes(charTable = false))

    val run: (StatsExportSelection) -> Unit = remember(uid, charCards, actionCards, summary, signature, charTitleText, actionTitleText, exporting) {
        { selection ->
            if (summary != null && !exporting && !selection.isEmpty) {
                exporting = true
                coroutineScope.launch {
                    try {
                        // 固定「角色牌 → 行动牌」顺序，与对话框里的选项顺序一致，反馈更好对齐
                        if (selection.charTable) {
                            onResult(exportOneTable(appContext, exportMutex, uid, charTable = true, cards = charCards, summary = summary, titleText = charTitleText, signature = signature))
                        }
                        // 上一张失败也继续第二张：exportOneTable 已把异常吃成错误文案，不抛出
                        if (selection.actionTable) {
                            onResult(exportOneTable(appContext, exportMutex, uid, charTable = false, cards = actionCards, summary = summary, titleText = actionTitleText, signature = signature))
                        }
                    } finally {
                        // 取消（离页等）时也要复位，否则按钮永久禁用；异常路径同样靠这里兜底
                        exporting = false
                    }
                }
            }
        }
    }

    return StatsExportAction(enabled = summary != null, exporting = exporting, run = run)
}

/** 单表导出：渲染 + 落盘，返回要 Toast 的成品文案（成功=相册路径，失败=错误信息） */
private suspend fun exportOneTable(
    appContext: Context,
    exportMutex: Mutex,
    uid: String,
    charTable: Boolean,
    cards: List<GcgCard>,
    summary: GcgSummary,
    titleText: String,
    signature: String?,
): String {
    // 落盘与 Toast 同源：subDir / UID 段只算一次（uid 为空时两级都省略）
    val dir = exportDirName(exportDirRes(charTable))
    val uidSegment = uid.ifBlank { null }
    return try {
        withContext(Dispatchers.Default) {
            exportMutex.withLock {
                val spec = if (charTable) {
                    buildCharTableSpec(summary, uid, cards, signature)
                } else {
                    buildActionTableSpec(summary, uid, cards, signature)
                }
                // 画布宽由表格内容推出（A1 冻结签名，不再传画布宽）
                val layout = computeTableLayout(
                    spec, paintTextMeasurer(), exportTwoColumnThreshold(charTable),
                )
                val assets = loadExportAssets(appContext.resources)
                try {
                    val bitmap = renderTableBitmap(spec, layout, assets)
                    try {
                        // 文件名带导出日期、按表类型 + UID 落子目录（G 冻结签名）
                        CardImageSaver(appContext).saveBitmap(
                            bitmap,
                            baseName = "${titleText}_${exportDateText()}",
                            format = Bitmap.CompressFormat.JPEG,
                            subDir = dir,
                            accountUid = uidSegment,
                        )
                        // 路径与 CardImageSaver 落盘同源：uid 为空时两级都省略，
                        // 走三参重载（与 saveBitmap 的 accountUid=null 行为一致）
                        LocaleStrings.get(
                            R.string.toast_saved_to_album_path,
                            if (uidSegment == null) {
                                buildAlbumRelativePath(
                                    Environment.DIRECTORY_PICTURES, GIGI_ALBUM_NAME, dir,
                                )
                            } else {
                                buildAlbumRelativePath(
                                    Environment.DIRECTORY_PICTURES,
                                    GIGI_ALBUM_NAME,
                                    dir,
                                    uidSegment,
                                )
                            },
                        )
                    } finally {
                        // 回收放 finally：saveBitmap 抛异常也不能漏大图（长图可达数百 KB×行数像素）
                        bitmap.recycle()
                    }
                } finally {
                    // 三张参考图资源位图同样不能因中途异常泄漏
                    assets.divider.recycle()
                    assets.sectionBg.recycle()
                    assets.logo.recycle()
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // CardImageSaver 抛的 IOException message 已是可读文案（含缺存储权限提示）
        e.message ?: LocaleStrings.get(R.string.error_export_failed)
    }
}
