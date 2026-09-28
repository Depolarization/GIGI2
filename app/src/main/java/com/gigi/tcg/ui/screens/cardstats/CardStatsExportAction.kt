// 卡牌统计页的「导出」动作（V27 从 CardStatsRoute 抽出，便于渲染器独立演进）：
// 组装 spec → computeTableLayout（画布宽由表格内容推出，不再由调用方传，见 SPEC-export「我们的适配」1）
// → renderTableBitmap（参考图资源经 ExportAssets 解码一次注入）→ JPEG 落盘
// Pictures/GIGI/<类型子目录>/<UID>/。渲染器签名归 A1、落盘签名归 G，本文件只按冻结契约
// （SPEC-frozen-api §2/§3）调用。
// V28-C：入口从「导出当前 tab 单表」改为对话框多选（角色牌/行动牌），按勾选项逐张导出；
// 两张表仍共用同一把 Mutex 串行，避免两张巨图同时分配。
// V29：反馈从「每张一条相册路径 Toast」改成「整次勾选一条消息」——
// 两张都成功 ⇒ 合并成一条且不报具体路径（「已保存 2 张图片到相册」）；
// 全失败 ⇒ 一条失败消息（带第一张的错误原因，不静默吞掉）；一成一败 ⇒ 一条混合消息。
// 同时把落盘后的 MediaStore Uri 一起回传给宿主，供 Snackbar 的「查看」action 打开相册。
// 另：V29-B 撤掉了导出图的签名框（签名恒 null 的诚实降级），build*TableSpec 已不收 signature ⇒
// 本文件这条链上不再有 signature，但 StatsUiState.signature 仍由 VM 取着（清理归撤签名框的那一棒）。

package com.gigi.tcg.ui.screens.cardstats

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
 * 真正会进导出表的条数（对话框里报给用户的数字必须和落盘的图对得上）。
 * 口径 = buildCharTableSpec / buildActionTableSpec 里的 `useCount > 0` 过滤
 * （CardStatsExport.kt:145 与 :199），出场次数为 0 或 null 的牌不画进图，也就不该计数。
 * 🔴 与页面上的排序按钮 / 类型筛选无关：导出的一直是全量列表（产品决策，见 [rememberStatsExporter] 注释），
 * 所以行动牌这里也**不能**用 state.filteredActionList，否则「筛选后 30 条」和图里 400 条自相矛盾。
 * 与那两处 filter 是同一条谓词的两份写法（CardStatsExport.kt 归别人改，这里不擅自重构），
 * 由 CardStatsTabsTest 的谓词一致性断言钉住，防止两处口径漂移。
 */
internal fun exportRowCount(cards: List<GcgCard>): Int = cards.count { (it.useCount ?: 0) > 0 }

/**
 * 导出勾选（V28-C 对话框多选状态）：两张表各自是否落盘。
 * 用两个独立布尔而非 Set/List：调用点是 Checkbox 状态，勾选框语义即「这一项要不要导」，
 * 结构化集合反而要多一层 contains 判定。
 */
data class StatsExportSelection(val charTable: Boolean, val actionTable: Boolean) {
    /** 全不勾：此时点「保存」不该静默失败（见 CardStatsRoute 的 onSave 分支） */
    val isEmpty: Boolean get() = !charTable && !actionTable
}

/**
 * 单表导出结果（V29）：[succeeded] 区分成功/失败（[uri] 为 null 不等于失败，见下），
 * [uri] 是落盘后的相册条目 Uri，只有它能让宿主把 Snackbar 的「查看」指到这张图；
 * [error] 仅在失败时非空（CardImageSaver 抛的 IOException message 已是可读文案，含缺存储权限提示）。
 *
 * ⚠️ API 24-28 走公共目录 + MediaScanner 落盘，**没有**可返回的 MediaStore uri
 * （CardImageSaver.saveBitmap 那条分支恒返回 null，文件确实写成功了）⇒ 这种机器上
 * succeeded=true 但 uri=null：消息照常说「已保存 N 张」，只是「查看」点不出东西（宿主需容忍空列表）。
 */
data class TableExportOutcome(val succeeded: Boolean, val uri: Uri?, val error: String? = null)

/** 导出动作三元组：按钮可用性、导出中态、按勾选项触发（run 内部自判 summary/exporting/空勾选） */
data class StatsExportAction(val enabled: Boolean, val exporting: Boolean, val run: (StatsExportSelection) -> Unit)

