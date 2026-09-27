// 积分排行榜页：移植 web/src/pages/RankPage.tsx（设计 §5.5）。
// 巅峰/赛事两 Tab（TabRow 指示器 + HorizontalPager 左右滑动）+ 分页 LazyColumn
// （首屏 60 条，滚到底自动追加）+ 下拉刷新当前 Tab（PullToRefreshBox → viewModel.retry，
// Loading/Error/Empty 三态同样可下拉）；
// 名次 = 下标 + 1，前三名固定金/银/铜语义色（不参与动态取色，设计红线 8，
// 色值对齐 tokens.css --color-gold/silver/bronze）；点击行回调 onOpenPlayerDetail(uid, avatarUrl)。
// V26：行点击把本行头像一并带出去——排行榜接口必定返回头像，而详情接口在无权访问
// （is_shield）时不给头像，弹窗靠这份入口头像兜底，避免退化成占位图标。

package com.gigi.tcg.ui.screens.rank

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import com.gigi.tcg.ui.components.LocalToast
import com.gigi.tcg.ui.theme.GoldColor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull

private val RankSilverColor = Color(0xFF9AA2AD)
private val RankBronzeColor = Color(0xFFB07A4A)

// V27 行内间距常量（用户在真机上要求：名次列贴左缘、收紧名次列自身留白、拉开头像与文字块）。
// 刻意用 const Int + `.dp`：JVM 单测（RankRowSpacingTest 对源码文本断言）能直接解析数值做区间校验。

/** 名次列左侧距列表左缘：16→8，让名次贴近左缘（≤8 且 ≥4，避免数字顶到屏幕边） */
private const val RANK_COLUMN_START_DP = 8

/**
 * 名次槽固定宽度：titleMedium（16sp）下 4 位数最宽 ≈ 16 × 0.6 × 4 = 38.4dp
 * （拉丁数字 advance ≈ 0.6×字号），取 40dp ⇒ 1~4 位数都不撑破、不换行不省略，
 * 且头像 x 位置跨行恒定（固定宽而非 wrapContent，杜绝随位数抖动）。
 */
private const val RANK_SLOT_WIDTH_DP = 40

/** 名次↔头像组间距：16→8，收窄空隙但仍 ≥4dp，名次数字与头像圆不粘连 */
private const val RANK_AVATAR_GAP_DP = 8

/** 头像→右侧信息列：12→16，头像与文字块是不同语义单元，拉开后层次更清晰 */
private const val AVATAR_INFO_GAP_DP = 16

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
    onOpenPlayerDetail: (uid: String, avatarUrl: String?) -> Unit,
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
    onOpenPlayerDetail: (uid: String, avatarUrl: String?) -> Unit,
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
                            onClick = { onOpenPlayerDetail(uidOf(info), info.avatarUrl) },
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
    // V24（用户真机反馈图 3）：原先第二行是「巅峰积分:2310　UID:253991234」，
    // 两个文字标签把整行挤到 Ellipsis 截断。现按用户拍板去掉标签、只留数值：
    // 页头 Tab 已交代积分口径，靠「位置 + 颜色层级」区分 —— 积分主色稍重，UID 弱化色。
    val score = if (tab == RankTab.Peak) info.peakScore ?: 0 else info.score ?: 0
    val uid = uidOf(info)
    val scoreColor = MaterialTheme.colorScheme.onSurface
    val uidColor = MaterialTheme.colorScheme.onSurfaceVariant
    // V25：长按行直接复制该玩家 UID（与首页列表的长按语义一致）。
    val showToast = LocalToast.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val copiedToast = stringResource(R.string.toast_copied_uid, uid)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    @Suppress("DEPRECATION") clipboard.setText(AnnotatedString(uid))
                    showToast(copiedToast)
                },
            )
            .padding(start = RANK_COLUMN_START_DP.dp, end = 16.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 名次列：固定 RANK_SLOT_WIDTH_DP 宽 + 居中 ⇒ 头像 x 位置跨行恒定、不随位数漂移；
        // 左右留白（行首 8dp、名次↔头像 8dp）均落在 4~8dp 区间：比旧版 16dp 收紧，
        // 名次更贴近左缘，又不与头像粘连（V27 用户要求）。
        // 刻意用显式 Spacer 而非 spacedBy：spacedBy 对所有子元素同间距，
        // 表达不了"名次↔头像 8dp（收紧密接）< 头像↔信息列 16dp（跨语义组拉开）"的层次。
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleMedium,
            color = medalColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(RANK_SLOT_WIDTH_DP.dp),
        )
        Spacer(Modifier.width(RANK_AVATAR_GAP_DP.dp))
        Avatar(url = info.avatarUrl, size = 44.dp, contentDescription = info.nickname)
        Column(modifier = Modifier.weight(1f).padding(start = AVATAR_INFO_GAP_DP.dp)) {
            Text(
                text = info.nickname.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = scoreColor, fontWeight = FontWeight.Medium)) {
                        append("$score")
                    }
                    append(" · ")
                    withStyle(SpanStyle(color = uidColor)) { append(uid) }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scoreColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
