// 卡牌统计页的「导出当前图表」动作（V27 从 CardStatsRoute 抽出，便于渲染器独立演进）：
// 组装 spec → computeTableLayout（画布宽由表格内容推出，不再由调用方传，见 SPEC-export「我们的适配」1）
// → renderTableBitmap（参考图资源经 ExportAssets 解码一次注入）→ JPEG 落盘
// Pictures/GIGI/<类型子目录>/<UID>/ → Toast 相册相对路径。
// 渲染器签名归 A1、落盘签名归 G，本文件只按冻结契约（SPEC-frozen-api §2/§3）调用。

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

/** 导出动作三元组：按钮可用性、导出中态、触发函数（导出中/无数据时 run 为空操作） */
data class StatsExportAction(val enabled: Boolean, val exporting: Boolean, val run: () -> Unit)

/**
 * 卡牌统计页的「导出当前图表」动作：组装 spec → 渲染 → 落盘 → 反馈。
 * 反馈经 onResult 回传（成功=相册相对路径 Toast 文案，失败=错误信息），由调用方接 LocalToast。
 *
 * 导出的是全量列表（cards 由调用方传 state.charList / state.actionList），
 * 不跟随页面排序按钮与类型筛选（产品决策：分享出去的图要完整可比对）。
 * 互斥锁串行：行动牌双栏长图降级 RGB_565 后仍约 54.5MB，两张巨图同时分配在低端机上可能 OOM。
 */
@Composable
fun rememberStatsExporter(
    context: Context,
    uid: String,
    charTable: Boolean,
    cards: List<GcgCard>,
    summary: GcgSummary?,
    onResult: (String) -> Unit,
): StatsExportAction {
    val appContext = context.applicationContext
    val coroutineScope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val exportMutex = remember { Mutex() }
    // baseName 用当前语言的分区标题；组合期取值，点击时已被最新重组固定（语言切换即重组）
    val titleText = stringResource(exportTitleRes(charTable))

    val run: () -> Unit = remember(charTable, uid, cards, summary, titleText, exporting) {
        {
            if (summary != null && !exporting) {
                exporting = true
                // 落盘与 Toast 同源：subDir / UID 段只算一次（uid 为空时两级都省略）
                val dir = exportDirName(exportDirRes(charTable))
                val uidSegment = uid.ifBlank { null }
                coroutineScope.launch {
                    val message = try {
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
                    } finally {
                        exporting = false
                    }
                    onResult(message)
                }
            }
        }
    }

    return StatsExportAction(enabled = summary != null, exporting = exporting, run = run)
}
