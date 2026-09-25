// 卡牌使用详情页面：移植 web/src/pages/CardStatsPage.tsx 的版式与文案（§5.6）。
// 两 Tab（角色牌/行动牌）+ 角色牌三键排序 + 行动牌类型筛选 + "玩家信息"可展开详情
// （默认折叠，字段与 StatsDetailPanel 分组一致）。搜索框本版未含（派单范围外）。

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as ComposeModifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import com.gigi.tcg.ui.theme.Motion

private val TAB_LABELS = listOf("角色牌", "行动牌")

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

// 签名由派单固定：container 供 VM factory、onShowToast 预留给后续接线（刷新/提示），本页暂无调用点。
@Suppress("UNUSED_PARAMETER")
@Composable
fun CardStatsRoute(
    container: AppContainer,
    onShowToast: (String) -> Unit,
    modifier: ComposeModifier = ComposeModifier,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: CardStatsViewModel = viewModel(key = "cardStats", factory = CardStatsViewModel.factory(app))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when {
        state.loading -> LoadingView(modifier = modifier.fillMaxSize(), label = "卡牌统计加载中")

        state.error != null -> Column(modifier.fillMaxSize()) {
            val retry: (() -> Unit)? = if (state.errorCanRetry) viewModel::retry else null
            ErrorState(message = state.error ?: "", onRetry = retry)
        }

        state.isEmpty -> EmptyState(modifier = modifier.fillMaxSize(), title = "返回数据为空")

        else -> CardStatsContent(state, viewModel, modifier)
    }
}

@Composable
private fun CardStatsContent(
    state: StatsUiState,
    viewModel: CardStatsViewModel,
    modifier: ComposeModifier,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.summary?.let { summary ->
            PlayerInfoCard(summary, state.avatarUrl, detailOpen = state.detailOpen, onToggle = viewModel::toggleDetail)
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

@Composable
private fun CharTableHeader() {
    Row(
        ComposeModifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
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
                Column {
                    Text("玩家信息", style = MaterialTheme.typography.labelMedium)
                    Text(summary.nickname, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "牌手等级 ${summary.level}　总胜率 ${summary.winRate}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(ComposeModifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("总对局", summary.totalGames.toString())
                Metric("获胜对局", summary.winGames.toString())
                Metric("打出行动牌", summary.actionTotalUse.toString())
                Metric("角色牌收集", summary.avatarCardNum.toString())
            }
            FilledTonalButton(onClick = onToggle, modifier = ComposeModifier.fillMaxWidth()) {
                Text(if (detailOpen) "收起详情" else "展开详情")
                Icon(if (detailOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
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
                        "收集进度",
                        listOf(
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
    Column(ComposeModifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        rows.forEach { (label, value, hint) ->
            Row(ComposeModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = ComposeModifier.weight(1f))
                Text(
                    if (hint != null) "$value（$hint）" else value,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
