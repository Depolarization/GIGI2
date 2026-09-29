// 卡组详情页导出渲染图的绘制与落盘（V37-F 任务 E）。
// 分工照统计页那一套：版式与文案在 DeckImageExport.kt（纯函数、JVM 可测），本文件只做
// 「解码素材 + 拉卡图 + Canvas 直画 + 交 CardImageSaver 落盘」，落盘策略（Pictures/GIGI/<类型>/<UID>/、
// 异常→可读 IOException、Q+/≤Q 两条路径）全部复用 CardImageSaver，不另起一套。
// 🔴 不用 GraphicsLayer.toImageBitmap：它要求整页完成 Compose 布局且受 GPU 纹理上限约束，
// TableImageRenderer.kt:319 同一结论，直画 Bitmap 才可控。
// 🔴 单张卡图 404 / 解码失败 ⇒ 画占位块继续走，绝不掀翻整次导出（派单硬要求）。

package com.gigi.tcg.ui.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.res.Resources
import android.net.Uri
import coil.imageLoader
import coil.request.ImageRequest
import com.gigi.tcg.R
import com.gigi.tcg.ui.dialogs.cardcover.CardImageSaver
import com.gigi.tcg.ui.dialogs.cardcover.exportDateText
import com.gigi.tcg.ui.dialogs.cardcover.exportDirName
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 官方出图 `quality: .95`（FEASIBILITY §2/§4）⇒ 卡组图用 95，不沿用长图的 72：
 * 长图是几百行文字、体积优先；卡组图只有一副牌、版式与官方对齐更重要，且体积本来很小。
 */
private const val DECK_EXPORT_JPEG_QUALITY = 95

/** header 里自家 logo 的落位（官方是装饰底图，素材在 styles.css base64 里、没逐张抠，按派单以品牌头替代） */
private const val DECK_LOGO_WIDTH_PX = 300
private const val DECK_LOGO_HEIGHT_PX = 100
private const val BRAND_TEXT_SIZE_PX = 26
private const val PLACEHOLDER_TEXT_SIZE_PX = 20
private const val PLACEHOLDER_BORDER_PX = 2

/** 参考图资源：只有 logo（drawable-nodpi，像素即设计像素）。解不出来就退化成品牌文字 */
data class DeckExportAssets(val logo: Bitmap?)

fun loadDeckExportAssets(resources: Resources): DeckExportAssets =
    DeckExportAssets(logo = runCatching { BitmapFactory.decodeResource(resources, R.drawable.export_logo) }.getOrNull())

/** 一次导出的结果：[uri] 只有 Q+ 的 MediaStore 分支有（≤API 28 落公共目录恒 null，但文件确实写成功） */
data class DeckExportOutcome(val succeeded: Boolean, val uri: Uri?, val error: String? = null)

/**
 * 全链路：组 spec → 算版式 → 拉卡图 → 直画 Bitmap → JPEG q95 落 `Pictures/GIGI/卡组/<UID>/`。
 * 任何异常都收成 [DeckExportOutcome.error]（CardImageSaver 抛的 message 已是可读文案，含缺存储权限），
 * 由 UI 层拼成一条带「查看」的 Snackbar；`CancellationException` 放行（离页取消不该当失败处理）。
 */
