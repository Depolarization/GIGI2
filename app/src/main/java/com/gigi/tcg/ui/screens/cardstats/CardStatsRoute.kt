// 卡牌使用详情页面：移植 web/src/pages/CardStatsPage.tsx 的版式与文案（§5.6）。
// 两 Tab（角色牌/行动牌）+ 角色牌三键排序 + 行动牌类型筛选 + 玩家信息卡可展开详情
// （默认折叠，分组对齐 web 的"行动牌详情 / 足迹"）。搜索框本版未含（派单范围外）。

package com.gigi.tcg.ui.screens.cardstats

import android.app.Application
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.gigi.tcg.i18n.LocaleStrings
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.dialogs.cardcover.CardImageSaver
import com.gigi.tcg.ui.dialogs.cardcover.albumRelativePath
import com.gigi.tcg.ui.export.computeTableLayout
import com.gigi.tcg.ui.export.renderTableBitmap
import com.gigi.tcg.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val TAB_LABEL_IDS = listOf(R.string.card_type_character, R.string.card_type_action)

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
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    // 指示器只由下拉手势（refresh()）驱动；不能用 state.loading——
    // 其默认值为 true，冷启动首屏会与居中 LoadingView 叠成两个圈。
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    val appContext = LocalContext.current.applicationContext
    val coroutineScope = rememberCoroutineScope()
    // 两个导出按钮各自独立的 loading 态（点哪个转哪个，另一个仍可点）；
    // 底层渲染用互斥锁串行：行动牌 1600px 双栏长图降级后仍约 93MB，两张巨图同时分配必 OOM。
    var exportingChar by remember { mutableStateOf(false) }
    var exportingAction by remember { mutableStateOf(false) }
    val exportMutex = remember { Mutex() }

    // 角色牌/行动牌各出一张长图（DESIGN-V8 决策 4）。导出的是全量列表，
    // 不跟随本页的排序按钮与类型筛选（产品决策：分享出去的图要完整可比对）。
    val startExport: (Boolean) -> Unit = { charTable ->
        val summary = state.summary
        val busy = if (charTable) exportingChar else exportingAction
        if (summary != null && !busy) {
            if (charTable) exportingChar = true else exportingAction = true
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.Default) {
                        exportMutex.withLock {
                            val cards = if (charTable) state.charList else state.actionList
                            val spec = if (charTable) {
                                buildCharTableSpec(summary, uid, cards)
                            } else {
                                buildActionTableSpec(summary, uid, cards)
                            }
                            val layout = computeTableLayout(
                                spec, EXPORT_IMAGE_WIDTH_PX, exportTwoColumnThreshold(charTable),
                            )
                            val bitmap = renderTableBitmap(spec, layout)
                            try {
                                // quality 72 是 V9-C 实测选档：1600x30524 的行动牌长图 q72 ≈ 7.9MB
                                //（真实卡名更短 ⇒ 接近用户样例 6MB），q65 只省 0.7MB 却开始糊 26px 中文笔画
                                CardImageSaver(appContext).saveBitmap(
                                    bitmap,
                                    spec.title,
                                    format = Bitmap.CompressFormat.JPEG,
                                    quality = 72,
                                )
                            } finally {
                                // 回收放 finally：saveBitmap 抛异常也不能漏大图（长图可达数百 KB×行数像素）
                                bitmap.recycle()
                            }
                        }
                    }
                    // 路径与 CardImageSaver 写入同源（albumRelativePath），不再各写一份字面量
                    onShowToast(LocaleStrings.get(R.string.toast_saved_to_album_path, albumRelativePath()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // CardImageSaver 抛的 IOException message 已是中文可读文案（含缺存储权限提示）
                    onShowToast(e.message ?: LocaleStrings.get(R.string.error_export_failed))
                } finally {
                    // 各自复位：只影响自己按钮的可用性/文案
                    if (charTable) exportingChar = false else exportingAction = false
                }
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            ComposeModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            state.summary?.let { summary ->
                PlayerInfoCard(summary, state.avatarUrl, detailOpen = state.detailOpen, onToggle = viewModel::toggleDetail)
            }

            // 按钮行上下间距显式取 14/10（原先随 spacedBy 各 12）：按钮高 40dp、TabRow 高 48dp，
            // 文字都在各自容器内垂直居中 ⇒ 等值外边距时，下方"按钮文字↔Tab 文字"视觉间距
            // 比上方"Card 边缘↔按钮文字"大 (48-40)/2 = 4dp。上+2/下-2 后两侧相等：
            // 上 = 14+(40-文字高)/2，下 = 10+(48-文字高)/2，差值恰好抵消。
            Row(
                modifier = ComposeModifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExportButton(
                    exporting = exportingChar,
                    labelRes = R.string.stats_export_char,
                    enabled = state.summary != null,
                    onClick = { startExport(true) },
                    modifier = ComposeModifier.weight(1f),
                )
                ExportButton(
                    exporting = exportingAction,
                    labelRes = R.string.stats_export_action,
                    enabled = state.summary != null,
                    onClick = { startExport(false) },
                    modifier = ComposeModifier.weight(1f),
                )
            }

            TabRow(selectedTabIndex = tabIndex) {
                TAB_LABEL_IDS.forEachIndexed { index, labelRes ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = { Text(stringResource(labelRes)) },
                    )
                }
            }

            AnimatedContent(
                targetState = tabIndex,
                transitionSpec = {
                    (slideInHorizontally(Motion.emphasizedSpring<IntOffset>()) { it / 6 } +
                        fadeIn(Motion.emphasized<Float>())) togetherWith
                        (slideOutHorizontally(Motion.emphasizedSpring<IntOffset>()) { -it / 6 } +
                            fadeOut(Motion.emphasized<Float>()))
                },
                modifier = ComposeModifier.padding(top = 12.dp),
                label = "cardStatsTab",
            ) { tab ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tab == 0) {
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
                    } else {
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
            }
        }
    }
}

@Composable
private fun ExportButton(
    exporting: Boolean,
    @StringRes labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: ComposeModifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !exporting,
        modifier = modifier,
    ) {
        Text(
            stringResource(if (exporting) R.string.stats_exporting else labelRes),
            maxLines = 1,
        )
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
