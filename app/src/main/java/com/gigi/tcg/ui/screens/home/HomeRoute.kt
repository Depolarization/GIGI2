// 首页路由：移植 web/src/pages/HomePage.tsx（ProfileCard + RecordItem + 双块三态渲染）。
// 展示逻辑一律走 domain 纯函数（tier/opponent/format），UI 层不重算；
// 胜负语义色取 LocalSemanticColors（红线 8 固定色，不参与动态取色）；
// 整页 PullToRefreshBox 下拉触发 refresh()，首屏两块同在加载时只渲染一个 LoadingView（整屏居中）。
// V28：个人信息卡的版式按导出图 RecentRecordsCardRenderer 的几何口径重排（层级留白 /
// 竖向对齐轴 / 表头与数值的字重字号差），见文件下方 PROFILE_*_DP 常量组说明。

package com.gigi.tcg.ui.screens.home

import android.app.Application
import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Download
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.i18n.LocaleStrings
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
import com.gigi.tcg.ui.dialogs.cardcover.CardImageSaver
import com.gigi.tcg.ui.dialogs.cardcover.EXPORT_DIR_RECORDS
import com.gigi.tcg.ui.dialogs.cardcover.GIGI_ALBUM_NAME
import com.gigi.tcg.ui.dialogs.cardcover.buildAlbumRelativePath
import com.gigi.tcg.ui.dialogs.cardcover.exportDateText
import com.gigi.tcg.ui.dialogs.cardcover.exportDirName
import com.gigi.tcg.ui.export.fetchProfileAvatarBitmap
import com.gigi.tcg.ui.export.fetchRecordAvatarBitmaps
import com.gigi.tcg.ui.export.renderRecordsCardBitmap
import com.gigi.tcg.ui.theme.LocalSemanticColors
import com.gigi.tcg.ui.theme.SemanticColors
import com.gigi.tcg.ui.theme.tierColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ---- ProfileCard 版式常量（V28）----
// 参照标准是导出图渲染器 RecentRecordsCardRenderer（dp×3 换算的同一套几何），
// 首页保持 Compose 实现、只把留白节奏与对齐规则对齐过去，做到两处所见即所得。
// 刻意用 const Int + `.dp`：JVM 单测（ProfileCardSpecTest 对源码文本断言）能直接解析数值做区间校验。

/** 卡片内边距（四边同值，导出图 PROFILE_PADDING_PX = 16×3） */
private const val PROFILE_CARD_PADDING_DP = 16

/** 头像直径（导出图 PROFILE_AVATAR_PX = 64×3）；行 1 的高度由它撑开，文本块在其中垂直居中 */
private const val PROFILE_AVATAR_SIZE_DP = 64

/**
 * 头像 ↔ 文本列：导出图 PROFILE_TEXT_GAP_PX。这 12dp 同时定义了卡片内唯一的竖向对齐轴
 * （昵称左缘 = UID 左缘 = 积分区左缘），故积分区也按「头像直径 + 本间距」缩进。
 */
private const val PROFILE_TEXT_COLUMN_GAP_DP = 12

/** 昵称 ↔ 段位同行间距（导出图 PROFILE_NICK_TIER_GAP_PX） */
private const val PROFILE_NICK_TIER_GAP_DP = 8

/**
 * 组内留白：昵称/段位 → UID。M3 行高已自带约 4dp 余量（titleMedium 24 行 / 16 字），
 * 显式再加 4dp 让「同属身份层的相邻两行」读起来是一组，而不是三段散开的文本。
 */
private const val PROFILE_IDENTITY_LINE_GAP_DP = 4

/**
 * 跨组留白：身份块 → 积分区（导出图 PROFILE_ROW_GAP_PX = 12×3）。
 * 行 1 高 64dp（头像撑开）、文本块垂直居中 ⇒ UID 下缘到积分表头上缘的实际留白
 * ≈ (64 − 44)/2 + 12 = 22dp（文本块 24 + 4 + 16 = 44dp），约是组内 4dp 的 5 倍，
 * 「头像 / 昵称+段位 / UID / 积分」四层节奏一眼可辨、又不至于散开。
 */
private const val PROFILE_SCORES_GAP_DP = 12

/** 积分表头 ↔ 数值：两列共用同一间距（与组内留白同值，保持"同一层内 4dp"的一致性） */
private const val SCORE_LABEL_VALUE_GAP_DP = 4

