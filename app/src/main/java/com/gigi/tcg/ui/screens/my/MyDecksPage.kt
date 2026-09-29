// 我的卡组（设计 §3.3）：牌组列表 → 页内切详情（角色牌 / 行动牌两组卡面 + 复制分享码）。
// 数据：gcg/deckList（实测官服 11 副 · 渠道服 0 副；avatar_cards 恒 3 张、action_cards 22–25 张）。
// 标题与返回入口在主壳顶栏（GigiNavHost），详情态是同一目的地内的状态切换，
// 返回按钮随内容画（见 DeckDetail 的头行），不再是第二条标题栏。

package com.gigi.tcg.ui.screens.my

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.R
import com.gigi.tcg.data.model.GcgDeck
import com.gigi.tcg.data.model.GcgDeckCard
import com.gigi.tcg.ui.components.AppImage
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.LocalToast

/** 卡面宽高比：接口图片 URL 自带 `resize,m_fixed,h_275,w_160`，按原比例摆框，避免 Crop 切掉卡名 */
internal const val CARD_FACE_ASPECT_RATIO: Float = 160f / 275f

internal val DeckCardShape = RoundedCornerShape(8.dp)

/** 详情卡位列数与间距：三等分（weight(1f)），不按屏宽推算固定 tile 宽 */
private const val DECK_GRID_COLUMNS = 3
private val DECK_GRID_GAP = 8.dp

@Composable
fun MyDecksPage(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: MyViewModel = viewModel(factory = MyViewModel.factory(app))
    val activeUid by viewModel.activeUid.collectAsStateWithLifecycle()
    val deckListData by viewModel.deckList.collectAsStateWithLifecycle()
    var selectedDeck by remember { mutableStateOf<GcgDeck?>(null) }

    // 🔴 lastUid 守卫，写法照首页 HomeRoute（LaunchedEffect 的 key 相同只防"同一组合内 key 变化"，
    // 防不了"离开 Composition 后重入"）：重入不该打掉详情态，也不该清空数字（VM 同 uid 不清空、静默替换）；
    // 只有真正换账户才掉回列表态 —— 详情正开着时切账户，屏幕不该继续展示上一账户那副牌组。
    var lastUid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeUid) {
        val uid = activeUid
        if (uid != null && uid != lastUid) selectedDeck = null
        if (uid != null) lastUid = uid
        viewModel.loadDeckList()
    }

    val deck = selectedDeck
    if (deck != null) {
        DeckDetail(deck = deck, onBack = { selectedDeck = null }, modifier = modifier)
        return
    }

    val decks = deckListData?.deckList.orEmpty()
    MySubpageScaffold(modifier = modifier) {
        if (decks.isEmpty()) {
            // 拉取失败 / 渠道服 0 副 / 尚未回包 —— 统一空态，不给「我的」页制造错误态。
            // 居中：EmptyState 不接管剩余空间，调用点 weight(1f) + Box 居中（不去改公共组件）。
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(title = stringResource(R.string.my_empty_decks))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
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

/**
 * 牌组详情：动态标题（牌组名）+ 返回随内容画，两组卡面三等分，底部「复制分享码」。
 * 行动牌 22–25 张，整页 verticalScroll（量小，不上 LazyGrid）。
 */
@Composable
private fun DeckDetail(deck: GcgDeck, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val toast = LocalToast.current
    // 剪贴板范式照 PlayerDetailDialog（LocalClipboardManager 已废弃但仍是当前工程口径）
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val shareCode = deck.shareCode
    // 🔴 判空白而不是判 null：share_code 实测可能是空串，`?:` 兜不住 ⇒ 按钮要能识别"没有可复制的东西"
    val canCopyShareCode = !shareCode.isNullOrBlank()
    // stringResource 不能在 onClick（普通 lambda）里调，先在组合期算好（同 PlayerDetailDialog）
    val copiedToast = stringResource(R.string.toast_copied_share_code, shareCode.orEmpty())

    MySubpageScaffold(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = MyRowHorizontalPadding, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Text(
                    deckDisplayName(deck),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedButton(
                onClick = {
                    val code = shareCode
                    val message = copiedToast
                    if (!code.isNullOrBlank() && message != null) {
                        @Suppress("DEPRECATION") clipboard.setText(AnnotatedString(code))
                        toast(message)
                    }
                },
                enabled = canCopyShareCode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MyRowHorizontalPadding, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.my_copy_share_code))
            }
            CardGroup(title = stringResource(R.string.my_deck_card_group_avatar), cards = deck.avatarCards.orEmpty())
            CardGroup(title = stringResource(R.string.my_deck_card_group_action), cards = deck.actionCards.orEmpty())
        }
    }
}

/**
 * 一组卡面：**真三等分**（Row + weight(1f)）。
 * 🔴 此前是 FlowRow + 固定 104.dp tile 宽 —— 那是按 360dp 屏推算出来的常量，
 * 换到实际屏宽就凑不满一行、末尾留下不等宽的空隙（用户第 9 项"不是 3 等分"）。
 * 末行不足 3 张时补空 Spacer 占位，否则 weight 会把剩下的两张拉宽、列不对齐。
 */
@Composable
private fun CardGroup(title: String, cards: List<GcgDeckCard>) {
    MySectionTitle(title)
    if (cards.isEmpty()) {
        Text(
            stringResource(R.string.state_empty_response),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MyRowHorizontalPadding, vertical = 4.dp),
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = MyRowHorizontalPadding)) {
        cards.chunked(DECK_GRID_COLUMNS).forEach { rowCards ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DECK_GRID_GAP),
            ) {
                rowCards.forEach { card ->
                    Box(Modifier.weight(1f)) { DeckCardTile(card) }
                }
                repeat(DECK_GRID_COLUMNS - rowCards.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(DECK_GRID_GAP))
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
