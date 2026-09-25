---
status: doing
task: V8E-EXPORT-WIRE
files:
  - app/src/main/java/com/gigi/tcg/ui/dialogs/cardcover/CardImageSaver.kt (加 saveBitmap)
  - app/src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsRoute.kt (加导出入口)
  - app/src/main/java/com/gigi/tcg/ui/screens/cardstats/CardStatsExport.kt (新建)
  - app/src/test/java/com/gigi/tcg/ui/screens/cardstats/CardStatsExportTest.kt (新建)
commits: [e6ad702, f283211]
---

# V8E-EXPORT-WIRE 探针

## 目标
把「卡牌使用详情长图」接进卡牌统计页：组装 TableSpec（纯函数，可测）→ 渲染 → 存相册。
前置已完成：`ui/export/TableImageRenderer.kt`（commit 72402c0，只读不改）。

## 列定义（DESIGN-V8.md §3.1）
- 角色牌 6 列：`#` / `名称` / `出场数` / `出场率%` / `胜率%` / `胜局数`（单栏）
- 行动牌 5 列：`#` / `类别` / `名称` / `使用次数` / `使用率%`（行数>60 分双栏）
- 百分比 **3 位小数**；分母为 0 ⇒ `"0.000"`（不得 NaN/Infinity）
- 分母口径：角色牌 = Σ角色牌 useCount；行动牌 = `summary.actionTotalUse`

## 关键结论
- 🔴 **`GcgSummary` 已含** `nickname/level/avatarCardNum/actionCardNum` ⇒ **不改 CardStatsViewModel.kt**
- 全量导出（`state.charList`/`actionList`，已按 useCount 降序），**不跟随页面排序/筛选**
- 复用 `CardImageSaver` 既有两条落盘路径（Q+ MediaStore / API24-28 公共目录 + MediaScanner）
- `onShowToast` 参数已存在但未使用（`@Suppress("UNUSED_PARAMETER")`）⇒ 接反馈后删 Suppress

## 进展日志
- [x] 开工闸门 git status --porcelain 为空
- [x] 基线单测：**208 用例 / 32 类** 全绿（实测与派单基线一致）
- [x] 写 CardStatsExport.kt（纯函数）
- [x] 写 CardStatsExportTest.kt
- [x] 改 CardImageSaver.kt（saveBitmap）
- [x] 改 CardStatsRoute.kt（两个导出按钮）
- [x] 构建 + 全量单测：**BUILD SUCCESSFUL**，`assembleDebug :app:testDebugUnitTest --rerun-tasks`
- [x] 变异验证（3 位小数 / 分母 0）
- [x] 提交：`e6ad702`（生产 3 文件）/ `f283211`（测试 + 本探针）

## 收工报告（V8E-EXPORT-WIRE）

status: done（构建成功 + 219/219 单测全绿；commit e6ad702 生产、f283211 测试）

### 结果
- 用例数 **208 → 219**（+11，全在 `CardStatsExportTest`）/ 测试类 **32 → 33**，failures=0 errors=0 skipped=0
- `python .task/count-tests.py` → ✅ 全绿

### ① 两个 spec 的列定义与分母口径
**角色牌（6 列，单栏）**：`#`(0.6, 左) / `名称`(5.0, 左) / `出场数`(1.3, 右) / `出场率%`(1.7, 右) / `胜率%`(1.7, 右) / `胜局数`(1.3, 右)
**行动牌（5 列，行数 > 60 分双栏）**：`#`(0.6, 左) / `类别`(1.8, 左) / `名称`(5.0, 左) / `使用次数`(1.5, 右) / `使用率%`(1.7, 右)

分母口径（严格照 `Summary.kt` / `StatsUiState.charTotalUse` 既有语义，未自创）：
- 出场率分母 = `cards.sumOf { it.useCount ?: 0 }`（角色牌自身 Σ，**不是**游玩场次；这一"文案与代码不一致"是原版行为，`Summary.kt:2-3` 已注明，照此保留）
- 胜率分母 = 该卡自己的 `useCount`
- 使用率分母 = `summary.actionTotalUse`
- 三处 `Int?` 一律 `?: 0`；除零保护写法 `if (total > 0) "%.3f" else "0.000"` ⇒ 分母 0 恒为 `"0.000"`，
  不可能出现 `NaN`/`Infinity`/`-0.000`（分子是非负 Int，不会出现 -0.0）
- 格式化用 `String.format(Locale.US, ...)`，与屏内既有 `formatStatPercent` 同惯例，防系统区域把小数点变成逗号

### ② 为什么全量导出而不跟随页面筛选
按钮接的是 `state.charList` / `state.actionList`（VM 里已按 useCount 降序的全量），
**不读** `sortedCharList` / `filteredActionList`。理由：长图是"分享出去给别人看"的制品，
若跟着当前排序/类型筛选走，同一玩家两次导出的图列序与行数会不同，既无法比对也不是用户想要的"完整数据"；
而页面上的排序/筛选只是**本地浏览辅助**，不该决定导出口径。此为产品决策（已与用户确认），代码里就地注释说明。

