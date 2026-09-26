// 玩家信息弹窗：移植 web/src/components/PlayerDetailDialog.tsx 的内容与布局，适配 M3 居中 Dialog。
// 四入口共用，参数 (uid, onClose)；弹窗开关由调用页持有，本组件不持全局 controller。
// 版式：头部（头像/昵称+段位/UID）+ 天梯/巅峰积分 + 展示角色 + 参赛经历；
// is_shield / 无 pageInfo 走独立分支；胜负语义色来自 LocalSemanticColors。

package com.gigi.tcg.ui.dialogs.playerdetail

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.domain.getTierStars
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LocalToast
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.components.tierLabel
import com.gigi.tcg.ui.theme.LocalSemanticColors
import com.gigi.tcg.ui.theme.SemanticColors

@Composable
fun PlayerDetailDialog(uid: String?, onClose: () -> Unit) {
    if (uid == null) return

    val app = LocalContext.current.applicationContext as Application
    val viewModel: PlayerDetailViewModel = viewModel(factory = PlayerDetailViewModel.factory(app))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sessionUid by viewModel.sessionUid.collectAsStateWithLifecycle()
    val toast = LocalToast.current
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val semantic = LocalSemanticColors.current

    LaunchedEffect(uid) { viewModel.openPlayerDetail(uid) }

    val content = (state as? DetailUiState.Content)?.takeIf { it.uid == uid }
    val isSelf = sessionUid != null && sessionUid == uid

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.detail_info_title)) },
        text = {
            // 🔴 必须 fillMaxWidth：只挂 verticalScroll 时宽度不受约束，内容列会塌缩到
            // 「最宽子项的内在宽度」（实测真机 ≈ 180dp），于是弹窗右侧空出约 140dp、
            // FlowRow 却按这个窄宽度换行（用户真机反馈"右方第三列还有空间但卡片仍换行"）。
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    content != null -> PlayerDetailBody(content, semantic)
                    state is DetailUiState.Error -> {
                        val e = state as DetailUiState.Error
                        ErrorState(message = e.message, onRetry = if (e.canRetry) viewModel::retry else null)
                    }
                    else -> LoadingView(label = stringResource(R.string.state_detail_loading))
                }
            }
        },
        confirmButton = {
            if (content != null) {
                val copiedSelfToast = stringResource(R.string.toast_copied_uid, content.uid)
                val copiedOpponentToast = stringResource(R.string.toast_copied_opponent_uid, content.uid)
                TextButton(onClick = {
                    @Suppress("DEPRECATION") clipboard.setText(AnnotatedString(content.uid))
                    toast(if (isSelf) copiedSelfToast else copiedOpponentToast)
                }) { Text(stringResource(R.string.action_copy_uid)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun PlayerDetailBody(content: DetailUiState.Content, semantic: SemanticColors) {
    val pageInfo = content.data.pageInfo
    when {
        // T10：接口无该玩家数据 → 说明式版式（区别于加载失败）
        pageInfo == null -> EmptyState(
            icon = Icons.Outlined.Search,
            title = stringResource(R.string.detail_not_found_title),
            message = stringResource(R.string.detail_not_found_message, content.uid),
        )
        // 屏蔽分支：仅保留昵称与 UID
        pageInfo.isShield == true -> Column {
            HeaderRow(uid = content.uid, nickname = pageInfo.nickname, avatarUrl = pageInfo.avatarUrl)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.detail_shielded),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> Column {
            HeaderRow(
                uid = content.uid,
                nickname = pageInfo.nickname,
                avatarUrl = pageInfo.avatarUrl,
                tier = tierLabel(getTierStars(pageInfo.ladderScore ?: 0)),
            )
            Spacer(Modifier.height(8.dp))
            ScoresRow(
                ladder = pageInfo.ladderScore ?: 0,
                peak = pageInfo.peakScore ?: 0,
                semantic = semantic,
            )
            RolesSection(pageInfo.roles.orEmpty())
            EntriesSection(pageInfo.entryExperience.orEmpty(), semantic)
        }
    }
}

@Composable
private fun HeaderRow(uid: String, nickname: String?, avatarUrl: String?, tier: String = "") {
    val gold = LocalSemanticColors.current.gold
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(url = avatarUrl, size = 64.dp, contentDescription = nickname)
        Spacer(Modifier.width(16.dp))
        Column {
            // 昵称与段位字号不同，基线对齐避免视觉不齐；Bottom 兜底无基线的子项
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = nickname ?: stringResource(R.string.common_unknown),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                )
                if (tier.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = tier,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = gold,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            Text(
                text = stringResource(R.string.player_uid, uid),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScoresRow(ladder: Int, peak: Int, semantic: SemanticColors) {
    Row(Modifier.fillMaxWidth()) {
        ScoreItem(label = stringResource(R.string.score_ladder), value = ladder, color = semantic.win, modifier = Modifier.weight(1f))
        ScoreItem(label = stringResource(R.string.score_peak), value = peak, color = semantic.gold, modifier = Modifier.weight(1f))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RolesSection(roles: List<com.gigi.tcg.data.model.RoleInfo>) {
    SectionTitle(stringResource(R.string.detail_roles_section), roles.size)
    if (roles.isEmpty()) {
        Text(stringResource(R.string.detail_roles_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        roles.forEach { role ->
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(
                        text = role.name ?: stringResource(R.string.common_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.detail_proficiency, role.proficiency ?: 0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun EntriesSection(entries: List<com.gigi.tcg.data.model.EntryExperience>, semantic: SemanticColors) {
    SectionTitle(stringResource(R.string.detail_history_section), entries.size)
    if (entries.isEmpty()) {
        Text(stringResource(R.string.detail_history_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    entries.forEachIndexed { index, entry ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(20.dp),
            )
            Text(
                text = entry.competitionName ?: stringResource(R.string.common_unknown),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = entry.competitionResult ?: stringResource(R.string.common_unknown),
                style = MaterialTheme.typography.labelLarge,
                color = resultColor(entry.competitionResult, semantic),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.detail_points, entry.score ?: 0),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 胜负语义着色：对 competition_result 文案做胜/负判定（红绿成败约定，不参与动态取色） */
private fun resultColor(result: String?, semantic: SemanticColors): Color {
    if (result == null) return Color.Unspecified
    return when {
        result.contains("胜") || result.contains("冠") || result.contains("第一") ||
            result.equals("win", ignoreCase = true) -> semantic.win
        result.contains("负") || result.contains("败") || result.contains("最后") ||
            result.equals("lose", ignoreCase = true) -> semantic.lose
        else -> Color.Unspecified
    }
}
