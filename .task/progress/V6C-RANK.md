status: done（assembleDebug --rerun-tasks BUILD SUCCESSFUL；diff 仅 RankRoute.kt / RankViewModel.kt 两独占文件）

## A 加载圈居中
- 未改 StateViews.kt（非独占）。在 RankRoute.kt 调用处修：
  `RankPageContent` 的 Loading/NotLoaded、Error、Empty 分支均包
  `Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ... }`。
  Box 位于 HorizontalPager 页内，pager 每页占满 PullToRefreshBox 视口，fillMaxSize 即撑满剩余高度。
- ErrorState 仍带 fillMaxWidth，其内部 TextButton 重试按钮在 Box 中正常可点。

## B Tab 左右滑动（HorizontalPager）
- `rememberPagerState(pageCount = { rankTabs.size })`；TabRow 保留为指示器，
  `selectedTabIndex = pagerState.settledPage`，Tab onClick → `scope.launch { pagerState.animateScrollToPage(index) }`。
- pager 页内各自渲染 Peak/Competition 的 Loading/Error/Empty/Content（RankPageContent 按页取 `rankListFor(state, rankTabs[page])`），分页数据不串页。
- pager→VM 同步：`LaunchedEffect(pagerState) { snapshotFlow { pagerState.settledPage }.collect { viewModel.selectTab(rankTabs[it]) } }`。
- 防回环依据：
  1) VM `selectTab` 幂等：RankViewModel.kt L58 `if (_uiState.value.activeTab == tab) return`；
  2) 单向数据流：Route 不再用 state.activeTab 反向 animate pager，activeTab 变化不会触发 pager 动画 ⇒ 无循环、无重复请求。

## C 下拉刷新
- `PullToRefreshBox`（material3，@OptIn(ExperimentalMaterial3Api::class)）包裹 HorizontalPager；
  纵向 pull 与 pager 横向滑动方向正交，可共存。
- 刷新入口：复用既有 `retry()` 语义（只刷 activeTab、force 绕缓存，RankViewModel.kt L68-73），
  未新增重复方法；但 retry() 原为 Unit 无进度信号，补 `refreshing: StateFlow<Boolean>`
  （L45-46，retry 置位、L120-129 setState 在 activeTab 落定 Content/Error 时清零），供指示器收尾。
  既有全局 `refresh()`（两表重取）语义不动，仍由其他调用方使用。
- retry() 加 `if (_refreshing.value) return` 与 uid==null 守卫，防重复并发 force 请求。

## D 切页不自动重拉（核实）
- `ensureLoaded` L91：`if (!force && state !is NotLoaded && state !is Error) return` —— 已 Content 的 tab 短路，不重复请求。✔
- init 的 sessionUid.collect → `ensureLoaded(activeTab)` 不带 force，同样短路。✔
- 切 Tab → selectTab → ensureLoaded（非 force）→ 已加载页短路，仅首次切换请求。✔
- 离开再回排行页若 VM 重建（导航层决定，非本文件），会按 NotLoaded 各拉一次，属预期首屏。未引入缓存层、未改 repository。

## E 自审
- 名次列 widthIn(min=28.dp)、行内 16/8dp 间距、Tab/TextButton 触摸目标均 ≥ 既有 Material3 组件默认，无新增违规；
  金银铜固定语义色为设计红线 8 要求，保留。RankRow 逻辑原样迁移，功能语义未变。
