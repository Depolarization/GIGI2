package com.gigi.tcg.ui.export

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.gigi.tcg.R
import com.gigi.tcg.i18n.LocaleStrings
import java.io.IOException
import kotlin.math.ceil

// 版式照抄参考导出图的实测规格（.task/dispatch/SPEC-export.md，源自 Alpiiine/gcg-plugin 的 gcg.html/css）。
// 🔴 V36 已知无解项（勿再排查、勿加重试）：导出图行动牌分母源自 gcg/basicInfo 的 *_card_num_total，
// 该端点被网关**定向门禁** —— V36-5 用 37 组变体实测恒 retcode=1034（41 字节定长、两账号逐字节一致，
// 报告 .task/v36-probe/basicinfo/REPORT.md）；同 cookie 下 deckList/cardBackList/cardList 恒 0，
// POST 一律 405、基线路径唯一正确 ⇒ 既非 cookie、非账号风控、非请求写法，判定在业务代码之前。
// ⇒ CardStatsViewModel.fetchOfficialCardTotals 恒 null，分母降级为**图鉴口径 568**（官方真值 **941**），
//   导出图上「没收集全」会被画成「全收集」。这是已知降级、不是待修 bug。
//   1034 已被 MihoyoClient 判为不可重试（V36-1），不要给它加退避/兜底。
//   若将来要修：改走 BuildConfig.CONTENT_LIST_URL（公开图鉴接口）取 941 分母绕开门禁 —— 涉数据层，归下一轮。
// 关键模型：**HTML 表格自动布局** —— 单元格全部 whitespace-nowrap，
// 列宽 = 该列 max(表头宽, 各行该列文本宽) + 左右内边距，表格自然变宽，
// **永不换行、永不省略**（旧实现按 weight 拉伸填满固定画布：长牌名折成「8+1」看着像缩进、
// 「使用率%」表头被省略号截断，根因都是列宽与内容无关）。
// 画布宽由表格内容反推（SPEC-export.md「我们的适配」第 1 条），不再由调用方指定。
// 字号直接按 px 定（不随屏幕密度），因为分享出去的图规格必须跨设备统一。

/** 文本测量注入：运行期用 Paint，JVM 单测用近似实现（CJK=字号，拉丁≈0.55×字号） */
fun interface TextMeasurer {
    fun measure(text: String, textSizePx: Float): Float
}

/**
 * 生产实现：单张 Paint 复用，避免每格重建 Paint。
 * 测量 Paint 开 fakeBold：表头与首列序号是按伪粗体画的，而 TextMeasurer 只吃 (文本, 字号)、
 * 无法区分粗细 ⇒ 统一按伪粗体（更宽的一侧）量，列宽只会略富不会欠，避免压字/蹭到邻列。
 */
fun paintTextMeasurer(): TextMeasurer {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    return TextMeasurer { text, textSizePx ->
        paint.textSize = textSizePx
        paint.measureText(text)
    }
}

/** 单元格水平对齐：# 列 START，其余列 CENTER */
enum class CellAlign { START, CENTER, END }

/** 列定义；[minWidthPx] 是列宽下限（0 = 不设），用于内容测不出可用宽度时兜底 */
data class TableColumn(
    val header: String,
    val align: CellAlign,
    val minWidthPx: Int = 0,
)

/** 一张表的完整内容（页眉文案 + 表体） */
data class TableSpec(
    /** 分区标题带文字（角色牌数据 / 行动牌数据） */
    val title: String,
    /** 页眉第 1 行，形如 "Clin - 110526730" */
    val nickname: String,
    /** 页眉第 2 行，形如 "牌手等级 10"；null 时整行连同间距都不占 */
    val levelText: String?,
    /** 蓝底胶囊（已得/总数、场次、胜率） */
    val badges: List<String>,
    val columns: List<TableColumn>,
    /** 每行的单元格文本，每行长度必须 == columns.size */
    val rows: List<List<String>>,
    /** 导出日期；画在右上角 logo 正下方、右对齐到 logo 右缘（自成视觉组，不占页眉竖向流）；null 不画 */
    val exportDateText: String?,
)

