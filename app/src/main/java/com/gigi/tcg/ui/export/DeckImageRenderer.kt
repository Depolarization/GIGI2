// 卡组详情页导出渲染图的绘制与落盘（V37-F 任务 E，V38-B 按官方参考图重做版式）。
// 分工照统计页那一套：版式与文案在 DeckImageExport.kt（纯函数、JVM 可测），本文件只做
// 「解码素材 + 拉卡图 + Canvas 直画 + 交 CardImageSaver 落盘」，落盘策略（Pictures/GIGI/<类型>/<UID>/、
// 异常→可读 IOException、Q+/≤Q 两条路径）全部复用 CardImageSaver，不另起一套。
// 🔴 V38-B：顶部 banner（米游社 logo + 七圣召唤文本）整段移除（用户明确要求）；画面 = 米白纸面 +
// 四角云纹（程序化近似，官方素材不在网页 CSS 内联里）+ 两个金棕分区标题 + 卡框牌阵 + 左下 UID/昵称 +
// 右下游戏 logo（文字近似）。
// 🔴 不用 GraphicsLayer.toImageBitmap：它要求整页完成 Compose 布局且受 GPU 纹理上限约束，
// TableImageRenderer.kt:319 同一结论，直画 Bitmap 才可控。
// 🔴 单张卡图 404 / 解码失败 ⇒ 画占位块继续走，绝不掀翻整次导出（派单硬要求）。

package com.gigi.tcg.ui.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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

private const val PLACEHOLDER_TEXT_SIZE_PX = 20
private const val PLACEHOLDER_BORDER_PX = 2

/** 右下角游戏 logo：参考图是「原神」白色立体字标，这里用文字近似（素材未提供） */
private const val GAME_LOGO_TEXT = "原神"

/** 卡框素材（drawable-nodpi/v38_card_frame.png，300×514，中心透明、边框 ≈7px、顶部尖角在框内） */
private const val CARD_FRAME_BORDER_PX = 7

/** 参考图资源：卡框位图。解不出来就退化成占位色块 + 描边 */
data class DeckExportAssets(val cardFrame: Bitmap?)

