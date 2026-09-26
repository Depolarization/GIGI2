package com.gigi.tcg.ui.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.IOException
import kotlin.math.ceil

// 字号：正文 26px、表头 26px、标题 48px（1600px 定宽图上直接按 px 定，不随密度）。
// internal 供单测做"表头能否放进列"的字宽断言（V8H：防回归）。
// 表头字号维持 26f：加到 28f 会让行动牌双栏 800px 下「使用次数」列余量从 +9.6% 跌到 +1.8%，
// 破掉 V8H 固化的 ≥8% 余量约束；表头清晰度靠加大行高留白达成（V8I-B）。
// 正文 28f → 26f（V9-C）：双栏名称列 avail 只有 228px（slack = 800-40*5 = 600，不是 720），
// 28f 时每行 7 字、两行 14 字 < 15 字仍在两行内放不下；26f 每行 8 字、两行 16 字才满足
// "行动牌名 ≤15 字不省略"，且 26*1.18*2 = 61.4px 仍装得进 64px 行高。
internal const val TEXT_SIZE_BODY_PX = 26f
internal const val TEXT_SIZE_HEADER_PX = 26f
private const val TEXT_SIZE_TITLE_PX = 48f

// 数据单元格换行：名称列两行优先，超两行才省略（V9-C）。行高占用 = 字号 × 该系数 × 行数。
internal const val MAX_CELL_LINES = 2
private const val CELL_LINE_HEIGHT_FACTOR = 1.18f

// 行/头/留白（行高 64px 为跨棒约定）
// V9-C 表头留白重排：标题区与表头行拉开（28→40），表头行本身收到 84 并把文字下压对齐数据行，
// 副标题行 48→52 让它与徽章行不贴在一起。
internal const val ROW_HEIGHT_PX = 64
internal const val HEADER_HEIGHT_PX = 84
internal const val TITLE_LINE_HEIGHT_PX = 88
internal const val SUBTITLE_LINE_HEIGHT_PX = 52
internal const val BADGES_LINE_HEIGHT_PX = 52
internal const val HEADER_TOP_GAP_PX = 40
internal const val BOTTOM_PADDING_PX = 64

internal const val MIN_COLUMN_WIDTH_PX = 40
internal const val CELL_PADDING_PX = 20

// ARGB_8888 估算内存超过它则降级 RGB_565
private const val MAX_BITMAP_MEMORY_BYTES = 64L * 1024L * 1024L

// 降级到 RGB_565 后仍超过它就不渲染，直接抛可读 IOException。
// 取 100MB：真实最大用例（行动牌 941 行双栏 1600px）RGB_565 ≈ 93MB 必须放行，
// 再大（约 1030 行以上）在低端机堆上必 OOM，不如提前失败。
private const val MAX_RENDERABLE_MEMORY_BYTES = 100L * 1024L * 1024L

private const val ELLIPSIS = "…"

private const val COLOR_BACKGROUND = 0xFFFFFFFF.toInt()
private const val COLOR_TITLE_BG = 0xFF8A6A16.toInt()
private const val COLOR_TITLE_TEXT = 0xFFFFFFFF.toInt()
private const val COLOR_SUBTITLE_TEXT = 0xFF555555.toInt()
private const val COLOR_BADGE_BG = 0xFFF0EEE6.toInt()
private const val COLOR_BADGE_TEXT = 0xFF6B5D2F.toInt()
private const val COLOR_HEADER_BG = 0xFFF7F5EF.toInt()
private const val COLOR_HEADER_TEXT = 0xFF333333.toInt()
private const val COLOR_BODY_TEXT = 0xFF222222.toInt()
private const val COLOR_ROW_STRIPE = 0xFFFAF9F6.toInt()
private const val COLOR_DIVIDER = 0xFFE5E2D8.toInt()

/** 列定义 */
data class TableColumn(
    val header: String,
    /** 相对权重（名称列给大值，数值列给小值）；用于按比例分配宽度 */
    val weight: Float,
    val alignEnd: Boolean,
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
)

