package com.gigi.tcg.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest

/**
 * 全工程统一网络图组件：自带 shimmer 占位、失败兜底、成功图 crossfade。
 * 尺寸/形状由调用方的 modifier 决定（如 .size(40.dp).clip(CircleShape)），本组件只负责铺满该区域。
 */
@Composable
fun AppImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val request = remember(model, context) {
        ImageRequest.Builder(context)
            .data(model)
            .crossfade(300)
            .build()
    }
    val painter = rememberAsyncImagePainter(request)

    Box(modifier) {
        // 必须无条件先绘制 painter：drawSize 只在 onDraw 中更新，而 coil 默认 SizeResolver
        // 会挂起等待正尺寸——若等 Success 才绘制，请求与绘制互相死锁，永远停在 Loading。
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
        when (painter.state) {
            is AsyncImagePainter.State.Success -> Unit
            is AsyncImagePainter.State.Error -> LoadFailurePlaceholder()
            else -> ShimmerPlaceholder()
        }
    }
}

/** 加载中：surfaceVariant 底色上一条 onSurface 低透明亮带横向扫过，暗/亮两主题下均与底色形成反差，无第三方依赖 */
@Composable
private fun ShimmerPlaceholder() {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)

    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-progress",
    )

    var width by remember { mutableFloatStateOf(0f) }
    // 亮带宽约一个自身宽度，中心从画布左外侧扫到右外侧；宽度未知时先落纯底色，避免 1px 退化的贴边渐变
    val band = width.coerceAtLeast(1f)
    val center = band * (progress * 3f - 1f)

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size: IntSize -> width = size.width.toFloat() }
            .background(
                if (width > 0f) {
                    Brush.linearGradient(
                        colors = listOf(base, highlight, base),
                        start = Offset(center - band, 0f),
                        end = Offset(center + band, 0f),
                    )
                } else {
                    SolidColor(base)
                }
            ),
    )
}

/** 加载失败：surface 底色 + 居中内置图标 */
@Composable
private fun LoadFailurePlaceholder() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
