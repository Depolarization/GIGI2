// 「最近对局」导出卡片图渲染器（V26）：版式逐项照抄首页 HomeRoute.RecordItem ——
// 圆角卡片 + 圆形头像 56dp + 「昵称(bodyLarge) / UID(bodySmall) / 时间(bodySmall)」+
// 右侧「天梯·巅峰(bodySmall，变化量语义色双色)」+ 胜负(titleSmall，胜绿/负红/空灰)。
// 画布宽 1080px：dp→px 系数 3（360dp 标准视口，1080/360）；sp→px 系数 2（未乘字体缩放）。
// 导出图规格必须跨设备统一，故颜色不随深浅主题漂移：底用浅色档定版值
// （win 0xFF2E7D4F / lose 0xFFB84A4A / gold 0xFF8A6A16，对纯白卡片均过 WCAG AA），
// 文本/容器色取 M3 light 基准（onSurface / onSurfaceVariant / surfaceVariant）。
// 最多 10 张卡（RECENT_RECORDS_MAX_CARDS），高度很小，无内存风险，不复用长图的内存闸。

package com.gigi.tcg.ui.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.gigi.tcg.R
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.ui.dialogs.cardcover.exportDateText
import com.gigi.tcg.ui.screens.home.RecentRecordsCard
import com.gigi.tcg.ui.screens.home.ResultColorType
import com.gigi.tcg.ui.screens.home.ScoreLine
import com.gigi.tcg.ui.screens.home.scoreChangeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.min

// ---- dp×3 ----
private const val RECORDS_PAGE_MARGIN_PX = 16 * 3  // 首页整页 Column 的外边距
private const val CARD_GAP_PX = 8 * 3              // 首页列表 verticalArrangement spacedBy(8.dp)
private const val CARD_CORNER_PX = 12 * 3          // M3 Card medium shape（12dp）
private const val CARD_PADDING_PX = 12 * 3         // RecordItem Row 的 padding(12.dp)
private const val AVATAR_SIZE_PX = 56 * 3          // Avatar size = 56.dp
private const val TEXT_COLUMN_GAP_PX = 12 * 3      // 文本列 padding(horizontal = 12.dp)
private const val RESULT_GAP_PX = 12 * 3           // 胜负文本 padding(start = 12.dp)
private const val CARD_BOTTOM_PX = 16 * 3          // 尾卡 → 画布底边（含卡下自带的 8dp 间隙）

// ---- sp×2（Material 默认 Typography：bodyLarge 16 / bodySmall 12 / titleSmall 14；
// 首页标题行 titleMedium 14 —— 顶部日期比它稍小一档，弱化成元信息）----
private const val TEXT_SIZE_NICK_PX = 16f * 2
private const val TEXT_SIZE_SMALL_PX = 12f * 2
private const val TEXT_SIZE_RESULT_PX = 14f * 2
private const val TEXT_SIZE_DATE_PX = 13f * 2

// 三行文本（昵称/UID/时间）的行高系数，行块整体垂直居中
private const val LINE_HEIGHT_FACTOR = 1.5f

// 定版颜色（见文件头）
private const val RECORDS_COLOR_PAGE_BG = 0xFFFAFAFA.toInt()
private const val COLOR_CARD_BG = Color.WHITE
private const val COLOR_CARD_SHADOW = 0x14000000
private const val RECORDS_COLOR_BODY_TEXT = 0xDE000000.toInt()   // M3 light onSurface
private const val RECORDS_COLOR_MUTED_TEXT = 0x99000000.toInt()  // M3 light onSurfaceVariant
private const val COLOR_AVATAR_BG = 0xFFE0E0E0.toInt()   // M3 light surfaceVariant（占位圆底）
private const val COLOR_WIN = 0xFF2E7D4F.toInt()
private const val COLOR_LOSE = 0xFFB84A4A.toInt()
private const val COLOR_GOLD = 0xFF8A6A16.toInt()

private fun resultColor(type: ResultColorType): Int = when (type) {
    ResultColorType.Win -> COLOR_WIN
    ResultColorType.Lose -> COLOR_LOSE
    ResultColorType.Neutral -> RECORDS_COLOR_MUTED_TEXT
}

