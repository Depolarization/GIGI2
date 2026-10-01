// 我的卡组（设计 §3.3）：牌组列表页 + 牌组详情页（角色牌 / 行动牌两组卡面 + 复制分享码 + 导出渲染图）。
// 数据：gcg/deckList（实测官服 11 副 · 渠道服 0 副；avatar_cards 恒 3 张、action_cards 22–25 张）。
// 🔴 V37-F：详情**升为独立路由** `my/deck/{deck_index}?deck_name=…`（不再是页内状态）。
// 根因：页内状态时主壳顶栏只知道当前在 my/deck，出不了牌组名，页内才补画了一行「返回 + 标题」
// ⇒ 与主壳顶栏叠成双标题栏。升路由后标题（牌组名，取导航参数）与返回都在主壳，返回栈也自然逐级：
// 详情 → 卡组列表 → 我的页。页内只剩两个 trailing icon 动作（复制 / 导出），没有任何标题行。
// 详情各自一份 MyViewModel：重活（5min 私有内存缓存）在共享的 GigiRepository 里，
// 口径与其余二级页一致（见 MySubpages.kt 头注的既有取舍）。

package com.gigi.tcg.ui.screens.my

import android.app.Application
import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
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
import com.gigi.tcg.ui.components.LoadingView
import com.gigi.tcg.ui.dialogs.cardcover.exportDateText
import com.gigi.tcg.ui.export.buildDeckImageSpec
import com.gigi.tcg.ui.export.deckExportFeedback
import com.gigi.tcg.ui.export.exportDeckImage
import kotlinx.coroutines.launch

/** 卡面宽高比：接口图片 URL 自带 `resize,m_fixed,h_275,w_160`，按原比例摆框，避免 Crop 切掉卡名 */
internal const val CARD_FACE_ASPECT_RATIO: Float = 160f / 275f

internal val DeckCardShape = RoundedCornerShape(8.dp)

/** 详情卡位列数与间距：三等分（weight(1f)），不按屏宽推算固定 tile 宽 */
private const val DECK_GRID_COLUMNS = 3
private val DECK_GRID_GAP = 8.dp

/**
 * 牌组列表页。点击某副牌 ⇒ 交宿主导航到详情路由（带上标号与该副牌的名字，
 * 名字进导航参数只为让主壳顶栏立刻出动态标题，不参与数据查找）。
 */
@Composable
fun MyDecksPage(modifier: Modifier = Modifier, onOpenDeckDetail: (index: Int, deckName: String) -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: MyViewModel = viewModel(factory = MyViewModel.factory(app))
    val activeUid by viewModel.activeUid.collectAsStateWithLifecycle()
    val deckListData by viewModel.deckList.collectAsStateWithLifecycle()

    // 装载幂等：同账户重入不清空（VM 只在 uid 变化时清空），命中内存缓存后静默替换 ⇒ 列表不闪。
    // 换账户的返回栈归属由主壳 `key(server to sessionUid)` 重建负责（详情是路由 ⇒ 自然一起弹出）。
    LaunchedEffect(activeUid) { viewModel.loadDeckList() }

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
                itemsIndexed(decks, key = { index, item -> stableItemKey(item.id, index) }) { index, item ->
                    val title = deckDisplayName(item)
                    DeckRow(deck = item, title = title, onClick = { onOpenDeckDetail(index, title) })
                }
            }
        }
    }
}

/**
 * 牌组详情路由的内容。参数只有标号 ⇒ 数据自己按内存缓存取（与其余二级页同一口径）；
 * 名字从导航参数进顶栏，这里不重复画。
 */
@Composable
fun MyDeckDetailPage(
    deckIndex: Int,
    onShowExportResult: (message: String, uris: List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: MyViewModel = viewModel(factory = MyViewModel.factory(app))
    val activeUid by viewModel.activeUid.collectAsStateWithLifecycle()
    val deckListData by viewModel.deckList.collectAsStateWithLifecycle()
    LaunchedEffect(activeUid) { viewModel.loadDeckList() }

    // 下标而非 id：实测牌组 id 可重复（见 stableItemKey 的服务端事实），下标才唯一对应「点进去的那一副」。
    val deck = deckListData?.deckList.orEmpty().getOrNull(deckIndex)
    MySubpageScaffold(modifier = modifier) {
        when {
            deck != null -> DeckDetail(
                deck = deck,
                nickname = deckListData?.nickname,
                uid = activeUid,
                onShowExportResult = onShowExportResult,
            )
            // 尚未回包（内存缓存命中时这一帧基本看不见）；回包后仍取不到该下标 ⇒ 空态，不画半截详情
            deckListData == null -> Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { LoadingView() }
            else -> Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(title = stringResource(R.string.my_empty_decks))
            }
        }
    }
}

/**
 * 一副牌组的行动牌**携带总张数**（列表行用），不是种类数。
 * `num` 缺失 / ≤0 记 1 ⇒ 与导出图 `ui/export/DeckImageExport.expandActionCardsByCount` 同一口径，
 * 两处「一共几张」必须对得上（实测 22 种行动牌、num 合计 30 时可正常开局，列表要显示 30）。
 */
