// 卡牌图鉴页（卡面下载）：移植 CardWikiPage.tsx 的渲染层。
// 三分类 TabRow（指示器 + HorizontalPager 左右滑动）+ 紧凑搜索框 + 四维筛选（一行横向可滑动的
// 原生下拉框）+ 竖版卡牌三列固定网格。搜索/筛选/网格滚动都是"每页一份"状态（见 WikiPage 注释）。
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.launch

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

/**
 * 图鉴三分类的规范页序：频道 id 与 CardWikiViewModel 的 CATEGORY_IDS（233/234/235）同序，
 * 其中首个直接引用 VM 的 CATEGORY_HERO_ID，避免两处各写一份魔数。
 */
internal enum class WikiCategory(val channel: Int) {
    Character(CATEGORY_HERO_ID),
    Action(234),
    Monster(235),
}

/** 图鉴规范页数（固定三分类）：页序映射的上界；实际页数仍跟着接口回传的分类列表走 */
internal const val WIKI_PAGE_COUNT: Int = 3

/**
 * 页序号 → 规范分类。越界（数据未就绪、接口缺频道导致列表缩短）时夹到最近的合法页：
 * 负号 → 角色牌（与 VM 的 activeCatId 默认值同口径），超出 → 魔物牌，映射结果始终合法。
 */
internal fun wikiCategoryOf(page: Int): WikiCategory =
    WikiCategory.entries[page.coerceIn(0, WIKI_PAGE_COUNT - 1)]

/** 筛选维度选中项进 Bundle：Map 不是 saveable 类型，摊平成 [label, value, …] 定长数组再还原 */
private val SelectionsSaver: Saver<Map<String, String>, Array<String>> = Saver(
    save = { selections ->
        val flat = ArrayList<String>(selections.size * 2)
        selections.forEach { (label, value) ->
            flat.add(label)
            flat.add(value)
        }
        flat.toTypedArray()
    },
    restore = { flat -> flat.asList().chunked(2).associate { it[0] to it[1] } },
)

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

    // 页数与页签同源（接口若缺某频道就少一页，见 VM 的 parseCategories），
    // 三分类的规范口径由 WIKI_PAGE_COUNT / wikiCategoryOf 交代；
    // rememberPagerState 每次组合都会刷新这个 lambda，取到的是最新列表。
    val pagerState = rememberPagerState(pageCount = { state.categories.size })
    val scope = rememberCoroutineScope()

    // 落定页 → VM 单选切换：刻意用 settledPage（而非 currentPage），来回拖拽不反复同步；
    // indicator 的实时跟随（下方 TabRow 用 currentPage）与它无关（同 RankRoute）。
    // channels 走 rememberUpdatedState：LaunchedEffect 只随 pagerState 重启，直接闭包读 state
    // 会一直拿到首帧的分类列表。
    val channels by rememberUpdatedState(remember(state.categories) { state.categories.map { it.id } })
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            channels.getOrNull(page)?.let(viewModel::selectCategory)
        }
    }

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
            pagerState = pagerState,
            onTabClick = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
        )

        Box(modifier = Modifier.fillMaxSize()) {
            val error = state.error
            when {
                // 加载/错误是"整页一次"的公共态（三分类共用同一份列表请求），
                // 因此留在 pager 之外：否则每个页面都要各画一遍菊花/错误块。
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
                else -> HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    WikiPage(page = page, state = state, onCardClick = { card ->
                        val id = card.contentId
                        if (id == null) onShowToast(cardDataError) else viewModel.openCover(id)
                    })
                }
            }
        }
    }
}

/** 单页内容：自己的搜索框 + 自己的四维筛选 + 自己的网格滚动位置，三页互不干扰 */
@Composable
private fun WikiPage(
    page: Int,
    state: WikiUiState,
    onCardClick: (CardVm) -> Unit,
) {
    val category = state.categories.getOrNull(page) ?: return
    // key 用页序号对应的规范频道 id，而不是列表下标或接口回传的 id：
    // 数据重载/顺序变动时"页 ↔ 自己的那份状态"这层绑定不漂移。
    val stateKey = wikiCategoryOf(page).channel

    // 搜索词与筛选维度刻意存在 Route 层、按页 key 记忆，而不是读 VM 的 keyword/selections：
    // VM 里那两份是"当前分类"的单份状态（改版前切 Tab 即重置），三页共读一份必然串台。
    // 不改 CardWikiViewModel（本轮它归集成方）：过滤语义通过构造状态副本复用（见 filteredCardsFor）。
    var keyword by rememberSaveable(stateKey) { mutableStateOf("") }
    var selections by rememberSaveable(stateKey, stateSaver = SelectionsSaver) {
        mutableStateOf(emptyMap<String, String>())
    }
    // 每页各自一份网格滚动状态（Pager 的页槽位稳定，滑回来仍停在原位置）；
    // 未做 saveable：LazyGridState 非 Bundle 类型，且改版前滚动位置本来就不跨重建保留。
    val gridState = rememberLazyGridState()

    Column(modifier = Modifier.fillMaxSize()) {
        FilterBar(
            category = category,
            selections = selections,
            keyword = keyword,
            onKeywordChange = { keyword = it },
            onSelectionChange = { label, value -> selections = selections + (label to value) },
        )

        Box(modifier = Modifier.fillMaxSize()) {
            val cards = remember(state, keyword, selections) {
                filteredCardsFor(state, category, keyword, selections)
            }
            if (cards.isEmpty()) {
                EmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    title = stringResource(R.string.wiki_empty_filtered),
                )
            } else {
                CardGrid(cards = cards, state = gridState, onCardClick = onCardClick)
            }
        }
    }
}

