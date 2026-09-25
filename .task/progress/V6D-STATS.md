# V6D-STATS · 卡牌统计页改版

status: done

## 构建记录
- 第 1 次 assembleDebug --rerun-tasks：`compileDebugKotlin` ✅（我的 2 文件无编译错误），
  失败在 `:app:packageDebug` → NoSuchFileException mergeExtDexDebug/classes.dex，
  典型并发 dex 干扰（ENV-NOTES 已预警）。按规程重跑。
- 第 2 次同命令重跑：BUILD SUCCESSFUL in 17s（35 tasks executed，含 compileDebugKotlin）。

## A 根因（头像首屏不加载）
- 根因在 VM：`CardStatsViewModel.kt:166` `if (_uiState.value.detailOpen) fetchAvatar()`。
  `StatsUiState.detailOpen` 默认 false（CardStatsViewModel.kt:61），所以 `load()` 成功后
  头像分支永远不执行；唯一赋值点是 `toggleDetail()`（L131）"首次展开且 avatarUrl==null 时懒取"。
  ⇒ 首屏 `avatarUrl` 恒为 null，Avatar 只显示占位；点"展开详情"才触发 fetchAvatar()，头像才出现。
- 修法：`load()` 成功路径改为无条件 `fetchAvatar()`（与 summary 一起就绪，
  fetchMyHomePageCached 有 45s 仓储缓存、与主页共用，成本低）；`toggleDetail()` 回归纯开关。
- 附带（F 依赖）：三态路由 `state.loading -> LoadingView` 会在下拉刷新时把内容整页替换掉，
  改为 `state.loading && state.summary == null` 才进全屏加载，刷新时保留内容。

## B 字段核对
- GcgSummary（domain/Summary.kt:62-83）实际字段：
  totalGames: Long（L68）、winGames: Long（L70）、actionTotalUse: Int（L76）、
  winRate: **String**（L72，formatPercent 产物如 "33.3%"，直接传 Metric(label, value:String)）。
- metric 行最终四项：总对局 / 获胜对局 / 打出行动牌 / 总胜率。

## B/C/D/E 实施记录
- B：metric 行四项 = totalGames / winGames / actionTotalUse / winRate（"角色牌收集"移除，"总胜率"新增）。
- C：删除 `Text("玩家信息", labelMedium)`；昵称 Row 改为 Avatar + Text(weight=1f)，
  长昵称省略号此前无宽度约束（原 Column 也未 weight），现修复。
- D：`"牌手等级 …　总胜率 …"` 整行删除（总胜率→B 行，牌手等级→E 足迹组）。
- E：DetailGroup "收集进度" → "足迹"，行序 = 牌手等级 / 角色牌收集 / 行动牌收集（hint 均 null）。

## F 下拉刷新
- CardStatsContent 用 material3 `PullToRefreshBox(isRefreshing = state.loading, onRefresh = viewModel::refresh)`
  包裹原 `Column.verticalScroll`；VM 新增 `refresh() = load(force = true)`（retry 保留给 ErrorState）。
- 配套：三态闸门 `state.loading` → `state.loading && state.summary == null`，
  否则刷新时 loading=true 会把整页替换成 LoadingView、PullToRefreshBox 被卸载。


## G 切页缓存核实结论
- 页内 Tab（角色牌/行动牌）：tabIndex 是 CardStatsRoute.kt:123 的 rememberSaveable 本地状态，
  排序/筛选只走 `_uiState.update`（VM setCharSort/setActionType），零网络。不重拉。
- 离开页面再回来：NavHost composable 销毁 backstack entry ⇒ VM 重建 ⇒ init load(force=false)；
  但 `fetchGcgCardListCached`（GigiRepository.kt:187，5min TTL）与 `fetchMyHomePageCached`
  （L193，45s TTL）命中仓储内存缓存 ⇒ TTL 内不发真实请求。仓储层已有缓存，不新增全局缓存层，无需改动。