fun loadDeckExportAssets(resources: Resources): DeckExportAssets =
    DeckExportAssets(cardFrame = runCatching { BitmapFactory.decodeResource(resources, R.drawable.v38_card_frame) }.getOrNull())

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
                val bitmap = renderDeckImageBitmap(spec, layout, roleImages, actionImages, assets, uid)
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
                assets.cardFrame?.recycle()
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
    uid: String? = null,
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
    // 卡图多半比 tile 大（接口给 h_275）⇒ 缩放开双线性，否则边缘锯齿
    val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    val srcRect = Rect()
    val dstRect = RectF()

    val sectionPaint = textPaint(DECK_GROUP_LABEL_TEXT_PX, DECK_COLOR_SECTION_TITLE).apply { isFakeBoldText = true }
    val placeholderPaint = textPaint(PLACEHOLDER_TEXT_SIZE_PX, DECK_COLOR_PILL_TEXT)
    val footerPaint = textPaint(UID_FOOTER_TEXT_PX, DECK_COLOR_FOOTER_TEXT)
    val logoPaint = textPaint(DECK_LOGO_TEXT_SIZE_PX, DECK_COLOR_LOGO_TEXT).apply {
        isFakeBoldText = true
    }
    // 参考图右下是白色「原神」立体字标：字面 + 一圈深色外描边 + 一层向外扩张的浅色轮廓。
    // 单层 shadowLayer 会显得又小又虚，这里改用 stroke 描边两次 + 偏移投影，凑出体积感。
    val logoStrokePaint = textPaint(DECK_LOGO_TEXT_SIZE_PX, DECK_COLOR_LOGO_STROKE).apply {
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = DECK_LOGO_STROKE_PX
    }
    val logoGlowPaint = textPaint(DECK_LOGO_TEXT_SIZE_PX, DECK_COLOR_LOGO_GLOW).apply {
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = DECK_LOGO_GLOW_PX
    }
    val logoShadowPaint = textPaint(DECK_LOGO_TEXT_SIZE_PX, DECK_COLOR_LOGO_SHADOW).apply {
        isFakeBoldText = true
        style = Paint.Style.FILL
    }

    val canvas = Canvas(bitmap)
    val width = layout.widthPx.toFloat()
    val height = layout.heightPx.toFloat()

    // 1. 米白纸面铺满 + 双层内描边 + 四角云纹（程序化近似，官方纸面素材不在网页 CSS 内联里）
    fillPaint.color = DECK_COLOR_PAPER_BG
    canvas.drawRect(0f, 0f, width, height, fillPaint)
    // 参考图纸面是"双线框"：外侧一圈细线 + 内侧一圈更浅的线，两线之间留 6px 纸面
    strokePaint.color = DECK_COLOR_PANEL_BORDER
    strokePaint.strokeWidth = 2f
    canvas.drawRect(8f, 8f, width - 8f, height - 8f, strokePaint)
    strokePaint.color = DECK_COLOR_PANEL_BORDER_INNER
    strokePaint.strokeWidth = 1.5f
    canvas.drawRect(PANEL_INNER_INSET_PX, PANEL_INNER_INSET_PX, width - PANEL_INNER_INSET_PX, height - PANEL_INNER_INSET_PX, strokePaint)
    strokePaint.color = DECK_COLOR_PANEL_BORDER
    strokePaint.strokeWidth = PLACEHOLDER_BORDER_PX.toFloat()
    drawCornerFlourishes(canvas, width, height)

    // 2. 两个金棕分区标题（居中）——顶部 banner 已移除，这里只剩参考图保留的两块
    layout.roleLabelTopPx?.let { top ->
        drawSectionTitle(canvas, spec.roleLabel, sectionPaint, top)
    }
    layout.actionLabelTopPx?.let { top ->
        drawSectionTitle(canvas, spec.actionLabel, sectionPaint, top)
    }

    // 3. 两组牌面（槽位已由纯函数排好，这里只按序贴图 + 叠卡框）
    drawCards(canvas, layout.roleSlots, roleImages, spec.roleCards, assets, fillPaint, strokePaint, imagePaint, placeholderPaint, srcRect, dstRect)
    drawCards(canvas, layout.actionSlots, actionImages, spec.actionCards, assets, fillPaint, strokePaint, imagePaint, placeholderPaint, srcRect, dstRect)

    // 4. 页脚：左下 UID/昵称两行 + 右下游戏 logo
    footerPaint.textAlign = Paint.Align.LEFT
    deckFooterLines(spec.authorText, uid).forEachIndexed { index, line ->
        canvas.drawText(line, DECK_BODY_PADDING_SIDE_PX.toFloat(), (layout.footerTopPx + (index + 1) * UID_LINE_HEIGHT_PX).toFloat(), footerPaint)
    }
    // 参考图 logo 在右下角、压住纸面底缘：先投影（偏移 3px）→ 再外层浅色轮廓 → 再深色描边 → 最后白字面
    val logoX = width - DECK_BODY_PADDING_SIDE_PX - 4f
    val logoY = height - 18f
    listOf(logoShadowPaint, logoGlowPaint, logoStrokePaint, logoPaint).forEach { it.textAlign = Paint.Align.RIGHT }
    canvas.drawText(GAME_LOGO_TEXT, logoX + 3f, logoY + 4f, logoShadowPaint)
    canvas.drawText(GAME_LOGO_TEXT, logoX, logoY, logoGlowPaint)
    canvas.drawText(GAME_LOGO_TEXT, logoX, logoY, logoStrokePaint)
    canvas.drawText(GAME_LOGO_TEXT, logoX, logoY, logoPaint)
    return bitmap
}

private const val UID_FOOTER_TEXT_PX = 20
private const val UID_LINE_HEIGHT_PX = 26
private const val DECK_LOGO_TEXT_SIZE_PX = 44
private const val DECK_LOGO_STROKE_PX = 4f
private const val DECK_LOGO_GLOW_PX = 9f

/** 纸面内框距画布边的距离（双线框的内线位置） */
private const val PANEL_INNER_INSET_PX = 16f

/**
 * 四角云纹：官方纸面花纹素材缺失（V36 逆向确认不在网页 CSS 内联 235 张里），
 * 程序化绘制——每角一组「外圈双弧 + 内侧 S 形卷草 + 末端涡卷」，镜像到四角。
 *
 * 🔴 第一版（V38-B）笔画 3px + alpha 180 + 半径 ≤120，在 750px 画布上几乎看不见，
 * 对照参考图判为**不可接受**（官方花纹清晰可辨、约 90×90px）。本版：笔画 5px、alpha 235、
 * 涡卷更大更外扩，颜色与纸面拉开到可辨对比。
 */
