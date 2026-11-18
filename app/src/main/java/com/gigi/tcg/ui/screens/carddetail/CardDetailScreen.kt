// 卡牌详情全屏页（报告 §4 / §5 / §6）：
// ┌ 壳层 TopAppBar：标题「卡牌详情」+ 返回（GigiNavHost 提供，本页不自绘标题栏）
// ├ TabRow：唯一切换控件（卡面 / 技能或效果），48dp，指示器复用工程内三处同款实现
// └ HorizontalPager（默认第 0 页 = 卡面，进入即卡面）
//    ├ 卡面：卡名 titleMedium → 预览（7:12，按可用空间等比缩放）→ 下载按钮
//    └ 技能 / 效果：基础信息卡 → 章节标题（无数量）→ 技能卡 ×N / 效果卡 ×1
//
// 指示器：与 CardWikiRoute/ WikiTabs、RankRoute、CardStatsRoute 完全同构 ——
// HorizontalPager + TabRow + TabRowDefaults.SecondaryIndicator，
// 用 currentPage + currentPageOffsetFraction 连续插值（拖动跟手），点击走 animateScrollToPage。
// 🔴 不要退回 offset{} 直接跳变（工程内孤例，已被用户实测否决）。

package com.gigi.tcg.ui.screens.carddetail

import android.Manifest
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.domain.CardDetail
import com.gigi.tcg.domain.CardSkill
import com.gigi.tcg.ui.components.AppImage
import com.gigi.tcg.ui.components.CenteredScrollableContainer
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.components.LocalToast
import com.gigi.tcg.ui.dialogs.cardcover.hasWriteExternalPermission
import com.gigi.tcg.ui.dialogs.cardcover.requiresWriteExternalPermission
import kotlinx.coroutines.launch

/** 官方卡面 420x720 竖版（7:12） */
private const val CARD_ASPECT_RATIO: Float = 7f / 12f
private val PREVIEW_MAX_WIDTH = 360.dp
private const val PAGE_COUNT = 2

@Composable
fun CardDetailScreen(
    container: AppContainer,
    contentId: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val viewModel: CardDetailViewModel = viewModel(
        factory = CardDetailViewModel.factory(app, contentId),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val showToast = LocalToast.current
    val scope = rememberCoroutineScope()

    // 默认第 0 页 = 卡面（用户明确要求：进入详情先看卡面）
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { PAGE_COUNT })

    val content = state as? CardDetailUiState.Content
    val detail = content?.detail
    val tabTitles = listOf(
        stringResource(R.string.cover_tab_cover),
        stringResource(
            if (detail?.isAction == true) R.string.cover_tab_effect else R.string.cover_tab_skills,
        ),
    )

    var dialog by remember { mutableStateOf<DownloadDialogState?>(null) }
    var pendingDialog by remember { mutableStateOf<DownloadDialogState?>(null) }
    // 报告 §7.2「权限先行」：API < 29 在打开对话框之前申请，避免系统弹窗叠在应用对话框上
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) dialog = pendingDialog
        pendingDialog = null
    }
    val openDownload: (DownloadDialogState?) -> Unit = { target ->
        if (target != null) {
            if (requiresWriteExternalPermission() && !hasWriteExternalPermission(context)) {
                pendingDialog = target
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                dialog = target
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        DetailTabs(
            pagerState = pagerState,
            titles = tabTitles,
            onTabClick = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
        )

        Box(modifier = Modifier.weight(1f)) {
            when (val current = state) {
                CardDetailUiState.Loading -> CenteredScrollableContainer {
                    LoadingView(label = stringResource(R.string.state_cover_loading))
                }

                is CardDetailUiState.Error -> ErrorState(
                    modifier = Modifier.align(Alignment.Center),
                    message = current.message,
                    onRetry = { viewModel.retry() },
                )

                is CardDetailUiState.Content -> HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    if (page == 0) {
                        CoverTab(
                            detail = current.detail,
                            onDownload = openDownload,
                        )
                    } else {
                        DetailTab(
                            detail = current.detail,
                            onDownloadIcon = { skill ->
                                openDownload(CardDownloads.iconDialog(skill))
                            },
                        )
                    }
                }
            }
        }
    }

    dialog?.let { current ->
        MediaDownloadDialog(
            state = current,
            saving = saving,
            onDismiss = { dialog = null },
            onSave = { option ->
                // 失败保持对话框打开（VM 只 toast 归因），成功才关闭
                viewModel.download(option, showToast) { dialog = null }
            },
        )
    }
}

