package com.gigi.tcg.ui.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.gigi.tcg.R
import com.gigi.tcg.i18n.LocaleStrings
import java.io.IOException
import kotlin.math.ceil

// 版式照抄参考导出图的实测规格（.task/progress/R3-findings.md）：
// 数据行/表头行各 50px、正文 20px、表头 22px（暖灰棕加粗）、分区标题带 64px 居中深棕字、
// 单元格左右内边距 12px、表格左右外边距各 26px、纯斑马纹（第 1 个数据行就是斑马色），
// 全表唯一的线是「表头上方 2px #EBEBEB」——行间/列间/双栏之间一律没有分隔线。
// 字号直接按 px 定（不随屏幕密度），因为分享出去的图规格必须跨设备统一。
// internal 供单测做「表头能否放进列 / 名称列容得下几个汉字」的字宽断言（防回归）。
internal const val TEXT_SIZE_BODY_PX = 20f
internal const val TEXT_SIZE_HEADER_PX = 22f
private const val TEXT_SIZE_TITLE_PX = 24f

// 数据单元格换行：名称列两行优先，超两行才省略（参考图是单行 + …，本应用在 670px 栏宽下靠两行保住长卡名）。
// 行高占用 = 字号 × 该系数 × 行数：20f × 1.18 × 2 = 47.2px ≤ 50px 行高（两行仍装得进）。
internal const val MAX_CELL_LINES = 2
private const val CELL_LINE_HEIGHT_FACTOR = 1.18f

// 行 / 表头 / 标题带 / 留白（R3 实测：数据行与表头行均 50px、分区标题带 64px）
internal const val ROW_HEIGHT_PX = 50
internal const val HEADER_HEIGHT_PX = 50
internal const val TITLE_LINE_HEIGHT_PX = 64
// 副标题/徽章行高按 20px 正文字号重算：行高 = 字号 × 2（旧 52 是 26px 字号时代的比例）
internal const val SUBTITLE_LINE_HEIGHT_PX = 40
internal const val BADGES_LINE_HEIGHT_PX = 40
// 徽章胶囊高 36px（R3 实测 pill y124–159），在 40px 行里上下各留 2px
private const val BADGE_PILL_HEIGHT_PX = 36
// 徽章行底 → 表头上方细线 ≈ 参考图 banner 底(307) → 线(332) 的 25px
internal const val HEADER_TOP_GAP_PX = 28
internal const val BOTTOM_PADDING_PX = 32

internal const val MIN_COLUMN_WIDTH_PX = 40
internal const val CELL_PADDING_PX = 12
/** 表格左右外边距（对称，R3 实测 ≈26px）；由「压缩表格」得到，不是加宽画布 */
internal const val PAGE_MARGIN_PX = 26
/** 表头上方唯一那条细线（R3 实测 2px #EBEBEB） */
private const val BORDER_LINE_PX = 2

// ARGB_8888 估算内存超过它则降级 RGB_565。
// 角色牌 822×7604×4 ≈ 23.8MB 保 ARGB；行动牌 1444×23804×4 ≈ 137MB 降级 RGB_565。
private const val MAX_BITMAP_MEMORY_BYTES = 64L * 1024L * 1024L

// 降级到 RGB_565 后仍超过它就不渲染，直接抛可读 IOException。
// 取 100MB：真实最大用例（行动牌 941 行双栏 1444px）RGB_565 = 1444×23804×2 ≈ 68.7MB 必须放行，
// 再大很多在低端机堆上必 OOM，不如提前失败。
private const val MAX_RENDERABLE_MEMORY_BYTES = 100L * 1024L * 1024L

private const val ELLIPSIS = "…"

