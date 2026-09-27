// 卡牌使用详情页面：移植 web/src/pages/CardStatsPage.tsx 的版式与文案（§5.6）。
// 两 Tab（角色牌/行动牌，TabRow 指示器 + HorizontalPager 左右滑动，交互照抄 RankRoute）
// + 角色牌三键排序 + 行动牌类型筛选 + 玩家信息卡可展开详情
// （默认折叠，分组对齐 web 的"行动牌详情 / 足迹"）。搜索框本版未含（派单范围外）。
// V27：一页只呈现一张表 ⇒ 个人信息卡下方那对导出按钮删除，顶栏行只留一个「导出当前图表」
// 图标按钮（语义=导出当前 tab 对应的表）；导出流程本身抽到 CardStatsExportAction.kt。

package com.gigi.tcg.ui.screens.cardstats

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as ComposeModifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgSummary
import com.gigi.tcg.domain.calcPercent
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.theme.Motion
import kotlinx.coroutines.launch

/** 统计数值列定义：固定列宽 + 右对齐 + 等宽数字，保证四列纵向对齐。 */
private data class StatColumn(@StringRes val labelRes: Int, val width: Dp)

private val StatCountWidth: Dp = 48.dp
private val StatPercentWidth: Dp = 60.dp
private val ListRowVerticalPadding = 8.dp
private val CHAR_STAT_COLUMNS = listOf(
    StatColumn(R.string.stat_appear, StatCountWidth),
    StatColumn(R.string.stat_appear_rate, StatPercentWidth),
    StatColumn(R.string.stat_win_rate, StatPercentWidth),
    StatColumn(R.string.stat_wins, StatCountWidth),
)

