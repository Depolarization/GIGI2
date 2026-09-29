// 玩家信息弹窗：移植 web/src/components/PlayerDetailDialog.tsx 的内容与布局，适配 M3 居中 Dialog。
// 四入口共用，参数 (uid, onClose)；弹窗开关由调用页持有，本组件不持全局 controller。
// 版式：头部（头像/昵称+段位/UID）+ 天梯/巅峰积分 + 展示角色 + 参赛经历；
// 头部与主页 ProfileCard 同一套口径（V37-3 任务 B）：昵称与段位**合并成单个 Text**
// （段位走 SpanStyle 行内染色），其下 UID 独立一行、与整行同左缘；UID 不带 "UID:" 前缀（位置即语义）；
// 段位色按档位取 ui/theme/TierColors.kt（不再一律染金），无段位不占位；
// is_shield / 无 pageInfo 走独立分支；胜负语义色来自 LocalSemanticColors。
// 头像兜底（V26）：列表接口（排行榜 rank_infos / 对局 game_records）必定带回头像，
// 而 other_home_page 在 is_shield 时 page_info.avatar_url 为空 ⇒ 弹窗只剩灰底占位图标。
// 故入口把列表头像随 [PlayerDetailTarget] 带进来，在 UI 层回落，不写进 VM 缓存。
// 底部按钮：关闭（dismiss，左）/ 复制UID（confirm，右）；复制写完剪贴板即收起弹窗。

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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.domain.TierStars
import com.gigi.tcg.domain.getTierStars
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LocalToast
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.components.tierLabel
import com.gigi.tcg.ui.theme.LocalSemanticColors
import com.gigi.tcg.ui.theme.SemanticColors
import com.gigi.tcg.ui.theme.tierColor

/**
 * 打开玩家详情的请求：目标 uid + 可选的「入口头像」。
 * avatarUrl 由入口列表提供（排行榜 rank_infos / 对局 game_records 都返回头像），
 * 只在 other_home_page 的 pageInfo.avatarUrl 缺失（典型：is_shield 无权访问）时兜底显示。
 * 兜底仅发生在 UI 层：不写进 ViewModel 的进程级缓存，避免污染同一 uid 的后续查询。
 */
data class PlayerDetailTarget(
    val uid: String,
    val avatarUrl: String? = null,
)