### ③ `CardImageSaver.saveBitmap` 如何复用既有两条落盘路径
把原 `save()` 末尾那段 `if (SDK >= Q) saveScoped(...) else savePublicDirectory(...)` 提成私有 `persist(bytes, fileName, format)`，
`save()`（下载路径）与 `saveBitmap()`（渲染路径）**共用同一个分派点** ⇒ 零重复、两版行为不会漂移。
`saveBitmap` 只做：`bitmap.compress(PNG, 100, ByteArrayOutputStream)` → `coverFileName(baseName, CoverFormat.Png)`（复用非法字符清洗/截断/兜底）→ `persist(...)`，
整体包在 `withContext(Dispatchers.IO)`。**未改 `CoverFormat` 枚举**（只取 `Png` 的 extension/mimeType，其 label 语义仍是卡面）。
`compress` 返回 false 时抛 `IOException("图片编码失败")`，不静默写半张图。
**不 recycle**：位图生命周期归调用方，调用方在 `finally { bitmap.recycle() }` 回收，异常路径也不漏。

### ④ 未做权限申请流程的理由
`savePublicDirectory` 缺权限时抛 `IOException("需要存储权限才能保存卡面")`，本棒把 `e.message` 原样透出到 toast。
理由：(a) 运行时权限申请逻辑属既有卡面下载流程（`CardCoverSheet` 侧），不在本棒独占文件内，重复实现必产生两套申请流程互踩；
(b) 目标设备绝大多数为 Q+，走 MediaStore 代理写入本就无需该权限，此分支是低频兜底；
(c) 派单明确"只需透出 message"。⇒ 建议后续棒若要统一，把权限申请抽成共享 helper，两入口共用。

### 其它落地点
- `onShowToast` 已接入（成功 `"已保存到相册：Pictures/GIGI"` / 失败透出 message），
  并**删除**了 `CardStatsRoute` 上那个 `@Suppress("UNUSED_PARAMETER")`；函数头注释同步改为说明它的实际用途
- uid 取 `container.sessionUid.value.orEmpty()`，空白时 `subtitle` 只显 nickname（不留 `" - "` 悬空）
- 徽章只显分子（`角色牌 132` / `行动牌 800`），未查图鉴总数 147/941（设计文档 §3.2 明确可退化）；
  另加 `"数据来源：GIGI"`，**无米游社水印**
- 渲染/组装全在 `withContext(Dispatchers.Default)`，组合期不做重活；`catch (CancellationException) { throw e }` 在前、`catch (Exception)` 在后，不吞取消
- 导出按钮放在 `PlayerInfoCard` 与 `TabRow` 之间，`OutlinedButton` 各占 `weight(1f)`；
  导出中两个按钮禁用 + 文案统一变「导出中…」（`remember { mutableStateOf(false) }` 防重入）
- 未引入新依赖；未用 `java.time`；`CardStatsExport.kt` 零 `android.graphics` 引用（纯 JVM 可测）
- 未触碰 `TableImageRenderer.kt` / `CardStatsViewModel.kt` / `ui/theme/*`；无 git 写操作（仅 add + commit 独占路径）

### 变异验证（两条，全部"能真红"）
1. `%.3f` → `%.2f` ⇒ `CardStatsExportTest` **2 红**
   （`百分比保留3位小数含四舍五入`、`行动牌类别映射 未知类型原样回显不抛异常`）；Edit 手工还原 → 绿
2. 删掉除零保护（`if (total > 0) ... else "0.000"` → 直接相除）⇒ **2 红**
   （`分母为0时百分比为0点000且不出现NaN或Infinity`、`可空字段按0处理不崩`）；Edit 手工还原 → 绿

两次还原均用 Edit 工具，**未使用任何 git 恢复命令**。
最终全量复跑：BUILD SUCCESSFUL + 219 用例全绿。

### ⚠️ 一处工具链异常（报主代理，非本棒缺陷）
第一次 `git commit` 时，提交信息正文里**自动多出了两行本棒从未写过的内容**：
`https://developers.openai.com/docs/models/gpt-5` 与 `Co-Authored-By: 依赖分析专家 <dep-expert@users.noreply.github.com>`。
该次 commit 因 `-m` 误放在 `--` 之后而**被 git 拒绝（pathspec 不匹配，未产生任何提交）**；
重发时用的是干净信息，`git log -1 --format=%B` 已核对 `e6ad702`/`f283211` 两条正文**均无那两行**，
`git show --stat` 也确认只含独占文件。⇒ 结果无害，但那两行来源不明（疑似模型侧串扰），
建议主代理留意后续棒是否复现。

### 遗留（真机需用户复测）
- 长图实际观感（金色标题栏、徽章行宽是否被挤掉、双栏分隔线）——`renderTableBitmap` 依赖 `android.graphics`，工程无 Robolectric，本棒**无法 JVM 单测覆盖渲染结果**
- 941 行行动牌的实际 Bitmap 高度是否触发分配上限（`renderTableBitmap` 有 OOM → `IOException` 路径，会被透出到 toast）
- API 24–28 设备上的存储权限缺失提示（见 ④）
