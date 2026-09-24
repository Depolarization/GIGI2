// 卡面预览弹层：设计文档 §4.5 —— CardCoverDialog 内容为竖版长卡面，故选 ModalBottomSheet。
// 状态机与切换语义移植 web/src/components/CardCoverDialog.tsx（PNG↔GIF、打开复位 PNG、
// 下载按钮在 info 缺失时禁用）；图片加载态由 Coil 承担，不再有 Web 版的 imgLoading 遮罩。

package com.gigi.tcg.ui.dialogs.cardcover

import android.Manifest
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LocalToast
import com.gigi.tcg.ui.components.LoadingView

private const val SHEET_TITLE_FALLBACK = "卡面"
private const val SHEET_LOADING_LABEL = "正在加载卡面数据"
private const val SHEET_EMPTY_FORMAT = "该格式暂无卡面图片"
private const val DOWNLOAD_LABEL = "下载"
private val PREVIEW_HEIGHT = 360.dp

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

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
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

            when (val current = state) {
                CoverUiState.Loading -> LoadingView(label = SHEET_LOADING_LABEL)

                is CoverUiState.Error -> ErrorState(
                    message = current.message,
                    onRetry = { viewModel.retry() },
                )

                is CoverUiState.Content -> CoverContent(
                    state = current,
                    onSelectFormat = { viewModel.selectFormat(it) },
                )
            }

            Button(
                onClick = startDownload,
                enabled = content?.imageUrl != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Text(DOWNLOAD_LABEL)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverContent(
    state: CoverUiState.Content,
    onSelectFormat: (CoverFormat) -> Unit,
) {
    val url = state.imageUrl
    if (url == null) {
        EmptyState(title = SHEET_EMPTY_FORMAT)
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = state.info?.name?.let { "$it ${state.format.label}" } ?: state.format.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        CoverFormat.entries.forEachIndexed { index, format ->
            SegmentedButton(
                selected = state.format == format,
                onClick = { onSelectFormat(format) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = CoverFormat.entries.size,
                ),
                label = { Text(format.label) },
            )
        }
    }
}
