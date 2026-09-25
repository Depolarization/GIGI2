# V6B-HOME-FIX · 主代理验收后缺陷修复（A 双圈 / B 刷新清空 / C 昵称裁切）

状态：done
开始/完成：2026-09-25
上游：V6B-HOME（commit a7c4737，A–F 已验收，本棒未重做任何已验收布局改动）

## 0. 改前闸门
`git diff --quiet -- HomeRoute.kt HomeViewModel.kt` → EXIT=0（两文件干净，放行）。

## 1. 缺陷 A：首屏两个转圈（已修）

根因：HomeRoute 旧 L87 `isRefreshing = state.profile is Async.Loading || state.records is Async.Loading`
从"是否在加载"派生 ⇒ 冷启动两块初值皆 Loading ⇒ PullToRefreshBox 顶部圈与居中 LoadingView 同转。

修法（照 RankViewModel `_refreshing` 只由主动刷新置位的形状）：
- HomeViewModel L86-87：新增 `private val _refreshing = MutableStateFlow(false)` + `val refreshing`。
- HomeViewModel `refresh()`（现 L112-119）：`_refreshing.value = true` 置于
  `val uid = container.sessionUid.value ?: return` **之后**——提前返回不置位（见 §2 清零保证）。
- HomeRoute L89：`val isRefreshing by viewModel.refreshing.collectAsStateWithLifecycle()`。
- 居中 LoadingView 分支（bothLoading）未动，仅顶部圈的数据源换了。

### "置位必有人清零"保证（含行号，改后文件）
置位点唯一：`refresh()` HomeViewModel.kt **L116**（在 L113 uid 判空之后）。
清零点唯一：`maybeEndRefreshing()` HomeViewModel.kt **L181-188**，条件
`_refreshing.value && profile !is Async.Loading && records !is Async.Loading`。
调用点：loadProfile 落定 `_uiState.value = copy(profile = next)` 之后 **L151**、
loadRecords 落定 copy 之后 **L175**——均在 `gen == *Generation` 分支内。
穷举证明不会悬挂：
1. refresh() 置 true 后必然已同步调用 loadProfile+loadRecords，各启动一个协程，任何结局
   （Content/Error/异常捕获）都会走落定 copy；
2. 若某块落定时被代数判定为过期（gen != generation），说明同一块有更新的请求在途，
   更新请求落定时同样走 L151/L175——最后一批落定的那次调用看到"两块都非 Loading"即清零；
3. 协程被取消仅发生于 viewModelScope 销毁（页面死亡，指示器无人再观察）；
4. L113 提前返回在置位之前，未登录路径根本不产生 true。

## 2. 缺陷 B：下拉刷新整页被清空（已修）

loadProfile L137 / loadRecords L160：`val silent = current is Async.Content && !force`
→ `val silent = current is Async.Content`（force 只决定绕不绕缓存，不再把已有内容打成 Loading）。
三情形逐条推演：
- **首次加载**：初值 `Async.Loading`（HomeUiState 默认值），`current is Content` 为假 ⇒
  silent=false ⇒ 进 Loading，居中 bothLoading 圈照常亮。✅ 行为不变。
- **下拉刷新（有内容）**：refresh() force=true，但两块 current 皆 Content ⇒ silent=true ⇒
  不回落 Loading ⇒ 整页内容保留，只有顶部指示器转；落定后 copy 新内容 + maybeEndRefreshing 收回。✅ 修复目标。
- **Error 态重试/刷新**：current 是 Error ⇒ silent=false ⇒ 回落 Loading、该块显示转圈；
  单块重试时另一块若非 Loading，bothLoading 不成立，走分支内 `Async.Loading -> LoadingView()` 局部圈。✅ 行为不变。
（即"有内容才保留"，不是"永远不显示加载态"。）

## 3. 缺陷 C：长昵称硬裁切（已修）

HomeRoute.kt ProfileCard 昵称 Text（现 L194-199）：`maxLines = 1` 基础上补
`overflow = TextOverflow.Ellipsis`，新增 import `androidx.compose.ui.text.style.TextOverflow`（L44）。
仅此一个属性 + 一条 import，字号/布局未动；RecordItem 对手昵称不在本缺陷清单，未动。

## 4. 新测试：app/src/test/java/com/gigi/tcg/ui/screens/home/HomeReloadModeTest.kt

选型：**源码文本断言**（未抽 `isSilentReload(b) = b` 恒真纯函数——单行恒等式抽函数是无意义抽象，
断言"真实调用点的 silent 判定里不再出现 force"反而钉死的是出过 bug 的那一行；做法参照 U3A2-WIRE-V2 §2.2）。
6 用例（对应验收门槛要求的 isSilentReload(true/false) 两语义：有内容⇒静默、无内容⇒加载态）：
1. `silentDecision_keepsContentRegardlessOfForce`：恰 2 处 `val silent =`，均含 `Async.Content`、均不含 `force`
   （= isSilentReload(true)==true 且与 force 无关 的源码等价物）；
2. `nonContentStillFallsBackToLoading`：两处 silent 后紧跟 `if (!silent)` 闸门
   （= isSilentReload(false)==false，首载/Error 仍进 Loading）；
3. `refreshingFlag_setOnlyAfterUidGuardInRefresh`：refresh() 体内 `_refreshing.value = true`
   出现在 `?: return` 之后（置位不放空枪）；
4. `refreshingFlag_clearedWhenBothSettled`：maybeEndRefreshing 定义存在、调用恰 2 处、条件为两块 !Loading；
5. `route_isRefreshingComesFromViewModelFlag`：HomeRoute 用 `viewModel.refreshing.collectAsStateWithLifecycle()`，
   且不存在 `isRefreshing = …Async.Loading…` 派生式；
6. `profileNickname_usesEllipsis`：昵称 Text 带 `overflow = TextOverflow.Ellipsis` 且 import 在位。

## 5. 自检反例推演（手工时序）

**冷启动首屏**：VM init 发首刷（force=false）→ HomeUiState 初值两块 Loading、`_refreshing` 恒 false ⇒
Route `isRefreshing=false` ⇒ PullToRefreshBox **不渲染顶部指示器**；bothLoading 成立 ⇒ 居中 LoadingView
唯一圈。profile 落定 Content → bothLoading 破 → 渲染内容。✅ 全程只见过一个圈。
（旧代码同一步骤 isRefreshing=true 与 LoadingView 并存=两个圈，即本缺陷。）

**下拉刷新（内容已就绪）**：手势触发 onRefresh=refresh() → L116 置 true → 顶部指示器展开；
两块 silent=true 不打 Loading → 内容**不消失不替换**；bothLoading 恒假 → 无居中圈；
两块落定 copy → L151/L175 maybeEndRefreshing → 顶圈收回。✅ 全程只转顶部一个指示器、内容零闪变。

**Error 重试**：retryProfile 不碰 `_refreshing`（只响应下拉/按钮时置位）→ 无顶圈；
块内 Error→Loading→局部居中圈。✅ 与修前行为一致。

## 6. 验收数字
- `JAVA_HOME=.../ms-21.0.12.1 ./gradlew :app:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL**；
  HomeReloadModeTest.xml：`tests="6" skipped="0" failures="0" errors="0"`（本次 --rerun-tasks 实际执行）。
- `JAVA_HOME=.../ms-21.0.12.1 ./gradlew :app:assembleDebug --rerun-tasks` → **BUILD SUCCESSFUL**（22 tasks executed）。
- 改动文件 = 独占 2 源码 + 新建 1 测试（+ 本探针），未触碰他人文件。
