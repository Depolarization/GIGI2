# V6E-WIKI 进度探针

status: doing → A/B/C/D 已完成，构建验证中

## 闸门
- `git diff --quiet -- CardWikiRoute.kt CardWikiViewModel.kt` → 退出码 0，放行。

## A. 四维筛选改"一行、横向可滑动"（CardWikiRoute.kt）
- `FilterBar`：外层 Column（整栏仍吸顶：位于 Column 顶部、LazyVerticalGrid 之外，不随网格滚动）不变；
  `KeywordField` 仍独立一行；其下新增
  `Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp))`，
  四个 `FilterDropdown` 依次排入（原 `filterDefs.forEach` 直接进 Column 的纵向一列已移除）。
- 宽度推导（`FILTER_DROPDOWN_WIDTH = 150.dp`，已写进代码注释）：
  - 浮动 label 12sp 中文 5 字 ≈ 60dp；当前值 16sp 中文 4 字 ≈ 64dp。
  - 150dp − OutlinedTextField 左右内边距 24dp − trailingIcon 48dp = 文本可见区 78dp ≥ 64dp ⇒ 中文标签（如"元素类型"）不截断成省略号。
  - 393dp 真机屏宽 − 栏水平边距 24dp = 369dp；150+8 步进 ≈ 每屏 2.2 个，第 3 个露出大半，第 4 个横向滑动补全。
- `FilterDropdown` 锚点由 `fillMaxWidth()` 改为 `width(FILTER_DROPDOWN_WIDTH)`（在 horizontalScroll 的 Row 内 fillMaxWidth 会退化为内容宽/无穷约束问题，固定宽同时保证四框等宽）。
- 下拉菜单展开验证方式（横向滚动容器内）：
  1. `menuAnchor` 通过 `onPositionChanged` 上报锚点的**屏幕绝对坐标**（含父级 horizontalScroll 的实时偏移），Popup 按该坐标定位 ⇒ 滑动后展开位置仍对准锚点（material3 ExposedDropdownMenuBox 源码语义，非臆测）。
  2. 菜单宽度：`ExposedDropdownMenu` 默认 `exposedDropdownSize()` = 跟随锚点（本 Box 宽即 150dp），与闭状态可见区域一致，不算"过窄"（选项本就是短中文词），未额外加修饰符；如后续发现长选项需要更宽菜单，material3 提供 `Modifier.exposedDropdownSize(matchTextFieldAnchorWidth)` 可显式修正。
  3. 实机路径：滑动筛选栏到第 4 维 → 点击展开 → 菜单贴锚弹出、选中后关闭并回显（本次为静态移植，未新增交互逻辑）。
- 注释同步：文件头 L2（"纵向一列"→"一行横向可滑动"）、FilterBar KDoc、FilterDropdown KDoc 均已更新。

## B. 图鉴不做下拉刷新（核实结论）
- grep `PullToRefresh|pullRequest|rememberPullToRefresh` 于 cardwiki 两文件 → **0 命中**，现状即无下拉刷新；未新增。文件头注释补了"无下拉刷新"一句以固化意图。

## C. 切页不自动重拉（核实结论，未改代码）
- 切 Tab（三个分类）：`CardWikiViewModel.selectCategory`（ViewModel L121-124）只做 `_uiState.update { copy(activeCatId, keyword, selections) }`，**不调用 load** ⇒ 切 Tab 零请求。
- 进页加载：唯一自动拉取点是 `init { load(force = false) }`（L86-88）。
- 离开图鉴再回来：GigiNavHost L254-260 `composable(ROUTE_CARD_WIKI)`，底部导航 `navigateToTab` 语义下 BackStackEntry 保留 ⇒ `viewModel()` 取到同一 VM 实例，init 不再执行；即使 entry 被回收重建，`load(force=false)` 经 `fetchCardWikiListCached`（GigiRepository L169-184，内存→磁盘 1h TTL）命中缓存，不发网络。
- `force=true` 仅出现在错误态"重试"按钮（Route L129）。结论：无重复网络请求，无需修复，未引入缓存层、未动 repository。

## D. Material3 自审（本 2 文件范围）
- 触摸目标：下拉锚点为 OutlinedTextField（高 56dp ≥ 48dp）；搜索框 48dp、清空 IconButton 自带 minimumInteractiveComponentSize（原有注释已说明）——无回归。
- 间距：Row 内 `spacedBy(8.dp)` 与栏上下 8dp、网格 8dp 一致；栏水平边距 12dp 与网格 contentPadding 12dp 对齐。
- 对比度/文案：未改色彩角色（label/trailingIcon 用 onSurfaceVariant，选中项 secondaryContainer/onSecondaryContainer 成对）；文案常量未动。
- 除 A 的结构调整与注释外，无其他语义改动；CardWikiViewModel.kt 零改动。

## 验收
- 构建：见下方"构建结果"。
- `git diff --stat`：只含独占 2 文件（实际只改了 CardWikiRoute.kt）。
- 自读改后文件：filteredCards 四维 AND 过滤逻辑（ViewModel L63-76）未触碰；网格 `GridCells.Fixed(3)` 三列固定未触碰。

status: done
