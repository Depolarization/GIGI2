// 「我的」页四个二级页共用的小件（设计 §3.3）：页面外框（标题 + 可选「返回」）、
// 以及 GcgTime 的展示格式化。页面本体各自一文件：
// MyDecksPage / MyCardBacksPage / MyFavoritesPage / MyChallengePage。
//
// 🔴 二级页的「列表 → 详情」一律走页内状态切换（remember 一个 selectedX，详情态把 onBack 传进外框），
// 不新增导航路由：`my/xxx` 二级路由会让 MyViewModel 在子 BackStackEntry 上多开一份实例，
// 切账号时的清值/重载链路要翻倍，P2 先用页内切换规避（系统返回键仍由 NavHost 逐级回退到一级页）。

package com.gigi.tcg.ui.screens.my

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgDeck
import com.gigi.tcg.data.model.GcgTime
import java.util.Locale

/** 接口给的是拆开的年月日时分，展示前自己拼；缺字段返回 null，由调用方整段不显示（不显示 0000-00-00） */
internal fun formatGcgDate(time: GcgTime?): String? {
    val year = time?.year ?: return null
    val month = time.month ?: return null
    val day = time.day ?: return null
    return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
}

/** 同上，补 `HH:mm`；时刻字段缺失时退回只到日期（对局时间缺分秒仍可读，不至于整行空白） */
internal fun formatGcgDateTime(time: GcgTime?): String? {
    val date = formatGcgDate(time) ?: return null
    val hour = time?.hour ?: return date
    val minute = time.minute ?: return date
    return String.format(Locale.US, "%s %02d:%02d", date, hour, minute)
}

/**
 * 牌组的可读名。三级回退，**必须用 isNotBlank 而不是判 null**：
 * 🔴 实测 challenge/record 内嵌 deck 的 `name` 恒为空串（玩家未改名），
 * 而 deckList 的 `name` 可能是"我的牌组"——空串不是 null，`?:` 兜底会静默失效画出空标题。
 * 回退顺序：接口名 → 三张角色牌名（牌组最可辨识的标识）→ 通用条目名。
 */
@Composable
internal fun deckDisplayName(deck: GcgDeck?): String {
    val named = deck?.name?.takeIf { it.isNotBlank() }
    if (named != null) return named
    val avatars = deck?.avatarCards.orEmpty().mapNotNull { it.name?.takeIf(String::isNotBlank) }
    if (avatars.isNotEmpty()) return avatars.joinToString(" / ")
    return stringResource(R.string.my_deck_entry)
}

/**
 * 二级页外框：页内标题行 + 内容。
 * 顶部标题不挂宿主 `TopAppBar`（那一层在 GigiNavHost 里，四个二级页共用，只能显示一级「我的」），
 * 且详情态的标题是动态的（牌组名 / 旬名），只能在页内画。
 *
 * 两态都有「返回」入口，语义不同：
 * @param onBack 详情态传入 —— 回到本页列表态（页内状态切换，不动导航栈）。
 *   列表态 `onBack == null`，标题左侧同样画返回，动作交 NavHost 弹掉本页
 *   （用 back 派发器，页内不持有 navController，四个页面签名保持 `MyXxxPage(modifier)` 不变）；
 *   派发器取不到时不画该控件（避免出现点了没反应的按钮）。
 */
@Composable
fun MySubpageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backOwner = LocalOnBackPressedDispatcherOwner.current
    val onExit: (() -> Unit)? = onBack ?: backOwner?.onBackPressedDispatcher
        ?.let { dispatcher -> { dispatcher.onBackPressed() } }
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onExit != null) {
                TextButton(onClick = onExit) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.action_back),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.padding(start = if (onExit == null) 8.dp else 0.dp),
            )
        }
        content()
    }
}

/** 页内小节标题（卡组详情的「角色牌 / 行动牌」等），沿用一级页区块小标题的 primary 弱化口径 */
@Composable
fun MySectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