private const val COLOR_PAGE_BG = 0xFFFAFAF8.toInt()
private const val COLOR_TABLE_BG = 0xFFFFFFFF.toInt()
private const val COLOR_ROW_STRIPE = 0xFFE1E8F0.toInt()
/** 表头文字（暖灰棕，R3 实测 #665856）；副标题与右上角导出日期（小灰字）同色 */
private const val COLOR_MUTED_TEXT = 0xFF665856.toInt()
private const val COLOR_BODY_TEXT = 0xFF1B1821.toInt()
private const val COLOR_TITLE_BG = 0xFFF0E5D3.toInt()
private const val COLOR_TITLE_TEXT = 0xFF6A5750.toInt()
private const val COLOR_BADGE_BG = 0xFF4CA2F9.toInt()
private const val COLOR_BADGE_TEXT = 0xFFFFFFFF.toInt()
private const val COLOR_BORDER_LINE = 0xFFEBEBEB.toInt()

/** 单元格水平对齐：# 列 START，其余列（含名称列）CENTER */
enum class CellAlign { START, CENTER, END }

/** 列定义 */
data class TableColumn(
    val header: String,
    /** 相对权重（名称列给大值，数值列给小值）；用于按比例分配宽度 */
    val weight: Float,
    val align: CellAlign,
)

/** 一张表的完整内容 */
data class TableSpec(
    val title: String,
    /** 顶部信息行（如 "Clin - 110526730"），可空 */
    val subtitle: String?,
    /** 徽章行（如 "共进行 3493 场游戏"），可空 */
    val badges: List<String>,
    val columns: List<TableColumn>,
    /** 每行的单元格文本，行数 = rows.size，每行长度必须 == columns.size */
    val rows: List<List<String>>,
    /** 导出日期（yyyy-MM-dd），画在右上角（参考图放 logo 的位置）；null 不画 */
    val exportDate: String? = null,
)

/** 纯计算的布局结果（不依赖 android.graphics，可 JVM 单测） */
data class TableLayout(
    val widthPx: Int,
    val heightPx: Int,
    /** 每列左边缘 x（**相对本栏左边缘**） */
    val columnX: List<Int>,
    /** 每列宽度（总和精确 == bandWidthPx） */
    val columnWidth: List<Int>,
    /** 单栏可用宽 = widthPx / 栏数 − 2 × PAGE_MARGIN_PX（表格只占槽位中间，两侧留页面底色） */
    val bandWidthPx: Int,
    val headerHeightPx: Int,
    val rowHeightPx: Int,
    /** 表头起始 y（= 标题区 + 与表头之间的留白） */
    val titleHeightPx: Int,
    val columnsPerBand: Int,
    /** 每栏行数（单栏 = rows.size；双栏 = ceil(rows.size/2)） */
    val rowsPerBand: Int,
)

/**
 * 纯函数：算布局。**不引用任何 android.graphics 类型**。
 *
 * 双栏契约：`columnX` / `columnWidth` **只描述第一栏**（相对本栏左边缘；列宽和 = [bandWidthPx]），
 * 第 band 栏的 x 偏移由 [renderTableBitmap] 加 `PAGE_MARGIN_PX + band × (widthPx / columnsPerBand)`，
 * [TableLayout] 不额外存字段。
 *
 * @param widthPx 目标总宽（角色牌 822 / 行动牌 1444）
 * @param twoColumnThreshold 行数超过它才分双栏
 */