/**
 * 预取头像 Bitmap（导出前调用，走 [Dispatchers.IO]）。
 * 🔴 allowHardware(false)：硬件位图无法画到软件 Bitmap 的 Canvas 上。
 * 任何一条失败（空 url / 网络错 / 解码错）都只让该槽位为 null —— 渲染器画占位，绝不阻断导出。
 */
suspend fun fetchRecordAvatarBitmaps(context: Context, cards: List<RecentRecordsCard>): List<Bitmap?> =
    withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val loader: ImageLoader = appContext.imageLoader
        coroutineScope {
            cards.map { card ->
                async {
                    if (card.avatarUrl.isNullOrBlank()) return@async null
                    runCatching {
                        val request = ImageRequest.Builder(appContext)
                            .data(card.avatarUrl)
                            .allowHardware(false)
                            .build()
                        (loader.execute(request) as? SuccessResult)?.drawable?.toSoftwareBitmap()
                    }.getOrNull()
                }
            }.map { it.await() }
        }
    }

/** Drawable → 软件 ARGB Bitmap（clipPath/drawBitmap 只认软件位图） */
private fun Drawable.toSoftwareBitmap(): Bitmap? {
    val width = intrinsicWidth
    val height = intrinsicHeight
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, width, height)
    draw(Canvas(bitmap))
    return bitmap
}

/**
 * 纯绘制：把卡片列表画成首页样式的图。[avatars] 与 [cards] 按位一一对应（来自
 * [fetchRecordAvatarBitmaps]，允许 null 槽位 → 「圆底 + 人形」占位）。
 * 顶部一行导出日期（yyyy-MM-dd，与落盘文件名同源 [exportDateText]）。
 */
fun renderRecordsCardBitmap(cards: List<RecentRecordsCard>, avatars: List<Bitmap?>): Bitmap {
    val width = 1080
    val contentWidth = width - 2 * RECORDS_PAGE_MARGIN_PX
    val cardHeight = AVATAR_SIZE_PX + 2 * CARD_PADDING_PX
    val dateBandHeight = (TEXT_SIZE_DATE_PX * 2).toInt()
    val stackHeight = if (cards.isEmpty()) 0 else cards.size * (cardHeight + CARD_GAP_PX)
    val height = dateBandHeight + stackHeight + CARD_BOTTOM_PX

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = RECORDS_COLOR_PAGE_BG
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

    // 顶部一行日期标题（表头要包含导出时间 —— 用户要求）
    val datePaint = textPaint(TEXT_SIZE_DATE_PX, RECORDS_COLOR_MUTED_TEXT)
    datePaint.textAlign = Paint.Align.LEFT
    canvas.drawText(
        exportDateText(), RECORDS_PAGE_MARGIN_PX.toFloat(),
        dateBandHeight / 2f + textCentering(datePaint), datePaint,
    )

    val nickPaint = textPaint(TEXT_SIZE_NICK_PX, RECORDS_COLOR_BODY_TEXT)
    val smallPaint = textPaint(TEXT_SIZE_SMALL_PX, RECORDS_COLOR_MUTED_TEXT)
    val bodySmallPaint = textPaint(TEXT_SIZE_SMALL_PX, RECORDS_COLOR_BODY_TEXT)
    val resultPaint = textPaint(TEXT_SIZE_RESULT_PX, COLOR_WIN)
    val changePaint = textPaint(TEXT_SIZE_SMALL_PX, COLOR_WIN)

    var cardTop = dateBandHeight.toFloat()
    cards.forEachIndexed { index, card ->
        drawCard(
            canvas, card, avatars.getOrNull(index), cardTop, width, contentWidth, cardHeight,
            nickPaint, smallPaint, bodySmallPaint, resultPaint, changePaint, fillPaint,
        )
        cardTop += cardHeight + CARD_GAP_PX
    }
    return bitmap
}

