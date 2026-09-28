// 卡牌使用详情页面：移植 web/src/pages/CardStatsPage.tsx 的版式与文案（§5.6）。
// 两 Tab（角色牌/行动牌，TabRow 指示器 + HorizontalPager 左右滑动，交互照抄 RankRoute）
// + 角色牌三键排序 + 行动牌类型筛选 + 玩家信息卡可展开详情
// （默认折叠，分组对齐 web 的"行动牌详情 / 足迹"）。搜索框本版未含（派单范围外）。
//
// V29 结构回退（修「滑动白屏」）：V28-C 把头部 + tab 行 + HorizontalPager 整页塞进同一个
// verticalScroll Column，HorizontalPager 在无限高约束下算不出自身高度，滑动时新页首帧高度
// 塌成 0 ⇒ 白屏（实测只有行动牌页会白，因为它的页更高、塌陷更明显）。现回退成 RankRoute
// （ui/screens/rank/RankRoute.kt:128-182）的成熟结构：
//   Column(fillMaxSize) { 固定头部（信息卡 + TabRow）; PullToRefreshBox(fillMaxSize) { HorizontalPager(fillMaxSize) { 页内 LazyColumn } } }
// 取舍：**顶部固定、列表区自己滚**，放弃 V28-C 的"一体共同滚动"。理由是那种做法本身不成立
// （pager 与无限高约束冲突，是白屏根因），回退后与排行榜/图鉴页同一套结构，下拉刷新也仍在
// pager 外层（RankRoute 同层级），手势链由页内 LazyColumn 的 nestedScroll 正常上抛。
// V29 导出入口：从 tab 行右缘（那个 IconButton）移进"玩家信息"详情面板末尾，改成 filled
// Button + 下载图标；TabRow 因此恢复左右满宽。点击仍弹多选对话框，导出流程在 CardStatsExportAction.kt。
// V29 反馈：导出结果从"每张一条纯文本 Toast"改成"整次一条带「查看」action 的消息"，
// 经 onShowExportResult（文案 + 已落盘 Uri 列表）交给宿主 Snackbar（宿主接线见本文件 KDoc 注释）。

package com.gigi.tcg.ui.screens.cardstats

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.gigi.tcg.domain.CARD_TYPE_ASSIST
import com.gigi.tcg.domain.CARD_TYPE_EVENT
import com.gigi.tcg.domain.CARD_TYPE_MODIFY
import com.gigi.tcg.domain.GcgCard
import com.gigi.tcg.domain.GcgSummary
import com.gigi.tcg.domain.calcPercent
import com.gigi.tcg.domain.percentSortKey
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.theme.Motion
import com.gigi.tcg.ui.theme.tierColor
import kotlinx.coroutines.launch

/** 统计数值列定义：固定列宽 + 右对齐 + 等宽数字，保证四列纵向对齐。 */
private data class StatColumn(@StringRes val labelRes: Int, val width: Dp)

private val StatCountWidth: Dp = 48.dp
private val StatPercentWidth: Dp = 60.dp
private val ListRowVerticalPadding = 8.dp

/**
 * 序号列宽（V29 需求 8）：两页表头/行都带 # 列，固定宽度右对齐 ⇒ 个位数与两位数左缘都齐。
 * 32dp 够放 "100+" 里的三位数（labelSmall 约 6dp/字）。
 */
private val RankColumnWidth: Dp = 32.dp

/** 行动牌「类型」列宽（V29：与角色牌表头同构，类型名最长 3 字：修改/支援/事件） */
private val ActionTypeColumnWidth: Dp = 52.dp

/** 列表行左右/上下留白：LazyColumn 的 contentPadding（横向 16dp 与页面边距一致） */
private val ContentHorizontalPadding = 16.dp
private val ContentVerticalPadding = 12.dp

/** 列表滚到底的额外留白（避免最后一行贴导航栏；同样落在 contentPadding 上） */
private val ContentBottomPadding = 16.dp

