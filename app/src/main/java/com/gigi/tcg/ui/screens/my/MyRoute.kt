// 「我的」页（V35 P0 骸架，设计文档 docs/superpowers/specs/2026-09-28-my-page-design.md §3.2）：
// 单条 LazyColumn 三个分区卡片（V36/2 重排）—— ①账户（含头像，列表/点选切换/添加/登出，
// 复用 LocalAccountActions 的现有多账号底座）②我的资产（我的卡组/卡背图鉴入口）
// ③最近对局（收藏对局/胜冠之试入口）。
// 原「个人信息」分区已删：UID 与昵称在①的账户行里已经展示，牌手等级并入同一行副标题，
// 再开一张卡片只是重复占位（用户第 7、14 项）。
// ②③ 的入口行带数值摘要（卡组数 / 已收集卡背 / 收藏条数 / 旬数），数据缺失时整段不显示；
// 内容本身在四个二级页（MyDecksPage 等，设计 §3.3）。
// 一级页只做导航枢纽，重内容一律进二级页（设计 §3.2「为什么用分区列表而不是嵌套 Tab」）。

package com.gigi.tcg.ui.screens.my

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.data.auth.StoredAccount
import com.gigi.tcg.i18n.displayShortName
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.login.LocalAccountActions

@Composable
fun MyRoute(
    onOpenDeck: () -> Unit,
    onOpenCardBack: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenChallenge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: MyViewModel = viewModel(key = "my", factory = MyViewModel.factory(app))
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val activeUid by viewModel.activeUid.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val deckList by viewModel.deckList.collectAsStateWithLifecycle()
    val cardBackList by viewModel.cardBackList.collectAsStateWithLifecycle()
    val matchList by viewModel.matchList.collectAsStateWithLifecycle()
    val challengeSchedule by viewModel.challengeSchedule.collectAsStateWithLifecycle()
    val actions = LocalAccountActions.current
    var logoutConfirmOpen by remember { mutableStateOf(false) }

    // 进入页面 / 切换账户后拉个人信息 + ②③ 摘要：5 组**串行错峰**（VM 内部延迟），
    // 不再一次性并发 5 个私有接口（正中米游社 -500004 保流窗口；口径同首页首刷 runStaggeredFirstLoad）。
    // 🔴 这里不调 `refresh()` 式的全清重载：装载本身是幂等的 —— VM 只在**账户变化**时清空，
    // 页面重入（离开 Composition 后 LaunchedEffect 重启）静默替换缓存值，数字不再闪（用户第 12 项）。
    LaunchedEffect(activeUid) { viewModel.loadAllStaggered() }

    // 摘要口径：数据没到 / 列表为空 ⇒ 整段不显示（显示 0 会把「还没拉到」误报成「真的没有」）
    val deckSummary = deckList?.deckList?.size?.takeIf { it > 0 }
        ?.let { stringResource(R.string.my_summary_decks, it) }
    val cardBacks = cardBackList?.cardBackList.orEmpty()
    val cardBackSummary = cardBacks.takeIf { it.isNotEmpty() }?.let { backs ->
        stringResource(R.string.my_cardback_count, backs.count { it.hasObtained == true }, backs.size)
    }
    // favouriteMatches 实测恒为 []（未收藏任何一局）⇒ 条数 0 属正常，同样不显示
    val matchSummary = matchList?.favouriteMatches?.size?.takeIf { it > 0 }
        ?.let { stringResource(R.string.my_summary_matches, it) }
    val scheduleSummary = challengeSchedule?.scheduleList?.size?.takeIf { it > 0 }
        ?.let { stringResource(R.string.my_summary_schedules, it) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "accounts") {
            SectionCard(title = stringResource(R.string.my_section_accounts)) {
                accounts.forEach { account ->
                    AccountRow(
                        account = account,
                        active = account.uid == activeUid,
                        // 牌手等级只有当前会话的接口数据（profile），非激活账户行不拼这段
                        level = if (account.uid == activeUid) profile?.level else null,
                        onSelect = { actions.switchAccount(account.uid) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                AddAccountRow(onClick = actions.addAccount)
                LogoutRow(onClick = { logoutConfirmOpen = true })
            }
        }
        item(key = "assets") {
            SectionCard(title = stringResource(R.string.my_section_assets)) {
                EntryRow(
                    label = stringResource(R.string.my_deck_entry),
                    trailing = deckSummary,
                    onClick = onOpenDeck,
                )
                EntryRow(
                    label = stringResource(R.string.my_cardback_entry),
                    trailing = cardBackSummary,
                    onClick = onOpenCardBack,
                )
            }
        }
        item(key = "records") {
            SectionCard(title = stringResource(R.string.my_section_records)) {
                EntryRow(
                    label = stringResource(R.string.my_favorites_entry),
                    trailing = matchSummary,
                    onClick = onOpenFavorites,
                )
                EntryRow(
                    label = stringResource(R.string.my_challenge_entry),
                    trailing = scheduleSummary,
                    onClick = onOpenChallenge,
                )
            }
        }
    }

    // 登出确认（与既有顶栏菜单同款对话框，触发点随账号管理迁入本页）
    if (logoutConfirmOpen) {
        AlertDialog(
            onDismissRequest = { logoutConfirmOpen = false },
            title = { Text(stringResource(R.string.account_logout_confirm_title)) },
            text = { Text(stringResource(R.string.account_logout_message)) },
            dismissButton = {
                TextButton(onClick = { logoutConfirmOpen = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        logoutConfirmOpen = false
                        actions.logout()
                    },
                ) { Text(stringResource(R.string.action_logout)) }
            },
        )
    }
}

/**
 * 账户行头像直径：**48dp**（用户 2026-09-29 拍板，V37-G）。
 * 口径 = M3 `ListItem` 的 **LeadingAvatar** 标准尺寸（列表行首头像），账户行正是列表行；
 * 原 56dp 比行首头像规范大一档，用户报「头像略大」。
 * ⚠️ 首页/统计页资料卡的头像（`PlayerInfoHeader.PLAYER_INFO_AVATAR_DP` = 64dp）是**另一处材料**，
 * 用户只说「我的页面」⇒ 本棒不动它。
 */
internal val MyAccountAvatarSize = 48.dp

/** 分区卡片：M3 filled Card，标题走 primary 色 titleSmall（区块小标题的工程惯例） */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = MyRowHorizontalPadding, vertical = 8.dp),
            )
            content()
        }
    }
}

