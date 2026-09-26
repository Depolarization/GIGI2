// 卡牌图鉴页（卡面下载）：移植 CardWikiPage.tsx 的渲染层。
// 三分类 TabRow + 紧凑搜索框 + 四维筛选（一行横向可滑动的原生下拉框）+ 竖版卡牌三列固定网格。
// 点击卡牌→上抛 onOpenCover(contentId)；contentId 缺失时提示数据异常。加载/空/错误态复用共享组件。
// 图鉴是 1h TTL 的公开静态数据：进页加载 + 筛选驱动即可，无手动刷新入口、无下拉刷新（仅错误态保留"重试"）。

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gigi.tcg.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.ui.components.AppImage
import com.gigi.tcg.ui.components.CenteredScrollableContainer
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LoadingView

/** 官方卡面 420x720 竖版，网格缩略图按同比例铺位 */
private const val CARD_ASPECT_RATIO: Float = 7f / 12f

private val GRID_SPACING = 8.dp
private val GRID_CONTENT_PADDING = 12.dp

/** M3 文本框默认 56dp，图鉴要留出网格高度：48dp 仍满足最小触控目标且高于清空按钮的触摸目标下限 */
private val SEARCH_FIELD_HEIGHT = 48.dp

/**
 * 下拉框固定宽度推导：浮动 label 12sp 中文 5 字（如"元素类型X"）≈ 60dp，
 * 当前值 16sp 中文 4 字（如"单手剑"3 字 + 余量）≈ 64dp；
 * 150dp − 左右内边距 24dp − trailingIcon 48dp = 文本可见区 78dp ≥ 64dp，label 浮动区亦不截断。
 * 393dp 屏宽（减页面边距 24dp）≈ 每屏 2.2 个，第 3 个露出大半可辨认，横向滑动补全 4 维。
 */
private val FILTER_DROPDOWN_WIDTH = 150.dp

@Composable
fun CardWikiRoute(
    container: AppContainer,
    onOpenCover: (Long) -> Unit,
    onShowToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CardWikiViewModel = viewModel(factory = CardWikiViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // onCardClick 回调非组合上下文，toast 文案先取
    val cardDataError = stringResource(R.string.wiki_card_data_error)

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
                // 加载态走共享容器：与主页/排行榜/卡牌统计同为整屏居中（图鉴页无 PullToRefreshBox，
                // 换容器不改变下拉行为，纯为视觉一致）
                state.loading -> CenteredScrollableContainer {
                    LoadingView(label = stringResource(R.string.state_wiki_loading))
                }
                error != null -> ErrorState(
                    modifier = Modifier.align(Alignment.Center),
                    message = error,
                    onRetry = { viewModel.load(force = true) },
                )
                state.filteredCards.isEmpty() -> EmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    title = stringResource(R.string.wiki_empty_filtered),
                )
                else -> CardGrid(
                    cards = state.filteredCards,
                    onCardClick = { card ->
                        val id = card.contentId
                        if (id == null) onShowToast(cardDataError) else viewModel.openCover(id)
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
                text = { Text(stringResource(category.titleRes), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** 搜索框独立一行 + 四个维度下拉框排成一行横向可滑动（一屏可见 2~3 个，左右滑动查看全部），整栏吸顶不随网格滚动 */
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
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            category.filterDefs.forEach { def ->
                val label = def.label
                if (!label.isNullOrEmpty()) {
                    FilterDropdown(
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
                    contentDescription = stringResource(R.string.cd_search),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (keyword.isEmpty()) {
                        Text(
                            text = stringResource(R.string.wiki_search_placeholder),
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
                            contentDescription = stringResource(R.string.wiki_clear_search),
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
 * 单个筛选维度：一个固定宽度的原生下拉框（横向滑动行内不随内容伸缩，四框等宽对齐）。
 * 闭状态同时可见维度名（label）与当前值（未选显示"不限"）；选项文本只显示选项值本身，不重复维度名。
 * 选中某项后关闭本菜单（单选下拉常规行为）。
 * 菜单在 horizontalScroll Row 内仍能正常展开：menuAnchor 按锚点屏幕绝对坐标上报 Popup 位置，
 * 父级横向偏移已被计入；菜单宽度默认对齐锚点（ExposedDropdownMenu 即 this 宽度语义），
 * 与闭状态可见区域一致，无需 exposedDropdownSize 修正。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterDropdown(
    label: String,
    children: List<String>,
    selected: String,
    onSelectionChange: (String, String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected.ifEmpty { stringResource(R.string.wiki_filter_all) },
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .width(FILTER_DROPDOWN_WIDTH),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            FilterOption(
                text = stringResource(R.string.wiki_filter_all),
                selected = selected.isEmpty(),
                onClick = {
                    onSelectionChange(label, "")
                    expanded = false
                },
            )
            children.forEach { child ->
                FilterOption(
                    text = child,
                    selected = selected == child,
                    onClick = {
                        onSelectionChange(label, child)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** 选项只显示选项值本身；选中项整行 secondaryContainer 背景块 + 其上的 onSecondaryContainer 文本色 */
@Composable
private fun FilterOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        // MenuItemColors 无 containerColor，选中底色走 modifier；DropdownMenuItem 是无底 Row，背景全宽可见
        modifier = if (selected) {
            Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
        } else {
            Modifier
        },
        colors = if (selected) {
            MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSecondaryContainer)
        } else {
            MenuDefaults.itemColors()
        },
    )
}

@Composable
private fun CardGrid(
    cards: List<CardVm>,
    onCardClick: (CardVm) -> Unit,
) {
    LazyVerticalGrid(
        // 固定 3 列：真机 393dp 宽下每列约 118dp，竖版缩略图（7:12）仍清晰可辨
        columns = GridCells.Fixed(3),
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
                    text = stringResource(R.string.wiki_no_image),
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}