/**
 * 卡牌统计页的「多选导出」动作：按勾选项组装 spec → 渲染 → 落盘，
 * 整次点击只经 [onResult] 回**一条**消息（文案 + 成功落盘的 Uri 列表），由宿主做成带「查看」action 的 Snackbar。
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
    onResult: (message: String, uris: List<Uri>) -> Unit,
): StatsExportAction {
    val appContext = context.applicationContext
    val coroutineScope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val exportMutex = remember { Mutex() }
    // baseName 用当前语言的分区标题；组合期取值，点击时已被最新重组固定（语言切换即重组）
    val charTitleText = stringResource(exportTitleRes(charTable = true))
    val actionTitleText = stringResource(exportTitleRes(charTable = false))

    val run: (StatsExportSelection) -> Unit = remember(uid, charCards, actionCards, summary, charTitleText, actionTitleText, exporting) {
        { selection ->
            if (summary != null && !exporting && !selection.isEmpty) {
                exporting = true
                coroutineScope.launch {
                    try {
                        // 固定「角色牌 → 行动牌」顺序，与对话框里的选项顺序一致
                        val outcomes = mutableListOf<TableExportOutcome>()
                        if (selection.charTable) {
                            outcomes += exportOneTable(appContext, exportMutex, uid, charTable = true, cards = charCards, summary = summary, titleText = charTitleText)
                        }
                        // 上一张失败也继续第二张：exportOneTable 已把异常吃成 error 字段，不抛出
                        if (selection.actionTable) {
                            outcomes += exportOneTable(appContext, exportMutex, uid, charTable = false, cards = actionCards, summary = summary, titleText = actionTitleText)
                        }
                        // 一张消息收口：两条 Toast 的话，第二张会盖掉第一张（Snackbar 队列也是排队显示）
                        onResult(exportFeedbackText(outcomes), outcomes.mapNotNull { it.uri })
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

/** 勾选两张 → saved_two，一张 → saved_one（都是「已保存 N 张图片到相册」，不带路径） */
@StringRes
private fun savedTextRes(savedCount: Int): Int =
    if (savedCount > 1) R.string.stats_export_saved_two else R.string.stats_export_saved_one

/**
 * 把本次点击的若干张结果聚成一句反馈：
 * 全成功 → 「已保存 N 张图片到相册」；全失败 → 「导出失败：<原因>」（原因缺失时回落纯失败文案）；
 * 有成一败 → 「成功 N 张、失败 M 张」。括号/量词随语种走，代码只填数字。
 * internal 而非 private：纯 JVM 单测（注入 LocaleStrings 解析器）能直接断言聚合口径。
 */
internal fun exportFeedbackText(outcomes: List<TableExportOutcome>): String {
    val saved = outcomes.count { it.succeeded }
    val failed = outcomes.size - saved
    return when {
        saved == 0 -> outcomes.firstNotNullOfOrNull { it.error }
            ?.let { LocaleStrings.get(R.string.stats_export_failed_detail, it) }
            ?: LocaleStrings.get(R.string.stats_export_failed)
        failed == 0 -> LocaleStrings.get(savedTextRes(saved), saved)
        else -> LocaleStrings.get(R.string.stats_export_partial, saved, failed)
    }
}

/** 单表导出：渲染 + 落盘，返回成功标记 / 相册 Uri / 失败原因三元组 */
private suspend fun exportOneTable(
    appContext: Context,
    exportMutex: Mutex,
    uid: String,
    charTable: Boolean,
    cards: List<GcgCard>,
    summary: GcgSummary,
    titleText: String,
): TableExportOutcome {
    // 落盘子目录与 UID 层级只算一次（uid 为空时两级都省略）
    val dir = exportDirName(exportDirRes(charTable))
    val uidSegment = uid.ifBlank { null }
    return try {
        withContext(Dispatchers.Default) {
            exportMutex.withLock {
                val spec = if (charTable) {
                    buildCharTableSpec(summary, uid, cards)
                } else {
                    buildActionTableSpec(summary, uid, cards)
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
                        // 返回的 uri：Q+ = MediaStore 条目，API 24-28 恒 null（见 TableExportOutcome）
                        val uri = CardImageSaver(appContext).saveBitmap(
                            bitmap,
                            baseName = "${titleText}_${exportDateText()}",
                            format = Bitmap.CompressFormat.JPEG,
                            subDir = dir,
                            accountUid = uidSegment,
                        )
                        TableExportOutcome(succeeded = true, uri = uri)
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
        TableExportOutcome(succeeded = false, uri = null, error = e.message ?: LocaleStrings.get(R.string.error_export_failed))
    }
}
