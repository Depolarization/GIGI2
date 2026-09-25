# V6D-STATS-FIX 探针

status: done

## A. 首屏双转圈修复（P1，已改）

缺陷根因：`PullToRefreshBox(isRefreshing = state.loading)`，而 `StatsUiState.loading` 默认 true
（CardStatsViewModel.kt L53）⇒ 冷启动首屏顶部刷新指示器与居中 LoadingView 同屏，两个圈。

修法（照 RankViewModel `_refreshing` 形状）：
- VM 新增 `_refreshing`/`refreshing`（L111-112），仅 `refresh()` 置位（L126）；`retry()` 不置位。
- Route `isRefreshing` 改为 `viewModel.refreshing.collectAsStateWithLifecycle()`（CardStatsRoute.kt L130）。

### "置位必有人清零"保证
`load()` 内 `loading = false` 的全部落点共 4 处，每处前一行均已 `_refreshing.value = false`：
1. L151（uid 空白提前返回分支，对应 L152）—— **会话失效兜底：不清就会永久转，已覆盖**
2. L160（cardList 为空，对应 L162）
3. L166（成功路径，对应 L169）
4. L185（异常路径，对应 L188，位于 `gen != generation` 守卫之后）

代际竞态推演：过期代际在 gen 检查（L157/L183）返回或被 `loadJob?.cancel()` 取消
（CancellationException 分支 L180 直接 rethrow，不清零），但过期只可能是"被新 load 取代"，
而**最新代际必然穿过两处 gen 检查、到达上述 4 个落点之一**（uid 空白/空数据/成功/异常，穷尽），
故任何时刻最后一个置位者必被清零，不存在悬挂路径。唯一例外是 viewModelScope 销毁（页面已不存在）。

### retry() 与 refresh() 语义差异结论
两者都 `load(force = true)` 绕 TTL 缓存，数据行为一致；差异仅在指示器：
`retry()` 挂在 ErrorState 重试按钮上（此时屏幕是 ErrorState、无 PullToRefreshBox），
置位会让"回到内容后顶部凭空闪一次刷新圈"，故**不置位**；`refresh()` 由下拉手势触发，置位正确。

## B. 三态闸门复核（只核实，未改）

结论：**下拉刷新时 `summary` 必非空，闸门 `loading && summary == null` 必为 false，内容保留，无闪屏**。
依据：
- `PullToRefreshBox` 仅存在于 `CardStatsContent`（Route L131），而进入 `CardStatsContent` 需
  Route L102-117 三分支全 miss：非(loading&&summary==null)、无 error、非 isEmpty
  （`isEmpty = !loading && error==null && summary==null`，VM L68）⇒ 无论 loading 真假，
  可下拉时 `summary != null` 恒成立。
- `refresh()` → `load()` L146 只 `copy(loading = true, error = null)`，不触碰 summary ⇒ 刷新期间闸门保持 false。
- 极端：刷新时会话失效 → L152 清 summary 但同次写 loading=false ⇒ 落到 EmptyState（正常空态，非双圈闪屏）。

## 冷启动/下拉刷新时序自检（验收门槛 4）
- **冷启动**：VM init → `load(force=false)`（L118）仅置 loading=true（L146），`_refreshing` 保持 false；
  首屏走 Route L103 分支渲染居中 LoadingView（不进 CardStatsContent，**PullToRefreshBox 未组合**）；
  即便内容已组合，`isRefreshing = refreshing = false` ⇒ 顶部指示器不出现。**只有居中一个圈。** ✓
- **下拉刷新**：手势 → `refresh()` L125 置位 → 顶部指示器转；summary 保留、内容不闪；
  请求落定到 4 落点之一 → 清零 → 指示器自动收回。无 delay/定时器 hack。✓

## 构建
`JAVA_HOME="C:/Users/oscur/.jdks/ms-21.0.12.1" ./gradlew :app:assembleDebug --rerun-tasks` → **BUILD SUCCESSFUL**（退出码 0）。
`git diff --stat` 仅含独占 2 文件（Route +5/-1、ViewModel +18/-3）。