/** 纯计算的布局结果（不引用 android.graphics，可 JVM 单测） */
data class TableLayout(
    /** 画布宽 = 表格内容宽 + 2×(24+2)，双栏再加 15px 栏间距 */
    val widthPx: Int,
    val heightPx: Int,
    /** 表格内容宽（双栏时 = 单栏宽 × 2，即两栏合计占用的内容宽） */
    val tableWidthPx: Int,
    /** 单栏表格内容宽 = 列宽之和（双栏时两栏共用同一套全局列宽 ⇒ 两栏等宽） */
    val bandWidthPx: Int,
    /** 各列左缘，相对表格内容区左缘 */
    val columnX: List<Int>,
    val columnWidth: List<Int>,
    val columnsPerBand: Int,
    /** 每栏行数（单栏 = rows.size；双栏 = ceil(rows.size/2)，第 2 栏取剩余） */
    val rowsPerBand: Int,
)

// ---- 表格本体：1:1 照抄参考图实测（字号 20、行高 50、内边距 10/15、斑马 #E2E8F0、无色线）----
internal const val TEXT_SIZE_BODY_PX = 20f
internal const val TEXT_SIZE_HEADER_PX = 20f
internal const val ROW_HEIGHT_PX = 50
internal const val HEADER_HEIGHT_PX = 50
internal const val CELL_PADDING_PX = 10
/** 首列左内边距（参考图 `sm:pl-3` 覆盖 `px-2`）；START 对齐只用于首列 */
internal const val FIRST_CELL_LEFT_PADDING_PX = 15
/** 页边距；表格盒边框 2px ⇒ 表格内容左缘 = 24 + 2 = 26（参考图实测） */
internal const val PAGE_MARGIN_PX = 24
internal const val TABLE_BORDER_PX = 2
internal const val TABLE_CORNER_RADIUS_PX = 6f
/** 双栏栏间距（参考图 `space-x-3`） */
internal const val BAND_GAP_PX = 15

// ---- 页眉（参考图数值整体 ×1.25，见 SPEC-export.md「我们的适配」第 2 条）----
private const val HEADER_TOP_PADDING_PX = 40
private const val NICKNAME_TEXT_SIZE_PX = 35f
private const val NICKNAME_LINE_HEIGHT_PX = 40
private const val NICKNAME_TOP_MARGIN_PX = 5
private const val NICKNAME_BOTTOM_MARGIN_PX = 10
private const val LEVEL_TEXT_SIZE_PX = 25f
private const val LEVEL_LINE_HEIGHT_PX = 25
private const val LEVEL_BOTTOM_MARGIN_PX = 25
private const val BADGE_TEXT_SIZE_PX = 25f
private const val BADGE_HEIGHT_PX = 45
private const val BADGE_RADIUS_PX = 8f
private const val BADGE_HORIZONTAL_PADDING_PX = 20
private const val BADGE_RIGHT_MARGIN_PX = 20
private const val BADGE_BOTTOM_MARGIN_PX = 13
private const val BADGE_ROW_BOTTOM_MARGIN_PX = 8
/**
 * 页眉最后一行 → 分隔虚线顶 的留白。
 * V29-B 移除签名框后，这里接替原先 SIGNATURE_BOTTOM_MARGIN_PX 的 25（与 LEVEL_BOTTOM_MARGIN_PX
 * 同一档间距），保证「文字行结束」到「虚线」不会挤成一行。
 */
private const val HEADER_BOTTOM_MARGIN_PX = 25
private const val DIVIDER_HEIGHT_PX = 4
/** 分隔虚线左右缩进（参考图 `left/right: 28px`） */
private const val DIVIDER_INSET_PX = 28
internal const val LOGO_WIDTH_PX = 225
internal const val LOGO_HEIGHT_PX = 75
internal const val LOGO_TOP_PX = 72
internal const val LOGO_RIGHT_INSET_PX = 40
/**
 * 日期顶 → logo 底 的间距。页眉流的间距档位是 5/10/13/25/28，日期要和 logo 成一组
 * （同组必须明显小于组间），故取次小档 8：小于 8 会蹭到 logo 下缘，大于 13 就散成两行独立信息。
 */
internal const val DATE_BELOW_LOGO_GAP_PX = 8