@Suppress("LongParameterList")
private fun drawCard(
    canvas: Canvas,
    card: RecentRecordsCard,
    avatar: Bitmap?,
    cardTop: Float,
    width: Int,
    contentWidth: Int,
    cardHeight: Int,
    nickPaint: Paint,
    smallPaint: Paint,
    bodySmallPaint: Paint,
    resultPaint: Paint,
    changePaint: Paint,
    fillPaint: Paint,
) {
    // 圆角卡片：先画一层下偏移的半透明底当柔和投影（software Canvas 上 setShadowLayer 只作用于文字），
    // 再画白卡 —— 对应首页 M3 Card elevation=1 的观感
    val cardLeft = RECORDS_PAGE_MARGIN_PX.toFloat()
    val cardRight = (RECORDS_PAGE_MARGIN_PX + contentWidth).toFloat()
    fillPaint.color = COLOR_CARD_SHADOW
    canvas.drawRoundRect(
        cardLeft - 2f, cardTop + 3f, cardRight + 2f, cardTop + cardHeight + 5f,
        CARD_CORNER_PX.toFloat(), CARD_CORNER_PX.toFloat(), fillPaint,
    )
    fillPaint.color = COLOR_CARD_BG
    canvas.drawRoundRect(cardLeft, cardTop, cardRight, cardTop + cardHeight,
        CARD_CORNER_PX.toFloat(), CARD_CORNER_PX.toFloat(), fillPaint)

    drawAvatar(canvas, avatar,
        left = cardLeft + CARD_PADDING_PX, top = cardTop + (cardHeight - AVATAR_SIZE_PX) / 2f,
        fillPaint = fillPaint)

    // 文本列：昵称 / UID / 时间 三行，行块垂直居中（首页 Column(weight 1f) 区域）
    val columnLeft = (RECORDS_PAGE_MARGIN_PX + CARD_PADDING_PX + AVATAR_SIZE_PX + TEXT_COLUMN_GAP_PX).toFloat()
    val cardRightInner = width - RECORDS_PAGE_MARGIN_PX - CARD_PADDING_PX
    // 昵称右缘让位给胜负列（胜负 1 字 + 左右 12dp 间隙 + 积分行最长观感余量）
    val nickMax = (cardRightInner - columnLeft - RESULT_GAP_PX * 2 - 150f).coerceAtLeast(60f)
    val lineHeight = TEXT_SIZE_SMALL_PX * LINE_HEIGHT_FACTOR
    val blockTop = cardTop + cardHeight / 2f - lineHeight * 3 / 2f

    nickPaint.textAlign = Paint.Align.LEFT
    canvas.drawText(
        ellipsize(card.nickname, nickMax, nickPaint::measureText), columnLeft,
        blockTop + lineHeight / 2f + textCentering(nickPaint), nickPaint,
    )
    smallPaint.textAlign = Paint.Align.LEFT
    val uidMax = (cardRightInner - columnLeft).coerceAtLeast(60f)
    canvas.drawText(
        ellipsize(card.opponentUid ?: "-", uidMax, smallPaint::measureText), columnLeft,
        blockTop + lineHeight * 1.5f + textCentering(smallPaint), smallPaint,
    )
    canvas.drawText(
        ellipsize(card.timeText, uidMax, smallPaint::measureText), columnLeft,
        blockTop + lineHeight * 2.5f + textCentering(smallPaint), smallPaint,
    )

    // 右缘组：胜负（titleSmall）与两行积分（bodySmall）顶对齐到昵称行，
    // 复刻首页「三行文本列 + 右两行积分 + 胜负」同顶的观感
    resultPaint.color = resultColor(card.resultColor)
    resultPaint.textAlign = Paint.Align.RIGHT
    canvas.drawText(card.resultDisplay, cardRightInner.toFloat(),
        blockTop + lineHeight / 2f + textCentering(resultPaint), resultPaint)

    val scoreRight = (cardRightInner - RESULT_GAP_PX).toFloat()
    val scoreBaseline1 = blockTop + lineHeight / 2f + textCentering(bodySmallPaint)
    drawScoreLine(canvas, card.ladder, scoreRight, scoreBaseline1, bodySmallPaint, changePaint,
        changeColor = COLOR_WIN, placeholder = "-")
    drawScoreLine(canvas, card.peak, scoreRight, scoreBaseline1 + lineHeight, bodySmallPaint, changePaint,
        changeColor = COLOR_GOLD, placeholder = LocaleStrings.getOrDefault(R.string.home_peak_placeholder, "巅峰 -"))
}

