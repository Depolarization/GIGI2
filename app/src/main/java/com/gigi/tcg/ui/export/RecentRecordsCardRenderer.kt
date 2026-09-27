// 「最近对局」导出卡片图渲染器（V27）：一比一复刻首页 HomeRoute 整页版式 ——
// 顶部导出日期行（16sp，用户要求「表头含导出时间且字号加大」）+ 个人信息卡
// （照抄 ProfileCard：64dp 圆头像 + 「昵称(titleMedium 粗体)+段位(titleSmall 中粗, tierColor)」
// 基线同行 + UID(bodyMedium, 无 "UID:" 前缀) + 天梯/巅峰双列积分）+ 对局卡列表
// （照抄 RecordItem：圆角卡片 + 圆形头像 56dp + 「昵称(bodyLarge) / UID(bodySmall) / 时间(bodySmall)」+
// 右侧「天梯·巅峰(bodySmall，变化量语义色双色)」+ 胜负(titleSmall，胜绿/负红/空灰)）。
// 「最近对局」标题行与右侧两个图标按钮不进导出图（用户指定排除）。
// 画布宽 1080px：dp→px 系数 3（360dp 标准视口，1080/360）；sp→px 系数 2（未乘字体缩放）。
// 导出图规格必须跨设备统一，故颜色不随深浅主题漂移：底用浅色档定版值
// （win 0xFF2E7D4F / lose 0xFFB84A4A / gold 0xFF8A6A16，对纯白卡片均过 WCAG AA），
// 文本/容器色取 M3 light 基准（onSurface / onSurfaceVariant / surfaceVariant）。
// 唯一例外是段位色：由调用方在组合上下文解析 C 路 tierColor(tier) 后以 ARGB 灌入
// （ProfileCardSpec.tierColorArgb），保证导出图与首页所见即所得。
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
import com.gigi.tcg.ui.screens.home.ProfileCardSpec
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

// ---- 个人信息卡（ProfileCard 1:1），dp×3 ----
private const val PROFILE_PADDING_PX = 16 * 3      // Column padding(16.dp)（四边同值）
private const val PROFILE_AVATAR_PX = 64 * 3       // Avatar size = 64.dp
private const val PROFILE_TEXT_GAP_PX = 12 * 3     // 文本列 padding(start = 12.dp)
private const val PROFILE_NICK_TIER_GAP_PX = 8 * 3 // 昵称与段位同行间距 Spacer(8.dp)
private const val PROFILE_ROW_GAP_PX = 12 * 3      // 行 1 → 积分区 Spacer(12.dp)

// ---- sp×2（Material 默认 Typography：bodyLarge 16 / bodyMedium·titleSmall 14 /
// bodySmall·labelMedium 12 / titleMedium 16 / titleLarge 22）----
// 顶部日期行：13sp→16sp×2=32px（用户要求「日期字号适当加大」，与昵称同级）
private const val TEXT_SIZE_DATE_PX = 16f * 2
private const val TEXT_SIZE_NICK_PX = 16f * 2      // 卡片昵称 bodyLarge / 个人信息昵称 titleMedium
private const val TEXT_SIZE_SMALL_PX = 12f * 2     // bodySmall / labelMedium
private const val TEXT_SIZE_RESULT_PX = 14f * 2    // titleSmall
private const val TEXT_SIZE_MED_PX = 14f * 2       // bodyMedium（个人信息 UID）
private const val TEXT_SIZE_SCORE_VALUE_PX = 22f * 2 // titleLarge（积分数值）

// 三行文本（昵称/UID/时间）的行高系数，行块整体垂直居中
private const val LINE_HEIGHT_FACTOR = 1.5f
// 个人信息卡行高取 M3 默认 lineHeight（sp×2）：titleMedium 24→48、bodyMedium 20→40、
// labelMedium 16→32、titleLarge 28→56。行 1 由 192px 头像撑高，文本块垂直居中。
private const val PROFILE_NICK_LINE_PX = 24f * 2
private const val PROFILE_UID_LINE_PX = 20f * 2
private const val PROFILE_LABEL_LINE_PX = 16f * 2
private const val PROFILE_VALUE_LINE_PX = 28f * 2

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