// ---- 分区标题带 / 日期 / 页脚 ----
private const val BANNER_HEIGHT_PX = 80
private const val BANNER_BOTTOM_MARGIN_PX = 30
private const val BANNER_TEXT_SIZE_PX = 31f
private const val BANNER_LETTER_SPACING_PX = 8f
/** 日期沿用页眉档字号（×1.25 后的 25px）：从独立行挪到 logo 下方后仍是弱化信息，不喧宾夺主 */
internal const val DATE_TEXT_SIZE_PX = 25f
private const val FOOTER_TOP_MARGIN_PX = 67
private const val FOOTER_LINE_HEIGHT_PX = 22
private const val FOOTER_BOTTOM_MARGIN_PX = 22
private const val FOOTER_TEXT_SIZE_PX = 15f

// ARGB_8888 估算内存超过它则降级 RGB_565。
private const val MAX_BITMAP_MEMORY_BYTES = 64L * 1024L * 1024L

// 降级到 RGB_565 后仍超过它就不渲染，直接抛可读 IOException。
// 取 100MB：真实最大用例（行动牌 941 行双栏）RGB_565 必须放行，
// 再大很多在低端机堆上必 OOM，不如提前失败。
private const val MAX_RENDERABLE_MEMORY_BYTES = 100L * 1024L * 1024L

private const val ELLIPSIS = "…"

private const val COLOR_PAGE_BG = 0xFFFAFAF9.toInt()
private const val COLOR_TABLE_BG = 0xFFFFFFFF.toInt()
private const val COLOR_ROW_STRIPE = 0xFFE2E8F0.toInt()
/** 页眉/表头/首列/标题带/页脚共用的暖灰棕（参考图 #675856） */
private const val COLOR_MUTED_TEXT = 0xFF675856.toInt()
private const val COLOR_BODY_TEXT = 0xFF111827.toInt()
private const val COLOR_BADGE_BG = 0xFF4CA1F8.toInt()
private const val COLOR_BADGE_TEXT = 0xFFFFFFFF.toInt()
private const val COLOR_BORDER_LINE = 0xFFEBEBEB.toInt()

/**
 * 纯函数：算布局。**不引用任何 android.graphics 类型**。
 *
 * 列宽对**全部行**取 max（双栏时两栏共用同一套全局列宽 ⇒ 两栏等宽）；
 * 画布宽由列宽反推，调用方不能再指定（旧 `widthPx` 参数与 weight 拉伸模型一起删除）。
 *
 * @param twoColumnThreshold 行数超过它才分双栏
 */
fun computeTableLayout(
    spec: TableSpec,
    measurer: TextMeasurer,
    twoColumnThreshold: Int,
): TableLayout {
    require(twoColumnThreshold > 0) { "twoColumnThreshold must be positive, got $twoColumnThreshold" }
    require(spec.columns.isNotEmpty()) { "columns must not be empty" }
    spec.rows.forEachIndexed { index, row ->
        require(row.size == spec.columns.size) {
            "row #$index has ${row.size} cells but columns.size=${spec.columns.size}"
        }
    }

    val columnWidth = spec.columns.mapIndexed { i, column ->
        var textWidth = measurer.measure(column.header, TEXT_SIZE_HEADER_PX)
        for (row in spec.rows) {
            val w = measurer.measure(row[i], TEXT_SIZE_BODY_PX)
            if (w > textWidth) textWidth = w
        }
        // 首列 15+10，其余 10+10（参考图 px-2 / sm:pl-3）；测量值向上取整，宁宽半像素也不压字
        val padding = if (i == 0) FIRST_CELL_LEFT_PADDING_PX + CELL_PADDING_PX else 2 * CELL_PADDING_PX
        maxOf(ceil(textWidth).toInt() + padding, column.minWidthPx)
    }
    val columnX = ArrayList<Int>(columnWidth.size)
    var x = 0
    for (w in columnWidth) {
        columnX.add(x)
        x += w
    }

    val twoColumn = spec.rows.size > twoColumnThreshold
    val columnsPerBand = if (twoColumn) 2 else 1
    val rowsPerBand = if (twoColumn) ceil(spec.rows.size / 2.0).toInt() else spec.rows.size

    val bandWidthPx = columnWidth.sum()
    // 画布宽 = 表格内容宽（双栏含两栏 + 栏间距）+ 两侧页边距与边框各 24+2
    val widthPx = bandWidthPx * columnsPerBand +
        (if (twoColumn) BAND_GAP_PX else 0) +
        2 * (PAGE_MARGIN_PX + TABLE_BORDER_PX)

    // 日期画在 logo 下方（绝对定位，与页眉左侧文字行、虚线都不重叠），不参与竖向流 ⇒ 不占高
    // 两栏各自成盒，行数可能差 1（奇数行）；高度按最高的那栏算 ⇒ 页脚位置与画布高都取 rowsPerBand
    val bandRows = minOf(rowsPerBand, spec.rows.size)
    // 竖向堆叠：页眉块 → 标题带 80 + mb 30 → 上边框 2 → 表头 50 → 数据行 → 下边框 2 → 页脚 111（67+22+22）
    val heightPx = computeHeaderBlockHeight(spec) + BANNER_HEIGHT_PX + BANNER_BOTTOM_MARGIN_PX +
        TABLE_BORDER_PX + HEADER_HEIGHT_PX + bandRows * ROW_HEIGHT_PX + TABLE_BORDER_PX +
        FOOTER_TOP_MARGIN_PX + FOOTER_LINE_HEIGHT_PX + FOOTER_BOTTOM_MARGIN_PX

    return TableLayout(
        widthPx = widthPx,
        heightPx = heightPx,
        tableWidthPx = bandWidthPx * columnsPerBand,
        bandWidthPx = bandWidthPx,
        columnX = columnX,
        columnWidth = columnWidth,
        columnsPerBand = columnsPerBand,
        rowsPerBand = rowsPerBand,
    )
}