@Composable
fun PlayerDetailDialog(target: PlayerDetailTarget?, onClose: () -> Unit) {
    if (target == null) return
    val uid = target.uid

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
                    content != null -> PlayerDetailBody(content, semantic, fallbackAvatarUrl = target.avatarUrl)
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
                    // 🔴 复制后必须收起弹窗：此前只写剪贴板不调 onClose，用户点完「复制UID」
                    // 弹窗仍挡在页面上（真机反馈："点击复制UID后对话框不消失"）。
                    // toast 由全局 LocalToast 承载，弹窗收起后依旧可见，不影响反馈。
                    onClose()
                }) { Text(stringResource(R.string.action_copy_uid)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun PlayerDetailBody(
    content: DetailUiState.Content,
    semantic: SemanticColors,
    fallbackAvatarUrl: String?,
) {
    val pageInfo = content.data.pageInfo
    when {
        // T10：接口无该玩家数据 → 说明式版式（区别于加载失败）
        pageInfo == null -> EmptyState(
            icon = Icons.Outlined.Search,
            title = stringResource(R.string.detail_not_found_title),
            message = stringResource(R.string.detail_not_found_message, content.uid),
        )
        // 屏蔽分支：仅保留昵称与 UID。此分支 pageInfo.avatarUrl 通常为空
        // （无权访问 ⇒ 拿不到头像），故必须带上入口列表头像兜底，否则只剩灰底占位。
        pageInfo.isShield == true -> Column {
            HeaderRow(
                uid = content.uid,
                nickname = pageInfo.nickname,
                avatarUrl = pageInfo.avatarUrl,
                fallbackAvatarUrl = fallbackAvatarUrl,
            )
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
                // 传 TierStars 而非 tierLabel 的结果：无段位时 tierLabel 会返回本地化的
                //「无段位」文案，按字符串判空判不掉，只能在上游用结构化数据表达「没有段位」
                tier = getTierStars(pageInfo.ladderScore ?: 0).takeIf { it.tier.isNotEmpty() },
                fallbackAvatarUrl = fallbackAvatarUrl,
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

/**
 * 详情头像取值：pageInfo 的 avatarUrl 优先，空白/缺失时回落到入口列表头像。
 * 两者都为空才返回 null（交给 Avatar 画灰底 Person 占位）——即"确实没有任何头像可用"。
 * 非 private：兜底优先级是本弹窗的对外契约，由 PlayerDetailAvatarTest 锁定。
 */
fun resolveAvatarUrl(avatarUrl: String?, fallback: String?): String? =
    avatarUrl?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }

@Composable
private fun HeaderRow(
    uid: String,
    nickname: String?,
    avatarUrl: String?,
    tier: TierStars? = null,
    fallbackAvatarUrl: String? = null,
) {
    // V37-3 任务 B（用户「对话框同上重构」）：昵称与段位合并成**单个 Text**，段位是行内一段
    // SpanStyle 染色（等价于 Android 的 SpannableString + ForegroundSpan）。
    // 旧结构 `Row { Text(昵称) + Spacer(8) + Text(段位) }` + widthIn(max=88dp) 是为"段位别被挤出可视区"
    // 打的补丁，但两个 Text 各自成块 ⇒ 真机 bounds 实测段位 LEFT=467、UID LEFT=297（差 170px），
    // 就是用户报的「段位和 ID 不对齐」。合并后行内只有一个布局节点：
    // 整行左缘 == UID 左缘，昵称↔段位的基线由文本排版自己保证，alignByBaseline() 全部移除。
    // 省略号现在按**整行**可用宽度截断（旧代码那套 88/148dp 上限是给两段 Text 分账用的，已无意义）：
    // 昵称过长时段位会被截进省略号里，这是单 Text 方案的已知取舍（弹窗文本列窄，实测未触发）。
    // 🔴 tierLabel / tierColor / stringResource 都是 @Composable，buildAnnotatedString 的 lambda
    // 不是组合上下文 ⇒ 全部在体内取成成品值再进 lambda。
    val nicknameText = nickname ?: stringResource(R.string.common_unknown)
    val tierText = tier?.let { tierLabel(it) }
    val tierSpan = tierText?.let { SpanStyle(color = tierColor(it)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(
            url = resolveAvatarUrl(avatarUrl, fallbackAvatarUrl),
            size = 64.dp,
            contentDescription = nickname,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = buildAnnotatedString {
                    append(nicknameText)
                    // tier == null（天梯分不足 1 分）时整段不渲染：不留占位、不留尾随空格。
                    // 不能用「tierLabel 结果为空串」判无段位——无段位会被本地化成「无段位」字样。
                    if (tierText != null && tierSpan != null) {
                        append(' ')
                        withStyle(tierSpan) { append(tierText) }
                    }
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            // 不加 "UID:" 前缀：与主页 ProfileCard 同一套设计语言——昵称在上、数字在下，
            // 位置本身即语义，前缀只会与昵称争宽度。左缘与上一行同轴（同一个 Column 的同一个 start）。
            Text(
                text = uid,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
    // V36 任务 H：补齐表头↔数值 4dp 间距，与 HomeRoute.ScoreItem（SCORE_LABEL_VALUE_GAP_DP）同口径
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    // V25（用户 2026-09-27 反馈）：原先三列挤一行，名称列只够四五个字，maxLines=1
    // 一省略就被砍到「只剩五六个字」，而赛事名普遍十几二十个字符。
    // 现拆成两行：名称独占整行宽度（可折到 3 行），结果与积分退居次行，
    // 名称终于能读全，同时主次信息层次更清楚。
    entries.forEachIndexed { index, entry ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 小字号顶对齐会比 bodyMedium 显得偏高，补 2dp 让首行视觉基线大致齐平
                    modifier = Modifier.width(20.dp).padding(top = 2.dp),
                )
                Text(
                    text = entry.competitionName ?: stringResource(R.string.common_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.competitionResult ?: stringResource(R.string.common_unknown),
                    style = MaterialTheme.typography.labelLarge,
                    color = resultColor(entry.competitionResult, semantic),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.detail_points, entry.score ?: 0),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
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

/**
 * 赛事结果 → 胜/负判定（V36 任务 I：从 resultColor 抽出的纯函数，可 JVM 单测）。
 * 原实现只认中文字符 + equals("win"/"lose")，英文赛事结果（Champion / Lost / 1st …）
 * 判不出色。子串匹配、大小写不敏感；判不出返回 null（不染色，不是猜色）。
 */
internal enum class ContestOutcome { Win, Lose }

internal fun contestOutcome(result: String?): ContestOutcome? {
    if (result == null) return null
    val r = result.lowercase()
    return when {
        listOf("胜", "冠", "第一", "win", "champion", "1st").any { r.contains(it) } -> ContestOutcome.Win
        listOf("负", "败", "最后", "lose", "lost", "last").any { r.contains(it) } -> ContestOutcome.Lose
        else -> null
    }
}

/** 胜负语义着色：判定走 [contestOutcome]（红绿成败约定，不参与动态取色） */
private fun resultColor(result: String?, semantic: SemanticColors): Color = when (contestOutcome(result)) {
    ContestOutcome.Win -> semantic.win
    ContestOutcome.Lose -> semantic.lose
    null -> Color.Unspecified
}
