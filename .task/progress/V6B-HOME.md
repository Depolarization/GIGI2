# V6B-HOME — 首页布局重构（A-H）

status: done

## 构建验收
`JAVA_HOME=C:/Users/oscur/.jdks/ms-21.0.12.1 ./gradlew :app:assembleDebug --rerun-tasks`
→ **BUILD SUCCESSFUL in 1m**（35 tasks 全部 executed，非缓存）。
`git diff --stat` 代码改动仅 HomeRoute.kt（+130/−72）；HomeViewModel.kt 经核实无需改动。

## 改前闸门
`git diff --quiet -- HomeRoute.kt HomeViewModel.kt` → exit=0，放行。

## A 删除"对局主页"标题
原 L86-95 的标题 Row 整块删除，页面不再有两级标题。

## B 刷新按钮并入"最近对局"行 + 边距推导
`Row(CenterVertically) { Text("最近对局", titleMedium, weight(1f)) ; IconButton(refresh) }`。
边距推导：IconButton 触摸目标 48dp，titleMedium 行高约 24dp ⇒ 按钮行自身在文字上下各带
约 12dp 空白。原方案卡片→标题间距为纯 16dp Spacer；为保持观感，标题行上方 Spacer 取
**8dp**（8 + 12 ≈ 20dp，接近原 16dp 且被按钮行视觉吸收，不会贴死卡片）；下方 Spacer 取
**4dp**（4 + 12 ≈ 16dp，标题与列表间距回到原 16dp 体系，不悬空）。已写入代码注释
（改后 HomeRoute.kt:117-118、119/130）。

## C 双加载圈合一
实现选择：`if (profile is Loading && records is Loading) LoadingView() else { 原双块渲染 }`
（改后 HomeRoute.kt:100-102）。首屏两块同在初始 Loading 时只亮一个圈；任一单块 Loading
（如单块重试、或 staggered 首刷的 400ms 错峰窗口）仍按块各自显示 LoadingView。
ErrorState 分支原样保留且带 retryProfile/retryRecords 重试按钮（HomeRoute.kt:111-114,
134-137），未被吞掉。

## D 积分区垂直下移（对齐 PlayerDetailDialog.ScoresRow L203-225）
ProfileCard 内容改为 `Column(padding 16dp) { Row(头像+信息列); Spacer(12dp); Row(fillMaxWidth){
ScoreItem(weight(1f)) ×2 } }`。ScoreItem 与详情对话框逐字一致：label `labelMedium`+
`onSurfaceVariant` 在上、数值 `titleLarge`+`FontWeight.Bold`+语义色在下；天梯=semantic.win、
巅峰=semantic.gold。

## E UID 与段位分两行
`"UID:$uid"`、`"段位:${tier.ifEmpty { "无段位" }}"` 各占一行，均 bodySmall +
onSurfaceVariant；昵称加 maxLines=1 防长名挤压（与 RecordItem 昵称同风格，H 项自审顺手修）。

## F 下拉刷新
`@OptIn(ExperimentalMaterial3Api::class)` + `PullToRefreshBox(isRefreshing, onRefresh =
viewModel::refresh)` 包外层，内容仍是 `Column.fillMaxSize().verticalScroll().padding(16dp)`
（Box 外、滚动 Column 内，满足可滚动要求）。isRefreshing = `profile is Loading ||
records is Loading` 派生布尔，两块落定后自动收回。refresh() 已 force 双块
（HomeViewModel.kt:106-111）。

## G 切页不自动重拉（缓存）—— 核实结论：现状即符合，未改代码
- 切 tab 走 `GigiNavHost.kt:303-308 navigateToTab`：`popUpTo(startDestination){saveState=true}`，
  Home 的 NavBackStackEntry 被 pop ⇒ ViewModelStore 清空（saveState 只保 SavedStateHandle），
  切回时 `viewModel()`（HomeRoute.kt:72）基于新 entry **重建 VM**，init 首刷重跑。
- 是否发请求：首刷 `force=false`（HomeViewModel.kt:99-100）⇒ `fetchMyHomePageCached` /
  `fetchGameRecordsCached`（GigiRepository.kt:193-201）⇒ `cachedPrivate`（GigiRepository.kt:
  305-320）force=false 先查 memoryCache（HOME_CACHE_TTL_MS=45s），命中**零网络请求**。
  `LaunchedEffect(sessionUid)`（HomeRoute.kt:80-84）仅在 uid 变化时 refresh，切 tab 不触发，保留。
- 结论：45s 内切回=不发请求，符合需求；超 TTL 后的重取是 repository 既定新鲜度策略
  （且 GigiRepository 属 U3A2-WIRE-V2 独占），按"不引入全局缓存层"纪律不改。
  切回瞬间一帧 LoadingView 属渲染层，无网络开销。

## H 自审
触摸目标（IconButton 48dp）✓；间距走 16/12/8/4 dp 体系 ✓；文案"刷新"contentDescription
保留 ✓；昵称 maxLines=1 防溢出 ✓。未动 LoadingView/鉴权逻辑/依赖。
