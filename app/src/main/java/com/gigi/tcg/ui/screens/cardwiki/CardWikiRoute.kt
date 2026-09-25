// 卡牌图鉴页（卡面下载）：移植 CardWikiPage.tsx 的渲染层。
// 三分类 TabRow + 紧凑搜索框 + 单行横向滚动筛选 chips + 竖版卡牌自适应网格。
// 点击卡牌→上抛 onOpenCover(contentId)；contentId 缺失时提示数据异常。加载/空/错误态复用共享组件。
// 图鉴是 1h TTL 的公开静态数据：进页加载 + 筛选驱动即可，无手动刷新入口（仅错误态保留"重试"）。

package com.gigi.tcg.ui.screens.cardwiki

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.ui.components.AppImage
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LoadingView

private const val EMPTY_FILTERED = "暂无符合条件的卡牌"
private const val CARD_DATA_ERROR = "卡牌数据异常"
private const val NO_IMAGE = "无图"
private const val SEARCH_PLACEHOLDER = "搜索卡牌名称"
private const val CLEAR_KEYWORD = "清空搜索"

/** 官方卡面 420x720 竖版，网格缩略图按同比例铺位 */
private const val CARD_ASPECT_RATIO: Float = 7f / 12f

/** 一屏可见卡牌数对齐 Web 版 `minmax(150px, 1fr)`；宽屏自动多列 */
private val GRID_CARD_MIN_WIDTH = 156.dp
private val GRID_SPACING = 10.dp
private val GRID_CONTENT_PADDING = 12.dp

/** M3 文本框默认 56dp，图鉴要留出网格高度：48dp 仍满足最小触控目标 */
private val SEARCH_FIELD_HEIGHT = 48.dp

@Composable
fun CardWikiRoute(
    container: AppContainer,
    onOpenCover: (Long) -> Unit,
    onShowToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CardWikiViewModel = viewModel(factory = CardWikiViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 待打开卡面：上抛宿主后立即置空，保证再次点击同一张也能触发
    val pendingCoverId = state.coverId
    LaunchedEffect(pendingCoverId) {
        if (pendingCoverId != null) {
            onOpenCover(pendingCoverId)
            viewModel.closeCover()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        WikiTabs(
            categories = state.categories,
            activeCatId = state.activeCatId,
            onSelect = viewModel::selectCategory,
        )

        val category = state.activeCategory
        if (!state.loading && state.error == null && category != null) {
            FilterBar(
                category = category,
                selections = state.selections,
                keyword = state.keyword,
                onKeywordChange = viewModel::onKeywordChange,
                onSelectionChange = viewModel::onSelectionChange,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val error = state.error
            when {
                state.loading -> LoadingView(modifier = Modifier.align(Alignment.Center))
                error != null -> ErrorState(
                    modifier = Modifier.align(Alignment.Center),
                    message = error,
                    onRetry = { viewModel.load(force = true) },
                )
                state.filteredCards.isEmpty() -> EmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    title = EMPTY_FILTERED,
                )
                else -> CardGrid(
                    cards = state.filteredCards,
                    onCardClick = { card ->
                        val id = card.contentId
                        if (id == null) onShowToast(CARD_DATA_ERROR) else viewModel.openCover(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun WikiTabs(
    categories: List<CategoryVm>,
    activeCatId: Int,
    onSelect: (Int) -> Unit,
) {
    val selectedIndex = categories.indexOfFirst { it.id == activeCatId }.coerceAtLeast(0)
    TabRow(selectedTabIndex = selectedIndex) {
        categories.forEach { category ->
            Tab(
                selected = category.id == activeCatId,
                onClick = { onSelect(category.id) },
                text = { Text(category.title) },
            )
        }
    }
}

/** 搜索框一行 + 全部筛选维度共用一行横向滚动：固定两行高，不与网格争垂直空间 */
@Composable
private fun FilterBar(
    category: CategoryVm,
    selections: Map<String, String>,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onSelectionChange: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeywordField(keyword = keyword, onKeywordChange = onKeywordChange)
        if (category.filterDefs.isNotEmpty()) {
            FilterRow(
                category = category,
                selections = selections,
                onSelectionChange = onSelectionChange,
            )
        }
    }
}

@Composable
private fun KeywordField(keyword: String, onKeywordChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = keyword,
        onValueChange = onKeywordChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SEARCH_FIELD_HEIGHT)
            .clip(CircleShape) // M3 corner=Full（stadium）：搜索框用胶囊形
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (keyword.isEmpty()) {
                        Text(
                            text = SEARCH_PLACEHOLDER,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (keyword.isNotEmpty()) {
                    IconButton(onClick = { onKeywordChange("") }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = CLEAR_KEYWORD,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun FilterRow(
    category: CategoryVm,
    selections: Map<String, String>,
    onSelectionChange: (String, String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        category.filterDefs.forEachIndexed { index, def ->
            val label = def.label ?: return@forEachIndexed
            if (index > 0) {
                VerticalDivider(modifier = Modifier.height(28.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            val selected = selections[label] ?: ""
            FilterChip(
                selected = selected.isEmpty(),
                onClick = { onSelectionChange(label, "") },
                label = { Text("全部") },
            )
            def.children.forEach { child ->
                val childLabel = child.label ?: return@forEach
                FilterChip(
                    selected = selected == childLabel,
                    onClick = { onSelectionChange(label, childLabel) },
                    label = { Text(childLabel) },
                )
            }
        }
    }
}

@Composable
private fun CardGrid(
    cards: List<CardVm>,
    onCardClick: (CardVm) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(GRID_CARD_MIN_WIDTH),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(GRID_CONTENT_PADDING),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
    ) {
        // contentId 可能缺失（数据异常），用下标兜底避免 LazyGrid 重复 key 崩溃
        itemsIndexed(cards, key = { index, card -> card.contentId ?: "noid-$index" }) { _, card ->
            WikiCard(card = card, onClick = { onCardClick(card) })
        }
    }
}

@Composable
private fun WikiCard(card: CardVm, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_ASPECT_RATIO)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (card.icon.isNotEmpty()) {
                AppImage(
                    model = card.icon,
                    contentDescription = card.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = NO_IMAGE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = card.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
