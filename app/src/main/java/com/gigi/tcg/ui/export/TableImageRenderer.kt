package com.gigi.tcg.ui.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.IOException
import kotlin.math.ceil

// 字号：正文 30px、表头 32px、标题 40px（1080px 定宽图上直接按 px 定，不随密度）
private const val TEXT_SIZE_BODY_PX = 30f
private const val TEXT_SIZE_HEADER_PX = 32f
private const val TEXT_SIZE_TITLE_PX = 40f

// 行/头/留白（行高 56px 为跨棒约定）
private const val ROW_HEIGHT_PX = 56
internal const val HEADER_HEIGHT_PX = 64
internal const val TITLE_LINE_HEIGHT_PX = 72
internal const val SUBTITLE_LINE_HEIGHT_PX = 48
internal const val BADGES_LINE_HEIGHT_PX = 52
internal const val BOTTOM_PADDING_PX = 48

internal const val MIN_COLUMN_WIDTH_PX = 64
private const val CELL_PADDING_PX = 12

// ARGB_8888 估算内存超过它则降级 RGB_565
private const val MAX_BITMAP_MEMORY_BYTES = 64L * 1024L * 1024L

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
 * @param widthPx 目标总宽（固定 1080）
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
 * 用 android.graphics.Canvas 直画 Bitmap。
 * 不用 GraphicsLayer.toImageBitmap：它要求整表完成 Compose 布局，
 * 超长内容会撞 GPU 纹理上限（常见 4096/8192）而失败。
 */
fun renderTableBitmap(spec: TableSpec, layout: TableLayout): Bitmap {
    val config = chooseBitmapConfig(layout.widthPx, layout.heightPx)
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
            drawText(
                canvas,
                ellipsize(cell, (w - 2 * CELL_PADDING_PX).toFloat(), bodyPaint::measureText),
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
    val badgeHeight = BADGES_LINE_HEIGHT_PX - 12
    val pillTop = top + 6f
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
) {
    if (text.isEmpty()) return
    val baseline = rowTop + rowHeight / 2f - (paint.ascent() + paint.descent()) / 2f
    if (alignEnd && columnWidthPx > 0) {
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(text, x + columnWidthPx - CELL_PADDING_PX, baseline, paint)
    } else {
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(text, x + CELL_PADDING_PX, baseline, paint)
    }
}
