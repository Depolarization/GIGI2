// 卡牌图鉴页（卡面下载）：移植 CardWikiPage.tsx 的渲染层。
// 三分类 TabRow + 紧凑搜索框 + 筛选下拉菜单（每维度一组、组内横向滚动）+ 竖版卡牌两列固定网格。
// 点击卡牌→上抛 onOpenCover(contentId)；contentId 缺失时提示数据异常。加载/空/错误态复用共享组件。
// 图鉴是 1h TTL 的公开静态数据：进页加载 + 筛选驱动即可，无手动刷新入口（仅错误态保留"重试"）。

package com.gigi.tcg.ui.screens.cardwiki

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
private const val FILTER_ENTRY = "筛选"
private const val FILTER_ALL = "全部"

/** 官方卡面 420x720 竖版，网格缩略图按同比例铺位 */
private const val CARD_ASPECT_RATIO: Float = 7f / 12f

private val GRID_SPACING = 8.dp
private val GRID_CONTENT_PADDING = 12.dp

/** M3 文本框默认 56dp，图鉴要留出网格高度：48dp 仍满足最小触控目标且高于清空按钮的触摸目标下限 */
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

/** 搜索框独立一行 + 筛选入口按钮一行：两行各 48dp，上下留白与行距统一 8dp，不与网格争垂直空间 */
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KeywordField(keyword = keyword, onKeywordChange = onKeywordChange)
        if (category.filterDefs.isNotEmpty()) {
            FilterMenu(
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
            .height(SEARCH_FIELD_HEIGHT) // 固定高度：清空按钮的 48dp 触摸目标不再把框撑高
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
                Spacer(modifier = Modifier.width(8.dp))
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
                    // M3 IconButton 自带 minimumInteractiveComponentSize：不锁 36dp，触摸目标保 ≥48dp
                    IconButton(onClick = { onKeywordChange("") }) {
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

/**
 * 筛选入口：单个按钮展开下拉菜单，菜单内每个维度一组，组内条件横向滚动。
 * 选中后菜单保持展开——用户常要连调多个维度，展开态可连续点选，关闭交给点击外部/系统返回；
 * 入口按钮文本带已选维度数（未选只显示"筛选"），避免筛选静默生效导致结果对不上。
 */
@Composable
private fun FilterMenu(
    category: CategoryVm,
    selections: Map<String, String>,
    onSelectionChange: (String, String) -> Unit,
) {
    val defs = category.filterDefs.filter { !it.label.isNullOrEmpty() }
    if (defs.isEmpty()) return
    val activeCount = defs.count { (selections[it.label] ?: "").isNotEmpty() }
    var expanded by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 菜单宽度对齐入口按钮宽度，组内横向滚动区才有稳定可视宽度
        val menuWidth = maxWidth
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (activeCount > 0) "$FILTER_ENTRY · 已选 $activeCount 项" else FILTER_ENTRY,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(
                modifier = Modifier
                    .width(menuWidth)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                defs.forEach { def ->
                    val label = def.label ?: return@forEach
                    FilterMenuGroup(
                        label = label,
                        children = def.children.mapNotNull { it.label },
                        selected = selections[label] ?: "",
                        onSelectionChange = onSelectionChange,
                    )
                }
            }
        }
    }
}

/** 单个筛选维度：标题固定可见，全部/子项 chips 只在组内横向滚动 */
@Composable
private fun FilterMenuGroup(
    label: String,
    children: List<String>,
    selected: String,
    onSelectionChange: (String, String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = { onSelectionChange(label, "") },
                label = { Text(FILTER_ALL) },
            )
            children.forEach { child ->
                FilterChip(
                    selected = selected == child,
                    onClick = { onSelectionChange(label, child) },
                    label = { Text(child) },
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
        // 固定 2 列：对齐 Web 版两列卡片墙，宽屏也不加列（卡面不被摊薄）
        columns = GridCells.Fixed(2),
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
