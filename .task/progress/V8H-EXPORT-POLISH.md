---
status: done
task: V8H-EXPORT-POLISH
files:
  - app/src/main/java/com/gigi/tcg/ui/export/TableImageRenderer.kt
  - app/src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsExport.kt
  - app/src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsRoute.kt
  - app/src/test/java/com/gigi/tcg/ui/export/TableLayoutTest.kt
  - app/src/test/java/com/gigi/tcg/ui/screens/cardstats/CardStatsExportTest.kt
commits: [663c342, c7cba37]
---

# V8H-EXPORT-POLISH 探针

## 用户反馈（真机）
1. 导出图片中**文本被省略非常严重** → 要增加各文本宽度和间距
2. 应用内导出按钮**上下间距应一致**
3. **点一个按钮禁用两个不合理** → 需重新设计交互

## 已确认决策
- 按钮 → **各自独立状态**（底层 Mutex 串行，避免同时两张巨图）
- 图宽 → **1600px**

## 根因复核（本棒独立复算，与派单一致）
旧参数下行动牌 941 行 ⇒ 双栏 ⇒ 带宽 540px，MIN=64/pad=12/表头 32px：
名称 167 / 类别 101 / 使用次数 95 / 使用率% 101（列宽），使用次数/使用率% 表头需 128px > 可用宽 ⇒ 表头截断；名称可用 143px 仅容 4 中文字。**根因确为带宽不足 + MIN/padding 吃列**，ellipsize 二分逻辑本身无错。

## 🔴 本棒对派单的两处按约束修正（约束优先原则授权）

### 修正 1：行动牌权重微调（派单值不满足约束 1）
保守字宽模型（中文=1.0×字号，其余=0.55×字号，表头 26px）下派单权重
`1.0/1.6/3.9/1.9/1.8`（带 800px）实测：「使用次数」表头需 104px、可用仅 111px ⇒ 余量 **6.7% < 8%** ❌。
微调为 **`#`=0.9 / `类别`=1.5 / `名称`=3.8 / `使用次数`=1.9 / `使用率%`=1.9**（总权重 10.0），全约束通过（实测表见下）。

### 修正 2：角色牌恒单栏（新增 EXPORT_CHAR_TWO_COLUMN_THRESHOLD = Int.MAX_VALUE）
派单要求角色牌权重按"单栏 1600px"验算，但 `EXPORT_TWO_COLUMN_THRESHOLD=60` 同样作用于角色牌 ⇒
147 行会折成双栏每栏 800px。**6 列在 800px 内按约束无解**：slack = 800−6×40 = 560px，
而约束下限合计 ≈ 671px（# 46 + 名称 224 + 出场数 92.4 + 出场率 107.8 + 胜率 107.8 + 胜局数 92.4）⇒ 必截断。
⇒ 角色牌恒单栏 1600px（147 行高 9692px，ARGB ≈ 59MB，可控）；行动牌阈值 60 不变。
Route 经 `exportTwoColumnThreshold(charTable)` 分发；单测锁「角色牌恒单栏 + 列宽和==1600」。

## 任务 E 实测表（.task/tmp/verify_v8h.py，复现 distributeColumnWidths，不提交）
可用宽 = 列宽 − 2×20；表头余量 = (可用−需求)/需求；中文字数 = ⌊可用/28⌋。

### 行动牌（941 行 ⇒ 双栏，每栏 800px）
| 列 | 列宽 | 可用 | 表头需求 | 表头余量 | 可容中文字 | 判定 |
| --- | --- | --- | --- | --- | --- | --- |
| `#` | 94 | 54 | 14.3 | +277% | 1（数字 3 位=46.2 ✓） | ✅ |
| `类别` | 130 | 90 | 52.0 | +73% | 3（装备牌 84 ≤ 90 ✓） | ✅ |
| `名称` | 268 | 228 | 52.0 | +338% | **8** ✓ | ✅ |
| `使用次数` | 154 | 114 | 104.0 | **+9.6%** ≥8% ✓ | 6 位数字 92.4 ✓ | ✅ |
| `使用率%` | 154 | 114 | 92.3 | +23.5% | 6 位 ✓、`100.000`(107.8) ✓ | ✅ |
列宽之和 94+130+268+154+154 = **800 == 图宽/2 精确** ✅（末列吸收舍入）

### 角色牌（147 行 ⇒ 单栏 1600px，权重 1.0/5.0/1.3/1.7/1.6/1.3）
| 列 | 列宽 | 可用 | 表头需求 | 表头余量 | 可容中文字 | 判定 |
| --- | --- | --- | --- | --- | --- | --- |
| `#` | 154 | 114 | 14.3 | +697% | 3 位数字 ✓ | ✅ |
| `名称` | 611 | 571 | 52.0 | +998% | **20** ≥8 ✓ | ✅ |
| `出场数` | 188 | 148 | 78.0 | +90% | 6 位 ✓ | ✅ |
| `出场率%` | 234 | 194 | 92.3 | +110% | 6 位 + 100.000 ✓ | ✅ |
| `胜率%` | 222 | 182 | 66.3 | +175% | ✓ | ✅ |
| `胜局数` | 191 | 151 | 78.0 | +94% | ✓ | ✅ |
列宽之和 = **1600 == 图宽 精确** ✅