/** 对局卡内边距 / 头像直径 / 积分列↔胜负列间距（导出图 CARD_PADDING_PX、AVATAR_SIZE_PX、RESULT_GAP_PX） */
private const val RECORD_CARD_PADDING_DP = 12
private const val RECORD_AVATAR_SIZE_DP = 56
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

    // ---- 最近对局导出卡片图（V27：一比一复刻首页整页——个人信息卡 + 对局卡列表，
    // 仅排除「最近对局」标题行与两个按钮；用户「省得截屏」）----
    // 内容组装走本包纯函数 buildRecentRecordsCards / buildProfileCardSpec（JVM 单测覆盖），
    // 头像在 IO 线程经 Coil 预取（allowHardware=false，硬件位图画不上软件 Canvas），
    // 绘制在 ui/export 渲染器，落盘走 CardImageSaver（文件名带导出日期、
    // 子目录 Pictures/GIGI/最近对局/<uid>）。
    val appContext = LocalContext.current.applicationContext
    val showToast = LocalToast.current
    val coroutineScope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val recordList = (state.records as? Async.Content<List<GameRecord>>)?.value.orEmpty()
    val profilePage = (state.profile as? Async.Content)?.value
    // tierColor 是 @Composable（C 路），协程里调不到 ⇒ 组合上下文解析成 ARGB 灌进导出 spec；
    // 与首页 ProfileCard 所见即所得（导出图不随主题漂移的定版色在渲染器内部）
    val exportTierColorArgb = tierColor(
        profilePage?.let { tierLabel(getTierStars(it.ladderScore ?: 0)) } ?: "",
    ).toArgb()
    val startExport: () -> Unit = {
        if (recordList.isNotEmpty() && !exporting) {
            exporting = true
            val cards = buildRecentRecordsCards(recordList, sessionUid.orEmpty())
            val profileSpec = profilePage?.let {
                buildProfileCardSpec(it, sessionUid.orEmpty(), exportTierColorArgb)
            }
            coroutineScope.launch {
                try {
                    val avatars = fetchRecordAvatarBitmaps(appContext, cards)
                    val profileAvatar = fetchProfileAvatarBitmap(appContext, profileSpec?.avatarUrl)
                    val bitmap = renderRecordsCardBitmap(cards, avatars, profileSpec, profileAvatar)
                    try {
                        val dateText = exportDateText()
                        val dir = exportDirName(EXPORT_DIR_RECORDS)
                        CardImageSaver(appContext).saveBitmap(
                            bitmap,
                            baseName = "最近对局_$dateText",
                            format = Bitmap.CompressFormat.JPEG,
                            subDir = dir,
                            accountUid = sessionUid.orEmpty().ifBlank { null },
                        )
                        showToast(
                            LocaleStrings.get(
                                R.string.toast_saved_to_album_path,
                                buildAlbumRelativePath(
                                    Environment.DIRECTORY_PICTURES, GIGI_ALBUM_NAME, dir, sessionUid.orEmpty(),
                                ),
                            )
                        )
                    } finally {
                        // 回收放 finally：saveBitmap 抛异常也不能漏掉位图
                        avatars.filterNotNull().forEach { it.recycle() }
                        profileAvatar?.recycle()
                        bitmap.recycle()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    showToast(e.message ?: LocaleStrings.get(R.string.error_export_failed))
                } finally {
                    exporting = false
                }
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        if (state.profile is Async.Loading && state.records is Async.Loading) {
            // 首屏两块同在加载：整屏居中，与排行榜/图鉴/卡牌统计三页一致。
            // 不再套外层滚动 Column——CenteredScrollableContainer 内部自带 verticalScroll，
            // PullToRefreshBox 依然收得到 nestedScroll，下拉刷新不失效。
            CenteredScrollableContainer { LoadingView(label = stringResource(R.string.state_home_first_loading)) }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when (val profile = state.profile) {
                    is Async.Loading -> LoadingView(label = stringResource(R.string.state_home_profile_loading))
                    is Async.Content -> ProfileCard(
                        profile = profile.value,
                        uid = sessionUid.orEmpty(),
                        // 本人卡片：详情接口必定可访问，无需入口头像兜底
                        onClick = { onOpenPlayerDetail(sessionUid.orEmpty(), null) },
                    )
                    is Async.Error -> ErrorState(
                        message = profile.message ?: stringResource(R.string.state_home_profile_empty),
                        onRetry = viewModel::retryProfile,
                    )
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
                    // 导出最近对局长图（V24）：放在刷新按钮左侧，与「卡牌统计」页的
                    // 导出按钮同款交互（导出中转圈、成功后 Toast 出相册路径）。
                    IconButton(
                        onClick = startExport,
                        enabled = !exporting && recordList.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = stringResource(R.string.home_export_records),
                        )
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.cd_refresh))
                    }
                }
                Spacer(Modifier.height(4.dp))

                when (val records = state.records) {
                    is Async.Loading -> LoadingView(label = stringResource(R.string.state_home_records_loading))
                    is Async.Error -> ErrorState(
                        message = records.message ?: stringResource(R.string.state_home_records_empty),
                        onRetry = viewModel::retryRecords,
                    )
                    is Async.Content -> {
                        if (records.value.isEmpty()) {
                            EmptyState(title = stringResource(R.string.home_empty_records))
                        } else {
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
            // 四层之 1「头像」+ 层 2「昵称/段位」+ 层 3「UID」：文本块整体在 64dp 头像里垂直居中
            // （与导出图 blockTop = row1Top + (avatar − block)/2 同一口径）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    url = profile.avatarUrl,
                    size = PROFILE_AVATAR_SIZE_DP.dp,
                    contentDescription = profile.nickname,
                )
                Column(Modifier.weight(1f).padding(start = PROFILE_TEXT_COLUMN_GAP_DP.dp)) {
                    // 层级靠「字号 + 字重」拉开：昵称 titleMedium 粗体最大、段位 titleSmall 中粗次之，
                    // 两者同行按基线对齐（字号不同时顶端不齐、基线才齐），段位色走 C 路 tierColor
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = profile.nickname ?: unknownLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                        )
                        if (tier.isNotEmpty()) {
                            Spacer(Modifier.width(PROFILE_NICK_TIER_GAP_DP.dp))
                            Text(
                                text = tier,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = tierColor(tier),
                                maxLines = 1,
                                modifier = Modifier.alignByBaseline(),
                            )
                        }
                    }
                    Spacer(Modifier.height(PROFILE_IDENTITY_LINE_GAP_DP.dp))
                    // 去掉 "UID:" 文字标签（位置即语义，与 RecordItem 对手 UID 行同一套设计语言）；
                    // 字号降到 bodySmall（12sp，与积分表头同档"最小层"），不再与段位抢字号。
                    // 左缘不额外缩进 ⇒ 天然与昵称左缘对齐
                    Text(
                        text = uid,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(PROFILE_SCORES_GAP_DP.dp))
            // 四层之 4「积分区」：等宽两列、各占一半；整块缩进「头像直径 + 列间距」，
            // 让表头左缘落进昵称/UID 那条竖向对齐轴（导出图 drawScoreColumn 用同一个 colLeft）。
            // 积分区在个人信息下方，纵向口径同 PlayerDetailDialog.ScoresRow
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = (PROFILE_AVATAR_SIZE_DP + PROFILE_TEXT_COLUMN_GAP_DP).dp),
            ) {
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
        // V28（对齐导出图「右缘组顶对齐到昵称行」）：三块文本改为共用昵称行的基线，
        // 胜负 / 天梯 / 巅峰不再各自垂直居中而落到 UID 那一行上；头像保持贴顶，
        // 卡高仍由 56dp 头像 + 上下 12dp 内边距决定（与导出图同一尺寸）。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(RECORD_CARD_PADDING_DP.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Avatar(url = record.avatarUrl, size = RECORD_AVATAR_SIZE_DP.dp, contentDescription = record.nickname)
            // 对手区域：点击跳转对手详情（UNKNOWN 不响应由 clickable enabled 承担）
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = RECORD_CARD_PADDING_DP.dp)
                    .alignByBaseline(),
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
                modifier = Modifier.alignByBaseline(),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = buildAnnotatedString {
                        append("$ladderPrefix $ladderScore ")
                        withStyle(SpanStyle(color = semantic.win)) {
                            append(formatScoreChange(ladderChange))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = buildAnnotatedString {
                        if (peakScore == 0 && peakChange == 0) {
                            append(peakPlaceholder)
                        } else {
                            append("$peakPrefix $peakScore ")
                            withStyle(SpanStyle(color = semantic.gold)) {
                                append(formatScoreChange(peakChange))
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
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
                modifier = Modifier
                    .padding(start = RECORD_RESULT_GAP_DP.dp)
                    .alignByBaseline(),
            )
        }
    }
}