/** 纯计算的布局结果（不依赖 android.graphics，可 JVM 单测） */
data class TableLayout(
    val widthPx: Int,
    val heightPx: Int,
    /** 每列左边缘 x */
    val columnX: List<Int>,
    /** 每列宽度 */
    val columnWidth: List<Int>,
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
 * 双栏契约：`columnX` / `columnWidth` **只描述第一栏**（带宽 = widthPx / 2 内的分配），
 * 第二栏的 x 偏移由 [renderTableBitmap] 加 `widthPx / 2`，[TableLayout] 不额外存字段。
 *
 * @param widthPx 目标总宽（固定 1600）
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
    val bandWidthPx = if (twoColumn) widthPx / 2 else widthPx

    val columnWidth = distributeColumnWidths(spec.columns, bandWidthPx)
    val columnX = ArrayList<Int>(columnWidth.size)
    var x = 0
    for (w in columnWidth) {
        columnX.add(x)
        x += w
    }

    var titleHeight = TITLE_LINE_HEIGHT_PX
    if (spec.subtitle != null) titleHeight += SUBTITLE_LINE_HEIGHT_PX
    if (spec.badges.isNotEmpty()) titleHeight += BADGES_LINE_HEIGHT_PX
    titleHeight += HEADER_TOP_GAP_PX   // 标题区与表头之间的留白（表头起点）

    val heightPx = titleHeight + HEADER_HEIGHT_PX + rowsPerBand * ROW_HEIGHT_PX + BOTTOM_PADDING_PX

    return TableLayout(
        widthPx = widthPx,
        heightPx = heightPx,
        columnX = columnX,
        columnWidth = columnWidth,
        headerHeightPx = HEADER_HEIGHT_PX,
        rowHeightPx = ROW_HEIGHT_PX,
        titleHeightPx = titleHeight,
        columnsPerBand = columnsPerBand,
        rowsPerBand = rowsPerBand,
    )
}