fun computeTableLayout(
    spec: TableSpec,
    widthPx: Int,
    twoColumnThreshold: Int,
): TableLayout {
    require(widthPx > 0) { "widthPx must be positive, got $widthPx" }
    require(spec.columns.isNotEmpty()) { "columns must not be empty" }
    require(spec.columns.sumOf { it.weight.toDouble() } > 0.0) {
        "sum of column weights must be positive"
    }
    spec.rows.forEachIndexed { index, row ->
        require(row.size == spec.columns.size) {
            "row #$index has ${row.size} cells but columns.size=${spec.columns.size}"
        }
    }

    val twoColumn = spec.rows.size > twoColumnThreshold
    val columnsPerBand = if (twoColumn) 2 else 1
    val rowsPerBand = if (twoColumn) ceil(spec.rows.size / 2.0).toInt() else spec.rows.size

    // 外边距是「压缩表格」出来的：每栏槽位 = 画布宽 / 栏数，表格只占槽位减去两侧 26px 的部分。
    // 双栏 1444px ⇒ 每栏槽位 722，表宽 670；单栏 822px ⇒ 表宽 770。
    val bandSlotPx = widthPx / columnsPerBand
    val bandWidthPx = bandSlotPx - 2 * PAGE_MARGIN_PX
    require(bandWidthPx > 0) {
        "widthPx=$widthPx too small for $columnsPerBand band(s) with ${PAGE_MARGIN_PX}px page margins"
    }

    val columnWidth = distributeColumnWidths(spec.columns, bandWidthPx)
    val columnX = ArrayList<Int>(columnWidth.size)
    var x = 0
    for (w in columnWidth) {
        columnX.add(x)
        x += w
    }

    var titleHeight = TITLE_LINE_HEIGHT_PX
    if (spec.subtitle != null || spec.exportDate != null) titleHeight += SUBTITLE_LINE_HEIGHT_PX
    if (spec.badges.isNotEmpty()) titleHeight += BADGES_LINE_HEIGHT_PX
    titleHeight += HEADER_TOP_GAP_PX   // 标题区与表头之间的留白（表头起点）

    val heightPx = titleHeight + HEADER_HEIGHT_PX + rowsPerBand * ROW_HEIGHT_PX + BOTTOM_PADDING_PX

    return TableLayout(
        widthPx = widthPx,
        heightPx = heightPx,
        columnX = columnX,
        columnWidth = columnWidth,
        bandWidthPx = bandWidthPx,
        headerHeightPx = HEADER_HEIGHT_PX,
        rowHeightPx = ROW_HEIGHT_PX,
        titleHeightPx = titleHeight,
        columnsPerBand = columnsPerBand,
        rowsPerBand = rowsPerBand,
    )
}

/**
 * 按 weight 比例分配列宽，总和精确 == totalWidthPx（= 栏宽）。
 * 每列至少 [MIN_COLUMN_WIDTH_PX]（在 totalWidthPx 装得下 n×最小宽时）；
 * 舍入误差全部由最后一列吸收。
 */