/**
 * 页眉块高（含顶部内边距与分隔虚线占位）：等级行/胶囊为空时整行连同间距都不占。
 * 虚线底 = 页眉块底。internal 供单测按同一口径核对画布总高。
 */
internal fun computeHeaderBlockHeight(spec: TableSpec): Int {
    var y = HEADER_TOP_PADDING_PX + NICKNAME_TOP_MARGIN_PX + NICKNAME_LINE_HEIGHT_PX + NICKNAME_BOTTOM_MARGIN_PX
    if (!spec.levelText.isNullOrEmpty()) y += LEVEL_LINE_HEIGHT_PX + LEVEL_BOTTOM_MARGIN_PX
    if (spec.badges.isNotEmpty()) y += BADGE_HEIGHT_PX + BADGE_BOTTOM_MARGIN_PX + BADGE_ROW_BOTTOM_MARGIN_PX
    return y + HEADER_BOTTOM_MARGIN_PX + DIVIDER_HEIGHT_PX
}

/** 内存预算内的位图配置选择（纯算术，可 JVM 单测） */
internal fun chooseBitmapConfig(widthPx: Int, heightPx: Int): Bitmap.Config {
    val argbBytes = widthPx.toLong() * heightPx.toLong() * 4L
    return if (argbBytes > MAX_BITMAP_MEMORY_BYTES) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
}

/** 位图占用的字节数（纯算术，可 JVM 单测） */
internal fun bitmapMemoryBytes(widthPx: Int, heightPx: Int, config: Bitmap.Config): Long {
    val bytesPerPixel = if (config == Bitmap.Config.RGB_565) 2L else 4L
    return widthPx.toLong() * heightPx.toLong() * bytesPerPixel
}

/** 渲染前预算闸：降级 RGB_565 后仍超限就拒绝，不等 createBitmap 抛 OOM */
internal fun checkRenderMemoryBudget(widthPx: Int, heightPx: Int, config: Bitmap.Config) {
    val bytes = bitmapMemoryBytes(widthPx, heightPx, config)
    if (bytes > MAX_RENDERABLE_MEMORY_BYTES) {
        throw IOException(
            LocaleStrings.getOrDefault(
                R.string.error_export_memory,
                "内容过多，无法导出（约需 %1\$dMB 显存，超出安全上限）",
                bytes / (1024 * 1024),
            ),
        )
    }
}

/**
 * 截断超长文本（可 JVM 单测：测量器由参数注入，不依赖 Paint）。
 * 未超长时原样返回；超长时二分找最长的能放下「前缀 + …」的截断点。
 * 🔴 表格本体不用它（nowrap 模型下没有该截断的文本）；V29-B 删掉「最近对局」导出卡片后
 * 目前只有 TableLayoutTest 在锁这套截断语义，是否清理由集成方裁决。
 */