// 签名由派单固定：container 供 VM factory 与 sessionUid（导出长图副标题）、onShowToast 供导出结果反馈。
@Composable
fun CardStatsRoute(
    container: AppContainer,
    onShowToast: (String) -> Unit,
    modifier: ComposeModifier = ComposeModifier,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: CardStatsViewModel = viewModel(key = "cardStats", factory = CardStatsViewModel.factory(app))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uid = container.sessionUid.value.orEmpty()

    // 三态与其余三页统一：共享组件内部 fillMaxWidth 会覆盖外部 align，
    // 统一用 Box 居中承载，避免 LoadingView 被拉成整屏高。
    // 全屏加载仅在"首屏无数据"时出现；下拉刷新（有 summary 的 loading）保留内容 + 刷新指示器。
    when {
        state.loading && state.summary == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingView(label = stringResource(R.string.state_stats_loading))
        }

        state.error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val retry: (() -> Unit)? = if (state.errorCanRetry) viewModel::retry else null
            ErrorState(message = state.error ?: "", onRetry = retry)
        }

        state.isEmpty -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(title = stringResource(R.string.state_empty_response))
        }

        else -> CardStatsContent(state, viewModel, uid, onShowToast, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardStatsContent(
    state: StatsUiState,
    viewModel: CardStatsViewModel,
    uid: String,
    onShowToast: (String) -> Unit,
    modifier: ComposeModifier,
) {
    val context = LocalContext.current
    // 指示器只由下拉手势（refresh()）驱动；不能用 state.loading——
    // 其默认值为 true，冷启动首屏会与居中 LoadingView 叠成两个圈。
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { STATS_TAB_COUNT })
    // tab 点击 → 翻页：与 RankRoute / CardWikiRoute 同一写法（rememberCoroutineScope + launch）
    val scope = rememberCoroutineScope()
    // 一页一张表 ⇒ 顶栏单按钮恒导出当前页对应的表
    val charTable = isCharTable(pagerState.currentPage)
    val exportAction = rememberStatsExporter(
        context = context,
        uid = uid,
        charTable = charTable,
        cards = if (charTable) state.charList else state.actionList,
        summary = state.summary,
        onResult = onShowToast,
    )

    Column(modifier = modifier.fillMaxSize()) {
        // 顶栏行（页内）：宿主 Scaffold 的 TopAppBar 由 GigiNavHost 统一持有且不在本棒
        // 可改文件内，故导出入口按「顶栏右缘动作」样式落在页内右上角；三页顶栏一致性的
        // 真正合并（把按钮上移到共享 TopAppBar）留给集成方，见 A2.md 遗留项。
        Row(
            modifier = ComposeModifier
                .fillMaxWidth()
                .padding(end = 4.dp, top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(
                onClick = exportAction.run,
                enabled = exportAction.enabled && !exportAction.exporting,
            ) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = stringResource(R.string.stats_export_current),
                )
            }
        }

        state.summary?.let { summary ->
            Column(ComposeModifier.padding(horizontal = 16.dp)) {
                PlayerInfoCard(summary, state.avatarUrl, detailOpen = state.detailOpen, onToggle = viewModel::toggleDetail)
            }
        }

        TabRow(
            selectedTabIndex = pagerState.currentPage.coerceIn(0, STATS_TAB_COUNT - 1),
            indicator = { tabPositions ->
                val lastIndex = STATS_TAB_COUNT - 1
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
                    ComposeModifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.BottomStart)
                        .offset { IntOffset(leftDp.roundToPx(), 0) }
                        .width(rightDp - leftDp),
                )
            },
        ) {
            // 本页数据首屏一次性全量加载、tab 只切展示 ⇒ 不需要 RankRoute 那套「落定页」的数据加载回调；
            // indicator 实时跟随用 currentPage（勿改成落定页，否则滑动时指示器会滞后一整段动画）。
            STATS_TAB_LABEL_RES.forEachIndexed { index, labelRes ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(stringResource(labelRes)) },
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            modifier = ComposeModifier.fillMaxSize(),
        ) {
            HorizontalPager(state = pagerState, modifier = ComposeModifier.fillMaxSize()) { page ->
                if (isCharTable(page)) {
                    CharStatsPage(state, viewModel)
                } else {
                    ActionStatsPage(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun CharStatsPage(state: StatsUiState, viewModel: CardStatsViewModel) {
    Column(
        ComposeModifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = ComposeModifier.fillMaxWidth()) {
            CharSortKey.entries.forEachIndexed { index, key ->
                SegmentedButton(
                    selected = state.charSort == key,
                    onClick = { viewModel.setCharSort(key) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = CharSortKey.entries.size),
                ) {
                    Text(stringResource(key.labelRes))
                }
            }
        }
        if (state.sortedCharList.isEmpty()) {
            NoMatchHint(R.string.stats_no_match_char)
        } else {
            CharTableHeader()
            state.sortedCharList.forEach { card ->
                CharCardRow(card, charTotalUse = state.charTotalUse)
            }
        }
    }
}

@Composable
private fun ActionStatsPage(state: StatsUiState, viewModel: CardStatsViewModel) {
    Column(
        ComposeModifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = ComposeModifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionTypeFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.actionType == filter,
                    onClick = { viewModel.setActionType(filter) },
                    label = { Text(stringResource(filter.labelRes)) },
                )
            }
        }
        if (state.filteredActionList.isEmpty()) {
            NoMatchHint(R.string.stats_no_match_action)
        } else {
            state.filteredActionList.forEach { card ->
                ActionCardRow(card)
            }
        }
    }
}

@Composable
private fun CharTableHeader() {
    Row(
        ComposeModifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.card_type_character),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = ComposeModifier.weight(1f),
        )
        CHAR_STAT_COLUMNS.forEach { col ->
            Text(
                stringResource(col.labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = ComposeModifier.width(col.width),
            )
        }
    }
}

@Composable
private fun CharCardRow(card: GcgCard, charTotalUse: Int) {
    val useCount = card.useCount ?: 0
    val wins = card.proficiency ?: 0
    val values = listOf(
        useCount.toString(),
        formatStatPercent(calcPercent(useCount.toDouble(), charTotalUse.toDouble())),
        formatStatPercent(calcPercent(wins.toDouble(), useCount.toDouble())),
        wins.toString(),
    )
    Row(
        ComposeModifier
            .fillMaxWidth()
            .padding(vertical = ListRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            card.name ?: stringResource(R.string.common_unknown),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = ComposeModifier.weight(1f),
        )
        values.forEachIndexed { index, value ->
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = ComposeModifier.width(CHAR_STAT_COLUMNS[index].width),
            )
        }
    }
}

