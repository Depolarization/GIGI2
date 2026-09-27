// 首页路由：移植 web/src/pages/HomePage.tsx（ProfileCard + RecordItem + 双块三态渲染）。
// 展示逻辑一律走 domain 纯函数（tier/opponent/format），UI 层不重算；
// 胜负语义色取 LocalSemanticColors（红线 8 固定色，不参与动态取色）；
// 整页 PullToRefreshBox 下拉触发 refresh()，首屏两块同在加载时只渲染一个 LoadingView（整屏居中）。

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
import com.gigi.tcg.ui.export.fetchRecordAvatarBitmaps
import com.gigi.tcg.ui.export.renderRecordsCardBitmap
import com.gigi.tcg.ui.theme.LocalSemanticColors
import com.gigi.tcg.ui.theme.SemanticColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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

    // ---- 最近对局导出卡片图（V26：版式照抄首页 RecordItem，用户"省得截屏"）----
    // 内容组装走本包纯函数 buildRecentRecordsCards（JVM 单测覆盖），头像在 IO 线程经 Coil
    // 预取（allowHardware=false，硬件位图画不上软件 Canvas），绘制在 ui/export 渲染器，
    // 落盘走 CardImageSaver（文件名带导出日期、子目录 Pictures/GIGI/最近对局）。
    val appContext = LocalContext.current.applicationContext
    val showToast = LocalToast.current
    val coroutineScope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val recordList = (state.records as? Async.Content<List<GameRecord>>)?.value.orEmpty()
    val startExport: () -> Unit = {
        if (recordList.isNotEmpty() && !exporting) {
            exporting = true
            val cards = buildRecentRecordsCards(recordList, sessionUid.orEmpty())
            coroutineScope.launch {
                try {
                    val avatars = fetchRecordAvatarBitmaps(appContext, cards)
                    val bitmap = renderRecordsCardBitmap(cards, avatars)
                    try {
                        val dateText = exportDateText()
                        CardImageSaver(appContext).saveBitmap(
                            bitmap,
                            baseName = "最近对局_$dateText",
                            format = Bitmap.CompressFormat.JPEG,
                            subDir = EXPORT_DIR_RECORDS,
                        )
                        showToast(
                            LocaleStrings.get(
                                R.string.toast_saved_to_album_path,
                                buildAlbumRelativePath(Environment.DIRECTORY_PICTURES, GIGI_ALBUM_NAME, EXPORT_DIR_RECORDS),
                            )
                        )
                    } finally {
                        // 回收放 finally：saveBitmap 抛异常也不能漏掉位图
                        avatars.filterNotNull().forEach { it.recycle() }
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
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(url = profile.avatarUrl, size = 64.dp, contentDescription = profile.nickname)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        text = profile.nickname ?: unknownLabel,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.player_uid, uid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.home_tier_label, tier),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // 积分区垂直置于个人信息下方，布局对齐 PlayerDetailDialog.ScoresRow
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

@Composable
private fun ScoreItem(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color,
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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(url = record.avatarUrl, size = 56.dp, contentDescription = record.nickname)
            // 对手区域：点击跳转对手详情（UNKNOWN 不响应由 clickable enabled 承担）
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
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
            Column(horizontalAlignment = Alignment.End) {
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
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
