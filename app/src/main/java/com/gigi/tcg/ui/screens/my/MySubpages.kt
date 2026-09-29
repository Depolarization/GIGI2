// 「我的」页四个二级页共用的小件（设计 §3.3）：页面内容容器、Lazy 复合 key、
// GcgTime 的展示格式化、牌组可读名、行内小节标题。页面本体各自一文件：
// MyDecksPage / MyCardBacksPage / MyFavoritesPage / MyChallengePage。
//
// 🔴 二级页的**标题与返回在主壳顶栏**（GigiNavHost 按完整 route 出二级标题 + ArrowBack）：
// 页面自己再画一行标题就成了双标题（V36/2 用户拍板，决策 1）。故 [MySubpageScaffold] 现在
// 只是纯内容容器，不再接 title / onBack。页内的「列表 → 详情」（卡组详情）仍在同一目的地内
// 用状态切换，详情态的返回按钮随内容画（不是第二条标题栏）。
//
// ℹ️ 订正此前那条与代码矛盾的注释（它称"不新增 my/xxx 二级路由以避免多开 ViewModel 实例"）：
// 四个二级路由实际早已存在（GigiNavHost ROUTE_MY_DECK / CARDBACK / FAVORITES / CHALLENGE）。
// 每条目的地各自 `viewModel()` 的 owner 是它自己的 NavBackStackEntry ⇒ 确实是**各自一份
// MyViewModel**（`viewModel(key = "my")` 换 owner 也共享不了，跨 entry 共享要显式传 store owner）。
// V36/2 权衡后**保持多实例**：重活（5min 私有缓存）在共享的 GigiRepository 里，多实例只多几个
// StateFlow；而把 owner 抬到 Activity 会让 VM 跨服务器切换存活、反而制造串数据风险。
// 用户报的"摘要数字闪动"根因是先清空（见 MyViewModel 头注），已在装载语义上修掉。

package com.gigi.tcg.ui.screens.my

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgDeck
import com.gigi.tcg.data.model.GcgTime
import java.util.Locale

/**
 * 「我的」域行组件的统一水平内距：此前 AccountRow/EntryRow/MySectionTitle 混用 12dp 与 16dp，
 * 同一页里左右留白不齐（V36/2 审计 N 的内距收敛项）。
 */
internal val MyRowHorizontalPadding = 16.dp

/**
 * LazyColumn / LazyVerticalGrid 的复合唯一 key：把 id 与下标拼在一起，**同时兜住 null 和重复**。
 *
 * 🔴 实测事实（真机，2026-09-29）：`recent_matches` 的 3 条记录 `game_id` **全是字符串 "10"**——
 * 非 null 但重复。原先写 `item.gameId ?: "noid-$index"` 只兜 null、不兜重复，
 * 于是同一个 key 出现两次 ⇒ `IllegalArgumentException: Key "10" was already used` ⇒
 * **点「收藏对局」直接闪退**。四个二级页的 id 字段（game_id / deck id / cardback id / schedule id）
 * 都可能是这种"看着像主键其实不唯一"的服务端值，故一律走本函数，不要再裸传 id。
 */
internal fun stableItemKey(id: Any?, index: Int) = "${id ?: "noid"}-$index"

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
 * 二级页的纯内容容器：只负责"占满 + 纵向排"，标题与返回交主壳顶栏（见文件头注）。
 * content 是 ColumnScope ⇒ 各页用 `Modifier.weight(1f)` 分配剩余高度（列表滚动区、居中空态）。
 */
@Composable
fun MySubpageScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) { content() }
}

/** 页内小节标题（卡组详情的「角色牌 / 行动牌」等），沿用一级页区块小标题的 primary 弱化口径 */
@Composable
fun MySectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = MyRowHorizontalPadding, vertical = 8.dp),
    )
}
