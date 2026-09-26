// 积分排行榜页：移植 web/src/pages/RankPage.tsx（设计 §5.5）。
// 巅峰/赛事两 Tab（TabRow 指示器 + HorizontalPager 左右滑动）+ 分页 LazyColumn
// （首屏 60 条，滚到底自动追加）+ 下拉刷新当前 Tab（PullToRefreshBox → viewModel.retry，
// Loading/Error/Empty 三态同样可下拉）；
// 名次 = 下标 + 1，前三名固定金/银/铜语义色（不参与动态取色，设计红线 8，
// 色值对齐 tokens.css --color-gold/silver/bronze）；点击行回调 onOpenPlayerDetail(uid)。

package com.gigi.tcg.ui.screens.rank

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.data.model.RankInfo
import com.gigi.tcg.data.repo.RankTab
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.components.CenteredScrollableContainer
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.theme.GoldColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull

private val RankSilverColor = Color(0xFF9AA2AD)
private val RankBronzeColor = Color(0xFFB07A4A)

private val rankTabs = listOf(RankTab.Peak, RankTab.Competition)

@Composable
private fun tabTitle(tab: RankTab): String =
    stringResource(if (tab == RankTab.Peak) R.string.score_peak else R.string.score_competition)

private fun uidOf(info: RankInfo): String = info.uid?.contentOrNull.orEmpty()

private fun rankListFor(state: RankUiState, tab: RankTab): AsyncRankList =
    if (tab == RankTab.Peak) state.peak else state.competition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankRoute(
    container: AppContainer,
    onOpenPlayerDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: RankViewModel = viewModel(factory = RankViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val visibleCount by viewModel.visibleCount.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { rankTabs.size })
    val scope = rememberCoroutineScope()

    // pager 落定页 → VM 单选切换；selectTab 内有 activeTab 幂等保护（同 tab 直接 return），
    // 且 VM 不回写 pager，单向数据流无回环。
    // ⚠️ 这里刻意用 settledPage（而非 currentPage）：数据加载只在滑动落定后触发，
    // 来回拖拽不反复请求；indicator 的实时跟随（下方 TabRow 用 currentPage）与它无关。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            viewModel.selectTab(rankTabs[page])
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = pagerState.currentPage.coerceIn(0, rankTabs.lastIndex),
            indicator = { tabPositions ->
                val lastIndex = rankTabs.lastIndex
                // 连续页码 = 最近页 + 相对偏移（currentPageOffsetFraction ∈ [-0.5, 0.5]），
                // 用连续量插值 ⇒ 正向/反向滑动都跟手（V7G 修反向跳变：旧代码把负 fraction
                // coerceIn(0f, 1f) 夹成 0，反向拖动时 indicator 停在原 tab 直到翻页才跳）。
                val continuous = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .coerceIn(0f, lastIndex.toFloat())
                val lo = continuous.toInt().coerceIn(0, lastIndex) // 非负 ⇒ toInt() 即 floor
                val hi = (lo + 1).coerceAtMost(lastIndex)
                val t = (continuous - lo).coerceIn(0f, 1f)
                val from = tabPositions[lo]
                val to = tabPositions[hi]
                val leftDp: Dp = from.left + (to.left - from.left) * t
                val rightDp: Dp = from.right + (to.right - from.right) * t
                // 必须与官方 tabIndicatorOffset 同构：先 fillMaxWidth + wrapContentSize(BottomStart)
                // 解开 TabRow 传给 indicator 的固定宽度约束（整行宽），否则显式宽度修饰符
                // 默认 enforceIncoming=true，被 constrainWidth 夹到整行宽
                // ⇒ indicator 横贯整个 TabRow（V7G 修的正是 V7A 的这个回归）。
                TabRowDefaults.SecondaryIndicator(
                    Modifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.BottomStart)
                        .offset { IntOffset(leftDp.roundToPx(), 0) }
                        .width(rightDp - leftDp),
                )
            },
        ) {
            rankTabs.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(tabTitle(tab)) },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::retry,
            modifier = Modifier.fillMaxSize(),
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                RankPageContent(
                    tab = rankTabs[page],
                    list = rankListFor(state, rankTabs[page]),
                    visibleCount = visibleCount,
                    onRetry = viewModel::retry,
                    onLoadMore = viewModel::loadMore,
                    onOpenPlayerDetail = onOpenPlayerDetail,
                )
            }
        }
    }
}

@Composable
private fun RankPageContent(
    tab: RankTab,
    list: AsyncRankList,
    visibleCount: Int,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenPlayerDetail: (String) -> Unit,
) {
    when (list) {
        AsyncRankList.Loading, AsyncRankList.NotLoaded -> CenteredScrollableContainer {
            LoadingView(label = stringResource(R.string.state_rank_loading))
        }

        is AsyncRankList.Error -> CenteredScrollableContainer {
            ErrorState(modifier = Modifier.fillMaxWidth(), message = list.message, onRetry = onRetry)
        }

        is AsyncRankList.Content -> {
            if (list.items.isEmpty()) {
                CenteredScrollableContainer {
                    EmptyState(
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.rank_empty, tabTitle(tab)),
                    )
                }
            } else {
                val shown = remember(list.items, visibleCount) { list.items.take(visibleCount) }
                val hasMore = shown.size < list.items.size
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(shown, key = { index, info -> "${uidOf(info)}-$index" }) { index, info ->
                        RankRow(
                            info = info,
                            rank = index + 1,
                            tab = tab,
                            onClick = { onOpenPlayerDetail(uidOf(info)) },
                        )
                    }
                    if (hasMore) {
                        item(key = "rank-sentinel") {
                            // 哨兵进入组合即触底：自动追加一页（对齐 IntersectionObserver 语义）
                            LaunchedEffect(Unit) { onLoadMore() }
                            Text(
                                text = stringResource(R.string.rank_shown_count, shown.size, list.items.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// 三态（Loading/Error/Empty）用共享 CenteredScrollableContainer：整屏居中且内部可滚动，
// 否则 PullToRefreshBox 收不到 nestedScroll，这些状态下拉无反应（缺陷 B，见该组件 KDoc）。

@Composable
private fun RankRow(
    info: RankInfo,
    rank: Int,
    tab: RankTab,
    onClick: () -> Unit,
) {
    val medalColor = when (rank) {
        1 -> GoldColor
        2 -> RankSilverColor
        3 -> RankBronzeColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val scoreText = if (tab == RankTab.Peak) {
        stringResource(R.string.rank_peak_line, info.peakScore ?: 0)
    } else {
        stringResource(R.string.rank_comp_line, info.score ?: 0)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 排名槽位：固定 32dp 宽 + 居中 ⇒ 玩家信息列左边缘恒定不随位数漂移；
        // 与右侧玩家信息块之间 16dp，等于行左 padding，使"排名"成为左右留白对称的
        // 独立视觉单元，避免数字与头像粘连混淆（V7F，M3 跨语义组间距）。
        // 刻意用显式 Spacer 而非 spacedBy：spacedBy 对所有子元素同间距，
        // 表达不了"排名↔头像 16dp（跨组）> 头像↔信息列 12dp（同组）"的层次。
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleMedium,
            color = medalColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(32.dp),
        )
        Spacer(Modifier.width(16.dp))
        Avatar(url = info.avatarUrl, size = 44.dp, contentDescription = info.nickname)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = info.nickname.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$scoreText　UID:${uidOf(info)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
