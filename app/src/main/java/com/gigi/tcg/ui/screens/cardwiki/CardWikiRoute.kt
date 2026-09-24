// 卡牌图鉴页（卡面下载）：移植 CardWikiPage.tsx 的渲染层。
// 三分类 TabRow + 每维度一行 FilterChip（"全部"即未选）+ 关键词搜索框 + 自适应网格。
// 点击卡牌→上抛 onOpenCover(contentId)；contentId 缺失时提示数据异常。加载/空/错误态复用共享组件。

package com.gigi.tcg.ui.screens.cardwiki

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.gigi.tcg.di.AppContainer
import com.gigi.tcg.ui.components.EmptyState
import com.gigi.tcg.ui.components.ErrorState
import com.gigi.tcg.ui.components.LoadingView

private const val EMPTY_FILTERED = "暂无符合条件的卡牌"
private const val CARD_DATA_ERROR = "卡牌数据异常"
private const val NO_IMAGE = "无图"
private val GRID_CARD_WIDTH = 110.dp

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
            onRefresh = { viewModel.load(force = true) },
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
    onRefresh: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val selectedIndex = categories.indexOfFirst { it.id == activeCatId }.coerceAtLeast(0)
        TabRow(
            selectedTabIndex = selectedIndex,
            modifier = Modifier.weight(1f),
        ) {
            categories.forEach { category ->
                Tab(
                    selected = category.id == activeCatId,
                    onClick = { onSelect(category.id) },
                    text = { Text(category.title) },
                )
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(imageVector = Icons.Outlined.Refresh, contentDescription = "刷新")
        }
    }
}

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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeywordChange,
            singleLine = true,
            placeholder = { Text("搜索卡牌名称") },
            modifier = Modifier.fillMaxWidth(),
        )
        category.filterDefs.forEach { def ->
            val label = def.label ?: return@forEach
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(64.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
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
    }
}

@Composable
private fun CardGrid(
    cards: List<CardVm>,
    onCardClick: (CardVm) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(GRID_CARD_WIDTH),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(cards, key = { "${it.contentId}-${it.title}" }) { card ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCardClick(card) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (card.icon.isNotEmpty()) {
                    AsyncImage(
                        model = card.icon,
                        contentDescription = card.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(NO_IMAGE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