suspend fun exportDeckImage(
    appContext: Context,
    spec: DeckImageSpec,
    deckLabel: String,
    uid: String?,
): DeckExportOutcome =
    try {
        withContext(Dispatchers.Default) {
            val layout = computeDeckImageLayout(spec, paintTextMeasurer())
            val roleImages = fetchDeckCardImages(appContext, spec.roleCards)
            val actionImages = fetchDeckCardImages(appContext, spec.actionCards)
            val assets = loadDeckExportAssets(appContext.resources)
            try {
                val bitmap = renderDeckImageBitmap(spec, layout, roleImages, actionImages, assets)
                try {
                    val uri = CardImageSaver(appContext).saveBitmap(
                        bitmap,
                        baseName = deckExportBaseName(deckLabel, spec.title, exportDateText()),
                        format = Bitmap.CompressFormat.JPEG,
                        quality = DECK_EXPORT_JPEG_QUALITY,
                        subDir = exportDirName(EXPORT_DIR_DECK),
                        accountUid = uid?.takeIf { it.isNotBlank() },
                    )
                    DeckExportOutcome(succeeded = true, uri = uri)
                } finally {
                    // 回收放 finally：saveBitmap 抛异常也不能漏这张整图
                    bitmap.recycle()
                }
            } finally {
                assets.logo?.recycle()
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (e: Exception) {
        DeckExportOutcome(succeeded = false, uri = null, error = e.message)
    } catch (e: OutOfMemoryError) {
        DeckExportOutcome(succeeded = false, uri = null, error = e.message)
    }

/**
 * 按版式结果把整幅图直画出来。位图生命周期：返回的 Bitmap 归调用方（务必 recycle），
 * 传进来的卡图来自 coil 内存缓存、**不能**在这里回收。
 */
fun renderDeckImageBitmap(
    spec: DeckImageSpec,
    layout: DeckImageLayout,
    roleImages: List<Bitmap?>,
    actionImages: List<Bitmap?>,
    assets: DeckExportAssets,
): Bitmap {
    val config = chooseBitmapConfig(layout.widthPx, layout.heightPx)
    checkRenderMemoryBudget(layout.widthPx, layout.heightPx, config)
    val bitmap = try {
        Bitmap.createBitmap(layout.widthPx, layout.heightPx, config)
    } catch (e: OutOfMemoryError) {
        val mb = layout.widthPx.toLong() * layout.heightPx * 4 / (1024 * 1024)
        throw IOException("Cannot allocate deck bitmap ${layout.widthPx}x${layout.heightPx} (~${mb}MB)", e)
    }

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = PLACEHOLDER_BORDER_PX.toFloat()
    }
    // 卡图多半比 tile 大（接口给 h_275，我们角色牌 261px）⇒ 缩放开双线性，否则边缘锯齿
    val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    val srcRect = Rect()
    val dstRect = RectF()

    val titlePaint = textPaint(DECK_TITLE_TEXT_PX, DECK_COLOR_TITLE_TEXT).apply { isFakeBoldText = true }
    val authorPaint = textPaint(DECK_AUTHOR_TEXT_PX, DECK_COLOR_DESC_TEXT)
    val descPaint = textPaint(DECK_DESC_TEXT_PX, DECK_COLOR_DESC_TEXT)
    val pillPaint = textPaint(DECK_PILL_TEXT_PX, DECK_COLOR_PILL_TEXT)
    val groupPaint = textPaint(DECK_GROUP_LABEL_TEXT_PX, DECK_COLOR_GROUP_LABEL).apply { isFakeBoldText = true }
    val brandPaint = textPaint(BRAND_TEXT_SIZE_PX, DECK_COLOR_GROUP_LABEL)
    val placeholderPaint = textPaint(PLACEHOLDER_TEXT_SIZE_PX, DECK_COLOR_PILL_TEXT)

    val canvas = Canvas(bitmap)
    val width = layout.widthPx.toFloat()
    val height = layout.heightPx.toFloat()

    // 1. share-body 底色铺满整幅，header 只是顶部另一档暖色
    fillPaint.color = DECK_COLOR_BODY_BG
    canvas.drawRect(0f, 0f, width, height, fillPaint)
    fillPaint.color = DECK_COLOR_HEADER_BG
    canvas.drawRect(0f, 0f, width, layout.headerHeightPx.toFloat(), fillPaint)
    drawHeader(canvas, spec.brandText, assets.logo, brandPaint, layout.headerHeightPx, srcRect, dstRect)

    // 2. 文字流：标题 → 作者 → 标签条 → 描述
    if (spec.title.isNotEmpty()) {
        titlePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            ellipsize(spec.title, layout.textContentWidthPx.toFloat()) { titlePaint.measureText(it) },
            layout.textLeftPx.toFloat(),
            layout.titleTopPx + baselineInBox(titlePaint, DECK_TITLE_TEXT_PX),
            titlePaint,
        )
    }
    layout.authorTopPx?.let { top ->
        authorPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            ellipsize(spec.authorText, layout.textContentWidthPx.toFloat()) { authorPaint.measureText(it) },
            layout.textLeftPx.toFloat(),
            top + baselineInBox(authorPaint, DECK_AUTHOR_LINE_HEIGHT_PX),
            authorPaint,
        )
    }
    layout.pills.forEachIndexed { index, pill ->
        // 官方 label-container 两档底色交替（#f1eadb / #faf3e5），胶囊圆角=半高
        fillPaint.color = if (index % 2 == 0) DECK_COLOR_PILL_BG else DECK_COLOR_PILL_ALT_BG
        canvas.drawRoundRect(
            pill.xPx.toFloat(), pill.yPx.toFloat(),
            (pill.xPx + pill.widthPx).toFloat(), (pill.yPx + pill.heightPx).toFloat(),
            pill.heightPx / 2f, pill.heightPx / 2f, fillPaint,
        )
        pillPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            pill.text, pill.xPx + pill.widthPx / 2f,
            pill.yPx + baselineInBox(pillPaint, pill.heightPx.toFloat()), pillPaint,
        )
    }
    if (spec.descText.isNotEmpty()) {
        descPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            ellipsize(spec.descText, layout.textContentWidthPx.toFloat()) { descPaint.measureText(it) },
            layout.textLeftPx.toFloat(),
            layout.descTopPx + baselineInBox(descPaint, DECK_DESC_LINE_HEIGHT_PX),
            descPaint,
        )
    }

    // 3. 两组牌面（槽位已由纯函数排好，这里只按序贴图）
    layout.roleLabelTopPx?.let { top -> drawGroupLabel(canvas, spec.roleLabel, groupPaint, layout.textLeftPx, top) }
    drawCards(canvas, layout.roleSlots, roleImages, spec.roleCards, fillPaint, strokePaint, imagePaint, placeholderPaint, srcRect, dstRect)
    layout.actionLabelTopPx?.let { top -> drawGroupLabel(canvas, spec.actionLabel, groupPaint, layout.textLeftPx, top) }
    drawCards(canvas, layout.actionSlots, actionImages, spec.actionCards, fillPaint, strokePaint, imagePaint, placeholderPaint, srcRect, dstRect)
    return bitmap
}