/** 页签：指示器几何与动效与图鉴 / 排行 / 统计三处完全一致（报告 §6.1） */
@Composable
private fun DetailTabs(
    pagerState: PagerState,
    titles: List<String>,
    onTabClick: (Int) -> Unit,
) {
    val selectedTabIndex = pagerState.currentPage.coerceIn(0, (titles.size - 1).coerceAtLeast(0))
    TabRow(
        selectedTabIndex = selectedTabIndex,
        indicator = { tabPositions ->
            if (tabPositions.isNotEmpty()) {
                val lastIndex = tabPositions.lastIndex
                // 连续页码 = 最近页 + 相对偏移；正向/反向拖动都跟手（同 WikiTabs / RankRoute V7G）
                val continuous = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .coerceIn(0f, lastIndex.toFloat())
                val lo = continuous.toInt().coerceIn(0, lastIndex)
                val hi = (lo + 1).coerceAtMost(lastIndex)
                val t = (continuous - lo).coerceIn(0f, 1f)
                val from = tabPositions[lo]
                val to = tabPositions[hi]
                val leftDp: Dp = from.left + (to.left - from.left) * t
                val rightDp: Dp = from.right + (to.right - from.right) * t
                // 与官方 tabIndicatorOffset 同构：先 fillMaxWidth + wrapContentSize 解开固定宽度约束
                TabRowDefaults.SecondaryIndicator(
                    Modifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.BottomStart)
                        .offset { IntOffset(leftDp.roundToPx(), 0) }
                        .width(rightDp - leftDp),
                )
            }
        },
    ) {
        titles.forEachIndexed { index, title ->
            Tab(
                selected = pagerState.currentPage == index,
                onClick = { onTabClick(index) },
                text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/**
 * 卡面 Tab（报告 §5.3）：卡名 → 预览 → 下载。
 * 格式选择（普通 / 动态）只放在下载对话框里：页面固定预览普通卡面，
 * 用户在对话框里切换并保存（V39-H 用户拍板回滚页面级 SegmentedButton）。
 */
@Composable
private fun CoverTab(
    detail: CardDetail,
    onDownload: (DownloadDialogState?) -> Unit,
) {
    val info = detail.info
    val name = info.name?.takeIf { it.isNotBlank() }
    val previewUrl = info.commonImg?.takeIf { it.isNotBlank() }
        ?: info.goldImg?.takeIf { it.isNotBlank() }
    val dialogState = remember(info) { CardDownloads.coverDialog(info) }

    Column(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)) {
        if (name != null) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (previewUrl == null) {
                EmptyState(title = stringResource(R.string.cover_empty_format))
            } else {
                // 宽 = min(可用宽, 360dp, 可用高 × 7/12)，高 = 宽 × 12/7 ⇒ 永远整图可见
                val cardWidth = minOf(maxWidth, PREVIEW_MAX_WIDTH, maxHeight * CARD_ASPECT_RATIO)
                Box(
                    modifier = Modifier
                        .width(cardWidth)
                        .aspectRatio(CARD_ASPECT_RATIO)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    AppImage(
                        model = previewUrl,
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onDownload(dialogState) },
            enabled = dialogState != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_download))
        }
    }
}

/** 技能 / 效果 Tab（报告 §5.4–5.6） */
@Composable
private fun DetailTab(
    detail: CardDetail,
    onDownloadIcon: (CardSkill) -> Unit,
) {
    val action = detail.isAction
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        item(key = "basic") {
            CardBasicInfoHeader(info = detail.info, isAction = action)
        }
        item(key = "section") {
            CardSectionLabel(
                // 距信息卡 16dp、距首张卡 8dp（报告 §5.4）
                title = stringResource(
                    if (action) R.string.cover_section_effect else R.string.cover_section_skills,
                ),
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
        }
        when {
            action -> detail.effect?.let { effect ->
                item(key = "effect") {
                    CardEffectCard(effect = effect, element = detail.element)
                }
            }

            detail.skills.isEmpty() -> item(key = "empty") {
                EmptyState(title = stringResource(R.string.cover_skills_empty))
            }

            else -> itemsIndexed(
                items = detail.skills,
                key = { index, skill -> "skill-$index-${skill.name}" },
            ) { index, skill ->
                CardSkillCard(
                    skill = skill,
                    element = detail.element,
                    onDownloadIcon = onDownloadIcon,
                    // 卡片之间 12dp（最后一张不占）
                    modifier = Modifier.padding(bottom = if (index == detail.skills.lastIndex) 0.dp else 12.dp),
                )
            }
        }
    }
}
