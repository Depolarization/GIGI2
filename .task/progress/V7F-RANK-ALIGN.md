# V7F-RANK-ALIGN — 排行榜行的排名/信息语义分隔

status: doing

## 开工
- 改前闸门：`git diff --quiet -- RankRoute.kt` → 退出码 0，GATE_OK 放行。
- 目标文件已读：RankRow 位于 RankRoute.kt:233-279，现状与派单一致
  （spacedBy(4.dp) / widthIn(min=28.dp) / padding(start=8.dp)）。
- grep 确认：`Arrangement` 全文件仅 line 253 一处使用、`widthIn` 仅 line 260 一处
  ⇒ 改后两行 import（line 11、23）都要删；`width` import 已有（TabRow indicator 在用）。
- V7A 的 TabRow/indicator 段（line 104-133）与 settledPage→selectTab 段（line 97-101）**不动**。

## 改边距/槽位（已落码）
- 根因1（spacedBy 4dp 粘连）→ 删 `horizontalArrangement = Arrangement.spacedBy(4.dp)`，
  改显式 `Spacer(Modifier.width(16.dp))`（排名↔头像 = 行左 padding 16dp）。
- 根因2（槽位随位数浮动）→ `widthIn(min = 28.dp)` → `width(32.dp)` 固定槽位，TextAlign.Center 不变。
- 根因3（16sp 平级混淆）→ 主要靠间距层次消除（16dp 跨组 > 12dp 同组）；
  头像↔信息列 `padding(start = 8.dp)` → `12.dp`（原 4+8=12 视觉不变，显式化）。
- import：删 `Arrangement`、`widthIn`（grep 确认全文件仅此一处使用），加 `Spacer`。
- 可选加分项 FontWeight.Bold：**未做**——派单明示"以间距为主因、拿不准就不加"，
  金色 16sp 加粗在深色背景上有过亮风险，且会改变前三名奖牌数字观感，保守放弃。
- 基线记录：开工时 count-tests.py = 174 用例（28 类），failures=0 errors=0。
- 新建 RankRowSpacingTest.kt：4 条源码文本断言（无 spacedBy / 有 16dp Spacer / 固定 32dp 槽 / start=12dp）。

## 构建 + 全量单测
- `JAVA_HOME=.../ms-21.0.12.1 ./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL。
- count-tests.py：29 类 / **178 用例**（基线 174 + 本棒新增 4），failures=0 errors=0 ✅。
- 未遇到 mergeExtDexDebug / R.jar 类并发失败，无需重跑。

## 变异验证（断言"能真红"）
- 临时把 `Spacer(Modifier.width(16.dp))` → `Spacer(Modifier.width(4.dp))`，
  跑 `:app:testDebugUnitTest --tests RankRowSpacingTest --rerun-tasks` →
  **rankToAvatarGapIsExplicit16dpSpacer FAILED（AssertionError at RankRowSpacingTest.kt:43），BUILD FAILED** ✅ 红。
- 还原 16.dp 后全量复跑 → BUILD SUCCESSFUL，178 用例全绿 ✅ 绿。

## 为什么必须用显式 Spacer 而不是 spacedBy
`horizontalArrangement = spacedBy(n)` 对 Row 的**所有相邻子元素施加同一间距**，
无法表达 M3 的层次语义：排名↔头像属于**跨语义组**（16dp，且与行左 padding 相等 ⇒ 排名单元左右留白对称，
正是用户"边距应该相同"的诉求），头像↔昵称/积分列属于**同组内**（12dp）。
只有显式 Spacer 能对这两段分别赋值；固定 `width(32.dp)` 槽位再保证间距不随位数浮动、信息列左边缘恒定对齐。

## 间距对照（前 → 后）
| 段 | 前 | 后 |
| --- | --- | --- |
| 行水平 padding | 16dp | 16dp（不变） |
| 排名槽位 | widthIn(min=28dp)，随位数浮动 | width(32dp) 固定 |
| 排名 ↔ 头像 | 4dp(spacedBy)+居中余量 ⇒ 10~14dp 浮动 | **16dp 恒定** |
| 头像 ↔ 信息列 | 4+8=12dp | **12dp**（显式 padding(start=12.dp)） |

## 提交
- `b585199` style: separate rank badge from player info with M3 spacing（RankRoute.kt）
- `af11235` test: lock rank row spacing against regressions (V7F)（RankRowSpacingTest.kt）
- 探针本笔（注：探针文件早期版本被并发棒 V7E 的 `676ab08` 误裹入库，非本棒 git add -A）
- V7A 的 TabRow/indicator 段与 settledPage→selectTab 段零改动；medalColor 取色逻辑未动；AppGate.kt 未碰。

status: done