/** 单个头像位图的加载（Coil）。空 url / 任何异常都只返回 null，绝不阻断导出。 */
private suspend fun loadAvatarBitmap(loader: ImageLoader, appContext: Context, url: String?): Bitmap? {
    if (url.isNullOrBlank()) return null
    return runCatching {
        val request = ImageRequest.Builder(appContext)
            .data(url)
            .allowHardware(false) // 🔴 硬件位图无法画到软件 Bitmap 的 Canvas 上
            .build()
        (loader.execute(request) as? SuccessResult)?.drawable?.toSoftwareBitmap()
    }.getOrNull()
}

/**
 * 预取对局卡头像 Bitmap（导出前调用，走 [Dispatchers.IO]）。
 * 任何一条失败都只让该槽位为 null —— 渲染器画「圆底 + 人形」占位，绝不阻断导出。
 */
suspend fun fetchRecordAvatarBitmaps(context: Context, cards: List<RecentRecordsCard>): List<Bitmap?> =
    withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val loader: ImageLoader = appContext.imageLoader
        coroutineScope {
            cards.map { card -> async { loadAvatarBitmap(loader, appContext, card.avatarUrl) } }
                .map { it.await() }
        }
    }

/** 预取个人信息卡头像（与对局卡头像分开取，失败同样只落占位） */
suspend fun fetchProfileAvatarBitmap(context: Context, avatarUrl: String?): Bitmap? =
    withContext(Dispatchers.IO) {
        loadAvatarBitmap(context.applicationContext.imageLoader, context.applicationContext, avatarUrl)
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

/** 个人信息卡高度 = 上下 padding + 行 1（64dp 头像）+ 间隙 + 积分区（标签 + 数值两行） */
private val PROFILE_CARD_HEIGHT_PX =
    2 * PROFILE_PADDING_PX + PROFILE_AVATAR_PX + PROFILE_ROW_GAP_PX +
        (PROFILE_LABEL_LINE_PX + PROFILE_VALUE_LINE_PX).toInt()

/**
 * 纯绘制：首页整页镜像（日期行 + 个人信息卡 + 对局卡列表；标题行与按钮按需求排除）。
 * [avatars] 与 [cards] 按位一一对应（来自 [fetchRecordAvatarBitmaps]，允许 null 槽位 →
 * 「圆底 + 人形」占位）；[profile] 为 null（如个人信息接口失败）时整卡跳过，其余照画。
 * 顶部一行导出日期（与落盘文件名同源 [exportDateText]，走 export_date_label 资源）。
 */
fun renderRecordsCardBitmap(
    cards: List<RecentRecordsCard>,
    avatars: List<Bitmap?>,
    profile: ProfileCardSpec?,
    profileAvatar: Bitmap?,
): Bitmap {
    val width = 1080
    val contentWidth = width - 2 * RECORDS_PAGE_MARGIN_PX
    val cardHeight = AVATAR_SIZE_PX + 2 * CARD_PADDING_PX
    val dateBandHeight = (TEXT_SIZE_DATE_PX * 2).toInt()
    val profileBlockHeight = if (profile != null) PROFILE_CARD_HEIGHT_PX + CARD_GAP_PX else 0
    val stackHeight = if (cards.isEmpty()) 0 else cards.size * (cardHeight + CARD_GAP_PX)
    val height = dateBandHeight + profileBlockHeight + stackHeight + CARD_BOTTOM_PX

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
        LocaleStrings.getOrDefault(R.string.export_date_label, "导出日期 %1\$s", exportDateText()),
        RECORDS_PAGE_MARGIN_PX.toFloat(),
        dateBandHeight / 2f + textCentering(datePaint), datePaint,
    )

    var nextTop = dateBandHeight.toFloat()
    if (profile != null) {
        drawProfileCard(canvas, profile, profileAvatar, nextTop, contentWidth, fillPaint)
        nextTop += PROFILE_CARD_HEIGHT_PX + CARD_GAP_PX
    }

    val smallPaint = textPaint(TEXT_SIZE_SMALL_PX, RECORDS_COLOR_MUTED_TEXT)
    val cardNickPaint = textPaint(TEXT_SIZE_NICK_PX, RECORDS_COLOR_BODY_TEXT)
    val bodySmallPaint = textPaint(TEXT_SIZE_SMALL_PX, RECORDS_COLOR_BODY_TEXT)
    val resultPaint = textPaint(TEXT_SIZE_RESULT_PX, COLOR_WIN)
    val changePaint = textPaint(TEXT_SIZE_SMALL_PX, COLOR_WIN)

    cards.forEachIndexed { index, card ->
        drawCard(
            canvas, card, avatars.getOrNull(index), nextTop, width, contentWidth, cardHeight,
            cardNickPaint, smallPaint, bodySmallPaint, resultPaint, changePaint, fillPaint,
        )
        nextTop += cardHeight + CARD_GAP_PX
    }
    return bitmap
}