private fun distributeColumnWidths(columns: List<TableColumn>, totalWidthPx: Int): List<Int> {
    val n = columns.size
    val totalWeight = columns.sumOf { it.weight.toDouble() }
    val widths = IntArray(n)

    val minFits = totalWidthPx.toLong() >= MIN_COLUMN_WIDTH_PX.toLong() * n
    if (!minFits) {
        // 装不下最小宽保护：纯比例分配，最后一列吸收误差
        var used = 0
        for (i in 0 until n - 1) {
            val w = (columns[i].weight.toDouble() / totalWeight * totalWidthPx)
                .toInt()
                .coerceAtLeast(0)
            widths[i] = w
            used += w
        }
        widths[n - 1] = totalWidthPx - used
        return widths.toList()
    }

    val slack = totalWidthPx - MIN_COLUMN_WIDTH_PX * n
    var used = 0
    for (i in 0 until n - 1) {
        val share = (columns[i].weight.toDouble() / totalWeight * slack)
            .toInt()
            .coerceAtLeast(0)
        widths[i] = MIN_COLUMN_WIDTH_PX + share
        used += widths[i]
    }
    widths[n - 1] = totalWidthPx - used
    return widths.toList()
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

/**
 * 纯函数：把文本切成 <= maxLines 行，能完整放下就原样返回（不省略）。
 * 放不下时在第 maxLines 行末尾加省略号，并二分找最长的能放下的前缀。
 * measure 注入，不依赖 Paint。
 *
 * 贪心按字符断行（CJK 无空格可依赖）；文本自带 `\n` 先按硬换行切开，每段再各自贪心，
 * 避免脏数据把行高算歪。
 */
internal fun wrapCellText(
    text: String,
    maxWidthPx: Float,
    measure: (String) -> Float,
    maxLines: Int = MAX_CELL_LINES,
): List<String> {
    if (maxWidthPx <= 0f || text.isEmpty()) return listOf("")
    val lines = ArrayList<String>()
    var segStart = 0
    while (segStart <= text.length) {
        val newline = text.indexOf('\n', segStart)
        val segEnd = if (newline < 0) text.length else newline
        var cursor = segStart
        while (cursor < segEnd) {
            var end = cursor + 1
            // 单字就超宽时也先落这个字，保证推进（否则死循环）
            while (end < segEnd && measure(text.substring(cursor, end + 1)) <= maxWidthPx) end++
            lines.add(text.substring(cursor, end))
            cursor = end
        }
        if (newline < 0) break
        segStart = newline + 1
        if (segStart == text.length) {
            lines.add("") // 结尾换行：留一空行，保持行数口径
            break
        }
    }
    if (lines.isEmpty()) return listOf("")
    if (lines.size <= maxLines) return lines
    val kept = lines.take(maxLines - 1)
    val rest = lines.subList(maxLines - 1, lines.size).joinToString("")
    return kept + ellipsize(rest, maxWidthPx, measure)
}

/**
 * 用 android.graphics.Canvas 直画 Bitmap。
 * 不用 GraphicsLayer.toImageBitmap：它要求整表完成 Compose 布局，
 * 超长内容会撞 GPU 纹理上限（常见 4096/8192）而失败。
 */
fun renderTableBitmap(spec: TableSpec, layout: TableLayout): Bitmap {
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
    val fillPaint = Paint().apply { style = Paint.Style.FILL }
    val titlePaint = antialiasedTextPaint(TEXT_SIZE_TITLE_PX, COLOR_TITLE_TEXT).apply {
        isFakeBoldText = true
    }
    val subtitlePaint = antialiasedTextPaint(TEXT_SIZE_BODY_PX, COLOR_MUTED_TEXT)
    val exportDatePaint = antialiasedTextPaint(TEXT_SIZE_BODY_PX, COLOR_MUTED_TEXT)
    val badgePaint = antialiasedTextPaint(TEXT_SIZE_BODY_PX, COLOR_BADGE_TEXT)
    val headerPaint = antialiasedTextPaint(TEXT_SIZE_HEADER_PX, COLOR_MUTED_TEXT).apply {
        isFakeBoldText = true
    }
    val bodyPaint = antialiasedTextPaint(TEXT_SIZE_BODY_PX, COLOR_BODY_TEXT)

    val canvas = Canvas(bitmap)
    val width = layout.widthPx
    val contentLeft = PAGE_MARGIN_PX
    val contentWidth = width - 2 * PAGE_MARGIN_PX

    // 1. 页面底色（#FAFAF8；表格区域稍后压白色数据带）
    fillPaint.color = COLOR_PAGE_BG
    canvas.drawRect(0f, 0f, width.toFloat(), layout.heightPx.toFloat(), fillPaint)

    // 2. 分区标题带：奶油色 #F0E5D3、64px、与表格同宽（R3 实测 x24–797 ≈ 26px 边距，两侧纹样装饰不抄）
    var y = 0
    fillPaint.color = COLOR_TITLE_BG
    canvas.drawRect(
        contentLeft.toFloat(), y.toFloat(),
        (contentLeft + contentWidth).toFloat(), (y + TITLE_LINE_HEIGHT_PX).toFloat(),
        fillPaint,
    )
    drawText(canvas, ellipsize(spec.title, contentWidth.toFloat(), titlePaint::measureText), titlePaint,
        x = 0f, rowTop = y, rowHeight = TITLE_LINE_HEIGHT_PX, align = CellAlign.CENTER, widthPx = width)
    y += TITLE_LINE_HEIGHT_PX

    // 3. 副标题（左）与导出日期（右，参考图放 logo 的位置）同一水平带
    if (spec.subtitle != null || spec.exportDate != null) {
        val dateText = spec.exportDate?.let {
            ellipsize(it, (contentWidth / 2).toFloat(), exportDatePaint::measureText)
        }
        val dateWidth = dateText?.let { exportDatePaint.measureText(it) } ?: 0f
        if (spec.subtitle != null) {
            val subtitleMax = contentWidth - (if (dateText != null) dateWidth + CELL_PADDING_PX else 0f)
            drawText(canvas, ellipsize(spec.subtitle, subtitleMax, subtitlePaint::measureText), subtitlePaint,
                x = contentLeft.toFloat(), rowTop = y, rowHeight = SUBTITLE_LINE_HEIGHT_PX,
                align = CellAlign.START, widthPx = contentWidth, padPx = PAGE_MARGIN_PX)
        }
        if (dateText != null) {
            // 右缘对齐表格右边界：x=0 起算整幅画布、pad 取页边距
            drawText(canvas, dateText, exportDatePaint,
                x = 0f, rowTop = y, rowHeight = SUBTITLE_LINE_HEIGHT_PX,
                align = CellAlign.END, widthPx = width, padPx = PAGE_MARGIN_PX)
        }
        y += SUBTITLE_LINE_HEIGHT_PX
    }
    if (spec.badges.isNotEmpty()) {
        drawBadges(canvas, spec.badges, badgePaint, fillPaint, top = y)
        y += BADGES_LINE_HEIGHT_PX
    }

    // 4. 表头 + 数据行；双栏时右栏偏移 = 页边距 + 一个栏槽位（见 computeTableLayout 契约）
    val bandSlotPx = width / layout.columnsPerBand
    repeat(layout.columnsPerBand) { band ->
        val offsetX = PAGE_MARGIN_PX + band * bandSlotPx
        val firstRow = band * layout.rowsPerBand
        val lastRowExclusive =
            if (layout.columnsPerBand == 2 && band == 1) minOf(firstRow + layout.rowsPerBand, spec.rows.size)
            else firstRow + layout.rowsPerBand
        drawBand(canvas, spec, layout, offsetX, firstRow, lastRowExclusive,
            headerPaint, bodyPaint, fillPaint)
    }
    return bitmap
}

private fun antialiasedTextPaint(textSize: Float, color: Int): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        this.color = color
    }