/** 一行积分：前缀正文色 + 变化量语义色，整体右对齐到同一边缘；null 画灰色占位 */
private fun drawScoreLine(
    canvas: Canvas,
    line: ScoreLine?,
    right: Float,
    baseline: Float,
    bodyPaint: Paint,
    changePaint: Paint,
    changeColor: Int,
    placeholder: String,
) {
    if (line == null) {
        bodyPaint.color = RECORDS_COLOR_MUTED_TEXT
        bodyPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(placeholder, right, baseline, bodyPaint)
        return
    }
    val defaultPrefix = if (line.prefixId == R.string.home_peak_prefix) "巅峰" else "天梯"
    val prefixText = LocaleStrings.getOrDefault(line.prefixId, defaultPrefix) + " " + line.score
    val changeText = scoreChangeText(line.change)
    changePaint.color = changeColor
    changePaint.textAlign = Paint.Align.RIGHT
    val changeWidth = changePaint.measureText(changeText)
    val spaceWidth = bodyPaint.measureText(" ")
    bodyPaint.color = RECORDS_COLOR_BODY_TEXT
    bodyPaint.textAlign = Paint.Align.LEFT
    val prefixWidth = bodyPaint.measureText(prefixText)
    canvas.drawText(changeText, right, baseline, changePaint)
    canvas.drawText(prefixText, right - changeWidth - spaceWidth - prefixWidth, baseline, bodyPaint)
}

private fun textPaint(textSize: Float, color: Int): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        this.color = color
    }

private fun textCentering(paint: Paint): Float = -(paint.ascent() + paint.descent()) / 2f

/**
 * 圆形头像：有位图 → clipPath 圆形 + 中心方形裁剪绘制；null → 「圆底 + 人形」占位
 * （与首页 Avatar 的灰底 Person 图标兜底同语义）。
 */
private fun drawAvatar(canvas: Canvas, src: Bitmap?, left: Float, top: Float, fillPaint: Paint) {
    val bounds = RectF(left, top, left + AVATAR_SIZE_PX, top + AVATAR_SIZE_PX)
    if (src != null && !src.isRecycled && src.width > 0 && src.height > 0) {
        val circle = Path().apply { addOval(bounds, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(circle)
        val side = min(src.width, src.height)
        val sx = (src.width - side) / 2
        val sy = (src.height - side) / 2
        canvas.drawBitmap(
            src,
            Rect(sx, sy, sx + side, sy + side),
            bounds,
            null,
        )
        canvas.restore()
        return
    }
    fillPaint.color = COLOR_AVATAR_BG
    canvas.drawOval(bounds, fillPaint)
    drawPersonGlyph(canvas, bounds)
}

/** 简单人形（头圆 + 肩弧），看得出是占位；几何按头像直径相对定义 */
private fun drawPersonGlyph(canvas: Canvas, bounds: RectF) {
    val d = bounds.width()
    val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = RECORDS_COLOR_MUTED_TEXT
        style = Paint.Style.FILL
    }
    canvas.drawCircle(bounds.centerX(), bounds.top + d * 0.36f, d * 0.15f, glyphPaint)
    val clip = Path().apply { addOval(bounds, Path.Direction.CW) }
    val shoulder = Path().apply {
        addOval(
            RectF(
                bounds.centerX() - d * 0.27f,
                bounds.top + d * 0.55f,
                bounds.centerX() + d * 0.27f,
                bounds.top + d * 1.15f,
            ),
            Path.Direction.CW,
        )
    }
    canvas.save()
    canvas.clipPath(clip)
    canvas.drawPath(shoulder, glyphPaint)
    canvas.restore()
}
