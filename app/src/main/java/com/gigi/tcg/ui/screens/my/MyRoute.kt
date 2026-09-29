// 「我的」页（V35 P0 骸架，设计文档 docs/superpowers/specs/2026-09-28-my-page-design.md §3.2）：
// 单条 LazyColumn 四个分区卡片 —— ①账号管理（列表/点选切换/添加/登出，复用 LocalAccountActions
// 的现有多账号底座）②个人信息（昵称/游戏 UID/牌手等级，接口失败降级为账户本地数据）
// ③卡牌资产（我的卡组/卡背图鉴入口）④对局记录（收藏对局/胜冠之试入口）。
// ③④ 的入口行带数值摘要（卡组数 / 已收集卡背 / 收藏条数 / 旬数），数据缺失时整段不显示；
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
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
import com.gigi.tcg.data.model.GcgBasicInfoData
import com.gigi.tcg.i18n.displayShortName
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
    val activeAccount = accounts.firstOrNull { it.uid == activeUid }

    // 进入页面 / 切换账户后拉个人信息 + 分区③④ 摘要：5 组**串行错峰**（VM 内部延迟），
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
                        onSelect = { actions.switchAccount(account.uid) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                AddAccountRow(onClick = actions.addAccount)
                LogoutRow(onClick = { logoutConfirmOpen = true })
            }
        }
        item(key = "profile") {
            SectionCard(title = stringResource(R.string.my_section_profile)) {
                ProfileSection(activeAccount = activeAccount, profile = profile)
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

/** 分区卡片：M3 filled Card，标题走 primary 色 titleSmall（区块小标题的工程惯例） */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            content()
        }
    }
}

/**
 * 账号行：头像占位 + 昵称 + 「UID · 服」，当前账户行尾 Check 并禁点（点自己无操作）。
 * P2 可换真实头像（接口已有 myHomePage 头像链路，P0 不引入网络依赖）。
 */
@Composable
private fun AccountRow(account: StoredAccount, active: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !active, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.AccountCircle,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
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
            Text(
                stringResource(R.string.my_account_subtitle, account.uid, account.server().displayShortName()),
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
 * 个人信息区：昵称/游戏 UID 来自激活账户（本地必有），牌手等级来自 gcg/basicInfo
 *（异步；未就绪或失败则不显示该行——不写"加载中"占位，避免页面闪烁）。
 */
@Composable
private fun ProfileSection(activeAccount: StoredAccount?, profile: GcgBasicInfoData?) {
    if (activeAccount == null) return
    InfoRow(
        label = stringResource(R.string.my_profile_nickname),
        value = profile?.nickname?.takeIf { it.isNotBlank() } ?: activeAccount.displayName(),
    )
    InfoRow(
        label = stringResource(R.string.my_profile_game_uid),
        value = activeAccount.uid,
    )
    profile?.level?.let { level ->
        InfoRow(
            label = stringResource(R.string.my_profile_level),
            value = level.toString(),
        )
    }
}

/** 标签-值行（标签弱化、值正常），个人信息与后续摘要共用 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.6f),
        )
    }
}

/**
 * 二级页入口行：文案 + 弱化摘要（可空）+ 尾部 chevron。
 * 摘要为 null（数据未就绪 / 列表为空）时整段不渲染，行退化成纯文案 —— 一级页不因缺数据出现「0 组」。
 */
@Composable
private fun EntryRow(label: String, onClick: () -> Unit, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