@Suppress("LongParameterList")
private fun drawProfileCard(
    canvas: Canvas,
    spec: ProfileCardSpec,
    avatar: Bitmap?,
    cardTop: Float,
    contentWidth: Int,
    fillPaint: Paint,
) {
    // 卡片底 + 柔和投影，与对局卡同款（对应 M3 Card elevation=1）
    val cardLeft = RECORDS_PAGE_MARGIN_PX.toFloat()
    val cardRight = (RECORDS_PAGE_MARGIN_PX + contentWidth).toFloat()
    val cardHeight = PROFILE_CARD_HEIGHT_PX.toFloat()
    fillPaint.color = COLOR_CARD_SHADOW
    canvas.drawRoundRect(
        cardLeft - 2f, cardTop + 3f, cardRight + 2f, cardTop + cardHeight + 5f,
        CARD_CORNER_PX.toFloat(), CARD_CORNER_PX.toFloat(), fillPaint,
    )
    fillPaint.color = COLOR_CARD_BG
    canvas.drawRoundRect(
        cardLeft, cardTop, cardRight, cardTop + cardHeight,
        CARD_CORNER_PX.toFloat(), CARD_CORNER_PX.toFloat(), fillPaint,
    )

    val row1Top = cardTop + PROFILE_PADDING_PX
    drawAvatar(
        canvas, avatar,
        left = cardLeft + PROFILE_PADDING_PX, top = row1Top,
        size = PROFILE_AVATAR_PX, fillPaint = fillPaint,
    )

    // 文本列：行 1 =「昵称(粗体) + 段位」基线同行，行 2 = UID（无 "UID:" 前缀，位置即语义）
    val colLeft = cardLeft + PROFILE_PADDING_PX + PROFILE_AVATAR_PX + PROFILE_TEXT_GAP_PX
    // contentWidth 与间距常量都是 Int，表达式结果为 Int；下游与 measureText 的 Float 比较，
    // 这里显式提升为 Float，避免 coerceAtLeast/减法两侧类型不一致
    val colMax = (contentWidth - 2 * PROFILE_PADDING_PX - PROFILE_AVATAR_PX - PROFILE_TEXT_GAP_PX)
        .toFloat().coerceAtLeast(60f)
    val blockHeight = PROFILE_NICK_LINE_PX + PROFILE_UID_LINE_PX
    val blockTop = row1Top + (PROFILE_AVATAR_PX - blockHeight) / 2f
    val nickPaint = boldPaint(TEXT_SIZE_NICK_PX, RECORDS_COLOR_BODY_TEXT)
    val tierPaint = textPaint(TEXT_SIZE_MED_PX, spec.tierColorArgb)
    val uidPaint = textPaint(TEXT_SIZE_MED_PX, RECORDS_COLOR_MUTED_TEXT)
    val tierWidth = tierPaint.measureText(spec.tierText)
    val nickMax = (colMax - tierWidth - PROFILE_NICK_TIER_GAP_PX).coerceAtLeast(60f)
    val nickShown = ellipsize(spec.nickname, nickMax, nickPaint::measureText)
    val nickBaseline = blockTop + PROFILE_NICK_LINE_PX / 2f + textCentering(nickPaint)
    nickPaint.textAlign = Paint.Align.LEFT
    canvas.drawText(nickShown, colLeft, nickBaseline, nickPaint)
    tierPaint.textAlign = Paint.Align.LEFT
    canvas.drawText(
        spec.tierText, colLeft + nickPaint.measureText(nickShown) + PROFILE_NICK_TIER_GAP_PX,
        nickBaseline, tierPaint,
    )
    uidPaint.textAlign = Paint.Align.LEFT
    canvas.drawText(
        ellipsize(spec.uid, colMax, uidPaint::measureText), colLeft.toFloat(),
        blockTop + PROFILE_NICK_LINE_PX + PROFILE_UID_LINE_PX / 2f + textCentering(uidPaint), uidPaint,
    )

    // 积分区：两列等宽（ScoreItem 1:1），labelMedium + titleLarge 粗体
    val scoresTop = row1Top + PROFILE_AVATAR_PX + PROFILE_ROW_GAP_PX
    val halfWidth = (contentWidth - 2 * PROFILE_PADDING_PX) / 2f
    drawScoreColumn(
        canvas, LocaleStrings.getOrDefault(R.string.score_ladder, "天梯"), spec.ladderScore.toString(),
        colLeft, scoresTop, COLOR_WIN,
    )
    drawScoreColumn(
        canvas, LocaleStrings.getOrDefault(R.string.score_peak, "巅峰"), spec.peakScore.toString(),
        colLeft + halfWidth, scoresTop, COLOR_GOLD,
    )
}