/**
 * 账号行：头像 + 昵称 + 「UID · 服 · 牌手等级」，当前账户行尾 Check 并禁点（点自己无操作）。
 * V36/2，尺寸与回填口径 V37-G 更新：
 * - 头像走 [StoredAccount.avatar]，直径 [MyAccountAvatarSize]（48dp = M3 ListItem LeadingAvatar）。
 *   该字段由「我的」页装载链对 my_home_page 的回填补齐（V36/2b 引入，V37-G 起**逐账户覆盖全部账户**，
 *   见 MyViewModel.backfillAllAvatars），登录接口的 avatar_url 只是顺带兜住。
 * - 🔴 缺字段/空白（存量未补齐、接口无头像、该账户请求失败）时**保留占位回落**：
 *   [Avatar] 自己画圆形 Person 占位，不留空、不隐藏头像位（行高因此不随回填进度跳动）。
 * - 牌手等级**并进这一行副标题**，不再单独开一张「个人信息」卡片 —— 用户原话：
 *   「上面账户里已经展示了 UID 和昵称，下方再展示只是浪费空间」「说明文本应合并到已有文本」。
 * - 等级只属于激活账户（profile 接口按当前会话取），非激活行不拼这一段。
 */
@Composable
private fun AccountRow(
    account: StoredAccount,
    active: Boolean,
    level: Int?,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !active, onClick = onSelect)
            .padding(horizontal = MyRowHorizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            url = account.avatar,
            size = MyAccountAvatarSize,
            contentDescription = stringResource(R.string.cd_avatar),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                account.displayName(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 分隔符沿用「我的」域既有口径（对局/旬区间也用 " · " 串），不为它单开文案
            val subtitle = listOfNotNull(
                stringResource(R.string.my_account_subtitle, account.uid, account.server().displayShortName()),
                level?.let { stringResource(R.string.my_profile_level) + " $it" },
            ).joinToString(" · ")
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (active) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(R.string.my_account_current),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 添加账号行（复用账户系统既有动作 account_add） */
@Composable
private fun AddAccountRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MyRowHorizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.account_add),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/** 退出当前账户行（error 色，触发确认对话框；真正删除动作仍在 AppGate.logout） */
@Composable
private fun LogoutRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MyRowHorizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Logout,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(R.string.account_logout_item),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * 二级页入口行：文案 + 弱化摘要（可空）+ 尾部 chevron。
 * 摘要为 null（数据未就绪 / 列表为空）时整段不渲染，行退化成纯文案 —— 一级页不因缺数据出现「0 组」。
 * internal：四个二级页的行组件同源，放开给它们复用（此前 private 导致各页手搓一份）。
 */
@Composable
internal fun EntryRow(label: String, onClick: () -> Unit, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MyRowHorizontalPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
