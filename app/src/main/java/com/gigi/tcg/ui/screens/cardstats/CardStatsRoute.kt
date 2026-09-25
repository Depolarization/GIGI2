// 卡牌使用详情页面：移植 web/src/pages/CardStatsPage.tsx 的版式与文案（§5.6）。
// 两 Tab（角色牌/行动牌）+ 角色牌三键排序 + 行动牌类型筛选 + 玩家信息卡可展开详情
// （默认折叠，分组对齐 web 的"行动牌详情 / 足迹"）。搜索框本版未含（派单范围外）。

package com.gigi.tcg.ui.screens.cardstats

import android.app.Application
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
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgSummary
import com.gigi.tcg.domain.calcPercent
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.dialogs.cardcover.CardImageSaver
import com.gigi.tcg.ui.export.computeTableLayout
import com.gigi.tcg.ui.export.renderTableBitmap
import com.gigi.tcg.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TAB_LABELS = listOf("角色牌", "行动牌")

/** 相册落盘目录，与 CardImageSaver 的 ALBUM_PARENT/ALBUM_NAME 保持一致 */
private const val ALBUM_TOAST_PATH = "Pictures/GIGI"

/** 统计数值列定义：固定列宽 + 右对齐 + 等宽数字，保证四列纵向对齐。 */
private data class StatColumn(val label: String, val width: Dp)

private val StatCountWidth: Dp = 48.dp
private val StatPercentWidth: Dp = 60.dp
private val ListRowVerticalPadding = 8.dp
private val CHAR_STAT_COLUMNS = listOf(
    StatColumn("出场", StatCountWidth),
    StatColumn("出场率", StatPercentWidth),
    StatColumn("胜率", StatPercentWidth),
    StatColumn("胜场", StatCountWidth),
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
            LoadingView(label = "卡牌统计加载中")
        }

        state.error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val retry: (() -> Unit)? = if (state.errorCanRetry) viewModel::retry else null
            ErrorState(message = state.error ?: "", onRetry = retry)
        }

        state.isEmpty -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(title = "返回数据为空")
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
    var exporting by remember { mutableStateOf(false) }

    // 角色牌/行动牌各出一张长图（DESIGN-V8 决策 4）。导出的是全量列表，
    // 不跟随本页的排序按钮与类型筛选（产品决策：分享出去的图要完整可比对）。
    val startExport: (Boolean) -> Unit = { charTable ->
        val summary = state.summary
        if (summary != null && !exporting) {
            exporting = true
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.Default) {
                        val cards = if (charTable) state.charList else state.actionList
                        val spec = if (charTable) {
                            buildCharTableSpec(summary, uid, cards)
                        } else {
                            buildActionTableSpec(summary, uid, cards)
                        }
                        val layout = computeTableLayout(spec, EXPORT_IMAGE_WIDTH_PX, EXPORT_TWO_COLUMN_THRESHOLD)
                        val bitmap = renderTableBitmap(spec, layout)
                        try {
                            CardImageSaver(appContext).saveBitmap(bitmap, spec.title)
                        } finally {
                            // 回收放 finally：saveBitmap 抛异常也不能漏大图（长图可达数百 KB×行数像素）
                            bitmap.recycle()
                        }
                    }
                    onShowToast("已保存到相册：$ALBUM_TOAST_PATH")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // CardImageSaver 抛的 IOException message 已是中文可读文案（含缺存储权限提示）
                    onShowToast(e.message ?: "导出失败")
                } finally {
                    exporting = false
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.summary?.let { summary ->
                PlayerInfoCard(summary, state.avatarUrl, detailOpen = state.detailOpen, onToggle = viewModel::toggleDetail)
            }

            Row(
                modifier = ComposeModifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExportButton(
                    exporting = exporting,
                    label = "导出角色牌",
                    enabled = state.summary != null,
                    onClick = { startExport(true) },
                    modifier = ComposeModifier.weight(1f),
                )
                ExportButton(
                    exporting = exporting,
                    label = "导出行动牌",
                    enabled = state.summary != null,
                    onClick = { startExport(false) },
                    modifier = ComposeModifier.weight(1f),
                )
            }

            TabRow(selectedTabIndex = tabIndex) {
                TAB_LABELS.forEachIndexed { index, label ->
                    Tab(selected = tabIndex == index, onClick = { tabIndex = index }, text = { Text(label) })
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
                                    Text(key.label)
                                }
                            }
                        }
                        if (state.sortedCharList.isEmpty()) {
                            NoMatchHint("没有匹配的角色牌")
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
                                    label = { Text(filter.label) },
                                )
                            }
                        }
                        if (state.filteredActionList.isEmpty()) {
                            NoMatchHint("没有匹配的行动牌")
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
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: ComposeModifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !exporting,
        modifier = modifier,
    ) {
        Text(if (exporting) "导出中…" else label, maxLines = 1)
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
            "角色牌",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = ComposeModifier.weight(1f),
        )
        CHAR_STAT_COLUMNS.forEach { col ->
            Text(
                col.label,
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
            card.name ?: "未知",
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
            card.name ?: "未知",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = ComposeModifier.weight(1f),
        )
        Spacer(ComposeModifier.width(12.dp))
        Text(
            "出场",
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
private fun NoMatchHint(message: String) {
    Text(
        message,
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
                Metric("总对局", summary.totalGames.toString())
                Metric("获胜对局", summary.winGames.toString())
                Metric("打出行动牌", summary.actionTotalUse.toString())
                Metric("总胜率", summary.winRate)
            }
            FilledTonalButton(onClick = onToggle, modifier = ComposeModifier.fillMaxWidth()) {
                Text(if (detailOpen) "收起详情" else "展开详情")
                Icon(
                    if (detailOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (detailOpen) "收起详情" else "展开详情",
                )
            }
            AnimatedVisibility(
                visible = detailOpen,
                enter = expandVertically(Motion.emphasized<IntSize>()) + fadeIn(Motion.emphasized<Float>()),
                exit = shrinkVertically(Motion.emphasized<IntSize>()) + fadeOut(Motion.emphasized<Float>()),
            ) {
                Column {
                    DetailGroup(
                        "行动牌详情",
                        listOf(
                            Triple("装备牌", summary.modifyUse.toString(), "占比 ${summary.modifyPercent}"),
                            Triple("支援牌", summary.assistUse.toString(), "占比 ${summary.assistPercent}"),
                            Triple("事件牌", summary.eventUse.toString(), "占比 ${summary.eventPercent}"),
                        ),
                    )
                    DetailGroup(
                        "足迹",
                        listOf(
                            Triple("牌手等级", summary.level.toString(), null),
                            Triple("角色牌收集", summary.avatarCardNum.toString(), null),
                            Triple("行动牌收集", summary.actionCardNum.toString(), null),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DetailGroup(title: String, rows: List<Triple<String, String, String?>>) {
    Column(ComposeModifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        rows.forEach { (label, value, hint) ->
            Row(ComposeModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = ComposeModifier.weight(1f),
                )
                Text(
                    if (hint != null) "$value（$hint）" else value,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