private fun drawCornerFlourishes(canvas: Canvas, width: Float, height: Float) {
    val decoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = CORNER_DECO_STROKE_PX
        color = DECK_COLOR_CORNER_DECO
        alpha = 235
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // 以角为原点、向右下方展开的一组卷草（半径最大约 150px）
    val motif = Path().apply {
        // 外圈双弧（贴纸面角，勾出云纹外轮廓）
        addArc(20f, 20f, 150f, 150f, 0f, 90f)
        addArc(34f, 34f, 122f, 122f, 0f, 90f)
        // 内侧 S 形卷草
        moveTo(58f, 30f)
        cubicTo(92f, 30f, 104f, 56f, 84f, 72f)
        cubicTo(70f, 83f, 52f, 76f, 55f, 62f)
        // 末端涡卷（参考图那种"卷回去"的小圆）
        addArc(78f, 96f, 126f, 144f, 300f, 250f)
        // 一条贴边横线，把花纹与内框线接上
        moveTo(20f, 168f)
        lineTo(168f, 20f)
    }
    val margin = 22f
    // 四角镜像：靠 scale(-1,1)/(1,-1)/(-1,-1) 复用同一 path，避免写四份重复坐标
    listOf(
        floatArrayOf(margin, margin, 1f, 1f),
        floatArrayOf(width - margin, margin, -1f, 1f),
        floatArrayOf(margin, height - margin, 1f, -1f),
        floatArrayOf(width - margin, height - margin, -1f, -1f),
    ).forEach { (tx, ty, sx, sy) ->
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(sx, sy)
        canvas.drawPath(motif, decoPaint)
        canvas.restore()
    }
}

private const val CORNER_DECO_STROKE_PX = 5f

private fun drawSectionTitle(canvas: Canvas, label: String, paint: Paint, topPx: Int) {
    if (label.isEmpty()) return
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText(label, canvas.width / 2f, topPx + baselineInBox(paint, DECK_GROUP_LABEL_HEIGHT_PX.toFloat()), paint)
}

/** 🔴 images 与 slots 同序等长（都来自同一个 spec 列表）；缺项/越界按 null ⇒ 走占位块，不抛 */
private fun drawCards(
    canvas: Canvas,
    slots: List<DeckCardSlot>,
    images: List<Bitmap?>,
    faces: List<DeckCardFace>,
    assets: DeckExportAssets,
    fillPaint: Paint,
    strokePaint: Paint,
    imagePaint: Paint,
    placeholderPaint: Paint,
    srcRect: Rect,
    dstRect: RectF,
) {
    val frame = assets.cardFrame?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
    slots.forEachIndexed { index, slot ->
        // 官方卡框 300×514 与槽位 92×153/120×200 同比（5/6 档）⇒ 整框缩放到槽位即可，无需 9-slice
        val border = (CARD_FRAME_BORDER_PX * slot.widthPx / 300f * (300f / 120f)).toInt().coerceAtLeast(2)
        val bitmap = images.getOrNull(index)
        if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            srcRect.set(0, 0, bitmap.width, bitmap.height)
            dstRect.set(
                (slot.xPx + border).toFloat(), (slot.yPx + border).toFloat(),
                (slot.xPx + slot.widthPx - border).toFloat(), (slot.yPx + slot.heightPx - border).toFloat(),
            )
            canvas.drawBitmap(bitmap, srcRect, dstRect, imagePaint)
        } else {
            drawCardPlaceholder(canvas, slot, border, faces.getOrNull(index)?.name.orEmpty(), fillPaint, strokePaint, placeholderPaint)
        }
        if (frame != null) {
            srcRect.set(0, 0, frame.width, frame.height)
            dstRect.set(slot.xPx.toFloat(), slot.yPx.toFloat(), (slot.xPx + slot.widthPx).toFloat(), (slot.yPx + slot.heightPx).toFloat())
            canvas.drawBitmap(frame, srcRect, dstRect, imagePaint)
        }
    }
}

/** 卡图取不到时的兜底：暖色块 + 描边 + 居中牌名（信息不丢，版式不塌） */
private fun drawCardPlaceholder(
    canvas: Canvas,
    slot: DeckCardSlot,
    insetPx: Int,
    name: String,
    fillPaint: Paint,
    strokePaint: Paint,
    textPaint: Paint,
) {
    val left = (slot.xPx + insetPx).toFloat()
    val top = (slot.yPx + insetPx).toFloat()
    val right = (slot.xPx + slot.widthPx - insetPx).toFloat()
    val bottom = (slot.yPx + slot.heightPx - insetPx).toFloat()
    fillPaint.color = DECK_COLOR_PLACEHOLDER_BG
    canvas.drawRect(left, top, right, bottom, fillPaint)
    strokePaint.color = DECK_COLOR_PLACEHOLDER_BORDER
    canvas.drawRect(left, top, right, bottom, strokePaint)
    if (name.isBlank()) return
    textPaint.textAlign = Paint.Align.CENTER
    val text = ellipsize(name, (right - left - 8f).coerceAtLeast(0f)) { textPaint.measureText(it) }
    canvas.drawText(text, (left + right) / 2f, top + (bottom - top - PLACEHOLDER_TEXT_SIZE_PX) / 2f, textPaint)
}

private fun textPaint(textSizePx: Int, color: Int): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSizePx.toFloat()
        this.color = color
    }

/** 文字在 [boxHeight] 高的盒内垂直居中时基线相对盒顶的偏移（TableImageRenderer 同口径，那份是 private） */
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