private fun drawScoreColumn(
    canvas: Canvas,
    label: String,
    value: String,
    left: Float,
    top: Float,
    valueColor: Int,
) {
    val labelPaint = textPaint(TEXT_SIZE_SMALL_PX, RECORDS_COLOR_MUTED_TEXT)
    val valuePaint = boldPaint(TEXT_SIZE_SCORE_VALUE_PX, valueColor)
    labelPaint.textAlign = Paint.Align.LEFT
    valuePaint.textAlign = Paint.Align.LEFT
    canvas.drawText(label, left, top + PROFILE_LABEL_LINE_PX / 2f + textCentering(labelPaint), labelPaint)
    canvas.drawText(value, left, top + PROFILE_LABEL_LINE_PX + PROFILE_VALUE_LINE_PX / 2f + textCentering(valuePaint), valuePaint)
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
        size = AVATAR_SIZE_PX, fillPaint = fillPaint)

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

// 软件 Canvas 拿不到可变字体字重，fakeBold 是 Compose FontWeight.Bold/Medium 的最近似
private fun boldPaint(textSize: Float, color: Int): Paint =
    textPaint(textSize, color).apply { isFakeBoldText = true }

private fun textCentering(paint: Paint): Float = -(paint.ascent() + paint.descent()) / 2f

/**
 * 圆形头像：有位图 → clipPath 圆形 + 中心方形裁剪绘制；null → 「圆底 + 人形」占位
 * （与首页 Avatar 的灰底 Person 图标兜底同语义）。
 */
private fun drawAvatar(canvas: Canvas, src: Bitmap?, left: Float, top: Float, size: Int, fillPaint: Paint) {
    val bounds = RectF(left, top, left + size, top + size)
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
