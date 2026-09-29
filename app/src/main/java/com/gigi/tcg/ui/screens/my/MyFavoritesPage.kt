// 收藏对局（设计 §3.3）：gcg/matchList 的 favourite_matches 列表。
// 🔴 实测该字段在未收藏任何一局时**恒为 []**（两份样本都是），空态是正常状态、不是错误。
// 对局对象没有卡组名/卡组 id，只有 3 张角色牌头像 URL（键名是接口拼错的 `linups`，勿改）。
// 胜负色走 LocalSemanticColors（设计红线 8：胜/负语义色固定，不参与动态取色、不新造 hex）。

package com.gigi.tcg.ui.screens.my

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgMatch
import com.gigi.tcg.ui.components.Avatar
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.theme.LocalSemanticColors

@Composable
fun MyFavoritesPage(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: MyViewModel = viewModel(factory = MyViewModel.factory(app))
    val activeUid by viewModel.activeUid.collectAsStateWithLifecycle()
    val matchData by viewModel.matchList.collectAsStateWithLifecycle()

    // 装载幂等：同账户重入不清空（VM 只在 uid 变化时清空），命中内存缓存后静默替换 ⇒ 列表不闪。
    LaunchedEffect(activeUid) { viewModel.loadMatchList() }

    val matches = matchData?.favouriteMatches.orEmpty()
    MySubpageScaffold(modifier = modifier) {
        if (matches.isEmpty()) {
            // 空态居中：EmptyState 不接管剩余空间（且把 fillMaxWidth 写在传入 modifier 之后），
            // 在调用点用 weight(1f) + Box 居中包住，不去改公共组件。
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(title = stringResource(R.string.my_empty_favorites))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 🔴 game_id 实测重复（3 条全是 "10"）：裸 id 当 key 会「Key was already used」闪退，
                // 必须复合下标（见 stableItemKey）
                itemsIndexed(matches, key = { index, item -> stableItemKey(item.gameId, index) }) { _, item ->
                    MatchRow(match = item)
                }
            }
        }
    }
}

@Composable
private fun MatchRow(match: GcgMatch) {
    val semantic = LocalSemanticColors.current
    val isWin = match.isWin == true
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isWin) semantic.win else semantic.lose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(if (isWin) R.string.home_result_win else R.string.home_result_lose),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
                Text(
                    // name 可能是空串（不是 null，`?:` 不生效），空串一并兜底避免画出空标题
                    match.opposite?.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.state_empty_response),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
            }
            Text(
                listOfNotNull(
                    match.matchType?.takeIf { it.isNotBlank() },
                    formatGcgDateTime(match.matchTime),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            // 两行头像：上行己方、下行对手（同一行内只有 3 张角色牌头像，接口不给卡组名）
            LineupRow(avatars = match.self?.linups)
            LineupRow(avatars = match.opposite?.linups)
        }
    }
}

@Composable
private fun LineupRow(avatars: List<String>?) {
    val urls = avatars.orEmpty()
    if (urls.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        urls.forEach { url ->
            Avatar(url = url, size = 32.dp)
        }
    }
}