internal fun deckActionCardTotal(cards: List<GcgDeckCard>?): Int =
    cards.orEmpty().sumOf { (it.num ?: 1).coerceAtLeast(1) }

@Composable
private fun DeckRow(deck: GcgDeck, title: String, onClick: () -> Unit) {
    val avatars = deck.avatarCards.orEmpty()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.my_deck_card_summary,
                    avatars.size,
                    deckActionCardTotal(deck.actionCards),
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
                // 卡面宽度预算（三张 + 两道 6dp 间隙，刻意不铺满行宽）：
                // 行可用宽 = 屏宽 - LazyColumn contentPadding(16×2) - Card 内 Column padding(16×2) = 屏宽 - 64
                //   360dp ⇒ 296dp，占用 3×72 + 2×6 = 228dp，余 68dp
                //   411dp ⇒ 347dp，占用同为 228dp，余 119dp
                avatars.forEach { card ->
                    Box(
                        Modifier
                            .width(72.dp)
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
 * 牌组详情内容：两组卡面三等分 + 一条动作行（左端牌组名，右端两个 icon：复制分享码 / 导出渲染图）。
 * 行动牌 22–25 张（同一张牌可携带 2 份，见 [DeckCardTile] 的张数徽标），整页 verticalScroll（量小，不上 LazyGrid）。
 * 🔴 标题行已删除（V37-F 任务 A）：牌组名与返回都在主壳顶栏，页内再画一行就是第二条标题栏。
 */
@Composable
private fun DeckDetail(
    deck: GcgDeck,
    nickname: String?,
    uid: String?,
    onShowExportResult: (message: String, uris: List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toast = LocalToast.current
    // 剪贴板范式照 PlayerDetailDialog（LocalClipboardManager 已废弃但仍是当前工程口径）
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val shareCode = deck.shareCode
    // 🔴 判空白而不是判 null：share_code 实测可能是空串，`?:` 兜不住 ⇒ 按钮要能识别"没有可复制的东西"
    val canCopyShareCode = !shareCode.isNullOrBlank()
    // stringResource 不能在 onClick（普通 lambda）里调，先在组合期算好（同 PlayerDetailDialog）
    val copiedToast = stringResource(R.string.toast_copied_share_code, shareCode.orEmpty())
    val copyLabel = stringResource(R.string.my_copy_share_code)
    val exportLabel = stringResource(R.string.my_export_deck_image)
    // 文件名里的「我的卡组」与导出图的品牌口径同源
    val deckLabel = stringResource(R.string.my_deck_entry)
    val exporter = rememberDeckExporter(
        context = LocalContext.current,
        deck = deck,
        deckTitle = deckDisplayName(deck),
        nickname = nickname,
        uid = uid,
        deckLabel = deckLabel,
        onResult = onShowExportResult,
    )

    MySubpageScaffold(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // 左侧卡组名 + 右侧两个动作。**顶栏（返回 + 标题）不动**，这是用户要的「动作行左边再标一次牌组名」。
            // 两个动作贴右上角：IconButton 的 48dp 触摸区外沿留 4dp ⇒ 图标正好落在 16dp 内容线上，
            // 与主壳顶栏 actions 区的图标同列（用户要的"横向对齐卡组名称"在单标题栏下的等价落点）。
            // 名称左缘同理必须与主壳顶栏主标题的墨迹同线（16dp），取「我的」域统一内距常量。
            // 🔴 两段 padding 分开写、不合并成一个调用：动作行的源码指纹是下面那行原文（既有闸门测试的锚点）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = MyRowHorizontalPadding)
                    .padding(top = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    deckDisplayName(deck),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val code = shareCode
                        val message = copiedToast
                        if (!code.isNullOrBlank() && message.isNotEmpty()) {
                            @Suppress("DEPRECATION") clipboard.setText(AnnotatedString(code))
                            toast(message)
                        }
                    },
                    enabled = canCopyShareCode,
                ) {
                    Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = copyLabel)
                }
                if (exporter.exporting) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = exporter.run, enabled = exporter.enabled) {
                        Icon(imageVector = Icons.Outlined.IosShare, contentDescription = exportLabel)
                    }
                }
            }
            CardGroup(
                title = stringResource(R.string.my_deck_card_group_avatar),
                cards = deck.avatarCards.orEmpty(),
                showCount = false,
            )
            CardGroup(
                title = stringResource(R.string.my_deck_card_group_action),
                cards = deck.actionCards.orEmpty(),
                showCount = true,
            )
        }
    }
}

/** 导出动作：按钮可用性 + 导出中态 + 点击执行（渲染在协程里跑，UI 期间禁点并出进度） */
private data class DeckExportActionUi(val enabled: Boolean, val exporting: Boolean, val run: () -> Unit)

/**
 * 卡组导出：组 spec → 渲染 → 落盘 → 一条带「查看」的结果回传宿主（与统计页导出同一链路）。
 * 渲染要拉十几张卡图，耗时不确定 ⇒ 必须挂协程；`exporting` 同步置位挡住重复点击，
 * finally 复位保证异常/取消路径不会把按钮永久禁用（照 rememberStatsExporter）。
 */