**6 条约束全部满足**（表头 ≥8% 余量 / 名称 ≥8 字 / 装备牌 / # 3 位 / 数值列 6 位 / 列宽和精确）。
旧 1080px 下该脚本立即报红（类别 51px < 56.2 需求、名称仅 4 字）⇒ 修复有效性的对照组。

## 内存复核与阈值
- 角色牌 147 行单栏：高 172+64+147×64+48 = **9692px**，1600×9692×4B ≈ **59MB** ≤64MB ⇒ 保持 ARGB_8888 ✅
- 行动牌 941 行双栏：高 172+64+471×64+48 = **30428px**，ARGB ≈186MB ⇒ 降级 RGB_565 ≈ **92.9MB**
- **渲染前预算闸**：`checkRenderMemoryBudget`，阈值 **100MB**（`MAX_RENDERABLE_MEMORY_BYTES`）。
  理由：最大真实用例 941 行 = 92.9MB 必须放行，否则本次修复对自己要支持的场景反而报错；
  再大（约 1030+ 行）在低端机（堆上限 192/512MB，且需预留渲染峰值）必 OOM ⇒ 提前抛
  IOException("内容过多，无法导出（约需 N MB 显存，超出安全上限）"）。OOM→IOException 兜底保留。

## 任务 C（按钮独立状态）
- `exportingChar` / `exportingAction` 两个独立标志；点哪个只转哪个、只禁哪个。
- 渲染+保存包 `exportMutex.withLock{}`（kotlinx.coroutines.sync.Mutex，无新依赖）⇒ 串行，不并现两张巨图。
- `catch(CancellationException) throw e` 在前；`finally` 复位**自己的**标志；`bitmap.recycle()` 在 saveBitmap 的 finally 内。
- 成功/失败各自 onShowToast（谁完成谁提示）。

## 任务 D（按钮行上下间距）推导与取值
按钮 40dp / TabRow 48dp，文字均垂直居中（同一文字高 h）：
- 上方视觉间距（Card 边缘→按钮文字）= g_top + (40−h)/2
- 下方视觉间距（按钮文字→Tab 文字）= g_bottom + (48−h)/2
等值 12/12 时下方视觉大 4dp ⇒ 取 **g_top=14dp / g_bottom=10dp**（差 4dp 抵消高度差 8dp 的一半），
两侧视觉间距相等（24−h/2）。实现：外层 Column 去掉 `spacedBy(12)`，按钮 Row 显式
`padding(top=14.dp, bottom=10.dp)`，TabRow→内容列表补 `padding(top=12.dp)` 维持原间距。
自检：40/48 与居中前提在 M3 默认尺寸下成立（OutlinedButton MinHeight 40dp、TabRow PreferredHeight 48dp）。

## 变异验证（改回旧值必须红）
| 变异 | 结果 |
| --- | --- |
| `EXPORT_IMAGE_WIDTH_PX` 1600→1080 | **9 红**（含「全部表头≥8%余量」「行动牌双栏名称≥8字」「列宽和」等新增断言 + TableLayoutTest 5 条宽度断言），还原后全绿 |
| `CELL_PADDING_PX` 20→12 | `sizeConstantsArePinned` **红**（可用宽反而变大 ⇒ 证明常量锁是唯一防线，语义正确） |
| `MIN_COLUMN_WIDTH_PX` 40→64 | `sizeConstantsArePinned` **红** + 「行动牌双栏名称≥8字」**红**（名称列被 MIN 挤到 <224） |
还原一律 Edit 手工完成，未用任何 git 写操作。

## 验收
- `assembleDebug + testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL，**33 类 / 225 用例 / 0 失败 0 错误**（基线 219，+6）
- 生产 commit / 测试 commit / 探针 commit 均 pathspec 限定独占文件

## 进展日志
- [x] 开工闸门 git status --porcelain 为空
- [x] 根因复核（旧参数手算与派单一致）
- [x] 改渲染器常量（A）+ 内存预算闸
- [x] 改图宽与权重（B，含两处按约束修正）
- [x] 校验脚本（E）6 约束全过，实测表见上
- [x] 按钮独立状态 + Mutex（C）
- [x] 按钮上下视觉间距 14/10（D）
- [x] 单测更新/新增（F）
- [x] 构建 + 全量单测 225/225 绿
- [x] 变异验证三组（1080 / pad12 / MIN64）均红并还原
- [x] 提交（663c342 生产 / c7cba37 测试 / 本探针 commit）