@Composable
private fun ActionCardRow(card: GcgCard) {
    Row(
        ComposeModifier
            .fillMaxWidth()
            .padding(vertical = ListRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            card.name ?: stringResource(R.string.common_unknown),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = ComposeModifier.weight(1f),
        )
        Spacer(ComposeModifier.width(12.dp))
        Text(
            stringResource(R.string.stat_appear),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(ComposeModifier.width(8.dp))
        Text(
            (card.useCount ?: 0).toString(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = ComposeModifier.width(StatCountWidth),
        )
    }
}

@Composable
private fun NoMatchHint(@StringRes messageRes: Int) {
    Text(
        stringResource(messageRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = ComposeModifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    )
}

@Composable
private fun PlayerInfoCard(
    summary: GcgSummary,
    avatarUrl: String?,
    detailOpen: Boolean,
    onToggle: () -> Unit,
) {
    Card(ComposeModifier.fillMaxWidth()) {
        Column(ComposeModifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(url = avatarUrl, size = 64.dp, contentDescription = summary.nickname)
                Spacer(ComposeModifier.width(12.dp))
                Text(
                    summary.nickname,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = ComposeModifier.weight(1f),
                )
            }
            Row(ComposeModifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric(R.string.stats_total_games, summary.totalGames.toString())
                Metric(R.string.stats_win_games, summary.winGames.toString())
                Metric(R.string.stats_action_played, summary.actionTotalUse.toString())
                Metric(R.string.stats_total_win_rate, summary.winRate)
            }
            FilledTonalButton(onClick = onToggle, modifier = ComposeModifier.fillMaxWidth()) {
                val detailToggleLabel = stringResource(
                    if (detailOpen) R.string.stats_detail_collapse else R.string.stats_detail_expand,
                )
                Text(detailToggleLabel)
                Icon(
                    if (detailOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = detailToggleLabel,
                )
            }
            AnimatedVisibility(
                visible = detailOpen,
                enter = expandVertically(Motion.emphasized<IntSize>()) + fadeIn(Motion.emphasized<Float>()),
                exit = shrinkVertically(Motion.emphasized<IntSize>()) + fadeOut(Motion.emphasized<Float>()),
            ) {
                Column {
                    DetailGroup(
                        R.string.stats_action_detail,
                        listOf(
                            DetailRow(R.string.card_type_modify, summary.modifyUse.toString(), summary.modifyPercent),
                            DetailRow(R.string.card_type_assist, summary.assistUse.toString(), summary.assistPercent),
                            DetailRow(R.string.card_type_event, summary.eventUse.toString(), summary.eventPercent),
                        ),
                    )
                    DetailGroup(
                        R.string.stats_footprint,
                        listOf(
                            DetailRow(R.string.stats_player_level, summary.level.toString()),
                            DetailRow(R.string.stats_char_collected, summary.avatarCardNum.toString()),
                            DetailRow(R.string.stats_action_collected, summary.actionCardNum.toString()),
                        ),
                    )
                }
            }
        }
    }
}

/** 详情面板一行：label 存资源 id，hintPercent 非空时才拼「占比」（null = 无占比列） */
private data class DetailRow(
    @StringRes val labelRes: Int,
    val value: String,
    val hintPercent: String? = null,
)

@Composable
private fun Metric(@StringRes labelRes: Int, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DetailGroup(@StringRes titleRes: Int, rows: List<DetailRow>) {
    Column(ComposeModifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        rows.forEach { row ->
            val label = stringResource(row.labelRes)
            // 「（占比 x%）」整体是一句资源：括号随语种走（英文资源用 ASCII 括号），代码不拼括号
            val hint = row.hintPercent?.let { stringResource(R.string.stats_ratio, it) }
            Row(ComposeModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = ComposeModifier.weight(1f),
                )
                Text(
                    if (hint != null) row.value + hint else row.value,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