internal fun ellipsize(text: String, maxWidthPx: Float, measure: (String) -> Float): String {
    if (text.isEmpty() || measure(text) <= maxWidthPx) return text
    if (maxWidthPx <= 0f) return ""
    var lo = 0
    var hi = text.length - 1
    var best = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (measure(text.substring(0, mid) + ELLIPSIS) <= maxWidthPx) {
            best = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return if (best >= 0) text.substring(0, best) + ELLIPSIS else ELLIPSIS
}

/** 参考图资源（divider / section-background / logo），由调用方解码一次注入 */
data class ExportAssets(val divider: Bitmap, val sectionBg: Bitmap, val logo: Bitmap)

/** 解一次参考图资源（放 drawable-nodpi：不随屏幕密度缩放，像素即设计像素） */
fun loadExportAssets(resources: Resources): ExportAssets {
    fun decode(id: Int, name: String): Bitmap =
        BitmapFactory.decodeResource(resources, id)
            ?: throw IOException("Missing export asset: $name")
    return ExportAssets(
        divider = decode(R.drawable.export_divider, "export_divider"),
        sectionBg = decode(R.drawable.export_section_bg, "export_section_bg"),
        logo = decode(R.drawable.export_logo, "export_logo"),
    )
}

/**
 * 用 android.graphics.Canvas 直画 Bitmap。
 * 不用 GraphicsLayer.toImageBitmap：它要求整表完成 Compose 布局，
 * 超长内容会撞 GPU 纹理上限（常见 4096/8192）而失败。
 */
fun renderTableBitmap(spec: TableSpec, layout: TableLayout, assets: ExportAssets): Bitmap {
    val config = chooseBitmapConfig(layout.widthPx, layout.heightPx)
    checkRenderMemoryBudget(layout.widthPx, layout.heightPx, config)
    val bitmap = try {
        Bitmap.createBitmap(layout.widthPx, layout.heightPx, config)
    } catch (e: OutOfMemoryError) {
        val mb = layout.widthPx.toLong() * layout.heightPx * 4 / (1024 * 1024)
        throw IOException(
            "Cannot allocate bitmap ${layout.widthPx}x${layout.heightPx} (~${mb}MB): table too large",
            e,
        )
    }

    // Paint 全部建好复用，禁止行内 new
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val srcRect = Rect()
    val dstRect = RectF()
    val nicknamePaint = antialiasedTextPaint(NICKNAME_TEXT_SIZE_PX, COLOR_MUTED_TEXT).apply {
        isFakeBoldText = true
    }
    val levelPaint = antialiasedTextPaint(LEVEL_TEXT_SIZE_PX, COLOR_MUTED_TEXT)
    val badgePaint = antialiasedTextPaint(BADGE_TEXT_SIZE_PX, COLOR_BADGE_TEXT)
    // letterSpacing 是字号的比例（Paint 语义），8/31 ⇒ 每字后加 8px
    val bannerPaint = antialiasedTextPaint(BANNER_TEXT_SIZE_PX, COLOR_MUTED_TEXT).apply {
        isFakeBoldText = true
        letterSpacing = BANNER_LETTER_SPACING_PX / BANNER_TEXT_SIZE_PX
    }
    val datePaint = antialiasedTextPaint(DATE_TEXT_SIZE_PX, COLOR_MUTED_TEXT)
    val headerPaint = antialiasedTextPaint(TEXT_SIZE_HEADER_PX, COLOR_MUTED_TEXT).apply {
        isFakeBoldText = true
    }
    val indexPaint = antialiasedTextPaint(TEXT_SIZE_BODY_PX, COLOR_MUTED_TEXT).apply {
        isFakeBoldText = true
    }
    val bodyPaint = antialiasedTextPaint(TEXT_SIZE_BODY_PX, COLOR_BODY_TEXT)
    val footerPaint = antialiasedTextPaint(FOOTER_TEXT_SIZE_PX, COLOR_MUTED_TEXT)

    val canvas = Canvas(bitmap)
    val width = layout.widthPx

    // 1. 页面底色
    fillPaint.color = COLOR_PAGE_BG
    canvas.drawRect(0f, 0f, width.toFloat(), layout.heightPx.toFloat(), fillPaint)

    // 2. 页眉块，返回页眉块底（= 分隔虚线底）
    val headerBottom = drawHeaderBlock(
        canvas, spec, layout, nicknamePaint, levelPaint, badgePaint, fillPaint,
    )

    // 3. 分隔虚线（页眉最后一行 + 25 留白，左右各缩进 28，高 4，其底即页眉块底）+ 右上角 logo（225×75，右缘距画布 40）
    drawStretch(
        assets.divider, canvas, top = headerBottom - DIVIDER_HEIGHT_PX,
        left = DIVIDER_INSET_PX, right = width - DIVIDER_INSET_PX, height = DIVIDER_HEIGHT_PX,
        srcRect = srcRect, dstRect = dstRect,
    )
    val logoRight = width - LOGO_RIGHT_INSET_PX
    drawStretch(
        assets.logo, canvas, top = LOGO_TOP_PX,
        left = logoRight - LOGO_WIDTH_PX, right = logoRight,
        height = LOGO_HEIGHT_PX, srcRect = srcRect, dstRect = dstRect,
    )
    // 日期贴 logo 下方、右对齐到 logo 右缘：与 logo 竖排成同一视觉列（原来贴画布右缘会多出 40px 悬空）
    if (!spec.exportDateText.isNullOrEmpty()) {
        val dateTop = LOGO_TOP_PX + LOGO_HEIGHT_PX + DATE_BELOW_LOGO_GAP_PX
        datePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            spec.exportDateText, logoRight.toFloat(),
            dateTop + baselineInBox(datePaint, DATE_TEXT_SIZE_PX), datePaint,
        )
    }

    // 4. 分区标题带
    var y = headerBottom
    drawBanner(canvas, spec.title, bannerPaint, assets.sectionBg, y, srcRect, dstRect)
    y += BANNER_HEIGHT_PX + BANNER_BOTTOM_MARGIN_PX

    // 5. 表格：每栏一个白盒（2px 边框 + radius 6），各栏自带表头行 + 行段。
    //    上边框占掉 banner 下方 30px 留白的前 2px ⇒ 内容顶 = y + 2。
    val contentTop = y + TABLE_BORDER_PX
    repeat(layout.columnsPerBand) { band ->
        val firstRow = band * layout.rowsPerBand
        val rowCount = (minOf(firstRow + layout.rowsPerBand, spec.rows.size) - firstRow).coerceAtLeast(0)
        drawBand(
            canvas, spec, layout,
            bandLeft = PAGE_MARGIN_PX + TABLE_BORDER_PX + band * (layout.bandWidthPx + BAND_GAP_PX),
            boxTop = contentTop, boxBottom = contentTop + HEADER_HEIGHT_PX + rowCount * ROW_HEIGHT_PX,
            firstRow = firstRow, lastRowExclusive = firstRow + rowCount,
            headerPaint = headerPaint, indexPaint = indexPaint, bodyPaint = bodyPaint, fillPaint = fillPaint,
        )
    }
    val tallestBoxBottom = contentTop + HEADER_HEIGHT_PX +
        minOf(layout.rowsPerBand, spec.rows.size) * ROW_HEIGHT_PX + TABLE_BORDER_PX

    // 6. 页脚：距最高的表格盒底 67，行高 22，其后留 22
    footerPaint.textAlign = Paint.Align.CENTER
    canvas.drawText(
        LocaleStrings.getOrDefault(R.string.export_footer, "七圣召唤"), width / 2f,
        tallestBoxBottom + FOOTER_TOP_MARGIN_PX + baselineInBox(footerPaint, FOOTER_LINE_HEIGHT_PX.toFloat()),
        footerPaint,
    )
    return bitmap
}