private fun drawBand(
    canvas: Canvas,
    spec: TableSpec,
    layout: TableLayout,
    offsetX: Int,
    firstRow: Int,
    lastRowExclusive: Int,
    headerPaint: Paint,
    bodyPaint: Paint,
    fillPaint: Paint,
) {
    val bandWidthPx = layout.bandWidthPx
    val tableTop = layout.titleHeightPx
    val rowsStart = tableTop + layout.headerHeightPx
    val rowsHeight = (lastRowExclusive - firstRow) * layout.rowHeightPx

    // 表格白底（表头行 + 本栏全部数据行）：压在页面底色上；双栏之间露出 52px 页面底色，无分隔线
    fillPaint.color = COLOR_TABLE_BG
    canvas.drawRect(
        offsetX.toFloat(), tableTop.toFloat(),
        (offsetX + bandWidthPx).toFloat(), (rowsStart + rowsHeight).toFloat(),
        fillPaint,
    )

    // 全表唯一的线：表头上方 2px #EBEBEB（表头下方/行间/列间一律无线）
    fillPaint.color = COLOR_BORDER_LINE
    canvas.drawRect(
        offsetX.toFloat(), (tableTop - BORDER_LINE_PX).toFloat(),
        (offsetX + bandWidthPx).toFloat(), tableTop.toFloat(),
        fillPaint,
    )

    spec.columns.forEachIndexed { i, column ->
        val w = layout.columnWidth[i]
        drawText(
            canvas,
            ellipsize(column.header, (w - 2 * CELL_PADDING_PX).toFloat(), headerPaint::measureText),
            headerPaint, (offsetX + layout.columnX[i]).toFloat(), tableTop, layout.headerHeightPx,
            column.align, w,
        )
    }

    var row = firstRow
    var line = 0
    while (row < lastRowExclusive) {
        val rowTop = rowsStart + line * layout.rowHeightPx
        // 参考图第 1 个数据行就是斑马色 ⇒ 行序号 0/2/4…（偶数）填 #E1E8F0
        if (line % 2 == 0) {
            fillPaint.color = COLOR_ROW_STRIPE
            canvas.drawRect(
                offsetX.toFloat(), rowTop.toFloat(),
                (offsetX + bandWidthPx).toFloat(), (rowTop + layout.rowHeightPx).toFloat(),
                fillPaint,
            )
        }
        val cells = spec.rows[row]
        cells.forEachIndexed { i, cell ->
            val w = layout.columnWidth[i]
            // 数据格两行优先：能完整放下就不省略，超过两行才在末行加省略号
            drawTextLines(
                canvas,
                wrapCellText(cell, (w - 2 * CELL_PADDING_PX).toFloat(), bodyPaint::measureText),
                bodyPaint, (offsetX + layout.columnX[i]).toFloat(), rowTop, layout.rowHeightPx,
                spec.columns[i].align, w,
            )
        }
        row++
        line++
    }
}

