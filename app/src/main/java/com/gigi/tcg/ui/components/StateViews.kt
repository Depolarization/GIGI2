package com.gigi.tcg.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 加载态：居中环 + 可选文案（对应 Feedback.tsx Spinner） */
@Composable
fun LoadingView(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 2.dp,
        )
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** 空态：居中图标 + 标题 + 可选描述（对应 Feedback.tsx EmptyState） */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String,
    message: String? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon ?: Icons.Outlined.Inbox,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 错误态：居中图标 + 文案 + 可选重试（对应 Feedback.tsx ErrorState） */
@Composable
fun ErrorState(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
                Text("重试")
            }
        }
    }
}

/**
 * 页面级三态容器：内容不足一屏时整屏居中、内容超一屏时可滚动。
 *
 * 缺陷 B：Loading/Error/Empty 三态原本是不可滚动的 Box，PullToRefreshBox 收不到
 * nestedScroll 事件 → 这些状态下拉无反应。包一层 verticalScroll 使其可下拉；
 * 修饰符顺序：BoxWithConstraints 必须在**外层**只挂 fillMaxSize()，这样 maxHeight
 * 拿到的是有界的视口高（verticalScroll 若挂在外层，它会把传给 BoxWithConstraints 的
 * maxHeight 放宽成 Constraints.Infinity，viewportHeight 随之变成 ~Int.MAX_VALUE，
 * 内层 heightIn(min=…) 把盒子撑到屏幕外，三态居中即变成空白）。
 * verticalScroll 放到内层 Box，且必须在 heightIn **之前**：先由 verticalScroll 把
 * 传给 heightIn 的 maxHeight 放宽成无限，heightIn(min=视口高) 才能在内容不足一屏时
 * 撑满视口让 Center 生效、超一屏时随内容增高并可滚动。
 */
@Composable
fun CenteredScrollableContainer(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
