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
// V37-AD：PlayerInfoHeader 的昵称+段位合并成单个 Text（段位走 SpanStyle，任务 B）；
// 表头改 stickyHeader 不再随行滚走（任务 E-1）；序号列改右对齐（任务 E-2）。
// V39-B 任务 A：**整体滑动** —— V29 那次「固定头 + 页内滚动」的结构回退只解决了白屏
// （pager 在无限高约束下塌成 0），代价是信息卡/tab 行永远占着顶部固定高，用户实测
// 「展开个人信息栏后底部几乎没有滑动区域」。该轮把头部移进**每页自己的 LazyColumn** 最前面
// 两个 item；**V40-A 起作废**（见下），V39-B 任务 B 的起始补偿常量随之删除。
//
// V40-A：头部（信息卡 + tab 行）**移出 HorizontalPager**、只保留一份 —— 用户报「切 tab
// 连带着整个卡牌统计页一起切」的根因正是 V39-B「头部放进每页 LazyColumn」：那份实现下
// 头部天然跟着翻页横向滑动。为不丢 V39-B 的「整体纵向滚动」（展开信息卡后列表仍有滑动
// 空间），头部改**折叠式**：容器层挂 nestedScroll 连接 —— 列表上滑先折信息卡、折满才滚
// 列表；到顶下拉反向展开；tab 行常驻、不折叠、不随翻页横移。两个页内 LazyColumn 顺带
// 删掉头部 item，只剩 排序/筛选行 → 列名(sticky, 用户点名保留) → 数据行。
// 🔴 白屏病根防线一个没动：PullToRefreshBox 仍包着 pager、weight(1f) 拿到的仍是**有界**高
// （V29 病根＝pager 在无限高约束下翻页首帧塌成 0），全页仍不得出现 verticalScroll。
// 🔴 折叠连接挂在 PullToRefreshBox 内、pager 外，而不是最外层 Column：nestedScroll 的
// onPostScroll 按「由内向外」派发，刷新盒在更内层且空闲会把到顶下拉的正余量全额吃掉
// （已按 material3 1.3.2 字节码核实：consumeAvailableOffset 无阈值即刻消费）⇒ 挂外层时
// 「到顶下拉回展」永远拿不到余量、成死代码；挂内层后先回展、展开完才轮到下拉圈。
// onPreScroll 派发方向是「外向内」：刷新盒先收自己的（无拉出量时为 0），剩余量到达折叠
// 连接，与「先折头部、再滚列表」的先后关系天然一致。

package com.gigi.tcg.ui.screens.cardstats

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as ComposeModifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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

// internal 同 [RankColumnWidth]：三位数余量算式由 StatsRowMetricsTest 断言，不靠截图
internal val StatCountWidth: Dp = 48.dp
internal val StatPercentWidth: Dp = 60.dp
private val ListRowVerticalPadding = 8.dp

/**
 * 序号列宽与对齐（V37-2 任务 E-2，🔴 反转 V36 任务 C 的"起始对齐"决定）：
 * 用户口径——「最左侧的序号列没有像最右侧的出场次数列那样右对齐，这是错误的设计，
 * 随着序号位数的增加，文本会越来越贴近名称列」。起始对齐时 1→10→100 的名次右缘持续向右逼近牌名列，
 * 右对齐则位数增加只向**左**生长，与牌名列的 [RankNameGap] 恒定。
 * 列宽 24dp 的来源：名次用 labelMedium(12sp)，Roboto 数字步进 ≈0.55em ≈6.6dp ⇒ 三位数 ≈20dp，
 * 取 24dp 留 ≈4dp 余量；本接口角色牌/行动牌数量级都在三位数内（四位不在数据范围内，不需要 32dp）。
 * internal 供 StatsRowMetricsTest 断言列几何算式，不靠截图。
 */
internal val RankColumnWidth: Dp = 24.dp

/** 序号列与牌名列的呼吸位：右对齐后牌名左缘 = 页边距 + [RankColumnWidth] + 本值（不再随名次位数变化） */
internal val RankNameGap: Dp = 8.dp

/** 行动牌「类型」列宽（V29：与角色牌表头同构，类型名最长 3 字：修改/支援/事件） */
private val ActionTypeColumnWidth: Dp = 52.dp

/** 列表行上下/右侧留白：LazyColumn 的 contentPadding（右侧 16dp 与页面边距一致；internal 同理由测试引用） */
internal val ContentHorizontalPadding = 16.dp
private val ContentVerticalPadding = 12.dp

