// 首页路由：移植 web/src/pages/HomePage.tsx（ProfileCard + RecordItem + 双块三态渲染）。
// 展示逻辑一律走 domain 纯函数（tier/opponent/format），UI 层不重算；
// 胜负语义色取 LocalSemanticColors（红线 8 固定色，不参与动态取色）。

package com.gigi.tcg.ui.screens.home

import android.app.Application
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.data.model.GameRecord
import com.gigi.tcg.data.model.PageInfo
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.domain.UNKNOWN_OPPONENT_UID
import com.gigi.tcg.domain.extractOpponentUid
import com.gigi.tcg.domain.formatRecordTime
import com.gigi.tcg.domain.formatScoreChange
import com.gigi.tcg.domain.formatTier
import com.gigi.tcg.domain.getTierStars
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.theme.LocalSemanticColors
import com.gigi.tcg.ui.theme.SemanticColors

@Composable
fun HomeRoute(
    container: AppContainer,
    onOpenPlayerDetail: (uid: String) -> Unit,
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

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "对局主页",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
            }
        }

        when (val profile = state.profile) {
            is Async.Loading -> LoadingView()
            is Async.Content -> ProfileCard(
                profile = profile.value,
                uid = sessionUid.orEmpty(),
                onClick = { onOpenPlayerDetail(sessionUid.orEmpty()) },
            )
            is Async.Error -> ErrorState(
                message = profile.message ?: "资料卡数据为空",
                onRetry = viewModel::retryProfile,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("最近对局", style = MaterialTheme.typography.titleMedium)

        when (val records = state.records) {
            is Async.Loading -> LoadingView()
            is Async.Error -> ErrorState(
                message = records.message ?: "对局数据为空",
                onRetry = viewModel::retryRecords,
            )
            is Async.Content -> {
                if (records.value.isEmpty()) {
                    EmptyState(title = "暂无对局记录，打一场七圣召唤再来查看吧")
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
                                    onOpenOpponent = onOpenPlayerDetail,
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
private fun ProfileCard(
    profile: PageInfo,
    uid: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tier = formatTier(getTierStars(profile.ladderScore ?: 0))
    Card(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(url = profile.avatarUrl, size = 64.dp, contentDescription = profile.nickname)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = profile.nickname ?: "未知",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "UID:$uid　段位:${tier.ifEmpty { "无段位" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("天梯积分:${profile.ladderScore ?: 0}", style = MaterialTheme.typography.bodyMedium)
                Text("巅峰积分:${profile.peakScore ?: 0}", style = MaterialTheme.typography.bodyMedium)
            }
        }
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

    val ladderScore = record.ladderScore?.score ?: 0
    val ladderChange = record.ladderScore?.scoreChange ?: 0
    val peakScore = record.peakScore?.score ?: 0
    val peakChange = record.peakScore?.scoreChange ?: 0

    Card(
        modifier
            .fillMaxWidth()
            .clickable(enabled = !unknown) { onOpenOpponent(opponentUid) },
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
                    text = record.nickname ?: "未知",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Text(
                    text = "UID:$opponentUid\n${formatRecordTime(record.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = buildAnnotatedString {
                        append("天梯 $ladderScore ")
                        withStyle(SpanStyle(color = semantic.win)) {
                            append(formatScoreChange(ladderChange))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = buildAnnotatedString {
                        if (peakScore == 0 && peakChange == 0) {
                            append("巅峰 -")
                        } else {
                            append("巅峰 $peakScore ")
                            withStyle(SpanStyle(color = semantic.gold)) {
                                append(formatScoreChange(peakChange))
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = if (isWin) "胜" else if (isLose) "负" else "空",
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