private val CHAR_STAT_COLUMNS = listOf(
    StatColumn(R.string.stat_appear, StatCountWidth),
    StatColumn(R.string.stat_appear_rate, StatPercentWidth),
    StatColumn(R.string.stat_win_rate, StatPercentWidth),
    StatColumn(R.string.stat_wins, StatCountWidth),
)

/**
 * 导出结果反馈（V29，交给宿主 Snackbar）：一次点击导两张也只回**一条**消息。
 * 宿主接线契约（GigiNavHost 侧照此实现）：
 * `message` 直接作 SnackbarVisuals 的文本，`uris` 非空时「查看」动作打开相册（详见 V29-A 交付报告）。
 */
typealias StatsExportFeedback = (message: String, uris: List<Uri>) -> Unit

// 签名由派单固定：container 供 VM factory 与 sessionUid（导出长图副标题）、onShowToast 供"未勾选"提示。
// onShowExportResult 带默认空实现 ⇒ 宿主未接线前本页照常编译，但导出结果静默（宿主必须补上才有效果）。
@Composable
fun CardStatsRoute(
    container: AppContainer,
    onShowToast: (String) -> Unit,
    onShowExportResult: StatsExportFeedback = { _, _ -> },
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

        else -> CardStatsContent(state, viewModel, uid, onShowToast, onShowExportResult, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardStatsContent(
    state: StatsUiState,
    viewModel: CardStatsViewModel,
    uid: String,
    onShowToast: (String) -> Unit,
    onShowExportResult: StatsExportFeedback,
    modifier: ComposeModifier,
) {
    val context = LocalContext.current
    // 指示器只由下拉手势（refresh()）驱动；不能用 state.loading——
    // 其默认值为 true，冷启动首屏会与居中 LoadingView 叠成两个圈。
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { STATS_TAB_COUNT })
    // 导出口径与「当前 tab」解耦——点按钮先选要导的表（默认两项全选），勾谁导谁
    val exportAction = rememberStatsExporter(
        context = context,
        uid = uid,
        charCards = state.charList,
        actionCards = state.actionList,
        summary = state.summary,
        onResult = onShowExportResult,
    )
    var exportDialogOpen by remember { mutableStateOf(false) }
    // 默认全选（产品确认）：主用途是「把两张图都存下来分享」，取消勾选才是少数情况
    var exportCharChecked by remember { mutableStateOf(true) }
    var exportActionChecked by remember { mutableStateOf(true) }
    // 提示语在组合期取好：onSave 是普通 lambda，里面不能调 stringResource
    val pickAtLeastOneToast = stringResource(R.string.stats_export_pick_at_least_one)
    // 对话框里报的条数必须等于真正落盘进表的条数（口径见 exportRowCount）
    val charExportCount = remember(state.charList) { exportRowCount(state.charList) }
    val actionExportCount = remember(state.actionList) { exportRowCount(state.actionList) }

    // 固定头 + 页内滚动（照 RankRoute:128-182）：pager 拿到的是 Column 给的**有界高**，
    // 不再塌陷成 0 高白屏。下拉刷新挂在 pager 外层（RankRoute 同一层级）。
    Column(modifier = modifier.fillMaxSize()) {
        state.summary?.let { summary ->
            // 顶部 8dp 让信息卡不贴 tab 行/刷新圈；padding 不能混用 horizontal+top（无该重载）
            Column(ComposeModifier.padding(top = 8.dp).padding(horizontal = ContentHorizontalPadding)) {
                PlayerInfoCard(
                    summary = summary,
                    avatarUrl = state.avatarUrl,
                    tier = state.tier,
                    uid = uid,
                    detailOpen = state.detailOpen,
                    onToggle = viewModel::toggleDetail,
                    exportEnabled = exportAction.enabled && !exportAction.exporting,
                    onExportClick = { exportDialogOpen = true },
                )
            }
        }

        StatsTabRow(pagerState = pagerState)

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

    if (exportDialogOpen) {
        StatsExportDialog(
            charChecked = exportCharChecked,
            actionChecked = exportActionChecked,
            charCount = charExportCount,
            actionCount = actionExportCount,
            onCharCheckedChange = { exportCharChecked = it },
            onActionCheckedChange = { exportActionChecked = it },
            onSave = {
                val selection = StatsExportSelection(exportCharChecked, exportActionChecked)
                if (selection.isEmpty) {
                    // 全不勾：给提示且**不关窗**。静默关窗会被当成「已经导出成功」，
                    // 而留在原地补勾再点保存比重新打开对话框少一步。
                    onShowToast(pickAtLeastOneToast)
                } else {
                    exportAction.run(selection)
                    // 🔴 M3 AlertDialog 的 confirmButton 不会自动收起（PlayerDetailDialog
                    // 「复制UID」同样踩过），导出触发后必须显式关窗；两张表的结果合并成一条消息。
                    exportDialogOpen = false
                }
            },
            onDismiss = { exportDialogOpen = false },
        )
    }
}

/**
 * tab 行（V29 恢复满宽）：导出按钮移进详情面板后，这一行不再让位给右缘按钮，
 * 写法与 RankRoute 的 TabRow 一致（fillMaxWidth + currentPage 实时插值指示器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsTabRow(pagerState: PagerState) {
    // tab 点击 → 翻页：与 RankRoute / CardWikiRoute 同一写法（rememberCoroutineScope + launch）
    val scope = rememberCoroutineScope()
    TabRow(
        modifier = ComposeModifier.fillMaxWidth(),
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
}

/**
 * 多选导出对话框：两项（角色牌 / 行动牌）默认全选，positive=保存 只导勾选项。
 * 选项文案带**真正会导出的条数**（stats_export_option_char / _action + [charCount]/[actionCount]），
 * 条数口径见 exportRowCount；括号随语种走 ⇒ 代码只填数字（工程既有惯例）。
 */
@Composable
private fun StatsExportDialog(
    charChecked: Boolean,
    actionChecked: Boolean,
    charCount: Int,
    actionCount: Int,
    onCharCheckedChange: (Boolean) -> Unit,
    onActionCheckedChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stats_export_dialog_title)) },
        text = {
            Column {
                ExportOptionRow(
                    stringResource(R.string.stats_export_option_char, charCount),
                    charChecked,
                    onCharCheckedChange,
                )
                ExportOptionRow(
                    stringResource(R.string.stats_export_option_action, actionCount),
                    actionChecked,
                    onActionCheckedChange,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 多选项一行：整行可点（勾选框热区只有 48dp，点文字也该能切换）；label 由调用方取好成品文案 */
@Composable
private fun ExportOptionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = ComposeModifier
            .fillMaxWidth()
            .clickable(onClick = { onCheckedChange(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CharStatsPage(state: StatsUiState, viewModel: CardStatsViewModel) {
    // 页内自己滚（LazyColumn + fillMaxSize，照 RankRoute:214 的榜一列表写法）：
    // pager 在有界高里给出确定页高，LazyColumn 的 nestedScroll 把下拉手势上抛给 PullToRefreshBox。
    // key 带下标 ⇒ 天然唯一（牌名可能重复，不能只按名做 key）。
    LazyColumn(
        ComposeModifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ContentHorizontalPadding,
            end = ContentHorizontalPadding,
            top = ContentVerticalPadding,
            bottom = ContentVerticalPadding + ContentBottomPadding,
        ),
    ) {
        item(key = "char-sort") {
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
        }
        if (state.sortedCharList.isEmpty()) {
            item(key = "char-empty") { NoMatchHint(R.string.stats_no_match_char) }
        } else {
            item(key = "char-header") { CharTableHeader() }
            // V29 需求 8：# 列名次按「当前排序键」判并列（1-2-2-4），换排序键时名次跟着重算
            val charRanks = ranksWithTies(state.sortedCharList) { charSortKey(it, state.charSort) }
            itemsIndexed(
                state.sortedCharList,
                key = { index, card -> "char-$index-${card.name.orEmpty()}" },
            ) { index, card ->
                CharCardRow(card, charTotalUse = state.charTotalUse, rank = charRanks[index])
            }
        }
    }
}

/**
 * 角色牌的排序键取值（与 [StatsUiState.sortedCharList] 的比较器**逐字同口径**）。
 * 两处必须一致：名次是"按当前排序键"给的，比较器换了键而这里没换，名次就会与行的顺序对不上。
 */
private fun charSortKey(card: GcgCard, sort: CharSortKey): Comparable<*> = when (sort) {
    CharSortKey.Use -> card.useCount ?: 0
    CharSortKey.Wins -> card.proficiency ?: 0
    CharSortKey.WinRate -> percentSortKey(
        calcPercent((card.proficiency ?: 0).toDouble(), (card.useCount ?: 0).toDouble()),
    )
}

@Composable
private fun ActionStatsPage(state: StatsUiState, viewModel: CardStatsViewModel) {
    // 同 CharStatsPage：LazyColumn 承载页内滚动（这一页行最多，正是滑动白屏最明显的一页）
    LazyColumn(
        ComposeModifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ContentHorizontalPadding,
            end = ContentHorizontalPadding,
            top = ContentVerticalPadding,
            bottom = ContentVerticalPadding + ContentBottomPadding,
        ),
    ) {
        item(key = "action-filter") {
            // V29 需求 8：四个类型筛选由 FilterChip 改 ToggleGroup（SingleChoiceSegmentedButtonRow），
            // 与角色牌页的排序 ToggleGroup 同一套控件 ⇒ 两页观感统一、点击区更大。
            SingleChoiceSegmentedButtonRow(modifier = ComposeModifier.fillMaxWidth()) {
                ActionTypeFilter.entries.forEachIndexed { index, filter ->
                    SegmentedButton(
                        selected = state.actionType == filter,
                        onClick = { viewModel.setActionType(filter) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ActionTypeFilter.entries.size,
                        ),
                    ) {
                        Text(stringResource(filter.labelRes))
                    }
                }
            }
        }
        if (state.filteredActionList.isEmpty()) {
            item(key = "action-empty") { NoMatchHint(R.string.stats_no_match_action) }
        } else {
            item(key = "action-header") { ActionTableHeader() }
            // 行动牌恒按出场数排序（页面无排序开关），名次即出场数的并列排名
            val actionRanks = ranksWithTies(state.filteredActionList) { it.useCount ?: 0 }
            itemsIndexed(
                state.filteredActionList,
                key = { index, card -> "action-$index-${card.name.orEmpty()}" },
            ) { index, card ->
                ActionCardRow(card, rank = actionRanks[index])
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
        // V29 需求 8：# 列表头（与行内的名次列同宽右对齐）
        Text(
            stringResource(R.string.stat_rank),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = ComposeModifier.width(RankColumnWidth),
        )
        Text(
            stringResource(R.string.card_type_character),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = ComposeModifier.weight(1f).padding(start = 8.dp),
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
private fun CharCardRow(card: GcgCard, charTotalUse: Int, rank: Int) {
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
        // V29 需求 8：# 列（名次）。用 labelMedium 弱化，不与牌名争视觉重心
        Text(
            rank.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = ComposeModifier.width(RankColumnWidth),
        )
        Text(
            card.name ?: stringResource(R.string.common_unknown),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = ComposeModifier.weight(1f).padding(start = 8.dp),
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

/**
 * 行动牌表头（V29 需求 8，与角色牌表头同构）：# / 名称 / 类型 / 出场。
 * 列宽与行渲染一一对应，改一处必须同步另一处。
 */
@Composable
private fun ActionTableHeader() {
    Row(
        ComposeModifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.stat_rank),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = ComposeModifier.width(RankColumnWidth),
        )
        Text(
            stringResource(R.string.card_type_action),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = ComposeModifier.weight(1f).padding(start = 8.dp),
        )
        Text(
            stringResource(R.string.stats_col_type),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = ComposeModifier.width(ActionTypeColumnWidth),
        )
        Text(
            stringResource(R.string.stat_appear),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = ComposeModifier.width(StatCountWidth),
        )
    }
}

@Composable
private fun ActionCardRow(card: GcgCard, rank: Int) {
    Row(
        ComposeModifier
            .fillMaxWidth()
            .padding(vertical = ListRowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rank.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = ComposeModifier.width(RankColumnWidth),
        )
        // 牌名字号对齐角色牌页（bodyLarge）：两页同一层级的文本用同一档字号，切页不跳变
        Text(
            card.name ?: stringResource(R.string.common_unknown),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = ComposeModifier.weight(1f).padding(start = 8.dp),
        )
        Text(
            stringResource(actionTypeLabelRes(card.cardType)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = ComposeModifier.width(ActionTypeColumnWidth),
        )
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

/** 服务端 cardType 字符串 → 类型列的本地化文案（与 [ActionTypeFilter] 的口径同源） */
private fun actionTypeLabelRes(cardType: String?): Int = when (cardType) {
    CARD_TYPE_MODIFY -> R.string.card_type_modify
    CARD_TYPE_ASSIST -> R.string.card_type_assist
    CARD_TYPE_EVENT -> R.string.card_type_event
    else -> R.string.common_unknown
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
    tier: String,
    uid: String,
    detailOpen: Boolean,
    onToggle: () -> Unit,
    exportEnabled: Boolean,
    onExportClick: () -> Unit,
) {
    Card(ComposeModifier.fillMaxWidth()) {
        Column(ComposeModifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // V29 需求 6：个人信息区版式对齐首页 ProfileCard ——
            // 64dp 头像 + 12dp 间距 + 文本列（第一行「昵称 + 段位」同行按基线对齐、第二行 UID）。
            // 段位为空串时整段不渲染（不是渲染成占位），层级靠字号字重拉开。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(url = avatarUrl, size = 64.dp, contentDescription = summary.nickname)
                Spacer(ComposeModifier.width(12.dp))
                Column(ComposeModifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            summary.nickname,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = ComposeModifier.weight(1f, fill = false).alignByBaseline(),
                        )
                        if (tier.isNotEmpty()) {
                            Spacer(ComposeModifier.width(6.dp))
                            Text(
                                tier,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = tierColor(tier),
                                maxLines = 1,
                                modifier = ComposeModifier.alignByBaseline(),
                            )
                        }
                    }
                    Spacer(ComposeModifier.height(4.dp))
                    // UID 与昵称左缘天然对齐（不额外缩进）；字号走 bodySmall，卡内最小档
                    Text(
                        uid,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                    // 导出入口（V29）：原先是挂在 tab 行右缘的 IconButton（把 TabRow 挤成不满宽），
                    // 用户要求移到详情面板末尾、改成 filled Button（样式照 CardCoverSheet:194-202 的下载按钮）。
                    // 图标语义即「导出」，文案用 stats_export_button（旧 stats_export_current「当前图表」
                    // 已与「导哪张看勾选」的口径不符）。
                    val exportLabel = stringResource(R.string.stats_export_button)
                    Button(
                        onClick = onExportClick,
                        enabled = exportEnabled,
                        modifier = ComposeModifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        // V29：图标语义从「下载」改成「导出」（IosShare = 向上导出箭头）。
                        // 本操作是生成图片到相册，不是从网上拉文件下来，Download 那个下箭头会读反。
                        Icon(Icons.Outlined.IosShare, contentDescription = exportLabel)
                        Spacer(ComposeModifier.width(8.dp))
                        Text(exportLabel)
                    }
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