/**
 * 按 weight 比例分配列宽，总和精确 == totalWidthPx。
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
            "内容过多，无法导出（约需 ${bytes / (1024 * 1024)}MB 显存，超出安全上限）",
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
    val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    val titlePaint = antialiasedTextPaint(TEXT_SIZE_TITLE_PX, COLOR_TITLE_TEXT).apply {
        isFakeBoldText = true
    }
    val subtitlePaint = antialiasedTextPaint(TEXT_SIZE_BODY_PX, COLOR_SUBTITLE_TEXT)
    val badgePaint = antialiasedTextPaint(TEXT_SIZE_BODY_PX, COLOR_BADGE_TEXT)
    val headerPaint = antialiasedTextPaint(TEXT_SIZE_HEADER_PX, COLOR_HEADER_TEXT).apply {
        isFakeBoldText = true
    }
    val bodyPaint = antialiasedTextPaint(TEXT_SIZE_BODY_PX, COLOR_BODY_TEXT)

    val canvas = Canvas(bitmap)
    val width = layout.widthPx

    // 1. 背景
    fillPaint.color = COLOR_BACKGROUND
    canvas.drawRect(0f, 0f, width.toFloat(), layout.heightPx.toFloat(), fillPaint)

    // 2. 标题区
    var y = 0
    fillPaint.color = COLOR_TITLE_BG
    canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + TITLE_LINE_HEIGHT_PX).toFloat(), fillPaint)
    drawText(canvas, ellipsize(spec.title, (width - 2 * CELL_PADDING_PX).toFloat(), titlePaint::measureText), titlePaint,
        x = 0f, rowTop = y, rowHeight = TITLE_LINE_HEIGHT_PX, alignEnd = false)
    y += TITLE_LINE_HEIGHT_PX
    if (spec.subtitle != null) {
        drawText(canvas, ellipsize(spec.subtitle, (width - 2 * CELL_PADDING_PX).toFloat(), subtitlePaint::measureText),
            subtitlePaint, x = 0f, rowTop = y, rowHeight = SUBTITLE_LINE_HEIGHT_PX, alignEnd = false)
        y += SUBTITLE_LINE_HEIGHT_PX
    }
    if (spec.badges.isNotEmpty()) {
        drawBadges(canvas, spec.badges, badgePaint, fillPaint, top = y)
        y += BADGES_LINE_HEIGHT_PX
    }

    // 3. 表头 + 数据行；双栏时右栏偏移 widthPx/2（见 computeTableLayout 契约）
    val bandWidth = if (layout.columnsPerBand == 2) width / 2 else width
    repeat(layout.columnsPerBand) { band ->
        val offsetX = band * (width / 2)
        val firstRow = band * layout.rowsPerBand
        val lastRowExclusive =
            if (layout.columnsPerBand == 2 && band == 1) minOf(firstRow + layout.rowsPerBand, spec.rows.size)
            else firstRow + layout.rowsPerBand
        drawBand(canvas, spec, layout, bandWidth, offsetX, firstRow, lastRowExclusive,
            headerPaint, bodyPaint, fillPaint, strokePaint)
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
    bandWidthPx: Int,
    offsetX: Int,
    firstRow: Int,
    lastRowExclusive: Int,
    headerPaint: Paint,
    bodyPaint: Paint,
    fillPaint: Paint,
    strokePaint: Paint,
) {
    val headerTop = layout.titleHeightPx
    fillPaint.color = COLOR_HEADER_BG
    canvas.drawRect(
        offsetX.toFloat(), headerTop.toFloat(),
        (offsetX + bandWidthPx).toFloat(), (headerTop + layout.headerHeightPx).toFloat(),
        fillPaint,
    )
    strokePaint.color = COLOR_DIVIDER
    canvas.drawLine(
        offsetX.toFloat(), (headerTop + layout.headerHeightPx).toFloat(),
        (offsetX + bandWidthPx).toFloat(), (headerTop + layout.headerHeightPx).toFloat(),
        strokePaint,
    )
    spec.columns.forEachIndexed { i, column ->
        val w = layout.columnWidth[i]
        val x = offsetX + layout.columnX[i]
        drawText(
            canvas,
            ellipsize(column.header, (w - 2 * CELL_PADDING_PX).toFloat(), headerPaint::measureText),
            headerPaint, x.toFloat(), headerTop, layout.headerHeightPx, column.alignEnd, w,
            // 表头文字下压：84px 行里纯居中会显得贴着上分隔线，下移 8px 与数据行文字基线对齐
            baselineBiasPx = 8f,
        )
    }

    val rowsStart = headerTop + layout.headerHeightPx
    var row = firstRow
    var line = 0
    while (row < lastRowExclusive) {
        val rowTop = rowsStart + line * layout.rowHeightPx
        if (line % 2 == 1) {
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
            val x = offsetX + layout.columnX[i]
            // 数据格两行优先（V9-C）：能完整放下就不省略，超过两行才在末行加省略号
            drawTextLines(
                canvas,
                wrapCellText(cell, (w - 2 * CELL_PADDING_PX).toFloat(), bodyPaint::measureText),
                bodyPaint, x.toFloat(), rowTop, layout.rowHeightPx, spec.columns[i].alignEnd, w,
            )
        }
        row++
        line++
    }
    // 双栏时两栏之间的分隔竖线
    if (layout.columnsPerBand == 2 && offsetX == layout.widthPx / 2) {
        strokePaint.color = COLOR_DIVIDER
        canvas.drawLine(
            offsetX.toFloat(), layout.titleHeightPx.toFloat(),
            offsetX.toFloat(), layout.heightPx.toFloat(),
            strokePaint,
        )
    }
}

private fun drawBadges(
    canvas: Canvas,
    badges: List<String>,
    badgePaint: Paint,
    fillPaint: Paint,
    top: Int,
) {
    val maxBadgeWidth = (canvas.width - 4 * CELL_PADDING_PX) / 2
    var x = CELL_PADDING_PX.toFloat()
    // 药丸收到 36px（行高 52 留 16px 呼吸），上下各 8px 对称居中
    val badgeHeight = BADGES_LINE_HEIGHT_PX - 16
    val pillTop = top + 8f
    for (badge in badges) {
        val text = ellipsize(badge, (maxBadgeWidth - 2 * CELL_PADDING_PX).toFloat(), badgePaint::measureText)
        val textWidth = badgePaint.measureText(text)
        val pillWidth = textWidth + 2 * CELL_PADDING_PX
        if (x + pillWidth > canvas.width - CELL_PADDING_PX) break
        fillPaint.color = COLOR_BADGE_BG
        canvas.drawRoundRect(
            x, pillTop, x + pillWidth, pillTop + badgeHeight,
            badgeHeight / 2f, badgeHeight / 2f, fillPaint,
        )
        val baseline = pillTop + badgeHeight / 2f -
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
    alignEnd: Boolean,
    columnWidthPx: Int = 0,
    baselineBiasPx: Float = 0f,
) = drawTextLines(canvas, listOf(text), paint, x, rowTop, rowHeight, alignEnd, columnWidthPx, baselineBiasPx)

/**
 * 画一格的所有文字块，整体在行高内垂直居中。
 * 单行时基线公式与历史实现逐字相同（居中 - (ascent+descent)/2），多行时按 lineHeight 堆叠后整体居中。
 * alignEnd 时每行都用同一条右边界 x。
 */
private fun drawTextLines(
    canvas: Canvas,
    lines: List<String>,
    paint: Paint,
    x: Float,
    rowTop: Int,
    rowHeight: Int,
    alignEnd: Boolean,
    columnWidthPx: Int = 0,
    baselineBiasPx: Float = 0f,
) {
    if (lines.isEmpty()) return
    val lineHeight = paint.textSize * CELL_LINE_HEIGHT_FACTOR
    val blockHeight = lineHeight * lines.size
    val blockTop = rowTop + rowHeight / 2f - blockHeight / 2f + baselineBiasPx
    val centering = -(paint.ascent() + paint.descent()) / 2f
    val endAligned = alignEnd && columnWidthPx > 0
    paint.textAlign = if (endAligned) Paint.Align.RIGHT else Paint.Align.LEFT
    val drawX = if (endAligned) x + columnWidthPx - CELL_PADDING_PX else x + CELL_PADDING_PX
    lines.forEachIndexed { i, line ->
        if (line.isEmpty()) return@forEachIndexed // drawText 对空串无意义
        canvas.drawText(line, drawX, blockTop + lineHeight * i + lineHeight / 2f + centering, paint)
    }
}
