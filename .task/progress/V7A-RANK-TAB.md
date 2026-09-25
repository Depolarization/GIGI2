# V7A-RANK-TAB — 排行榜 TabRow indicator 跟随滑动修复

status: done

## 现象
`TabRow(selectedTabIndex = pagerState.settledPage...)`，`settledPage` 仅在滚动完全落定后变化
→ indicator 滑动过程中纹丝不动，落定后才跳。

## 闸门
`git diff --quiet -- .../rank/RankRoute.kt` → exit 0 ✅（改前独占文件干净）

## 结论：平滑插值版（未退化）
`selectedTabIndex = pagerState.currentPage`；indicator 在相邻两个 `TabPosition` 之间按
`currentPageOffsetFraction` 线性插值 left/right，跟手移动；fraction == 0 时走官方
`Modifier.tabIndicatorOffset(current)` 静止态。`Tab(selected =)` 同步改为 `currentPage == index`。

## 实际编译到的 material3 API（BOM 2025.09.00 → material3-android 1.3.2，javap 实测）
```
TabRowDefaults.SecondaryIndicator(modifier: Modifier = Modifier, width: Dp,
    height: Dp = TabRowDefaults.IndicatorHeight, color = contentColor)   // 默认参数版
Modifier.tabIndicatorOffset(tabPosition: TabPosition): Modifier          // 同文件已有 @OptIn(ExperimentalMaterial3Api)
class TabPosition { val left: Dp; val right: Dp; val width: Dp; val contentWidth: Dp }   // ⚠️ 是 Dp 不是 px！
Modifier.offset(offsetX: (Density.() -> IntOffset))                      // androidx.compose.foundation.layout（foundation-layout 1.9.1）
```
⚠️ 参考实现里 `TabPosition.left` 当 px-Float 用是错的：1.3.2 中 left/right 为 **Dp**，
插值直接在 Dp 上做（`current.left + (next.left - current.left) * fraction`），
偏移用 `leftDp.roundToPx()`、宽度用 `rightDp - leftDp`。

## 数据加载语义
`LaunchedEffect(pagerState) { snapshotFlow { pagerState.settledPage }... selectTab }` **原样保留**
（仅补注释说明 indicator 跟随 currentPage、数据仍跟随 settledPage）。

## 验收
- `JAVA_HOME=ms-21.0.12.1 ./gradlew :app:assembleDebug --rerun-tasks` → **BUILD SUCCESSFUL**
  （第一次因并发构建 Windows 文件锁 R.jar 失败，重试即过；AppGate.kt 的报错系并发棒中途状态，未裹挟）
- 独占文件只动 `RankRoute.kt`；其余 diff（AppGate.kt / CardStatsRoute.kt 等）为并发棒产物，未 add。
