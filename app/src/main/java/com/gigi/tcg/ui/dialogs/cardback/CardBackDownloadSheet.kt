// 卡背下载弹层（V38-D）：与卡牌图鉴的 CardCoverSheet 同一形态（ModalBottomSheet + 大图预览 + 下载按钮），
// 但**没有 ViewModel**：卡背直链在进页时已随 gcg/cardBackList 落到内存（MyViewModel.cardBackList），
// 打开弹层不需要再取任何数据 ⇒ 建 VM + StateFlow 状态机只会复刻 CardCoverViewModel 的形、却无其因。
// 落盘复用 CardImageSaver（Q+/非 Q+ 双路径、权限、文件名清洗都在它内层），本页不写 MediaStore。
// 成功提示走工程既有「带『查看』→ openInGallery」链路（LocalToastAction），失败只出可读文案、永不抛到 UI。

package com.gigi.tcg.ui.dialogs.cardback

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgCardBack
import com.gigi.tcg.domain.Throttle
import com.gigi.tcg.ui.components.AppImage
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.LocalToast
import com.gigi.tcg.ui.components.LocalToastAction
import com.gigi.tcg.ui.components.openInGallery
import com.gigi.tcg.ui.dialogs.cardcover.CoverFormat
import com.gigi.tcg.ui.dialogs.cardcover.CardImageSaver
import com.gigi.tcg.ui.dialogs.cardcover.exportDateText
import com.gigi.tcg.ui.dialogs.cardcover.exportDirName
import com.gigi.tcg.ui.dialogs.cardcover.hasWriteExternalPermission
import com.gigi.tcg.ui.dialogs.cardcover.requiresWriteExternalPermission
import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch

/** 卡背原图实测 420×720（7:12），与卡面同比例 ⇒ 预览尺寸口径照 CardCoverSheet，窄屏按宽度自适应 */
private const val CARD_BACK_ASPECT_RATIO: Float = 7f / 12f
private val PREVIEW_MAX_HEIGHT = 360.dp
private val PREVIEW_MAX_WIDTH = PREVIEW_MAX_HEIGHT * CARD_BACK_ASPECT_RATIO

/** 连点节流：与卡面下载同一档（1s），一次下载动辄几百 KB，不给图床重复发同一张 */
private const val DOWNLOAD_THROTTLE_MS = 1000L

/**
 * [cardBack] 为 null 时不渲染弹层（对齐 CardCoverSheet 的 `contentId == null` 语义）。
 * 预览与下载**同一 URL 源**（[resolveCardBackImageUrl]），未收集的卡背由调用点置灰、不给点进来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardBackDownloadSheet(
    cardBack: GcgCardBack?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val showToast = LocalToast.current
    val showToastAction = LocalToastAction.current
    val saver = remember(appContext) { CardImageSaver(appContext) }
    val scope = rememberCoroutineScope()
    val throttle = remember { Throttle(DOWNLOAD_THROTTLE_MS) }
    // downloading 挂在 cardBack 上：换一张卡背即复位，异常/取消路径由 finally 兜住
    var downloading by remember(cardBack) { mutableStateOf(false) }

    val downloadUrl = resolveDownloadable(cardBack)
    val label = stringResource(R.string.cardback_name_fallback)
    val savedText = stringResource(R.string.toast_saved_to_album)
    val viewText = stringResource(R.string.action_view)
    val fallbackText = stringResource(R.string.toast_save_failed)

    val startDownload: () -> Unit = {
        if (downloadUrl != null && !downloading && throttle()) {
            scope.launch {
                downloading = true
                try {
                    // 卡背图恒 PNG（实测 28/28 的 image_v2 都是 .png）⇒ 格式写死，不做扩展名嗅探
                    val uri = saver.save(
                        url = downloadUrl,
                        name = cardBackBaseName(cardBack, exportDateText(), label),
                        format = CoverFormat.Png,
                        subDir = exportDirName(EXPORT_DIR_CARDBACK),
                    )
                    // ≤API 28 那条路径没有 MediaStore uri 可返回 ⇒ 只出纯文本，不挂「查看」
                    if (uri == null) showToast(savedText) else showToastAction(savedText, viewText) {
                        openInGallery(appContext, uri)
                    }
                    onDismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 失败保持弹层打开：CardImageSaver 抛的就是已本地化的可读文案
                    showToast(e.message ?: fallbackText)
                } finally {
                    downloading = false
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startDownload() }

    if (cardBack == null) return

    val title = cardBack.id?.let { "$label $it" } ?: label

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
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
                        contentDescription = stringResource(R.string.action_close),
                    )
                }
            }

            Button(
                // 直链缺失（两个图字段都空）时禁用，与卡面「info 缺失即禁下载」同一判据
                enabled = downloadUrl != null && !downloading,
                onClick = {
                    if (requiresWriteExternalPermission() && !hasWriteExternalPermission(context)) {
                        permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        startDownload()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.action_download))
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (downloadUrl == null) {
                    EmptyState(title = stringResource(R.string.cardback_empty_image))
                } else {
                    Box(
                        modifier = Modifier
                            .widthIn(max = PREVIEW_MAX_WIDTH)
                            .aspectRatio(CARD_BACK_ASPECT_RATIO)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 加载态由 AppImage 的 shimmer 承担（同 CardCoverSheet），本页不再叠一层状态
                        AppImage(
                            model = downloadUrl,
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