private fun drawBadges(
    canvas: Canvas,
    badges: List<String>,
    badgePaint: Paint,
    fillPaint: Paint,
    top: Int,
) {
    val contentWidth = canvas.width - 2 * PAGE_MARGIN_PX
    val maxBadgeWidth = contentWidth / 2
    var x = PAGE_MARGIN_PX.toFloat()
    // 药丸 36px（R3 实测 pill 高），行高 40 上下各留 2px 对称居中
    val pillTop = top + (BADGES_LINE_HEIGHT_PX - BADGE_PILL_HEIGHT_PX) / 2f
    for (badge in badges) {
        val text = ellipsize(badge, (maxBadgeWidth - 2 * CELL_PADDING_PX).toFloat(), badgePaint::measureText)
        val textWidth = badgePaint.measureText(text)
        val pillWidth = textWidth + 2 * CELL_PADDING_PX
        if (x + pillWidth > canvas.width - PAGE_MARGIN_PX) break
        fillPaint.color = COLOR_BADGE_BG
        canvas.drawRoundRect(
            x, pillTop, x + pillWidth, pillTop + BADGE_PILL_HEIGHT_PX,
            BADGE_PILL_HEIGHT_PX / 2f, BADGE_PILL_HEIGHT_PX / 2f, fillPaint,
        )
        val baseline = pillTop + BADGE_PILL_HEIGHT_PX / 2f -
            (badgePaint.ascent() + badgePaint.descent()) / 2f
        badgePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(text, x + CELL_PADDING_PX, baseline, badgePaint)
        x += pillWidth + CELL_PADDING_PX
    }
}

private fun drawText(
    canvas: Canvas,
    text: String,
    paint: Paint,
    x: Float,
    rowTop: Int,
    rowHeight: Int,
    align: CellAlign,
    widthPx: Int,
    padPx: Int = CELL_PADDING_PX,
) = drawTextLines(canvas, listOf(text), paint, x, rowTop, rowHeight, align, widthPx, padPx)

/**
 * 画一格的所有文字块，整体在行高内垂直居中。
 * 单行时基线公式与历史实现逐字相同（居中 - (ascent+descent)/2），多行时按 lineHeight 堆叠后整体居中。
 * START/END 的 x 含 padPx 内边距；CENTER 以「x + width/2」为基准（内边距由居中对齐吸收）。
 */
private fun drawTextLines(
    canvas: Canvas,
    lines: List<String>,
    paint: Paint,
    x: Float,
    rowTop: Int,
    rowHeight: Int,
    align: CellAlign,
    widthPx: Int,
    padPx: Int = CELL_PADDING_PX,
) {
    if (lines.isEmpty()) return
    val lineHeight = paint.textSize * CELL_LINE_HEIGHT_FACTOR
    val blockHeight = lineHeight * lines.size
    val blockTop = rowTop + rowHeight / 2f - blockHeight / 2f
    val centering = -(paint.ascent() + paint.descent()) / 2f
    paint.textAlign = when (align) {
        CellAlign.START -> Paint.Align.LEFT
        CellAlign.CENTER -> Paint.Align.CENTER
        CellAlign.END -> Paint.Align.RIGHT
    }
    val drawX = when (align) {
        CellAlign.START -> x + padPx
        CellAlign.CENTER -> x + widthPx / 2f
        CellAlign.END -> x + widthPx - padPx
    }
    lines.forEachIndexed { i, line ->
        if (line.isEmpty()) return@forEachIndexed // drawText 对空串无意义
        canvas.drawText(line, drawX, blockTop + lineHeight * i + lineHeight / 2f + centering, paint)
    }
}