/**
 * 列表起始内距（V39-B 任务 B）：序号列右对齐、列宽 24dp 已定，再叠 16dp 页面边距 ⇒
 * 用户实测「序号列距离左侧边距较大」。收到 8dp，省下的 8dp 全部还给牌名列（[RankNameGap] 不变）。
 */
internal val ContentStartPadding = 8.dp

// V40-A：原先给头部补「列表起始内距差（16 − 8）」的那个常量已删除 —— 信息卡 / tab 行移出
// 列表后不再吃 8dp 起始内距，各自直接补页面边距（见两处包装函数），常量留着只会误导。

/** 信息卡顶部额外留白：不贴刷新圈（V29 起就是这个值，本轮只是换了容器） */
private val HeaderTopPadding = 8.dp

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
    val sessionUid by container.sessionUid.collectAsStateWithLifecycle()
    val uid = sessionUid.orEmpty()

    // 🔴 换账号自动重拉（V37-G 任务 D，V37-C residual 6-1 / V37-AD residual 7 两名子代理独立点名）：
    // 统计页 VM 侧早已备好归属闸门（load() 里的 contentUid：uid 变了才清内容、页面重入不清）
    // 与过期回包丢弃（generation），**缺的只是「uid 变了谁来调 load()」这个触发器** ——
    // 没有它，一旦 VM 活得比账号久，页面就顶着上一账号的统计显示，直到用户手动下拉（数据串号）。
    //
    // 现状兜底：主壳 `key(server to sessionUid)`（GigiNavHost.kt:217）在切账号时整棵重建导航树，
    // 各页 VM 随之作废 ⇒ 今天这条路径上下面的守卫不会触发。但那是**全链路唯一**的兜底，且 N2
    // 待裁决项（同一处注释）明写着「想保住导航位置需把 key 收窄到 server，并给各页 ViewModel
    // 加账号归属判定」⇒ 触发器必须长在页面自己身上，不能赌主壳的重建口径。
    //
    // 🔴 守卫必须 rememberSaveable，不是 HomeRoute.kt:121 那种 remember（手册红线 6）：
    // 统计页被 popUpTo(saveState=true) 弹出时，**VM 随条目状态存活、remember 不存活**。
    // 用户切账号只能去「我的」页 ⇒ 统计组合必然已被 dispose ⇒ 用 remember 的话回来时
    // lastUid 是 null，守卫永远判不出「变过」，串号照旧；rememberSaveable 与 VM 同生死，
    // 正好覆盖「组合重入 + VM 存活」这条真实路径。主壳 key() 重建时状态袋是新的 ⇒ 回落 null，
    // 与 VM init 的首刷不会并发（不会双打私有接口触发 -500004）。
    //
    // 🔴 必须 collectAsStateWithLifecycle() 读 StateFlow（上面那行），组合里绝不读 .value
    //（StateFlowValueCalledInComposition 是 lint error）。
    // 首次见到 uid 只记基线、不触发；入口用 retry() 不用 refresh() —— refresh() 会点亮下拉指示器，
    // 而换账号不是用户下拉手势（V37-C 的 StatsLoadingGateTest 锁定「refreshing 置 true 只允许出现在 refresh()」）。
    var lastUid by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionUid) {
        val nextUid = sessionUid
        if (nextUid != null && lastUid != null && nextUid != lastUid) viewModel.retry()
        if (nextUid != null) lastUid = nextUid
    }

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

    // 整体纵向滚动（V40-A）：信息卡 + tab 行搬出 pager 后不再随翻页横移，纵向的
    // 「先折头、再滚列表」由这对 state + 下面的 nestedScroll 连接补回来。用
    // mutableFloatState 而非普通值：连接只 remember 一次、读写同一对引用，不必每帧重建。
    val headerCollapsePx = remember { mutableFloatStateOf(0f) }
    val headerNaturalHeightPx = remember { mutableFloatStateOf(0f) }
    val headerCollapseConnection = remember { HeaderCollapseConnection(headerCollapsePx, headerNaturalHeightPx) }
    val exportEnabled = exportAction.enabled && !exportAction.exporting
    Column(modifier = modifier.fillMaxSize()) {
        CollapsibleHeaderContainer(headerCollapsePx, headerNaturalHeightPx) {
            StatsInfoCardItem(state, uid, viewModel::toggleDetail, exportEnabled) { exportDialogOpen = true }
        }
        // tab 行常驻：不折叠、不进 pager ⇒ 翻页时纹丝不动（用户报的病根就是它跟着横滑）
        StatsTabRowItem(pagerState)
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            // V37-1 任务 E-1：列表区吃满剩余高度用 weight(1f)。🔴 本棒折叠头占的是同一
            // 列布局里的**真实高度**（不是浮层），weight 让 P2R/pager 继续拿到有界剩余高
            // ⇒ 页内 LazyColumn 不会遇到无限高（V29 白屏病根防线，一个字没动）。
            modifier = ComposeModifier.weight(1f),
        ) {
            // 🔴 折叠连接挂点：P2R 内、pager 外。nestedScroll 的 onPostScroll 按「由内向外」
            // 派发（compose-ui 1.9.1 字节码核实），而 P2R 空闲态会把到顶下拉的正余量全额吃掉
            // （material3 1.3.2 字节码核实；它没有 enabled 参数，没法「折叠未归零时暂停刷新」
            // 绕开顺序）⇒ 挂到 P2R 外面则「到顶下拉回展」永远轮不到、成死代码；挂这里则先回展、
            // 展平后的正余量才继续外抛给 P2R 拉刷新圈。onPreScroll 是「外向内」派发，P2R 先收
            // 自己的（未拉出时为 0），余量照样先到折叠连接：先折头、再滚列表，顺序天然正确。
            Box(ComposeModifier.fillMaxSize().nestedScroll(headerCollapseConnection)) {
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
 * 折叠容器（V40-A 整体纵向滚动的载体）：外框高 = 信息卡自然高 − 折叠量，
 * clipToBounds 把折出去的部分裁掉（不裁会盖住下面的 tab 行与列表区）。
 * 折叠量/自然高读在**本函数内**：滚动期间逐帧变化只重组这一小块，pager 与大列表不陪跑。
 *
 * 🔴 onSizeChanged 必须挂在内层「不限高」的 Column 上：外框高本来就是这两个状态的函数，
 * 挂外层量到的会是折叠后的可见高（自锁：一折就把自然高改小，再也展不回来）。
 * 首帧自然高未知（=0）先按自然排版、量到后再交给公式接管 —— 否则首帧先以 0 高画一帧，
 * 头部会白闪一下。
 */
@Composable
private fun CollapsibleHeaderContainer(
    collapsePx: MutableFloatState,
    naturalHeightPx: MutableFloatState,
    content: @Composable () -> Unit,
) {
    val naturalHeight = naturalHeightPx.floatValue
    val boxModifier = if (naturalHeight > 0f) {
        ComposeModifier
            .fillMaxWidth()
            .clipToBounds()
            .height(
                with(LocalDensity.current) {
                    (naturalHeight - collapsePx.floatValue).coerceAtLeast(0f).toDp()
                },
            )
    } else {
        ComposeModifier.fillMaxWidth()
    }
    Box(boxModifier) {
        Column(
            ComposeModifier
                .wrapContentHeight(unbounded = true, align = Alignment.Top)
                .onSizeChanged { naturalHeightPx.floatValue = it.height.toFloat() },
        ) {
            content()
        }
    }
}

/**
 * 折叠手势链（V40-A）：上滑先折信息卡，折满余量才轮到列表滚；列表到顶后下拉反向展开，
 * 展平后才把正余量继续外抛给 PullToRefreshBox 去拉刷新圈。只吃拖拽（UserInput）——
 * 惯性滑动（Fling）不带动头部，免得抬手后头部自己跳一段。
 *
 * 状态用 [MutableFloatState] 引用进出（而不是每帧回传新值）：连接只 remember 一次，
 * 读写都落在同一对 state 上。
 */
private class HeaderCollapseConnection(
    private val collapsePx: MutableFloatState,
    private val naturalHeightPx: MutableFloatState,
) : NestedScrollConnection {

    /** 折叠量的合法范围：0（完全展开）~ 自然高（完全收起） */
    private fun clampCollapse(value: Float): Float = value.coerceIn(0f, naturalHeightPx.floatValue)

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // 只接拖拽；上滑（负）才折，下拉的正数留给 onPostScroll（要先等列表自己滚到顶）
        if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
        val old = clampCollapse(collapsePx.floatValue)
        val next = clampCollapse(old - available.y)
        collapsePx.floatValue = next
        // 返回值必须与 available 同号且不超过它（nestedScroll 的消耗约定），即 old − next：
        // 上滑段为负（先吃掉这段 ⇒ 头部先收），收到顶 next==old ⇒ 归零、余量放给列表。
        return Offset(0f, old - next)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // 到达这里的正余量 = 列表已在顶、自己吃不下的下拉量（列表没在顶时轮不到它）
        if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        val old = clampCollapse(collapsePx.floatValue)
        val next = clampCollapse(old - available.y)
        collapsePx.floatValue = next
        // 同号为正（回展吃掉多少），展平（0）后余量继续外抛给 P2R 拉刷新圈
        return Offset(0f, old - next)
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
 * 头部之一：玩家信息卡（V40-A 从每页 LazyColumn 搬回外层折叠容器，全页只此一份）。
 * 调用参数沿用 V39-B 版 ⇒ 行为不变；`state.detailOpen` 仍由 VM 驱动。
 * 左缘直接用页面边距 16dp：搬出 LazyColumn 后外面没有 8dp contentPadding 可叠，
 * V39-B 的 8dp 起始补偿常量随之作废。
 */
@Composable
private fun StatsInfoCardItem(
    state: StatsUiState,
    uid: String,
    onToggleDetail: () -> Unit,
    exportEnabled: Boolean,
    onExportClick: () -> Unit,
) {
    val summary = state.summary ?: return
    Column(ComposeModifier.padding(top = HeaderTopPadding).padding(start = ContentHorizontalPadding)) {
        PlayerInfoCard(
            summary = summary,
            avatarUrl = state.avatarUrl,
            tier = state.tier,
            uid = uid,
            detailOpen = state.detailOpen,
            onToggle = onToggleDetail,
            exportEnabled = exportEnabled,
            onExportClick = onExportClick,
        )
    }
}

/** 头部之二：tab 行（V40-A 起常驻在 pager 外，翻页时不动）。左缘与信息卡同为 16dp 页面边距 */
@Composable
private fun StatsTabRowItem(pagerState: PagerState) {
    Column(ComposeModifier.padding(start = ContentHorizontalPadding)) {
        StatsTabRow(pagerState = pagerState)
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
private fun CharStatsPage(
    state: StatsUiState,
    viewModel: CardStatsViewModel,
) {
    // 页内自己滚（LazyColumn + fillMaxSize，照 RankRoute:214 的榜一列表写法）：
    // pager 在有界高里给出确定页高，LazyColumn 的 nestedScroll 把下拉手势上抛给 PullToRefreshBox。
    // key 带下标 ⇒ 天然唯一（牌名可能重复，不能只按名做 key）。
    // V40-A：信息卡 + tab 行已上移出 pager（本页不再有头部 item），只剩
    // 排序行 → 列名(sticky) → 数据行；头部的折叠/展开由外层容器统一负责。
    LazyColumn(
        ComposeModifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ContentStartPadding,
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
            // V37-1 任务 E-1：表头 **stickyHeader**；V40-A 起信息卡/tab 行已移出本列表，
            // 列名依旧是本页唯一吸顶的元素（用户点名保留的正是它）——滚过排序行后吸在视口顶部。
            stickyHeader(key = "char-header") { CharTableHeader() }
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
private fun ActionStatsPage(
    state: StatsUiState,
    viewModel: CardStatsViewModel,
) {
    // 同 CharStatsPage：LazyColumn 承载页内滚动（这一页行最多，正是滑动白屏最明显的一页）
    // V40-A：信息卡 + tab 行已上移出 pager，顺序 = 筛选行 → 列名(sticky) → 数据行
    LazyColumn(
        ComposeModifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ContentStartPadding,
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
            // V37-1 任务 E-1：同角色牌页，表头 sticky（行动牌页行数最多，滚得最深，病最明显）
            stickyHeader(key = "action-header") { ActionTableHeader() }
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
            // stickyHeader 底色：行内容会从表头下方滚过，没有底色就会从字缝里穿出来
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // V37-2 任务 E-2：# 列改右对齐（与最右「出场次数」同一口径），名次位数增加只向左生长
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
            modifier = ComposeModifier.weight(1f).padding(start = RankNameGap),
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
        // V37-2 任务 E-2：# 列（名次）右对齐，与表头同一列宽、同一右缘（labelMedium 弱化，不与牌名争重心）
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
            modifier = ComposeModifier.weight(1f).padding(start = RankNameGap),
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
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // V37-2 任务 E-2：行动牌页的 # 列与角色牌页同一口径（右对齐）
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
            modifier = ComposeModifier.weight(1f).padding(start = RankNameGap),
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
            modifier = ComposeModifier.weight(1f).padding(start = RankNameGap),
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

/**
 * ---- 玩家信息头几何（V36 任务 B：首页 ProfileCard 与统计页信息卡共用一套口径）----
 * V37-3 后只剩这三枚：头像直径、头像↔文本列间距、昵称行↔UID 行间距。
 * 🔴 原先第四枚 `PLAYER_INFO_NICK_TIER_GAP_DP`（昵称↔段位 8dp Spacer）随任务 B 一起删除——
 * 昵称与段位已合并成**单个** Text（见 [PlayerInfoHeader]），中间只剩一个空格字符，没有 Spacer 可量。
 */
internal const val PLAYER_INFO_AVATAR_DP = 64
internal const val PLAYER_INFO_TEXT_COLUMN_GAP_DP = 12
internal const val PLAYER_INFO_UID_LINE_GAP_DP = 4

/**
 * 玩家信息头：64dp 头像 + 文本列（第一行「昵称 + 段位」**合并成单个 Text**，第二行 UID）。
 * 首页个人信息卡照抄本结构（用户第 1 项），段位无值时整段不渲染（不是渲染占位）。
 *
 * 🔴 V37-3 任务 B（用户方案原话：「使用单个 textview，为段位部分设置 spannablestring +
 * foregroundspan，特定文本位置颜色定向改变」）：
 * 旧结构是 `Row { Text(昵称) + Spacer + Text(段位) }`，两个 Text 分属两个布局节点，
 * 真机 bounds 实测（1080×2340）昵称 LEFT=297、段位 LEFT=467、UID LEFT=297
 * ⇒ 段位与 UID 左缘差 170px，这就是用户报的「段位和 ID 不对齐」。
 * 合并为单个 Text 后：段位只是同一行内文本里的一段染色 span，
 * **整行左缘 == UID 左缘**（同一个 Column 的同一个 start），错位问题结构性消失；
 * 昵称与段位的基线也交给文本排版自己保证，不再需要 `alignByBaseline()`（V36 红线 1 的那套 hack 全部移除）。
 * 代价：段位与昵称同字号（titleMedium 16sp，旧结构段位是 titleSmall 14sp）——同一 Text 内混排
 * 字号才是"看着不齐"的另一个来源，统一字号是这次方案的一部分。
 *
 * 🔴 `tierColor()` 是 @Composable，`buildAnnotatedString { }` 的 lambda 不是组合上下文，
 * 直接在里面调会编译不过 ⇒ 段位的 `SpanStyle` 在 Composable 体内先取好，lambda 只用成品值。
 */
@Composable
internal fun PlayerInfoHeader(
    avatarUrl: String?,
    nickname: String,
    tier: String,
    uid: String,
    modifier: ComposeModifier = ComposeModifier,
) {
    // tier 为空 → null → 整段（含前导空格）不渲染；段位名未命中色表时 tierColor 回落到
    // onSurfaceVariant，文字照旧显示，只是不着色（与旧结构同一口径）。
    val tierSpan = if (tier.isEmpty()) null else SpanStyle(color = tierColor(tier))
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Avatar(url = avatarUrl, size = PLAYER_INFO_AVATAR_DP.dp, contentDescription = nickname)
        Column(ComposeModifier.weight(1f).padding(start = PLAYER_INFO_TEXT_COLUMN_GAP_DP.dp)) {
            Text(
                text = buildAnnotatedString {
                    append(nickname)
                    if (tierSpan != null) {
                        append(' ')
                        withStyle(tierSpan) { append(tier) }
                    }
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = ComposeModifier.fillMaxWidth(),
            )
            Spacer(ComposeModifier.height(PLAYER_INFO_UID_LINE_GAP_DP.dp))
            // UID 与「昵称+段位」同一 Column、同一 start ⇒ 左缘天然对齐（本任务要修的就是这件事）
            Text(
                uid,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
            // V29 需求 6 / V36 任务 B：个人信息区与首页 ProfileCard 同一套结构，
            // 两侧都收敛为共享的 PlayerInfoHeader（几何常量单一来源，见其 KDoc）。
            PlayerInfoHeader(
                avatarUrl = avatarUrl,
                nickname = summary.nickname,
                tier = tier,
                uid = uid,
            )
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