@Composable
private fun rememberDeckExporter(
    context: Context,
    deck: GcgDeck,
    deckTitle: String,
    nickname: String?,
    uid: String?,
    deckLabel: String,
    onResult: (message: String, uris: List<Uri>) -> Unit,
): DeckExportActionUi {
    val appContext = context.applicationContext
    val coroutineScope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    val run: () -> Unit = remember(deck, deckTitle, nickname, uid, deckLabel, exporting) {
        {
            if (!exporting) {
                exporting = true
                coroutineScope.launch {
                    try {
                        val spec = buildDeckImageSpec(deck, deckTitle, nickname, uid, exportDateText())
                        val outcome = exportDeckImage(appContext, spec, deckLabel, uid)
                        onResult(deckExportFeedback(outcome.succeeded, outcome.error), listOfNotNull(outcome.uri))
                    } finally {
                        exporting = false
                    }
                }
            }
        }
    }
    return DeckExportActionUi(enabled = !exporting, exporting = exporting, run = run)
}

/**
 * 一组卡面：**真三等分**（Row + weight(1f)）。
 * 🔴 此前是 FlowRow + 固定 104.dp tile 宽 —— 那是按 360dp 屏推算出来的常量，
 * 换到实际屏宽就凑不满一行、末尾留下不等宽的空隙（用户第 9 项"不是 3 等分"）。
 * 末行不足 3 张时补空 Spacer 占位，否则 weight 会把剩下的两张拉宽、列不对齐。
 *
 * `showCount` 由分组类型决定：行动牌组传 true（同一张牌可携带多份），角色牌组传 false（见 [DeckCardTile]）。
 */
@Composable
private fun CardGroup(title: String, cards: List<GcgDeckCard>, showCount: Boolean) {
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
                    Box(Modifier.weight(1f)) { DeckCardTile(card = card, showCount = showCount) }
                }
                repeat(DECK_GRID_COLUMNS - rowCards.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(DECK_GRID_GAP))
        }
    }
}

/** 张数只在 num > 1 时才值得画：num == 1 是绝大多数牌，画出来纯噪音（用户要的是"这张带了几份"）。 */
internal fun shouldShowCardCount(num: Int?): Boolean = (num ?: 0) > 1

/**
 * 张数徽标文案；返回 null 表示不画。前缀走 string（`×`），中英繁三套同一形态，
 * 避免"数字 + 量词"在英文下需要复数变形。
 */
internal fun cardCountLabel(num: Int?, countPrefix: String): String? =
    if (shouldShowCardCount(num)) "$countPrefix$num" else null

/**
 * 一张牌：卡面（可选张数徽标）+ 牌名，牌名居中单行省略。
 * 🔴 张数只对**行动牌**画（`showCount` 由 [CardGroup] 按分组传入）：
 * 真机 `gcg/deckList` 11 副牌组实测（`.task/v36-probe/basicinfo/raw/ctrl-deckList.json` 与 `-2nd.json`），
 * 行动牌 `num` 出现 2（如第 1 副末段 `[..,2,1,2,2,2]`、第 2 副 `[..,2,2,2,..]`）⇒ 同一张行动牌可携带多份；
 * 角色牌 `num` 实测恒等于 1（三张都唯一）⇒ 画徽标无信息量。
 * V37-F 曾据此把张数整个删掉，那是把角色牌的实测结论错误推广到了行动牌（本轮 bug 根因）。
 * `num` 的其它消费点：[deckActionCardTotal]（列表行总张数）与 `ui/export` 的按张数展开，三处同口径。
 */
@Composable
private fun DeckCardTile(card: GcgDeckCard, showCount: Boolean) {
    val countLabel = cardCountLabel(card.num, stringResource(R.string.my_deck_card_count_prefix))
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_FACE_ASPECT_RATIO)
                .clip(DeckCardShape),
            contentAlignment = Alignment.Center,
        ) {
            // 图在框内居中：AppImage 内层 Image 是 fillMaxSize + contentScale，Fit 本身即等比居中；
            // 外层 Box 再显式 Center，防 aspectRatio 与图片实际比例不合时贴到左上。
            // image 可能缺失：传 null 让 AppImage 走自己的占位/兜底，空串会被 coil 当合法地址去请求
            AppImage(
                model = card.image?.takeIf { it.isNotBlank() },
                contentDescription = card.name,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Fit,
            )
            if (countLabel != null) {
                // 卡面左上角小标签：配色照 MyCardBacksPage 卡面角标同一范式（scrim 半透明黑底 +
                // inverseOnSurface 白字，压在图片上两主题都可读；V37 实测 inverseSurface 会被 MIUI 动态取色
                // 解析成浅底 ⇒ 不能当容器用）。刻意不做成费用圈那种 48dp 大圆底——那是"费用"的视觉语义，混用会误读。
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            card.name ?: stringResource(R.string.state_empty_response),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}