private fun antialiasedTextPaint(textSize: Float, color: Int): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        this.color = color
    }

/** 文字在 [boxHeight] 高的盒内垂直居中时，基线相对盒顶的偏移；入参收 Float，调用点不必各自转型 */
private fun baselineInBox(paint: Paint, boxHeight: Float): Float =
    boxHeight / 2f - (paint.ascent() + paint.descent()) / 2f

/** 把 Bitmap 拉伸画进 (left, top)→(right, top+height)；尺寸非法或位图已回收时 no-op */
private fun drawStretch(
    src: Bitmap,
    canvas: Canvas,
    top: Int,
    left: Int,
    right: Int,
    height: Int,
    srcRect: Rect,
    dstRect: RectF,
) {
    if (src.isRecycled || right <= left || height <= 0 || src.width <= 0 || src.height <= 0) return
    srcRect.set(0, 0, src.width, src.height)
    dstRect.set(left.toFloat(), top.toFloat(), right.toFloat(), (top + height).toFloat())
    canvas.drawBitmap(src, srcRect, dstRect, null)
}

/**
 * 页眉块：昵称 → 等级 → 胶囊行，返回页眉块底（= 分隔虚线底）。
 * 胶囊行宽度不够时折到下一行（参考图 flex-wrap）。
 */