/** 🔴 images 与 slots 同序等长（都来自同一个 spec 列表）；缺项/越界按 null ⇒ 走占位块，不抛 */
private fun drawCards(
    canvas: Canvas,
    slots: List<DeckCardSlot>,
    images: List<Bitmap?>,
    faces: List<DeckCardFace>,
    fillPaint: Paint,
    strokePaint: Paint,
    imagePaint: Paint,
    placeholderPaint: Paint,
    srcRect: Rect,
    dstRect: RectF,
) {
    slots.forEachIndexed { index, slot ->
        val bitmap = images.getOrNull(index)
        if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            srcRect.set(0, 0, bitmap.width, bitmap.height)
            dstRect.set(slot.xPx.toFloat(), slot.yPx.toFloat(), (slot.xPx + slot.widthPx).toFloat(), (slot.yPx + slot.heightPx).toFloat())
            canvas.drawBitmap(bitmap, srcRect, dstRect, imagePaint)
        } else {
            drawCardPlaceholder(canvas, slot, faces.getOrNull(index)?.name.orEmpty(), fillPaint, strokePaint, placeholderPaint)
        }
    }
}

/** 卡图取不到时的兜底：暖色块 + 描边 + 居中牌名（信息不丢，版式不塌） */
private fun drawCardPlaceholder(
    canvas: Canvas,
    slot: DeckCardSlot,
    name: String,
    fillPaint: Paint,
    strokePaint: Paint,
    textPaint: Paint,
) {
    val left = slot.xPx.toFloat()
    val top = slot.yPx.toFloat()
    val right = (slot.xPx + slot.widthPx).toFloat()
    val bottom = (slot.yPx + slot.heightPx).toFloat()
    fillPaint.color = DECK_COLOR_PLACEHOLDER_BG
    canvas.drawRect(left, top, right, bottom, fillPaint)
    strokePaint.color = DECK_COLOR_PLACEHOLDER_BORDER
    canvas.drawRect(left, top, right, bottom, strokePaint)
    if (name.isBlank()) return
    textPaint.textAlign = Paint.Align.CENTER
    val text = ellipsize(name, (slot.widthPx - 8f).coerceAtLeast(0f)) { textPaint.measureText(it) }
    canvas.drawText(text, (left + right) / 2f, top + (slot.heightPx - PLACEHOLDER_TEXT_SIZE_PX) / 2f, textPaint)
}

