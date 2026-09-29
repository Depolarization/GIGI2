// 我的卡组（设计 §3.3）：牌组列表 → 页内切详情（角色牌 / 行动牌两组卡面）。
// 数据：gcg/deckList（实测官服 11 副 · 渠道服 0 副；avatar_cards 恒 3 张、action_cards 22–25 张）。
// 详情不做二级路由：`selectedDeck` 页内状态，返回入口在页内标题行（理由见 MySubpages.kt 头注）。

package com.gigi.tcg.ui.screens.my

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgDeck
import com.gigi.tcg.data.model.GcgDeckCard
import com.gigi.tcg.ui.components.AppImage
import com.gigi.tcg.ui.components.EmptyState

/** 卡面宽高比：接口图片 URL 自带 `resize,m_fixed,h_275,w_160`，按原比例摆框，避免 Crop 切掉卡名 */
internal const val CARD_FACE_ASPECT_RATIO: Float = 160f / 275f

internal val DeckCardShape = RoundedCornerShape(8.dp)

/** 详情卡位宽度：360dp 屏 - 左右 16dp 留白 - 2 条 8dp 间距 ≈ 一行 3 张 */
private val DECK_CARD_TILE_WIDTH = 104.dp

@Composable
fun MyDecksPage(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: MyViewModel = viewModel(factory = MyViewModel.factory(app))
    val activeUid by viewModel.activeUid.collectAsStateWithLifecycle()
    val deckListData by viewModel.deckList.collectAsStateWithLifecycle()
    var selectedDeck by remember { mutableStateOf<GcgDeck?>(null) }

    LaunchedEffect(activeUid) {
        // 切账户先掉回列表态：详情正开着时切账户，屏幕不该继续展示上一账户那副牌组
        selectedDeck = null
        viewModel.loadDeckList()
    }

    val deck = selectedDeck
    if (deck != null) {
        DeckDetail(deck = deck, onBack = { selectedDeck = null }, modifier = modifier)
        return
    }

    val decks = deckListData?.deckList.orEmpty()
    MySubpageScaffold(title = stringResource(R.string.my_deck_entry), modifier = modifier) {
        if (decks.isEmpty()) {
            // 拉取失败 / 渠道服 0 副 / 尚未回包 —— 统一空态，不给「我的」页制造错误态
            EmptyState(
                modifier = Modifier.padding(top = 32.dp),
                title = stringResource(R.string.my_empty_decks),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 牌组 id 实测 1..11 不连续、可缺失也可重复 ⇒ 复合下标（见 stableItemKey）
                itemsIndexed(decks, key = { index, item -> stableItemKey(item.id, index) }) { _, item ->
                    DeckRow(deck = item, onClick = { selectedDeck = item })
                }
            }
        }
    }
}

@Composable
private fun DeckRow(deck: GcgDeck, onClick: () -> Unit) {
    val avatars = deck.avatarCards.orEmpty()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                deckDisplayName(deck),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.my_deck_card_summary,
                    avatars.size,
                    deck.actionCards.orEmpty().size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                avatars.forEach { card ->
                    Box(
                        Modifier
                            .width(52.dp)
                            .aspectRatio(CARD_FACE_ASPECT_RATIO)
                            .clip(DeckCardShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        AppImage(
                            model = card.image?.takeIf { it.isNotBlank() },
                            contentDescription = card.name,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/** 牌组详情：两组卡面。行动牌 22–25 张，整页 verticalScroll + 固定宽度流式排布（量小，不上 LazyGrid） */
@Composable
private fun DeckDetail(deck: GcgDeck, onBack: () -> Unit, modifier: Modifier = Modifier) {
    MySubpageScaffold(title = deckDisplayName(deck), onBack = onBack, modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            CardGroup(title = stringResource(R.string.my_deck_card_group_avatar), cards = deck.avatarCards.orEmpty())
            CardGroup(title = stringResource(R.string.my_deck_card_group_action), cards = deck.actionCards.orEmpty())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardGroup(title: String, cards: List<GcgDeckCard>) {
    MySectionTitle(title)
    if (cards.isEmpty()) {
        Text(
            stringResource(R.string.state_empty_response),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        return
    }
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 卡位固定宽度：窄屏一行 3 张，宽屏自动多排，不随卡数拉伸
        cards.forEach { card ->
            Box(Modifier.width(DECK_CARD_TILE_WIDTH)) { DeckCardTile(card) }
        }
    }
}

@Composable
private fun DeckCardTile(card: GcgDeckCard) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_FACE_ASPECT_RATIO)
                .clip(DeckCardShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            // image 可能缺失：传 null 让 AppImage 走自己的占位/兜底，空串会被 coil 当合法地址去请求
            AppImage(
                model = card.image?.takeIf { it.isNotBlank() },
                contentDescription = card.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            card.name ?: stringResource(R.string.state_empty_response),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            card.num?.toString().orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
