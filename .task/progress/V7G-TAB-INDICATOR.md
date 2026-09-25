---
status: done
task: V7G-TAB-INDICATOR
files:
  - app/src/main/java/com/gigi/tcg/ui/screens/rank/RankRoute.kt
  - app/src/test/java/com/gigi/tcg/ui/screens/rank/RankTabIndicatorTest.kt (新建)
commits:
  - 1f0bc6c fix: unwrap tab indicator width constraint so it stops covering the whole row
---

# V7G-TAB-INDICATOR 探针

## 现象
真机反馈：排行榜 indicator 静止正常，**手指拖动 pager 过程中**指示条变成横贯整个 TabRow 的一条线。

## 根因复核（独立实证，非照抄派单）
本机 `javap -c` 反编译 gradle 缓存中 **material3-release-runtime.jar（版本目录确认 1.3.2）** 的
`androidx.compose.material3.TabRowDefaults$tabIndicatorOffset$2`，逐条调用顺序：
```
SizeKt.fillMaxWidth$default
Alignment$Companion.getBottomStart → SizeKt.wrapContentSize$default
OffsetKt.offset(Modifier; Function1)          // lambda 版 offset
SizeKt."width-3ABfNKs"(Modifier; F)           // width(Dp)
```
与派单一致。**差异机制**：TabRow 测量 indicator 时传**固定宽度约束**（整行宽）；
`Modifier.width(dp)` 默认 `enforceIncoming = true`，内部 `constrainWidth` 会把请求宽度
夹到[min=整行宽, max=整行宽] ⇒ 自定义宽度失效、indicator 横贯整行。
官方链里的 `wrapContentSize(Alignment.BottomStart)` 测量子内容时用**宽松 Constraints()**
（unbounded），把父约束"解开"，其后 `.offset{}` 平移、`.width(w)` 才真正生效。

## 改动 1：modifier 链补齐（RankRoute.kt indicator 块）
`TabRowDefaults.SecondaryIndicator(Modifier.fillMaxWidth().wrapContentSize(Alignment.BottomStart).offset { IntOffset(leftDp.roundToPx(), 0) }.width(rightDp - leftDp))`
—— 与官方同构，但 offset/width 用自定义插值（保留跟手）。

## 改动 2：连续页码插值（修反向滑动跳变）
旧：`fraction = currentPageOffsetFraction.coerceIn(0f, 1f)`。`currentPageOffsetFraction` 真实范围
**[-0.5, +0.5]**（currentPage=最近页）：反向滑（如 1→0）时 fraction 为**负**，被 coerceIn 夹成 0
⇒ 走"静止分支"，indicator 钉在原 tab 上不动，直到 currentPage 翻成 0 才从 0.5 处继续 ⇒ **跳变**。
新：`continuous = (currentPage + currentPageOffsetFraction).coerceIn(0, lastIndex)`，
lo=floor、hi=lo+1、t=continuous-lo，在 tabPositions[lo]/[hi] 间插值 ⇒ 连续量无方向性，双向跟手。
hi==lo（端点）时 t=0，结果即端点位置，安全。

## import 处理
- 新增 `androidx.compose.foundation.layout.wrapContentSize`
- 删 `androidx.compose.ui.platform.LocalDensity`（offset lambda 自带 Density 接收者，grep 确认无其它使用）
- 删 `androidx.compose.material3.TabRowDefaults.tabIndicatorOffset`（grep 确认无其它使用，仅注释提及）
- `TabRowDefaults` 本体保留；Alignment/fillMaxWidth/offset/width/IntOffset/Dp 均已有

## 未触碰（硬约束）
- `snapshotFlow { pagerState.settledPage } → selectTab` 原样保留（并有文件级测试锁）
- selectedTabIndex / Tab(selected) / RankRow / medalColor 零改动（diff 仅 indicator 块 + import）

## 测试（RankTabIndicatorTest.kt，源码文本断言，6 用例）
取区间法：`indexOf("indicator = {")` → 其后第一个「换行+12 空格+`},`」（TabRow 实参表收尾，
块内嵌套括号缩进更深不会误截断）。断言：
1. 区间含 `wrapContentSize(Alignment.BottomStart)`（核心）
2. `fillMaxWidth()` 在 wrapContentSize 之前
3. wrapContentSize 在 `.width(` 之前
4. 区间无 `currentPageOffsetFraction.coerceIn(0f, 1f)`（反向回归锁）
5. 区间含 `pagerState.currentPage + pagerState.currentPageOffsetFraction`（仍是插值方案）
6. 文件级：`snapshotFlow { pagerState.settledPage }` 仍在（硬约束 1 锁）

踩坑记录：首跑 1 红——生产代码**注释**里写的 `Modifier.width()` 字面量被断言 3 的 `.width(`
搜索命中；改注释措辞绕开（不改断言语义）。

## 验收
- 基线：178 用例 / 29 类 全绿 → 改后全量：**184 用例 / 30 类，failures=0 errors=0**（+6 为本棒新增）
- assembleDebug + testDebugUnitTest --rerun-tasks：BUILD SUCCESSFUL
- `git show --stat` 各 commit 仅含本棒独占文件（pathspec 限定提交，防并发裹挟）

## 变异验证
临时删除 `.wrapContentSize(Alignment.BottomStart)` 一行 → `RankTabIndicatorTest` **6 用例 3 红**
（恰为核心三条：indicatorUnwrapsParentConstraints / fillMaxWidthPrecedesWrapContentSize /
wrapContentSizePrecedesWidth）⇒ 断言"能真红"。
`git checkout -- RankRoute.kt` 还原（此时生产码已提交 1f0bc6c，checkout 安全）→ **6/6 绿**。

## 进展日志
- [x] 开工闸门 git diff --quiet exit=0
- [x] 基线 178/29 全绿
- [x] 根因复核（本机 javap 反编译 1.3.2 实证四步链）
- [x] 改 modifier 链（fillMaxWidth + wrapContentSize 补齐）
- [x] 改插值（连续页码，双向跟手）
- [x] import 清理（+wrapContentSize，-LocalDensity，-tabIndicatorOffset）
- [x] 写测试（6 用例）
- [x] 构建 + 全量单测 184/30 全绿
- [x] 变异验证（删 wrapContentSize→3 红；还原→6 绿）
- [x] 生产码 commit 1f0bc6c；测试 + 探针随后提交
