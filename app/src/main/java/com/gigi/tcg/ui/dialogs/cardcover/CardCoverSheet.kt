// 卡面预览弹层：设计文档 §4.5 —— CardCoverDialog 内容为竖版长卡面，故选 ModalBottomSheet。
// 状态机与切换语义移植 web/src/components/CardCoverDialog.tsx（PNG↔GIF、打开复位 PNG、
// 下载按钮在 info 缺失时禁用）；图片加载态由 AppImage 的 shimmer 承担。
//
// 折叠态布局约束：ModalBottomSheet 半屏时只有内容上部落在屏内（超出部分在屏幕下方），
// 所以"格式切换 + 下载"必须留在滚动区之外的固定块里，否则被长图挤到屏外；
// 预览图放在 weight(1f, fill=false) 的滚动区内，半屏见上部、上滑展开见全图。

package com.gigi.tcg.ui.dialogs.cardcover

import android.Manifest
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigi.tcg.ui.components.AppImage
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LocalToast
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.theme.Motion

private const val SHEET_TITLE_FALLBACK = "卡面"
private const val SHEET_LOADING_LABEL = "正在加载卡面数据"
private const val SHEET_EMPTY_FORMAT = "该格式暂无卡面图片"
private const val DOWNLOAD_LABEL = "下载"

/** 卡面 420x720（7:12）：高度上限 360dp 折算成宽度上限，窄屏按比例自适应用宽度撑 */
private const val CARD_ASPECT_RATIO: Float = 7f / 12f
private val PREVIEW_MAX_HEIGHT = 360.dp
private val PREVIEW_MAX_WIDTH = PREVIEW_MAX_HEIGHT * CARD_ASPECT_RATIO

/**
 * [contentId] 为 null 时不渲染弹层（对齐 Web 版 `open={contentId !== null}`）；
 * 非 null 时按 id 取详情。提示走 [LocalToast]，宿主未 provide 时静默。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardCoverSheet(
    contentId: Int?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: CardCoverViewModel = viewModel(
        factory = CardCoverViewModel.factory(context.applicationContext as Application),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val showToast = LocalToast.current

    LaunchedEffect(contentId) { viewModel.setContentId(contentId) }

    if (contentId == null) return

    // skipPartiallyExpanded=false：内容高过半屏时保留半屏↔全屏两档拖拽
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.download(showToast)
    }
    val startDownload: () -> Unit = {
        if (requiresWriteExternalPermission() && !hasWriteExternalPermission(context)) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.download(showToast)
        }
    }

    val content = state as? CoverUiState.Content
    val title = content?.info?.name?.takeIf { it.isNotBlank() } ?: SHEET_TITLE_FALLBACK

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "关闭",
                    )
                }
            }

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                CoverFormat.entries.forEachIndexed { index, format ->
                    SegmentedButton(
                        selected = content?.format == format,
                        enabled = content != null,
                        onClick = { viewModel.selectFormat(format) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = CoverFormat.entries.size,
                        ),
                        label = { Text(format.label) },
                    )
                }
            }

            Button(
                onClick = startDownload,
                enabled = content?.imageUrl != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(DOWNLOAD_LABEL)
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (val current = state) {
                    CoverUiState.Loading -> LoadingView(label = SHEET_LOADING_LABEL)

                    is CoverUiState.Error -> ErrorState(
                        message = current.message,
                        onRetry = { viewModel.retry() },
                    )

                    is CoverUiState.Content -> CoverPreview(current)
                }
            }
        }
    }
}

@Composable
private fun CoverPreview(state: CoverUiState.Content) {
    val url = state.imageUrl
    if (url == null) {
        EmptyState(title = SHEET_EMPTY_FORMAT)
        return
    }
    Box(
        modifier = Modifier
            .widthIn(max = PREVIEW_MAX_WIDTH)
            .aspectRatio(CARD_ASPECT_RATIO)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        // 切格式时新旧图交叉淡入，避免中间闪一张空 shimmer
        Crossfade(
            targetState = url,
            animationSpec = Motion.emphasized<Float>(),
            label = "cover-format-switch",
        ) { targetUrl ->
            AppImage(
                model = targetUrl,
                contentDescription = "${state.info?.name.orEmpty()} ${state.format.label}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