/**
 * 复用 VM 的过滤语义（各维度选中项 AND + 关键词不区分大小写包含），不为三页把逻辑再抄一份：
 * 用 state.copy 造一份"该页视角"的图鉴状态去取 filteredCards。
 * 此时 VM 自己的 keyword/selections 不参与计算（它们已退化为主页残留值）。
 */
private fun filteredCardsFor(
    state: WikiUiState,
    category: CategoryVm,
    keyword: String,
    selections: Map<String, String>,
): List<CardVm> = state
    .copy(activeCatId = category.id, keyword = keyword, selections = selections)
    .filteredCards

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WikiTabs(
    categories: List<CategoryVm>,
    pagerState: PagerState,
    onTabClick: (Int) -> Unit,
) {
    // 数据未就绪时 categories 为空（加载态），lastIndex = -1 会让 coerceIn 抛异常，夹到 0 兜底
    val selectedTabIndex = pagerState.currentPage.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
    TabRow(
        selectedTabIndex = selectedTabIndex,
        indicator = { tabPositions ->
            // 位置表与页签同源，空表时无从插值（数据未就绪的极窄窗口）
            if (tabPositions.isNotEmpty()) {
                val lastIndex = tabPositions.lastIndex
                // 连续页码 = 最近页 + 相对偏移（currentPageOffsetFraction ∈ [-0.5, 0.5]），
                // 用连续量插值 ⇒ 正向/反向滑动都跟手（同 RankRoute V7G：旧代码把负 fraction
                // coerceIn(0f, 1f) 夹成 0，反向拖动时 indicator 停在原 tab 直到翻页才跳）。
                val continuous = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .coerceIn(0f, lastIndex.toFloat())
                val lo = continuous.toInt().coerceIn(0, lastIndex) // 非负 ⇒ toInt() 即 floor
                val hi = (lo + 1).coerceAtMost(lastIndex)
                val t = (continuous - lo).coerceIn(0f, 1f)
                val from = tabPositions[lo]
                val to = tabPositions[hi]
                val leftDp: Dp = from.left + (to.left - from.left) * t
                val rightDp: Dp = from.right + (to.right - from.right) * t
                // 必须与官方 tabIndicatorOffset 同构：先 fillMaxWidth + wrapContentSize(BottomStart)
                // 解开 TabRow 传给 indicator 的固定宽度约束（整行宽），否则显式宽度修饰符
                // 默认 enforceIncoming=true，被 constrainWidth 夹到整行宽
                // ⇒ indicator 横贯整个 TabRow（RankRoute V7A 踩过的回归）。
                TabRowDefaults.SecondaryIndicator(
                    Modifier
                        .fillMaxWidth()
                        .wrapContentSize(Alignment.BottomStart)
                        .offset { IntOffset(leftDp.roundToPx(), 0) }
                        .width(rightDp - leftDp),
                )
            }
        },
    ) {
        categories.forEachIndexed { index, category ->
            Tab(
                selected = pagerState.currentPage == index,
                onClick = { onTabClick(index) },
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

/** 选项只显示选项值本身；选中项整行 secondaryContainer 背景块 + 其上的 onSecondaryContainer
 *  填到 itemColors 的**全部三槽**（text / leadingIcon / trailingIcon），见函数体内 V39-H2 注释 */
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
            // 🔴 V39-H2（G2 清单④ D-2 收口）：`MenuDefaults.itemColors` 的
            // textColor / leadingIconColor / trailingIconColor 是**三个独立槽**，只填一个就是半成品——
            // 现在这一行没有图标所以看不出问题，一旦有人加对勾（leading/trailing icon），
            // 没填的那两槽会去吃 M3 默认值（onSurfaceVariant / primary）⇒「块换了、图标没换」，
            // 同一个容器里文字与图标两条通道不同源。**三槽必须同时给、同值**，这是本文件的规约。
            // 实测（onSecondaryContainer × secondaryContainer，正文门槛 4.5）：夜 7.19 / 白 13.24
            //（改前只填 textColor 时同值，本条收口不改对比度，只堵分叉；三槽同值后即便加图标仍是这组值）
            MenuDefaults.itemColors(
                textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                trailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            MenuDefaults.itemColors()
        },
    )
}

@Composable
private fun CardGrid(
    cards: List<CardVm>,
    state: LazyGridState,
    onCardClick: (CardVm) -> Unit,
) {
    LazyVerticalGrid(
        // 固定 3 列：真机 393dp 宽下每列约 118dp，竖版缩略图（7:12）仍清晰可辨
        columns = GridCells.Fixed(3),
        state = state, // 每页各传一份：滑到别的分类再滑回来，仍停在本类自己的滚动位置
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