private fun drawGroupLabel(canvas: Canvas, label: String, paint: Paint, leftPx: Int, topPx: Int) {
    if (label.isEmpty()) return
    paint.textAlign = Paint.Align.LEFT
    canvas.drawText(label, leftPx.toFloat(), topPx + baselineInBox(paint, DECK_GROUP_LABEL_HEIGHT_PX), paint)
}

private fun drawHeader(
    canvas: Canvas,
    brandText: String,
    logo: Bitmap?,
    brandPaint: Paint,
    headerHeightPx: Int,
    srcRect: Rect,
    dstRect: RectF,
) {
    val centerX = canvas.width / 2f
    var textTop = (headerHeightPx - BRAND_TEXT_SIZE_PX) / 2f
    if (logo != null && !logo.isRecycled && logo.width > 0 && logo.height > 0) {
        val logoTop = (headerHeightPx - DECK_LOGO_HEIGHT_PX - BRAND_TEXT_SIZE_PX) / 3f
        srcRect.set(0, 0, logo.width, logo.height)
        dstRect.set(centerX - DECK_LOGO_WIDTH_PX / 2f, logoTop, centerX + DECK_LOGO_WIDTH_PX / 2f, logoTop + DECK_LOGO_HEIGHT_PX)
        canvas.drawBitmap(logo, srcRect, dstRect, null)
        textTop = logoTop + DECK_LOGO_HEIGHT_PX + 8f
    }
    if (brandText.isEmpty()) return
    brandPaint.textAlign = Paint.Align.CENTER
    canvas.drawText(brandText, centerX, textTop + brandPaint.textSize, brandPaint)
}

private fun textPaint(textSizePx: Int, color: Int): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx.toFloat()
        this.color = color
    }

/** 文字在 [boxHeight] 高的盒内垂直居中时基线相对盒顶的偏移（TableImageRenderer 同口径，那份是 private） */
private fun baselineInBox(paint: Paint, boxHeight: Int): Float = baselineInBox(paint, boxHeight.toFloat())

private fun baselineInBox(paint: Paint, boxHeight: Float): Float =
    boxHeight / 2f - (paint.ascent() + paint.descent()) / 2f

/**
 * 拉一组卡图。**并发**提交给 coil（一副牌 25 张，串行会拖到数秒），任何一张失败都只让它自己变占位块。
 * 🔴 `allowHardware(false)`：硬件位图不能画进软件 Canvas，直画路线必须强制软件位图。
 */
private suspend fun fetchDeckCardImages(context: Context, faces: List<DeckCardFace>): List<Bitmap?> =
    coroutineScope {
        faces.map { face -> async(Dispatchers.IO) { decodeCardImage(context, face.imageUrl) } }.awaitAll()
    }

private suspend fun decodeCardImage(context: Context, url: String?): Bitmap? =
    if (url.isNullOrBlank()) {
        null
    } else {
        try {
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context).data(url).allowHardware(false).build()
            )
            result.drawable?.toSoftwareBitmap()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            // 404 / 解码失败 / OOM 全走占位：一张图不该毁掉整张导出图
            null
        }
    }

/** coil 命中内存缓存时一般就是 BitmapDrawable；矢量/动画一类兜底自绘成软件位图 */
private fun Drawable.toSoftwareBitmap(): Bitmap? = when (this) {
    is BitmapDrawable -> bitmap?.takeIf { !it.isRecycled }
    else -> runCatching {
        val w = intrinsicWidth.coerceAtLeast(1)
        val h = intrinsicHeight.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, w, h)
        draw(Canvas(out))
        out
    }.getOrNull()
}
