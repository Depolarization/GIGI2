// 首页路由：移植 web/src/pages/HomePage.tsx（ProfileCard + RecordItem + 双块三态渲染）。
// 展示逻辑一律走 domain 纯函数（tier/opponent/format），UI 层不重算；
// 胜负语义色取 LocalSemanticColors（红线 8 固定色，不参与动态取色）；
// 整页 PullToRefreshBox 下拉触发 refresh()，首屏两块同在加载时只渲染一个 LoadingView（整屏居中）。
// V28：个人信息卡的版式按「层级留白 / 竖向对齐轴 / 表头与数值的字重字号差」重排，
// 见文件下方 PROFILE_*_DP 常量组说明。
// V29：移除了「导出最近对局长图」功能（渲染器 RecentRecordsCardRenderer 已删），
// 首页只保留展示与刷新。

package com.gigi.tcg.ui.screens.home

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GameRecord
import com.gigi.tcg.data.model.PageInfo
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.domain.UNKNOWN_OPPONENT_UID
import com.gigi.tcg.domain.extractOpponentUid
import com.gigi.tcg.domain.formatRecordTime
import com.gigi.tcg.domain.formatScoreChange
import com.gigi.tcg.domain.getTierStars
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.components.CenteredScrollableContainer
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.components.LocalToast
import com.gigi.tcg.ui.components.tierLabel
import com.gigi.tcg.ui.screens.cardstats.PlayerInfoHeader
import com.gigi.tcg.ui.theme.LocalSemanticColors
import com.gigi.tcg.ui.theme.SemanticColors
import com.gigi.tcg.ui.theme.scoreDeltaColor

// ---- ProfileCard 版式常量（V28 起，V36 任务 B 后个人信息区几何归 PlayerInfoHeader）----
// 头像/昵称/段位/UID 的版式常量收敛到 cardstats/PlayerInfoHeader（PLAYER_INFO_*），
// 首页与统计页同一事实源；这里只剩卡片留白与积分区/对局卡的几何。
// 刻意用 const Int + `.dp`：JVM 单测（HomeProfileLayoutTest 对源码文本断言）能直接解析数值做区间校验。

/** 卡片内边距（四边同值，导出图 PROFILE_PADDING_PX = 16×3） */
private const val PROFILE_CARD_PADDING_DP = 16

/**
 * 跨组留白：身份块 → 积分区（导出图 PROFILE_ROW_GAP_PX = 12×3）。
 * 行 1 高 64dp（头像撑开）、文本块垂直居中 ⇒ UID 下缘到积分表头上缘有实测留白支撑，
 * 约是身份组内 4dp 的 3 倍以上，「头像 / 昵称+段位 / UID / 积分」四层节奏一眼可辨。
 */
private const val PROFILE_SCORES_GAP_DP = 12

/** 积分表头 ↔ 数值：两列共用同一间距（与身份组内留白同值，保持"同一层内 4dp"的一致性） */
private const val SCORE_LABEL_VALUE_GAP_DP = 4

