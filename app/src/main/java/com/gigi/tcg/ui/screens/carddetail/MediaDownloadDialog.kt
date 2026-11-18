// 统一下载对话框（报告 §7）：卡面与技能图标共用一套「预览 +（可选）格式选择 + 保存 / 取消」。
// - BasicAlertDialog：28dp 圆角（shapes.extraLarge）、surfaceContainerHigh + 6dp tonal；
// - 标题 headlineSmall；预览卡面 117×200（7:12）/ 技能图标 96×96；
// - 格式选择仅当 options.size > 1（普通 + 动态）时渲染，默认选中普通卡面；
// - 保存中 positive 置灰，取消 / scrim / 返回键一律只关闭不保存。

package com.gigi.tcg.ui.screens.carddetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gigi.tcg.R
import com.gigi.tcg.ui.components.AppImage

/** 卡面预览固定 117 × 200（7:12，报告 §7 决策③） */
private const val COVER_ASPECT_RATIO: Float = 7f / 12f
private val COVER_PREVIEW_WIDTH = 117.dp
private val ICON_PREVIEW_SIZE = 96.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaDownloadDialog(
    state: DownloadDialogState,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (DownloadOption) -> Unit,
) {
    var selected by remember(state) { mutableIntStateOf(0) }
    val option = state.options.getOrNull(selected)

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(state.titleRes),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val url = option?.url ?: state.previewUrl
                    when (state.preview) {
                        DownloadPreview.Cover -> Box(
                            modifier = Modifier
                                .width(COVER_PREVIEW_WIDTH)
                                .aspectRatio(COVER_ASPECT_RATIO)
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        DownloadPreview.Icon -> Box(
                            modifier = Modifier
                                .size(ICON_PREVIEW_SIZE)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // 单格式（含技能图标）完全不渲染选择器（报告 §7 决策④）
                if (state.options.size > 1) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        state.options.forEachIndexed { index, item ->
                            SegmentedButton(
                                selected = index == selected,
                                onClick = { selected = index },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = state.options.size,
                                ),
                                label = {
                                    Text(
                                        text = stringResource(item.labelRes),
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = { option?.let(onSave) },
                        enabled = option != null && !saving,
                    ) {
                        Text(stringResource(R.string.download_action_save))
                    }
                }
            }
        }
    }
}
