// 积分排行榜页：移植 web/src/pages/RankPage.tsx（设计 §5.5）。
// 巅峰/赛事两 Tab（TabRow）+ 分页 LazyColumn（首屏 60 条，滚到底自动追加）；
// 名次 = 下标 + 1，前三名固定金/银/铜语义色（不参与动态取色，设计红线 8，
// 色值对齐 tokens.css --color-gold/silver/bronze）；点击行回调 onOpenPlayerDetail(uid)。

package com.gigi.tcg.ui.screens.rank

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.serialization.json.contentOrNull

private val RankSilverColor = Color(0xFF9AA2AD)
private val RankBronzeColor = Color(0xFFB07A4A)

private val rankTabs = listOf(RankTab.Peak, RankTab.Competition)

private fun tabTitle(tab: RankTab): String = if (tab == RankTab.Peak) "巅峰积分" else "赛事积分"

private fun uidOf(info: RankInfo): String = info.uid?.contentOrNull.orEmpty()

@Composable
fun RankRoute(
    container: AppContainer,
    onOpenPlayerDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: RankViewModel = viewModel(factory = RankViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val visibleCount by viewModel.visibleCount.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = rankTabs.indexOf(state.activeTab)) {
            rankTabs.forEach { tab ->
                Tab(
                    selected = state.activeTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text(tabTitle(tab)) },
                )
            }
        }
        when (val list = if (state.activeTab == RankTab.Peak) state.peak else state.competition) {
            AsyncRankList.Loading, AsyncRankList.NotLoaded -> LoadingView(modifier = Modifier.fillMaxWidth())

            is AsyncRankList.Error -> ErrorState(
                modifier = Modifier.fillMaxWidth(),
                message = list.message,
                onRetry = { viewModel.retry() },
            )

            is AsyncRankList.Content -> {
                if (list.items.isEmpty()) {
                    EmptyState(
                        modifier = Modifier.fillMaxWidth(),
                        title = "${tabTitle(state.activeTab)}暂无上榜玩家",
                    )
                } else {
                    val shown = remember(list.items, visibleCount) { list.items.take(visibleCount) }
                    val hasMore = shown.size < list.items.size
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(shown, key = { index, info -> "${uidOf(info)}-$index" }) { index, info ->
                            RankRow(
                                info = info,
                                rank = index + 1,
                                tab = state.activeTab,
                                onClick = { onOpenPlayerDetail(uidOf(info)) },
                            )
                        }
                        if (hasMore) {
                            item(key = "rank-sentinel") {
                                // 哨兵进入组合即触底：自动追加一页（对齐 IntersectionObserver 语义）
                                LaunchedEffect(Unit) { viewModel.loadMore() }
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleMedium,
            color = medalColor,
            modifier = Modifier.width(36.dp),
        )
        Avatar(url = info.avatarUrl, size = 44.dp, contentDescription = info.nickname)
        Column(modifier = Modifier.weight(1f)) {
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
            )
        }
    }
}