/** 对局卡内边距 / 积分列↔胜负列间距（沿用原导出图 CARD_PADDING_PX、RESULT_GAP_PX；导出渲染器 V29 已删） */
private const val RECORD_CARD_PADDING_DP = 12
/** 对局卡头像直径。V40-C：与排行榜页同尺寸 44dp（事实源 = RankRoute 的 `Avatar(..., size = 44.dp, ...)`） */
private const val RECORD_AVATAR_SIZE_DP = 44
private const val RECORD_RESULT_GAP_DP = 12

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoute(
    container: AppContainer,
    /** uid + 入口头像：头像来自列表接口（对局记录 / 排行榜），供详情无权访问时兜底（V26） */
    onOpenPlayerDetail: (uid: String, avatarUrl: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(app))
    val sessionUid by viewModel.sessionUid.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val semantic = LocalSemanticColors.current

    // 换会话（重新登录）才 force 重拉两块；首帧/首个 UID 由 VM init 串行化首刷处理，
    // 若此处也 refresh 会与首刷并发，再次触发启动瞬态米游社保流（-500004）
    var lastUid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionUid) {
        val uid = sessionUid
        if (uid != null && lastUid != null && uid != lastUid) viewModel.refresh()
        if (uid != null) lastUid = uid
    }

    // 下拉刷新指示器：只由用户主动下拉（VM.refreshing）驱动，不从"是否在加载"派生，
    // 否则冷启动首屏顶部圈会与居中 LoadingView 同转（两个 progressbar）
    val isRefreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        // V36 任务 D/E：三态排布统一为「整屏一个视图」——
        // 首屏两块同在加载 → 整屏 LoadingView；任一块失败 → 整屏一个 ErrorState（二合一，
        // 不再「个人信息区一套错、对局区一套错」）；两块都有结果但列表为空 → 空态在剩余
        // 高度里居中（不滚动容器 + weight(1f) + Center，公开组件签名不动）。
        val profile = state.profile
        val records = state.records
        val profileError = profile as? Async.Error
        val recordsError = records as? Async.Error
        val recordsEmpty = (records as? Async.Content)?.value?.isEmpty() == true
        when {
            profile is Async.Loading && records is Async.Loading -> {
                // 首屏两块同在加载：整屏居中，与排行榜/图鉴/卡牌统计三页一致。
                // 不再套外层滚动 Column——CenteredScrollableContainer 内部自带 verticalScroll，
                // PullToRefreshBox 依然收得到 nestedScroll，下拉刷新不失效。
                CenteredScrollableContainer { LoadingView(label = stringResource(R.string.state_home_first_loading)) }
            }

            profileError != null || recordsError != null -> {
                // 错误二合一（用户第 13 项）：位置与加载态一致，重试整页重拉两块。
                // message 为 null 只可能来自 profile（合法响应但数据缺失），兜底资料卡空文案。
                CenteredScrollableContainer {
                    ErrorState(
                        message = profileError?.message ?: recordsError?.message
                            ?: stringResource(R.string.state_home_profile_empty),
                        onRetry = viewModel::refresh,
                    )
                }
            }

            else -> {
                // 空态不套 verticalScroll：Column 有界高里 weight(1f) 才成立（滚动容器里
                // weight 非法且高度=内容高，居中无从谈起）。此时页面必然不足一屏
                // （资料卡 + 标题行 + 空态占位），放弃滚动没有副作用。
                val scrollable = !recordsEmpty
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier,
                        )
                        .padding(16.dp),
                ) {
                    when (profile) {
                        // Loading 只可能是「另一块先落定」的过渡态；Error 已在上面整屏接管
                        is Async.Loading -> LoadingView(label = stringResource(R.string.state_home_profile_loading))
                        is Async.Content -> ProfileCard(
                            profile = profile.value,
                            uid = sessionUid.orEmpty(),
                            // 本人卡片：详情接口必定可访问，无需入口头像兜底
                            onClick = { onOpenPlayerDetail(sessionUid.orEmpty(), null) },
                        )

                        else -> Unit
                    }

                    // IconButton 触摸目标高 48dp，行内文字上下自带约 12dp 视觉空白，
                    // 故上留 8dp（≈原 16dp 观感）、下留 4dp（标题贴列表不悬空）
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.home_recent_games),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    if (recordsEmpty) {
                        // 空态居中（任务 D）：吃掉剩余高度后 Box 居中，与加载/错误整屏态同一观感
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            EmptyState(title = stringResource(R.string.home_empty_records))
                        }
                    } else {
                        when (records) {
                            // 🔴 保持原样（V40-C 越权改动已回退）：本分支渲染时 scrollable=true
                            // （外层 Column 挂着 verticalScroll），此时用 weight(1f) 拿的是**无限高**，
                            // 与上方 recordsEmpty 分支（那一条外层不滚动、weight 才有界）不是一回事。
                            is Async.Loading -> LoadingView(label = stringResource(R.string.state_home_records_loading))
                            is Async.Content -> {
                                // 服务端最多返回 10 条：外层整页已可滚，直接顺序渲染。
                                // 不再嵌套 LazyColumn——原"视口高 − 列表顶部偏移"方案把两个
                                // onSizeChanged 挂在同一个 Box 上，测的都是 Box 自身，
                                // 差值恒为 0，列表被裁成 0 高不可见（取证报告 偏离-1 根因）
                                Column(
                                    Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    records.value.forEachIndexed { index, record ->
                                        key("${record.transNo ?: "na"}-$index") {
                                            RecordItem(
                                                record = record,
                                                uid = sessionUid.orEmpty(),
                                                semantic = semantic,
                                                // 对局记录的 nickname/avatar_url 就是对手的
                                                // （解析见 GameRecordsParseTest："对手1"），
                                                // 故可直接作为对手详情弹窗的头像兜底。
                                                onOpenOpponent = { uid -> onOpenPlayerDetail(uid, record.avatarUrl) },
                                            )
                                        }
                                    }
                                }
                            }

                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: PageInfo,
    uid: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tier = tierLabel(getTierStars(profile.ladderScore ?: 0))
    val semantic = LocalSemanticColors.current
    val unknownLabel = stringResource(R.string.common_unknown)
    Card(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(PROFILE_CARD_PADDING_DP.dp),
        ) {
            // V36 任务 B（用户第 1、2 项）：个人信息区照抄统计页版式——共用 PlayerInfoHeader；
            // V37-3 任务 B 起昵称与段位合并成单个 Text（段位走 SpanStyle 行内染色），
            // 几何常量仍是单一事实源（PLAYER_INFO_*）。
            PlayerInfoHeader(
                avatarUrl = profile.avatarUrl,
                nickname = profile.nickname ?: unknownLabel,
                tier = tier,
                uid = uid,
            )
            Spacer(Modifier.height(PROFILE_SCORES_GAP_DP.dp))
            // V37-3 任务 C（用户澄清口径已定）：个人信息卡改为**竖向三段**——
            // ① PlayerInfoHeader（头像 + 昵称/段位 + UID）② 天梯/巅峰两栏 ③（卡外）最近对局。
            // 积分区从「缩进在头像右侧的文本列里」提升为与 PlayerInfoHeader **平级**的一整行
            // fillMaxWidth：卡片内容左缘 = padding(16) = 头像左缘，所以两栏左缘自然 == 头像左缘。
            // 旧写法给整块补了「头像直径 + 列间距」的起始缩进，把两栏推到文本列左缘（实测 LEFT=297，
            // 而头像 LEFT=88，差 209px），正是用户报的「天梯/巅峰没有和头像对齐」；
            // 参照物是同项目里已经正确的 PlayerDetailDialog.ScoresRow —— 它就是独立于 HeaderRow
            // 的一整行 fillMaxWidth，不缩进在头像右。
            // 🔴 两栏**内部**维持原样（各自 label 与数值左对齐，实测已对齐，用户也确认这点是对的）。
            Row(Modifier.fillMaxWidth()) {
                ScoreItem(
                    label = stringResource(R.string.score_ladder),
                    value = profile.ladderScore ?: 0,
                    color = semantic.win,
                    modifier = Modifier.weight(1f),
                )
                ScoreItem(
                    label = stringResource(R.string.score_peak),
                    value = profile.peakScore ?: 0,
                    color = semantic.gold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 积分一格 = 上「表头」下「数值」：表头走 labelMedium（12sp，卡片内最小档）再加粗 +
 * 拉开字距，读作小标题而不是正文；数值 titleLarge（22sp）粗体 + 语义色，是全卡最重的一层。
 * 两格样式共用本函数，间距/字号差天然统一。
 */
@Composable
private fun ScoreItem(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(SCORE_LABEL_VALUE_GAP_DP.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                // 表头字距拉开：CJK 逐字间距变大后"标题感"更明确，也不会被误读成数值的注脚
                letterSpacing = 1.5.sp,
            ),
            // 弱化只换"角色"不换对比度：用 M3 的 onSurfaceVariant（AA 达标），不用自定义灰
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun RecordItem(
    record: GameRecord,
    uid: String,
    semantic: SemanticColors,
    onOpenOpponent: (uid: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val opponentUid = extractOpponentUid(record.transNo, uid)
    val unknown = opponentUid == UNKNOWN_OPPONENT_UID
    val isWin = record.result == "Win"
    val isLose = record.result == "Lose"
    // V25：长按条目直接复制对手 UID（原先只能点进详情弹窗再复制，两步）。
    val showToast = LocalToast.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val copiedToast = stringResource(R.string.toast_copied_opponent_uid, opponentUid)

    val ladderScore = record.ladderScore?.score ?: 0
    val ladderChange = record.ladderScore?.scoreChange ?: 0
    val peakScore = record.peakScore?.score ?: 0
    val peakChange = record.peakScore?.scoreChange ?: 0
    // buildAnnotatedString 的 lambda 非组合上下文，文案先取好
    val ladderPrefix = stringResource(R.string.home_ladder_prefix)
    val peakPrefix = stringResource(R.string.home_peak_prefix)
    val peakPlaceholder = stringResource(R.string.home_peak_placeholder)

    Card(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = !unknown,
                onClick = { onOpenOpponent(opponentUid) },
                onLongClick = {
                    @Suppress("DEPRECATION") clipboard.setText(AnnotatedString(opponentUid))
                    showToast(copiedToast)
                },
            ),
    ) {
        // V40-C（V28 口径反转，用户原话：「头像过大，改为和排行榜一页一致大小，确保头像居中对齐，
        // 包括右方的积分变化和胜负情况也要居中对齐于材料中轴线」）：旧 V28 的 Top + 三处
        // alignByBaseline 是为对齐导出图「右缘组顶对齐到昵称行」定的（导出渲染器 V29 已删），
        // 现按用户要求反转——头像 44dp 与排行榜同尺寸，头像 / 昵称列 / 积分变化 / 胜负
        // 四块一律垂直居中于卡片内容区中轴。卡高随之变矮（44dp 头像 + 上下 12dp 内边距），属预期。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(RECORD_CARD_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(url = record.avatarUrl, size = RECORD_AVATAR_SIZE_DP.dp, contentDescription = record.nickname)
            // 对手区域：点击跳转对手详情（UNKNOWN 不响应由 clickable enabled 承担）
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = RECORD_CARD_PADDING_DP.dp),
            ) {
                Text(
                    text = record.nickname ?: stringResource(R.string.common_unknown),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // V24（真机图 4）：原先一行是 "UID:340438735\n08-12 14:17"，
                // 前缀 + 大字号让 9 位 UID 被挤成两行。现在去掉 "UID:" 文字标签
                // （与排行榜同一套设计语言：位置即语义），并把 UID / 时间拆成各自单行。
                Text(
                    text = opponentUid,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatRecordTime(record.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Column(
                // V37-3 任务 D（用户第 3 项「胜/负和天梯/巅峰积分变动的文本没有居中对齐」）：
                // 旧写法 horizontalAlignment = Alignment.End ⇒ 两行只是"右贴齐"，真机 bounds 实测
                // `天梯 2760 (10)` LEFT=700、`巅峰 -` LEFT=843（同右缘 931）——短的那行整个贴在右边，
                // 两行左缘各浮一处，读起来就是"没对齐"。
                // 现改为**两行共用同一个列宽**并居中：列宽用 width(IntrinsicSize.Max)，
                // 由 Compose 按内容实测（= 本卡两行里较长那行的自然宽），
                // 🔴 不拍脑袋写死 dp：写死既放不下「天梯 2760 (+150)」这类长文案，也会在
                // 三语（Ladder/Peak、巔梯/巔峰）之间长短不一。短行在列宽内居中 ⇒ 两行左右缘一致。
                // 胜负列不额外定宽：三个取值（胜/负/空、W/L/–）都是**单字**，天然等宽，
                // 且整组右贴齐卡片内容右缘 ⇒ 跨卡右缘恒定，无需再套一个宽度常量。
                modifier = Modifier
                    .width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // V42（用户 2026-10-02 转达玩家建议「减分改成红色/橙色更直观」）：
                // 此前天梯恒 `semantic.win`、巅峰恒 `semantic.gold` —— **颜色不携带任何符号信息**，
                // 于是「(-7)」被染成绿色，玩家看到的是"绿色=掉了分"，语义反了。
                // 现按 [scoreDeltaColor] 的统一口径：涨=win / 跌=lose / 平=onSurfaceVariant（中性）。
                // 🔴 两个色值必须先在 Composable 体内取好再进 buildAnnotatedString：
                // 它的 lambda 不是组合上下文（与本函数上方 ladderPrefix 同一理由）。
                // 巅峰行不再用 gold：那一抹金不携带信息，且与"跌=红"并排时会把符号读乱；
                // 「巅峰」的身份由前缀文字承担（分数区的巅峰数值仍是 gold，见 PlayerDetailDialog.ScoresRow）。
                val ladderDeltaColor = scoreDeltaColor(ladderChange)
                val peakDeltaColor = scoreDeltaColor(peakChange)
                Text(
                    text = buildAnnotatedString {
                        append("$ladderPrefix $ladderScore ")
                        withStyle(SpanStyle(color = ladderDeltaColor)) {
                            append(formatScoreChange(ladderChange))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    text = buildAnnotatedString {
                        if (peakScore == 0 && peakChange == 0) {
                            append(peakPlaceholder)
                        } else {
                            append("$peakPrefix $peakScore ")
                            withStyle(SpanStyle(color = peakDeltaColor)) {
                                append(formatScoreChange(peakChange))
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            Text(
                text = if (isWin) {
                    stringResource(R.string.home_result_win)
                } else if (isLose) {
                    stringResource(R.string.home_result_lose)
                } else {
                    stringResource(R.string.home_result_none)
                },
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    isWin -> semantic.win
                    isLose -> semantic.lose
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = RECORD_RESULT_GAP_DP.dp),
            )
        }
    }
}