private fun drawHeaderBlock(
    canvas: Canvas,
    spec: TableSpec,
    layout: TableLayout,
    nicknamePaint: Paint,
    levelPaint: Paint,
    badgePaint: Paint,
    fillPaint: Paint,
): Int {
    val left = PAGE_MARGIN_PX.toFloat()
    val right = (layout.widthPx - PAGE_MARGIN_PX).toFloat()
    var y = HEADER_TOP_PADDING_PX + NICKNAME_TOP_MARGIN_PX

    nicknamePaint.textAlign = Paint.Align.LEFT
    canvas.drawText(spec.nickname, left, y + baselineInBox(nicknamePaint, NICKNAME_LINE_HEIGHT_PX.toFloat()), nicknamePaint)
    y += NICKNAME_LINE_HEIGHT_PX + NICKNAME_BOTTOM_MARGIN_PX

    spec.levelText?.takeIf { it.isNotEmpty() }?.let { level ->
        levelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(level, left, y + baselineInBox(levelPaint, LEVEL_LINE_HEIGHT_PX.toFloat()), levelPaint)
        y += LEVEL_LINE_HEIGHT_PX + LEVEL_BOTTOM_MARGIN_PX
    }

    if (spec.badges.isNotEmpty()) {
        var x = left
        var rowTop = y
        for (badge in spec.badges) {
            val badgeWidth = ceil(badgePaint.measureText(badge)).toInt() + 2 * BADGE_HORIZONTAL_PADDING_PX
            if (x > left && x + badgeWidth > right) {
                x = left
                rowTop += BADGE_HEIGHT_PX + BADGE_BOTTOM_MARGIN_PX
            }
            fillPaint.color = COLOR_BADGE_BG
            canvas.drawRoundRect(
                x, rowTop.toFloat(), x + badgeWidth, (rowTop + BADGE_HEIGHT_PX).toFloat(),
                BADGE_RADIUS_PX, BADGE_RADIUS_PX, fillPaint,
            )
            badgePaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                badge, x + badgeWidth / 2f,
                rowTop + baselineInBox(badgePaint, BADGE_HEIGHT_PX.toFloat()), badgePaint,
            )
            x += badgeWidth + BADGE_RIGHT_MARGIN_PX
        }
        y = rowTop + BADGE_HEIGHT_PX + BADGE_BOTTOM_MARGIN_PX + BADGE_ROW_BOTTOM_MARGIN_PX
    }

    // V29-B：签名框整行删除，页眉最后一行到虚线改走 HEADER_BOTTOM_MARGIN_PX 这一档留白
    return y + HEADER_BOTTOM_MARGIN_PX + DIVIDER_HEIGHT_PX
}

/** 分区标题带：section-background 拉伸铺满 (画布宽 − 48) × 80（x = 24），文字水平垂直居中 */
private fun drawBanner(
    canvas: Canvas,
    title: String,
    bannerPaint: Paint,
    sectionBg: Bitmap,
    top: Int,
    srcRect: Rect,
    dstRect: RectF,
) {
    drawStretch(
        sectionBg, canvas, top = top, left = PAGE_MARGIN_PX, right = canvas.width - PAGE_MARGIN_PX,
        height = BANNER_HEIGHT_PX, srcRect = srcRect, dstRect = dstRect,
    )
    if (title.isEmpty()) return
    // measureText 把最后一个字的字距也算进去 ⇒ 减掉半个字距再居中才是视觉居中
    val textWidth = bannerPaint.measureText(title) - BANNER_LETTER_SPACING_PX / 2f
    bannerPaint.textAlign = Paint.Align.LEFT
    canvas.drawText(
        title, (canvas.width - textWidth) / 2f, top + baselineInBox(bannerPaint, BANNER_HEIGHT_PX.toFloat()), bannerPaint,
    )
}

/**
 * 一栏：白盒 + 四边 2px 边框 + 表头行 + 斑马数据行。
 * 行间、列间一律无线（参考图 divide-y 的计算值是 0px）；斑马只覆盖表格内容宽，不铺满盒右侧空白。
 */
