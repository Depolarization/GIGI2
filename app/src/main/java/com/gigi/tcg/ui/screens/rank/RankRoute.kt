// 积分排行榜页：移植 web/src/pages/RankPage.tsx（设计 §5.5）。
// 巅峰/赛事两 Tab（TabRow 指示器 + HorizontalPager 左右滑动）+ 分页 LazyColumn
// （首屏 60 条，滚到底自动追加）+ 下拉刷新当前 Tab（PullToRefreshBox → viewModel.retry，
// Loading/Error/Empty 三态同样可下拉）；
// 名次 = 下标 + 1，前三名固定金/银/铜语义色（不参与动态取色，设计红线 8，
// 色值对齐 tokens.css --color-gold/silver/bronze）；点击行回调 onOpenPlayerDetail(uid)。

package com.gigi.tcg.ui.screens.rank

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.data.model.RankInfo
import com.gigi.tcg.data.repo.RankTab
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.theme.GoldColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull

private val RankSilverColor = Color(0xFF9AA2AD)
private val RankBronzeColor = Color(0xFFB07A4A)

private val rankTabs = listOf(RankTab.Peak, RankTab.Competition)

private fun tabTitle(tab: RankTab): String = if (tab == RankTab.Peak) "巅峰积分" else "赛事积分"

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
                val page = pagerState.currentPage.coerceIn(0, rankTabs.lastIndex)
                val current = tabPositions[page]
                val next = tabPositions.getOrNull(page + 1)
                val fraction = pagerState.currentPageOffsetFraction.coerceIn(0f, 1f)
                if (next != null && fraction > 0f) {
                    // 手指滑动中：在相邻两个 TabPosition（left/right 为 Dp）间线性插值，indicator 实时跟随
                    val leftDp: Dp = current.left + (next.left - current.left) * fraction
                    val rightDp: Dp = current.right + (next.right - current.right) * fraction
                    val indicatorModifier = with(LocalDensity.current) {
                        Modifier
                            .offset { IntOffset(leftDp.roundToPx(), 0) }
                            .width(rightDp - leftDp)
                    }
                    TabRowDefaults.SecondaryIndicator(indicatorModifier)
                } else {
                    TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(current))
                }
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
        AsyncRankList.Loading, AsyncRankList.NotLoaded -> CenteredScrollableBox {
            LoadingView()
        }

        is AsyncRankList.Error -> CenteredScrollableBox {
            ErrorState(modifier = Modifier.fillMaxWidth(), message = list.message, onRetry = onRetry)
        }

        is AsyncRankList.Content -> {
            if (list.items.isEmpty()) {
                CenteredScrollableBox {
                    EmptyState(modifier = Modifier.fillMaxWidth(), title = "${tabTitle(tab)}暂无上榜玩家")
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
                                text = "已显示 ${shown.size} / ${list.items.size} 名",
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

// 缺陷 B：Loading/Error/Empty 三态原本是不可滚动的 Box，PullToRefreshBox 收不到
// nestedScroll 事件 → 这些状态下拉无反应。包一层 verticalScroll 使其可下拉；
// 修饰符顺序：BoxWithConstraints 必须在**外层**只挂 fillMaxSize()，这样 maxHeight
// 拿到的是有界的视口高（verticalScroll 若挂在外层，它会把传给 BoxWithConstraints 的
// maxHeight 放宽成 Constraints.Infinity，viewportHeight 随之变成 ~Int.MAX_VALUE，
// 内层 heightIn(min=…) 把盒子撑到屏幕外，三态居中即变成空白）。
// verticalScroll 放到内层 Box，且必须在 heightIn **之前**：先由 verticalScroll 把
// 传给 heightIn 的 maxHeight 放宽成无限，heightIn(min=视口高) 才能在内容不足一屏时
// 撑满视口让 Center 生效、超一屏时随内容增高并可滚动。
@Composable
private fun CenteredScrollableBox(content: @Composable BoxScope.() -> Unit) {
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
    val scoreText = if (tab == RankTab.Peak) "巅峰积分:${info.peakScore ?: 0}" else "赛事积分:${info.score ?: 0}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleMedium,
            color = medalColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 28.dp),
        )
        Avatar(url = info.avatarUrl, size = 44.dp, contentDescription = info.nickname)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
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
