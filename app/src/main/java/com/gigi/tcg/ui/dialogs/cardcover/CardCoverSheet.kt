// 卡面预览弹层：设计文档 §4.5 —— CardCoverDialog 内容为竖版长卡面，故选 ModalBottomSheet。
// 状态机与切换语义移植 web/src/components/CardCoverDialog.tsx（PNG↔GIF、打开复位 PNG、
// 下载按钮在 info 缺失时禁用）；图片加载态由 AppImage 的 shimmer 承担。
// 与 Web 版差异：① 保存成功后 onDismiss 关闭弹层（toast 由宿主渲染，会被 Sheet 遮挡）；
// ② gold_img 缺失/空串的卡隐藏格式选择器，只允许下载普通卡面。
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gigi.tcg.R
import com.gigi.tcg.ui.components.AppImage
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LocalToast
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.theme.Motion

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
        if (granted) viewModel.download(showToast, onDismiss)
    }
    val startDownload: () -> Unit = {
        if (requiresWriteExternalPermission() && !hasWriteExternalPermission(context)) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.download(showToast, onDismiss)
        }
    }

    val content = state as? CoverUiState.Content
    val title = content?.info?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.cover_title_fallback)

    // 「卡面加载完成」判定依据（读自 CardCoverViewModel 的 CoverUiState 状态机）：
    // 仅 `state is CoverUiState.Content` 代表数据就绪——Loading=详情请求中、Error=请求/解析失败，均未就绪；
    // 再看 `content.imageUrl != null`（它取自 info.commonImg/goldImg 且过滤空串）：非空即预览位从
    // LoadingView 切到 CoverPreview、AppImage 开始渲染卡面，等价于「卡面可展示」。
    // 说明：Coil 的图片解码 Success 由 AppImage 内部 painter 私有持有、未对外回调，故以「数据就绪且卡面地址可用」为准，
    // 这也是本文件能与 ViewModel 契约对齐、不新增依赖的最小信号（ViewModel 无需改动）。
    val coverReady = content?.imageUrl != null

    // 一次性闩：按 contentId remember ⇒ 本次打开只自动展开一次；重开新卡（或退出重进）自动复位。
    var autoExpanded by remember(contentId) { mutableStateOf(false) }

    // key 用就绪信号（非 Unit）：coverReady 由 false→true 才触发一次展开。
    // 为什么只展开一次、不与手势打架：
    //  ① 切格式 PNG↔GIF 只改 imageUrl、不改本布尔，key 不变 ⇒ 不会反复弹开；
    //  ② 展开后用户手动下拖到半屏/关闭时，重组不会改 key ⇒ 本 effect 不重跑，不会再抢动画；
    //  ③ autoExpanded 兜住「就绪信号因重试等原因再次翻回 true」的重复触发。
    // 对话框刚 show 的一瞬通常还是 Loading（网络异步），就绪往往晚于入场动画 ⇒ 不与入场滑入抢；
    // 再加以 currentValue!=Expanded 为条件，用户已抢先上拖到全展开时直接跳过，避免无谓动画。
    LaunchedEffect(coverReady) {
        if (coverReady && !autoExpanded) {
            autoExpanded = true
            if (sheetState.currentValue != SheetValue.Expanded) {
                // 用 SheetState.expand()：本工程实际解析到的 material3 是 1.3.2，
                // 该版本的 animateTo/snapTo 都是 internal（名字被编译器加了 $material3_release 后缀），
                // 对外的公开入口就是成员函数 expand()/partialExpand()/show()/hide()，无需 import。
                // 保持 skipPartiallyExpanded=false ⇒ 半屏档仍在，展开到最大后用户仍可向下拖到半屏或关闭。
                sheetState.expand()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
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

            // 无动态卡面（gold_img 缺失/空串）或数据未就绪时整块隐藏选择器，只保留普通卡面下载
            if (content != null && content.hasDynamic) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    CoverFormat.entries.forEachIndexed { index, format ->
                        SegmentedButton(
                            selected = content.format == format,
                            onClick = { viewModel.selectFormat(format) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = CoverFormat.entries.size,
                            ),
                            label = { Text(stringResource(format.labelRes)) },
                        )
                    }
                }
            }

            Button(
                onClick = startDownload,
                enabled = content?.imageUrl != null,
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
                when (val current = state) {
                    CoverUiState.Loading -> LoadingView(label = stringResource(R.string.state_cover_loading))

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
        EmptyState(title = stringResource(R.string.cover_empty_format))
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
                contentDescription = "${state.info?.name.orEmpty()} ${stringResource(state.format.labelRes)}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