private fun drawBand(
    canvas: Canvas,
    spec: TableSpec,
    layout: TableLayout,
    bandLeft: Int,
    boxTop: Int,
    boxBottom: Int,
    firstRow: Int,
    lastRowExclusive: Int,
    headerPaint: Paint,
    indexPaint: Paint,
    bodyPaint: Paint,
    fillPaint: Paint,
) {
    val bandWidth = layout.bandWidthPx
    fillPaint.color = COLOR_TABLE_BG
    canvas.drawRoundRect(
        (bandLeft - TABLE_BORDER_PX).toFloat(), (boxTop - TABLE_BORDER_PX).toFloat(),
        (bandLeft + bandWidth + TABLE_BORDER_PX).toFloat(), (boxBottom + TABLE_BORDER_PX).toFloat(),
        TABLE_CORNER_RADIUS_PX, TABLE_CORNER_RADIUS_PX, fillPaint,
    )
    // 四边描边用 4 个矩形而非 Paint.Style.STROKE：RGB_565 下描边抗锯齿会发灰
    fillPaint.color = COLOR_BORDER_LINE
    val outerLeft = (bandLeft - TABLE_BORDER_PX).toFloat()
    val outerRight = (bandLeft + bandWidth + TABLE_BORDER_PX).toFloat()
    canvas.drawRect(outerLeft, (boxTop - TABLE_BORDER_PX).toFloat(), outerRight, boxTop.toFloat(), fillPaint)
    canvas.drawRect(outerLeft, boxBottom.toFloat(), outerRight, (boxBottom + TABLE_BORDER_PX).toFloat(), fillPaint)
    canvas.drawRect(outerLeft, boxTop.toFloat(), bandLeft.toFloat(), boxBottom.toFloat(), fillPaint)
    canvas.drawRect((bandLeft + bandWidth).toFloat(), boxTop.toFloat(), outerRight, boxBottom.toFloat(), fillPaint)

    // 表头行：全部居中（含首列 #）
    spec.columns.forEachIndexed { i, column ->
        drawCellText(
            canvas, headerPaint, column.header, bandLeft + layout.columnX[i], boxTop,
            layout.columnWidth[i], if (i == 0) CellAlign.CENTER else column.align, HEADER_HEIGHT_PX,
        )
    }

    val rowsStart = boxTop + HEADER_HEIGHT_PX
    var row = firstRow
    while (row < lastRowExclusive) {
        val line = row - firstRow
        val rowTop = rowsStart + line * ROW_HEIGHT_PX
        // 参考图 tbody tr:nth-child(odd) ⇒ 本栏第 1、3、5…个数据行填斑马色
        if (line % 2 == 0) {
            fillPaint.color = COLOR_ROW_STRIPE
            canvas.drawRect(
                bandLeft.toFloat(), rowTop.toFloat(),
                (bandLeft + bandWidth).toFloat(), (rowTop + ROW_HEIGHT_PX).toFloat(), fillPaint,
            )
        }
        val cells = spec.rows[row]
        cells.forEachIndexed { i, cell ->
            // nowrap：列宽已按该列最宽文本算出 ⇒ 单行直画，既不换行也不省略
            drawCellText(
                canvas, if (i == 0) indexPaint else bodyPaint, cell, bandLeft + layout.columnX[i],
                rowTop, layout.columnWidth[i], spec.columns[i].align, ROW_HEIGHT_PX,
            )
        }
        row++
    }
}

private fun drawCellText(
    canvas: Canvas,
    paint: Paint,
    text: String,
    columnLeft: Int,
    rowTop: Int,
    columnWidth: Int,
    align: CellAlign,
    rowHeight: Int,
) {
    if (text.isEmpty()) return
    val drawX = when (align) {
        // START 只用于首列 ⇒ 左内边距恒取 15（参考图 sm:pl-3）
        CellAlign.START -> {
            paint.textAlign = Paint.Align.LEFT
            (columnLeft + FIRST_CELL_LEFT_PADDING_PX).toFloat()
        }
        CellAlign.CENTER -> {
            paint.textAlign = Paint.Align.CENTER
            columnLeft + columnWidth / 2f
        }
        CellAlign.END -> {
            paint.textAlign = Paint.Align.RIGHT
            (columnLeft + columnWidth - CELL_PADDING_PX).toFloat()
        }
    }
    canvas.drawText(text, drawX, rowTop + baselineInBox(paint, rowHeight.toFloat()), paint)
}
